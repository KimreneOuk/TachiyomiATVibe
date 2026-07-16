# Tasks - Unified Translation Pipeline Recovery

Status: In progress; CP0 passed and Agents A/B are being released.

## Execution rules

- Complete Checkpoint 0 before assigning production edits.
- One orchestrator owns `TranslationPipeline.kt`, `TranslationManager.kt`, final
  integration, checkpoint records, and validation.
- Agents work from frozen input/output contracts. Do not run agents concurrently
  when their file ownership overlaps.
- Every agent returns files changed, tests run, unperformed validation, unexpected
  findings, and whether its dependent checkpoint remains valid.
- Agents do not commit, push, alter unrelated files, weaken strict parsing, or
  bypass generation/edit/defunct checks.

## Implementation todo list

- [x] CP0: Configure Java, run focused baseline tests, and record dirty-worktree
  exclusions.
- [ ] CP1: Add stage-specific atomic merges, immutable work references, and one
  shared provider-request admission.
- [ ] CP2: Complete structured provider parity, retries, capabilities, and compact
  Pass-1 panel headers.
- [ ] CP3: Replace the streaming coordinator with OCR-first scheduling and prove
  no provider backpressure on OCR.
- [ ] CP4: Route manual, auto, and batch through shared stage operations.
- [ ] CP5: Freeze pure revision scope/preflight/KCU/report/metadata contracts and
  their race-safe tests.
- [ ] CP6: Implement standalone manager-owned revision and decouple it from batch
  terminal state.
- [ ] CP7: Add manga/reader preflight, confirmation, progress, cancellation, and
  result UI using only immutable manager contracts.
- [ ] CP8: Correct reader/app lifecycle and critical-memory ownership for batch
  and revision.
- [ ] CP9: Align progress, remove dead streaming paths, update stable docs, and
  verify no stale behavior claims remain.
- [ ] CP10: Run full JVM validation, diff review, independent review, and device
  memory/lifecycle/provider gates.

## Agent work packages

### Agent A - Store and admission contracts

Depends on: CP0.

Owns:

- `ChapterTranslationStore.kt`
- New pure stage-patch/work-reference/provider-admission types
- Focused store/race/admission tests

Must deliver:

- Independent translation/inpaint/render/revision merge preconditions.
- One shared provider-request gate with cancellation-safe release.
- No orchestration rewiring.

Agent B, not Agent A, owns provider capability classification. Agent A consumes
that frozen classification only when admission policy needs it.

Do not touch: UI, providers, coordinator scheduling.

### Agent B - Provider parity and panel input

Depends on: CP0. May run in parallel with Agent A; the orchestrator alone wires
both outputs into the shared provider admission.

Owns:

- Contextual translator interfaces and Pass-1 builders/parsers
- `OpenAiCompatibleTranslator` and contextual provider adapters
- Standard-provider detached adapters/retry integration
- Pass-1 compact panel-header serializer and provider contract tests

Must deliver:

- Structured results for Gemini, OpenRouter, DeepSeek, and LM Studio.
- Explicit contextual-review versus validation-only capability.
- Pass-1 `[OK]/[FLAG]` parity and strict ID accounting.
- Existing sorter order preserved; no speaker/bubble inference.

Follow-up after Agent E freezes K/C/U:

- Resume Agent B to implement review-specific adapters for Gemini, OpenRouter,
  DeepSeek, and LM Studio using the dedicated revision request/result contract.
- Prove every adapter returns strict K/C/U results and no provider inherits an
  empty default or mutates store-owned blocks.

Do not touch: manager jobs, UI, coordinator.

### Agent C - OCR-first coordinator

Depends on: Agents A and B.

Owns:

- `BatchCoordinator.kt`
- `BatchCoordinatorInterfaces.kt`
- Coordinator fake/wired tests

Must deliver:

- Ordered OCR producer, chapter-bounded reference queue, all-OCR barrier, serial
  inpaint sweep, real provider events, and one render join per page.
- Stalled-provider proof that OCR still completes.
- No bitmap/native/stream ownership in queued work.

Do not touch: reader UI, revision protocol, lifecycle policy.

### Agent D - Shared stage parity

Depends on: Agent C.

Owns with orchestrator review:

- `TranslationExecutor.kt`
- `TranslationScheduler.kt`
- Shared-stage adapters in `TranslationPipeline.kt`
- Manual/auto/batch parity tests

Must deliver:

- One implementation per OCR, Pass-1, inpaint, render, and validation operation.
- Force/resume/OOM/display behavior preserved.

Do not remove old paths until parity tests pass.

### Agent E - Pure revision contracts

Depends on: Agents A and B contract freeze. May run in parallel with Agent C after
the store-patch and provider contracts are frozen.

Owns:

- `RevisionPlanner.kt`
- `RevisionMerger.kt`
- `RevisionCommitter.kt`
- Dedicated revision request builder/parser/result types
- Immutable eligibility/preflight/KCU/progress/report models
- Backward-compatible summary/report persistence
- Pure planner/parser/merge/report tests

Must deliver:

- `FLAGGED` and `ALL_TRANSLATED` selection.
- Strict Pass-2-only K/C/U accounting.
- Stale preflight token inputs and legacy language requirements.
- Bounded latest report with accepted before/after changes.
- Freeze the K/C/U result and revision-progress snapshot consumed by Agents B,
  F, G, and H before their dependent work starts.

Do not touch: screen/view models or manager job orchestration.

### Agent F - Revision orchestration

Depends on: Agent D, Agent E, and Agent B's review-adapter follow-up.

Owns with orchestrator review:

- Manager-owned revision job/preflight/start/cancel/observe APIs
- Reusable text-only revision driver extracted from `TranslationPipeline.kt`
- Batch-terminal decoupling and revision integration tests

Must deliver:

- Cold/partial/legacy store review without image decode.
- Explicit contextual reviewer selection for any durable draft origin.
- Shared provider admission, same-chapter auto pause/drain, active-batch rejection,
  generation invalidation, and terminal report publication.

Do not add UI-specific strings or expose mutable blocks to callers.

### Agent G - Revision UI/UX

Depends on: Agent E model freeze and Agent F manager API freeze. May implement UI
after those APIs compile; do not invent duplicate eligibility logic.

Owns:

- Chapter translation action/menu presentation
- Manga screen-model preflight/confirmation/result state
- Reader translation-sheet action
- Last-selected contextual reviewer preference/picker using existing configured
  AI profiles
- Progress/result sheet presentation and strings
- Pure reducer/view-model tests where supported

Must deliver:

- One confirmation flow shared by manga and reader entry points.
- Partial/legacy/no-reviewer/active-run states.
- Exact progress/result display and accepted before/after changes.
- No credentials, blocks, stores, bitmaps, streams, or provider clients in UI
  state.

### Agent H - Lifecycle, cleanup, and validation

Depends on: Agents C, D, F, and G.

Owns:

- Reader/app lifecycle and memory-policy corrections
- Progress reducer reconciliation and dead streaming cleanup
- Focused lifecycle/progress tests
- Stable `docs/` updates after executable behavior agrees

Must deliver:

- Reader/background survival, explicit cancel/delete, critical-memory behavior,
  exact terminal delivery, bounded caches/reports, and no stale documentation.

Do not perform final integration alone; the orchestrator reviews every shared-file
change and runs CP10.

## Orchestrator-only integration tasks

- Freeze and publish shared contracts before dependent agents start.
- Resolve every edit to `TranslationPipeline.kt`, `TranslationManager.kt`, and
  progress models sequentially.
- Serialize edits to `TranslationPrompts.kt`, `ContextualRequestBuilder.kt`, and
  contextual parser contracts; Agents B and E must not edit them in parallel.
  Prefer dedicated revision protocol files.
- Review agent output against live code, not plan comments.
- Update `handoff.md` after each checkpoint using the template in
  `checkpoints.md`.
- Run focused tests after each merge, then full tests/device gates at CP10.
- Inspect the final diff for unrelated work and report all unperformed checks.

## Recommended agent waves

1. Orchestrator completes CP0.
2. Agents A and B run in parallel on disjoint store/admission and provider files.
3. After A/B contract review, Agents C and E run in parallel on coordinator and
   pure revision contracts.
4. Agent B resumes review-adapter work after E freezes K/C/U; Agent D runs after
   coordinator integration and may proceed in parallel on disjoint files.
5. Agent F runs after D/E and the Agent B follow-up integrate.
6. Agent G runs after manager/UI contracts compile.
7. Agent H runs after feature integration, followed by orchestrator CP10 review.
