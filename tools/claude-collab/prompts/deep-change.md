# Deep-change (paste-ready fallback)

Use this when the `/deep-change` skill is not installed. Prefer installing the skill via `tools/claude-collab/install.sh` so Claude auto-loads the protocol.

---

Run the deep-change workflow for this task.

1. Read `AGENTS.md` and/or `CLAUDE.md` completely.
2. If `.ai/workflows/deep-change.md` exists, read it completely and follow it (it wins over any generic protocol). Otherwise follow the generic deep-change protocol: coordination files under `.ai/` (`TASK.md`, `PLAN.md`, `REVIEW.md`, `HANDOFF.md`); roles `task-author`, `planner`, `plan-reviewer`, `implementer`, `code-reviewer`; state machine `DRAFT -> RESEARCHING -> PLAN_REVIEW -> AWAITING_APPROVAL -> IMPLEMENTING -> CODE_REVIEW -> FIXING -> VERIFYING -> COMPLETE`.
3. Inspect `.ai/TASK.md`, `.ai/PLAN.md`, `.ai/REVIEW.md`, and `.ai/HANDOFF.md` to determine the current stage. Create `.ai/` on first use.
4. Perform only the role I name. If I name none, perform only the next valid role. Stop at approval gates.
5. Never assume approval. Never overwrite another role’s artifact unless assigned that role. Planning/review roles must not edit product code.
6. When the next valid role is `plan-reviewer` or `code-reviewer`, immediately spawn Codex with:

```bash
codex \
  --ask-for-approval never \
  --sandbox workspace-write \
  exec \
  --cd "$(pwd)" \
  --output-last-message .ai/codex-review-last.txt \
  - < .ai/prompts/plan-reviewer.md
```

(Use `.ai/prompts/code-reviewer.md` for code review.) Do not ask me whether to launch Codex. If Codex is missing after one retry, report failure and ask how to proceed. After Codex finishes, if Stage is `AWAITING_APPROVAL`, stop and request explicit approval of `.ai/PLAN.md`.

Record durable results in the coordination files rather than relying on conversation history.

My role request (or “auto”): AUTO
Task / context:
