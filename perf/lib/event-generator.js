// Deterministic, moderately-entropic event generator shared by every k6 write
// scenario, so the request shape lives in one place and the load and spike
// tests exercise the same distribution. Given a sequence number it returns a
// request body whose fields imitate a real event stream: high-cardinality
// users, a skewed event-type mix, Zipf-ish page URLs, and a property set that
// differs per event type. It is a pure function of `seq` — no Math.random(),
// no wall-clock in the distribution — so the generated stream's shape is
// identical from run to run and never injects noise into the throughput series
// the write tests are compared against.
//
// `source` is deliberately NOT part of the entropy: a fixed sentinel is what
// lets a write test identify and delete exactly its own batch afterwards, so
// realism there is traded for a reliable teardown.

const USER_SPACE = 100000;
const PAGE_SPACE = 300;
const SESSION_SPACE = 500000;

const SOURCE = 'perf-test';

const EVENT_TYPES = [
  { type: 'page_view', cumulative: 0.6 },
  { type: 'click', cumulative: 0.85 },
  { type: 'signup', cumulative: 0.95 },
  { type: 'purchase', cumulative: 1.0 },
];

const DEVICES = [
  { value: 'mobile', cumulative: 0.6 },
  { value: 'desktop', cumulative: 0.9 },
  { value: 'tablet', cumulative: 1.0 },
];

const COUNTRIES = ['UA', 'US', 'DE', 'PL', 'GB', 'FR', 'ES', 'IT'];
const REFERRERS = ['/search', '/', '/promo', 'https://google.com', 'https://t.co'];
const SIGNUP_METHODS = ['email', 'google', 'github'];
const SIGNUP_PLANS = [
  { value: 'free', cumulative: 0.8 },
  { value: 'pro', cumulative: 0.95 },
  { value: 'team', cumulative: 1.0 },
];

function hash(value) {
  let h = value | 0;
  h = Math.imul(h ^ (h >>> 16), 0x45d9f3b);
  h = Math.imul(h ^ (h >>> 16), 0x45d9f3b);
  return (h ^ (h >>> 16)) >>> 0;
}

function stream(seq, salt) {
  return hash((seq + Math.imul(salt, 0x9e3779b1)) >>> 0);
}

function unit(seq, salt) {
  return stream(seq, salt) / 4294967296;
}

function pickCumulative(table, u) {
  for (const entry of table) {
    if (u < entry.cumulative) {
      return entry;
    }
  }
  return table[table.length - 1];
}

function skewedIndex(u, size) {
  return Math.floor(size * u * u);
}

function zipfRank(u, size) {
  return Math.floor(Math.pow(size, u));
}

function pageUrl(seq) {
  const rank = zipfRank(unit(seq, 3), PAGE_SPACE);
  return rank <= 8 ? `/products/featured/${rank}` : `/products/${rank}`;
}

function properties(seq, eventType) {
  const device = pickCumulative(DEVICES, unit(seq, 4)).value;
  const country = COUNTRIES[skewedIndex(unit(seq, 5), COUNTRIES.length)];
  const referrer = REFERRERS[stream(seq, 6) % REFERRERS.length];
  const sessionId = `s_${stream(seq, 7) % SESSION_SPACE}`;

  switch (eventType) {
    case 'click':
      return {
        page_url: pageUrl(seq),
        element_id: `btn_${stream(seq, 9) % 200}`,
        device,
        country,
        session_id: sessionId,
      };
    case 'purchase':
      return {
        order_id: `o_${stream(seq, 10) % 10000000}`,
        amount_cents: (stream(seq, 11) % 100000) + 99,
        currency: country === 'UA' ? 'UAH' : 'USD',
        items: (stream(seq, 12) % 5) + 1,
        page_url: pageUrl(seq),
      };
    case 'signup':
      return {
        method: SIGNUP_METHODS[stream(seq, 13) % SIGNUP_METHODS.length],
        plan: pickCumulative(SIGNUP_PLANS, unit(seq, 14)).value,
        referrer,
        country,
      };
    default:
      return { page_url: pageUrl(seq), referrer, device, country, session_id: sessionId };
  }
}

export function generateEvent(seq) {
  const eventType = pickCumulative(EVENT_TYPES, unit(seq, 2)).type;
  return {
    source: SOURCE,
    user_id: `user_${stream(seq, 1) % USER_SPACE}`,
    event_type: eventType,
    properties: properties(seq, eventType),
  };
}
