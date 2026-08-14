# Deep change workflow (generic protocol)

Use this protocol for research-heavy bugs, risky features, or architectural changes. One agent may perform multiple roles over time, but planning and review should use different model sessions when practical. Only one implementation session may edit product code at a time.

## Coordination files

All live under `.ai/` in the repo root. Create them on first use.

- `.ai/TASK.md`: user-owned task contract: problem, scope, constraints, acceptance criteria, and a `Stage:` field tracking approval state.
- `.ai/PLAN.md`: planner-owned research and proposed implementation plan.
- `.ai/REVIEW.md`: reviewer-owned findings. Reviewers do not edit implementation code.
- `.ai/HANDOFF.md`: implementer-owned concise state for the next agent or session.

Never overwrite another role's artifact without being assigned that role.

## Invocation

Invoke `deep-change` and specify one role: `task-author`, `planner`, `plan-reviewer`, `implementer`, or `code-reviewer`. If no role is supplied, inspect the workflow state and perform only the next valid role. Stop when a user approval gate is reached.

## Codex review handoff

When the current session is Claude (or any non-Codex planner/implementer) and the next valid role is `plan-reviewer` or `code-reviewer`, **immediately** spawn an independent Codex review. Do not review in the same session. Do not ask the user for permission. Launch Codex in the same turn the stage becomes `PLAN_REVIEW` or `CODE_REVIEW`.

Only skip auto-launch if the user already ordered an in-session review, or if Codex is unavailable (then report failure after one retry).

If the project has prompt files at `.ai/prompts/plan-reviewer.md` and `.ai/prompts/code-reviewer.md`, use them. Otherwise write them first from the role checklists below (state the read/write file rules, the checklist, the finding classifications, and the stage transitions; end with "Then stop. Do not implement."). Do not rewrite an existing prompt in a way that argues for the plan or softens findings.

Use this command from the repo root (global approval/sandbox flags must come **before** `exec`):

```bash
codex \
  --ask-for-approval never \
  --sandbox workspace-write \
  exec \
  --cd "$(pwd)" \
  --output-last-message .ai/codex-review-last.txt \
  - < .ai/prompts/plan-reviewer.md
```

For code review, use the same flags but pipe `.ai/prompts/code-reviewer.md` instead.

Rules:

- Codex may write only `.ai/REVIEW.md`, `.ai/HANDOFF.md`, and (Stage only) `.ai/TASK.md`. It must not edit product code.
- After Codex exits, read `.ai/REVIEW.md`, `.ai/HANDOFF.md`, `.ai/TASK.md`, and `.ai/codex-review-last.txt`. Summarize the outcome to the user.
- If Stage is `AWAITING_APPROVAL`, stop and request explicit user approval of `.ai/PLAN.md`. Never assume approval and never start implementation in the same uninterrupted step.
- If Codex fails, is missing, or produces an incomplete review, report that and either retry once or ask the user how to proceed. Do not silently self-review as a substitute unless the user asks.
- `--ask-for-approval never` only skips Codex command-approval prompts. It does not replace the workflow's human `AWAITING_APPROVAL` gate.
- Do not use `--dangerously-bypass-approvals-and-sandbox` or deprecated `--full-auto`.

## State machine

`DRAFT -> RESEARCHING -> PLAN_REVIEW -> AWAITING_APPROVAL -> IMPLEMENTING -> CODE_REVIEW -> FIXING -> VERIFYING -> COMPLETE`

Do not skip `AWAITING_APPROVAL` for architectural changes. Do not mark `COMPLETE` with unresolved blocking findings or undocumented required verification.

## 1. Task author

Update only `.ai/TASK.md` unless source inspection is needed to clarify scope.

- Define observed and expected behavior.
- Record reproduction, constraints, acceptance criteria, and explicit exclusions.
- Set Stage to `RESEARCHING` when the task is sufficiently concrete.
- Do not prescribe a solution unless it is a genuine constraint.

## 2. Planner

Read the project's `AGENTS.md`/`CLAUDE.md`, `.ai/TASK.md`, relevant code, Git history when useful, and current working-tree state. Write `.ai/PLAN.md`; do not edit product code.

- Reproduce or characterize the behavior before settling on a cause.
- Conduct external research only where repository evidence is insufficient or behavior is version-dependent.
- Prefer primary sources: official documentation, upstream source, issue trackers maintained by the relevant project, specifications, and original research.
- Record source URLs, dates/versions, supported claims, and uncertainty.
- Produce falsifiable root-cause hypotheses, a minimal solution, regression strategy, phased work, risks, and rollback.
- Set `.ai/TASK.md` Stage to `PLAN_REVIEW` and update `.ai/HANDOFF.md`.
- If this session is Claude (or any non-Codex planner), immediately run the Codex plan-reviewer handoff in the same turn. Do not ask the user first.

## 3. Plan reviewer

Prefer the Codex review handoff above when Claude reached this stage. Read the task, plan, relevant source, and cited research. Write only `.ai/REVIEW.md` and `.ai/HANDOFF.md`; do not edit the plan or product code.

Check:

- Does the evidence actually support the proposed root cause?
- Were plausible competing hypotheses tested?
- Is the solution smaller than necessary, or does it hide unrelated refactoring?
- Are the project-specific concerns from `AGENTS.md`/`CLAUDE.md` (runtime versions, platform constraints, concurrency, compatibility) addressed where relevant?
- Could the change create crashes, data loss, security, or privacy issues?
- Are regression and manual verification adequate?

Classify findings as `BLOCKING`, `IMPORTANT`, or `OPTIONAL`. Set the task Stage back to `RESEARCHING` when blocking evidence gaps exist; otherwise set it to `AWAITING_APPROVAL`.

## 4. Planner revision and approval

The planner resolves every finding in `.ai/PLAN.md`, marking it accepted or rejected with evidence. A reviewer may re-review if blocking findings changed the design materially.

Stop and request explicit user approval of `.ai/PLAN.md`. Record approval in `.ai/TASK.md`; then set Stage to `IMPLEMENTING`.

## 5. Implementer

Confirm the plan is approved and record the starting Git status. Implement one plan phase at a time.

- Do not expand scope silently.
- Add characterization or regression coverage before or alongside the fix.
- After each phase, run the narrowest useful checks and update `.ai/HANDOFF.md`.
- If evidence disproves the plan, stop, set Stage to `RESEARCHING`, and hand back to the planner.
- When implementation and implementer checks finish, set Stage to `CODE_REVIEW`.

## 6. Code reviewer

Prefer the Codex review handoff above when Claude reached this stage; launch it immediately with no confirmation prompt. Review the actual diff against the approved task and plan. Write only `.ai/REVIEW.md` and `.ai/HANDOFF.md`.

- Inspect for correctness, regressions, concurrency hazards, compatibility, and missing tests.
- Independently run relevant checks rather than trusting the handoff.
- Give file and line references for findings.
- Set Stage to `FIXING` if blocking or important findings exist; otherwise set it to `VERIFYING`.

## 7. Fix and verify

The implementer resolves `BLOCKING` and `IMPORTANT` findings or records an evidence-backed rejection. The reviewer rechecks material fixes.

Run the approved verification plan, including the project's build/test commands when applicable. Record exact outcomes and distinguish automated from manual verification. Set Stage to `COMPLETE` only when acceptance criteria are satisfied; otherwise document what remains.

## Cost control

Do not use this workflow for trivial documentation, formatting, mechanical dependency edits, or small well-understood fixes. For a normal nontrivial fix, use a shortened path: `IMPLEMENTING -> CODE_REVIEW -> VERIFYING`.
