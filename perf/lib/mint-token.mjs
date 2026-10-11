// Mint an RS256 tenant token with dev-keys/ using Node's built-in crypto.
//
// Usage: node perf/lib/mint-token.mjs <tenant> [scope]
//
// A scope such as persistence:control grants the service endpoints a tenant token cannot reach.
//
// Write only the token, without a trailing newline. Omit exp so long perf runs cannot
// turn token expiry into an apparent load failure; Spring checks expiry when present.

import { createSign } from 'node:crypto';
import { readFileSync } from 'node:fs';

const PRIVATE_KEY_PATH = 'dev-keys/dev-only-unsafe-private-key.pem';

const tenant = process.argv[2];
const scope = process.argv[3];

if (!tenant) {
  throw new Error('Usage: node perf/lib/mint-token.mjs <tenant> [scope]');
}

function segment(value) {
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

const signingInput = [
  segment({ alg: 'RS256', typ: 'JWT' }),
  segment({ tenant, ...(scope && { scope }), iat: Math.floor(Date.now() / 1000) }),
].join('.');

const signature = createSign('RSA-SHA256')
  .update(signingInput)
  .sign(readFileSync(PRIVATE_KEY_PATH))
  .toString('base64url');

process.stdout.write(`${signingInput}.${signature}`);
