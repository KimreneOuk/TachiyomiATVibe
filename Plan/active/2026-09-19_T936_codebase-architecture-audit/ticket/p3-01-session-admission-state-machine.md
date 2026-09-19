# Ticket P3-01: Session admission state machine — route every translation entry point through one owner

**Phase:** 3 | **Risk:** High | **Type:** New architecture (additive) | **Tests-first required**

## Evidence base

Scoping report `team/p3-scoping-report.md` §2 (entry-point table) — all file:line refs verified @ `5bcb592`.
Current state: no centralized admission. Manual reader requests reach `TranslationScheduler.translatePage`
(ReaderViewModel.kt:2306/2336/2346) and hit the lease matrix directly; auto consults only a per-chapter
retention flag; batch actions each manage their own cancellation.

## Changes

1. New `TranslationSessionCoordinator` (package `eu.kanade.translation.orchestration` — first file in
   the Phase-5 target package, created now):
   - States: `IDLE`, `BATCH_SESSION`, `PAUSING`, `READER_SESSION` (single StateFlow, thread-safe).
   - API: `requestReaderSession(intent): SessionAdmission`, `requestBatchSession(...): SessionAdmission`,
     `finishSession()`. `SessionAdmission` is sealed: `Admitted`, `Switched(previous)`,
     `Rejected(reason: SessionRejection)` where reasons include `BATCH_ACTIVE` (switch offer pending),
     `PAUSING_IN_PROGRESS`, `READER_ACTIVE(batch request while reader owns session)`.
   - BATCH→READER switch on confirmation is P3-02's quiescent transition; until P3-02 lands,
     `Switched` performs today's plain pause (behavior parity with current, no regression window).
2. Route EVERY entry point from scoping report §2.3 through the coordinator:
   - Reader manual: the three `translationScheduler.translatePage` call sites in ReaderViewModel.
   - Reader auto: live `updateAutoWindow` path (TranslationManager:1574-1603, scheduler:186-255).
     Do NOT build on dormant `requestAutoWindow` — leave it quarantined as-is.
   - Reader lifecycle: onCleared, chapter switch, background, stop-all, translation toggle-off,
     retry/resume re-arm paths — these end/release the READER session via `finishSession()`.
   - Batch: pause/start (MangaScreenModel:907-913, TranslationForegroundService:87/:160), chapter
     start/cancel/requeue/replace/multi-chapter admission (MangaScreenModel:945-1028, 1269-1287,
     1300-1322, 1395; manager translateChapter/cancelRunningChapterForReplace/cancelQueuedTranslation).
   - `ReaderTeardownCoordinator` delegates admission to the session coordinator (no hidden second state).
3. Existing lease/attach machinery stays functional underneath (P3-03 removes it later). The gate
   makes cross-origin paths unreachable; nothing is deleted in this ticket.
4. Typed state surfaced to UI: reader translate while `BATCH_SESSION` returns `Rejected(BATCH_ACTIVE)`
   with enough info for the P3-02 switch prompt; while `PAUSING` returns `Rejected(PAUSING_IN_PROGRESS)`.

## Tests (write BEFORE implementation; extend coexistence package)

`TranslationSessionCoordinatorTest`: IDLE→BATCH, IDLE→READER, BATCH+reader-request→BATCH_ACTIVE,
double-batch→idempotent, reader teardown→IDLE symmetry, no-bypass (direct scheduler entry without
admission returns typed rejection at the gate seam), retry re-admission. Existing suites must stay
green unchanged (they exercise paths beneath the gate).

## STOP-gate

If any entry point in §2.3 cannot be routed without changing observable behavior beyond admission,
report it before forcing it.

## Verification

Full both-flavor suites green; `assembleDevDebug` green.

## Commit(s)

`feat(translation): session admission state machine routing all translation entry points`
(+ test commit if split)
