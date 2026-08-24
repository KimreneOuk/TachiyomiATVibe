# Batch translation repair order

## Settled contract

For a token-adaptive image chunk, the batch completes OCR for every page in the chunk, then runs that chunk's AI translation and inpaint work concurrently, renders after both prerequisites are complete, and only then admits the next image chunk. One OCR-only probe page may establish that the preceding whole pages fill the provider-safe envelope; the probe cannot advance to AI, inpaint, render, or further native admission until that preceding chunk is terminal. This is a phase barrier inside the existing `SequentialBatchCoordinator`, not a second scheduler or new concurrency lane.

On resume, stage-complete pages are reused independently, but rolling context is rebuilt in natural order only from completed predecessors. Later completed pages are preserved but never fed into an earlier missing page's request. A non-textless terminal gap blocks new later AI translations until it is retried or explicitly resolved.

## Evidence

- A paused chapter retained a valid backup artifact manifest with 34 pages even while its flat chapter JSON was zero bytes and its primary manifest was absent. Resume progress is not reliably reconstructed from durable state.
- Older flat chapter maps can contain batch-context keys removed by the current refactor. Strict decoding treats them as a complete failure.
- Reader pause and exit synchronously perform scheduler cancellation and translation-store persistence on the Android main thread.
- The coordinator currently releases native lookahead when a page is buffered by the AI planner. Buffered pages can therefore let native OCR and inpaint run through the chapter before an AI envelope is sent or committed.

## Repair sequence

1. Make persisted work recoverable and visible.
   - Use a compatibility JSON reader with `ignoreUnknownKeys` for flat legacy page-map reads only.
   - Recover a valid backup manifest when primary manifest promotion was interrupted, without overwriting valid artifacts.
   - Rebuild `TranslationBatchProgressTracker` from the store immediately on tracker creation, before resumed work begins.
   - Tests: legacy removed fields preserve a page; backup recovery preserves manifest pages; resumed ready page is immediately represented in every completed stage counter.

2. Remove reader-exit work from the main thread.
   - Make reader lifecycle callbacks schedule cancellation, transient-page cleanup, and active-store eviction off main.
   - Ensure no undispatched suspend prefix can reach `runBlocking`, store mutex work, artifact I/O, or the bounded pending-persist join on main.
   - Tests: reader-exit entry point returns without waiting for store persistence; batch-active reader cleanup remains safe.

3. Enforce the strict adaptive image-chunk barrier in the one existing coordinator.
   - Retain chunk ownership while AI input is buffered; buffering is not translation completion.
   - Pack whole pages by the current provider budget; permit one OCR-only probe page to detect a full envelope, but do not advance that probe further or admit another page.
   - OCR every page in the chunk before starting its AI/inpaint phase.
   - Start chunk translation and inpaint concurrently after the OCR barrier; render only after both page prerequisites complete.
   - Admit no next chunk until every page of the current chunk has rendered or reached a terminal documented failure state.
   - Rebuild rolling pairs from natural-order reused predecessors; fold later reused pages into context only once the traversal reaches them; do not translate through a non-textless terminal gap.
   - Tests: a buffering AI planner cannot advance native OCR or inpaint into the next chunk; one probe is the only permitted native lookahead; a 429/parser failure retains the current chunk and reports its terminal state; fragmented resume neither retranslates completed pages nor leaks future context; rendering waits for both branches.

4. Make queue state observable.
   - Distinguish pending/buffered, running, succeeded, and failed AI pages in the progress model and UI.
   - Log an envelope's admission, provider request, retry, parser result, and terminal outcome using page-safe identifiers.
   - Tests: progress distinguishes buffered from successful and exposes terminal provider failures.

## Validation order

Run focused app tests for each repair, then `spotlessCheck :app:testStandardDebugUnitTest :domain:testReleaseUnitTest`. The existing `AotReportBubbleFillTest` pixel mismatch remains the only accepted full-suite floor. Device validation follows: pause/reinstall/resume, reader enter/exit during batch, and a controlled Gemini 429 run.
