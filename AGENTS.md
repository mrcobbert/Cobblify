# BedwarsQOL Agent Instructions

## Project shape

- This is a Java 8 Minecraft 1.8.9 Forge mod built with Gradle.
- Forge source lives under `src/main/`; Lunar (Weave) source under `lunar/src/main/`.
- `common/src/` holds code that is identical on both platforms and imports no Minecraft, Forge,
  Weave, LWJGL or Mixin type. **Both builds compile it**, as additional main and test source
  directories. Edit it once.
- The two platform trees still mirror each other for 48 main files. Some differ on purpose, some
  are identical but touch `net.minecraft` and were left alone. Before changing a mirrored file,
  compare both trees and keep intentional equivalents synchronized.
- `tools/tree-divergence.txt` declares every legitimate difference: pairs allowed to differ, and
  files that exist on one side only. `tools/check-tree-drift.sh` enforces it and runs in CI ahead
  of release publishing. If you make the trees differ, declare it there or CI fails.
- Writing **new** platform-neutral code? Put it straight in `common/`. Do not create a copy in each
  tree first - that is the duplication this layout exists to prevent.
- **Moving an existing mirrored file** into `common/` is only correct if the two copies are already
  byte-identical and it has no platform imports. If it needs an edit to compile on both, it does
  not belong there.
- Build with `./gradlew build` (Forge, JDK 17-21) and `./gradlew build` in `lunar/`. Run narrower
  relevant checks first when available. Note `harness.LaneContrastTest` is a wall-clock benchmark,
  not a correctness test - it dominates the Forge suite runtime and is timing-sensitive.

## Safety

- Read `git status --short` before editing.
- Preserve unrelated and pre-existing changes. Never discard or overwrite them.
- Treat generated output, game logs, credentials, API keys, and player data as sensitive.
- Do not claim Minecraft runtime behavior was verified unless the relevant client scenario was actually exercised.
- Prefer focused changes over opportunistic refactors.

## Workflow selection

Use the normal workflow for small, well-understood changes: inspect, implement, run relevant checks, and summarize.

Use the `deep-change` workflow when the user invokes it or when work involves unclear game behavior, mixins, networking, compatibility, security, architectural changes, migrations, or a bug whose cause requires substantial research. Do not impose this workflow on routine edits unless the risk justifies it.

The shared protocol is `.ai/workflows/deep-change.md`. Provider skills are thin entry points to that protocol.

## Coordination files

- `.ai/TASK.md`: user-owned task contract: problem, scope, constraints, acceptance criteria, and approval state.
- `.ai/PLAN.md`: planner-owned research and proposed implementation plan.
- `.ai/REVIEW.md`: reviewer-owned findings. Reviewers do not edit implementation code unless explicitly asked later to implement fixes.
- `.ai/HANDOFF.md`: implementer-owned concise state for the next agent or session.

Do not treat these files as automatic orchestration. Follow the ownership and stage rules in the selected workflow. Never overwrite another role's artifact without being assigned that role.

## Verification

- Record exact commands and outcomes in `.ai/HANDOFF.md` during a deep-change run.
- Distinguish passing checks, failing checks, and checks not run.
- A build alone does not prove in-game behavior. State any remaining manual verification clearly.
