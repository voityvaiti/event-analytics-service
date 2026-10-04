// Measure steady ingestion alongside the read spike's phases. Separate tenant tokens
// keep writes out of the read corpus; both streams share the app, pool, and host.
//
// Use one open-model write stream across phases so stalled VUs remain occupied during
// recovery. Attribute writes to their starting phase and let gracefulStop exceed the
// deadline so final requests are counted.
//
// Writes exceeding WRITE_TIMEOUT_SECONDS count as unaccepted even if the server later
// persists them. Without deadlines, a smoke run reported 18% unaccepted writes solely
// because its 6,000 VUs were exhausted.
//
// Preallocate 1.1 * WRITE_RATE * deadline VUs; growing them during measurement can drop
// work. The 5s default fits the rig: 10s at 1,000 req/s needs 11,000 VUs, exceeding
// Tomcat's 8,192 connections even during baseline.
//
// Phase drops are scheduled minus sent requests; also record k6's exact total. Outcomes
// are 202 acceptance, 5xx, other status (harness rejection), timeout, or transport
// error. Report latency only for accepted writes.

import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { postEvent } from '../../lib/k6-ingest.js';
import { getStats } from '../../lib/k6-stats.js';
import { generateQuery } from '../../lib/query-generator.js';
import { metric, phaseWindows, runWindow } from '../../lib/k6-summary.js';
import { LOAD_SEQ_BASE } from '../../lib/seq-space.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SUMMARY_OUT = __ENV.SUMMARY_OUT || 'perf/mixed/spike/last-summary.json';

const SCENARIO = 'ingest-under-read-spike';

const ENDPOINT = __ENV.ENDPOINT || 'active-users';
const GROUP_BY = __ENV.GROUP_BY || '';
const WINDOW = __ENV.SPIKE_WINDOW || '1d';

const CORPUS_ANCHOR = Date.parse(__ENV.SEED_ANCHOR || '2026-01-01T00:00:00Z');
const CORPUS_DAYS = Number(__ENV.SEED_SPREAD_DAYS || 180);
const CORPUS = {
  startMillis: CORPUS_ANCHOR,
  endMillis: CORPUS_ANCHOR + CORPUS_DAYS * 86400000,
};

const READ_BASELINE_RATE = Number(__ENV.READ_BASELINE_RATE || 20);
const READ_SPIKE_RATE = Number(__ENV.READ_SPIKE_RATE || 400);
const READ_MAX_VUS = Number(__ENV.READ_MAX_VUS || 500);

const WRITE_RATE = Number(__ENV.WRITE_RATE || 1000);
const WRITE_TIMEOUT_SECONDS = Number(__ENV.WRITE_TIMEOUT_SECONDS || 5);
const WRITE_MAX_VUS = Number(
  __ENV.WRITE_MAX_VUS || Math.ceil(WRITE_RATE * WRITE_TIMEOUT_SECONDS * 1.1),
);

const SECONDS = {
  baseline: Number(__ENV.BASELINE_SECONDS || 20),
  spike: Number(__ENV.SPIKE_SECONDS || 30),
  recovery: Number(__ENV.RECOVERY_SECONDS || 30),
};
const PHASES = Object.keys(SECONDS);
const TOTAL_SECONDS = PHASES.reduce((total, phase) => total + SECONDS[phase], 0);

const OUTCOMES = ['accepted', 'server_errors', 'rejected', 'timeouts', 'transport_errors'];
const writeOutcomes = Object.fromEntries(
  OUTCOMES.map((outcome) => [outcome, new Counter(`write_${outcome}`)]),
);

const REQUEST_TIMEOUT_ERROR_CODE = 1050;

const readPhase = (phase, rate, startTime) => ({
  executor: 'constant-arrival-rate',
  exec: 'read',
  rate,
  timeUnit: '1s',
  duration: `${SECONDS[phase]}s`,
  startTime,
  preAllocatedVUs: Math.min(READ_MAX_VUS, 100),
  maxVUs: READ_MAX_VUS,
  tags: { flow: 'read' },
});

// Nothing is gated: this cell measures before anything is judged by it. Every
// threshold below exists to materialise a sub-metric handleSummary reads, since
// one no threshold names reports nothing at all.
function materialisedThresholds() {
  const thresholds = { 'dropped_iterations{scenario:write}': ['count>=0'] };
  for (const phase of PHASES) {
    const read = `scenario:read_${phase}`;
    thresholds[`http_reqs{${read}}`] = ['count>=0'];
    thresholds[`http_req_failed{${read}}`] = ['rate<=1'];
    thresholds[`http_req_duration{${read}}`] = ['p(95)>=0'];
    thresholds[`dropped_iterations{${read}}`] = ['count>=0'];

    thresholds[`http_reqs{flow:write,phase:${phase}}`] = ['count>=0'];
    thresholds[`http_req_duration{flow:write,phase:${phase},expected_response:true}`] = [
      'p(95)>=0',
    ];
    for (const outcome of OUTCOMES) {
      thresholds[`write_${outcome}{phase:${phase}}`] = ['count>=0'];
    }
  }
  return thresholds;
}

export const options = {
  scenarios: {
    read_baseline: readPhase('baseline', READ_BASELINE_RATE, '0s'),
    read_spike: readPhase('spike', READ_SPIKE_RATE, `${SECONDS.baseline}s`),
    read_recovery: readPhase(
      'recovery',
      READ_BASELINE_RATE,
      `${SECONDS.baseline + SECONDS.spike}s`,
    ),
    write: {
      executor: 'constant-arrival-rate',
      exec: 'write',
      rate: WRITE_RATE,
      timeUnit: '1s',
      duration: `${TOTAL_SECONDS}s`,
      startTime: '0s',
      gracefulStop: `${WRITE_TIMEOUT_SECONDS + 5}s`,
      preAllocatedVUs: WRITE_MAX_VUS,
      maxVUs: WRITE_MAX_VUS,
      tags: { flow: 'write' },
    },
  },
  thresholds: materialisedThresholds(),
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function read() {
  const query = generateQuery(exec.scenario.iterationInTest, CORPUS, WINDOW);

  const params = { from: query.from, to: query.to };
  if (GROUP_BY) {
    params.groupBy = GROUP_BY;
  }

  const response = getStats(BASE_URL, ENDPOINT, params, { token: __ENV.READ_TOKEN });
  check(response, { 'read status is 200': (r) => r.status === 200 });
}

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

  const eventId = `evt_${RUN_ID}_mixed_${iteration}`;

  const response = postEvent(BASE_URL, eventId, LOAD_SEQ_BASE + iteration, {
    tags: { phase },
    token: __ENV.WRITE_TOKEN,
    timeout: `${WRITE_TIMEOUT_SECONDS}s`,
  });
  writeOutcomes[outcomeOf(response)].add(1, { phase });
}

const count = (data, name) => {
  const value = metric(data, name, 'count');
  return Number.isFinite(value) ? value : 0;
};

function readSummary(data, phase) {
  const tag = (name) => `${name}{scenario:read_${phase}}`;
  return {
    achieved_rps: count(data, tag('http_reqs')) / SECONDS[phase],
    dropped: count(data, tag('dropped_iterations')),
    failed_rate: metric(data, tag('http_req_failed'), 'rate'),
    p95_ms: metric(data, tag('http_req_duration'), 'p(95)'),
    p99_ms: metric(data, tag('http_req_duration'), 'p(99)'),
    max_ms: metric(data, tag('http_req_duration'), 'max'),
  };
}

function writeSummary(data, phase) {
  const accepted = `http_req_duration{flow:write,phase:${phase},expected_response:true}`;
  return Object.assign(
    {
      scheduled: WRITE_RATE * SECONDS[phase],
      sent: count(data, `http_reqs{flow:write,phase:${phase}}`),
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
    PHASES.map((phase) => [
      phase,
      Object.assign({}, windows[phase], {
        read: readSummary(data, phase),
        write: writeSummary(data, phase),
      }),
    ]),
  );

  const summary = {
    scenario: SCENARIO,
    run_id: RUN_ID,
    started_at: run.started_at,
    finished_at: run.finished_at,
    base_url: BASE_URL,
    endpoint: ENDPOINT,
    group_by: GROUP_BY,
    window: WINDOW,
    read_baseline_rate: READ_BASELINE_RATE,
    read_spike_rate: READ_SPIKE_RATE,
    read_max_vus: READ_MAX_VUS,
    write_rate: WRITE_RATE,
    write_timeout_seconds: WRITE_TIMEOUT_SECONDS,
    write_max_vus: WRITE_MAX_VUS,
    write_dropped: count(data, 'dropped_iterations{scenario:write}'),
    seconds: SECONDS,
    phases,
  };

  const num = (value, decimals) => (Number.isFinite(value) ? value.toFixed(decimals) : 'n/a');
  const percent = (part, whole) => (whole > 0 ? `${num((part / whole) * 100, 2)} %` : 'n/a');
  const line = (label, value) => `  ${label.padEnd(26)} ${value}`;
  const query = ENDPOINT + (GROUP_BY ? ` groupBy=${GROUP_BY}` : '');
  const text = [
    '',
    `ingest under read spike  ${query} ${WINDOW}  (run ${RUN_ID})`,
    line(
      'writes',
      `${WRITE_RATE} req/s, ${WRITE_TIMEOUT_SECONDS}s deadline, up to ${WRITE_MAX_VUS} VUs`,
    ),
    line('reads baseline → spike', `${READ_BASELINE_RATE} → ${READ_SPIKE_RATE} req/s`),
    ...PHASES.flatMap((phase) => {
      const { read, write } = phases[phase];
      return [
        line(
          `${phase} writes not accepted`,
          `${percent(write.scheduled - write.accepted, write.scheduled)}  ` +
            `(sent ${write.sent} of ${write.scheduled}, 5xx ${write.server_errors}, ` +
            `timeouts ${write.timeouts})`,
        ),
        line(`${phase} accepted write p99`, `${num(write.p99_ms, 1)} ms`),
        line(`${phase} read p95`, `${num(read.p95_ms, 1)} ms`),
      ];
    }),
    line('write drops, whole run', num(summary.write_dropped, 0)),
    '',
  ].join('\n');

  return {
    stdout: text,
    [SUMMARY_OUT]: JSON.stringify(summary, null, 2),
  };
}
