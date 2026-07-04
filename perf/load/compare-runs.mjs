/**
 * Compares two sets of k6 ingest-throughput summaries (main vs PR branch) and
 * renders a Markdown verdict. Both sides are expected to have been measured
 * back-to-back on the same runner — this script only interprets the numbers,
 * it does not control how they were produced.
 *
 * Usage: node compare-runs.mjs <main-dir> <pr-dir>
 *
 * Each directory holds one or more `*.json` summaries in the shape emitted by
 * `ingest-events.js` (`handleSummary`). Per side the median across runs is
 * taken, so an unlucky single run does not decide the comparison. A delta
 * inside the noise band (NOISE_PERCENT, default 10) is reported as "within
 * noise" rather than a win or a regression — GitHub-hosted runners are too
 * noisy to trust small differences in a low-millisecond write path.
 *
 * Throughput counts every request, failures included — a broken app returns
 * errors faster than it does real work, so a high failure rate inflates
 * throughput and would otherwise read as "better". When either side's failure
 * rate reaches FAILED_THRESHOLD (default 0.01, matching the k6 threshold), or a
 * side produced no summary at all, the comparison is declared invalid: the
 * verdict is withheld and the process exits non-zero so the workflow fails
 * loudly instead of posting a misleading win.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const NOISE_PERCENT = Number(process.env.NOISE_PERCENT || 10);
const FAILED_THRESHOLD = Number(process.env.FAILED_THRESHOLD || 0.01);
const MARKER = '<!-- perf-compare -->';

function loadSummaries(dir) {
  return readdirSync(dir)
    .filter((name) => name.endsWith('.json'))
    .map((name) => JSON.parse(readFileSync(join(dir, name), 'utf8')));
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

function aggregate(summaries) {
  return {
    runs: summaries.length,
    throughput: median(summaries.map((s) => s.throughput_rps)),
    p95: median(summaries.map((s) => s.latency_ms.p95)),
    p99: median(summaries.map((s) => s.latency_ms.p99)),
    failed: summaries.length ? Math.max(...summaries.map((s) => s.failed_rate)) : NaN,
  };
}

/**
 * Returns the signed change of PR relative to main as a percentage. For
 * throughput a positive change is an improvement; for latency it is a
 * regression. `higherIsBetter` flips the verdict accordingly.
 */
function verdict(mainValue, prValue, higherIsBetter) {
  const deltaPercent = ((prValue - mainValue) / mainValue) * 100;
  if (Math.abs(deltaPercent) < NOISE_PERCENT) {
    return { deltaPercent, label: '≈ within noise' };
  }
  const improved = higherIsBetter ? deltaPercent > 0 : deltaPercent < 0;
  return { deltaPercent, label: improved ? '✅ better' : '⚠️ worse' };
}

function value(raw, decimals, unit) {
  return Number.isFinite(raw) ? `${raw.toFixed(decimals)}${unit}` : 'n/a';
}

function percent(raw) {
  return Number.isFinite(raw) ? `${(raw * 100).toFixed(2)}%` : 'n/a';
}

/**
 * Renders one metric row. When `trustworthy` is false (an invalid run) the
 * delta and verdict are withheld as "—" so a broken side can never read as a
 * win, while the raw numbers are still shown for context.
 */
function row(name, mainValue, prValue, unit, higherIsBetter, decimals, trustworthy) {
  const cells = [name, value(mainValue, decimals, unit), value(prValue, decimals, unit)];
  if (trustworthy && Number.isFinite(mainValue) && Number.isFinite(prValue)) {
    const { deltaPercent, label } = verdict(mainValue, prValue, higherIsBetter);
    const sign = deltaPercent >= 0 ? '+' : '';
    cells.push(`${sign}${deltaPercent.toFixed(1)}%`, label);
  } else {
    cells.push('—', '—');
  }
  return `| ${cells.join(' | ')} |`;
}

function render(main, pr) {
  const invalid =
    !(main.runs > 0) ||
    !(pr.runs > 0) ||
    !(main.failed < FAILED_THRESHOLD) ||
    !(pr.failed < FAILED_THRESHOLD);

  const lines = [MARKER, '## Performance comparison — ingest write throughput', ''];

  if (invalid) {
    lines.push(
      `> ❌ **Comparison invalid — verdict withheld.** Failed requests — main: ${percent(main.failed)}, ` +
        `PR: ${percent(pr.failed)} (threshold ${percent(FAILED_THRESHOLD)}). A failing app returns errors ` +
        `faster than it does real work, so throughput is not a meaningful signal here. Fix the failures ` +
        `and re-run before trusting any number below.`,
      '',
    );
  }

  lines.push(
    `Median of ${main.runs} run(s) per branch, measured back-to-back on the same runner. ` +
      `Deltas within ±${NOISE_PERCENT}% are treated as noise — runner jitter dominates a sub-millisecond write path.`,
    '',
    '| Metric | main | PR | Δ | |',
    '|--------|------|-----|---|---|',
    row('throughput', main.throughput, pr.throughput, ' req/s', true, 0, !invalid),
    row('latency p95', main.p95, pr.p95, ' ms', false, 2, !invalid),
    row('latency p99', main.p99, pr.p99, ' ms', false, 2, !invalid),
    '',
    `Failed requests — main: ${percent(main.failed)}, PR: ${percent(pr.failed)}.`,
    '',
  );

  return { text: lines.join('\n'), invalid };
}

const [mainDir, prDir] = process.argv.slice(2);
if (!mainDir || !prDir) {
  process.stderr.write('usage: node compare-runs.mjs <main-dir> <pr-dir>\n');
  process.exit(1);
}

const { text, invalid } = render(aggregate(loadSummaries(mainDir)), aggregate(loadSummaries(prDir)));
process.stdout.write(text);
if (invalid) {
  process.exitCode = 1;
}