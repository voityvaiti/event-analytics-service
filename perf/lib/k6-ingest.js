// The /api/v1/events request shapes for every write scenario, so a change to the
// ingest contract touches this file and not every scenario that posts to it. The
// event bodies themselves are produced by event-generator.js from the caller's
// sequence number.
//
// Both endpoints send the same events; they differ only in how many go per
// request and in the envelope the batch one puts them in.

import http from 'k6/http';
import { generateEvent } from './event-generator.js';

function buildEvent(eventId, seq) {
  const event = generateEvent(seq);
  return {
    event_id: eventId,
    timestamp: new Date().toISOString(),
    source: event.source,
    user_id: event.user_id,
    event_type: event.event_type,
    properties: event.properties,
  };
}

function requestParams(tags) {
  return {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${__ENV.TOKEN}`,
    },
    tags,
  };
}

export function postEvent(baseUrl, eventId, seq, tags) {
  const body = JSON.stringify(buildEvent(eventId, seq));

  return http.post(`${baseUrl}/api/v1/events`, body, requestParams(tags));
}

// Every event in the batch gets its own event_id and its own sequence number, so
// a batch of 100 is 100 distinct inserts rather than one insert and 99 conflicts —
// the caller hands over the start of a stretch it owns, and this walks it.
export function postEventBatch(baseUrl, idPrefix, seqStart, size, tags) {
  const events = [];
  for (let index = 0; index < size; index += 1) {
    events.push(buildEvent(`${idPrefix}_${index}`, seqStart + index));
  }
  const body = JSON.stringify({ events });

  return http.post(`${baseUrl}/api/v1/events/batch`, body, requestParams(tags));
}
