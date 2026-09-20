# Ticket HF-02: Manual translation UX — spurious "Batch 0/1", silent stage stall, killable in-flight work

**Priority:** HOTFIX (device-verified UX regressions) | **Branch:** `t936/hotfix-manual-ux-truth`

## Symptom report (Director, PKG110 v0.17.1-604, 2026-09-20 22:39+)

1. Manual translate: "Reading Text" → **long silent stillness** (no stage feedback at all) → sudden
   visual jump to cleaned image + overlaid text (terminal commit arrives as one step).
2. **"Batch 0/1" label appears in the reader during MANUAL translation** — user: "why is there even
   Batch 0/1?" A batch-flavored affordance is visible/interactive in a manual, single-page context.
3. At 22:43:01 the in-flight manual work was killed by "All translation cancelled":
   ```
   22:43:01.722 ChapterTranslationStore: store generation invalidated: generation=1 reason=All translation cancelled
   22:43:01.726 live translation update: pageKey=2.png ocr=CANCELLED inpaint=CANCELLED translate=CANCELLED render=CANCELLED cleaned=null error=All translation cancelled
   22:43:01.789 ChapterTranslationStore: store marked defunct: generation=2
   ```
   Correlation: user likely interacted with the confusing batch affordance (stop/cancel) believing
   it unrelated to their manual tap — or a reader-bar control fired all-cancel. Either way a
   manual page died mid-flight with a batch-flavored kill.
4. HF-01 fix CONFIRMED working (completion now arrives without rebind) — do not regress it.

## Anchor sites (verified)

- `TranslationUiTruth.kt` — `batchStatusLine` (:705), `BatchStatusLineKind` (:1025), BATCH_SESSION_SWITCH truth (:502-598)
- `presentation/reader/appbars/BottomReaderBar.kt:80-82` (renders batch status in reader bar)
- `presentation/manga/components/TranslationProgressSheet.kt:1064-1178`
- Cancellation path: grep "All translation cancelled" → the cancel-all entry (manager stop /
  toggle-off / queue action) that fired at 22:43:01.

## Tasks

1. **"Batch 0/1" root cause + fix:** trace why MANUAL-origin single-page work renders a batch
   counter in the reader bar (likely: progress projection counts any active chapter work as a
   1-page "batch"). Manual origin must show per-page stage truth, never batch framing. If the bar
   intentionally shows queue status, gate it on an actual BATCH_SESSION (TranslationSessionCoordinator
   state), not on "work exists".
2. **Silent stall fix:** manual page overlay must surface intermediate stages (OCR → translating →
   inpainting → rendering) as they commit; terminal-only visual jump is the defect. Trace the
   page-status flow: are intermediate states emitted to the overlay but not rendered, or never
   emitted? Fix whichever layer drops them.
3. **Cancellation attribution:** identify which entry point fired "All translation cancelled" at
   22:43:01 (logcat context: user scrolled pages 4-14 rapidly just before — check navigation/lifecycle
   triggers too). Manual in-flight work must only die from explicit user intent directed at it; a
   reader-bar/queue interaction must not silently kill a manual page. Add the acting-origin to the
   cancellation reason.
4. **Latency quantification (evidence only):** from trace durationMs during a fresh repro (the
   orchestrator will capture live logcat), report per-stage wall time for one manual page (det init,
   OCR, provider call, inpaint, render) so we know whether the "stillness" is also a real perf issue
   (e.g., inpainting on CPU — XNNPACK absent in this build per earlier warnings).
5. **Tests:** (a) manual origin never yields a batch status line / batch counter in reader truth;
   (b) manual stage transitions emit observable overlay truth per stage; (c) cancellation of manual
   work requires explicit manual-directed intent. Behavioral names.

## Constraints

- Do NOT change session semantics, store keying (HF-01), or durability paths.
- UX copy changes limited to what the truth mapping requires.

## Verification

Focused ui/orchestration suites + full both-flavor green + assembleDevDebug (orchestrator reinstalls
and device-verifies with live logcat).

## Commit(s)

`fix(translation): manual-mode reader truth (no batch framing, live stage feedback, attributed cancel)`
