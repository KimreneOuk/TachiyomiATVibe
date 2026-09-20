# Ticket HF-01: Manual translation overlay stall — store key mismatch between writer and observer

**Priority:** HOTFIX (production regression on device) | **Branch:** `t936/hotfix-manual-translate-key`

## Symptom (device PKG110, build v0.17.1-599, 2026-09-20 18:13, chapter c7d320efaf178cfd4)

User taps manual translate in reader → page shows "Reading Text" → nothing further. Translation
appears ONLY after changing reading mode (forces rebind/hydration). Pipeline itself completes.

## Evidence (logcat, app pid 27824)

1. Manual flow runs fully — trace stages complete:
   `18:13:17.8 stage_start ocr → … native stage_end ×4 → 18:13:19.6 storage lane (ends 20.6) → 18:13:20.6 provider lane → 18:13:21.4 stage_end`. Page id `p376aa82bb1a67de6`, `pageIndex=none`.
2. Observer/WRITER KEY MISMATCH (decisive):
   ```
   [reader_translate_diag] observePageView pageIdx=3 pageKey=4.jpg sourceFileName=4.jpg storeSize=2 hasKey=false sampleKeys=[3.jpg, 5.jpg]
   [reader_translate_diag] observePageView pageIdx=5 pageKey=6.jpg sourceFileName=6.jpg storeSize=2 hasKey=false sampleKeys=[3.jpg, 5.jpg]
   [reader_translate_diag] store emitted NULL for pageKey=4.jpg (no matching entry)
   [reader_translate_diag] store emitted NULL for pageKey=6.jpg (no matching entry)
   ```
   Store contains index-derived keys (`3.jpg`, `5.jpg` for pageIdx 3/5, i.e. `"$pageIdx.jpg"`),
   observer looks up real source file names (`4.jpg`, `6.jpg`). Zero overlap → live overlay never updates.
   Rebind works because persisted-artifact hydration keys by real file name.
3. PagerPageHolder JobCancellationException ×2 at 18:13:29 (likely the user's mode-switch rebind — check for lost observation re-registration after cancellation).
4. Secondary anomaly: NO `[onnx_runtime] compiledProviders=…` line appears despite ORT init at
   18:13:12–17 inside the buffer window — verify the P2-00 diagnostic actually emits (placement/condition).

## Tasks

1. **Trace both key derivations.** Write path: ReaderTranslationController manual dispatch →
   TranslationScheduler.translatePage → TranslationPipeline (single-page) → store write (pageKey
   construction). Observe path: controller `observePageView` / store observation (sourceFileName).
   Identify the exact site(s) where the writer derives `"$pageIdx.jpg"`-style keys instead of the
   page's real file name. Suspects: P4-03 controller extraction (index passed where name expected),
   scheduler entry translation, or store `preRegisterPages`/`rekeyPages` interplay.
2. **Code archaeology:** determine whether the mismatch predates the campaign (check `7262bf4`
   behavior of the same paths via git) — the fix is the same either way, but the report must say
   which phase (if any) introduced it.
3. **Fix:** single canonical pageKey derivation (real source file name) for writer and observer.
   No behavioral change beyond the key correction. If some flow legitimately needs index keys
   (e.g., pre-registration before names known), reconcile via the store's rekey mechanism and
   document it.
4. **Regression tests (behavioral names):** (a) manual translate publishes under the page's source
   file name and the reader observation for that page emits non-null; (b) writer/observer key
   equality contract test covering the spread case (pageIdx≠fileName). Add to the reader/orchestration
   test packages as fits.
5. **Diagnosis for the JobCancellationExceptions** in PagerPageHolder at rebind: confirm they are
   benign page-recycle cancellations and that observation re-registers after rebind (log evidence
   shows it does — observePageView fires post-rebind — verify in code).
6. **P2-00 diagnostic check:** confirm compiledProviders line placement; fix if dead (it is our
   only P1-05 evidence instrument).

## Verification

Focused reader/orchestration/scheduler suites + full both-flavor suites green. Build assembleDevDebug
for device reinstall (orchestrator will install + verify on PKG110).

## Commit(s)

`fix(translation): manual translate publishes under source file name (overlay stall)` (+ tests; separate commits fine)
