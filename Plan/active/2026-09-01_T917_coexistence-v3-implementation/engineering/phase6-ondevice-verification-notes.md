# Phase 6 — On-device end-to-end verification notes

Environment: AVD `T917_Verify` (`emulator-5554`), Android 14 (API 34, no GMS),
6 GB RAM config. App: `app.kanade.tachiyomi.at.debug` (standard flavor debug APK).
All screenshots in this folder (`p6-*.png`).

## Verified working (evidence screenshots)

- Install + first launch + SAF storage-location onboarding (`p6-after-install.png`, `p6-after-settings.png`).
- Local source browse + reader (libimagedecoder loads; pages render).
- D10 batch admission on a Local-source chapter: honest `download_failed`
  sheet with truthful `unsupported_source` copy, no hang, no fake progress
  (`p6-batch-unsupported.png`). Logcat: `event=request_phase ... to=waiting_for_download`,
  `event=queue_result result=unsupported_source`.
- Extension repo management: add keiyoushi repo via UI
  (`https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json` —
  must be the `repo` branch and match the `index(.min).json` repoRegex),
  extension search/install with "install unknown apps" grant (`p6-ext-*.png`).
- Online source (CyComi) catalog browse, manga details, 102-chapter list,
  online reading in reader (`p6-cycomi.png`, `p6-cycomi-reader.png`).
- Translation settings: language pickers auto-switch OCR model to MangaOCR for
  Japanese (`p6-jp-selected.png`); bundled ML Kit + MangaOCR models present in APK
  (no Play Services dependency).

## Findings (honest record)

### F1 — FIXED + E2E-VERIFIED (T917 defect): batch translation of a never-translated manga aborted with LEGACY_RESCUE_FAILED

Symptom: chapter-level Translate on CyComi JP chapter → progress sheet
`● Aborted`, banner "Page pre-registration was rejected: artifact store
rescue/publication failed", 0%, store marked defunct.

Root cause chain (all verified in code + logcat):
1. For a never-translated manga the on-disk translations tree does not exist;
   `TranslationProvider.findMangaDir` is find-only → returns null.
2. `DurableChapterStatusResolver.findTranslationDocument` returns null →
   `TranslationManager.openOrCreateActiveChapterTranslationStoreImpl` builds a
   LAZY store with `artifactParent = null`.
3. D10 batch `preRegisterPages` (first mutation) → `admitMutationLocked` →
   `ensureArtifactStoreLocked()` → `parent = artifactParent ?: translationFile?.parentFile ?: return false`
   → `Rejected(LEGACY_RESCUE_FAILED)`. Every later mutation rejects the same way.
4. The per-page pipeline fallback (`SinglePageOnnxPhase.kt:238`) uses
   `provider.getMangaDir(...)` (CREATE path) and never hits this; only the
   active-store (reader/batch) path is affected.

Fix (preserves lazy-store intent — no document/dir creation on mere open):
- `ChapterTranslationStore.ensureArtifactStoreLocked()`: when both
  `artifactParent` and `translationFile` are null, resolve the parent through
  the existing `fileCreator` seam (runCatching → null → honest rejection).
  `fileCreator` was previously dead ("never invoked"); all existing tests that
  pass a creator also pass a non-null `artifactParent`, so their tripwires are
  unaffected (creator remains last-resort only).
- `TranslationManager.openOrCreateActiveChapterTranslationStoreImpl`: pass
  `fileCreator = { provider.getMangaDir(mangaTitle, source) }` to the lazy store
  — the same create-the-directory step the pipeline fallback uses.
- Tests (TDD, RED→GREEN in `ChapterTranslationStoreArtifactMigrationTest`):
  `lazy store without parent resolves artifact authority through fileCreator`
  (RED first with `0 should not equal 0` — creator never invoked), and
  `lazy store without parent rejects mutation when fileCreator fails` (honest
  rejection preserved). Class after fix: 21/21 green, XML-verified.
- Full sweep after fix: **197 suites / 1444 tests / 0 failures / 0 errors /
  0 skipped** (XML-verified). Commit `1812cf9`.

**E2E verification after fix (real run, on-device)**: batch START
(`generation=1 reason=batch start`), full pipeline per stage on page 001:
OCR `RoiPageRecognitionEngine ... route=CPU_XNNPACK ... total=9288ms blocks=12`,
ML Kit NMT translations in logcat matching rendered output, inpaint
`AOTInpainting mode=FAST`, render 7.1s → **English overlays rendered in the
reader on page 1/4** (`p6-r-reader-translated.png`). Durable layer:
`紙単行本告知_artifacts/` with manifest, 4 generation snapshots,
`pages/001.jpg-*/committed-g-*.json` (committed), candidates for 002-004,
`attempts/ledger.json` (D9, schema-correct, entries empty — on-device ML Kit
makes no billed calls), companion cleaned images for all 4 pages, .nomedia at
every level — the directory chain was created by the `fileCreator` fix.
Logcat evidence: `phase6-e2e-logcat.log` (2199 lines).

### F6 — FIXED + ON-DEVICE VERIFIED (T917 defect): multi-page standard batch stranded every page after the first work-needing page

**Defect (found live in the first full run):** pages 002-004 skipped
translation AND render stages (`stage_timing ... stage=translation
durationMs=0 success=true items=N`, render 0ms) with NO `stage_decision`
events for the translation stage, then were stranded by the reconciler
("Translation incomplete — expected page has no readable terminal output") →
`batch complete outcome=ERROR`. The sheet showed honest totals (1 of 4 pages
ready, 3 pending) with an honest red error bar, and the reader showed page 2
untranslated with a "Failed" chip (`p6-r-reader-p2b.png`) — the Phase-5 truth
layer worked as designed; the defect was upstream (planner/lane).

**Root cause** (`phase6-multipage-strand-investigation.md`): `planChapter`
overwrites every page after the first ordered-work page with
`WAIT_FOR_DEPENDENCY/PRIOR_PAGE_INCOMPLETE` in the immutable batch-start plan
(`BatchResumePlanner.batchPagePlans`). The standard-lane skip
(`priorPageBlocksStandardTranslation`) keyed on that static reason never
re-checked whether the predecessor had *since* reached a terminal outcome, so
every pass translated exactly one more page and re-stranded the rest.
`translateOutcome`'s silent default (`ChunkCompletionOutcome.Completed`)
laundered the skips into "success", hiding the strand from the pass loop; only
the stranded-page reconciler caught it honestly. AI lane was immune (its
multi-page envelopes + `!isAi` guard). JVM tests missed it because their
"batch completes" fixtures were one-page.

**Fix (commit `d289ff0`, `BatchLaneWorkers.kt`):**
- Live predecessor-terminality check: a planned
  `PRIOR_PAGE_INCOMPLETE` block only holds while the natural-order
  predecessor is STILL non-terminal in the live store
  (`naturalOrderPredecessorTerminal` via `orderedStreams` + `store.state`
  translationStatus READY/SKIPPED).
- Live-store completed-page skip twin (`completedStandardPageAfterPriorGap`):
  a WAIT_FOR_DEPENDENCY/PRIOR_PAGE_INCOMPLETE page whose live store status is
  already READY/SKIPPED skips re-translation — deliberately origin- and
  fingerprint-blind, reading `store.state.value[pageKey]` instead of the stale
  `translationRegistry` snapshot (D1: manual output is authoritative and is
  never re-paid; the manual path does not stamp `translationFingerprint`, so a
  fingerprint fence cannot be used).

**Tests** (`StandardLaneMultiPageCompletionTest`, harness-based):
- Positive: 3-page fresh standard batch → each page translates exactly once
  (`transportCallsFor == 1`), reconciliation TRANSLATED, no stranded pages,
  all renderStatus READY. RED first via a named negative probe (the multi-page
  batch "never translates p1: static PRIOR_PAGE_INCOMPLETE skip is permanent
  within a pass").
- Negative control: FAILED predecessor keeps successors honestly stranded
  (transport stays silent, page stays PENDING).
- Full sweep after fix caught the D2 manual→batch double-pay regression the
  first fix attempt introduced (fingerprint-fenced skip missed it): batch
  re-translated a manually-owned page (paid calls 1→2). The live-store
  origin-blind skip resolved it. Final sweep: **198 suites / 1446 tests /
  0 failures / 0 errors / 0 skipped** (XML-verified).

**On-device verification after fix (real retry run, same chapter):**
- All 4 pages ran real stages with non-zero timings — translation
  16634 / 8817 / 10474 / 8977 ms (ML Kit NMT), OCR/inpaint/render all
  `success=true`; logcat evidence `phase6-f6-fix-logcat.log`:
  `batch complete chapter=紙単行本告知 pages=4 outcome=TRANSLATED`.
- Progress sheet reached 100% — "4 of 4 pages ready to read", all four
  pipeline stages 4/4 ✓ (`p6-f6-complete.png`).
- Reader overlays verified on every page: 2/4
  ("I'M LOOKING FORWARD TO THE QUIET TIME AT FUKU'S BOOKSTORE.", "THANK YOU
  FOR CALM", "BABY.", "FUKU KUN"), 3/4 ("THANK YOU VERY MUCH", "BUY IT ~",
  "CULTURE 3 RELEASE!", "TALENT"), 4/4 color cover ("M MISCELLANEOUS TYPE
  CORPORATION", "STEEL CHILD IS STILL", "START STARTED STARTING AT EACH
  BOOKSTORE!") — `p6-f6-reader-p4.png`, `p6-f6-reader-p3.png`,
  `p6-f6-reader-p4-final.png`.

### F2 — Pre-existing (NOT T917): Local-source chapters cannot be translated

`ReaderViewModel.observePageView` requires `sourceManager.get(manga.source) as? HttpSource`;
Local source is not HttpSource → reader translation observation bails
(`[reader_translate_diag] observePageView BAIL: source null`). Batch path
additionally cannot download from Local (D10 honest `unsupported_source`).
Pre-existing architecture limitation; recorded, not changed in T917.

### F3 — Pre-existing (NOT T917): Rawkuma extension incompatible

Rawkuma throws `IllegalStateException: IgnoreGzipInterceptor must not be present
in default client`. The assert lives in the extension's own classes.dex; the
app's `NetworkHelper` (line 36 `addNetworkInterceptor(IgnoreGzipInterceptor())`)
matches upstream Mihon. Extension-side quirk; switched verification to CyComi.

### F4 — Dead source: CManhua site returns HTTP 404 (catalog empty). Switched sources.

### F5 — Sheet subtitle wrong copy (Phase-6 carry-list item confirmed live, twice)

During the F1 abort AND after the F6 error completion, the progress sheet
header showed "All pages translated and ready to read" with a `● Completed`
pill while the truth was Aborted (first run) and 1-of-4-ready ERROR with a red
bar (second run). Matches the already-recorded carry item "notification
catch-all gating on TRANSLATED" — the catch-all copy fires on non-TRANSLATED
terminal states. The honest data (55%, "1 of 4 pages ready to read" chip, red
bar, row-level ❗ icon, reader "Failed" chip) was correct everywhere else; only
the catch-all subtitle/pill lies. Not fixed in P6 (carry list); do not count as
a new deviation.

### F7 — ONNX Runtime missing on x86_64 (packaging note, debug-only override applied)

`onnxruntime-android-qnn:1.27.0` (the chosen AAR) ships **arm64-v8a only** —
the x86_64 emulator build had no `libonnxruntime.so`/`libonnxruntime4j_jni.so`,
so the first engine init failed with `UnsatisfiedLinkError` and the batch
aborted. Following the repo's existing debug-override pattern (QNN libs in
`app/src/debug/jniLibs/arm64-v8a/`), the standard `onnxruntime-android:1.27.0`
AAR's x86_64 libs were dropped into `app/src/debug/jniLibs/x86_64/` (same
version → same JNI surface; release builds and arm devices unaffected). After
rebuild/reinstall the full ONNX pipeline ran on the emulator
(`route=CPU_XNNPACK`). Note for the Director: production emulator-coverage of
ONNX remains arm-only unless a mixed-ABI packaging strategy is adopted.

### F8 — UX gap (carry list): terminal/error progress sheet has no Retry affordance

After the F6 error completion, the sheet sat in its Idle/terminal state with a
red error bar and a row-level ❗ icon whose content description says "retry is
available" — but the sheet itself offered NO Retry/Resume button in that state
(long-press did nothing either). The retry only fired after closing the sheet
and tapping the chapter row's ❗ icon again in the chapter list. Retry works,
but it is undiscoverable from the sheet where the failure is explained.
Overlaps the F5 catch-all-copy item: the sheet's terminal-state action surface
needs a pass (honest subtitle + a Retry action when any page is
retryable-failed). Recorded, not fixed in P6.

## Emulator automation notes (for reproducibility)

- a11y tree via `ui_resolve`/`ui_tap` authoritative; reader center-tap menu
  toggle does not register on this emulator (worked around via chapter-level
  Translate button on the manga screen).
- `adb shell screencap -p` through Git Bash redirect corrupts PNG; use
  `exec-out screencap -p`.
- `MSYS_NO_PATHCONV=1` required for `adb push` paths.
- Extension list cached empty after repo fix → force-stop + monkey relaunch.
