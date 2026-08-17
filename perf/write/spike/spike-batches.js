// How the batch ingest path behaves when request rate suddenly steps far above
// steady-state capacity, and whether it recovers afterwards. The single-event
// spike-events.js counterpart, and it borrows that file's reasoning about why the
// executor is arrival-rate rather than VU-based.
//
// The surge is in requests per second at a fixed batch size, not in batch size at
// a fixed rate. Both would be a step up in events per second, but only the first
// is the same shock the single-event cell applies, and a cell that changed the
// unit of work mid-run would be measuring two things at once.

import exec from 'k6/execution';
import { check } from 'k6';
import { postEventBatch } from '../../lib/k6-ingest.js';
import { metric } from '../../lib/k6-summary.js';
import { BATCH_SPIKE_PHASE_SEQ_BASE } from '../../lib/seq-space.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SUMMARY_OUT = __ENV.SUMMARY_OUT || 'perf/write/spike/last-summary.json';

const SCENARIO = 'ingest-batch-spike';

// Events per request, held fixed across all three phases — see the header.
const BATCH_SIZE = Number(__ENV.BATCH_SIZE || 100);

// Requests-per-second targets, not virtual-user counts, for the reason
// spike-events.js gives. Both figures are far lower than that cell's because each
// request here carries BATCH_SIZE events: the surge is in events per second, and
// the rate that delivers it is smaller by that factor.
//
// Both are predictions until the batch load cell has rows — the cell's README
// carries the rule they come from and this file's defaults get replaced by the
// derived figures, the way the read spike cells' did.
const BASELINE_RATE = Number(__ENV.BASELINE_RATE || 50);
const SPIKE_RATE = Number(__ENV.SPIKE_RATE || 2000);

const BASELINE_SECONDS = Number(__ENV.BASELINE_SECONDS || 20);
const SPIKE_SECONDS = Number(__ENV.SPIKE_SECONDS || 30);
const RECOVERY_SECONDS = Number(__ENV.RECOVERY_SECONDS || 30);

const MAX_VUS = Number(__ENV.MAX_VUS || 1000);

const arrival = (rate, duration, startTime) => ({
  executor: 'constant-arrival-rate',
  rate,
  timeUnit: '1s',
  duration,
  startTime,
  preAllocatedVUs: Math.min(MAX_VUS, 200),
  maxVUs: MAX_VUS,
});

// Non-failing thresholds materialise the per-phase sub-metrics so handleSummary
// can read them — a phase whose http_reqs is not named here reports no request
// count at all. The one real gate is recovery health; the spike phase is observed,
// never gated, because a spike is allowed to shed.
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

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const idPrefix = `evt_${RUN_ID}_${exec.scenario.name}_${__VU}_${iteration}`;
  const base = BATCH_SPIKE_PHASE_SEQ_BASE[exec.scenario.name] || 0;
  const response = postEventBatch(BASE_URL, idPrefix, base + iteration * BATCH_SIZE, BATCH_SIZE);
  check(response, { 'status is 202': (r) => r.status === 202 });
}

// Rated over the phase's own seconds rather than over the metric's rate, which is
// a counter averaged across the whole run.
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
    batch_size: BATCH_SIZE,
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
    `batch ingest spike  (run ${RUN_ID}, batch size ${BATCH_SIZE})`,
    line('baseline → spike rps', `${BASELINE_RATE} → ${SPIKE_RATE}`),
    line('spike achieved rps', num(spike.achieved_rps, 0)),
    line('spike achieved', `${num(spike.achieved_rps * BATCH_SIZE, 0)} events/s`),
    line('spike dropped', num(spike.dropped, 0)),
    line('spike failed', `${num(spike.failed_rate * 100, 2)} %`),
    line('spike p95', `${num(spike.p95_ms, 1)} ms`),
    line('spike p99', `${num(spike.p99_ms, 1)} ms`),
    line('spike max', `${num(spike.max_ms, 1)} ms`),
    line('recovery failed', `${num(recovery.failed_rate * 100, 2)} %`),
    line('recovery p95', `${num(recovery.p95_ms, 1)} ms  (baseline ${num(baseline.p95_ms, 1)})`),
    '',
  ].join('\n');

  return {
    stdout: text,
    [SUMMARY_OUT]: JSON.stringify(summary, null, 2),
  };
}
