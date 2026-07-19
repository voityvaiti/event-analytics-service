import http from 'k6/http';
import { generateEvent } from './event-generator.js';

// Shared building blocks for the k6 ingest scenarios, so the request shape and
// the summary reader live in one place: a change to the /api/v1/events contract
// touches this file, not every scenario that posts to it. The event body itself
// is produced by event-generator.js from the caller's sequence number.

export function postEvent(baseUrl, eventId, seq, tags) {
  const event = generateEvent(seq);
  const body = JSON.stringify({
    event_id: eventId,
    timestamp: new Date().toISOString(),
    source: event.source,
    user_id: event.user_id,
    event_type: event.event_type,
    properties: event.properties,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      // Once JWT (HS256) auth lands, add: Authorization: `Bearer ${__ENV.TOKEN}`
    },
    tags,
  };

  return http.post(`${baseUrl}/api/v1/events`, body, params);
}

export function metric(data, name, value) {
  const m = data.metrics[name];
  return m && m.values[value] != null ? m.values[value] : NaN;
}