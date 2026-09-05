# Your Mac release checklist

These are the steps only you can complete because they involve your accounts,
private keys, approval, or testing the real app. Do not paste any secret value
into chat or commit it to the repository.

## 1. Enable Cloudflare R2 - complete

R2 is enabled. Codex created the private updater bucket, update-events database,
Worker bindings, and download-ticket secret on September 2, 2026. The bucket has
no public URL.

## 2. Create and protect the updater signing key - complete

Run this from the repository's `launcher` folder:

```sh
mkdir -p ~/.config/cobblify-secrets
npx tauri signer generate \
  --write-keys ~/.config/cobblify-secrets/cobblify-updater.key
```

Choose a strong password when prompted. Save these items:

- The encrypted private key file.
- Its password, stored separately from the key.
- The public key printed by the command.

Keep tested backups of the private key and password. Losing either one means
installed launchers cannot trust another update and must be reinstalled.

## 3. Create Cloudflare release credentials - complete

You need two different credentials. The first lets the release workflow upload
through Wrangler. The second lets it list releases and remove old ones through
the S3 API.

### A. Create the Wrangler upload token

1. Sign in to the Cloudflare dashboard.
2. Open **Manage Account**, then **API Tokens**.
3. Select **Create Token**.
4. Choose **Create Custom Token**.
5. Name it `Cobblify GitHub release upload`.
6. Under **Permissions**, select:
   - Resource type: **Bucket**
   - Permission: **Workers R2 Storage Bucket Item**
   - Access: **Write**
7. Under **Resources**, choose **Include**, **Specific bucket**, then
   `cobblify-launcher-updates`.
8. Leave IP filtering and expiration empty unless you deliberately want the
   release workflow to expire.
9. Select **Continue to summary**, check that no other permissions or buckets
   are included, then select **Create Token**.
10. Copy the token immediately. Cloudflare shows it only once. Save it in your
    password manager as `CLOUDFLARE_API_TOKEN`.

If the custom-token screen does not offer the bucket-level permission, use
**Account**, **Workers R2 Storage**, **Write**, scoped to your Cobblify account.
That is broader because it can manage every R2 bucket in the account, but it is
compatible with Wrangler.

### B. Create the S3 cleanup credentials

1. Open **Storage & databases**, **R2**, then **Overview**.
2. In **Account Details**, find **API Tokens** and select **Manage**.
3. Select **Create Account API token**. If that option is unavailable, select
   **Create User API token** instead.
4. Name it `Cobblify GitHub release cleanup`.
5. For permission, choose **Object Read & Write**.
6. Choose **Apply to specific buckets only**.
7. Select only `cobblify-launcher-updates`.
8. Select **Create Account API token** or **Create User API token**.
9. On the confirmation page, copy both values immediately:
   - **Access Key ID** -> save as `R2_ACCESS_KEY_ID`
   - **Secret Access Key** -> save as `R2_SECRET_ACCESS_KEY`

Do not use the token labeled **API Token** from this second screen as
`CLOUDFLARE_API_TOKEN`. Object Read & Write credentials are for the S3 API; the
Wrangler upload uses the separate credential from part A.

### C. Copy the account ID

1. Return to **Storage & databases**, **R2**, **Overview**.
2. Find **Account Details**.
3. Copy **Account ID** and save it as `CLOUDFLARE_ACCOUNT_ID`.

At the end of this step your password manager should contain exactly four new
values:

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `R2_ACCESS_KEY_ID`
- `R2_SECRET_ACCESS_KEY`

Do not send these values in chat. They will be entered into GitHub in step 5.

## 4. Create a private friends token - complete

Create a new random token for this group of friends. This command copies a valid
64-character token without displaying it:

```sh
openssl rand -hex 32 | pbcopy
```

Save it in your password manager and keep it separate from your owner token.
The same value must be:

- Added to the Worker's complete `STATS_TOKEN` list.
- Stored in GitHub as `COBBLIFY_BACKEND_TOKEN`.

When updating `STATS_TOKEN`, preserve every token already in the list. Replacing
the secret with only the new token would lock out existing builds.

## 5. Configure GitHub Actions - complete

The current setup uses repository-level Actions secrets so every value is added
only once. In the repository, open **Settings**, **Secrets and variables**,
**Actions**.

These repository secrets are configured:

- `COBBLIFY_BACKEND_URL` - `https://bedwarsqol-stats.mrcobbert.workers.dev`
- `COBBLIFY_BACKEND_TOKEN` - the new friends token
- `COBBLIFY_UPDATE_URL` - `https://bedwarsqol-stats.mrcobbert.workers.dev`
- `WEAVE_AGENT_PASSPHRASE` - the short password used to decrypt the pinned
  Weave Loader Agent stored in the repository
- `TAURI_SIGNING_PRIVATE_KEY` - the full encrypted private-key contents
- `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` - the key password
- `TAURI_SIGNING_PUBLIC_KEY` - the public key value, not a file path
- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `R2_ACCESS_KEY_ID`
- `R2_SECRET_ACCESS_KEY`

This repository variable is configured:

```text
COBBLIFY_UPDATE_BUCKET=cobblify-launcher-updates
```

The workflows use the `launcher-release` and `launcher-stable` environments,
but they reference only the repository secrets needed by each job. The separate,
manually started promotion workflow is the approval gate.

The Weave Loader Agent is too large for a GitHub secret. Only its short
decryption password is stored in GitHub; the repository contains the encrypted
jar. Never commit the unencrypted jar.

## 6. Prepare release notes - complete

The public release-notes URL for 0.10.0 is:

```text
https://gist.github.com/mrcobbert/81884fe97f4e58237e6fa8effb04971e
```

## 7. Test and promote the Mac release

Codex will prepare a `Launcher Update Candidate` run. After it finishes:

1. Write down the candidate run ID shown in its summary.
2. Download that run's Mac installation ZIP from GitHub Actions.
3. Install it in `Applications` and open it through Finder.
4. If macOS blocks it, use **System Settings**, **Privacy & Security**, then
   **Open Anyway**.
5. Confirm the launcher starts and the bundled Forge and Lunar options install.
6. Launch Minecraft and test one supported game mode.
7. Confirm unsupported game modes do not show the launcher dashboard.
8. If those checks pass, manually start `Promote Launcher Update` and enter the
   candidate run ID. That workflow publishes the already-tested files without
   rebuilding them.
9. Test updating an older launcher to the newly promoted build, including
   restart-to-install.

The first updater-enabled build seeds the update system. A second, higher test
version is required to prove that an installed launcher can update itself.

## When you are ready

Tell Codex only that these items are complete; do not send the values:

- [x] R2 enabled
- [ ] Updater key backed up
- [x] GitHub Actions secrets and variable ready
- [x] Public release-notes URL ready
