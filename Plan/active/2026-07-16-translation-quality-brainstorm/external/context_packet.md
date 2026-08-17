# External Brainstorming Packet — Translation Quality (post-2-pass)

> **Purpose:** self-contained context for an external reviewer (LLM/human) to
> brainstorm **remaining** translation-quality and logic improvements. The
> 2-pass ID-mapped pipeline described in the *old* `brainstorm_context.md` is
> already **implemented and shipped**; this packet reflects the live code as of
> 2026-07-16 and focuses on what is still weak or missing.
>
> Authority: live code under `app/src/main/java/eu/kanade/translation/` and
> `app/src/main/java/eu/kanade/tachiyomi/ui/reader/`. All `file:line` refs are
> entry points for verification. Prompts and thresholds are quoted from source.

---

## 0. What is already solved (do NOT re-propose)

- **2-pass workflow** is live. Pass 1 (`bN|Text|[OK|FLAG]`) produces a fast
  draft; Pass 2 revises only `[FLAG]` blocks with chapter context.
- **ID-mapped pipe format** replaced JSON; no positional fallback anywhere.
  `ContextualResponseParser` rejects malformed/unknown/duplicate/blank IDs.
- **Strict no-fallback**: blank/source-equal translations never render as the
  source text; `PageTextRenderer`-equivalent (`TranslationOverlayView`) hardcodes
  `renderSourceText=false`.
- **State ownership & race safety**: `ChapterTranslationStore` is sole owner;
  atomic patches gated by generation + page-version + block-fingerprint;
  `NativeRunQuarantine` for timed-out ONNX calls.
- **Memory**: direct pooled ONNX buffers, identity-tracked pools, ORT arena off,
  heap-pressure decode gating, `reclaimPooledMemory` per page.
- **Pass-2 merge safety**: `RevisionMerger` re-checks draft/fingerprint/
  `userEditedAt` before applying; stale/edit/missing retain the draft + flag.

---

## 1. System summary (as implemented)

Kotlin Android manga/manhwa/manhua reader. Per-chapter pipeline, all on-device
except the LLM HTTP call:

```
detect (detector-v4 ONNX) ─┐
  + optional bubble seg     ├──► recognize (OCR) ──► TranslationBlocks
  + optional panel det      │           │
                            │      PageInpaintingPlanner.computeMask  (captured HERE, persisted)
                            │           │
                            ▼           ▼
                    inpaint (erase source text) ──► cleaned .jpg
                            │
                            ├── (REMOTE_IO) offer to translate channel BEFORE inpaint finishes
                            ▼
              Pass 1: contextual LLM batch (bN|Text|[STATUS])  ── barrier ──►
              Pass 2: revision chunks (≤20 flagged targets, glossary + nearby ctx)
                            │
                            ▼
              render overlay (TextLayoutPlanner + TranslationOverlayView) ──► translated page
```

- **Target devices:** ≥6 GB RAM; 6 GB / 20-30% heap is the design point.
- **Branch:** `fix-translation-pipeline`.

---

## 2. The four subsystems (where quality lives or leaks)

### 2.1 OCR / recognition

- **Engines:** `MangaOcrEngine` (JP, encoder + autoregressive decoder ONNX,
  native-vertical), `PaddleOcrV6SmallEngine` (CNN+CTC rec, horizontal, ZH/EN),
  ML Kit (fallback). Selection: `OcrModelCatalog.defaultFor` → JP=MANGAOCR,
  else MLKIT; PADDLE_V6_SMALL offered for ZH/EN. Facade: `TextRecognizer`.
- **Detection:** `OnnxPageTextDetector` (detector-v4, RT-DETR style). Labels:
  `0=bubble, 1=text_bubble, 2=text_free`. Conf floor **0.45**; dedupe IoU 0.75.
  Optional YOLO11 bubble segmenter + YOLO26-nano panel detector.
- **Free vs parented:** center-containment + overlap ≥0.20 → parented bubble;
  else free-text, categorised by `PanelAssignment` (OWNED/SPANNING/FREE_FLOATING…).
- **Routing:** `RoiOcrEngine.prefersHorizontalText`. MangaOcr (false) gets the ROI
  crop only — one whole read. Paddle (true) gets det-split + per-glyph rotate for
  vertical CJK.
- **Filtering:** `OcrTextFilter.isUsable` requires a real CJK char for CJK sources
  (drops `N0`/`N°` Latin misreads). `OcrArtifactSanitizer` strips `№/Ｎ０`.
  `OCR_MIN_CONFIDENCE=0.5f` but **MangaOcr returns constant conf 1.0 → exempt**.

**Quality gaps to brainstorm:**
- **G-OCR-1.** MangaOcr emits no real confidence; a garbled read is never filtered
  by score and flows into translation as garbage source. Should we synthesize /
  request a confidence, or add a post-OCR plausibility gate (char-level entropy,
  dictionary hit, repeat-collapse detector like the `viletetete…` failure mode)?
- **G-OCR-2.** Vertical CJK on Paddle is fragile: CTC head is horizontal-line,
  needs per-glyph ink-gap split + 90° rotate; whole-column reads are documented
  "garbage". Heuristic fallback degrades further. Is per-glyph rotate the right
  answer, or should vertical CJK route to a vertical-native engine?
- **G-OCR-3.** **No furigana / ruby handling.** Furigana merges into the parent
  line and corrupts it. Same for handwritten text and stylized SFX.
- **G-OCR-4.** **Reading-order vs block order.** OCR blocks are roughly spatially
  ordered; the LLM is told to "use narrative judgment" to connect bubbles. There
  is no structural panel/reading-path inference feeding the translator. For dense
  layouts this causes wrong speaker/pronoun attribution.
- **G-OCR-5.** MangaOcr decoder hard ceiling **pos<128** (position-embedding
  table); long bubbles are truncated. Is truncation surfaced, or silently lost?

### 2.2 Inpainting / cleaning

- **Modes:** `QUALITY` (neural AOT) vs `FAST` (classical). `PageInpaintingEngine`
  **throws** if QUALITY requested but model not initialized, unless the user opted
  into `translation_inpaint_quality_fallback` (then FAST). No silent downgrade.
- **Mask:** captured at OCR time into `PageTranslation.inpaintMaskBoxes`
  (durable), because detector-only + watermark regions leave `blocks` before
  inpaint on the resume path. `build()` prefers PERSISTED, else recomputes.
- **Two erase paths:**
  - *Parented bubbles* → `inpaintReportBubbles`: classical
    `AotReportBubbleFill` (BFS distance-to-boundary + histogram median + 12
    smoothing passes) over the YOLO seg mask or a dynamic pill mask.
  - *Free text* → `inpaintReportFreeTextNeural` (QUALITY, fixed-512 centered
    crop) or `inpaintReportFreeTextFast` (push-pull/Telea). Refined by
    **PaddleOCR-v6 det line boxes** (thresh 0.18/0.34) back-projected to page
    coords — tighter than the coarse detector box.
- **Neural model:** AOT ONNX, `[1,3,512,512]` image+mask → `[1,3,512,512]`.
  Image normalized to `[-1,1]`, masked pixels zeroed. Fixed-512 + dynamic +
  strict NNAPI sessions. Skipped on heap/native budget → FAST.
- **Guard:** `AotOutputGuard` rejects uniform fills (variance<9, channelDelta<8)
  in 3 luma bands: near-black ≤24, mid-gray 96–160, near-white ≥238. On reject →
  fallback to `SmartBubbleTextCleaner.cleanRegions`.
- **Edges:** chamfer distance-field feather (`FEATHER_RAMP_PX=12`), disk dilation
  (rounds corners), `bgSourceMask` keeps background sampling inside the bubble.

**Quality gaps to brainstorm:**
- **G-INP-1.** `bgSourceMask` (color-bleed fix) is applied **only on the parented
  path**; free-text passes null. Free-text fills can still pull surrounding
  artwork color. Should free-text also constrain its background sampling?
- **G-INP-2.** Parented-bubble path median-fills **regardless of screentone
  tier** (`BoundaryAwarePipeline.classifyTier` is advisory only on that path).
  Screentone bubbles flatten to solid gray.
- **G-INP-3.** `insetPx=5` leaves a 5px un-overwritten boundary ring inside
  bubbles, hidden only by feathering. On high-contrast borders this can ghost.
- **G-INP-4.** Oversized/long masks collapse the neural crop context to ~0 →
  near-black uniform rejection. No tiling/segmentation of large regions.
- **G-INP-5.** `fillSolidBoxes` (SmartBubbleTextCleaner) appears referenced in
  comments but the FAST route uses push-pull/Telea instead — possible dead path
  or undocumented divergence.

### 2.3 Translation (Pass 1 + Pass 2, contextual batch)

- **Chunking:** `TranslationContextChunkPlanner` → `StreamingChunkPlanner`. Greedy
  flush on prompt-token budget / maxBlocks / maxPages. Real **jtokkit
  CL100K_BASE** tokenizer for estimates.
  - Budget: `MAX_CONTEXT_TOKENS=8192`, `SAFETY_MARGIN=512`, `MIN_OUTPUT_TOKENS=256`,
    `PROMPT_OVERHEAD_TOKENS=1100`, `MAX_ROLLING_CONTEXT_TOKENS=1500`,
    `MAX_ROLLING_PAIRS=32`.
  - LM Studio profile: 10k ctx, 768 rolling, 32 blocks / 4 pages per chunk.
- **Inactivity flush:** `InactivityFlusher` 250ms idle → flush incomplete chunk
  under `translateMutex` (never two concurrent provider requests).
- **Request build:** `ContextualRequestBuilder`. Pass-1 id `b<seq>`, line `id|text`;
  Pass-2 id `p<pageIndex>_b<seq>`, line `id|Source: … | Draft: …`. Snapshots
  `TargetPrecondition` (draft, fingerprint, needsRevision, userEditedAt) per id.
- **Context prefix:** `TranslationPrompts.contextPrefix` = glossary + rolling
  recent pairs; omitted entirely when both blank (no framing noise on chunk 1).
- **System prompts:** `TranslationPrompts.pass1SystemPrompt` / `pass2SystemPrompt`
  (single source of truth, all 4 AI providers). Pass-1 prompt is detailed (~POV,
  pro-drop inference, deictics, reading order, honorifics, SFX) with 5 few-shots.
- **Parse:** `ContextualResponseParser` → `TranslationPrompts.parseLine` (regex
  `\b(p\d+_b\d+|b\d+)[^\w]*(.*)`). Malformed/unknown/duplicate/blank → REJECTED,
  no positional fallback. Tagless Pass-1 line → auto-FLAG.
- **Apply:** Pass-1 via legacy in-place `translateContextual`; strict Pass-2 via
  `RevisionCommitter.commit` → `ChapterTranslationStore.patchBlock` with
  `PatchPrecondition(generation, pageVersion)` + expected fingerprint/draft/
  userEditedAt. Mismatch → `Rejected`, draft+flag retained.
- **Glossary (two layers):** `ChapterGlossaryBuilder` (deterministic — recurring
  CJK-ideograph proper nouns ≥3× with ≥0.8 capitalized-Latin recall, ≤30 entries)
  + `GlossaryExtractor` (one-shot AI extraction on chunk 1). Concatenated.
  Persisted debounced 250ms to `.glossary.json`.
- **Rolling context:** sliding window of last 32 `source=>target` pairs, dropped
  first glossary then entirely if it blows the 1500-token budget.
- **Retry:** `TranslationRetry` 3 attempts, exp backoff + jitter, transient =
  IOException / 429 / 5xx / timeout. `AiTranslationRetryPlanner` halves chunk
  size on partial failure.
- **Providers:** `OpenAiCompatibleTranslator` (OkHttp base, 60s) → DeepSeek /
  OpenRouter / LmStudio extend it; Gemini uses Google SDK. **Only Gemini
  overrides `translateContextualStructured`**; DeepSeek still uses the legacy
  in-place apply path. `TranslatorComputeClass`: REMOTE_IO (overlap native) vs
  LOCAL_COMPUTE (MLKit, serialized).

**Quality gaps to brainstorm:**
- **G-TX-1.** **No chain-of-thought.** The prompt forbids reasoning ("Output ONLY
  these lines… no explanations"). For ambiguous pro-drop dialogue this caps
  quality. Options: (a) a separate reasoning field in the line protocol
  (`bN|text|[OK]|reason`), (b) a `<think>` block the parser strips, (c) a
  per-target mini-CoT in Pass-2 only. Trade-off vs `maxOutputTokens` and parse
  robustness.
- **G-TX-2.** **Rolling context is a flat 32-pair dump.** No speaker/name memory
  structure; pronoun drift across a long chapter is the documented failure. Should
  we maintain an explicit `speaker→pronoun/name` map (LLM-extracted, stored in the
  glossary or a sidecar) instead of raw pairs? This also cuts tokens.
- **G-TX-3.** **Pass-2 context window is only 16 nearby lines + glossary.** A
  `[FLAG]` referencing a plot point 15 pages back gets no help. Chunk-of-pages
  revision (current design is target-grouped) vs a chapter-wide summary?
- **G-TX-4.** **`[FLAG]` over-flagging.** A cheap local model (LM Studio) may flag
  90% of lines → Pass-2 budget blowout. No cap on "if >X% flagged, revise the
  whole page/chapter instead". Current `RevisionPlanner` respects token budget
  but not a flag-ratio circuit-breaker.
- **G-TX-5.** **Pass-2 cannot fix a wrong `[OK]`.** It only revises flagged
  targets. A confidently-wrong Pass-1 `[OK]` is frozen. Should Pass-2 optionally
  re-screen `[OK]` lines against context (cheap classifier)?
- **G-TX-6.** **Tag collision.** If translated text literally contains `[FLAG]`/
  `[OK]` (e.g. "Raise the [FLAG]!"), `parseLine` strips/misinterprets it. Need an
  escaping rule or a status in a fixed column.
- **G-TX-7.** **Structured-result path is Gemini-only.** DeepSeek/OpenRouter/
  LmStudio still apply in-place (`translateContextual`) and bypass the strict
  `RevisionCommitter` precondition path. Inconsistency across providers.
- **G-TX-8.** **Glossary is write-once-chunk-1 + deterministic mine.** No
  feedback loop: a user correction or a Pass-2 decision does not update the
  glossary for later chunks/sessions.
- **G-TX-9.** **Prompt is monolithic & verbose** (~1100 overhead tokens, sent
  every request). Compression opportunities without losing the POV/pro-drop
  guidance? Few-shot count vs token cost.
- **G-TX-10.** **No speaker/segmentation signal from OCR to LLM.** The `[SPEECH]`
  tag was removed; the LLM gets no bubble-vs-free-text, no parent grouping, no
  panel membership. Re-adding a lightweight structural tag (bubble/free/panel-id)
  — derived from detection, not guessed — could materially help POV inference.

### 2.4 Reader / presentation / UX (logic only)

- **Display readiness** (pure, `PageTranslationState`): cleaned ready AND
  (translation READY|PARTIAL) AND render READY AND some block translated → overlay.
  Original image kept until all three hold; a translation failure never exposes a
  cleaned-only page. Textless pages → SKIPPED, terminal success, keep original.
- **`PageView.overlayContentFingerprint`** = `:`-joined block fingerprints when
  display-ready → text-only Pass-2 changes refresh the overlay without re-decoding.
- **Triggers:** per-page button (`ReaderViewModel.translateSinglePage`, resolves
  `force` from FAILED state → `prepareForcedRetry`); auto-prefetch
  (`TranslationScheduler.requestAutoWindow`, default window 2); chapter batch
  (`MangaScreenModel`, gated by `ConfirmTranslationDialog` + `TranslationSettingsSummary`).
- **Progress:** `TranslationProgressSnapshot` (phase FIRST_PASS/REVISING/
  FINALIZING/FINISHED, `activeStages` set, per-stage succeeded/failed/skipped/
  total, `RevisionProgress`). `READY_WITH_WARNINGS` = readable with partial/
  unresolved-revision; rendered like TRANSLATED.
- **State:** `ChapterTranslationStore` (`PersistentMap` + `Mutex`, `StateFlow`),
  per-chapter in `TranslationManager.activeStores`, selected by `chapterId`
  (cross-chapter isolation). Terminal snapshots in access-ordered LRU **≤20**.
- **Cancellation:** `cancelChapter` → scheduler cancel → `store.markDefunt()`
  (every mutator no-ops once defunct) → stranded-page sweep.
- **Memory pressure:** `App.onTrimMemory` → `TranslationMemoryPressureForwarder`
  → `TranslationManager.onMemoryPressure(level)`.

**Logic/UX gaps to brainstorm:**
- **G-UX-1.** **No manual block editor.** `TranslationBlock.userEditedAt` is
  declared and *consumed* (revision opt-out, edit-wins precondition) but
  **never written anywhere** — the editing UI was never shipped. So the entire
  edit-wins machinery is dormant and `RevisionProgress.userEditedBlocks` is
  always 0. Highest-leverage missing feature for quality (human-in-the-loop).
- **G-UX-2.** **No glossary UI.** The chapter glossary is mined and used
  internally but cannot be viewed/edited/corrected by the user. A glossary
  editor would let users lock name spellings (feeds G-TX-2/G-TX-8).
- **G-UX-3.** **No per-block quality signal to the user.** A `[FLAG]`/partial
  block is indistinguishable from a confident `[OK]` in the rendered overlay.
  Should low-confidence/flagged blocks get a subtle indicator + tap-to-edit
  (pairs with G-UX-1)?
- **G-UX-4.** **No prompt/context knobs in settings.** Chunk size, rolling
  pair count, glossary caps are hardcoded in `TranslationContextChunkPlanner`/
  `ContextualRequestBuilder`. Power users can't tune for their model.
- **G-UX-5.** **Pass-2 correctness is invisible at the block level.** Progress
  shows revision totals, but not *which* lines were corrected or why a revision
  was rejected (logged, not surfaced). Hard for a user to trust or debug.

---

## 3. Cross-cutting themes (where the biggest wins likely are)

1. **Human-in-the-loop (G-UX-1, G-UX-2, G-UX-3, G-TX-8).** The strict
   edit-wins + glossary infrastructure is built but has no UI. Shipping a block
   editor + glossary editor turns Pass-2 corrections and user locks into durable
   quality improvements, and gives `[FLAG]` a resolution path.
2. **Structured context > raw text (G-TX-2, G-TX-10, G-OCR-4).** Replace the
   flat 32-pair rolling dump and the removed `[SPEECH]` tag with *derived*
   structure: a speaker/pronoun memory, and bubble/free/panel tags from
   detection. This targets pronoun drift and wrong-POV — the top reported class.
3. **OCR confidence & plausibility (G-OCR-1, G-OCR-5).** MangaOcr's constant
   1.0 means garbage source reaches the LLM uncritically. A post-OCR
   plausibility gate (repeat-collapse, char-entropy, dictionary) would let the
   pipeline flag-or-skip instead of translating noise.
4. **Pass-2 scope (G-TX-3, G-TX-4, G-TX-5).** Nearby-16-lines context, no
   `[OK]` re-screen, no over-flag circuit-breaker. Rethinking Pass-2 as a
   chapter-scoped editorial pass (with a summary) rather than target-grouped
   patches could raise the ceiling.
5. **Provider parity (G-TX-7).** Migrate DeepSeek/OpenRouter/LmStudio onto
   `translateContextualStructured` so the strict precondition path is uniform.

---

## 4. Constraints (non-negotiable)

- **≥6 GB RAM** devices; 6 GB / 20-30% heap is the target. No unbounded native
  or bitmap growth; pooled direct buffers; ORT arena off.
- **Strict no-fallback** is a project invariant: never silently substitute
  source text, positional guesses, vertical layout, or default config.
- **Atomic state patches** with generation/version/fingerprint preconditions;
  late writes rejected and logged. Edit-wins over auto-revision.
- **Plain JVM unit tests** (JUnit5 + Kotest) for pure logic; Android/Bitmap/ONNX
  is device-only — the project deliberately avoids Robolectric. Keep new logic
  extractable as pure helpers (the `★ PURE` pattern).
- **No external services / no telemetry.** The only network call is the user's
  chosen LLM provider. On-device OCR/inpaint/render.
- Token/cost sensitivity: `maxOutputTokens` default 8192; prompt overhead matters
  at chapter scale.

---

## 5. Open questions for the reviewer

1. **CoT without breaking the parser or budget** (G-TX-1): best line-protocol
   extension or separate reasoning channel? How do peer manga-translators do it?
2. **Speaker/pronoun memory** (G-TX-2): LLM-extracted `speaker→{pronoun,name}`
   sidecar vs a generative chapter summary? Update cadence and storage?
3. **MangaOcr confidence substitute** (G-OCR-1): what's the cheapest
   post-hoc plausibility signal that catches the `viletetete…` repeat-collapse
   and garbage-romanji cases without a dictionary dependency?
4. **Re-adding *derived* structural tags** (G-TX-10): which subset
   (bubble/free/panel-id/speaker-turn) gives the most POV lift for the least
   prompt-token cost, given detection already produces them?
5. **Pass-2 as chapter-scope editorial pass** (G-TX-3/4/5): target-grouped
   patches vs page-chunk revision vs full-chapter summary-then-rewrite — which
   balances quality, tokens, and the existing strict-merge precondition?
6. **Manual block editor scope** (G-UX-1): minimal viable — inline tap-to-edit
   on a block, writing `userEditedAt` + corrected text, feeding the glossary?
   Or a dedicated review screen for `[FLAG]`/partial blocks?
7. **Over-flag circuit-breaker** (G-TX-4): per-page and per-chapter flag-ratio
   thresholds that trigger a wholesale re-translation vs per-target revision?

---

## 6. How to respond

Treat this packet as the source of truth for the *current* state. Propose:
- concrete protocol/prompt changes (quote the new line format / prompt section);
- data structures for any new memory/glossary/sidecar;
- where each change plugs in (cite the `file:class` entry points above);
- token-cost and RAM impact estimates;
- a pure-helper extraction so the logic stays unit-testable.

Flag anything that conflicts with the constraints in §4. Distinguish
"quality win" from "UX feature" — both are in scope, but they sequence differently.
