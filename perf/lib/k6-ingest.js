// The /api/v1/events request shape for every write scenario, so a change to the
// ingest contract touches this file and not every scenario that posts to it.
// The event body itself is produced by event-generator.js from the caller's
// sequence number.

import http from 'k6/http';
import { generateEvent } from './event-generator.js';

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
