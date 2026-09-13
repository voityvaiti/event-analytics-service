// Reads one number out of a k6 summary, defaulting to NaN so a metric that a
// scenario never produced surfaces as "n/a" instead of crashing handleSummary
// half-way through writing the file. Shared by the read and write scenarios.

export function metric(data, name, value) {
  const m = data.metrics[name];
  return m && m.values[value] != null ? m.values[value] : NaN;
}

const isoMillis = (millis) => new Date(Math.round(millis)).toISOString();

// The wall-clock window a run was measured in, for a dashboard to be pointed at.
// Taken from k6 rather than from the shell around the container: handleSummary
// runs once the test is over, so Date.now() here is the end of the run and
// data.state.testRunDurationMs is how long the run took. Stamping around the
// docker invocation instead would fold the image check, container startup and VU
// initialisation into the window — measured at ~0.45s before and ~0.68s after a
// six-second run on the reference rig.
//
// Millisecond precision is kept because a spike phase boundary is worth more
// than a second of resolution.
export function runWindow(data) {
  const finishedMillis = Date.now();
  return {
    started_at: isoMillis(finishedMillis - data.state.testRunDurationMs),
    finished_at: isoMillis(finishedMillis),
  };
}
