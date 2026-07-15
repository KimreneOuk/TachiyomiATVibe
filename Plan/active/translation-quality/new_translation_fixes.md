# Translation Pipeline Correctness and Performance Plan

## Summary

- Pass 2 is wired for AI batch translation and awaited before completion, but it falsely counts missing responses as successful, clears revision flags without valid corrections, and can overwrite concurrent edits.
- Cleaned-only pages occur because the reader exposes the cleaned image after inpainting while the text overlay correctly waits for translation/render readiness.
- Translation currently receives each page only after OCR, inpainting, and cleaned-image persistence; the documented OCR→translation overlap is not implemented.
- Deliver this as three checkpoints: correctness/state ownership, pipeline scheduling, then progress/lifecycle cleanup.

## Implementation Changes

### 1. Correct state ownership and display behavior

- Make `ChapterTranslationStore` the sole owner of stored page/block instances. Stage workers operate on immutable work items and submit atomic patches rather than mutating shared `PageTranslation` objects.
- Add page versions, batch-generation tokens, and block fingerprints to patch preconditions. Cancellation, timeout, user edits, or newer work invalidate late commits.
- Publish cleaned files safely: write and verify a versioned file, atomically commit its name to the store, then delete the previous file. Interrupted publication may leave an orphan, never a broken store reference.
- Keep the original image visible until a text-bearing page satisfies cleaned-image, valid translation, and overlay readiness. Translation failures remain visible even when cleaning succeeded.
- Mark genuinely textless pages explicitly as downstream `SKIPPED`, keep their original image, and count them as successful terminal pages without pretending they were rendered.
- Add an overlay-content fingerprint to `PageView` so Pass-2 text-only changes refresh pager and webtoon overlays without decoding the image again.
- Replace the unsafe watchdog force-release with native-run quarantine: reject new native admissions while a timed-out call is still alive and discard every late result using its generation token.

### 2. Build the real staged batch and reliable Pass 2

- Extract a testable batch coordinator with one native lane, one translator lane, and a per-page render join.
- Immediately after OCR persistence, offer an immutable translation work item to a bounded channel of capacity 2, then continue same-page inpainting on the existing bitmap. OCR and inpainting remain serialized; remote translation can overlap inpainting.
- Never suspend with a bitmap and native permit when the channel is full. Finish inpainting and release native resources before performing the blocking send.
- Classify translators as `REMOTE_IO` or `LOCAL_COMPUTE`. Gemini, OpenRouter, DeepSeek, LM Studio, DeepL, and Google use early overlap; on-device ML Kit remains serialized to protect low-end devices.
- Flush incomplete AI chunks after 250 ms of input inactivity while retaining existing token limits and a single provider request lane.
- Change all contextual AI providers to return a structured result keyed by request-local IDs instead of mutating blocks. Use anchored IDs such as `p0_b3`; no persisted block-ID migration is required.
- Pass 1 accepts only a valid nonblank translation. A valid line missing `[OK]`/`[FLAG]` remains readable but is automatically flagged for review; malformed or missing IDs remain untranslated and enter existing partial/retry handling.
- Start Pass 2 after the complete Pass-1 translation barrier, even if inpainting/rendering is still finishing. Final batch completion waits for revision, inpainting, and rendering.
- Plan revisions in reading order with at most 20 target IDs and the existing token budget. Include the chapter glossary and nearby source/draft dialogue as context, while requesting output only for flagged targets.
- At merge time, re-check page version, block fingerprint, draft, `needsRevision`, and `userEditedAt`. Only valid, nonblank corrections actually applied count as completed and clear the flag. Missing, blank, stale, duplicate, or malformed results retain the draft and remain flagged.
- Re-run `TranslationBlockValidation` after every revision merge; never force an entire page to `READY`.

### 3. Align progress, status, and lifecycle management

- Use typed batch events and a pure reducer as the single progress source. The pipeline writes stage state; the tracker becomes a serialized projection and no longer writes duplicate store transitions.
- Represent concurrent active stages as a set, allowing UI copy such as “Cleaning and translating” rather than selecting one misleading stage.
- Expand stage counts to succeeded, failed, skipped, processed, and total. Progress fractions use processed work, so failures complete the bar while remaining visibly failed. Revision progress uses `(completed + failed) / total`.
- Reconcile against every ordered page key. Missing, cancelled, or incomplete pages cannot fall through as successful; write stranded failures before producing the terminal snapshot.
- Add a `READY_WITH_WARNINGS` chapter outcome for readable drafts with partial translations or unresolved revisions. Hard OCR/inpaint/render failures remain errors.
- Persist a small adjacent chapter-summary sidecar containing version, expected page count, terminal outcome, unresolved revision count, and update time. Existing translation JSON remains unchanged; legacy files without a summary are treated as partial availability until touched by a full batch.
- Bypass UI throttling for terminal events, localize revision labels, hide Cancel after termination, and show failed/skipped/user-edited revision counts.
- Key active store state by chapter ID to prevent background batches contaminating the open reader’s queue.
- Dispose completed/cancelled trackers immediately and retain at most 20 immutable terminal snapshots, never store references or tick jobs.
- Forward application-level memory-pressure callbacks to translation management so manga-screen batches receive them without an open reader.
- Debounce glossary persistence with the chapter store and flush it on completion/cancellation.
- Keep concise comments only for concurrency, memory, publication, and compatibility invariants. Remove stale staged-pipeline claims and update current architecture/data-flow documentation; leave historical experimental notes untouched.

## Interfaces and Types

- `ContextualTextTranslator.translateContextual` returns a structured per-ID result.
- Add atomic store patch APIs carrying run generation, page version, and block fingerprint.
- Add `StageStatus.SKIPPED`, `Translation.State.READY_WITH_WARNINGS`, concurrent `activeStages`, richer stage counts, and an overlay fingerprint in `PageView`.
- Add the backward-compatible chapter-summary sidecar; do not replace or migrate the existing page JSON format.

## Test Plan

- Coordinator tests prove translation begins after OCR and before same-page inpainting completes, native OCR/inpainting never overlap, bounded backpressure releases bitmaps/permits, and render waits for both outputs.
- Pass-2 tests cover complete, partial, missing, blank, malformed, duplicate, stale, and over-budget responses; maximum 20 targets; context inclusion; edit-wins behavior; retry/resume; and preservation of `PARTIAL`.
- Store/race tests cover immutable emissions, same-millisecond updates, text-only overlay refresh, cancellation/timeout late-write rejection, native quarantine, and safe cleaned-file replacement.
- UI/reducer tests cover cleaned-only and failed-translation pages staying original, textless pages, concurrent stages, final snapshot delivery, warning outcomes, exact terminal counts, and cross-chapter isolation.
- Lifecycle tests verify cancellation and normal completion dispose trackers and that the terminal cache remains bounded.
- Run focused translation tests, the full `:app:testStandardDebugUnitTest` suite, and `git diff --check`.
- On a low-memory device, stress batch cancellation, native timeout, reader opening during pre-translation, and background memory pressure. Verify translation-request timing precedes inpaint completion and peak bitmap/native concurrency does not increase.

## Assumptions

- Use the recommended phased foundation, keep original images until overlays are ready, and report unresolved revision work as ready with warnings.
- Scope is the Android translation/pre-translation path; the companion server is unchanged.
- No manual block editor is added, but `userEditedAt` protection remains enforced for future or externally persisted edits.
- Preserve the current uncommitted tracker initialization-order fix and refine the indicator change with processed revision progress and localized copy. Do not touch unrelated untracked files.
