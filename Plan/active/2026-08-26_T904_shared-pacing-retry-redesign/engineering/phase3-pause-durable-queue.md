# T904 Phase 3: pause, durable failure, and queue rearm

## Scope and predecessors

Phase 3 is implemented on `t904/pause-durable` after the Phase 1 governor and
Phase 2 semantic-retry changes:

- Phase 1: `dcd29c89c7d2e28a73bb639c2b6b3ce3336462f6` (local cherry-pick `bdba261`)
- Phase 2: `1f62fe03ed4d8c40bae4ee419f9998b37df37265` (local cherry-pick `5869092`)

The implementation keeps the existing OCR, inpainting, chunk sizing, and
normal rendering paths intact. It adds pause and durability at their existing
boundaries rather than changing concurrency policy.

## Coordinator and pipeline pause semantics

`TranslatorLaneWorker` and chunk completion now have typed completed, paused,
and failed outcomes. A paused outcome carries the unresolved anchor, committed
natural-order prefix, retryable page keys, safe failure metadata, and the next
eligible retry time. `SequentialBatchCoordinator` stops admission immediately
after the first non-completed chunk, closes translation/render gates, settles
non-rendered branches, and releases its leases/native handoffs. The pipeline
applies AI results only to the committed prefix and anchor, and records context
only for pages that are actually ready.

The paused pass exits through an explicit reconciliation path. It leaves the
unresolved tail pending, preserves any completed prefix, and does not invoke
stranded-page tail-failure synthesis. Retryable quota/transient pauses never
call `recordContextPage(... terminalFailure = true)`. Terminal refusal,
configuration/authentication, source, and protocol failures remain distinct
from retryable pauses.

## Atomic artifact durability

Artifact-backed chapter writes publish a candidate sidecar and the durable
failure/stage record in one manifest publication. The committed display and
generation remain unchanged until candidate promotion succeeds. Durable failure
metadata includes safe category/message, fingerprint, retry time, envelope ID,
and missing-block IDs; raw prompts, bodies, and keys are not persisted in
diagnostics. Successful promotion clears translation failure metadata
explicitly. Demotion, deletion, cancellation, and page rekeying clean up or
move associated metadata.

The manifest schema is additively advanced to version 2 with defaults for old
files. Interrupted running stages are recovered as retryable durable failures
(`LEGACY_UNKNOWN`, immediately eligible), so a process restart does not turn an
unfinished candidate into a terminal error or silently discard it.

## Planner, status, and queue lifecycle

`PageWorkPlanner` distinguishes `FAILED_RETRYABLE` from `FAILED_TERMINAL`,
honors the persisted cooldown, and rejects stale failure fingerprints. A
retryable page is eligible only when due (unless an explicit force-retry is
used); terminal failures remain blocked. Chapter artifact status exposes
retryable durable failures as `Translation.State.PAUSED`, with `PAUSED` appended
after the existing values 0–5. Translation manager, chapter translator, and
foreground-service active predicates count only `QUEUE` and `TRANSLATING` as
active work; a paused chapter remains durable queue state without being
reported as actively running.

Queue restoration rehydrates paused/error state without auto-starting work.
`requeueExisting` is idempotent, honors `nextEligibleRetryAt` unless forced,
and explicitly moves a paused chapter back to `QUEUE`. It does not preempt a
currently translating chapter. This preserves restart-as-user-start semantics
while allowing a later user/scheduler rearm.

## Focused tests

Successful focused verification after the final source changes:

```text
.\gradlew.bat spotlessApply :app:testStandardDebugUnitTest \
  --tests 'eu.kanade.translation.batch.SequentialBatchCoordinatorTest' \
  --tests 'eu.kanade.translation.artifact.ChapterArtifactStoreTest' \
  --tests 'eu.kanade.translation.model.PageWorkPlannerTest' \
  --tests 'eu.kanade.translation.TranslationManagerAutoArbitrationTest' \
  --no-daemon --console=plain
BUILD SUCCESSFUL
```

This covers typed coordinator pause/admission stop, local translation-lane
propagation, prefix/tail reconciliation, candidate/failure atomicity,
interrupted-stage recovery, promotion cleanup, planner cooldown/terminal
classification, and paused queue active predicates. Kotlin and unit-test
compilation also passed with:

```text
.\gradlew.bat :app:compileStandardDebugKotlin \
  :app:compileStandardDebugUnitTestKotlin --no-daemon --console=plain
BUILD SUCCESSFUL
```

The required repository tier was run:

```text
.\gradlew.bat spotlessCheck :app:testStandardDebugUnitTest \
  :domain:testReleaseUnitTest --no-daemon --console=plain
```

Formatting and compilation passed. The app suite completed 1048 tests with one
failure, `AotReportBubbleFillTest.reportBubbleFill preserves diagonal
components without an inset interior` at `AotReportBubbleFillTest.kt:49`.
The director's T904 test policy records this as a deterministic failure on the
untouched base `926ae00`; it is excluded from the Phase 3 pass/fail gate and
was not changed. No other test failure was observed in the required run.

## Decisions, deviations, and risks

- Existing non-typed worker methods remain compatibility bridges. Legacy
  generic exceptions retain the prior per-page isolation behavior; production
  provider adapters must use the typed outcome or `ProviderFailureException`
  for pause/terminal classification.
- An interrupted stage is conservatively retryable with a safe legacy marker;
  the next user/scheduler rearm decides when to resume.
- No device/instrumentation test was run. The queue rearm behavior is covered
  by store/planner/manager unit tests, but an end-to-end service restart test
  remains a follow-up risk.
- Existing schema-1 manifests remain readable and retain their original schema
  number until a subsequent mutation; additive defaults make migration safe.
- Queue restoration may resolve a lazy active store to inspect durable status;
  it does not start translation or publish an artifact, but registry lifetime
  should be monitored in future lifecycle work.
- UI changes are limited to status classification needed for `PAUSED`; no new
  visuals or retry controls were introduced.
