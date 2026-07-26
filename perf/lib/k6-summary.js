// Reads one number out of a k6 summary, defaulting to NaN so a metric that a
// scenario never produced surfaces as "n/a" instead of crashing handleSummary
// half-way through writing the file. Shared by the read and write scenarios.

export function metric(data, name, value) {
  const m = data.metrics[name];
  return m && m.values[value] != null ? m.values[value] : NaN;
}
