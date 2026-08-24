# Agent handoff prompt — batch-translation follow-ups (viewport-first, background survival, single decode)

You are implementing three follow-up optimizations to the batch-translation
pipeline of TachiyomiAT, a Kotlin Android manga reader (Mihon-based) with
on-device OCR/ONNX detection, segmentation, inpainting, AI translators, and
rendered text overlays. Work through the phases in order (A, then B, then C);
each phase must be implemented, tested, and validated before the next begins.

## Environment and build

- Repository root: the current working directory (Windows, Git Bash).
- JDK: set `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` for every
  Gradle invocation.
- Use the Gradle wrapper (`./gradlew`). App test tasks MUST include the flavor:
  `:app:testStandardDebugUnitTest`, never `testDebugUnitTest`.
- Domain tests: `:domain:testReleaseUnitTest`.
- `AGENTS.md` at the repo root is binding. Read it first, and follow its
  validation tiers exactly.

## Required reading (in this order, before writing any code)

1. `AGENTS.md` — build/test rules, validation tiers, editing rules.
2. `docs/project_context/planning.md`, `docs/project_context/implementing.md`,
   `docs/project_context/knowledge_base.md` — workflow conventions.
3. `docs/architecture/batch-translation-pipeline.md` — the current pipeline
   architecture after a recent deslimming refactor. Its closing note is law:
   do not reintroduce a second scheduler for the AI path.
4. `Plan/active/2026-08-23-batch-translation-deslimming/` — `plan.md`,
   `progress.md`, and `investigation-rolling-auto-hang.md`: what was already
   changed and why, plus the evidence-based diagnosis of the recently fixed
   test hang.

## Current state of the tree (do not undo any of this)

- Branch: `optimize_translation_finishing_page`. The working tree contains a
  large UNCOMMITTED refactor (49 files, net ~ -3,200 lines) that simplified
  the batch pipeline, plus a test fix for
  `RollingAutoCoordinatorTest.older same-spec snapshot build cannot overwrite
  newer stage`. Everything is validated green except one known pre-existing
  failure (see below). Preserve all of it; never revert unrelated local
  changes; do not commit unless explicitly instructed.
- Architectural facts you must build on (all recently established and pinned
  by tests):
  - ONE scheduler: the AI batch runs through `SequentialBatchCoordinator`
    (`app/src/main/java/eu/kanade/translation/scheduling/`) — serialized
    native lane (OCR then inpaint per page, bounded lookahead
    `MAX_NATIVE_LOOKAHEAD_PAGES`), one ordered translation lane, per-page
    render join.
  - Unified fingerprints: reader-adhoc and batch translations share the same
    fingerprint scheme (`currentTranslatorSignature`), so the batch REUSES
    reader-translated pages instead of retranslating (`PageWorkPlanner`
    emits REUSE; pinned by `PageWorkPlannerTest`).
  - Store leases arbitrate page ownership: writes go through
    `ChapterTranslationStore.updatePageGuarded` with
    `PageWriteOrigin.READER_ADHOC` vs `BATCH` provenance; stale writers are
    rejected, never clobber.
  - Rolling context = chapter glossary + always-on recent translated pairs
    (`TranslationPrompts.contextPrefix`, `updateRollingContext`,
    `store.translatedPairs()`).

## Known pre-existing failure (do not chase it)

`AotReportBubbleFillTest > reportBubbleFill preserves diagonal components
without an inset interior` fails on pristine HEAD (pixel-color assertion,
expected -16711165, got -16645372). It is unrelated to your work. When a full
suite run shows exactly this one failure, that is the expected floor.

## Phase A — viewport-first reading experience (highest value)

Objective: while a batch translation is running for the current chapter, the
reader must behave like auto-translation: the visible page and the next few
pages get translated promptly by the reader's existing single-page /
rolling-coordinator path, and the batch reuses those results instead of being
blocked by or blocking the viewport.

Verified evidence (trust this, it was confirmed with a debugging session):

- `TranslationManager.updateAutoWindow`
  (`app/src/main/java/eu/kanade/translation/TranslationManager.kt:799-802`)
  currently shuts the rolling coordinator down and returns while a batch is
  active for the chapter. `reconcileAutoWindow` (`:817-821`) applies the same
  guard. This suppression is a policy decision, not a technical necessity —
  the safety mechanisms it was afraid of already exist (leases + unified
  fingerprints above).

Implementation, in two steps:

1. Minimal (do first, validate, then decide if step 2 is still needed):
   lift the suppression so the rolling coordinator's window coexists with an
   active batch. Verify the interplay: reader-adhoc writes carry
   `translationOrigin=READER_ADHOC`; the batch planner must reuse those pages
   when fingerprints match; the batch must not stomp reader results mid-write
   (lease rejections are the mechanism — confirm both sides handle
   `PatchResult.Rejected` gracefully rather than erroring the chapter).
2. Viewport-first ordering (only if, after step 1, the batch still starves
   near-viewport pages because the native lane is busy with far pages):
   reorder the batch's page admission around the reader viewport — the
   visible page and the next few pages jump the queue. Do this by changing
   the page order handed to `coordinator.runPass1(...)` in
   `TranslationPipeline.kt` (search `runPass1`), NOT by adding a second
   scheduler or new concurrency lanes.

Acceptance criteria:

- With a batch running, opening the reader and flipping pages translates the
  visible page via the reader path without waiting for the batch to reach it,
  and the batch's subsequent pass over that page is a REUSE (no double
  translation).
- Unit tests pinning: (a) `updateAutoWindow` no longer no-ops during batch;
  (b) reader-adhoc translated page is REUSEd by the batch plan; (c) if you
  implement step 2, a test that viewport pages are admitted ahead of
  lower-index untouched pages.
- Existing suites stay green: `eu.kanade.translation.scheduling.*`,
  `PageWorkPlannerTest`, `TranslationManagerAutoArbitrationTest`,
  `SequentialBatchCoordinatorTest`.

## Phase B — background survival via foreground service

Objective: a running batch must survive the app being backgrounded (within
normal Android process-lifetime rules) and show progress in a notification.

Verified evidence: `ChapterTranslator`
(`app/src/main/java/eu/kanade/translation/ChapterTranslator.kt:177`) runs the
batch queue on a plain `CoroutineScope(SupervisorJob() + Dispatchers.IO)`;
nothing hosts translation in the background (no translation service exists in
the codebase).

Implementation:

- Add a foreground service that HOSTS the existing `ChapterTranslator`
  lifecycle — do NOT reimplement or move pipeline logic into the service.
  Follow the repo's existing download-service pattern (see
  `app/src/main/java/eu/kanade/tachiyomi/data/download/` for the canonical
  foreground-service + notification + delegate structure).
- Start when the batch queue becomes non-empty; stop (foreground + scope)
  when the queue drains or is cancelled. Notification shows per-chapter /
  per-page progress (ChapterTranslator already publishes progress snapshots)
  with a stop action that calls the existing cancel path.
- Android 8+ (minSdk 26): use a proper foreground-service type (dataSync) and
  declare `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` permissions as
  required by the target SDK.
- Respect the layering rule: the service is a UI/host-layer component; keep
  business logic in the translation layer it already lives in.

Acceptance criteria: batching started from the reader continues when the app
is backgrounded (device-verifiable; at minimum assert service start/stop is
driven by queue state in unit tests); cancelling from the notification stops
work and the service.

## Phase C — eliminate the OCR/inpaint double decode

Objective: one source-image decode per page per pipeline visit instead of two.

Verified evidence:

- OCR stage decodes the source page at
  `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt:1910`.
- The inpaint stage re-decodes the same source at `:2059`; the design comment
  at `:3704-3706` states "For the inpaint stage the page is re-decoded".
- Each decode also re-runs a SHA-256 source fingerprint (`:4174-4176`).
- A third call site exists at `:2727` (single-page path).

Implementation:

- Within one `SequentialBatchCoordinator` native-lane visit (OCR then inpaint
  back-to-back for the same page), carry the decoded bitmap — or the buffered
  source bytes + computed fingerprint — from the OCR step to the inpaint step
  instead of re-decoding. The cache must be bounded (at most one page's
  bitmap), dropped when the lane moves on, and must respect the existing
  memory model: the batch loop recycles each page's bitmap after use; one
  page's bitmap is alive at a time.
- Do NOT break the resume paths: `BatchResumeGate.INPAINT_ONLY` (around
  `:1900`) intentionally skips OCR and decodes once in inpaint; independent
  inpaint resume without a durable cleaned image still needs its own decode.
  The optimization applies only to the same-visit path.
- Prefer the smallest change that threads the existing `DecodedPage` through;
  avoid new global caches.

Acceptance criteria: batch OCR→inpaint same-visit path performs exactly one
source decode per page (assert in a focused unit test with a counting stream
factory); all existing pipeline/coordinator tests stay green; memory model
unchanged (documented in the code where the bitmap handoff happens).

## Validation protocol (per phase, per AGENTS.md)

1. Per change: focused unit tests for the touched area on
   `:app:testStandardDebugUnitTest --tests "<package>.*"`.
2. Before declaring a phase done:
   `./gradlew spotlessCheck :app:testStandardDebugUnitTest :domain:testReleaseUnitTest`
   — expected floor: everything green except the one known pre-existing
   AotReportBubbleFillTest failure.
3. Full release gate is CI-only; do not run it locally.

## Workflow and documentation

- Record checkpoints as you go in
  `Plan/active/<YYYY-MM-DD>-<topic>/checkpoints.md` (what changed, tests run,
  unexpected findings, whether the next step is still valid). Create a new
  dated folder for this effort; do not rewrite the 2026-08-23 folder's
  history, only append relevant new facts to its `progress.md` if they change
  its conclusions.
- Durable architecture changes go in `docs/architecture/` (update
  `batch-translation-pipeline.md` rather than creating near-duplicates).
- Match surrounding Kotlin/Compose style; no comment narration; self-documenting
  naming.

## Stop conditions

Stop and write up the conflict in your plan folder instead of forcing a
solution if: lease/ownership semantics clash with the coexisting reader path
in a way tests can't pin; the foreground service requires restructuring
ChapterTranslator's ownership rather than hosting it; or the decode reuse
would violate the one-bitmap-alive memory model. In each case document the
evidence (file:line, failing scenario) and the options considered.
