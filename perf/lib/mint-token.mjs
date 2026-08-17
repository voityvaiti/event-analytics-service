// Mints an RS256 bearer token for one tenant, signed with the throwaway key in
// dev-keys/. Node's built-in crypto does the whole job, so this needs no
// dependencies and runs from the same pinned node image the corpus seeder uses —
// keeping the suite's "nothing but Docker and a running app" promise.
//
// Usage: node perf/lib/mint-token.mjs <tenant>
//
// Writes the token to stdout with no trailing newline, so a caller can use it
// directly in an Authorization header.
//
// No `exp` claim is emitted, and that is deliberate. Spring validates expiry only
// when the claim is present, and a full suite run — ten cells, three rounds, 20M
// rows — outlives any lifetime worth choosing. A token expiring mid-run would
// surface as a nonzero failed_rate in a journal row, which reads as the service
// failing under load rather than as an auth problem, and the row would be wrong
// in a way nobody would think to question.

import { createSign } from 'node:crypto';
import { readFileSync } from 'node:fs';

const PRIVATE_KEY_PATH = 'dev-keys/dev-only-unsafe-private-key.pem';

const tenant = process.argv[2];

if (!tenant) {
  throw new Error('Usage: node perf/lib/mint-token.mjs <tenant>');
}

function segment(value) {
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

const signingInput = [
  segment({ alg: 'RS256', typ: 'JWT' }),
  segment({ tenant, iat: Math.floor(Date.now() / 1000) }),
].join('.');

const signature = createSign('RSA-SHA256')
  .update(signingInput)
  .sign(readFileSync(PRIVATE_KEY_PATH))
  .toString('base64url');

process.stdout.write(`${signingInput}.${signature}`);
