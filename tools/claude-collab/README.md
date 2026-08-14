# Shared Claude Code workflows: deep-change + grill-me

These slash commands live in **personal** Claude skills (`~/.claude/skills/`), not in the gitignored project `.claude/` folder. Sharing a Claude subscription does **not** copy them — each machine needs an install.

## What you get

| Slash command | Purpose |
|---|---|
| `/deep-change` | Multi-stage research → plan → independent review → approve → implement → verify |
| `/grill-me` | Explicit slash entry that starts a grilling session |
| `/grilling` | Relentless one-question-at-a-time design interview (with recommended answers) |

`/deep-change` prefers the repo’s `.ai/workflows/deep-change.md` when present; otherwise it uses the bundled generic protocol.

## Install (friend machine)

From this repo (or from a zip of this folder):

```bash
cd tools/claude-collab
chmod +x install.sh
./install.sh
```

If the project is missing `.ai/workflows/deep-change.md` (that path is gitignored), seed it:

```bash
./install.sh --with-project --project /path/to/bedwarsqol
```

Then restart Claude Code (or start a new session) and confirm:

```text
/deep-change
/grill-me
```

### Full `/deep-change` parity

1. **Claude Code** installed and logged into the shared subscription.
2. **Codex CLI** on `PATH` (`npm i -g @openai/codex` or your usual install). Reviews are supposed to launch Codex automatically at `PLAN_REVIEW` / `CODE_REVIEW`. Without Codex, Claude should report failure and ask how to proceed — not silently self-review unless you ask.
3. Project files: `AGENTS.md` / `CLAUDE.md` (committed) and `.ai/workflows/deep-change.md` (local; seed with `--with-project`).

`/grill-me` needs only the skills — no Codex.

## How to use

### Grill

```text
/grill-me
```

Then describe the plan/design (or point at a file). Claude asks **one** question at a time, with a recommended answer, until the tree is resolved.

### Deep-change

```text
/deep-change
```

Optional role pin:

```text
/deep-change planner
/deep-change implementer
```

If you omit the role, it inspects `.ai/TASK.md` `Stage:` and runs only the next valid role, then stops at approval gates (especially `AWAITING_APPROVAL` before any product-code edits).

## No-install fallback

If skills cannot be installed, paste:

- `prompts/grill-me.md`
- `prompts/deep-change.md`

## Zip for AirDrop / Messages

```bash
cd tools/claude-collab
./make-zip.sh
# -> ../../dist-owner/claude-collab-skills.zip  (or ./claude-collab-skills.zip)
```

Friend unzip → `./install.sh`.

## Syncing updates

Re-run `./install.sh` after this folder changes. It replaces only `deep-change`, `grill-me`, and `grilling` under `~/.claude/skills/`. With `--with-project`, existing `.ai/` files are never overwritten.
