// Disjoint sequence bands prevent producers from generating identical users, sessions,
// and pages. Keep all bands below ~147M, where the generator's per-field hash streams
// begin to alias.

export const CORPUS_SEQ_LIMIT = 30000000;

// Single-event load and mixed writes share a band because write cells clean up between
// runs. Mixed writes coexist only with the corpus. No extra band fits below the
// aliasing ceiling.
export const LOAD_SEQ_BASE = 30000000;

export const SPIKE_PHASE_SEQ_BASE = {
  baseline: 40000000,
  spike: 50000000,
  recovery: 60000000,
};

// Batch bands count events, not requests. At BATCH_SIZE=100, 30s load produces ~3M
// events and a surge under 10M. The load and phase bands reserve headroom while ending
// below the ~147M aliasing ceiling.
export const BATCH_LOAD_SEQ_BASE = 70000000;

export const BATCH_SPIKE_PHASE_SEQ_BASE = {
  baseline: 90000000,
  spike: 100000000,
  recovery: 130000000,
};
