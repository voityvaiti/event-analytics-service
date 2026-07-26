// Deterministic query generator for the read scenarios — the read-side
// counterpart to event-generator.js. Given a sequence number it returns the
// slice of time one dashboard request would ask for.
//
// What varies on a read is not the payload but the question, so that is what
// this diversifies. Two distributions carry the realism:
//
//   window size — analytics traffic is mostly "the last hour" and "today", with
//   a thinner tail of weekly and monthly reports, and those differ by more than
//   an order of magnitude in the rows they touch. Every size stays a small
//   fraction of the corpus: a window covering most of it would be cheaper to
//   sweep than to look up, and the index under test would never be chosen.
//
//   window position — recent data is asked for far more often than old data, by
//   the same 1/rank law that governs page traffic. The tail still reaches the
//   start of the corpus, so nothing goes permanently unread.
//
// Pure in `seq`, like the event generator: both arms of an index comparison
// replay the identical sequence of questions, so the only thing left differing
// between them is the index.

const HOUR_MILLIS = 3600000;
const DAY_MILLIS = 86400000;

const WINDOW_SIZES = [
  { label: '1h', millis: HOUR_MILLIS, cumulative: 0.3 },
  { label: '1d', millis: DAY_MILLIS, cumulative: 0.7 },
  { label: '7d', millis: 7 * DAY_MILLIS, cumulative: 0.9 },
  { label: '30d', millis: 30 * DAY_MILLIS, cumulative: 1.0 },
];

function hash(value) {
  let h = value | 0;
  h = Math.imul(h ^ (h >>> 16), 0x45d9f3b);
  h = Math.imul(h ^ (h >>> 16), 0x45d9f3b);
  return (h ^ (h >>> 16)) >>> 0;
}

function unit(seq, salt) {
  return hash((seq + Math.imul(salt, 0x9e3779b1)) >>> 0) / 4294967296;
}

function pickCumulative(table, u) {
  for (const entry of table) {
    if (u < entry.cumulative) {
      return entry;
    }
  }
  return table[table.length - 1];
}

export function generateQuery(seq, corpus) {
  const size = pickCumulative(WINDOW_SIZES, unit(seq, 21));
  const span = Math.min(size.millis, corpus.endMillis - corpus.startMillis);

  // Inverse CDF of a 1/rank law over the corpus, in hours back from its end:
  // most questions land in the last day or two, the tail still reaches the
  // oldest data.
  const hoursBack = Math.floor(
    Math.pow(Math.max(1, (corpus.endMillis - corpus.startMillis) / HOUR_MILLIS), unit(seq, 22)),
  );
  const latestStart = corpus.endMillis - span;
  const from = Math.max(corpus.startMillis, latestStart - hoursBack * HOUR_MILLIS);

  return {
    from: new Date(from).toISOString(),
    to: new Date(from + span).toISOString(),
    window: size.label,
  };
}
