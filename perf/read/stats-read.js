// Steady-state read latency for one /api/v1/stats endpoint. Which endpoint and
// which grouping is env, not a separate file: the cells differ only in the URL
// they call, while the shape of the measurement — constant VUs over a fixed
// window against the seeded corpus — is identical for all of them.
//
// Latency, not throughput, is the headline here. An analytics read is answered
// in tens to hundreds of milliseconds rather than the ~2ms an insert takes, and
// what a regression looks like is a query getting slower, not the service
// accepting fewer of them.

import exec from 'k6/execution';
import { check } from 'k6';
import { getStats } from '../lib/k6-stats.js';
import { generateQuery } from '../lib/query-generator.js';
import { metric } from '../lib/k6-summary.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Well under the connection pool on purpose. A read holds its pooled connection
// for the whole query, so a VU count at pool size would spend the run measuring
// how long requests wait for a connection instead of how long the query costs.
const VUS = Number(__ENV.VUS || 4);
const DURATION = __ENV.DURATION || '30s';

const ENDPOINT = __ENV.ENDPOINT || 'event-counts';
const GROUP_BY = __ENV.GROUP_BY || '';
const LIMIT = __ENV.LIMIT || '';

const CORPUS_ANCHOR = Date.parse(__ENV.SEED_ANCHOR || '2026-01-01T00:00:00Z');
const CORPUS_DAYS = Number(__ENV.SEED_SPREAD_DAYS || 180);

const CORPUS = {
  startMillis: CORPUS_ANCHOR,
  endMillis: CORPUS_ANCHOR + CORPUS_DAYS * 86400000,
};

const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SUMMARY_OUT = __ENV.SUMMARY_OUT || 'perf/read/last-summary.json';

const SCENARIO = `stats-${ENDPOINT}`;

const WINDOWS = ['1h', '1d', '7d', '30d'];

// Non-failing thresholds exist to materialise the per-window sub-metrics so
// handleSummary can read them: the run mixes window sizes on purpose, and a
// single latency figure would hide that its median describes hour-long
// questions while its tail describes month-long ones.
const windowThresholds = {};
for (const window of WINDOWS) {
  windowThresholds[`http_req_duration{window:${window}}`] = ['p(95)>=0'];
  windowThresholds[`http_reqs{window:${window}}`] = ['count>=0'];
}

export const options = {
  scenarios: {
    read: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: Object.assign({ http_req_failed: ['rate<0.01'] }, windowThresholds),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const query = generateQuery(exec.scenario.iterationInTest, CORPUS);

  const params = { from: query.from, to: query.to };
  if (GROUP_BY) {
    params.groupBy = GROUP_BY;
  }
  if (LIMIT) {
    params.limit = LIMIT;
  }

  const response = getStats(BASE_URL, ENDPOINT, params, { window: query.window });
  check(response, { 'status is 200': (r) => r.status === 200 });
}

function perWindow(data) {
  const windows = {};
  for (const window of WINDOWS) {
    const tag = (name) => `${name}{window:${window}}`;
    windows[window] = {
      requests: metric(data, tag('http_reqs'), 'count'),
      med_ms: metric(data, tag('http_req_duration'), 'med'),
      p95_ms: metric(data, tag('http_req_duration'), 'p(95)'),
    };
  }
  return windows;
}

export function handleSummary(data) {
  const windows = perWindow(data);
  const summary = {
    scenario: SCENARIO,
    run_id: RUN_ID,
    base_url: BASE_URL,
    endpoint: ENDPOINT,
    group_by: GROUP_BY,
    corpus_days: CORPUS_DAYS,
    windows,
    vus: VUS,
    duration: DURATION,
    requests: metric(data, 'http_reqs', 'count'),
    throughput_rps: metric(data, 'http_reqs', 'rate'),
    failed_rate: metric(data, 'http_req_failed', 'rate'),
    latency_ms: {
      avg: metric(data, 'http_req_duration', 'avg'),
      med: metric(data, 'http_req_duration', 'med'),
      p95: metric(data, 'http_req_duration', 'p(95)'),
      p99: metric(data, 'http_req_duration', 'p(99)'),
      max: metric(data, 'http_req_duration', 'max'),
    },
  };

  const label = GROUP_BY ? `${ENDPOINT} groupBy=${GROUP_BY}` : ENDPOINT;
  const num = (v, decimals) => (Number.isFinite(v) ? v.toFixed(decimals) : 'n/a');
  const line = (name, value) => `  ${name.padEnd(16)} ${value}`;
  const byWindow = WINDOWS.map((window) =>
    line(
      `  ${window}`,
      `med ${num(windows[window].med_ms, 1)} ms  p95 ${num(windows[window].p95_ms, 1)} ms` +
        `  (${num(windows[window].requests, 0)} req)`,
    ),
  );
  const text = [
    '',
    `stats read latency  ${label}  (run ${RUN_ID})`,
    line('vus', VUS),
    line('duration', DURATION),
    line('requests', num(summary.requests, 0)),
    line('throughput', `${num(summary.throughput_rps, 1)} req/s`),
    line('failed', `${num(summary.failed_rate * 100, 2)} %`),
    line('latency med', `${num(summary.latency_ms.med, 1)} ms`),
    line('latency p95', `${num(summary.latency_ms.p95, 1)} ms`),
    line('latency p99', `${num(summary.latency_ms.p99, 1)} ms`),
    line('latency max', `${num(summary.latency_ms.max, 1)} ms`),
    '  by window size',
  ]
    .concat(byWindow)
    .concat([''])
    .join('\n');

  return {
    stdout: text,
    [SUMMARY_OUT]: JSON.stringify(summary, null, 2),
  };
}
