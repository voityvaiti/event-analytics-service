// Steady ingest while a read surge holds the connection pool: is an event still
// accepted when the dashboards take every connection? The read spike cannot ask
// it, since it sends no writes, and the write cells cannot, since they send no
// reads.
//
// Two flows in one process, each as the tenant it has to be. Reads repeat
// read/spike's surge — the same three phases, rates and window — against the
// seeded corpus. Writes post single events to the tenant the harness deletes
// afterwards, so they never change what a read counts; what the flows share is
// the app, the pool and the machine.
//
// Writes are one open-model stream across all three phases rather than one
// scenario per phase. Each request is attributed to the phase it started in,
// and the VUs a stalled write holds stay held into the next phase, as a real
// producer's would: a fresh set of VUs at the start of recovery would give the
// client a clean slate the server never got. gracefulStop outlasts the write
// deadline, so the last writes are counted rather than cut off.
//
// Every write carries a deadline, WRITE_TIMEOUT_SECONDS, the way a producer's
// client does: one not accepted by then is given up on and counts as not
// accepted, even if the server finishes it later. The deadline is what makes
// the headline a property of the app. Without it, a write waits as long as the
// app makes it, each wait holds a VU, and what the run reports as unaccepted is
// how many VUs k6 was given: a smoke run at 1,000 req/s with 6,000 VUs counted
// 18% of the surge's writes unaccepted, every one of them dropped by k6 and
// none refused by the app.
//
// The deadline defaults to 5s, stricter than common clients (OkHttp waits 10s),
// because the rig cannot hold a longer one: k6 keeps a connection per VU and
// hands iterations to every allocated VU in turn, so even a healthy baseline
// opens one connection per VU, and Tomcat accepts 8,192. A 10s deadline at
// 1,000 req/s needs 11,000 VUs, and a smoke run saw Tomcat stop accepting
// connections eight seconds into the baseline.
//
// So the write VU budget is derived from the deadline, a tenth over rate times
// deadline, and runs out only after the deadline has. The whole budget is
// allocated before the run starts, because k6 drops an iteration when no VU is
// free and only then initialises another, which would make the drops count how
// fast k6 grows VUs. A drop is still possible, and journalled: k6 counts drops
// per scenario, so a phase's drops are what it scheduled minus what it sent,
// with the run's exact total beside them. Reads keep read/spike's allocation,
// so their side of the surge is applied the way that cell applies it.
//
// A write ends in one of five outcomes: accepted (202), a server error (5xx),
// rejected (any other status, which means the harness is wrong, not the app), a
// timeout (not answered within the deadline), or another transport error.
// Latency is reported for accepted writes only.

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
