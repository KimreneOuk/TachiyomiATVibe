# Experimental Notes

Research records and investigation documents for the manga translation overlay rendering, inpainting, and segmentation pipeline. These are **investigation artifacts**, not production code or finalized specs.

> **Project rule**: *We are not baking the inpainting onto the images but instead showing the overlay on top of the image with precise masking/position tracking.*

---

## Document index

### Rendering & overlay (core)

| Document | What it covers |
|---|---|
| **[text_overlay_rendering_research.md](text_overlay_rendering_research.md)** | The main investigation: 3 core problems (positioning, collision, zoom-tracking), 6 approaches ranked against Android constraints, model I/O contract, and **§11: experimentation results** documenting the policy pivot from bbox-fence → mask-sole-limit. **Start here.** |
| **[text_overlay_rendering.md](text_overlay_rendering.md)** | Finalized architecture decisions: DOM/Canvas overlay vs baked, zoom strategy, text-bounding policy (mask-sole-limit, revised), growth algorithm. |
| **[overlay_rendering_mode.md](overlay_rendering_mode.md)** | Android implementation plan for the overlay mode (two phased: overlay first, seg-mask inpaint second). Target: 6GB RAM low-end phones. |

### Inpainting & segmentation

| Document | What it covers |
|---|---|
| **[inpainting_optimizations.md](inpainting_optimizations.md)** | Bubble inpainting (interior median solid fill), free-text inpainting (push-pull fast mode), mask generation, pipeline priority/conflict resolution. |
| **[inpaint_testing.md](inpaint_testing.md)** | Native bitmap "layout first, then inpaint" architectural plan with Python prototype rationale. |

### Lab guide

| Document | What it covers |
|---|---|
| **[MANGA_RENDER_LAB.md](MANGA_RENDER_LAB.md)** | The Manga Render Lab (`overlay_lab/`) — Python FastAPI + Canvas sandbox for investigating rendering/inpainting/segmentation/overlay outside the Android app. |

---

## Current state of the investigation (2026-07-10)

**Text-bounding policy**: segmentation mask bbox is the **sole strict limit**. Text + stroke outline must stay strictly inside the mask. Connected bubbles that fuse into one mask polygon are separated by partitioning the shared mask at child-box-center midpoints (fused-mask split).

**Validated in lab**: 117 tests pass. Visual quality is functional but not final — further iteration expected.

**Not yet ported to Android**: all implementation is in `companion_server/render/layout_planner.py` (Python port). The Android codebase (`app/src/`) is untouched.

## Reading order for new context

1. `MANGA_RENDER_LAB.md` — what the lab is and its core rule.
2. `text_overlay_rendering_research.md` — the full investigation + §11 results.
3. `text_overlay_rendering.md` — the locked decisions.
4. `overlay_rendering_mode.md` — the Android port plan (when ready).
