# Manga Translation Quality — Investigation & Diagnostic Proposal

> Investigation of how auto-translation works end-to-end (retrieval →
> preparation → output) and why quality is poor "even with AI."
>
> Companion to `Plan/translation_issue.md` (which covers scheduling/lifecycle).
> This document covers the **content-quality** path: detection → OCR →
> translation → render, and proposes an opt-in diagnostic to confirm the
> dominant root cause on-device before committing to fixes.
>
> Investigated on branch `feat/reader-translation-flexibility` (June 2026).

---

## TL;DR

Translation quality is poor **not because the AI is weak, but because the AI is
fed bad or mismatched data.** The pipeline has several compounding defects:

1. **Default source language is Chinese, not Japanese** — if the user never
   changes the setting, every Japanese manga is OCR'd with a Chinese model and
   the AI is told "translate from Chinese." This alone produces garbage.
2. **The four AI providers use inconsistent prompts** — only DeepSeek's is good;
   Gemini/OpenRouter omit the source language entirely and use a generic prompt.
3. **There is no OCR quality gate** — once a detection passes detector
   confidence `0.45`, the OCR text (even gibberish like `RSORE`, `3duds`) is
   shipped to the translator unchanged.
4. **Partial failures are silent** — when a block is dropped or misaligned, it
   silently keeps its raw untranslated source text while `translationStatus`
   reports `READY`. A page can look "translated" but contain OCR Japanese.

The proposed next step is **measurement, not fixing**: add three opt-in log
lines that pinpoint which cause is firing for your content, then fix with
evidence.

---

## Part 1 — How Translation Works (End to End)

### 1.1 The 5-stage per-page pipeline

Triggered when you open a page (auto-translate) or run "translate chapter." All
heavy work runs **one page at a time** behind a single permit (one set of ONNX
tensors + one HTTP call alive at a time). The wiring lives in
`TranslationPipeline.translateSinglePageInternal()`.

```
decode → detect → OCR (per crop) → inpaint → translate → render → persist
```

| Stage | Component | Role |
|-------|-----------|------|
| Decode | `TranslationPipeline.decodePageBitmap` | Page bytes → `Bitmap` (downsampled for memory). |
| Detect | `OnnxPageTextDetector` | RT-DETR 640×640 model, 3 classes (`bubble`/`text_bubble`/`text_free`) → text bounding boxes. Gate: detector confidence ≥ `0.45`. |
| OCR | `RoiPageRecognitionEngine.analyze` | Crops each detected box **tightly** and OCRs each crop independently → `TranslationBlock.text`. **One block = one detected box's OCR output.** |
| Inpaint | `SmartBubbleTextCleaner` + `AOTInpainting` | Erase the original text from the bubble. |
| Translate | `TextTranslator` (selected engine) | Mutates each `block.translation` in place. |
| Render | `PageTextRenderer` + `RenderColorEstimator` | Draw translated text back onto the cleaned bitmap; color picked per block. |
| Persist | `ChapterTranslationStore` | `PageTranslation` JSON + `<page>.cleaned.png` + `<page>.rendered.webp`, per chapter. |

### 1.2 Data retrieval → preparation → output

- **Retrieval:** page comes from the reader's page loader (HTTP / download /
  archive). Bytes are captured into a stream registry and decoded.
- **Preparation for translation:** the translator receives a
  `MutableMap<String, PageTranslation>` — keys are page filenames, values carry
  `blocks: List<TranslationBlock>`. Each block holds the OCR'd `text`, the
  `translation` (initially empty), bbox, and (later) color/stroke.
- **Output:** the translator mutates each `block.translation` in place; the
  renderer paints it; JSON + companion images are written to the chapter store.

### 1.3 The `TranslationBlock` shape (`model/PageTranslation.kt`)

```kotlin
data class TranslationBlock(
    var text: String,            // OCR'd original (translator input)
    var translation: String,     // translated (translator output; rendered if non-blank)
    var width/hight/x/y: Float,  // text bbox (px, decoded-sample space)
    val score: Float,            // detector confidence (NOT OCR confidence)
    val direction: String,       // "LTR" or "TTB" (vertical CJK)
    var textColor/strokeColor…,  // re-derived post-inpaint (RenderColorEstimator)
    …
)
```

### 1.4 Engine & translator resolution

Both stages share **one** source-language preference (`translateFromLanguage`):

```
fromLang = TextRecognizerLanguage.fromPref(translateFromLanguage())   // OCR + translator
ocrModel = OcrModelCatalog.selectedModel(prefs, fromLang)
textTranslator = TranslationEngineBuilder.build(prefs, fromLang, toLang)
```

The translator provider is selected from `translationEngineCategory`
(`STANDARD` → ML Kit/Google, `AI_MODEL` → Gemini/OpenRouter/DeepSeek/LM Studio).
AI translators capture model/temperature/max-tokens at construction and never
re-read them; the pipeline rebuilds the instance when a signature changes.

---

## Part 2 — Why Quality Is Poor (Root Causes, Ranked)

### 🔴 R1. Default source language is Chinese, not Japanese

**File:** `app/.../translation/ocr/TextRecognizerLanguage.kt:13-23`

```kotlin
fun fromPref(pref: Preference<String>): TextRecognizerLanguage {
    val name = pref.get()
    var lang = entries.firstOrNull { it.name.equals(name, true) }
    if (lang == null) {
        pref.set(CHINESE.name)   // ← DEFAULT IS CHINESE
        return CHINESE
    }
    return lang
}
```

If the user never touches the source-language setting, **every Japanese manga is
treated as Chinese.** Consequences:
- OCR model resolves via `defaultFor(CHINESE) = MLKIT` (not MangaOCR).
- The AI is told `"Translate ... from Chinese (trad/sim) ..."`.

This single defect corrupts both OCR and the translator instruction. It is the
most likely explanation for "translation is bad even with AI" on Japanese
content.

### 🔴 R2. Inconsistent AI prompts — only DeepSeek's is good

There is **no shared prompt.** Each adapter hardcodes its own. Verified
verbatim:

| Feature | Gemini | OpenRouter | DeepSeek | LM Studio |
|---|---|---|---|---|
| Source lang sent to model? | **No** | **No** | Yes | Yes |
| Reading-order (RTL/TTB) guidance | No | No | **Yes** | Brief |
| Honorifics guidance | No | No | **Yes (detailed)** | No |
| SFX / onomatopoeia guidance | No | No | **Yes** | Vague |
| Bubble-size / conciseness | No | No | **Yes** | Brief |
| OCR-artifact instruction | No | No | **Yes** | No |
| Watermark→`RTMTH` instructed | Yes | Yes | **No** | Yes |
| `OcrArtifactSanitizer` applied to output | No | No | **Yes** | No |

- **Gemini & OpenRouter** (`GeminiTranslator.kt:44-82`, `OpenRouterTranslator.kt:51-86`):
  generic "translate scanned comics" prompt with **no source language**, no
  manga-specific guidance. The model must auto-detect language from raw OCR text.
- **DeepSeek** (`DeepSeekTranslator.kt:58-71`): the richest prompt (reading
  order, honorifics, conciseness, SFX, OCR-artifact handling). Its SFX examples
  are hardcoded English even for non-English targets, and it's missing the
  `RTMTH` watermark instruction.
- **LM Studio** (`LmStudioTranslator.kt:60-72`): a *truncated* copy of
  DeepSeek's prompt — drops honorifics and OCR guidance, and does not apply
  `OcrArtifactSanitizer` either.

**Effect:** switching provider silently changes translation quality and
behavior. Gemini/OpenRouter are materially weaker than DeepSeek for the same
content. Picking Gemini or OpenRouter would explain "weak/inconsistent prompts."

### 🔴 R3. No OCR quality gate before translation ("garbage in, garbage out")

Once a detection passes detector confidence `0.45`, the OCR text is accepted
**no matter how nonsensical** and shipped to the translator:

- **No minimum-length filter** on the ONNX/MangaOCR path — a 1-char block or
  ASCII noise (`RSORE`, `3duds`) passes straight through. (Only the ML Kit
  fallback filters `length > 1`.)
- **No OCR confidence score** carried forward — `Detection.score` is the
  *detector's* score, not the OCR engine's. `PaddleOcrV6SmallEngine.recognize`
  returns raw CTC decode with **zero postprocessing.**
- **No language-of-text verification** — if the user picks PaddleOCR for
  Japanese, a horizontal-CTC Chinese model runs on vertical Japanese text →
  Latin/ASCII garbage that then gets "translated."
- The DeepSeek prompt even acknowledges this as a contract: *"The source text
  comes from OCR and may contain misreads … simply omit them."* The model is
  being asked to repair noise that should be filtered upstream.

This is the classic "bad OCR feeding the AI" path.

### 🟠 R4. Silent partial failures mask the problem

When the model returns fewer/more translations than blocks:

- **DeepSeek/LM Studio** (`DeepSeekTranslator.kt:119-124`): the affected block
  silently keeps its **raw, untranslated source text**, `translationStatus`
  stays `READY`, and the page renders looking "translated" but containing OCR
  Japanese. **No user signal.**
- **Gemini** (`GeminiTranslator.kt:108-119`): logs the count mismatch, then
  falls back to source text.
- **OpenRouter** (`OpenRouterTranslator.kt:107-126`): silently falls back,
  **no log at all.**
- **Google** (`GoogleTranslator.kt`): returns empty string `""` on
  rate-limit/bot-detection → blank bubble, no error.

The `NumberedLineResponseParser` fallback to *positional* line assignment
(`NumberedLineResponseParser.kt:36-43`) shifts every subsequent block's
translation by one when a model wraps output across lines.

### 🟠 R5. Whole-chapter batching with no chunking

All four AI translators send **every page, every block in a single request.**
For a large chapter this can blow past the default 8192 max-output-tokens →
truncated JSON → whole-batch parse failure. No per-page isolation, no retry, no
cross-page glossary/character-name context.

### 🟡 R6. Reading order not normalized

Blocks are sent to the LLM in detector-emission order, while DeepSeek's prompt
asserts RTL/TTB flow. The two can disagree, hurting narrative coherence.
(Alignment back to blocks is positional-by-index, so assignment isn't corrupted,
but quality suffers.)

### 🟡 R7. Vertical-text OCR is fragile

Column splitting only triggers at `boxHeight > boxWidth × 1.5` and only for
`prefersHorizontalText` engines (PaddleOCR). MangaOcr (the JP default) takes
vertical crops whole. PaddleOCR's documented vertical-text failure
(`docs/ocr-engine-notes.md`) means misrouted Japanese → garbage OCR.

### 🟡 R8. `PageTranslationHelper.mergeOverlap` is dead code

Overlapping detections are **deduped (one dropped by score)**, never merged.
Two bubbles whose boxes overlap lose text rather than concatenate.

---

## Part 3 — Proposed Diagnostic (Confirm the Cause On-Device)

**Goal: measurement, not fixing.** Add three opt-in log lines so one logcat
session shows exactly which root cause is firing, then fix with evidence.

### 3.1 Prerequisites to observe ANY diagnostic

Logging is a process-wide no-op unless **both** prefs are ON (verified at
`App.kt:160-162`):

1. `Settings → Advanced → Verbose logging` (`verbose_logging`) — master switch;
   **requires app restart** (installs `LogcatLogger` only in `App.onCreate`).
2. `Settings → (Translation) → Diagnostics` (`translation_diagnostics`). On the
   dev device this is already `true` in `prefs-device.xml`, but the code default
   is `false` (`domain/.../TranslationPreferences.kt:177`).

### 3.2 Existing diagnostics already in place (no work needed)

- `[ocr_block] box=... size=... rotated=... text="..."` — per-block OCR text
  (the translator's input), `RoiPageRecognitionEngine.kt:225`.
- `[paddle_ocr]` / MangaOcr equivalent — per-engine timing + recognized text.
- `TachiyomiAT translate step START/DONE: ... translator=... blocks=N
  translated=X/N` — unconditional; confirms the step ran.
- `TachiyomiAT translate INPUT [idx] text="..."` and `OUTPUT [idx] "ocrText" ->
  "translatedText"` — per-block I/O, gated on `translation_diagnostics`.

### 3.3 The three gaps to fill

All additions follow the existing idiom **exactly**:
`if (<gate>()) { logcat(LogPriority.INFO) { "..." } }`, bracketed tags, cached
Injekt diagnostic read. No new helper class, no new preference, no new
dependency.

#### Addition 1 — `[engine_resolved]`: what actually runs

**File:** `app/.../translation/TranslationPipeline.kt`, inside
`createRecognitionEngine` (lines 290–312), after the engine is chosen.
**Gate:** `translationPreferences.translationDiagnostics().get()`.

```
[engine_resolved] lang=JAPANESE ocrModel=MANGAOCR engine=RoiPageRecognitionEngine autoFallbackToFast=false
[engine_resolved] lang=JAPANESE ocrModel=MANGAOCR engine=MlKitFullPageRecognitionEngine autoFallbackToFast=true reason=onnx_unavailable
```

**Why:** The single line that confirms R1 (Japanese treated as Chinese /
MangaOCR silently falling back to ML Kit). Reading `lang=CHINESE` for a Japanese
manga is a smoking gun.

#### Addition 2 — `[translator_config]`: provider + from/to + model

**File:** `app/.../translation/translator/AiTranslatorKind.kt`, in `build()`
after line 28 (after `temperature` is read), before the `require(...)` validation.
**Gate:** add a companion-object `isDiagnosticsEnabled()` helper copied verbatim
from `PaddleOcrV6SmallEngine.kt:200-214`.

```
[translator_config] provider=DeepSeek fromLang=Japanese toLang=English model=deepseek-chat temp=0.3 maxTokens=8192
```

**Why:** Confirms which prompt family ran (`provider=Gemini` → weak generic
prompt; `provider=DeepSeek` → rich prompt) and that `fromLang` matches the OCR
engine. A mismatch between this `fromLang` and `[engine_resolved] lang=` proves
a wiring bug.

#### Addition 3 — `[translate_fallback]`: flag silent untranslated blocks

**File:** `app/.../translation/TranslationPipeline.kt`, inside the existing
OUTPUT `forEachIndexed` (lines 834–840). **Gate:** reuse the existing `transDiag`
local (line 807).

```
[translate_fallback] [idx] text="ocrText" -> kept untranslated (provider returned nothing/mismatched)
```

**Detection rule (conservative, no false positives):**
`b.translation.isNullOrBlank() || b.translation == b.text`.

**Why:** The most important line for "am I seeing translations or raw Japanese?"
The existing OUTPUT log shows the text but doesn't *flag* a fallback, so a page
full of `[idx] "日本語" -> "日本語"` looks like success at a glance. This tag
makes silent failures grep-able.

### 3.4 Deliberately deferred

A 4th item — an ASCII-noise detector on OCR output to flag garbage like
`RSORE`/`3duds` *before* it reaches the translator — is intentionally left out.
The existing `[ocr_block]` line already shows the raw OCR text, so garbage is
visible in logs today without new logic. A heuristic noise-classifier risks
false positives and is better deferred until after we confirm whether OCR or the
prompt is the dominant cause.

### 3.5 How to read the output

After enabling both prefs and restarting, open a page and grep logcat:

```
adb logcat --pid=$(adb shell pidof app.kanade.tachiyomi.at.debug) | grep -E "engine_resolved|translator_config|translate_fallback|ocr_block|translate INPUT|translate OUTPUT"
```

Verdict table:

| What you see | Dominant root cause | Fix direction |
|---|---|---|
| `[engine_resolved] lang=CHINESE` for Japanese manga | **R1 — default source language** | One line in `TextRecognizerLanguage.kt` (or auto-detect from manga source language). |
| `[translator_config] provider=Gemini\|OpenRouter` + OUTPUT reads wrong, `[translate_fallback]` rare | **R2 — weak prompt** | Unify to the DeepSeek prompt; send `fromLang` to all providers. |
| `[ocr_block] text=` shows garbage (`RSORE`, `3duds`, Latin noise on CJK) + frequent `[translate_fallback]` | **R3 — bad OCR feeding the AI** | Add OCR quality gate; route JP to MangaOCR; filter ASCII noise. |
| Frequent `[translate_fallback]` with a good provider and clean OCR | **R4 — numbered-line misalignment** | Switch DeepSeek/LM Studio to a JSON wire format; surface per-block errors. |

---

## Part 4 — Fix Candidates (After Diagnosis Confirms Cause)

Not to be implemented until the diagnostic above identifies the dominant cause.
Listed so the path forward is visible.

1. **R1 (highest leverage):** default `fromLang` to `JAPANESE`, or auto-resolve
   from the manga's source-language metadata. One-line change with large impact.
2. **R2:** extract a single canonical manga-translation prompt (DeepSeek's,
   parameterized by target language), shared across all four providers. Always
   send `fromLang.label`. Apply `OcrArtifactSanitizer` uniformly as a shared
   post-step. Add `RTMTH` instruction to DeepSeek (or make watermark removal
   pattern-based, not token-based).
3. **R3:** add an OCR quality gate — minimum length, ASCII-on-CJK noise filter,
   and route Japanese to MangaOCR unconditionally unless the user overrides.
   Carry an OCR confidence where the engine provides one.
4. **R4:** switch DeepSeek/LM Studio to a JSON-array wire format (mirroring
   Gemini/OpenRouter) and drop the fragile `[n] text` regex parser; surface a
   "N of M blocks untranslated" signal to the user instead of silent fallback.
5. **R5:** chunk the batch by page (or N blocks) with per-chunk retry.
6. **R6:** sort `page.blocks` into manga reading order (RTL/TTB) before sending.
7. **R7/R8:** tune vertical-column split thresholds; wire
   `PageTranslationHelper.mergeOverlap` so overlapping bubbles merge instead of
   silently dropping text.

---

## Appendix — Key File Locations

All paths relative to repo root.

| Concern | File |
|---|---|
| Per-page pipeline wiring | `app/.../translation/TranslationPipeline.kt` (`translateSinglePageInternal`, lines 533–989) |
| OCR per-block loop | `app/.../translation/recognition/RoiPageRecognitionEngine.kt` (`analyze`) |
| OCR engines | `app/.../translation/ocr/{MangaOcrEngine,PaddleOcrV6SmallEngine,MlKitRoiOcrEngine}.kt` |
| Default source language (R1) | `app/.../translation/ocr/TextRecognizerLanguage.kt:13-23` |
| AI translator prompts (R2) | `app/.../translation/translator/{Gemini,DeepSeek,OpenRouter,LmStudio}Translator.kt` |
| Translator resolution | `app/.../translation/translator/{TranslationEngineBuilder,AiTranslatorKind}.kt` |
| Numbered-line parser (R4) | `app/.../translation/translator/NumberedLineResponseParser.kt` |
| OCR artifact sanitizer | `app/.../translation/translator/OcrArtifactSanitizer.kt` |
| Watermark filter | `app/.../translation/translator/TranslationBlockFilters.kt` |
| Diagnostics preference | `domain/.../translation/TranslationPreferences.kt:177` |
| Verbose-logging install | `app/.../tachiyomi/App.kt:160-162` |
| Known vertical-OCR limit | `docs/ocr-engine-notes.md` |
| Module map | `docs/TRANSLATION_MODULE.md` |
