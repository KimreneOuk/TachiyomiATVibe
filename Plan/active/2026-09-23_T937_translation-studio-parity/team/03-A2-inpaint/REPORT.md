# T937 A2 — Android Inpainting Port

## Delivered

- Added `inpaint_android.py` as a selectable Android-parity engine; the existing `aot_inpaint.py` legacy engine remains available and unchanged.
- Ported planner, bubble/free-text partition, Paddle refinement, segmentation and box masks, Android fill, OpenCV Telea, fixed/dynamic AOT with fallback, and PushPull routing. Each returned region carries source boxes, route, mask-component id, context bounds, and stage timings.
- Added the `android` engine, bubble and free-text leg settings, Android FAST/QUALITY presets, route JSON provenance, and an inpaint cache key containing engine and leg matrix. The Clean action now sends `force: true`.
- Added `requirements.txt` with NumPy, Pillow, SciPy, ONNX Runtime, and OpenCV; its comment marks cv2 as the Telea leg.

Implementation entry points: [inpaint_android.py](../../../../../tools/translation_studio/inpaint_android.py:172), [pipeline.py](../../../../../tools/translation_studio/pipeline.py:703), [index.html](../../../../../tools/translation_studio/static/index.html:466), [app.js](../../../../../tools/translation_studio/static/app.js:1253), and [selftest_inpaint.py](../../../../../tools/translation_studio/selftest_inpaint.py:32).

## Kotlin source checks and corrections

| Area | Source verification and implementation result |
|---|---|
| Planner and parent bubble | `PageInpaintingPlanner.kt:83-128` filters blank OCR, emits parent rectangles as label 0 and text boxes with their labels, and checks expanded detector boxes against all OCR boxes. `RoiPageRecognitionEngine.kt:586-600` confirms the stored block geometry is the detector text box; the studio uses `box`, not its padded `ocr_box`. Parent selection and trimming follow `OcrBlockDeduplication.kt:149-240`. |
| Overlap and duplicate handling | Kotlin uses 20% overlap to recover a parent bubble during OCR recognition (`OcrBlockDeduplication.kt:35,213-240`), while the inpainting partition uses 12% (`AotBoxGeometry.kt:57-76`). These are different stages; the port now keeps separate 0.20 and 0.12 constants. Kotlin deduplicates parent boxes and extra detector boxes (`PageInpaintingPlanner.kt:87-122`) but retains duplicate text-box entries. The port mirrors that scope rather than removing OCR duplicates. |
| Partition and free-text refinement | Label fallback and routing follow `AOTInpainting.kt:350-410` and `AotBoxGeometry.kt:40-76`. The 12px crop and Paddle thresholds 0.18/0.34 match `AOTInpainting.kt:45-47,448-502`; cluster merging matches the 512px union-area rule in `AotBoxGeometry.kt:87-125`. |
| Bubble mask and fill | Radius-5 disk erosion, radius-2 retry, then raw-mask fallback match `AOTInpainting.kt:565-655`. The source excludes a text box from box fallback when any associated raw segmentation overlaps it (`AOTInpainting.kt:529-539`); fallback boxes use +8px and disk radius `clamp(shortSide/8, 2, 16)` (`BubbleMaskBuilder.kt:320-349`). Median fill, 4-neighbour boundary BFS, distance-2 sampling, luma snap, inset, 12 smoothing passes, and the 12px chamfer feather match `AotReportBubbleFill.kt:33-227` and `BubbleMaskBuilder.kt:177-279`. |
| FAST and PushPull | Free-text crop, +1 box pad, radius-2 dilation, Telea radius 3, and feather 3 follow `AOTInpainting.kt:45-54,657-675`; the crop bounds use `AotBoxGeometry.kt:17-23`. The reused PushPull path has matching Android constants: ring 8, downsample divisor 20, and 15 diffusion passes (`PushPullGradient.kt:26,32,35`; corresponding constants in `aot_inpaint.py:63-65`). |
| AOT | Fixed input uses the `[0,1]` contract and leaves masking to the fixed model (`AotPixelOps.kt:18-26`); the dynamic path uses `[-1,1]`, zeros masked pixels, aligns to 8, and caps inference at 768 (`AOTInpainting.kt:1179-1232,1274-1318`). The report route first creates a centered 512px crop (`AotBoxGeometry.kt:26-37`) and calls the generic routine with `maskAlreadyCropped=true` (`AOTInpainting.kt:1086-1116`), so that route bypasses the generic 32px dynamic margin. The port follows this direct Kotlin call path; it does not add another margin around the report crop. Fixed rejection/exhaustion cascades through dynamic AOT and then FAST. |

## Verification and evidence

- `python -m py_compile` passed for `pipeline.py`, `inpaint_android.py`, and `selftest_inpaint.py`.
- `node --check tools/translation_studio/static/app.js` and `git diff --check` passed.
- `python selftest_inpaint.py` passed on both `demo_chapter` pages for Android FAST and Android QUALITY. Each case produced a nonempty mask and a route for every region. The demo pages route their OCR regions through `bubble/android-fill`.
- The selftest also exercised OpenCV, AOT fixed-512, forced fixed-output rejection followed by dynamic AOT, PushPull, and the selectable bubble variants; it verified changing the leg matrix invalidates/recomputes the cached output.
- `python studio.py --chapter demo_chapter --no-browser` started successfully; end-to-end calls were made directly through `Pipeline` by the selftest.
- Evidence is under [evidence/](evidence/). [selftest_summary.json](evidence/selftest_summary.json) indexes the PNG masks, cleaned PNGs, and per-case route JSONs. It records 192,256 mask pixels for p001 and 211,010 for p002 in both production presets.

## Known gaps

- The checkout's Paddle detection asset fails ONNX Runtime loading with `INVALID_PROTOBUF`. The pipeline catches this and retains original detector boxes, so demo free-text Paddle refinement itself could not be exercised. Other free-text paths were exercised by the synthetic probes.
- The demo pages' regions are bubble-associated, so their QUALITY preset does not invoke AOT for production free-text. The synthetic probe reaches fixed AOT and, after a forced guard rejection, dynamic AOT.
- The desktop port bounds AOT tensor dimensions but does not reproduce Android's runtime system-headroom memory gate. The Android app retains that gate; Studio is a local test bench.

## File surface

Changed only the allowed inpainting sections in `tools/translation_studio/pipeline.py`, inpaint settings markup/handlers in `static/index.html` and `static/app.js`, plus `inpaint_android.py`, `requirements.txt`, `selftest_inpaint.py`, and this report with its evidence directory. Detection and OCR source paths, `boxgeom.py`, `paddle_ocr.py`, and legacy `aot_inpaint.py` were not edited.
