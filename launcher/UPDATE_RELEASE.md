# Private launcher update operations

Launcher updates are whole private bundles: a universal macOS app archive and
a Windows x64 NSIS installer. Tauri Minisign signatures authenticate artifacts
and the critical-version policy. This project intentionally does not use paid
Apple notarization or Windows Authenticode, so the existing first-open OS
guidance still applies.

## One-time setup

The owner's account, credential, approval, and real-Mac steps are in
[MAC_RELEASE_CHECKLIST.md](MAC_RELEASE_CHECKLIST.md). Generate the updater key
outside the repository and keep tested backups of its private key and password.

The owner's Cloudflare resources were provisioned on September 2, 2026. For a
replacement account, create them from `server/stats-worker/`, then put the
returned D1 database id in `wrangler.owner.toml`:

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

The GitHub Actions secrets and variable are listed in that checklist. They are
stored once at repository level; each workflow job references only the values
it needs. The Cloudflare API token needs only R2 object-write access for the
selected account. The R2 S3 credentials are used only to prune old immutable
releases.

## Release and promotion

1. Align every version source; `tools/check-release-version.sh` must pass.
2. Run `Launcher Update Candidate` manually with the exact version.
3. After it finishes, download and test its Mac installation ZIP. Record the run
   ID shown in the workflow summary.
4. If it passes, manually run `Promote Launcher Update` with that candidate run
   ID, version, minimum supported version, short notes, and a public HTTPS
   release-notes URL.
5. Promotion verifies the source workflow, successful run, commit, run ID,
   version, and exact artifact counts. It downloads the stored candidate files,
   never rebuilds them, and refuses to overwrite an existing version.
6. Promotion uploads both immutable update artifacts first and writes
   `channels/stable.json` last. That final write is the atomic channel switch.

If a candidate fails testing, do not run promotion. Build a higher corrected
version instead.

Promotion retains the newest three immutable release prefixes. There is no
beta channel, staged rollout, remote rollback button, or per-device enrollment;
to recover from a bad stable release, ship a higher corrected version.

## Privacy and diagnostics

The client sends only fixed update event names, current/target versions,
platform, and architecture. It never sends the backend identity. D1 rows older
than 30 days are deleted by the Worker's scheduled handler. Update transport
errors are exposed to users as stable diagnostic codes; tokens and signed URLs
must never be logged.
