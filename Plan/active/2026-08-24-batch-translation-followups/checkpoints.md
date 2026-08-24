# Batch Translation Follow-ups — Checkpoints

## Phase A — viewport-first reader coexistence

- Changed `TranslationManager` so reader auto-window updates and reconciles can run while a chapter batch is queued or translating. `translateChapter` still retires an existing coordinator when a batch is explicitly started; the next reader window update recreates it.
- Extended `TranslationManagerAutoArbitrationTest` to prove a queued batch no longer prevents the reader window from being re-armed.
- The deslimmed `PageWorkPlannerTest` already proves a reader-adhoc page with matching fingerprints is reused by batch. Store lease and guarded-write tests cover rejected stale writes.
- Viewport ordering remains unnecessary for this checkpoint: the reader path can now submit immediately through its existing rolling coordinator. Validate focused tests before Phase B.

## Phase B — foreground-service host

- Added `TranslationForegroundService` as a `dataSync` foreground service. It observes the existing manager queue, polls the current batch progress snapshot for the notification, and has no pipeline or queue implementation of its own.
- `TranslationManager.startTranslation()` starts the service whenever queued or translating batch work remains. The service stops itself and cancels its monitor scope when the queue drains; the notification stop action calls the existing `clearQueue()` path.
- Added the translation notification channel/group and declared the service with `foregroundServiceType="dataSync"`. The required foreground-service permissions were already present in the manifest.
- `BatchTranslationForegroundPolicyTest` pins that queued/translating states retain the host while terminal/empty queues stop it. Focused test passes. Run the phase gate before Phase C.

## Phase C — OCR/inpaint source handoff

- `SequentialBatchCoordinator` now carries an opaque, page-local native handoff from OCR to inpaint and guarantees its release in a `finally` block, including cancellation between stages.
- The batch pipeline supplies its existing `DecodedPage` as the handoff. Same-visit inpaint consumes it instead of re-opening the page stream; `INPAINT_ONLY` keeps its independent decode and all decoded bitmaps are recycled by the native worker release boundary.
- Added `SequentialBatchCoordinatorTest.same visit hands one source decode from ocr to inpaint`, with a counting source-stream factory. The focused coordinator class passed. Run the final gate and inspect the working-tree diff.

## Final validation

- Focused Phase A, B, and C unit-test selections passed.
- `spotlessCheck :app:testStandardDebugUnitTest :domain:testReleaseUnitTest --no-daemon` passed formatting and domain tests. The app suite completed 969 tests with the one pre-existing failure in `AotReportBubbleFillTest` (expected `-16711165`, actual `-16645372`); all new and touched tests passed.
- `git diff --check` completed without diagnostics.

## Page-atomic Gemini revision

- Replaced the static four-page / 28-block AI envelope admission with dynamic
  token-budget packing of complete pages. The coordinator has no AI envelope
  constants, and an over-budget page now fails whole rather than fragmenting
  its source text into disconnected requests.
- Removed the adaptive block-splitting retry path. HTTP 429 and transient
  Gemini failures retry the unchanged envelope; a structurally invalid response
  remains fail-closed for the affected page envelope.
- Replaced the deprecated Gemini Android client with direct `generateContent`
  requests. The Gemini setting now defaults reasoning to Disabled and exposes
  Auto and Low. Unsupported thinking configuration is omitted or retried once
  without the field; logs record status and retry timing without request text
  or credentials.
- Focused tests passed for planner atomicity, retry behavior, Gemini payload
  construction/response extraction, and page-envelope behavior.
- Gate on 2026-08-24: `spotlessCheck :app:testStandardDebugUnitTest
  :domain:testReleaseUnitTest --no-daemon` passed Spotless and domain tests;
  app ran 972 tests with only the known pre-existing
  `AotReportBubbleFillTest` pixel mismatch.

## Ticket 3 alignment fixup — 2026-08-24

- Review found that the retained OCR-only probe was admitted to the streaming
  planner at the overflow boundary and then admitted a second time when the
  next chunk began. The coordinator now retains the planner-owned probe without
  re-admission; the regression counts probe admission calls, not only unique
  page keys.
- Review also found that `SKIP_ALL` reused pages reached after a resume gap did
  not notify `BatchContextFrontier`, so durable later pages could never fold in
  once natural traversal reached them. Both reusable-page fast paths now record
  terminal pages at traversal time; the frontier still withholds them while a
  non-textless gap remains.
- Focused validation passed: `SequentialBatchCoordinatorTest` (12/12),
  `BatchContextFrontierTest` (4/4), and `StreamingChunkPlannerTest` (15/15).
  `spotlessCheck` passed; `:domain:testReleaseUnitTest` passed. The full app
  Standard Debug suite ran 982 tests with only the known pre-existing
  `AotReportBubbleFillTest` pixel mismatch.
- The strict barrier remains single-coordinator: no new lane or scheduler was
  introduced, and next-chunk OCR remains after the current chunk render join.

## Ticket 4 — AI progress observability — 2026-08-24

- Added a projection-only `AiPageProgressState` reducer and aggregate counters
  for pending, buffered, running, succeeded, and failed pages. Ordered pages are
  pre-registered before the tracker is created, so an untouched 105-page batch
  has an honest `0 / 105` denominator instead of looking stalled.
- Wired transitions at planner admission, provider execution, guarded commit
  acceptance, and rejection/failure paths. The reducer keeps terminal states
  terminal; persisted READY/PARTIAL/SKIPPED and FAILED pages rebuild as
  succeeded/failed, while in-flight projection state remains intentionally
  ephemeral.
- The progress sheet now shows succeeded/total plus pending, buffered,
  running/retrying, and failed counts and colors per-page chips accordingly.
  Envelope lifecycle diagnostics use hashed page-set identifiers and bounded
  counters; no raw page text, prompts, translations, or provider payloads are
  logged. No scheduler, chunk policy, retry behavior, or lifecycle ownership
  changed.
- Independent validation after the child’s final commit-acceptance patch:
  focused tracker/event/diagnostics/retry filters passed; the exact standard
  gate completed Spotless and domain work and ran 986 app tests with only the
  documented pre-existing `AotReportBubbleFillTest` pixel mismatch. `git
  diff --check` was clean apart from the existing model-binary CRLF warning.
