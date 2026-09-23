# Gate 1 final review — Phase A engine parity

**Reviewed head:** `main@e00f0c38d95f9317a9591adce11eff93684b9aa1` (B2 merged). The reviewed pipeline modules have no diff from the assigned `c84ef46` base; B2's code diff is limited to `server.py` and `static/*` (`git diff --exit-code c84ef46..HEAD -- tools/translation_studio/{pipeline.py,inpaint_android.py,detection_artifacts.py,paddle_ocr.py,sliding_detector.py}` was clean; `server.py` B2 diff adds `/api/artifacts`, `/api/export-filtered`, and `/img/seg_overlay`).

## Verdict

**GATE 1: CONDITIONAL-PASS.** All five original A1/A2 findings are resolved. A5 findings 1–3 are fixed; finding 4 is fixed on the active production path, with a dormant unused helper and sampling limits documented below. The full verification suite passes on `e00f0c3`, and real-model Studio evidence is complete. Gate closure is conditional on B1 fixing and reviewing the confirmed MEDIUM render snapshot defect below. It can render stale OCR geometry over the newly validated inpaint and persist mismatched OCR/inpaint lineage.

## Original A1/A2 blockers

| # | Finding | Verdict | Verification |
|---|---|---|---|
| 1 | Inpaint/render do not consistently consume A1 captured masks (original review `review/phase-a-a1-a2/REVIEW.md:19-33`). | **RESOLVED** | Detection records carry the `segmenter_assignment` (`pipeline.py:1270-1271,1351-1355`). Android inpaint passes the current capture's cached mask RLE and outputs into the planner (`pipeline.py:2199-2230`); the planner resolves each assigned `(mask_ref, component_id)` instead of rerunning segmentation (`inpaint_android.py:247-248,433-486`; `detection_artifacts.py:208-255`). Rendering uses the projected region's same assignment and resolver (`pipeline.py:3023-3078`). The A4 selftest records matching `bs0000` references for capture, inpaint, and render (`evidence/mask_planner.json:4-18`); its tall case has matching union hashes and zero full-page segmenter reruns (`:55-67`). Missing current refs degrade explicitly without a fallback rerun (`detection_artifacts.py:208-240`; `pipeline.py:3077-3085`). |
| 2 | Low-confidence detector candidates can reach the inpaint planner (original review `:34-46`). | **RESOLVED** | Execution projection replays only finite scores at or above the active confidence (`pipeline.py:1141-1157,1883-1956`); current captures pass that threshold to `plan_erase_regions` (`pipeline.py:2131-2138`). The planner filters before label routing; label 0 is bubble-parent context while labels 1/2 are detector-only erase candidates (`inpaint_android.py:88-120,200-210,294-310`). Exactly `0.6` is the parity floor and lower thresholds are experimental per the Director decision (`PLAN.md:41-43`; `engineering/phase-a-remediation-plan.md:24-27`). The A4 evidence shows below-threshold label-0 and label-2 items excluded at `0.6`, then eligible at `0.3` with non-parity provenance (`evidence/mask_planner.json:13-28`). |
| 3 | Inpaint cache reuse ignores upstream inputs (original review `:47-59`). | **RESOLVED** | Every inpaint request validates detection and cached OCR before computing its key (`pipeline.py:2113-2129`). The key includes page and decision fingerprints, OCR fingerprint/regions, planner candidates, selected RLE digests, active legs/parameters, and only used assets (`pipeline.py:1965-2111`). Direct-inpaint selftest evidence confirms detector-identity change recaptures, OCR-setting change recomputes, and no force flag is needed (`evidence/cache_fingerprints.json:27-31`). |
| 4 | Detection cache is not bound to page content (original review `:60-70`). | **RESOLVED** | A page operation computes a SHA-256 over the compressed page bytes and binds identity, dimensions, byte size, and content digest (`pipeline.py:142-148,202-222`). Detection inputs store and validate that fingerprint before cache reuse (`pipeline.py:776-803,828-858`). Equal-size, equal-compressed-byte-count source replacement changes the fingerprint and invalidates dependent capture/OCR/inpaint/render outputs (`evidence/cache_fingerprints.json:3-17`). |
| 5 | A2 evidence did not prove Paddle free-text refinement (original review `:71-82`). | **RESOLVED** | Real Paddle detector and recognizer models were verified hydrated at 9,880,512 B and 21,159,378 B (`evidence/real_model_studio_summary.json:40-49`). Original `p001.jpg` ran Paddle OCR; a controlled `テスト` overlay on the same real page made the free-text branch exercise real PaddleDet at `thresh=0.18`, `box_thresh=0.34`, returning a refined line (`evidence/real_model_studio_summary.json:623-750`; per-region `studio_runs/regular-demo-free-text-control/android-fast/routes.json:628-656`). This closes the evidence gap while keeping clear that the control image has synthetic text added. |

## Original A5 review findings

The four findings in `team/07-A5-fingerprints/REVIEW.md:12-54` were subsumed and checked against the A5 commit `c7eafdf` (ancestor of the reviewed head).

| # | Finding | Verdict | Verification |
|---|---|---|---|
| 1 | Inpaint can reuse artifacts without validating upstream captures. | **RESOLVED** | `inpaint_page()` calls `detect_page()` and, when present, `ocr_page()` before building the key (`pipeline.py:2123-2129`). The direct-inpaint tests force both detector identity and OCR-setting changes through that path (`selftest_cache_fingerprints.py:195-220`; evidence `cache_fingerprints.json:27-31`). |
| 2 | Page validation and stage call are not one locked operation. | **RESOLVED** | `_page_operation` holds the pipeline `RLock` across both fingerprint validation and the full wrapped stage (`pipeline.py:142-148,156-158`); `open_folders()` mutates chapter/cache state under that same lock (`pipeline.py:391-425`). |
| 3 | Skipped detection models still invalidate raw capture. | **RESOLVED** | Inputs represent a skipped panel model or disabled segmenter as skipped; validation excludes those unused model fields from comparisons (`pipeline.py:776-803,828-858`). The selftest confirms a skipped panel asset change does not recapture the text detector (`selftest_cache_fingerprints.py:300-303`; `evidence/cache_fingerprints.json:32-35`). |
| 4 | Same-size/same-timestamp model replacement may retain an old digest. | **RESOLVED-WITH-LIMITS** | The production digest memo key includes a length/head/tail sample (first and last 4 KiB), so a same-stat replacement changing those bytes forces a fresh full digest (`pipeline.py:102-134`). The test restores size and mtime and confirms the production digest changes (`selftest_cache_fingerprints.py:23-40`; `evidence/cache_fingerprints.json:36-40`). A focused probe confirms the separate `detection_artifacts.asset_sha12()` helper remains stat-keyed and stale on that replacement (`detection_artifacts.py:13-25`; `evidence/probe_asset_digest_helpers.py:27-57`; `evidence/asset_digest_helper_probe.json:1-14`). A repository call-site scan found only that helper's definition, so it is dormant in the current pipeline (`asset_digest_helper_probe.json:10-14`). A same-stat edit confined to the production sample's unsampled middle bytes can also retain the prior full digest. These are residual limits, not current production call-path failures. |

## Added A5 follow-up finding

**MEDIUM — UNRESOLVED on reviewed `main`; B1 pending.** The follow-up finding supplied with the gate brief is confirmed. `render_page()` snapshots `ocr` before calling `inpaint_page()`, whose validation can replace the OCR cache entry, then uses the old local snapshot both for cache inputs and `render_regions()` (`pipeline.py:2446-2458,2476-2488`). GET `/img/render` calls `render_page()` directly (`server.py:219-229`), so the first request after changed detector input can overlay stale geometry on fresh inpaint and persist the stale OCR fingerprint paired with the new inpaint fingerprint. A focused direct `render_page()` reproduction observed fresh OCR in cache but stale region ID/geometry rendered and stale OCR fingerprint saved beside `inpaint-after-validation` (`evidence/reproduce_stale_render_snapshot.py:34-86`; `evidence/stale_render_snapshot_repro.json:1-9`).

The named `team/07-A5-fingerprints/REVIEW_FOLLOWUP.md` was not present in this primary checkout; this finding was checked from the supplied addendum, live source, and the isolated reproduction. Gate closure condition: merge B1 and verify the described changed-detector/direct-render case returns the fresh OCR geometry and fingerprint.

## Verification suite

All requested groups passed on current `main@e00f0c38d95f9317a9591adce11eff93684b9aa1`: `py_compile` for the five named modules; `selftest_cache_fingerprints.py`; `selftest_mask_planner.py`; `selftest_ocr.py`; `test_translation_providers.py`; and `selftest_inpaint.py` (`evidence/verification_suite.txt:1-7`; full captured output in `evidence/verification_suite.json`).

## Real-model Studio run

`run_real_model_studio.py` ran the real `Pipeline.process_page(translate=False)` chain and replayed unchanged stages, with no fake ONNX sessions (`evidence/run_real_model_studio.py:90-118,139-160,190-230,299-329`). It covered original demo `p001.jpg`, the A1 method's centered `p001`+`p002` 900×2800 tall stack, and a `p001` free-text control, each on Android FAST and QUALITY (`evidence/real_model_studio_summary.json:2-38,63-64,175-176,282-283,459-460,623-624,840-841`).

- All six cases wrote per-region route JSON under `evidence/studio_runs/{regular-demo,a1-tall-stack,regular-demo-free-text-control}/{android-fast,android-quality}/routes.json`.
- Original `p001.jpg` used Paddle OCR with detector/recognizer model calls; both Android presets used captured bubble routes (`real_model_studio_summary.json:63-165,175-272`). The free-text control invoked PaddleDet refinement with returned line geometry and routed the region through FAST OpenCV and QUALITY AOT (`:623-750,:840-962`; FAST route record `studio_runs/regular-demo-free-text-control/android-fast/routes.json:628-656`).
- The A1 tall stack ran three sliding windows (`top=0/1010/2020`, bottoms `1260/2270/2800`); captured segmenter inputs were window-sized and the FAST case recorded zero full-page segmenter calls (`studio_runs/a1-tall-stack/android-fast/detection_capture.json:237-254`; `real_model_studio_summary.json:282-450`). FAST and QUALITY route summaries confirm six OCR regions and bubble Android fill (`:438-449,:602-613`).
- Second unchanged-stage runs hit OCR and inpaint caches with zero extra model calls for each case (`real_model_studio_summary.json:162-165,269-272,446-449,610-613,827-830,1039-1042`).

## Kotlin parity spot-checks

Kotlin is treated as the source of truth. These seven load-bearing comparisons match the desktop implementation:

| Behavior | Kotlin source | Studio source | Result |
|---|---|---|---|
| Detector floor `0.6`, score rounding to 4 decimals, detector dedup thresholds IoU/containment/center/size `0.75/0.88/0.12/0.18`. | `OnnxPageTextDetector.kt:173-194,223-237` | `pipeline.py:1141-1162`; `boxgeom.py:66` | Match; Director's execution threshold remains independently applied at 0.6. |
| Tall-page threshold `height/width >= 2.0`, target window `round(width*1.4)`, overlap `min(250,target/4)`, crop-by-window detection. | `WebtoonSlidingDetector.kt:22-68,152-183` | `sliding_detector.py:9-38` | Match; live tall run produced three windows and no full-page segmenter rerun. |
| Paddle OCR line planning uses `thresh=0.2`, DB box threshold `0.45`; recognition confidence floor `0.5` except whole-region degraded fallback. | `VerticalLineOcr.kt:115-145,164-195`; `DbPostProcess.kt:67` | `paddle_ocr.py:28-30,52,518-520,611-614` | Match. |
| Free-text inpaint detector refinement uses `0.18/0.34`. | `AOTInpainting.kt:45-47,470` | `inpaint_android.py:31-32` | Match; verified call and returned line in real-model evidence above. |
| Free-text erase mask uses pad 1, disk dilation 2, feather ramp 3. | `AOTInpainting.kt:49-52,657-675`; `BubbleMaskBuilder.kt:127-141,366-380` | `inpaint_android.py:34-36,616-617,770-772` | Match. |
| Segmenter union erosion uses disk radius 5, falls back to radius 2 for vanished components, then restores original component if needed. | `AOTInpainting.kt:55,565-654` | `inpaint_android.py:37,545-575` | Match. |
| OCR line/column assembly concatenates Japanese/Chinese without separators and joins other languages with a space; vertical Korean remains on the spaced path. | `TextRecognizerLanguage.kt:21-30`; `VerticalLineOcr.kt:455-484` | `paddle_ocr.py:427-442,603-618` | Match. |

## Phase B notes

- The assigning agent reports intermittent `500 /api/page` responses during real browser-boot concurrency and has routed that issue to B1. This Gate 1 run used direct sequential pipeline calls and did not exercise or diagnose that browser race; the supplied reproduction harness is in `team/08-B2-visualization/evidence/merge_diag/repro_b2f.py:1-24`.
- UI layout acceptance was informational to this gate and is not assessed here. The head/tail asset sample limit above should remain documented as a cache identity limitation.
