import exec from 'k6/execution';
import { check } from 'k6';
import { postEventBatch } from '../../lib/k6-ingest.js';
import { metric } from '../../lib/k6-summary.js';
import { BATCH_LOAD_SEQ_BASE } from '../../lib/seq-space.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 10);
const DURATION = __ENV.DURATION || '30s';

const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const SUMMARY_OUT = __ENV.SUMMARY_OUT || 'perf/write/load/last-summary.json';

// Its own SCENARIO, stamped into every summary, so a batch number is never read
// against the single-event series as one line.
const SCENARIO = 'ingest-batch';

// Events per request. Reported in the summary rather than assumed by whoever
// reads the journal: at a fixed batch size, requests/s and events/s differ by
// exactly this factor, and only one of them is comparable with the single-event
// cell.
const BATCH_SIZE = Number(__ENV.BATCH_SIZE || 100);

// Constant concurrency for the whole window, matching the single-event cell — the
// caller runs a throwaway warm-up first, so every metric here is steady-state.
export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const idPrefix = `evt_${RUN_ID}_${__VU}_${iteration}`;
  const seqStart = BATCH_LOAD_SEQ_BASE + iteration * BATCH_SIZE;
  const response = postEventBatch(BASE_URL, idPrefix, seqStart, BATCH_SIZE);
  check(response, { 'status is 202': (r) => r.status === 202 });
}

export function handleSummary(data) {
  const requests = metric(data, 'http_reqs', 'count');
  const throughputRps = metric(data, 'http_reqs', 'rate');

  const summary = {
    scenario: SCENARIO,
    run_id: RUN_ID,
    base_url: BASE_URL,
    vus: VUS,
    duration: DURATION,
    batch_size: BATCH_SIZE,
    requests,
    throughput_rps: throughputRps,
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
    `batch ingest write-throughput  (run ${RUN_ID})`,
    line('vus', VUS),
    line('duration', DURATION),
    line('batch size', BATCH_SIZE),
    line('requests', summary.requests),
    line('throughput', `${summary.throughput_rps.toFixed(1)} req/s`),
    line('events', `${(summary.throughput_rps * BATCH_SIZE).toFixed(0)} events/s`),
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
