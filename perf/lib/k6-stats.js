// The /api/v1/stats request shape for every read scenario, so a change to the
// read contract touches this file rather than each scenario — the write side's
// k6-ingest.js counterpart.
//
// The endpoints differ only in path and query parameters, so they share one
// request builder instead of one file each; which endpoint a cell measures is
// the caller's choice, not a separate copy of the same code.

import http from 'k6/http';

export function getStats(baseUrl, endpoint, params, tags) {
  const query = Object.keys(params)
    .map((key) => `${key}=${encodeURIComponent(params[key])}`)
    .join('&');

  return http.get(`${baseUrl}/api/v1/stats/${endpoint}?${query}`, {
    headers: {
      // Once JWT (HS256) auth lands, add: Authorization: `Bearer ${__ENV.TOKEN}`
    },
    tags,
  });
}
