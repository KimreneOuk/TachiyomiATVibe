# Fix Translation Animation/Replacement on First Page

The user reported that the first page of the reader does not animate or replace the image when translated, particularly when entering the reader for the first time or resuming from the background.

## Root Cause
In `PagerPageHolder.kt`, the `holderScope` coroutine scope is cancelled during `onDetachedFromWindow()` to prevent memory leaks. However, `ViewPager` frequently detaches and re-attaches views during layout passes or when the activity is resumed. Because `PagerPageHolder` has no logic to recreate the scope or its subscriptions upon re-attachment, the first page's translation status observer (`observePageView`) is permanently cancelled after the layout pass. Consequently, it never receives state updates to trigger the animation.

## Proposed Changes

### 1. Fix `PagerPageHolder` Lifecycle
Make `holderScope` mutable and move the initialization of long-running collectors from the `init` block into `onAttachedToWindow()`.
#### [MODIFY] PagerPageHolder.kt
- Change `val holderScope` to `var holderScope`.
- Add `override fun onAttachedToWindow()`.
- Check `if (!holderScope.isActive)` and recreate the scope.
- Move the `observePageView` subscription and `loadJob` launch from `init` into `onAttachedToWindow()`.

### 2. Fix Paddle OCR v6 Memory Handling
The user correctly pointed out that Paddle OCR v6 engines (`PaddleOcrV6DetEngine` and `PaddleOcrV6SmallEngine`) are not handling memory correctly compared to `MangaOcrEngine`. `MangaOcrEngine` uses a `DirectBufferPool` to prevent native memory leaks inside ONNX Runtime. However, both Paddle OCR engines currently use `FloatBuffer.wrap(pixels)` which creates a heap-backed buffer. This forces ONNX Runtime to create an internal native copy of the tensor on every inference call, which lingers on the session's internal heap and causes memory accumulation.

#### [MODIFY] PaddleOcrV6DetEngine.kt & PaddleOcrV6SmallEngine.kt
- Introduce a `DirectBufferPool` in both engine classes to manage the input tensor buffers, similar to how it is done in `MangaOcrEngine`.
- Update the `preprocess` methods to write the preprocessed pixel data directly into the pooled direct buffer instead of a new `FloatArray`.
- Create the `OnnxTensor` using the pooled direct buffer.

---

# Add DeepL Support for Standard Translation

DeepL will be added as a "Standard" translation engine. It requires an API key, which will be added to the translation settings.

## Open Questions
> [!IMPORTANT]
> DeepL has two different endpoint URLs depending on whether the API key is for a Free account (`api-free.deepl.com`) or a Pro account (`api.deepl.com`). Free keys typically end in `:fx`. I will implement logic to auto-detect the endpoint based on the `:fx` suffix. Does this approach work for you, or would you prefer an explicit toggle in the settings?

## Proposed Changes

### 1. Add Settings & Preferences
#### [MODIFY] TranslationPreferences.kt
- Add a new string preference `translationDeeplApiKey()`.
#### [MODIFY] StandardEngine.kt (Domain)
- Add `DEEPL` to the `StandardEngine` enum.
#### [MODIFY] SettingsTranslationScreen.kt
- Add an input field for the DeepL API key in the Standard Engine settings section. It will only be visible/enabled when DeepL is selected or as a general setting.

### 2. Implement DeepL Translator
#### [NEW] DeepLApi.kt
- Define Retrofit interface for DeepL's `v2/translate` endpoint.
#### [NEW] DeepLTranslator.kt
- Implement `TextTranslator` using Retrofit to send batched or single-text blocks to DeepL.
- The logic will dynamically choose between `api.deepl.com` and `api-free.deepl.com` based on whether the API key ends with `:fx`.
#### [MODIFY] StandardTranslatorKind.kt
- Add `DEEPL` mapping to instantiate `DeepLTranslator(apiKey)`.

## Verification Plan

### Manual Verification
- **Bug Fix**: Enter the reader and immediately click Translate on the first page. Verify that the loading overlay appears and the image replaces correctly. Tab out of the app, resume, and translate again to verify it still works.
- **DeepL Integration**: Select "DeepL" as the standard engine, enter an API key, and translate a page. Verify that text blocks are successfully translated and rendered on the page.
