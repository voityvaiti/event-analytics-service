import exec from 'k6/execution';
import { check } from 'k6';
import { postEvent } from '../../lib/k6-ingest.js';
import { metric, phaseWindows, runWindow } from '../../lib/k6-summary.js';
import { SPIKE_PHASE_SEQ_BASE } from '../../lib/seq-space.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SUMMARY_OUT = __ENV.SUMMARY_OUT || 'perf/write/spike/last-summary.json';

const SCENARIO = 'ingest-spike';

// An open arrival-rate model keeps offering traffic as latency rises; constant VUs
// would slow arrivals instead. SPIKE_RATE must exceed measured capacity (~4k req/s on
// the reference rig).
const BASELINE_RATE = Number(__ENV.BASELINE_RATE || 500);
const SPIKE_RATE = Number(__ENV.SPIKE_RATE || 8000);

// Seconds per phase. The spike is a sudden step to SPIKE_RATE and back — no
// ramp — because the point is the shock, not a gradual climb.
const BASELINE_SECONDS = Number(__ENV.BASELINE_SECONDS || 20);
const SPIKE_SECONDS = Number(__ENV.SPIKE_SECONDS || 30);
const RECOVERY_SECONDS = Number(__ENV.RECOVERY_SECONDS || 30);

// MAX_VUS caps in-flight requests. When all VUs are occupied, k6 reports
// dropped_iterations. Increase the cap to offer more work to the server.
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

// Non-failing thresholds expose each phase's sub-metrics to handleSummary. Only
// recovery health gates; surge shedding is recorded. Materialise http_reqs for all
// phases to avoid missing counts.
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
  const eventId = `evt_${RUN_ID}_${exec.scenario.name}_${__VU}_${iteration}`;
  const seq = (SPIKE_PHASE_SEQ_BASE[exec.scenario.name] || 0) + iteration;
  const response = postEvent(BASE_URL, eventId, seq);
  check(response, { 'status is 202': (r) => r.status === 202 });
}

// Rated over the phase's own seconds rather than over the metric's rate, which
// is a counter averaged across the whole run: at 20s baseline, 30s spike and 30s
// recovery, a surge that carried 1500 req/s for its 30s would report 560.
function phase(data, scenario, seconds, window) {
  const tag = (name) => `${name}{scenario:${scenario}}`;
  const requests = metric(data, tag('http_reqs'), 'count');
  return {
    started_at: window.started_at,
    finished_at: window.finished_at,
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
  const run = runWindow(data);
  const seconds = {
    baseline: BASELINE_SECONDS,
    spike: SPIKE_SECONDS,
    recovery: RECOVERY_SECONDS,
  };
  const windows = phaseWindows(run, seconds);

  const baseline = phase(data, 'baseline', BASELINE_SECONDS, windows.baseline);
  const spike = phase(data, 'spike', SPIKE_SECONDS, windows.spike);
  const recovery = phase(data, 'recovery', RECOVERY_SECONDS, windows.recovery);

  const summary = {
    scenario: SCENARIO,
    run_id: RUN_ID,
    started_at: run.started_at,
    finished_at: run.finished_at,
    base_url: BASE_URL,
    baseline_rate: BASELINE_RATE,
    spike_rate: SPIKE_RATE,
    max_vus: MAX_VUS,
    seconds,
    phases: { baseline, spike, recovery },
  };

  const num = (v, decimals) => (Number.isFinite(v) ? v.toFixed(decimals) : 'n/a');
  const line = (label, value) => `  ${label.padEnd(22)} ${value}`;
  const text = [
    '',
    `ingest spike  (run ${RUN_ID})`,
    line('baseline → spike rps', `${BASELINE_RATE} → ${SPIKE_RATE}`),
    line('spike achieved rps', num(spike.achieved_rps, 0)),
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