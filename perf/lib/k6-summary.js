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

// Where each phase of a run that steps through its phases back to back begins
// and ends, from the run window and the seconds each phase was given — named in
// the order they run. A spike's verdict (recovered = served and drained) is
// corroborated by watching the pool's wait queue climb during the surge and
// drain after it, and one window spanning all three phases averages them into a
// single band that cannot show either.
//
// The bounds are the nominal ones k6 was handed, not a partition of the measured
// window: what they mark is where the offered rate changed. A request still in
// flight across a boundary is attributed to the phase that issued it by k6's
// scenario tags, where a dashboard panel cut at the same instant counts it in the
// phase it lands in.
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
