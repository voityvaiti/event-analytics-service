// Emits the fixed corpus as CSV on stdout for COPY to swallow — one line per
// row, in the column order the harness declares. Rows come from the same
// generator the write scenarios post through, so the corpus and the write path
// are one population instead of two implementations drifting apart.
//
// occurred_at is spread linearly over the window rather than hashed: that is
// how an event table actually accumulates, and it keeps physical row order
// aligned with the index the read tests exercise.
//
// `source` is set here rather than by the generator, which no longer emits one:
// a write scenario's rows now take their source from the tenant its token
// asserts, and only this COPY still writes the column directly. The two must
// stay different so a write test's teardown deletes its own batch and leaves the
// corpus standing.

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
