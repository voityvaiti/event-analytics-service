// Reads one number out of a k6 summary, defaulting to NaN so a metric that a
// scenario never produced surfaces as "n/a" instead of crashing handleSummary
// half-way through writing the file. Shared by the read and write scenarios.

export function metric(data, name, value) {
  const m = data.metrics[name];
  return m && m.values[value] != null ? m.values[value] : NaN;
}

const isoMillis = (millis) => new Date(Math.round(millis)).toISOString();

// Derive the measured UTC window from k6's end time and testRunDurationMs, retaining
// milliseconds. Shell timing would include container startup and teardown (~0.45s
// before and ~0.68s after a six-second run).
export function runWindow(data) {
  const finishedMillis = Date.now();
  return {
    started_at: isoMillis(finishedMillis - data.state.testRunDurationMs),
    finished_at: isoMillis(finishedMillis),
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
