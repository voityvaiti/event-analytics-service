import exec from 'k6/execution';
import { check } from 'k6';
import { postEvent, metric } from '../lib/k6-ingest.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SUMMARY_OUT = __ENV.SUMMARY_OUT || 'perf/spike/last-summary.json';

const SCENARIO = 'ingest-spike';

// Requests-per-second targets, not virtual-user counts: an arrival-rate executor
// is an OPEN model — k6 holds the target rate whether or not the app keeps up,
// so a surge that outpaces the pool shows as growing latency and, past the
// connection timeout, dropped work. A closed (constant-vus) model can't show
// that: 90 idle VUs would just park on getConnection() and we'd measure the
// pool, not a surge. SPIKE_RATE must exceed the load test's measured throughput
// (~4k req/s on the reference rig) or there is no surge to observe.
const BASELINE_RATE = Number(__ENV.BASELINE_RATE || 500);
const SPIKE_RATE = Number(__ENV.SPIKE_RATE || 8000);

// Seconds per phase. The spike is a sudden step to SPIKE_RATE and back — no
// ramp — because the point is the shock, not a gradual climb.
const BASELINE_SECONDS = Number(__ENV.BASELINE_SECONDS || 20);
const SPIKE_SECONDS = Number(__ENV.SPIKE_SECONDS || 30);
const RECOVERY_SECONDS = Number(__ENV.RECOVERY_SECONDS || 30);

// The client-side ceiling on concurrent in-flight requests. Under overload each
// request holds a VU until it returns, so a hard surge can demand far more VUs
// than this; when it does, k6 sheds the excess as `dropped_iterations` — itself
// a signal that the surge outran what the client could offer. Raise it to push
// the server harder rather than the client.
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
// can read them; the one real gate is recovery health — after the surge the app
// must serve cleanly again. The spike phase itself is observed, never gated: a
// spike is allowed to shed, and gating it would either hide that or red every
// run.
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
    'http_reqs{scenario:spike}': ['count>=0'],
    'dropped_iterations{scenario:spike}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const eventId = `evt_${RUN_ID}_${exec.scenario.name}_${__VU}_${iteration}`;
  const response = postEvent(BASE_URL, eventId, iteration);
  check(response, { 'status is 202': (r) => r.status === 202 });
}

function phase(data, scenario) {
  const tag = (name) => `${name}{scenario:${scenario}}`;
  return {
    achieved_rps: metric(data, tag('http_reqs'), 'rate'),
    requests: metric(data, tag('http_reqs'), 'count'),
    failed_rate: metric(data, tag('http_req_failed'), 'rate'),
    dropped: metric(data, tag('dropped_iterations'), 'count'),
    p95_ms: metric(data, tag('http_req_duration'), 'p(95)'),
    p99_ms: metric(data, tag('http_req_duration'), 'p(99)'),
    max_ms: metric(data, tag('http_req_duration'), 'max'),
  };
}

export function handleSummary(data) {
  const baseline = phase(data, 'baseline');
  const spike = phase(data, 'spike');
  const recovery = phase(data, 'recovery');

  const summary = {
    scenario: SCENARIO,
    run_id: RUN_ID,
    base_url: BASE_URL,
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