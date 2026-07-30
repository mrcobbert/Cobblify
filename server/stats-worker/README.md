# Cobblify stats worker

Cloudflare Worker that scrapes Hypixel stats and proxies Urchin/Seraph cheater
tags for the mod. Two deployment configs:

- `wrangler.toml` - self-hosters. Deploy with `npm run deploy`. No KV binding,
  so provider tags resolve unavailable; stats work.
- `wrangler.owner.toml` - the owner's shared deployment, with the STATS_KV
  binding. Deploy ONLY with `npm run deploy:owner` (a plain `wrangler deploy`
  strips the binding and silently disables the providers).

Tests: `npm test` (note: the route tests bind a local server and fail with
`EPERM` in sandboxes that deny `listen` - run them outside such sandboxes).

## Tokens: the multi-token model

`STATS_TOKEN` (a Worker secret) is a **comma-separated list** of tokens, one
entry per user:

- Parsing: split on `,`, trim each entry, drop empties, dedupe preserving
  order. The **first surviving entry is the owner**.
- Each token must match `^[A-Za-z0-9_-]{16,64}$` (no commas or whitespace are
  possible inside a token).
- Unset/empty secret = the Worker is open (self-host back-compat) and all
  provider routes fail closed.
- Owner-only behavior: the `URCHIN_KEY`/`SERAPH_KEY` env secrets, when set,
  manage the owner identity's provider-key slot only. Every other identity
  manages its own slot via `POST /urchin/key` / `/seraph/key` (the in-game GUI
  fields or `/cobblify urchinkey|seraphkey`).

Each token maps to an **identity**: the first 16 hex chars of SHA-256(token).
All per-user provider state (stored key, backoff, disabled flag, breaker) is
keyed by that suffix, e.g. `urchin:cfg:key:<identity>`, so no user can read,
overwrite, or trip another user's provider access.

### Adding or revoking a token

Edit the list and set the secret again, then redeploy:

```sh
wrangler secret put STATS_TOKEN -c wrangler.owner.toml   # paste the new list
npm run deploy:owner
```

Revocation is per-token: remove that entry from the list, re-set the secret,
and redeploy. The revoked token stops authenticating; everyone else's identity
(and provider state) is unchanged.

## One-time migration after the first multi-token deploy

Provider state became per-identity; nothing reads the legacy unsuffixed KV
entries anymore. After the first deploy of the multi-token Worker:

1. Re-paste your Urchin and Seraph keys once (the in-game GUI fields under the
   Urchin/Seraph Tags cards, or `/cobblify urchinkey` / `/cobblify seraphkey`).
2. Delete the six legacy unsuffixed KV entries (use the namespace id from
   `wrangler.owner.toml`):

   ```sh
   wrangler kv key delete --namespace-id <id> "urchin:cfg:key"
   wrangler kv key delete --namespace-id <id> "urchin:cfg:backoff"
   wrangler kv key delete --namespace-id <id> "urchin:cfg:disabled"
   wrangler kv key delete --namespace-id <id> "seraph:cfg:key"
   wrangler kv key delete --namespace-id <id> "seraph:cfg:backoff"
   wrangler kv key delete --namespace-id <id> "seraph:cfg:disabled"
   ```

## Attribution

The Worker only ever sees identity hashes, never labels. The owner keeps a
**private label -> token mapping outside this repo** (which friend got which
token). To find the KV identity suffix for a token:

```sh
node -e "crypto.subtle.digest('SHA-256',new TextEncoder().encode(process.argv[1])).then(b=>console.log(Buffer.from(b).toString('hex').slice(0,16)))" <token>
```

That printed value is the `<identity>` suffix on the `urchin:cfg:*`/
`seraph:cfg:*` KV entries.

## Owner packaging flow

Official client builds get the backend URL and a token baked in at build time:

1. Put `cobblifyBackendUrl` and `cobblifyBackendToken` in
   `~/.gradle/gradle.properties` (never on a command line, never committed).
2. One-time: fill in `WEAVE_AGENT_SHA256` at the top of
   `tools/package-owner-bundle.sh` with the pinned Weave agent's digest
   (`shasum -a 256` output); the script refuses to run with the placeholder.
3. Run `tools/package-owner-bundle.sh`. It builds both trees, verifies the
   agent hash and the baked properties in both jars, assembles `dist-owner/`
   (the baked Forge jar + the `Cobblify-Lunar-<version>.zip` bundle), runs the
   secret-hygiene greps, and scrubs every plaintext intermediate.

`dist-owner/` is gitignored and its artifacts are **distributed privately
only** (DM). Public GitHub Releases keep receiving only the blank CI jars,
which require self-hosting.
