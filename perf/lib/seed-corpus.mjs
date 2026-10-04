// Emit corpus CSV in the harness's COPY column order, using the shared event generator.
// Linear timestamps align physical row order with time. Set tenant_name directly here;
// API writes obtain it from tokens. Keep corpus and write tenants distinct for cleanup.

import { generateEvent } from './event-generator.js';
import { CORPUS_SEQ_LIMIT } from './seq-space.js';

const ROWS = Number(process.env.SEED_ROWS || 20000000);
const SPREAD_DAYS = Number(process.env.SEED_SPREAD_DAYS || 180);
const ANCHOR = Date.parse(process.env.SEED_ANCHOR || '2026-01-01T00:00:00Z');

const SOURCE = 'perf-seed';
const FLUSH_EVERY = 20000;

if (!Number.isInteger(ROWS) || ROWS < 1) {
  throw new Error(`SEED_ROWS must be a positive integer, got "${process.env.SEED_ROWS}"`);
}
if (ROWS > CORPUS_SEQ_LIMIT) {
  throw new Error(
    `SEED_ROWS ${ROWS} overflows the corpus band of ${CORPUS_SEQ_LIMIT} — the corpus would ` +
      `reach into a write scenario's band and replay its events. Move the bands in seq-space.js.`,
  );
}
if (!Number.isFinite(ANCHOR)) {
  throw new Error(`SEED_ANCHOR must be an ISO timestamp, got "${process.env.SEED_ANCHOR}"`);
}

const stepMillis = (SPREAD_DAYS * 86400000) / ROWS;

function csvJson(value) {
  return `"${JSON.stringify(value).replace(/"/g, '""')}"`;
}

let buffer = '';
for (let seq = 1; seq <= ROWS; seq++) {
  const event = generateEvent(seq);
  const occurredAt = new Date(ANCHOR + (seq - 1) * stepMillis).toISOString();
  buffer +=
    `seed_${seq},${SOURCE},${event.user_id},${event.event_type},` +
    `${occurredAt},${csvJson(event.properties)}\n`;

  if (seq % FLUSH_EVERY === 0) {
    if (!process.stdout.write(buffer)) {
      await new Promise((resolve) => process.stdout.once('drain', resolve));
    }
    buffer = '';
  }
}

if (buffer) {
  process.stdout.write(buffer);
}
