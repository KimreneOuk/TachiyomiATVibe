# A5 — Stage cache fingerprints

## Delivered

A5 gives Studio pages and downstream stages content-addressed cache inputs. A top-level page operation fingerprints the source once; nested calls reuse that value. Detection capture and decision replay have separate fingerprints, and OCR, inpaint, and render each carry the relevant upstream fingerprint chain. Legacy cache records without the new provenance are recomputed.

A source change clears in-memory dimensions, bubble masks, OCR crop/render assignment caches, and the page's detection/OCR/inpaint/render/thumbnail/segmentation artifacts. State reads fresh image-header dimensions without hashing or decoding full page pixels. Translation entries stay intact because they are outside the A5/C2 scope. Translation text is excluded from inpaint inputs and included in render inputs.

Display-only detection replay operates on a deep copy. It does not mutate or rewrite detections.json; repeated persistent replay writes only when its decision fingerprint changes. Page operations are guarded by the pipeline lock. Image GET routes now validate the current render/inpaint cache before serving the saved output, so an existing PNG cannot bypass fingerprint validation.

## Fingerprint scheme

Fingerprints are SHA-256 over canonical JSON (sorted keys, compact separators, UTF-8). The page's compressed source bytes are read in 1 MiB chunks; the pixel image is not copied to hash it.

| Stage | Inputs bound into the key |
|---|---|
| Page | Hash algorithm/version, chapter-relative POSIX page identity, compressed byte size, image width and height, SHA-256 of the complete compressed source file |
| Detection capture | Page fingerprint and dimensions, capture schema/algorithm, text-detector asset identity, panel/segmenter identities only when inference is eligible/enabled, raw score floor |
| Detection replay | Capture and page fingerprints, execution confidence, panel/segmenter assignment eligibility, reading order, source language, replay algorithm, ordered regions/panels/candidate IDs |
| OCR | Page and detection-decision fingerprints, OCR engine and model asset identities, OCR algorithm, engine-relevant batch/language/normalization settings, ordered detection geometry and assignments; the OCR output gets a separate fingerprint |
| Inpaint | Page, detection-decision and OCR-output fingerprints; ordered OCR region identity/geometry/text/labels; planner candidates/items; selected mask references and RLE digests; engine, used legs, mode and relevant parameters; only assets used by selected legs |
| Render | Page, detection-decision, OCR-output and inpaint fingerprints; render regions, translations, dimensions, font identity/settings and render algorithm |

Algorithm version fields invalidate artifacts created by earlier implementations. The page source is rehashed for each independent top-level operation, so same-size replacement under the same name is detected. process_page shares its page fingerprint through detection, OCR, inpaint and render and hashes that source once. Model/font asset SHA results are memoized by resolved path, modification time, byte size and an 8 KiB sample (the first and last 4 KiB plus length). Every check reads only that fixed sample on a cache hit; a stat or sample change triggers a full 1 MiB-streamed SHA-256.

## Kotlin and survey cross-check

No Android source was changed. Current Kotlin StageFingerprints confirms the same dependency directions: detection binds source/model/configuration versions; OCR binds its detection artifact, engine/model, source language and preprocessing/normalization; inpaint binds source/mask artifacts, engine/model/mode/settings; layout binds translation and cleaned-image/source artifacts plus font/style/dimensions (app/src/main/java/eu/kanade/translation/artifact/StageFingerprints.kt:29-105). StageArtifactRecord.fingerprint is nullable and documented as the stage input fingerprint; null is unknown provenance (app/src/main/java/eu/kanade/translation/artifact/ArtifactContracts.kt:160-173). Studio's JSON/filesystem fingerprints are intentionally not byte-compatible with Kotlin's artifact fingerprints; they express the analogous invalidation chain within the desktop cache format.

One survey-versus-source correction: the desktop survey records the then-observed 0.45 Studio confidence default (team/02-desktop-survey/REPORT.md, cache/behavior notes), while current Kotlin OnnxPageTextDetector.kt filters at 0.6 (CONFIDENCE_THRESHOLD, postprocess). Current Studio defaults to 0.6 (pipeline.py:3175-3176), so the present defaults agree. A5 fingerprints the runtime confidence value rather than changing confidence semantics. The Android survey's exact-ID translation mapping is confirmed by the Kotlin request/response mapping sources noted in team/01-android-survey/REPORT.md; A5 leaves that C2 behavior untouched.

## Verification

All commands were run from the repository root unless otherwise noted.

- python -m py_compile tools/translation_studio/pipeline.py tools/translation_studio/server.py tools/translation_studio/selftest_cache_fingerprints.py tools/translation_studio/selftest_mask_planner.py — passed.
- python tools/translation_studio/selftest_cache_fingerprints.py — passed. Evidence: evidence/cache_selftest.json. It verifies same-size page replacement, downstream invalidation after a detection geometry change, direct inpaint validation of changed detector/OCR inputs, unused panel-asset changes without recapture, same-size/same-mtime asset replacement detected by the sample probe, unchanged-input cache hits, translation-only render invalidation, read-only GET replay, no repeat replay writes, and one page hash across process_page.
- python -c "import sys; from pathlib import Path; sys.path.insert(0,'tools/translation_studio'); import selftest_mask_planner as t; t.EVIDENCE_PATH=Path('Plan/active/2026-09-23_T937_translation-studio-parity/team/07-A5-fingerprints/evidence/a4_mask_planner_regression.json').resolve(); t.main()" — passed. Evidence: evidence/a4_mask_planner_regression.json.
- python tools/translation_studio/selftest_ocr.py — passed, 17 tests.
- python tools/translation_studio/test_translation_providers.py — passed, 16 tests.
- python Plan/active/2026-09-23_T937_translation-studio-parity/team/07-A5-fingerprints/evidence/run_inpaint_baseline.py — passed all demo preset checks and synthetic routing probes. The wrapper uses a temporary demo copy, temporary .recent.json, and evidence/inpaint_baseline/ output so the pre-existing demo cache and A2 evidence are not overwritten. Summary/routes: evidence/inpaint_baseline/selftest_summary.json and evidence/inpaint_baseline/*_routes.json.

The inpaint run encountered the worktree's known 132-byte Paddle detector ONNX LFS pointer, reported INVALID_PROTOBUF, and followed its existing unavailable-detector fallback; MangaOCR, the text detector, AOT routes and the test assertions completed successfully. This run does not claim Paddle refinement was exercised.

## Independent review follow-up

The independent review identified direct-stage upstream validation, chapter-switch locking, unused-model over-invalidation and same-stat asset replacement as issues. A5 now calls current detection and validates any existing OCR record before inpaint cache lookup; the reentrant page lock covers validation through each stage and the full process chain; disabled/skipped model asset identities are ignored until that model becomes eligible; and asset hash cache hits run the head/tail sample probe before reusing a full digest. These cases are covered by the focused cache evidence.

## Limits and risks

- Page hashing adds a sequential read of each compressed page once per independent operation. Working hash memory is bounded at 1 MiB; process_page hashes once across nested stages. Large source images are not decoded a second time for validation.
- The asset spot-check reads the first and last 4 KiB plus file length on a stat-cache hit. A middle-only edit that preserves size, timestamp, and both boundary samples could retain the prior digest until process restart; this is accepted as a manual-tampering edge case to avoid hashing roughly 146 MB of OCR/AOT models on each page operation.
- Cache image paths remain stem-based as in the existing Studio layout. Chapters with two page filenames sharing a stem may have cache reuse churn; the content/identity fingerprints prevent a false fingerprint match, but the shared output path layout itself is not redesigned here.
- Full inference parity with a hydrated Paddle detector model remains outside this cache task and was not tested in this worktree.
