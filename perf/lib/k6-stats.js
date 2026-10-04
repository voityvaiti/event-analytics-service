// Shared /api/v1/stats request builder. Callers select endpoint and query parameters.
// request supplies tags and an optional token overriding __ENV.TOKEN for mixed
// scenarios.

import http from 'k6/http';

export function getStats(baseUrl, endpoint, params, { tags, token = __ENV.TOKEN } = {}) {
  const query = Object.keys(params)
    .map((key) => `${key}=${encodeURIComponent(params[key])}`)
    .join('&');

  return http.get(`${baseUrl}/api/v1/stats/${endpoint}?${query}`, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
    tags,
  });
}
