# A4 — Bind planner and mask consumers to captured artifacts

## Result

Implemented A4 on `t937-a4-mask-planner`, based on `d2637a0`. Android inpaint
and text rendering now resolve A1's `artifact_id`, `segmenter_assignment`, and
page-space mask RLEs. Tall-page A2 inpaint consumes window-projected RLEs and
does not call `bubble_masks()` or rerun the segmenter on the full page.

The planner replays the cached detector capture at the current confidence
setting, joins cached OCR regions by `artifact_id`, and sends only eligible
detector artifacts to planning. Raw detector artifacts remain available for
diagnosis. Exactly `0.6` is recorded as `android-production` for a current
Android capture; other thresholds and legacy-schema fallback runs are marked
`experimental/non-parity` and record their confidence in execution provenance
and the inpaint cache key.

## Changes

- Attach A1 `segmenter_assignment` references to the complete current
  confidence-projected detector set before OCR-stage deduplication. This also
  gives detector-only proposals stable artifact and mask provenance.
- Resolve the assigned component RLEs into one shared page union before
  erosion. Flat row-major runs are split at page-row boundaries while
  decoding. No dense full-page mask is created for each RLE component.
- Render with the captured assignment and decode only the text-fit/clip crops
  from its RLE. The render result reports the resolved `mask_ref` and source
  component for each translated region.
- Draw the segmentation overlay directly from cached RLE row spans, including
  tall-page windows; the overlay path does not request fresh segmentation.
- Add `segmenter_component_id` and `erase_mask_component_id`; keep
  `mask_component_id` equal to the erase-mask ID. Inpaint provenance is schema
  version 2 and retains segmenter model, asset, hash, window, and mask-ref
  metadata.
- A missing reference in a current capture is reported through
  `execution.mask_capture_integrity` and per-region resolution provenance.
  Current captures do not fall back to a fresh segmenter run. Legacy schemas
  keep the compatibility adapter.
- Include the execution confidence in the inpaint cache key so changing the
  A4 execution threshold recomputes the output. Include the segmentation
  enablement setting as well, so toggling it recomputes both with and without
  cached segmenter assignments. No page/content fingerprints or other A5
  cache identity changes were made.

## Verification

Commands run from the repository root:

```powershell
python -m py_compile tools/translation_studio/detection_artifacts.py tools/translation_studio/inpaint_android.py tools/translation_studio/pipeline.py tools/translation_studio/selftest_mask_planner.py
python tools/translation_studio/selftest_mask_planner.py
```

Both passed. The focused model-free selftest covers a normal page and a
synthetic tall page, current-reference degradation, `.6` versus `.3`, low-score
label-0 parent exclusion, detector-only threshold gating, preserved raw
artifacts, render/inpaint reference identity, distinct component IDs and the
legacy alias, schema versioning, row-crossing RLE decoding, direct tall-page
RLE overlay rendering, detector model/asset/hash/window provenance, bubble
union exclusion for blank OCR and free detector proposals, non-forced
segmentation-setting cache toggles in both directions, stale translation
exclusion after raising confidence, and the exact pre-erosion union hash. Its
machine-readable output is
[`evidence/selftest_mask_planner.json`](evidence/selftest_mask_planner.json).

The existing A1 capture test was run against the A4 worktree source with its
evidence redirected to a temporary directory:

```powershell
python -c "import sys,importlib.util,tempfile; from pathlib import Path; sys.path.insert(0,str(Path.cwd()/'tools/translation_studio')); import pipeline; p=Path('C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/Plan/active/2026-09-23_T937_translation-studio-parity/team/04-A1-detection/evidence/selftest_detect.py'); s=importlib.util.spec_from_file_location('a1_selftest',p); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); m.REPO=Path.cwd(); m.STUDIO=Path.cwd()/'tools/translation_studio'; m.DEMO=m.STUDIO/'demo_chapter'; m.EVIDENCE=Path(tempfile.mkdtemp(prefix='t937-a4-a1-evidence-')); m.main()"
```

It passed across both demo pages and a synthetic tall page; detection, panel,
and bubble-segmenter captures were ready, with four tall-window merges.

The existing A2 inpaint test was run on a temporary copy of `demo_chapter`,
with its output evidence and recent-chapter setting redirected to that same
temporary directory:

```powershell
python -c "import sys,tempfile,shutil; from pathlib import Path; sys.path.insert(0,str(Path.cwd()/'tools/translation_studio')); import pipeline,selftest_inpaint as t; root=Path(tempfile.mkdtemp(prefix='t937-a4-a2-')); t.DEMO_CHAPTER=root/'demo_chapter'; shutil.copytree(Path.cwd()/'tools/translation_studio/demo_chapter',t.DEMO_CHAPTER); t.EVIDENCE=root/'evidence'; pipeline.RECENT_FILE=root/'recent.json'; t.main()"
```

It passed both Android presets on both pages and all seven route-matrix probes.
The local Paddle detector asset failed to load with ONNX Runtime
`INVALID_PROTOBUF`, so the test exercised its documented unrefined fallback;
this run does not verify the real Paddle refinement path.

The independent review identified three issues in the first A4 implementation:
mask union scope, segmentation-setting cache invalidation, and stale translated
regions after raising confidence. All three were fixed and independently
verified in the [follow-up review](REVIEW.md). The focused selftest now covers
each case and the disabled segmentation overlay behavior.

`git status` remained limited to A4 source, test, report, and evidence changes;
no hydrated model files were staged.

## Compatibility choices and deviations

- Current `capture_version: 1` consumers use only saved A1 references. If a
  reference or its component data is missing, provenance marks the capture
  degraded instead of assigning another mask.
- Legacy detection schemas keep the prior segmentation adapter. The legacy
  engine remains a comparison path; current Android A4 consumes RLEs directly.
- Confidence was added to the inpaint cache key because A4 makes it an
  execution input. General page/OCR/content fingerprinting remains A5 work.
- No requested behavior was changed outside A4; Android sources, UI filters,
  and A5 fingerprints were not edited.

## Remaining risk

- The real Paddle refinement route was not re-exercised in this worktree
  because the local Paddle detector ONNX file is not parseable. A3's
  previously reviewed synthetic Paddle probe remains the available evidence.
- The tall-page A4 test uses a synthetic page-space window RLE; full real-model
  Studio processing of a tall manga page remains unverified.
- Upstream content-based cache invalidation remains outstanding for A5.
