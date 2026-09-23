---
kind: review
title: "T937 A4 Mask Planner Independent Review"
---

# T937 A4 mask planner follow-up review

I re-checked the three reported remediations in the shared worktree, including their regression cases. No implementation files were changed. The focused model-free selftest was run and passed: `python tools/translation_studio/selftest_mask_planner.py` (exit 0).

## Finding closure

### 1. RESOLVED — HIGH: Captured mask union included records outside the bubble-text erase plan

**Verification:** The implementation now builds `bubble_record_ids` from the partitioned `bubble_items` and passes that set into `_captured_segmenter_union` (`tools/translation_studio/inpaint_android.py:1041-1048`). Assigned records outside the set are marked `not-selected-for-bubble-leg` and skipped before mask resolution or union writes (`inpaint_android.py:448-469`).

The added `_bubble_union_scope_check` fixture gives separate components to a readable bubble OCR record, a blank OCR record, and a free detector-only proposal. It asserts the union contains only the 16 pixels expected from the bubble record and checks the blank/free provenance states (`tools/translation_studio/selftest_mask_planner.py:310-363`). The focused selftest passed; its evidence records the expected and actual union count and hash (`team/06-A4-mask-planner/evidence/selftest_mask_planner.json`, `bubble_union_scope`). This resolves the previously reported over-selection.

### 2. RESOLVED — MEDIUM: Bubble-segmentation changes could reuse stale inpaint output

**Verification:** `bubble_segmentation` is now part of the inpaint cache key (`tools/translation_studio/pipeline.py:1490-1498`). The regression case calls inpaint without `force` first with segmentation disabled, then enabled, and checks the recorded key and resulting segmenter-union pixel counts (`tools/translation_studio/selftest_mask_planner.py:206-221`). The focused selftest passed and reports 0 pixels while disabled and 1,444 after re-enabling (`selftest_mask_planner.json`, `normal_page.segmentation_setting_cache`). The key change and non-forced output checks resolve this finding.

### 3. RESOLVED — MEDIUM: Current-capture rendering could draw a cached translation outside the active confidence projection

**Verification:** Current-capture rendering now appends an audit record and skips regions absent from the current projection; it then replaces the draw list with only eligible regions (`tools/translation_studio/pipeline.py:2290-2322`). The regression case retains a translated low-confidence OCR region, raises confidence to 0.6, and checks both its exclusion status and that its pixels remain identical to the inpainted background (`tools/translation_studio/selftest_mask_planner.py:192-205`). The focused selftest passed; evidence records `excluded_region_drawn: false` (`selftest_mask_planner.json`, `normal_page.confidence_raise_render`). This resolves the rendering inconsistency.

## Residual limitations

- The new regression cases and the tall-page check use synthetic, model-free fixtures. They verify the targeted logic but do not establish full real-model manga-page behavior.
- The A4 report still states that real Paddle refinement was not re-exercised in this worktree because the local detector asset failed to load, and full real-model tall-page Studio processing remains unverified.
- Page, OCR, candidate, and content fingerprinting remains A5 work; this follow-up closes the segmentation-setting cache-key gap but does not establish upstream content-based invalidation.
- The overlay now returns a transparent RGBA image when `bubble_segmentation` is false, before reading or rasterizing captured masks (`tools/translation_studio/pipeline.py:1633-1638`). The toggle test asserts every alpha pixel is zero while disabled (`selftest_mask_planner.py:206-214`); the enabled tall-page RLE path is also asserted. I reran the focused selftest after this update and it passed.
