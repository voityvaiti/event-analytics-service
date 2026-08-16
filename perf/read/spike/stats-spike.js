// How the read path behaves when dashboard traffic suddenly steps far above
// what the pool can serve, and whether it recovers afterwards. The write side's
// spike-events.js counterpart, and it borrows that file's reasoning about why
// the executor is arrival-rate rather than VU-based.
//
// One surge test covers the read path rather than one per endpoint: what a
// spike measures is the service's behaviour when requests outrun the pool, and
// that is a property of the pool and the queue rather than of a query shape.
// ENDPOINT points it at whichever query is worth surging; the default is the
// heaviest one, because that is where the ceiling is lowest.
//
// The window size is pinned while the position keeps moving. A spike changes
// one variable — the arrival rate — and mixing window sizes into it would vary
// the cost of each request at the same time.

import exec from 'k6/execution';
import { check } from 'k6';
import { getStats } from '../../lib/k6-stats.js';
import { generateQuery } from '../../lib/query-generator.js';
import { metric } from '../../lib/k6-summary.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SUMMARY_OUT = __ENV.SUMMARY_OUT || 'perf/read/spike/last-summary.json';

const SCENARIO = 'stats-spike';

const ENDPOINT = __ENV.ENDPOINT || 'active-users';
const GROUP_BY = __ENV.GROUP_BY || 'day';
const WINDOW = __ENV.SPIKE_WINDOW || '1d';

const CORPUS_ANCHOR = Date.parse(__ENV.SEED_ANCHOR || '2026-01-01T00:00:00Z');
const CORPUS_DAYS = Number(__ENV.SEED_SPREAD_DAYS || 180);
const CORPUS = {
  startMillis: CORPUS_ANCHOR,
  endMillis: CORPUS_ANCHOR + CORPUS_DAYS * 86400000,
};

// Reads are answered in tens to hundreds of milliseconds, not the ~2ms an
// insert takes, so the pool saturates at request rates two orders of magnitude
// below the write spike's. SPIKE_RATE must clear the read ceiling — roughly
// pool size divided by query latency — or there is no surge to observe.
const BASELINE_RATE = Number(__ENV.BASELINE_RATE || 20);
const SPIKE_RATE = Number(__ENV.SPIKE_RATE || 400);

const BASELINE_SECONDS = Number(__ENV.BASELINE_SECONDS || 20);
const SPIKE_SECONDS = Number(__ENV.SPIKE_SECONDS || 30);
const RECOVERY_SECONDS = Number(__ENV.RECOVERY_SECONDS || 30);

const MAX_VUS = Number(__ENV.MAX_VUS || 500);

const arrival = (rate, duration, startTime) => ({
  executor: 'constant-arrival-rate',
  rate,
  timeUnit: '1s',
  duration,
  startTime,
  preAllocatedVUs: Math.min(MAX_VUS, 100),
  maxVUs: MAX_VUS,
});

// Only recovery is gated. A surge is allowed to shed; what must hold is that
// the read path serves cleanly again once it passes. The rest are non-failing
// thresholds that exist to materialise the per-phase sub-metrics handleSummary
// reads — a phase whose http_reqs is not named here reports no request count at
// all, which is how baseline_achieved_rps journalled as null until this file
// listed all three.
export const options = {
  scenarios: {
    baseline: arrival(BASELINE_RATE, `${BASELINE_SECONDS}s`, '0s'),
    spike: arrival(SPIKE_RATE, `${SPIKE_SECONDS}s`, `${BASELINE_SECONDS}s`),
    recovery: arrival(
      BASELINE_RATE,
      `${RECOVERY_SECONDS}s`,
      `${BASELINE_SECONDS + SPIKE_SECONDS}s`,
    ),
  },
  thresholds: {
    'http_req_failed{scenario:recovery}': ['rate<0.01'],
    'http_req_failed{scenario:baseline}': ['rate<=1'],
    'http_req_failed{scenario:spike}': ['rate<=1'],
    'http_req_duration{scenario:baseline}': ['p(95)>=0'],
    'http_req_duration{scenario:spike}': ['p(95)>=0'],
    'http_req_duration{scenario:recovery}': ['p(95)>=0'],
    'http_reqs{scenario:baseline}': ['count>=0'],
    'http_reqs{scenario:spike}': ['count>=0'],
    'http_reqs{scenario:recovery}': ['count>=0'],
    'dropped_iterations{scenario:spike}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

// Every phase walks the same window positions on purpose, unlike the write
// spike where identical sequence numbers would mean identical rows. Here it is
// what makes baseline and recovery comparable: they ask the same questions, so
// a difference between them is the surge's aftermath and not a different query.
export default function () {
  const query = generateQuery(exec.scenario.iterationInTest, CORPUS, WINDOW);

  const params = { from: query.from, to: query.to };
  if (GROUP_BY) {
    params.groupBy = GROUP_BY;
  }

  const response = getStats(BASE_URL, ENDPOINT, params);
  check(response, { 'status is 200': (r) => r.status === 200 });
}

function phase(data, scenario, seconds) {
  const tag = (name) => `${name}{scenario:${scenario}}`;
  const requests = metric(data, tag('http_reqs'), 'count');
  return {
    achieved_rps: requests / seconds,
    requests,
    failed_rate: metric(data, tag('http_req_failed'), 'rate'),
    dropped: metric(data, tag('dropped_iterations'), 'count'),
    p95_ms: metric(data, tag('http_req_duration'), 'p(95)'),
    p99_ms: metric(data, tag('http_req_duration'), 'p(99)'),
    max_ms: metric(data, tag('http_req_duration'), 'max'),
  };
}

export function handleSummary(data) {
  const baseline = phase(data, 'baseline', BASELINE_SECONDS);
  const spike = phase(data, 'spike', SPIKE_SECONDS);
  const recovery = phase(data, 'recovery', RECOVERY_SECONDS);

  const summary = {
    scenario: SCENARIO,
    run_id: RUN_ID,
    base_url: BASE_URL,
    endpoint: ENDPOINT,
    group_by: GROUP_BY,
    window: WINDOW,
    baseline_rate: BASELINE_RATE,
    spike_rate: SPIKE_RATE,
    max_vus: MAX_VUS,
    seconds: { baseline: BASELINE_SECONDS, spike: SPIKE_SECONDS, recovery: RECOVERY_SECONDS },
    phases: { baseline, spike, recovery },
  };

  const num = (v, decimals) => (Number.isFinite(v) ? v.toFixed(decimals) : 'n/a');
  const line = (label, value) => `  ${label.padEnd(22)} ${value}`;
  const text = [
    '',
    `stats read spike  ${ENDPOINT} ${WINDOW}  (run ${RUN_ID})`,
    line('baseline → spike rps', `${BASELINE_RATE} → ${SPIKE_RATE}`),
    line('spike achieved rps', num(spike.achieved_rps, 0)),
    line('spike dropped', num(spike.dropped, 0)),
    line('spike failed', `${num(spike.failed_rate * 100, 2)} %`),
    line('spike p95', `${num(spike.p95_ms, 1)} ms`),
    line('spike p99', `${num(spike.p99_ms, 1)} ms`),
    line('recovery failed', `${num(recovery.failed_rate * 100, 2)} %`),
    line('recovery p95', `${num(recovery.p95_ms, 1)} ms  (baseline ${num(baseline.p95_ms, 1)})`),
    '',
  ].join('\n');

  return {
    stdout: text,
    [SUMMARY_OUT]: JSON.stringify(summary, null, 2),
  };
}
