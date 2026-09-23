---
kind: review
title: "A5 cache fingerprints review"
---

# A5 cache fingerprints — independent review

Scope: the live A5 changes in `pipeline.py`, `server.py`, and the two cache selftests, against the assigned A5 contract. No implementation was changed; tests were not run.

## Findings

### 1. [MEDIUM] Inpaint can reuse artifacts without validating its upstream captures

**Likelihood:** Medium
**Classification:** Defec
**Evidence:** VERIFIED for the validation bypass; STRONG INFERENCE for a stale cache hit.

`detect_page()` checks the capture algorithm, page identity, model assets, and statuses before reuse, but `inpaint_page()` calls it only when the detection entry is absent (`tools/translation_studio/pipeline.py:796-815`, `1021-1044`, `2069-2075`). With an existing entry, `_inpaint_execution_projection()` trusts `capture_version == 1` and replays that record, while `_inpaint_cache_inputs()` fingerprints the stored decision/OCR fingerprints and leg assets, not current detector assets or current OCR settings (`pipeline.py:1833-1865`, `1913-2058`). The provenance-key equality then permits reuse when the cached output files exist (`pipeline.py:2124-2126`). The `/img/inpainted` and `/img/render` GET handlers call these stage methods directly (`server.py:159-181`).

After a detector/segmenter asset change, or an OCR setting change before OCR is refreshed, a direct inpaint/render request can therefore keep using the old upstream result. The selftest covers page-byte and edited-detection changes, but not changed model assets or stale upstream settings (`selftest_cache_fingerprints.py:131-181`).

**Confirm/refute:** Change a detector asset identity or OCR setting after populating the caches, call `/api/inpaint` without first calling `/api/detect` or `/api/ocr`, and inspect whether the capture/OCR is refreshed before a reported inpaint cache hit.

### 2. [MEDIUM] Page validation and the stage call are not one locked operation

**Likelihood:** Low
**Classification:** Defec
**Evidence:** VERIFIED for the lock gap; STRONG INFERENCE for the concurrent request failure.

`_page_operation()` exits `_page_operation_scope()` before acquiring `self.lock` for the wrapped method (`tools/translation_studio/pipeline.py:120-127`). The scope holds the lock only while computing and accepting the fingerprint, then yields outside that lock (`pipeline.py:164-178`). Meanwhile `open_folders()` changes `chapter`, reloads caches, and clears page fingerprints under the same lock (`pipeline.py:369-400`). Because the server is a `ThreadingHTTPServer` and image GETs invoke these decorated stage methods (`server.py:159-181`, `265-267`), a concurrent `/api/open` can switch chapters between validation and cache access. `process_page()` also keeps only the scope, not the lock, across its multi-stage sequence (`pipeline.py:2496-2515`).

The affected request may then use a page fingerprint from the previous chapter with the new chapter state, producing a failed request or a fallback image during chapter switching.

**Confirm/refute:** Coordinate `/api/open` between page-scope validation and the wrapped method (or between `process_page()` stages); verify that the stage still uses the original chapter snapshot, or reproduces the mismatched-state failure.

### 3. [LOW] Assets for skipped detection models still invalidate raw capture

**Likelihood:** Low
**Classification:** Design limitation / avoidable over-invalidation
**Evidence:** VERIFIED.

`_detection_capture_inputs()` always includes panel-detector and bubble-segmenter asset identities (`tools/translation_studio/pipeline.py:755-771`), and `_detection_cache_is_current()` compares the entire input map (`pipeline.py:796-806`). Yet panel inference is skipped for some page/settings combinations (`pipeline.py:744-753`), and segmenter inference is skipped when bubble segmentation is disabled (`pipeline.py:942-955`). Replacing an asset that was not used for the page can therefore force recapture of the text detector. This is broader than the A5 contract’s enabled-model input identity.

**Confirm/refute:** On a page where panel inference is skipped, change only the panel model asset and check whether `detect_page()` recaptures the text detector.

### 4. [LOW] Same-size, same-timestamp model replacements can retain the old diges

**Likelihood:** Low
**Classification:** Defec
**Evidence:** VERIFIED for the digest cache key; STRONG INFERENCE for missed invalidation under that file replacement.

`_cached_file_sha256()` memoizes by resolved path, `mtime_ns`, and size (`tools/translation_studio/pipeline.py:102-108`); `_asset_identity()` supplies exactly those metadata fields to that cache (`pipeline.py:111-116`). `detection_artifacts.asset_sha12()` uses the same path/mtime/size cache key (`tools/translation_studio/detection_artifacts.py:13-26`). If an OCR, detector, or inpaint model is replaced while preserving its size and timestamp, the cached digest remains the old one and the new model content is not reflected in the cache identity. The page digest avoids this issue by hashing the page bytes directly (`pipeline.py:180-199`).

**Confirm/refute:** Replace a model file with different same-size bytes, restore its prior timestamp, and compare `_asset_identity()` / `asset_sha12()` before and after; a fresh content digest should change.
