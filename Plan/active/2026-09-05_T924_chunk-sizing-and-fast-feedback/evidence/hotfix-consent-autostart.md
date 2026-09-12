# T924 Hotfix — Restart Consent: no auto-start after restore + same-chapter concurrency guard + UI affordances

Branch `t924/batch-profile-pipeline` @ `88a7265` (worktree `TachiyomiAT-t924-impl`). No commits made; orchestrator owns the sweep.
Director policy (binding): after an app/process restart, NO translation work (OCR/LLM) may start without an explicit user action. Live in-session requests (user tapped translate; download completes later) must still auto-start.

Command: `./gradlew :app:testStandardDebugUnitTest` with filters `eu.kanade.translation.*Queue*`, `*Request*`, `*Reconcile*`, `ChapterTranslator*`, `TranslationManager*`, `eu.kanade.tachiyomi.data.download.Downloader*`, `eu.kanade.tachiyomi.ui.manga.*` — **33 classes, 167 tests, 0 failures, 0 errors** (BUILD SUCCESSFUL).

---

## Fix 1 — `ChapterTranslator.start()` no longer resurrects ERROR entries

File: `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt:293-319`

- The pending filter (`:308-311`) now also excludes `Translation.State.ERROR` alongside TRANSLATED/PAUSED. A generic queue start can therefore never resurrect the ERROR-restored Chapter 21 as queue head (the 11:45:22 device event).
- Re-entry for ERROR work exists ONLY via the explicit per-chapter gate added in Fix 2: `TranslationManager.translateChapter` (and the list variant) re-arms PAUSED/ERROR queue entries to QUEUE before `startTranslation()`. `requeueExisting` already gates PAUSED (and returns false for ERROR).

## Fix 2 — Restore-admitted pending requests do not auto-start translation

Files: `app/src/main/java/eu/kanade/translation/TranslationManager.kt`, `.../manager/TranslationRequestCoordinator.kt`

- **Mark set** (`TranslationManager.kt:148-158`): process-local one-shot `restoreAdmittedChapterIds` (nullable backing + self-healing accessor `restoreAdmittedMarks()` — reflection-built test fixtures skip field initializers, so an absent set means "no marks"; no existing harness needed changes).
- **Populated in BOTH reconciler admission branches**:
  - `reconcilePendingRequestsForStartup` WAITING-for-download branch (`:559-561`) — the chapter 6466 case (durable request + download queue owner; handoff later in the same process).
  - `admitRestoredPendingTranslation` on successful queue admission (`:659-661`) — the has-downloaded-files branch.
- **Consumed at the download handoff**: manager wrapper `startTranslationAfterDownloadIfRequested` (`:455-462`) removes the mark (one-shot) and passes `autoStart = !restoreAdmitted` into the coordinator; `TranslationRequestCoordinator.startTranslationAfterDownloadIfRequested` gained an `autoStart: Boolean = true` parameter (`TranslationRequestCoordinator.kt:499-530`) forwarded through the `translateChapter` lambda (type now `(Manga, Chapter, Long?, Boolean) -> Unit`, `:56`; wired in the manager's `requestCoordinator` getter, `TranslationManager.kt:185-187`).
- **`translateChapter` gains `autoStart: Boolean = true`** (`TranslationManager.kt:783-841`):
  - `autoStart == false` (restore-admitted): enqueue, then flip the queue entry QUEUE→PAUSED (`:830-837`) so the UI shows paused, not a fake-active spinner; `startTranslation()` is NOT called. Request-clear/failure bookkeeping unchanged.
  - `autoStart == true` (live): explicit per-chapter requests re-arm a PAUSED/ERROR queue entry to QUEUE before starting (`:819-829`) — this is the only gate that resurrects such entries (Fix 1 removed the generic path). `translateChaptersInternal` got the same re-arm for multi-select START (`:895-906`).
- **Live requests supersede stale marks**: `queueTranslationAfterDownload` (`:334-337`) and `acknowledgeTranslationRequests` (`:348-352`) clear the mark for their chapters, so a user re-request can never be silenced by a leftover restore mark.

Live-path behavior (no mark): the handoff flow is byte-identical to before — the coordinator's generation fence, PREPARING write, and `translateChapter` default `autoStart = true` are untouched for it.

## Fix 3 — Same-chapter concurrent-start guard (root cause + close)

**The hole.** `ChapterTranslator`'s only guards were `translationJob?.isActive` check-then-acts (`start()`, `requeueExisting`, `launchTranslatorJob`) with no serialization. Two bypasses produce two concurrent batch schedules for one chapter, and the second batch's store generation advance cancels the first (the observed `outcome=cancelled` chain s1→s2):
1. **Async-unwind overlap (deterministic, matches the device log):** `pause()`/`stop()` cancel the translator job and reset `translationJob = null` immediately, but the batch coroutine unwinds only at its next suspension point — it can sit inside an uncancellable native run (OrtSession.run) indefinitely. Any admission in that window (`start()` from a re-tap/handoff/`removeFromTranslationQueue` re-launch, `requeueExisting`) sees `isRunning == false` and schedules a SECOND batch for the same chapter while the first is still in flight.
2. **Concurrent-admission race:** two threads in `start()`/`launchTranslatorJob()` between the `isRunning` read and the `translationJob` write each launch a translator job over the same queue head.

**Close** (`ChapterTranslator.kt`):
- `translatorLaunchLock` (`:284`) serializes check-and-launch in `start()` (`:293-319`) and `launchTranslatorJob()` (`:399-405`) — one translator job can ever exist.
- `inFlightChapterIds` claim set (`:291`): `launchTranslationJob` atomically claims the chapter (`Set.add`, `:455-471`); if a previous batch for that chapter is still unwinding, the new batch coroutine WAITS (100 ms poll, `IN_FLIGHT_CLAIM_RETRY_MS`) and runs only after the previous coroutine fully unwound. The claim is released in a `finally` (`:493-505`) — i.e., after unwind, never at cancel time. An admission for a chapter whose batch is running is therefore a no-op for the running work: no cancel, no restart, no second schedule.

**Regression test** (`app/src/test/java/eu/kanade/translation/ChapterTranslatorBatchStartGuardTest.kt`): `overlapping admissions while a batch is in flight schedule exactly one concurrent batch` — batch #1 is held inside the store resolver (non-suspending block ≈ uncancellable native work), then `pause(); start()` fires an overlapping admission; a concurrency counter around the resolver asserts `maxConcurrent == 1`. Verified to FAIL without the fix (maxConcurrent = 2: stashed ChapterTranslator.kt changes, test failed; stash popped, test passes). Two more tests pin Fix 1 (generic start does not resurrect an ERROR entry; still admits a plain QUEUE entry).

## Fix 4 — UI affordances (`app/src/main/java/eu/kanade/presentation/manga/components/ChapterTranslationIndicator.kt`)

- `TranslatingIndicator` dropdown (`:267-279`): when `translationState == PAUSED`, a Resume item (`ChapterTranslationAction.START`, existing string `MR.strings.action_resume` — no new resources) renders ABOVE the existing Cancel item.
- `ErrorIndicator` (`:384-432`): long-press now opens a DropdownMenu (same idiom as `TranslatedIndicator`): START first (retry, `ATMR.strings.manga_translate`), then DELETE (`MR.strings.action_delete`). Tap still routes DETAILS via `translationIndicatorTapAction` (mapping unchanged).
- START routing verified: `MangaScreenModel.runChapterTranslationActions` START → `confirmChapterTranslation` → `translateChapter` (autoStart=true default) whose new re-arm gate flips the PAUSED/ERROR entry to QUEUE and starts. Pinned by the two `explicit translate re-arms ...` tests.

## Tests

Added:
- `app/src/test/java/eu/kanade/translation/ChapterTranslatorBatchStartGuardTest.kt` — 3 tests (Fix 1 x2, Fix 3 x1).
- `app/src/test/java/eu/kanade/translation/TranslationManagerStartupReconciliationTest.kt` — 4 new tests: reconciler marks both admission branches (`restoreAdmittedChapterIds` reflection-read); gated admission (`autoStart=false`) enqueues PAUSED with zero `translator.start()`; explicit translate re-arms PAUSED and ERROR entries (verify start called).

Updated: none needed — no existing test encoded the old behaviors (the existing "admitted exactly once without auto-start" test already matched the new contract; grep for ERROR-requeue-on-start expectations found none).

Result: 167 tests / 0 failures across the 33 affected classes (list above). Regression check: with ChapterTranslator.kt reverted, the Fix 3 test fails (maxConcurrent=2) and the Fix 1 test fails (ERROR resurrected) — both pass with the fixes.

## Deviations / notes

1. `restoreAdmittedChapterIds` uses a nullable, self-healing backing instead of an eagerly initialized `val` set: ~10 existing test harnesses build `TranslationManager` via `Unsafe.allocateInstance` (field initializers skipped); a non-null field would NPE on every reconciler/wrapper path they exercise. Semantics identical in production.
2. The mark is additionally cleared at the two live-request entry points (`queueTranslationAfterDownload`, `acknowledgeTranslationRequests` manager wrappers). Without this, a user re-request of a restore-marked chapter would inherit the mark and silently not auto-start. The coordinator-level `queueTranslationAfterDownloadIfCurrent` is only reachable right after an ack in the same flow, so it needs no extra clear.
3. Defense-in-depth note: today the coordinator's attach-generation fence already drops a pure-restore handoff (`downloadAttachGenerations` is process-local), so the WAITING-branch mark is dormant until an in-process re-attach exists — it guards any future automatic re-attach path (the device log shows exactly such an in-process re-attach sequence at 11:45:13).
4. Explicit re-arm of PAUSED entries bypasses the `requeueExisting` cooldown (force semantics for an explicit tap), consistent with `requeueExisting(force=true)` in the reader path.
5. `IN_FLIGHT_CLAIM_RETRY_MS = 100L` poll in the claim wait: the waiter holds no locks and is cancellable; bounded by the previous batch's unwind time (same bounded-join concern already documented as `BATCH_JOIN_TIMEOUT_MS`).
