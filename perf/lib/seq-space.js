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
