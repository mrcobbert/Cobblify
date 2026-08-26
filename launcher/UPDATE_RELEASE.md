# Private launcher update operations

Launcher updates are whole private bundles: a universal macOS app archive and
a Windows x64 NSIS installer. Tauri Minisign signatures authenticate artifacts
and the critical-version policy. This project intentionally does not use paid
Apple notarization or Windows Authenticode, so the existing first-open OS
guidance still applies.

## One-time setup

Generate one updater keypair from `launcher/`:

```sh
npx tauri signer generate --write-keys cobblify-updater.key
```

Keep the encrypted private key and password in two protected GitHub
environments (`launcher-release` and `launcher-stable`). Keep a tested offline
backup of the private key and password in separate locations. Losing the key
ends the update chain for every installed launcher; replacing it requires a
manual reinstall. The public key is safe to store as the
`TAURI_SIGNING_PUBLIC_KEY` secret, but it must be the base64 value Tauri emits,
not a path.

Create owner Cloudflare resources from `server/stats-worker/`, then uncomment
their bindings in `wrangler.owner.toml` using the returned D1 database id:

```sh
npx wrangler r2 bucket create cobblify-launcher-updates
npx wrangler d1 create cobblify-launcher-update-events
npx wrangler d1 migrations apply UPDATE_EVENTS --remote -c wrangler.owner.toml
npx wrangler secret put UPDATE_URL_SECRET -c wrangler.owner.toml
npm run deploy:owner
```

The R2 bucket must remain private. `UPDATE_URL_SECRET` is a separate random
secret used only for 15-minute download tickets; it is not the backend token or
the Minisign key.

Configure the protected GitHub environments with required reviewers and these
values:

- Secrets: `COBBLIFY_BACKEND_URL`, `COBBLIFY_BACKEND_TOKEN`,
  `COBBLIFY_UPDATE_URL`, `WEAVE_AGENT_B64`, `TAURI_SIGNING_PRIVATE_KEY`,
  `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`, `TAURI_SIGNING_PUBLIC_KEY`,
  `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, and
  `R2_SECRET_ACCESS_KEY`.
- Variable: `COBBLIFY_UPDATE_BUCKET=cobblify-launcher-updates`.

The Cloudflare API token needs only R2 object-write access for the selected
account. The R2 S3 credentials are used only to prune old immutable releases.

## Release and promotion

1. Align every version source; `tools/check-release-version.sh` must pass.
2. Run `Launcher Update Candidate` manually with the exact version, minimum
   supported version, short notes, and an HTTPS release-notes URL.
3. Leave **promote** off for a candidate-only build. Download and test both
   30-day workflow artifacts on real machines.
4. Re-run the same trusted ref with **promote** on. The protected stable job
   uploads both immutable artifacts first and writes `channels/stable.json`
   last. That final write is the atomic channel promotion.

Promotion retains the newest three immutable release prefixes. There is no
beta channel, staged rollout, remote rollback button, or per-device enrollment;
to recover from a bad stable release, ship a higher corrected version.

## Privacy and diagnostics

The client sends only fixed update event names, current/target versions,
platform, and architecture. It never sends the backend identity. D1 rows older
than 30 days are deleted by the Worker's scheduled handler. Update transport
errors are exposed to users as stable diagnostic codes; tokens and signed URLs
must never be logged.
