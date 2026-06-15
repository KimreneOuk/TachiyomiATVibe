# OCR Routing And Package Refactor Plan

## Goal

Make the translation pipeline match the intended architecture:

```text
fetch image
-> memory-aware decode/downscale
-> detect text/bubble ROIs
-> crop ROI
-> OCR backend selected by source language
-> unified translation engine
-> inpaint using detector boxes
-> render translated text
```

The important design rule is that detection and OCR are separate responsibilities.

```text
Detection decides where text is.
OCR decides what text says.
Translation decides what text becomes.
Inpainting removes original text from detected regions.
Rendering draws translated text back into those regions.
```

## Intended Runtime Behavior

### Detection / ROI

All source languages should use the ONNX detector for page-level text/bubble region detection.

```text
Japanese -> ONNX detector
English  -> ONNX detector
Chinese  -> ONNX detector
Korean   -> ONNX detector
```

The ONNX detector remains the source of truth for bounding boxes and region metadata.

### OCR

OCR should be routed by source language after detector ROIs are available.

```text
Japanese -> MangaOCR / ONNX OCR
English  -> ML Kit OCR on ONNX ROI crops
Chinese  -> ML Kit OCR on ONNX ROI crops
Korean   -> ML Kit OCR on ONNX ROI crops
```

ML Kit should not replace ONNX detection/segmentation. It should receive cropped ROI images and return recognized text for each ROI.

### Translation

Translation should remain unified.

The currently selected translation engine applies to all languages. This means settings such as DeepSeek, Gemini, OpenRouter, Google Translate, or ML Kit Translator remain selected through the existing translation settings flow.

```text
All languages -> TranslationEngineBuilder-selected translator
```

No special rule should force non-Japanese translations to use ML Kit Translator.

### Inpainting

Inpainting should continue to use detector boxes, not ML Kit OCR boxes.

For all languages:

```text
ONNX detector boxes -> inpainting mask/regions
```

This keeps text removal independent from whichever OCR backend reads the cropped text.

### Rendering

Rendering should continue to use `TranslationBlock` coordinates derived from detector boxes.

The text inside each block comes from the language-routed OCR engine, then the unified translator fills `TranslationBlock.translation`.

## Current Code State

### Current Pipeline Entry Points

Main orchestration currently lives in:

```text
app/src/main/java/eu/kanade/translation/ChapterTranslator.kt
```

Relevant methods:

```text
createRecognitionEngine(...)
translateChapter(...)
translateSinglePage(...)
processSinglePage(...)
decodePageBitmap(...)
```

### Current Recognition Classes

Current recognition files:

```text
app/src/main/java/eu/kanade/translation/recognizer/PageRecognitionEngine.kt
app/src/main/java/eu/kanade/translation/recognizer/OnnxPageRecognitionEngine.kt
app/src/main/java/eu/kanade/translation/recognizer/MlKitPageRecognitionEngine.kt
app/src/main/java/eu/kanade/translation/recognizer/TextRecognizer.kt
app/src/main/java/eu/kanade/translation/recognizer/TextRecognizerLanguage.kt
```

Current ONNX implementation files:

```text
app/src/main/java/eu/kanade/translation/onnx/OnnxBubbleDetector.kt
app/src/main/java/eu/kanade/translation/onnx/OnnxOcrEngine.kt
app/src/main/java/eu/kanade/translation/onnx/OnnxModelStore.kt
app/src/main/java/eu/kanade/translation/onnx/OnnxRuntimeProvider.kt
```

### Current Coupling Problem

`OnnxPageRecognitionEngine` currently couples detection and OCR:

```text
ONNX detector -> crop ROI -> ONNX OCR -> TranslationBlock
```

The OCR call is currently hardcoded to ONNX OCR:

```kotlin
val text = ocrEngine!!.recognise(crop)
```

Because of that coupling, when ONNX is available, all languages are effectively routed through the same ONNX OCR model.

### Current Naming Problem

The current package layout is grouped by implementation/vendor instead of pipeline stage.

Current examples:

```text
onnx/OnnxBubbleDetector.kt
onnx/OnnxOcrEngine.kt
recognizer/OnnxPageRecognitionEngine.kt
recognizer/MlKitPageRecognitionEngine.kt
```

This obscures the actual pipeline stages:

```text
detection -> OCR -> translation -> inpainting -> rendering
```

## Proposed Architecture

### Pipeline Interfaces

Introduce explicit stage-level interfaces.

#### PageTextDetector

Responsible only for detecting page regions.

```kotlin
interface PageTextDetector : Closeable {
    fun detect(bitmap: Bitmap): List<Detection>
}
```

Notes:

- The existing `Detection` model can be reused or moved with this interface.
- The current ONNX detector becomes the primary implementation.
- Detection does not return OCR text.

#### RoiOcrEngine

Responsible only for reading text from one cropped ROI.

```kotlin
interface RoiOcrEngine : Closeable {
    suspend fun recognize(crop: Bitmap): String
}
```

Notes:

- MangaOCR/ONNX OCR implements this for Japanese.
- ML Kit implements this for English, Chinese, and Korean.
- The caller remains responsible for cropping and mapping results back to page coordinates.

#### PageRecognitionEngine

Responsible for combining detection, ROI OCR, block construction, and inpainting.

Existing `PageRecognitionEngine` can remain, but the main implementation should be renamed from `OnnxPageRecognitionEngine` to a pipeline-oriented name.

Example:

```text
RoiPageRecognitionEngine
```

This better describes the engine:

```text
detect page ROIs -> OCR each ROI -> produce PageTranslation -> inpaint
```

## OCR Routing Design

### OCR Backend Selection

`RoiPageRecognitionEngine` should receive `TextRecognizerLanguage` and select OCR backend based on it.

```kotlin
private fun createOcrEngine(lang: TextRecognizerLanguage): RoiOcrEngine {
    return when (lang) {
        TextRecognizerLanguage.JAPANESE -> MangaOcrEngine(...)
        TextRecognizerLanguage.ENGLISH,
        TextRecognizerLanguage.CHINESE,
        TextRecognizerLanguage.KOREAN -> MlKitRoiOcrEngine(lang)
    }
}
```

### Japanese OCR

Japanese should use the current ONNX OCR models.

Current model files:

```text
models/ocr/encoder.onnx
models/ocr/decoder_init.onnx
models/ocr/decoder_step.onnx
models/ocr/vocab.txt
```

The current `OnnxOcrEngine` should be wrapped or renamed as:

```text
MangaOcrEngine
```

This name better reflects the role in the application.

### Non-Japanese OCR

English, Chinese, and Korean should use ML Kit OCR on cropped detector ROIs.

The existing `TextRecognizer` already supports:

```text
TextRecognizerLanguage.ENGLISH -> TextRecognizerOptions.DEFAULT_OPTIONS
TextRecognizerLanguage.CHINESE -> ChineseTextRecognizerOptions
TextRecognizerLanguage.JAPANESE -> JapaneseTextRecognizerOptions
TextRecognizerLanguage.KOREAN -> KoreanTextRecognizerOptions
```

For the new routing, `MlKitRoiOcrEngine` should use only the needed non-Japanese cases, while keeping Japanese fallback possible if MangaOCR fails.

Example output logic for a cropped ROI:

```kotlin
val image = InputImage.fromBitmap(crop, 0)
val result = textRecognizer.recognize(image)
return result.textBlocks
    .flatMap { it.lines }
    .joinToString("\n") { it.text }
    .trim()
```

The exact join strategy can be tuned later. Initially, preserving line breaks is safer than flattening all lines into one string.

## Inpainting And Block Construction

### Source Of Coordinates

Coordinates should continue to come from ONNX detector boxes.

For every OCR result:

```text
Detection.bbox -> TranslationBlock.x/y/width/height
OCR text -> TranslationBlock.text
```

ML Kit OCR boxes from inside the cropped ROI should not replace the detector boxes.

### Parent Bubble Metadata

The existing ONNX detector provides both bubble and text labels:

```text
label 0 -> bubble
label 1 -> text in bubble
label 2 -> free text
```

`RoiPageRecognitionEngine` should keep current parent bubble assignment logic.

That metadata supports:

```text
parentX
parentY
parentWidth
parentHeight
```

and helps rendering place translated text into the full bubble region instead of only the text glyph box.

### Inpainting Input

Inpainting should remain box-based.

The input boxes should come from detector-derived `TranslationBlock`s:

```text
bubbleBoxes + textBoxes + extra detector boxes
```

The OCR backend should not affect which boxes are inpainted.

## Translation Design

Translation remains unchanged and unified.

Current translation selector:

```text
app/src/main/java/eu/kanade/translation/translator/TextTranslator.kt
TranslationEngineBuilder.build(...)
```

This currently chooses between:

```text
STANDARD -> ML Kit or Google Translate
AI_MODEL -> Gemini, OpenRouter, or DeepSeek
```

No language-specific override should be added for translation.

The current settings UI should continue to decide the translation engine for all languages.

## Proposed Package Layout

Move toward stage-based naming.

Recommended final layout:

```text
translation/
  detection/
    Detection.kt
    PageTextDetector.kt
    OnnxPageTextDetector.kt

  ocr/
    RoiOcrEngine.kt
    MangaOcrEngine.kt
    MlKitRoiOcrEngine.kt
    TextRecognizer.kt
    TextRecognizerLanguage.kt

  recognition/
    PageRecognitionEngine.kt
    RoiPageRecognitionEngine.kt
    MlKitFullPageRecognitionEngine.kt

  inpainting/
    AotInpainter.kt
    InpaintingMode.kt
    SmartBubbleTextCleaner.kt

  rendering/
    PageTextRenderer.kt

  translator/
    TextTranslator.kt
    MLKitTranslator.kt
    GoogleTranslator.kt
    GeminiTranslator.kt
    OpenRouterTranslator.kt
    DeepSeekTranslator.kt

  runtime/onnx/
    OnnxModelStore.kt
    OnnxRuntimeProvider.kt

  model/
    PageTranslation.kt
```

### Naming Mapping

Suggested renames:

```text
onnx/OnnxBubbleDetector.kt
-> detection/OnnxPageTextDetector.kt

onnx/OnnxOcrEngine.kt
-> ocr/MangaOcrEngine.kt

recognizer/TextRecognizer.kt
-> ocr/TextRecognizer.kt

recognizer/TextRecognizerLanguage.kt
-> ocr/TextRecognizerLanguage.kt

recognizer/OnnxPageRecognitionEngine.kt
-> recognition/RoiPageRecognitionEngine.kt

recognizer/MlKitPageRecognitionEngine.kt
-> recognition/MlKitFullPageRecognitionEngine.kt

onnx/OnnxModelStore.kt
-> runtime/onnx/OnnxModelStore.kt

onnx/OnnxRuntimeProvider.kt
-> runtime/onnx/OnnxRuntimeProvider.kt
```

`MlKitFullPageRecognitionEngine` can remain only as a fallback implementation. It should not be the primary non-Japanese path if ONNX detection is available.

## Implementation Phases

### Phase 1: Behavior Refactor With Minimal File Movement

Goal: implement intended routing while minimizing rename churn.

Steps:

1. Add `RoiOcrEngine` interface.
2. Add a `MangaOcrEngine` wrapper around current `OnnxOcrEngine` or make `OnnxOcrEngine` implement `RoiOcrEngine` temporarily.
3. Add `MlKitRoiOcrEngine` using existing `TextRecognizer`.
4. Modify `OnnxPageRecognitionEngine` constructor to accept `TextRecognizerLanguage`.
5. Inside `OnnxPageRecognitionEngine`, choose OCR backend by language.
6. Keep `OnnxBubbleDetector` as the detector for all languages.
7. Keep translation engine selection unchanged.
8. Keep inpainting logic unchanged.
9. Update `ChapterTranslator.createRecognitionEngine(...)` to construct the ONNX/ROI recognition engine with the source language.

Expected result:

```text
Japanese -> ONNX detector -> MangaOCR -> unified translator -> inpaint -> render
English/Chinese/Korean -> ONNX detector -> ML Kit ROI OCR -> unified translator -> inpaint -> render
```

### Phase 2: Naming Refactor

Goal: rename packages and files to match pipeline stages.

Steps:

1. Move detector types to `translation/detection`.
2. Move OCR types to `translation/ocr`.
3. Move recognition orchestration types to `translation/recognition`.
4. Move ONNX infrastructure to `translation/runtime/onnx`.
5. Update imports across the app.
6. Build and fix package/import errors.

This should be done after Phase 1 is behaviorally stable.

### Phase 3: Quality Improvements

Possible follow-up improvements:

1. Tune ML Kit ROI text joining for horizontal and vertical text.
2. Add confidence/empty-text handling per ROI.
3. Skip OCR for extremely small boxes.
4. Merge nearby detector boxes before OCR if ML Kit performs better on larger crops.
5. Add limited parallel OCR if safe for ML Kit and ONNX Runtime memory.
6. Add logs showing OCR backend per page and per language.

## Risks And Tradeoffs

### ML Kit On Cropped ROIs

ML Kit may perform differently on small cropped regions than on full pages.

Mitigation:

- Add padding around detector boxes before cropping.
- Preserve original detector coordinates for rendering.
- Clamp crop padding to page bounds.

### Japanese Fallback

If MangaOCR fails or ONNX models are unavailable, fallback behavior should be explicit.

Possible fallback:

```text
Japanese MangaOCR failure -> ML Kit Japanese OCR on same detector ROIs
```

This keeps the pipeline functional, but quality may differ.

### Translation Settings

Because translation is unified, changing translator settings affects all languages.

This is intended.

The OCR backend should not silently change the selected translation engine.

### Package Rename Churn

Moving packages can create many import changes.

Mitigation:

- Implement behavior first.
- Rename packages second.
- Avoid mixing large package moves with logic changes in the same phase.

## Acceptance Criteria

### Behavior

The implementation should satisfy:

```text
Japanese source language uses MangaOCR/ONNX OCR for ROI text recognition.
English source language uses ML Kit OCR for ROI text recognition.
Chinese source language uses ML Kit OCR for ROI text recognition.
Korean source language uses ML Kit OCR for ROI text recognition.
All languages use ONNX detector boxes for ROI, inpainting, and rendering geometry.
All languages use the same selected translation engine from settings.
```

### Code Organization

The final organization should make these responsibilities obvious from package names:

```text
detection = boxes
ocr = text recognition from crops
translator = text translation
inpainting = original text removal
rendering = translated text drawing
runtime/onnx = ONNX infrastructure
```

### Logging

Add or preserve logs that make routing visible:

```text
Selected detector: ONNX
Selected ROI OCR: MangaOCR or ML Kit
Source language: Japanese/English/Chinese/Korean
Detected ROI count
OCR block count
Translation engine category/provider
```

## Recommended First Code Change

The smallest useful first implementation is:

1. Add `RoiOcrEngine`.
2. Add `MlKitRoiOcrEngine`.
3. Pass `TextRecognizerLanguage` into `OnnxPageRecognitionEngine`.
4. Route only the OCR call inside the existing `OnnxPageRecognitionEngine` loop.

This avoids a large package rename while proving the intended behavior.

After that works, proceed with package/file naming cleanup.
