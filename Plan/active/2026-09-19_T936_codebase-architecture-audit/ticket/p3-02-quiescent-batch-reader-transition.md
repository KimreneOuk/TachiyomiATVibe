# Ticket P3-02: Quiescent batch→reader transition with honest timeout policy

**Phase:** 3 (after P3-01) | **Risk:** High (concurrency around non-interruptible native work) |
**Type:** New behavior (per Director principle 1)

## Evidence base

Scoping report §2.1–2.2: `cancelTranslatorJob` non-blocking (ChapterTranslator.kt:554-557);
`cancelTranslatorJobAndJoin` exists but fixed at 2s, no parameter, used only by delete/rekey/reset
(TranslationManager:1330, ChapterDataResetController:142/246); chapter claim held until full unwind
(:497-516, :543-551); native ONNX/bitmap work not interruptible (TranslationPipeline 90s native timer).

## Changes

1. `ChapterTranslator.cancelTranslatorJobAndJoin(timeoutMs: Long = BATCH_JOIN_TIMEOUT_MS)` —
   parameterize; existing callers unchanged (default keeps 2s).
2. Quiescent transition in `TranslationSessionCoordinator` (sequence per scoping §2.2, run from an
   IO/application scope, NEVER while holding TranslationManager request locks,
   ReaderTeardownCoordinator.readerTeardownMutex, or any store/artifact mutex):
   a. `BATCH_SESSION` → `PAUSING` atomically; new batch + reader work rejected during transition.
   b. Non-blocking pause/stop (no new chapter work scheduled).
   c. `cancelTranslatorJobAndJoin(timeoutMs = 3_000)`.
   d. **Timeout policy (mandatory): a timed-out join is NOT quiescence.** Remain `PAUSING` and retry
      the join (2s cadence, unbounded retries, each attempt logged). Reader admission stays
      `Rejected(PAUSING_IN_PROGRESS)` until the real join succeeds. Never auto-admit on timeout.
   e. On success (coroutine fully unwound → `finally` ran → NonCancellable store flush + lease
      release completed — per Phase 2 lock discipline): publish `READER_SESSION`, admit the queued
      reader intent, reusing ALL batch-produced durable checkpoints (already store-backed; assert it).
3. UI switch flow (spec Model B): reader translate during `BATCH_SESSION` surfaces a confirm
   ("Batch translation is active. Pause Batch and switch to Reader translation?" — reuse existing
   string resources or add new ones) → confirm triggers the quiescent transition → translate begins
   after `Switched`; dismiss → `Rejected(BATCH_ACTIVE)`, reader shows informative state, batch
   continues unimpeded.

## Tests

`TranslationSessionCoordinatorQuiescenceTest`: join-success path admits reader + reuses OCR
checkpoints (zero re-OCR assertions); join-timeout path keeps `PAUSING`, denies admission, retries,
admits after late unwind; pause preserves batch tracker progress (pause ≠ cancel semantics kept);
switch preserves durability (T930/T934 assertions green); teardown of reader mid-switch.

## Constraints

- Preserve existing `NonCancellable` teardown flushes and lease cleanup exactly (Phase 2 lock order).
- The 3s bound returns control to the UI only; it never licenses admission.

## Verification

Full both-flavor suites green; new tests green; `assembleDevDebug` green.

## Commit(s)

`feat(translation): quiescent batch-to-reader session transition with bounded join and honest pause policy`
