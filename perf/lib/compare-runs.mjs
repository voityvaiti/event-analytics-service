/**
 * Compares two sets of k6 load-cell summaries (main vs PR branch) and renders a
 * Markdown verdict covering every cell that ran. Both sides are expected to have
 * been measured back-to-back on the same runner — this script only interprets the
 * numbers, it does not control how they were produced.
 *
 * Usage: node compare-runs.mjs <main-dir> <pr-dir>
 *
 * Each directory holds `*.json` summaries in the shape the load scripts emit from
 * `handleSummary`. They are grouped by the cell that produced them, using fields
 * the summary already carries — `scenario`, plus `group_by` where one scenario
 * runs several shapes — so the caller needs no filename convention beyond writing
 * each summary somewhere under its own side. Per cell the median across rounds is
 * taken, so an unlucky single round does not decide anything.
 *
 * A delta inside the noise band (NOISE_PERCENT, default 10) is reported as "within
 * noise" rather than a win or a regression: GitHub-hosted runners are too noisy to
 * trust small differences, and a CI-sized corpus makes the read cells noisier
 * still than the fixed-rig floor in perf/README.md.
 *
 * Only throughput and the overall p95 carry a verdict at all. The p99 and the
 * per-window rows show their delta but are left unjudged, because they are decided
 * by too few samples to survive a short run: measured over two runs of an identical
 * jar, batch p99 swung 45% and the narrowest read window 10%, both of which would
 * have been announced as improvements. They are kept because a real regression
 * usually shows there first — as a number to look at, not a verdict to trust.
 *
 * Throughput counts every request, failures included — a broken app returns errors
 * faster than it does real work, so a high failure rate inflates throughput and
 * would otherwise read as "better". When either side's failure rate reaches
 * FAILED_THRESHOLD (default 0.01, matching the k6 threshold), a side produced no
 * summary at all, or the two sides did not run the same set of cells, the
 * comparison is declared invalid: verdicts are withheld and the process exits
 * non-zero so the workflow fails loudly instead of posting a misleading win.
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const NOISE_PERCENT = Number(process.env.NOISE_PERCENT || 10);
const FAILED_THRESHOLD = Number(process.env.FAILED_THRESHOLD || 0.01);
const MARKER = '<!-- perf-compare -->';

/** Every `*.json` under `dir`, one level of subdirectories included. */
function loadSummaries(dir) {
  const files = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      files.push(...readdirSync(path).filter((n) => n.endsWith('.json')).map((n) => join(path, n)));
    } else if (entry.endsWith('.json')) {
      files.push(path);
    }
  }
  return files.map((path) => JSON.parse(readFileSync(path, 'utf8')));
}

/**
 * Names the cell a summary came from. `scenario` alone is not enough: one read
 * scenario runs three groupings, and pooling them would report the gap between
 * query plans as if it were jitter within one.
 */
function cellKey(summary) {
  return summary.group_by ? `${summary.scenario} groupBy=${summary.group_by}` : summary.scenario;
}

function groupByCell(summaries) {
  const cells = new Map();
  for (const summary of summaries) {
    const key = cellKey(summary);
    if (!cells.has(key)) {
      cells.set(key, []);
    }
    cells.get(key).push(summary);
  }
  return cells;
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

/** The windows a read cell swept, in the order its summaries list them. */
function windowNames(summaries) {
  const first = summaries.find((s) => s.windows);
  return first ? Object.keys(first.windows) : [];
}

function aggregate(summaries) {
  const windows = {};
  for (const window of windowNames(summaries)) {
    const values = summaries
      .filter((s) => s.windows?.[window])
      .map((s) => s.windows[window].p95_ms);
    if (values.length) {
      windows[window] = median(values);
    }
  }
  return {
    runs: summaries.length,
    throughput: median(summaries.map((s) => s.throughput_rps)),
    p95: median(summaries.map((s) => s.latency_ms.p95)),
    p99: median(summaries.map((s) => s.latency_ms.p99)),
    failed: summaries.length ? Math.max(...summaries.map((s) => s.failed_rate)) : NaN,
    windows,
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
 * Renders one metric row. When `trustworthy` is false (an invalid run) the delta
 * and verdict are withheld as "—" so a broken side can never read as a win, while
 * the raw numbers are still shown for context.
 */
function row(name, mainValue, prValue, unit, higherIsBetter, decimals, trustworthy, judged = true) {
  const cells = [name, value(mainValue, decimals, unit), value(prValue, decimals, unit)];
  if (trustworthy && Number.isFinite(mainValue) && Number.isFinite(prValue)) {
    const { deltaPercent, label } = verdict(mainValue, prValue, higherIsBetter);
    const sign = deltaPercent >= 0 ? '+' : '';
    cells.push(`${sign}${deltaPercent.toFixed(1)}%`, judged ? label : '·');
  } else {
    cells.push('—', '—');
  }
  return `| ${cells.join(' | ')} |`;
}

function cellSection(name, main, pr, trustworthy) {
  const lines = [
    `### ${name}`,
    '',
    '| Metric | main | PR | Δ | |',
    '|--------|------|-----|---|---|',
    row('throughput', main.throughput, pr.throughput, ' req/s', true, 0, trustworthy),
    row('latency p95', main.p95, pr.p95, ' ms', false, 2, trustworthy),
    row('latency p99', main.p99, pr.p99, ' ms', false, 2, trustworthy, false),
  ];
  // Per-window rows only for the read cells, which sweep several window sizes in
  // one run: a single p95 over that mix has its median describing hour-long
  // questions and its tail describing month-long ones. Unjudged, like p99 — the
  // narrowest window answers in around a millisecond, where rounding alone is
  // worth a few percent.
  for (const window of Object.keys(main.windows)) {
    lines.push(
      row(`p95 (${window})`, main.windows[window], pr.windows[window], ' ms', false, 2, trustworthy, false),
    );
  }
  lines.push('', `Failed requests — main: ${percent(main.failed)}, PR: ${percent(pr.failed)}.`, '');
  return lines;
}

function render(mainCells, prCells) {
  const names = [...new Set([...mainCells.keys(), ...prCells.keys()])].sort();
  const mismatched = names.filter((name) => !mainCells.has(name) || !prCells.has(name));
  const broken = names.filter((name) => {
    const main = mainCells.get(name);
    const pr = prCells.get(name);
    return (
      (main && !(main.failed < FAILED_THRESHOLD)) || (pr && !(pr.failed < FAILED_THRESHOLD))
    );
  });
  const invalid = names.length === 0 || mismatched.length > 0 || broken.length > 0;

  const lines = [MARKER, '## Performance comparison — load cells', ''];

  if (names.length === 0) {
    lines.push('> ❌ **No summaries found on either side.**', '');
    return { text: lines.join('\n'), invalid: true };
  }

  if (mismatched.length > 0) {
    lines.push(
      `> ❌ **Comparison invalid — verdicts withheld.** These cells ran on only one side: ` +
        `${mismatched.join(', ')}. A cell missing from a side did not run, which is a broken ` +
        `measurement rather than a fast one.`,
      '',
    );
  }
  if (broken.length > 0) {
    lines.push(
      `> ❌ **Comparison invalid — verdicts withheld.** These cells failed too many requests ` +
        `(threshold ${percent(FAILED_THRESHOLD)}): ${broken.join(', ')}. A failing app returns ` +
        `errors faster than it does real work, so throughput is not a meaningful signal there. ` +
        `Fix the failures and re-run before trusting any number below.`,
      '',
    );
  }

  const rounds = median([...mainCells.values()].map((cell) => cell.runs));
  lines.push(
    `Median of ${rounds} round(s) per branch per cell, measured back-to-back on the same ` +
      `runner. Deltas within ±${NOISE_PERCENT}% are treated as noise — runner jitter dominates, ` +
      `and the read cells run against a CI-sized corpus rather than the 20M-row one the journal ` +
      `under \`perf/\` is measured on. Read these against each other, never against a journal row.`,
    '',
    `Rows marked \`·\` are shown without a verdict: p99 and the per-window figures rest on too ` +
      `few samples to judge over a run this short. A delta there is worth looking at, not worth ` +
      `believing on its own.`,
    '',
  );

  for (const name of names) {
    const main = mainCells.get(name);
    const pr = prCells.get(name);
    if (!main || !pr) {
      continue;
    }
    lines.push(...cellSection(name, main, pr, !invalid));
  }

  return { text: lines.join('\n'), invalid };
}

function aggregateCells(dir) {
  const cells = new Map();
  for (const [name, summaries] of groupByCell(loadSummaries(dir))) {
    cells.set(name, aggregate(summaries));
  }
  return cells;
}

const [mainDir, prDir] = process.argv.slice(2);
if (!mainDir || !prDir) {
  process.stderr.write('usage: node compare-runs.mjs <main-dir> <pr-dir>\n');
  process.exit(1);
}

const { text, invalid } = render(aggregateCells(mainDir), aggregateCells(prDir));
process.stdout.write(text);
if (invalid) {
  process.exitCode = 1;
}
