# Implementation Plan: Experimental Overlay Rendering Mode

**Status:** Draft — awaiting approval. Produced after investigation + dual adversarial review.
**Branch:** Quality-Improvement
**Target devices:** Low-end phones, 6 GB RAM minimum (memory is the binding constraint — this drives several decisions below).

---

## 1. Goal

Replace the current **flatten translated text into a baked `.rendered.png`** architecture with a **live text overlay drawn via `Canvas` on top of the cleaned background**, in order to:

- **Lower storage** (one fewer full-page PNG per page).
- **Lower translate-time CPU** (skip one full-page PNG q=100 encode per page).
- **Improve zoom crispness** (vector text stays sharp at high zoom vs. baked-pixel upscale).
- **Instant font / language re-render** (pure re-plan, no bitmap I/O).

> [!WARNING]
> **What this mode does NOT improve (corrected after review)**
> - **View-time memory does NOT improve.** Today the reader decodes one bitmap at view time (`.rendered.png`); `.cleaned.png` is a translate-time intermediate, not held at view time. Overlay mode decodes `.cleaned.png` (same dimensions) + cheap draw state. View memory is **neutral at best**. Do NOT claim a memory win.
> - **View-render speed is marginal/debatable.** It trades "decode 1 PNG" for "decode 1 PNG + 1 Canvas text draw."
> - **Detection / OCR / HTTP translate are unchanged** — these dominate translate wall-time.

> [!IMPORTANT]
> **Cheaper alternative to evaluate separately**
> Re-encoding `.rendered.png` as **WebP-lossless** (low effort) typically cuts storage 40–60% with **zero new machinery**. If storage is the *only* goal, that dominates every overlay approach on simplicity. Overlay earns its complexity only via **zoom crispness + instant font re-render**. WebP-reencode is **out of scope** here but should be evaluated as an independent, cheaper storage win.

---

## 2. Current architecture (verified facts)

```
detect/OCR → inpaint (erase src text) → cleaned.png (PNG q=100)
  → translate HTTP → TextLayoutPlanner.plan() (PURE) → PageTextRenderer.render() draws text via Canvas → rendered.png (PNG q=100)
```

- **Two full-page PNGs/page** stored: `.cleaned.png` + `.rendered.png` (both q=100).
- **`TextLayoutPlanner.plan()` is PURE** (no Android deps, JVM-testable), neighbour-aware, returns `List<BlockLayout>` (originX/Y, safeW/H, fontSizePx, strokeWidth, drawAlign, clipRect, isVertical). It already accepts an injected `TextMeasurer`.
- **`PageTextRenderer.render()`** = `plan()` then draw each block (stroke=luma-inverse + fill), structural `clipRect` no-overlap guarantee.
- **Reader:** `ReaderPageImageView` is a `FrameLayout` wrapping `SubsamplingScaleImageView` (SSIV). The fork **already** positions a per-page translate button via `SSIV.sourceToViewCoord()` + repositions on `onScaleChanged`/`onCenterChanged`. `computeImageRect()` and `restoreOverlayOrder()` exist. The hard alignment problem is already solved.
- **Webtoon:** `WebtoonSubsamplingImageView`, fixed scale (`SCALE_TYPE_FIT_WIDTH`), scrolled by `RecyclerView`. **No user zoom.**
- **Animated pages (GIF/WebP):** `PhotoView`/`AppCompatImageView` — **no `sourceToViewCoord`**, uses `displayRect`/`imageMatrix`.
- `RenderColorEstimator.recomputeFor(cleanedBitmap, blocks)` re-derives `textColor` against the cleaned bg after inpaint. Stroke re-derived as luma-inverse at draw time.
- `retryInpaintDownscaled` scales the cleaned bitmap back up to original decode dims because block coords are in original-scale space.

---

## 3. Design decisions (post-review synthesis)

The plan went through two adversarial reviews. Key decisions, with rationale:

### 3.1 Re-plan at view time — do NOT persist layouts
**Original draft:** persist `TextLayoutPlanner.plan()` outputs to JSON.
**Reviewer finding (Critical):** Two `Paint` instances (plan-time vs draw-time, possibly different `Context`/density) can drift on glyph advance widths → silent text truncation on certain devices, undetectable in CI. Persisted-layout also adds a serialization schema (versioning tax) and forces whole-page re-persist on any structural change.

**Decision:** Persist **inputs** (blocks are already persisted) + `textColor` + `pageWidth/pageHeight` only. **Re-run `plan()` at view time with the same `Paint` that draws** — drift becomes structurally impossible. `plan()` is pure, ~10ms on a low-end phone, cached in memory.

### 3.2 Hybrid: overlay for pager (non-animated), bake for webtoon/animated
**Reviewer findings:**
- Animated pages have no `sourceToViewCoord`, and static text over moving art is broken → overlay **impossible**, not just undesirable.
- Webtoon gets **zero zoom-crispness benefit** (fixed scale), and the redraw-on-scroll primitive is wrong (should be `setTranslationY`, not `onDraw`).

**Decision:**
- **Pager + non-animated → live Canvas overlay.**
- **Webtoon → existing bake path** (no benefit, avoid complexity).
- **Animated → existing bake path** (overlay impossible).
- `.rendered.png` generation is **NOT deleted** — it's branched on mode/viewer. This retains the safety net for the paths that need it.

### 3.3 Reject sparse-overlay-PNG approaches (both reviewers proposed them) — on the memory constraint
**Reviewer approaches:** render text once into a transparent ARGB PNG (mostly empty → DEFLATE crushes it), composite as a second image at view time.

**Why rejected:** each reviewer's own fatal weakness — view-time RAM goes up by **+8.6 MB/page** (two decoded full-page bitmaps: cleaned + overlay). On the **6 GB-RAM target**, that's the wrong trade. Live Canvas keeps view memory **neutral** (one decoded bitmap + cheap draw). The memory target that the reviewers used to attack the plan is exactly what makes live-Canvas the better pick over their PNG proposals.

### 3.4 Do NOT adopt SSIV-tile-decoder approach (R1 alternative)
**Idea:** bake text into SSIV's tile `decodeRegion()` — genuinely best on memory (text lives in the existing tile cache), zoom-crisp for free.

**Why rejected for now:** deeply invasive (long-lived fork of SSIV internals), `BitmapRegionDecoder` support varies by format (animated/WebP fall back to full-decode-then-tile — must also hook that path), webtoon needs the same patch, and animated still can't use it. **Out of scope** for this experimental mode; documented as a future hardening direction if the team can own an SSIV fork.

### 3.5 Solve jank + frame-swim
**Reviewer findings (High):** Stroke+fill = 2× glyph rasterization; SSIV fires scale/center callbacks ~per frame during a gesture → ~1500 glyph draws/frame on a 50-block page → jank + AA shimmer. Sibling-View `invalidate()` also lags the base image by one frame → text "swims" against bubble borders.

**Decision:**
- **Throttle overlay redraw to coarse scale buckets** (snap `effectiveScale` to 1.25× steps) during an active gesture; full re-rasterize only on **gesture lift** + on `isReady()`. Trades a little transient AA quality for no jank — acceptable and matches the translate button's existing discrete-granularity tradeoff.
- During a gesture, the bucketed draw is a single pass with cached `Paint`s.

### 3.6 Use one transform Matrix — never single-axis scale
**Reviewer finding (Critical under rotation):** `effectiveScale` from a single axis (`sourceToViewCoord(sWidth,0).x - sourceToViewCoord(0,0).x`) collapses to ~0 under 90°/270° rotation.

**Decision:** Build **one `Matrix`** from SSIV's `scale` + `center` + `orientation`; map both positions AND sizes through it. Never derive scale from a single-axis delta, never double-apply.

### 3.7 Failure handling (complies with project rule: no fallback, log every failure)
**Reviewer finding:** "show cleaned without text" is a silent, invisible quality regression. By dropping `.rendered.png` there's no recovery artifact.

**Decision:** On any overlay draw exception → **log at `LogPriority.ERROR`** (no silent swallow) → show cleaned (blank bubbles = a *visible*, retryable failure, compliant with the rule). Note `.rendered.png` is retained for webtoon/animated, so those paths keep the safety net. For overlay pages, the visible-failure behavior is intentional and matches the rule.

---

## 4. Edge-case answers (complete)

| Concern | Resolution |
|---|---|
| **Inpainting (fast + quality)** | **Unchanged in all paths.** `.cleaned.png` produced as today. Both FAST (PushPull/reportBubbleFill) and QUALITY (AOT-GAN free-text + classical bubbles) run identically. Overlay + bake both consume the cleaned bitmap. |
| **How we render** | Pager/non-animated → live Canvas overlay (re-planned at view, shared Paint, bucketed redraw). Webtoon/animated → existing bake. |
| **Text position tracking** | **Nothing persisted for layout.** Re-planned from already-persisted blocks at view time; result cached in memory keyed by `(blocksHash, pageDims, paintHash)`. Only `pageWidth/pageHeight` added to JSON. |
| **Overlap / collision detect** | **Unchanged** — `plan()`'s neighbour-aware placement + structural `clipRect` run at view time now. Verify `clipRect` is inflated by `strokeWidth/2` so scaled strokes can't bleed into neighbors at high zoom. |
| **Performance optimization** | Neutral view RAM (no second bitmap); one-time ~10ms plan cached per page; bucketed redraw avoids gesture jank; skip one PNG encode at translate time. |
| **Rotation** | Single transform Matrix from SSIV scale+center+orientation. |
| **Font change** | Genuinely instant — re-plan is pure, cached, no bitmap I/O. |
| **`decodeSampleSize` mismatch** | Persist `pageWidth/pageHeight`; assert `sWidth/sHeight` match at view time; refuse + log + show cleaned if they diverge. |
| **Webtoon** | Bake (no zoom benefit; scroll handled by existing path). |
| **Animated** | Bake (overlay impossible). |
| **Color estimation** | Use persisted `textColor` (sampled against the same cleaned bg that's shown — PNG q=100 lossless, so bg pixels match byte-for-byte). Do NOT re-sample at view time. |
| **Readiness race** | Gate first draw on `isReady()`; hide overlay (show cleaned) until then — mirror the translate-button lifecycle exactly. |
| **Partial re-translate** | Whole-page re-plan is automatic (plan is neighbour-aware); no stale siblings. |

---

## 5. Proposed changes

### 5.1 Data model (`translation/model`)
#### [ADD] `PersistedOverlayMeta` (new data class) — minimal: `pageWidth: Int`, `pageHeight: Int`. (No layouts persisted.)
#### [MODIFY] [PageTranslation.kt](file:///C:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/model/PageTranslation.kt)
- Add `var overlayPageWidth: Int = 0`, `var overlayPageHeight: Int = 0` (serialized). These pin the coord space; asserted against SSIV `sWidth/sHeight` at view time.
- `textColor` is already on `TranslationBlock` — reused.
#### [MODIFY] [ChapterTranslationStore.kt](file:///C:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt)
- `shouldPersistUpdate`: gate on overlay meta as a durable signal (treat non-zero `overlayPageWidth` like `hasRenderedResult`).

### 5.2 Setting (`domain` + settings UI)
#### [MODIFY] [TranslationPreferences.kt](file:///C:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt)
- Add `translationOverlayMode()` → `Boolean`, default `false` (experimental).
#### [MODIFY] [SettingsTranslationScreen.kt](file:///C:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt)
- Add toggle, labelled **"Experimental — Overlay translated text (pager only)"**. Note webtoon/animated fall back to baked.
#### [MODIFY] `TranslationSettingsSheet.kt` (reader-side sheet)
- Mirror the toggle.

### 5.3 Pipeline (`TranslationPipeline`, render stage)
#### [MODIFY] [TranslationPipeline.kt](file:///C:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt)
In `tryRender` / `translateSinglePageHttpRender` / `renderResumedPage`, when overlay mode is on **and the page is non-animated**:
- Run `RenderColorEstimator.recomputeFor(cleanedBitmap, page.blocks)` (unchanged) so `textColor` is correct.
- Set `page.overlayPageWidth` / `page.overlayPageHeight` from the cleaned bitmap.
- Set `renderStatus = READY`.
- **Skip** `PageTextRenderer.render()` + `persistRenderedBitmap()`. `.cleaned.png` still written as today.
- **No fallback:** if planning is attempted and throws (only if a future refactor runs plan here), set `renderStatus = FAILED` + log (do not silently bake a PNG).
- Webtoon/animated pages always take the existing bake path regardless of the setting.

> [!NOTE]
> Because layouts are re-planned at view time (§3.1), the pipeline does **not** call `plan()` at translate time in overlay mode. It only pins dimensions + colors.

### 5.4 Reader overlay (`ui/reader/viewer`)
#### [ADD] `TranslationOverlayView.kt` — custom `View` whose `onDraw`:
1. Builds **one** `Paint` (the drawing Paint, animeace bold) in its own `Context`.
2. Wraps it as the injected `TextMeasurer`.
3. Runs `TextLayoutPlanner.plan(blocks, pageW, pageH, measurer)` on a **background thread** (cached in memory keyed by `(blocksHash, pageDims, paintHash)`).
4. Draws returned `BlockLayout`s with stroke(luma-inverse) + fill, reusing `PageTextRenderer`'s stroke + clip logic.
5. Applies a **single transform `Matrix`** (from SSIV scale+center+orientation) for both positions and sizes.
6. **Bucketed redraw** during gesture (snap `effectiveScale` to 1.25× steps); full re-rasterize on lift + on `isReady()`.
7. **Exception handling:** `try/catch` around draw → `logcat(LogPriority.ERROR)` → no text drawn (cleaned shows). Compliant with the project's no-silent-failure rule.
#### [MODIFY] [ReaderPageImageView.kt](file:///C:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt)
- Add the overlay child (same slot as translate button / processing scrim).
- Register it in `restoreOverlayOrder()` (z-order gotcha: `setImage` re-adds `pageView` last and buries overlays).
- Drive `overlay.relayout()` from the existing `onScaleChanged`/`onCenterChanged`/`onImageLoaded` hooks (same place `relayoutTranslateButton()` runs).
- Expose `sourceToViewCoord` / `displayRect` access for the overlay via the existing `computeImageRect`-style helpers.
- **Pager only** — do not instantiate for webtoon (`isWebtoon`) or animated `pageView`.

### 5.5 Reader wiring (`ReaderViewModel`, holders)
#### [MODIFY] [ReaderViewModel.kt](file:///C:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt)
- `attachTranslatedStream`: in overlay mode, set `translatedStream` to the **cleaned** image stream (primary).
#### [MODIFY] [PagerPageHolder.kt](file:///C:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt)
- `setImage`: feed blocks + overlay meta to the overlay after the cleaned image is ready (reuse `onImageLoaded`/`lastShownRenderRevision` dedup so we don't redraw needlessly).
- Skip overlay instantiation for animated pages (the `PhotoView` path).
- **No changes to `WebtoonPageHolder.kt`** (webtoon stays baked).

### 5.6 Tests (TDD per AGENT.md)
#### [ADD] JVM unit tests (pure):
- Overlay-mode pipeline branch: given overlay pref ON + non-animated, assert `rendered.png` is **not** written, `overlayPageWidth/Height` are set, `renderStatus = READY`.
- Overlay-mode pipeline branch: webtoon/animated → still writes `rendered.png` (bake path).
- `shouldPersistUpdate` gates on overlay meta.
#### [ADD] JVM unit tests (layout parity, guards against drift):
- `plan()` output geometry is deterministic for a fixed `(blocks, pageDims, measurer)` — assert the in-memory plan used at view time matches the geometry the bake path would draw for the same inputs. (Since plan+draw now share one Paint, this is the drift guard.)
- `effectiveScale` bucketing helper (pure): assert 1.25× snapping, boundary behavior.
#### Android-side overlay alignment: NOT unit-testable without Robolectric (project avoids it) — validate manually (§6).

---

## 6. Validation

- **Build:** `./gradlew :app:assembleDebug` (or project's flavor).
- **Run new JVM tests** (§5.6).
- **Manual** (translate a chapter with overlay ON):
  1. Pager, fit-to-screen: text aligns over bubbles, colors correct.
  2. Pinch-zoom: text stays crisp + aligned; no jank/shimmer on low-end device.
  3. Pan: text tracks the artwork (acceptable discrete granularity, like the translate button).
  4. Rotation: text does not vanish (single-Matrix fix).
  5. Webtoon: still shows baked text (no regression).
  6. Animated page: still shows baked text.
  7. Toggle OFF → existing `.rendered.png` chapters still show.
  8. Flip font → overlay re-renders instantly (no bitmap I/O).
  9. Compare storage: overlay chapter = one PNG/page; non-overlay = two.
- Report any validation not performed.

---

## 7. Scope / defaults

- Overlay applies **forward + on re-render** only; existing `.rendered.png` chapters keep working (no migration).
- **Pager + non-animated only.** Webtoon + animated stay baked (hybrid).
- Drops `.rendered.png` **only** in overlay mode (pager, non-animated). `.cleaned.png` retained.
- Default **OFF** (experimental).

---

## 8. Risks / follow-up

- **Main risk:** overlay misalignment on some device/zoom. Mitigated by reusing the proven `sourceToViewCoord` path + single-Matrix transform; needs careful manual validation (§6).
- **SSIV discrete notification granularity:** scale/center fire on discrete changes, not continuous per-frame. Acceptable (same tradeoff as the translate button); bucketed redraw further reduces cost.
- **`clipRect` stroke-half inflation:** must verify `plan()` inflates `clipRect` by `strokeWidth/2`, else scaled strokes bleed into neighbors at high zoom. Verify in implementation.
- **Future hardening (out of scope):** SSIV tile-decoder approach (§3.4) would give a true view-memory win if the team can own an SSIV fork; sparse-overlay-PNG approaches are viable if the memory target is ever relaxed.
- **Orthogonal storage win:** evaluate WebP-lossless re-encode of `.rendered.png` separately (§1).

---

## Appendix A — Approaches considered and rejected (with reasons)

| Approach | Storage | View RAM | View CPU/jank | Correctness | Verdict |
|---|---|---|---|---|---|
| **A. Persist layout + live Canvas** (original draft) | ~50% | neutral | per-frame stroke jank, swim | Paint-drift (Critical) | **Rejected** — drift + jank |
| **B. Sparse transparent overlay PNG + blit** (both reviewers) | best | **+8.6 MB** (2 bitmaps) | one blit | baked pixels, no drift | **Rejected** — doubles decode RAM on a 6 GB target |
| **C. SSIV tile decoder (bake text into tiles)** (R1) | best | **best** | bg-thread, cached | ✓ | **Deferred** — deep SSIV fork; revisit if team owns fork |
| **D. Re-plan at view + live Canvas + hybrid bake** (synthesis) | ~50% | neutral | bucketed redraw | no drift (shared Paint) | **Chosen** — best fit for the memory constraint + goals |
