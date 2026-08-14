---
name: deep-change
description: Coordinate research-heavy bug fixes, risky features, and architectural changes through task definition, evidence-based planning, independent plan review, approval, phased implementation, code review, and verification. Use when the user invokes /deep-change or requests deep research, architecture work, compatibility-sensitive changes, or another high-risk modification.
---

# Run a deep change

If the project has its own `.ai/workflows/deep-change.md`, read it completely and follow it - it overrides this skill's generic protocol. Otherwise read `references/protocol.md` in this skill directory.

Also read the project's `AGENTS.md` and/or `CLAUDE.md` for project-specific constraints, build commands, and review concerns; apply them throughout.

Inspect `.ai/TASK.md`, `.ai/PLAN.md`, `.ai/REVIEW.md`, and `.ai/HANDOFF.md` to determine the current stage. Create the `.ai/` directory on first use.

Perform only the role explicitly requested by the user. If no role is requested, perform only the next valid role in the state machine. Respect approval gates and file ownership. Never assume approval, never overwrite unrelated work, and never let planning or review roles edit product code.

## Codex review - mandatory, no asking

When the next valid role is `plan-reviewer` or `code-reviewer`, **immediately** spawn Codex using the command in the protocol. Do this in the same turn you finish planning (or discover the stage is `PLAN_REVIEW` / `CODE_REVIEW`).

**Do not** ask the user whether to launch Codex. **Do not** offer choices like "launch now / review in-session / adjust the plan first." **Do not** wait for confirmation. Just run the Codex handoff.

Exceptions (only these):
- The user already said to review in-session instead of Codex.
- Codex is missing or the handoff fails after one retry - then report the failure and ask how to proceed.

After Codex finishes, summarize the review artifacts. If Stage is `AWAITING_APPROVAL`, stop there and request explicit user approval of `.ai/PLAN.md`. Never start implementation without that approval.

Record durable results in the coordination files rather than relying on conversation history.
