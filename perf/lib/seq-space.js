// The one number line shared by everything that draws from event-generator.js:
// the corpus seeder and both write scenarios. The generator is a pure function
// of its sequence number, so two producers standing on the same stretch emit
// identical events — a run would look like it touched more distinct users,
// sessions and pages than it did. Bands live here rather than in each consumer
// so they cannot drift into each other.
//
// Keep every band under ~147M: past that the generator's per-field hash streams
// begin to alias one another.

export const CORPUS_SEQ_LIMIT = 30000000;

export const LOAD_SEQ_BASE = 30000000;

export const SPIKE_PHASE_SEQ_BASE = {
  baseline: 40000000,
  spike: 50000000,
  recovery: 60000000,
};

// The batch scenarios draw BATCH_SIZE numbers per request where the single-event
// ones draw one, so their bands are sized for the product rather than for the
// request count. At the default batch of 100: a 30s load window at even 1000
// req/s is 3M, and a 30s surge offering a few thousand req/s is under 10M — so
// 20M for load and 10M/30M/17M for the surge phases leave room for a rate an
// order of magnitude above anything measured, while the last band still ends
// below the ~147M aliasing ceiling.
export const BATCH_LOAD_SEQ_BASE = 70000000;

export const BATCH_SPIKE_PHASE_SEQ_BASE = {
  baseline: 90000000,
  spike: 100000000,
  recovery: 130000000,
};
