# Agent Handoff — 2026-10-09 (Device-side latency validation, parallel inpainting build)

## Objective

Continue **device-side latency validation** for TachiyomiATVibe. The code work is done, built,
and installed. The single outstanding task is: **start a telemetry recording, have the user
translate 1–2 manga pages, and report the measured latencies.** No new development is expected
unless the measurements reveal a regression.

## Project and environment

- Workspace: `C:\Users\User\Documents\TachiyomiAT-1.16.8-dev\TachiyomiAT-1.16.8-dev\TachiyomiATVibe`
- Branch: `release/apk-6-candidate` — HEAD is `dc4e4e2`
- Device: OnePlus PKG110 (Snapdragon 8 Gen 3), wireless ADB `192.168.100.223:46123`
- Package: `app.kanade.tachiyomi.vibe.debug` (versionName 0.17.1-243)
- ADB: `C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe` (also on PATH fallback
  inside `trace_stream.ps1`)
- Shell note: agent runs Git Bash; PowerShell is available for the `.ps1` scripts.

## Work already completed (do not redo)

### Commit history on this branch (most recent first)

- `dc4e4e2` perf(pipeline): parallelize inpainting with translation and remove artificial
  governor spacing — inpainting detached into a `Dispatchers.Default.async` coroutine that
  releases the native ONNX permit immediately (next page's det/rec can start), HTTP translation
  runs concurrently, pipeline awaits inpaint + refreshes store precondition snapshot before
  persisting the cleaned bitmap. Artificial inter-request governor spacing removed
  (`minIntervalMs = 0`). Unit tests in `eu.kanade.translation.coexistence.*` passed.
- `c599113` fix(ocr): normalize paddle batch governor latency to prevent false downgrades
- `bf850c3` / `0f88a75` docs: plan + design for the above
- `0b15134` → `5ebbe30` (7 commits ending at `0b15134`): dynamic whole-page OCR recognition
  batching — DYNAMIC batch preference, governor-capped ceilings (historically: 16 at page
  width 640, 6 at width 1600 — re-verify from telemetry), halving failure ladder.
  Full suite: 2,173 tests, 3 verified pre-existing failures, all 223 vision-package tests green.
- Earlier (per prior handoffs): full-page PaddleOCR detection + spatial grouping, structured
  telemetry for manual/auto/batch translation.

### Build and install status (verified 2026-10-09)

- APK: `app/build/outputs/apk/dev/debug/app-dev-arm64-v8a-debug.apk` (289 MB, built 17:18 local)
- Build command: `gradlew.bat assembleDevArm64Debug`
- Installed on device at `2026-10-09 17:19:17` (confirmed via `dumpsys package`), so the
  installed build **includes** `dc4e4e2`.
- App was **not running** at last check (`pidof` returned nothing) — the user must simply open it.

### ⚠️ Working tree is dirty

`git status` shows ~13 modified-but-uncommitted files (SettingsTranslationScreen.kt,
HardwareDiscoveryEngine.kt, OnnxRuntimeProvider.kt, PaddleOcrSessionFactory.kt, the ONNX
detectors/segmenter, EngineLane.kt, several tests, docs, TranslationPreferences.kt, strings.xml,
paddle_ocr_gui.py, trace_stream.ps1) plus many untracked files (this handoff, Plan/, doc/, etc.).
Run `git diff` before any rebuild — the installed APK was built with the tree in this state.
**Never push. Never reset/discard without explicit user approval.**

## Verified device configuration (read from shared_prefs, 2026-10-09)

- `translation_enabled=true`, engine category `AI_MODEL`
- AI translation: **LM Studio** at `http://192.168.100.7:3456/v1`, model `hy-mt2-7b@q4_k_xl`
  → **prerequisite: that LM Studio server must be reachable from the phone before testing.**
- OCR models: `PADDLEOCR_V6_SMALL` for both Japanese and Chinese
- `translation_paddle_ocr_recognition_batch=DYNAMIC` ← the setting under test
- Paddle execution provider: `CPU` (intentional), general `translation_vision_gpu_acceleration=true`
- Inpainting mode: `QUALITY`

This configuration is correct for the validation. Expected telemetry: DYNAMIC batch ceilings
~16 crops @ width 640, ~6 @ width 1600.

## THE OUTSTANDING TASK — recording + validation protocol

1. Start the filtered capture (script auto-connects ADB and resolves the app UID):

   ```powershell
   .\scripts\trace_stream.ps1 -Device "192.168.100.223:46123"
   ```

   It writes `trace_capture_<timestamp>.log` in the CWD. **Verify the file is non-empty and
   receiving lines before telling the user capture is live** — an earlier attempt produced an
   empty file, and a later attempt was cut off by an API usage limit. There is no capture
   running right now.

2. Ask the user to translate 1–2 manga pages in the app (manual translate on a page is fine).

3. Inspect the live capture for, in order of importance:
   - `crop_summary` — whole-page batching evidence (crop counts per page/batch)
   - `[translation_perf]` — must show `dynamic=true` plus per-phase OCR timing
   - `[paddle_batch_governor]` — current tier and any downgrade events
   - Native timing fields: `detMs`, `ocrMs`, `inpaintMs`, `totalNativeMs`, `budgetMet`
     (budget goal: total native vision ≤ 2000 ms across Det + Seg + OCR + Inpaint)

4. Offline analysis — pull the app-side trace and run the analyzer:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 192.168.100.223:46123 pull /sdcard/Android/data/app.kanade.tachiyomi.vibe.debug/files/translation-trace/translation-trace.log ./trace.log
   python scripts/analyze_latency_budget.py trace.log
   ```

5. Report: whether recording started (verified, not assumed), what page activity was captured,
   and the measured values vs. the 2000 ms budget — specifically whether parallelized inpainting
   (`dc4e4e2`) is visible as overlapping det/ocr and inpaint phases instead of serialized ones.

## Context documents (read if background is needed)

- `docs/translation-architecture.md` — pipeline architecture (recently modified)
- `docs/superpowers/specs/2026-10-09-parallel-inpaint-translation-pipeline-design.md` — design
  for the change in `dc4e4e2`
- `docs/superpowers/plans/2026-10-09-parallel-inpaint-translation-pipeline.md` — its plan
  (contains the `assembleDevArm64Debug` build command)
- `docs/superpowers/specs/2026-10-09-dynamic-page-batching-design.md` +
  `docs/superpowers/plans/2026-10-09-dynamic-page-batching.md` — DYNAMIC batching
- `docs/superpowers/plans/2026-10-09-full-page-paddleocr-detection-and-spatial-grouping.md`
- `ARCHITECTURE_LAYERS.md`, `AGENT_HANDOFF_2026-09-16.md`, `AGENT_HANDOFF_2026-09-18.md`
- `docs/paddle_ocr_performance_investigation_report.md`, `docs/translation-input-quality-plan.md`

## Constraints

- Avoid Gradle unless a rebuild is genuinely needed. If building: use
  `gradlew.bat assembleDevArm64Debug`, and confirm no other machine-wide Gradle build is
  running first.
- **Never push.** Commits stay local on `release/apk-6-candidate`.
- Report results faithfully — do not claim capture is active or pages were captured until
  verified in the file.
