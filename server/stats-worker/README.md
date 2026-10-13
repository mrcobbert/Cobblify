# Cobblify stats worker

Cloudflare Worker that scrapes Hypixel stats and proxies Urchin cheater tags
for the mod. (Seraph was a second tag source until its API shut down in October
2026; see "Seraph (retired)" below.) Two deployment configs:

- `wrangler.toml` - self-hosters. Deploy with `npm run deploy`. No KV binding,
  so provider tags resolve unavailable; stats work.
- `wrangler.owner.toml` - the owner's shared deployment, with the STATS_KV
  binding. Deploy ONLY with `npm run deploy:owner` (a plain `wrangler deploy`
  strips the binding and silently disables the providers).

To deploy and immediately probe the result, run
`WRANGLER_CONFIG=wrangler.owner.toml WORKER_TOKEN=<token> npm run deploy-and-test`.
`WRANGLER_CONFIG` selects the config that script deploys (default `wrangler.toml`,
so the owner must set it, for the reason above) and it prints which one it used.
Both probe scripts send `WORKER_TOKEN` as the `X-BedwarsQol-Token` header: a
token-gated deployment answers 401 to every route without it, and the probe then
says so and names the variable.

The owner deployment can also serve private launcher updates. Add the
`UPDATE_BUCKET` R2 and `UPDATE_EVENTS` D1 bindings documented in
`wrangler.owner.toml`, apply `migrations/0001_launcher_update_events.sql`, and
set `UPDATE_URL_SECRET`. Metadata uses the existing `STATS_TOKEN` identity;
artifacts remain private behind expiring HMAC download tickets. Update telemetry
contains no identity and is deleted after 30 days.

Tests: `npm test` (note: the route tests bind a local server and fail with
`EPERM` in sandboxes that deny `listen` - run them outside such sandboxes).

## Tokens: the multi-token model

`STATS_TOKEN` (a Worker secret) is a **comma-separated list** of tokens, one
entry per user:

- Parsing: split on `,`, trim each entry, drop empties, dedupe preserving
  order. Each surviving entry must match `^[A-Za-z0-9_-]{16,64}$` (no commas
  or whitespace are possible inside a token); entries that do not are
  **dropped** and can never authenticate. The **first surviving valid entry
  is the owner**.
- Unset/empty secret = the Worker is open (self-host back-compat) and all
  provider routes fail closed. A secret with entries but **zero valid ones**
  means auth was intended and misconfigured: every authenticated route is
  denied (fail closed), never fallen open.
- Owner-only behavior: the `URCHIN_KEY` env secret, when set, manages the
  owner identity's provider-key slot only. Every other identity manages its own
  slot via `POST /urchin/key`, reachable only through the owner-facing
  `/cobblify urchinkey` command (there is no in-game UI for provider keys).

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

1. Re-paste your Urchin key once (`/cobblify urchinkey`).
2. Delete the three legacy unsuffixed KV entries (use the namespace id from
   `wrangler.owner.toml`):

   ```sh
   wrangler kv key delete --namespace-id <id> "urchin:cfg:key"
   wrangler kv key delete --namespace-id <id> "urchin:cfg:backoff"
   wrangler kv key delete --namespace-id <id> "urchin:cfg:disabled"
   ```

## Seraph (retired)

Seraph's public API (the second cheater-tag source) shut down in October 2026
and the Worker no longer calls it. Mod builds with the removal never ask for
Seraph. Older clients (0.16.x, 0.17, and 0.18 dev builds before it) still send the
`X-BWQOL-Seraph: 1` opt-in header and may call `/seraph/<uuid>` and
`/seraph/key`; the Worker answers them without any upstream call, in the shape
they treat as final, so they stop asking:

- `/bedwars/<name>?uuid=<uuid>` and `/bedwars/batch?...&uuids=...`: each
  opted-in player gets `seraphUuid` + `seraphUnavailable: true` inline on its
  stats line (no follow-up lines). Without the uuid the batch client drops the
  answer and would re-ask every 60 s.
- `GET /seraph/<uuid>`: `{success: true, uuid, tags: [], notFound: false,
  unavailable: true}` (same identity + opt-in gate and uuid validation as before).
- `POST /seraph/key`: `{key: null}` deletes the caller's old `seraph:cfg:*`
  entries and answers `{success: true}` (503 `kv_write_failed` if a delete
  fails); any key is refused with 410 `seraph_retired` and nothing is stored.

`test/seraph-retired.test.mjs` pins all of this. Owner clean-up, once, after
deploying (neither is needed for correctness):

```sh
npx wrangler secret delete SERAPH_KEY -c wrangler.owner.toml
npx wrangler kv key list --namespace-id <id> --prefix "seraph:" -c wrangler.owner.toml
# then `wrangler kv key delete --namespace-id <id> "<name>"` for each listed entry
```

## Attribution

The Worker only ever sees identity hashes, never labels. The owner keeps a
**private label -> token mapping outside this repo** (which friend got which
token). To find the KV identity suffix for a token:

```sh
node -e "crypto.subtle.digest('SHA-256',new TextEncoder().encode(process.argv[1])).then(b=>console.log(Buffer.from(b).toString('hex').slice(0,16)))" <token>
```

That printed value is the `<identity>` suffix on the `urchin:cfg:*` KV entries.

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
