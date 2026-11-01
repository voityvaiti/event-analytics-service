// One open-model stream of RATE events a second, split into named phases by the time a
// request starts. BATCH_SIZE (default 1) events go in each request; above 1 they go to the
// batch endpoint at RATE / BATCH_SIZE requests a second, and RATE must divide evenly. The
// steady cell judges its measure phase; the restart cell runs one phase while it stops
// and restarts the consumer underneath. Request counts in the summary count requests.
//
// Preallocate 1.1 * requests/s * deadline VUs so k6 keeps the rate even if every request
// waits out the deadline: an unaccepted write is then the server's, never k6 running out
// of VUs. Growing VUs during the run can itself drop work.
//
// Outcomes and drop accounting follow mixed/spike/ingest-under-read-spike.js.

import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { postEvent, postEventBatch } from '../../lib/k6-ingest.js';
import { metric, phaseWindows, runWindow } from '../../lib/k6-summary.js';
import { BATCH_LOAD_SEQ_BASE, LOAD_SEQ_BASE } from '../../lib/seq-space.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SUMMARY_OUT = __ENV.SUMMARY_OUT || 'perf/write/steady/last-summary.json';

const SCENARIO = __ENV.SCENARIO || 'ingest-at-rate';

const RATE = Number(__ENV.RATE || 3700);
const BATCH_SIZE = Number(__ENV.BATCH_SIZE || 1);
const REQUEST_RATE = RATE / BATCH_SIZE;
if (!Number.isInteger(BATCH_SIZE) || BATCH_SIZE < 1 || !Number.isInteger(REQUEST_RATE)) {
  throw new Error(`RATE (${RATE}) must be a whole multiple of BATCH_SIZE (${BATCH_SIZE})`);
}
const WRITE_TIMEOUT_SECONDS = Number(__ENV.WRITE_TIMEOUT_SECONDS || 1);
const MAX_VUS = Number(
  __ENV.MAX_VUS || Math.ceil((REQUEST_RATE * WRITE_TIMEOUT_SECONDS * 11) / 10),
);

// Phases in order as "name:seconds,…".
const SECONDS = Object.fromEntries(
  (__ENV.PHASES || 'warmup:30,measure:180').split(',').map((entry) => {
    const [phase, seconds] = entry.split(':');
    return [phase, Number(seconds)];
  }),
);
const PHASES = Object.keys(SECONDS);
const TOTAL_SECONDS = PHASES.reduce((total, phase) => total + SECONDS[phase], 0);

const OUTCOMES = ['accepted', 'server_errors', 'rejected', 'timeouts', 'transport_errors'];
const writeOutcomes = Object.fromEntries(
  OUTCOMES.map((outcome) => [outcome, new Counter(`write_${outcome}`)]),
);

const REQUEST_TIMEOUT_ERROR_CODE = 1050;

// Nothing is gated here; the harness judges the row. Each threshold only materialises
// a sub-metric handleSummary reads.
function materialisedThresholds() {
  const thresholds = { 'dropped_iterations{scenario:write}': ['count>=0'] };
  for (const phase of PHASES) {
    thresholds[`http_reqs{phase:${phase}}`] = ['count>=0'];
    thresholds[`http_req_duration{phase:${phase},expected_response:true}`] = ['p(95)>=0'];
    for (const outcome of OUTCOMES) {
      thresholds[`write_${outcome}{phase:${phase}}`] = ['count>=0'];
    }
  }
  return thresholds;
}

export const options = {
  scenarios: {
    write: {
      executor: 'constant-arrival-rate',
      exec: 'write',
      rate: REQUEST_RATE,
      timeUnit: '1s',
      duration: `${TOTAL_SECONDS}s`,
      gracefulStop: `${WRITE_TIMEOUT_SECONDS + 5}s`,
      preAllocatedVUs: MAX_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: materialisedThresholds(),
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function phaseAt(elapsedMillis) {
  let phaseEndMillis = 0;
  for (const phase of PHASES) {
    phaseEndMillis += SECONDS[phase] * 1000;
    if (elapsedMillis < phaseEndMillis) {
      return phase;
    }
  }
  return PHASES[PHASES.length - 1];
}

function outcomeOf(response) {
  if (response.status === 202) {
    return 'accepted';
  }
  if (response.status >= 500) {
    return 'server_errors';
  }
  if (response.status !== 0) {
    return 'rejected';
  }
  return response.error_code === REQUEST_TIMEOUT_ERROR_CODE ? 'timeouts' : 'transport_errors';
}

export function write() {
  const iteration = exec.scenario.iterationInTest;
  const phase = phaseAt(Date.now() - exec.scenario.startTime);
  const eventId = `evt_${RUN_ID}_steady_${iteration}`;
  const request = {
    tags: { phase },
    token: __ENV.TOKEN,
    timeout: `${WRITE_TIMEOUT_SECONDS}s`,
  };

  const response =
    BATCH_SIZE === 1
      ? postEvent(BASE_URL, eventId, LOAD_SEQ_BASE + iteration, request)
      : postEventBatch(
          BASE_URL,
          eventId,
          BATCH_LOAD_SEQ_BASE + iteration * BATCH_SIZE,
          BATCH_SIZE,
          request,
        );
  writeOutcomes[outcomeOf(response)].add(1, { phase });
}

const count = (data, name) => {
  const value = metric(data, name, 'count');
  return Number.isFinite(value) ? value : 0;
};

function phaseSummary(data, phase) {
  const accepted = `http_req_duration{phase:${phase},expected_response:true}`;
  return Object.assign(
    {
      scheduled: REQUEST_RATE * SECONDS[phase],
      sent: count(data, `http_reqs{phase:${phase}}`),
    },
    Object.fromEntries(
      OUTCOMES.map((outcome) => [outcome, count(data, `write_${outcome}{phase:${phase}}`)]),
    ),
    {
      p95_ms: metric(data, accepted, 'p(95)'),
      p99_ms: metric(data, accepted, 'p(99)'),
      max_ms: metric(data, accepted, 'max'),
    },
  );
}

export function handleSummary(data) {
  const run = runWindow(data);
  const windows = phaseWindows(run, SECONDS);

  const phases = Object.fromEntries(
    PHASES.map((phase) => [phase, Object.assign({}, windows[phase], phaseSummary(data, phase))]),
  );

  const summary = {
    scenario: SCENARIO,
    run_id: RUN_ID,
    accepted_events: metric(data, 'accepted_events', 'count'),
    started_at: run.started_at,
    finished_at: run.finished_at,
    base_url: BASE_URL,
    rate: RATE,
    batch_size: BATCH_SIZE,
    request_rate: REQUEST_RATE,
    write_timeout_seconds: WRITE_TIMEOUT_SECONDS,
    max_vus: MAX_VUS,
    dropped: count(data, 'dropped_iterations{scenario:write}'),
    seconds: SECONDS,
    phases,
  };

  const num = (value, decimals) => (Number.isFinite(value) ? value.toFixed(decimals) : 'n/a');
  const percent = (part, whole) => (whole > 0 ? `${num((part / whole) * 100, 2)} %` : 'n/a');
  const line = (label, value) => `  ${label.padEnd(26)} ${value}`;
  const text = [
    '',
    `${SCENARIO}  (run ${RUN_ID})`,
    line(
      'writes',
      `${RATE} events/s in ${REQUEST_RATE} req/s, ${WRITE_TIMEOUT_SECONDS}s deadline, ` +
        `${MAX_VUS} VUs`,
    ),
    ...PHASES.flatMap((phase) => {
      const p = phases[phase];
      return [
        line(
          `${phase} not accepted`,
          `${percent(p.scheduled - p.accepted, p.scheduled)}  ` +
            `(sent ${p.sent} of ${p.scheduled}, 5xx ${p.server_errors}, timeouts ${p.timeouts})`,
        ),
        line(`${phase} accepted p99`, `${num(p.p99_ms, 1)} ms`),
      ];
    }),
    line('drops, whole run', num(summary.dropped, 0)),
    '',
  ].join('\n');

  return {
    stdout: text,
    [SUMMARY_OUT]: JSON.stringify(summary, null, 2),
  };
}
