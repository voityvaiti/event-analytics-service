// Reads one number out of a k6 summary, defaulting to NaN so a metric that a
// scenario never produced surfaces as "n/a" instead of crashing handleSummary
// half-way through writing the file. Shared by the read and write scenarios.

export function metric(data, name, value) {
  const m = data.metrics[name];
  return m && m.values[value] != null ? m.values[value] : NaN;
}

const isoMillis = (millis) => new Date(Math.round(millis)).toISOString();

// Record when the run starts; every scenario using runWindow re-exports this. The clock in
// handleSummary cannot date the run: k6 calls it only after aggregating every sample,
// ~3 s late at 25,000 req/s.
export function setup() {
  return { startedMillis: Date.now() };
}

// Derive the measured UTC window from setup's start and testRunDurationMs, retaining
// milliseconds. Shell timing would include container startup and teardown (~0.45s
// before and ~0.68s after a six-second run).
export function runWindow(data) {
  const startedMillis = data.setup_data && data.setup_data.startedMillis;
  if (!Number.isFinite(startedMillis)) {
    throw new Error("runWindow needs setup's start time: export { setup } from k6-summary.js");
  }
  return {
    started_at: isoMillis(startedMillis),
    finished_at: isoMillis(startedMillis + data.state.testRunDurationMs),
  };
}

// Derive nominal phase boundaries from the run window and configured durations. They
// mark offered-rate changes, not request completion. k6 attributes requests to their
// starting scenario; dashboard panels count completions within the displayed interval.
export function phaseWindows(run, secondsByPhase) {
  let startMillis = Date.parse(run.started_at);
  const windows = {};
  for (const [phase, seconds] of Object.entries(secondsByPhase)) {
    const endMillis = startMillis + seconds * 1000;
    windows[phase] = {
      started_at: isoMillis(startMillis),
      finished_at: isoMillis(endMillis),
    };
    startMillis = endMillis;
  }
  return windows;
}
