import http from 'k6/http';

// Shared building blocks for the k6 ingest scenarios, so the request shape and
// the summary reader live in one place: a change to the /api/v1/events contract
// touches this file, not every scenario that posts to it.

const EVENT_TYPES = ['page_view', 'click', 'purchase', 'signup'];

export function eventType(index) {
  return EVENT_TYPES[index % EVENT_TYPES.length];
}

export function postEvent(baseUrl, eventId, type, tags) {
  const body = JSON.stringify({
    source: 'perf-test',
    event_id: eventId,
    user_id: `user_${__VU}`,
    event_type: type,
    timestamp: new Date().toISOString(),
    properties: {
      page_url: '/products/laptop-x1',
      referrer: '/search?q=laptop',
      device: 'mobile',
      country: 'UA',
    },
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