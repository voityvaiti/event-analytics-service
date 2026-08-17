# Development keys

A throwaway RSA-2048 key pair, committed on purpose so that a clone can run the
app and the whole perf suite with nothing but Docker. **It is not a secret and
must never be used anywhere deployed.**

| File | Who reads it |
|------|--------------|
| `dev-only-unsafe-private-key.pem` | the signer — `perf/lib/mint-token.mjs`, and the integration tests |
| `../src/main/resources/keys/dev-public-key.pem` | the app, at startup |

The two halves live in different directories deliberately: only the public half
belongs on the classpath, so the private key cannot end up inside the packaged
jar.

## Why one pair and not one per tenant

A JWT's signature covers the whole payload, the `tenant` claim included, so the
claim is exactly as trustworthy as the key that signed it. A second key would add
nothing — anyone able to forge the claim could already forge anything else.

Per-tenant keys would mean the app holding N public keys plus a key-to-tenant
mapping, which turns "add a tenant" into a config change and a restart. That
reintroduces as configuration the very lookup the design keeps off the ingest
path. Keys per tenant are for federated issuers — several parties minting tokens,
an `iss` claim and a JWKS each — which this service does not have.

## Regenerating

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out dev-keys/dev-only-unsafe-private-key.pem
openssl rsa -pubout -in dev-keys/dev-only-unsafe-private-key.pem \
  -out src/main/resources/keys/dev-public-key.pem
```

Both files must be regenerated together; a mismatched pair fails every request
with a 401 and no other symptom.

## Deployment

`spring.security.oauth2.resourceserver.jwt.public-key-location` defaults to the
committed public key so local runs and tests need no extra configuration. A
deployment **must** override it — otherwise it trusts tokens that anyone with a
copy of this repository can sign.
