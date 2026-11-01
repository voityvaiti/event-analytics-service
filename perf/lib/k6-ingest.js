// Shared single and batch ingest requests; event-generator.js creates bodies from
// sequence numbers. request may override tags, token (default __ENV.TOKEN), and timeout
// (default k6's 60s). Mixed scenarios use separate tokens so reads see the corpus and
// writes reach the cleanup tenant.

import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { generateEvent } from './event-generator.js';

// What the harness reconciles the table against once the consumer has drained: the
// events answered 202, and the ID of every event that was not. A request k6 gave up on
// may still have been stored, so its IDs are logged for the harness to leave out.
const acceptedEvents = new Counter('accepted_events');

function recordOutcome(response, eventIds) {
  if (response.status === 202) {
    acceptedEvents.add(eventIds.length);
  } else {
    eventIds.forEach((eventId) => console.log(`unaccepted ${eventId}`));
  }
}

function buildEvent(eventId, seq) {
  const event = generateEvent(seq);
  return {
    event_id: eventId,
    timestamp: new Date().toISOString(),
    user_id: event.user_id,
    event_type: event.event_type,
    properties: event.properties,
  };
}

function requestParams({ tags, token = __ENV.TOKEN, timeout } = {}) {
  const params = {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    tags,
  };
  if (timeout) {
    params.timeout = timeout;
  }
  return params;
}

export function postEvent(baseUrl, eventId, seq, request) {
  const body = JSON.stringify(buildEvent(eventId, seq));

  const response = http.post(`${baseUrl}/api/v1/events`, body, requestParams(request));
  recordOutcome(response, [eventId]);
  return response;
}

// Every event in the batch gets its own event_id and its own sequence number, so
// a batch of 100 is 100 distinct inserts rather than one insert and 99 conflicts —
// the caller hands over the start of a stretch it owns, and this walks it.
export function postEventBatch(baseUrl, idPrefix, seqStart, size, request) {
  const events = [];
  for (let index = 0; index < size; index += 1) {
    events.push(buildEvent(`${idPrefix}_${index}`, seqStart + index));
  }
  const body = JSON.stringify({ events });

  const response = http.post(`${baseUrl}/api/v1/events/batch`, body, requestParams(request));
  recordOutcome(response, events.map((event) => event.event_id));
  return response;
}
