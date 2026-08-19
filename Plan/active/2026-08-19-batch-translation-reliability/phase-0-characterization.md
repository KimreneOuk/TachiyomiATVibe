# Phase 0 characterization checkpoint

## Objective

Lock down the current NPU batch-translation behavior before any scheduler,
store, reader, or context changes. The implementation branch starts at
`4868cd24e7aa99d07f1fe705ff18818b52a683bb`, matching
`origin/feat/npu-acceleration-and-hardware-discovery`.

## Current live paths

| Concern | Current path | Phase 0 disposition |
|---|---|---|
| Batch admission/order | `ChapterTranslator.translateChapterInternal` → `ResumeOrdering.forwardFirstThenBackfill` using `chapter.lastPageRead` | Characterized as rotated mid-chapter order; natural page order is the later-phase requirement. |
| Production coordinator | `TranslationPipeline.translateBatch` → `ChunkBatchCoordinator` | Keep as the live path for characterization; no replacement in Phase 0. |
| Older coordinator | `BatchCoordinator` | Not production-wired; existing coordinator tests retain its cancellation coverage, while Phase 0 adds no duplicate retired-coordinator test. |
| Viewport priority | Former `ChunkBatchCoordinator`/`TranslationPipeline` viewport members | Proven dead by repository-wide source search and removed in the baseline fixup; no viewport scheduler is imported. |
| AI context | Live `StreamingChunkPlanner`, `ChapterGlossaryBuilder`, and in-memory rolling strings in `TranslationPipeline` | Characterize as the effective path. |
| New context seam | `ChunkTranslatorLaneWorker`, `RollingContextManager`, `RollingContextPacket` | Present but not reached by the live translator worker; defer to the context-quality phase. |
| Reader fallback | `PageTranslation.isTranslationDisplayReady` and `selectReaderTranslationOverlayBinding` | Characterize that OCR + target text without a current cleaned image falls back to original. |
| Store publication | `ChapterTranslationStore.publishLocked` and `state` `StateFlow` | Characterize emissions that make an in-progress replacement temporarily non-displayable. |

## Refactor-only commit inventory

The isolated branch was intentionally created from the NPU head. Refactor-only
commits are inventory, not cherry-pick instructions:

| Commit(s) | Classification | Notes |
|---|---|---|
| `d884be3`, `29f9cd8`, `67c469f` | Reusable reference | Design/implementation planning only; governing plans supersede their earlier assumptions. |
| `eb9debc` | Superseded/deferred | Single-pass native `BatchCoordinator` conflicts with the current chunked OCR barrier; revisit only after the lifecycle decision. |
| `c434188` | Superseded/inert | Viewport-priority scheduling is not a valid batch-order source and is not live-wired on the NPU path. |
| `8897970`, `6252cf5` | Unrelated/deferred | Provider pacing and AI reasoning/sanitizer work are outside Phase 0 reliability characterization. |
| `7e6d447` | Reusable only after review | Copy-on-write store publication changes ownership/emission semantics; do not port during baseline characterization. |
| `f18a8fa` | Superseded/deferred | Refactor UI redesign is not the NPU baseline and must not be merged wholesale. |
| `9e4e778` | Reusable reference | Render merge fingerprint fix is represented in the NPU-side stage contracts (`expectedOcrBlockFingerprints`). |
| `0a7a5bf` | Unrelated/deferred | Reader compare-menu/layout follow-up, not required for Phase 0. |
| `0bc979a`, `327458a`, `c5d2f5c` | Reusable reference | Earlier chunk/context design documents; final governing plans supersede them. |
| `c536038`, `5917293`, `8b531c2` | Superseded equivalents | Refactor hashes of clean-overlay, chunk coordinator, and rolling-prompt changes represented by NPU commits `0c666d9`, `0b32e68`, and `4868cd2`. |

The hardware/config commit `e2cf197` is already part of the shared
`c7c50c1` ancestry. NPU-only commits after that shared base are:

- `29d90f0` — dynamic page chunking with density guardrail;
- `50cf292` — rolling context manager;
- `0c666d9` — clean-inpaint invariant for reader overlay binding;
- `0b32e68` — chunk batch coordinator;
- `4868cd2` — rolling glossary and micro-summary prompt support.

## Checkpoint result

The Phase 0 test-only counters record source decode, detection, OCR, inpaint,
translation, layout/render, store emission, and reader-display transitions
without retaining source or target text. Characterization tests cover natural
and rotated ordering, live resume-gate reuse/restart decisions, the readiness
mismatch, and store emissions/fallback. Existing production-coordinator tests
remain the cancellation reference; no duplicate retired-coordinator coverage
is added here. No Phase 1 behavior is changed by this checkpoint.

## Validation

The baseline repair was validated with task-local Android Studio JBR and SDK
overrides. `:app:spotlessCheck` passes. The focused
`:app:testStandardDebugUnitTest` invocation covered
`Phase0BatchTranslationCharacterizationTest`, `BatchResumeGateDeciderTest`,
`PageDisplayReadinessTest`, `StrictZeroSkipTest`,
`ReaderTranslationOverlayBindingTest`, `OcrArtifactSanitizerTest`, the
existing `BatchCoordinatorCancellationTest`, and the live
`ChunkBatchCoordinatorTest`: 51 tests completed, 0 failed.

The compile repair removes the proven-dead viewport/priority references and
restores the proven `stripThinkingTags` behavior with focused XML, markdown,
unclosed-block, stray-tag, and blank-input tests. The Phase 0 files have no
trailing whitespace, and `git diff --check` is clean for tracked content.
