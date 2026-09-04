# Task T922 — Translation Pipeline Speed, Reader Fluidity & Model Picker UX Roadmap

## Status
EMPIRICAL RUNTIME VALIDATION COMPLETE (OnePlus Ace 5 / Snapdragon 8 Gen 3 / Android 16).
HTP hardware execution verified: AOT-GAN runs at 110 ms median (43.46x speedup over CPU). GPU Error 6020 root cause isolated. Ready for architectural integration.

## Objective
Establish an evidence-based, code-grounded roadmap to:
1. Accelerate the end-to-end translation pipeline (Detect, Reorder, OCR, Translation, Clean, Render).
2. Diagnose and resolve Qualcomm QNN HTP (NPU) device creation failures (`QNN_DEVICE_ERROR_INVALID_CONFIG`) and introduce the Qualcomm Adreno GPU fallback path.
3. Eliminate reader scroll sluggishness and re-entry stutter by introducing pre-computed text layout persistence.
4. Overhaul the Model Picker UI with reachability testing to eliminate wasted compute on unreachable endpoints.
5. Codify the batch translation chunking and RPM governance architecture.

## Structure & Deliverables

```
Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/
├── README.md                                             # This task contract and directory index
├── ROADMAP.md                                            # Master implementation & optimization roadmap
└── engineering/
    ├── npu-gpu-hardware-acceleration.md                  # Deep dive: NPU failure diagnosis & Adreno GPU fallback
    ├── reader-sluggishness-layout-persistence.md         # Architecture: 0ms reader layout hydration & disk persistence
    ├── model-picker-reachability-ux.md                    # Architecture: Pre-flight reachability & Settings test button
    └── batch-translation-mechanics.md                    # Verification: Streaming chunk planner & 15 RPM governance
```

## Executive Document Summaries

1. **[Master Roadmap (`ROADMAP.md`)](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/ROADMAP.md)**:
   - Synthesis across all 4 tracks with prioritized phases (Quick Wins $\to$ Reader Fluidity $\to$ Hardware Acceleration).
2. **[NPU & GPU Acceleration (`engineering/npu-gpu-hardware-acceleration.md`)](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/engineering/npu-gpu-hardware-acceleration.md)**:
   - Root cause analysis of `QNN_DEVICE_ERROR_INVALID_CONFIG`: missing `useLegacyPackaging = true` causing empty `nativeLibraryDir`, missing `<uses-native-library android:name="libcdsprpc.so" />`, and unversioned context caching.
   - Specification for the Adreno GPU user-space fallback via `libQnnGpu.so`.
3. **[Reader Layout Persistence (`engineering/reader-sluggishness-layout-persistence.md`)](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/engineering/reader-sluggishness-layout-persistence.md)**:
   - Solves the 12-page in-memory LRU cache eviction and single-threaded background queue contention by persisting `BlockLayout` and `PositionedLine` data directly to page snapshots.
4. **[Model Picker & Reachability UX (`engineering/model-picker-reachability-ux.md`)](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/engineering/model-picker-reachability-ux.md)**:
   - Eliminates wasted compute (running 4s of OCR/inpaint before failing on a bad key or quota) via pre-flight gates and an interactive Settings "Test Connection" button.
5. **[Batch Translation Mechanics (`engineering/batch-translation-mechanics.md`)](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/Plan/active/2026-09-04_T922_translation-pipeline-performance-and-ux-roadmap/engineering/batch-translation-mechanics.md)**:
   - Verifies the streaming chunk planner's role in satisfying the 15 RPM limit and maintaining character dialogue consistency across pages.
