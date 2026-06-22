import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 10);
const DURATION = __ENV.DURATION || '30s';
const RAMP = __ENV.RAMP || '10s';

const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SUMMARY_OUT = __ENV.SUMMARY_OUT || 'load/last-summary.json';

export const options = {
  scenarios: {
    ingest: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },
        { duration: DURATION, target: VUS },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const EVENT_TYPES = ['page_view', 'click', 'purchase', 'signup'];

export default function () {
  const eventId = `evt_${RUN_ID}_${__VU}_${exec.scenario.iterationInTest}`;
  const eventType = EVENT_TYPES[exec.scenario.iterationInTest % EVENT_TYPES.length];

  const body = JSON.stringify({
    source: 'load-test',
    event_id: eventId,
    user_id: `user_${__VU}`,
    event_type: eventType,
    timestamp: new Date().toISOString(),
    properties: {
      page_url: '/products/laptop-x1',
      referrer: '/search?q=laptop',
      device: 'mobile',
      country: 'UA',
    },
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      // Once JWT (HS256) auth lands, add: Authorization: `Bearer ${__ENV.TOKEN}`
    },
  };

  const response = http.post(`${BASE_URL}/api/v1/events`, body, params);
  check(response, { 'status is 202': (r) => r.status === 202 });
}

function metric(data, name, value) {
  const m = data.metrics[name];
  return m && m.values[value] != null ? m.values[value] : NaN;
}

export function handleSummary(data) {
  const summary = {
    run_id: RUN_ID,
    base_url: BASE_URL,
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

  const line = (label, value) => `  ${label.padEnd(16)} ${value}`;
  const text = [
    '',
    `ingest write-throughput  (run ${RUN_ID})`,
    line('vus', VUS),
    line('requests', summary.requests),
    line('throughput', `${summary.throughput_rps.toFixed(1)} req/s`),
    line('failed', `${(summary.failed_rate * 100).toFixed(2)} %`),
    line('latency avg', `${summary.latency_ms.avg.toFixed(1)} ms`),
    line('latency p95', `${summary.latency_ms.p95.toFixed(1)} ms`),
    line('latency p99', `${summary.latency_ms.p99.toFixed(1)} ms`),
    line('latency max', `${summary.latency_ms.max.toFixed(1)} ms`),
    '',
  ].join('\n');

  return {
    stdout: text,
    [SUMMARY_OUT]: JSON.stringify(summary, null, 2),
  };
}
