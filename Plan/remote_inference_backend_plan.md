# Remote Desktop Inference Backend — Implementation Plan

This document outlines the technical plan for adding a **desktop/PC companion server** that performs the heavy inference (text detection, OCR, translation, inpainting) while the phone only decodes, renders, caches, and displays. The phone stays cool, fast, and memory-light; the desktop brings its GPU, RAM, and stronger models to bear.

All eight design decisions below were locked during brainstorming. Please review the proposed changes and provide approval to begin execution.

---

## Locked Design Decisions

| # | Decision | Choice |
|---|----------|--------|
| 1 | Backend strategy | **Pluggable, companion-first** — abstract remote inference behind an interface; ship the companion server as the first backend. MIT/others become pluggable later. |
| 2 | Offload scope | **Full pipeline** — desktop runs detect + OCR + translate + inpaint. Phone renders + caches + shows. |
| 3 | Backend selection | **Single master switch** — "Inference: On-device / Desktop" toggle. |
| 4 | Plan coverage | **Client + companion server** — full vertical in one effort. |
| 5 | Translation ownership | **Server uses its own translator** — minimal contract; the phone sends no API keys or translator config. |
| 6 | Server stack | **Python** — FastAPI + onnxruntime-gpu + Pillow + numpy. |
| 7 | Model selection | **Server-side config** — the desktop operator picks detector/OCR/inpaint/translator models via hot-reloadable server config. The phone is model-agnostic. |
| 8 | Protocol shape | **Hybrid** — per-page endpoint for manual/auto interactivity + batch endpoint for pre-translate. |

---

## Core Architectural Insight

The pipeline already splits along the exact seam this feature wants. Two facts make this a *clean insertion, not a rewrite*:

### 1. `translateSinglePage` is two phases
[`TranslationPipeline.kt#L653`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L653):
- **ONNX phase** (permit-held via `translatorPermit = Semaphore(1)`, [`#L178`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L178)): decode → detect → OCR → inpaint → persist `.cleaned.png`.
- **HTTP+Render phase** (permit-free): translate → render onto cleaned bitmap → persist rendered PNG.

The slow, memory-painful part is the ONNX phase, serialized so only one page's tensors live at a time. **In desktop mode the phone holds no ONNX tensors** — its only remaining constraint is decode + holding cleaned bitmaps + render (light bitmaps).

### 2. The render phase is output-contract-driven
`translateSinglePageHttpRender` ([`#L1766`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L1766)) consumes only three things:
- `pageTranslation.cleanedBitmap` (the inpainted image)
- `pageTranslation.blocks` (with `.translation` already filled)
- `pageTranslation.decodeSampleSize`

`RenderColorEstimator.recomputeFor` ([`#L1906`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L1906)) re-derives text/stroke colors against the cleaned bitmap on-device.

**Result:** A backend that returns *translated blocks + cleaned bitmap* feeds the render path unchanged. The phone's font, layout solver (`TextLayoutPlanner`), and color logic stay authoritative. Caching (`ChapterTranslationStore`), dedup, resume, and the reader UI need **zero changes** — the output contract is fixed.

In desktop mode the pipeline becomes: `decode → remote round-trip → write blocks + .cleaned.png → render`. Render is shared code, not duplicated.

### Why desktop mode is structurally different (the dynamic-concurrency point)
The entire memory-shaped throttle exists *because on-device ONNX holds heavy tensors*:
- `translatorPermit = Semaphore(1)` ([`#L178`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L178))
- The auto-window memory gate `TranslationMemoryBudget.hasHeadroomForPrefetch` ([`ReaderViewModel.kt#L1022`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L1022))
- Batch Lane A's `withLeakProofPermit` ([`#L1106`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L1106))
- `BatchOomPolicy` ([`#L1201`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L1201))

In desktop mode **none of that memory pressure exists on the phone**. So the execution model for desktop mode is **designed for concurrency from the start** — fire many pages in parallel, let the desktop's GPU scheduler batch them — not forced through the on-device single-permit structure.

---

## How It Fits Each Mode

| Mode | Trigger → Orchestrator | Fit |
|------|------------------------|-----|
| **Manual** | `PagerPageHolder` click ([`#L121`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt#L121)) → `ReaderViewModel.translateSinglePage` ([`#L1702`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L1702)) → per-page `/v1/translate` | Drops in cleanly. Biggest per-tap UX win — the ONNX phase (the slow part) becomes a network round-trip. |
| **Auto** | `onPageSelected` ([`#L956`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L956)) → `handleAutoTranslation` ([`#L993`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt#L993)) → per-page `/v1/translate` | The memory throttle (`hasRoomForPrefetch`) becomes irrelevant — remote inference consumes no device memory, so the prefetch window (clamped 1–5 today) can safely widen to server capacity. The generation-based staleness model ([`TranslationScheduler.kt#L110`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt#L110)) already finishes the in-flight page then stops on stale gen — scales without rework. |
| **Pre-translate / batch** | `MangaScreenModel` START → `TranslationManager.translateChapter` ([`#L218`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationManager.kt#L218)) → `translateBatch` ([`#L809`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L809)) → `/v1/translate_batch` | The strongest fit. The existing 3-lane pipeline maps perfectly: Lane A (OCR+inpaint) and Lane B (translate) both become remote; Lane C (render) stays on-device. For the batch endpoint, Lane A issues one `/v1/translate_batch` call per chunk — highest throughput, and the server runs a single shared-context translator call for continuity. |

---

# Part A — Android Client

## A1. Preference: Master Switch + Connection Config

Add to [`TranslationPreferences.kt`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt) (mirrors the existing `autoTranslate()` / inpainting-mode pattern):

```kotlin
enum class InferenceBackend { ON_DEVICE, DESKTOP }

/** Master switch: run heavy inference on-device (default) or on a desktop companion server. */
fun translationInferenceBackend() =
    preferenceStore.getEnum("translation_inference_backend", InferenceBackend.ON_DEVICE)

/** Companion server base URL (e.g. http://192.168.1.10:8765). */
fun translationDesktopBaseUrl() =
    preferenceStore.getString("translation_desktop_base_url", "")

/** Optional bearer token for the companion server (private, excluded from backups). */
fun translationDesktopAuthToken() =
    preferenceStore.getString("__PRIVATE_translation_desktop_auth_token", "")

/** Client-side cap on concurrent in-flight desktop requests (clamped against server report). */
fun translationDesktopMaxConcurrency() =
    preferenceStore.getInt("translation_desktop_max_concurrency", 4)
```

`getEnum` serializes by `.name` (existing convention). Default `ON_DEVICE` = zero behavior change for existing users.

## A2. New Engine: `RemotePageTranslationEngine`

**Key design point:** `PageRecognitionEngine` ([`recognition/PageRecognitionEngine.kt#L7`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/recognition/PageRecognitionEngine.kt#L7)) is too narrow — it covers detect+OCR+inpaint, **not translation**. Since the desktop also translates, we add a round-trip abstraction that returns **blocks with `.translation` filled + cleaned bitmap**, while still implementing `PageRecognitionEngine` so the pipeline's `recognitionEngine` field type is unchanged.

New file `app/src/main/java/eu/kanade/translation/remote/RemotePageTranslationEngine.kt`:

```kotlin
class RemotePageTranslationEngine(
    private val baseUrl: String,
    private val authToken: String?,
    private val toLang: TextTranslatorLanguage,
    private val inpaintingMode: InpaintingMode,
    private val maxConcurrency: Int,
) : PageRecognitionEngine {

    // Per-page (manual + auto). Returns blocks WITH .translation already filled.
    override suspend fun analyze(bitmap: Bitmap): PageTranslation

    // Per-page inpaint: POST bitmap + mask boxes -> cleaned PNG bytes.
    override suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap?

    // BATCH entrypoint (pre-translate): one call for N pages -> server runs
    // detect+OCR+translate+inpaint with GPU packing + one shared-context translator call.
    suspend fun translateBatch(bitmaps: List<Bitmap>): List<PageTranslation>

    override fun close() { /* evict OkHttp pool + dispatcher */ }
    // reclaimPooledMemory / forceReleaseNativeBuffers: no-ops (no native memory on client)
}
```

Template: [`LmStudioTranslator.kt`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/translator/LmStudioTranslator.kt) — private `OkHttpClient` with 60–90s timeouts, `eu.kanade.tachiyomi.network.await`, `close()` evicts pool+dispatcher (line 180). Generous timeouts because the server may run a large model.

## A3. Protocol Contract (Hybrid + Model-Agnostic)

The contract is **model-agnostic** — the phone sends no model ids. Models live in server-side config (Part B4). `/v1/health` reports the active set so the phone *displays* what's running without coupling to it.

### `GET /v1/health`
```json
{
  "status": "ok",
  "protocol_version": 10,
  "gpu": "cuda",
  "max_concurrency": 8,
  "models": {
    "detector":  "detector-v4-s_int8",
    "ocr":       "manga-ocr",
    "inpaint":   "aot",
    "translator": "qwen2.5-72b"
  }
}
```
Used by the connection-test UI and to clamp client concurrency. `protocol_version` ties to [`PageTranslation.CURRENT_INPAINT_REVISION`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/model/PageTranslation.kt#L135) (currently `10`). Mismatch → client refuses with "update the companion server."

### `POST /v1/translate` (per-page — manual + auto)
- **Request:** `multipart/form-data` — `image` (JPEG/PNG bytes; client MUST NOT encode to JPEG if the source image has an alpha channel/transparency), `target_lang` (e.g. `"ENGLISH"`), `mode` (`"FAST"`|`"QUALITY"`).
- **Response:** `application/json`:
```json
{
  "protocol_version": 10,
  "img_width": 1200, "img_height": 1800,
  "blocks": [
    { "text": "源文", "translation": "translated",
      "x": 100.0, "y": 200.0, "width": 80.0, "height": 30.0,
      "sym_height": 28.0, "sym_width": 26.0, "angle": 0.0,
      "label": 1, "direction": "LTR" }
  ],
  "inpaint_mask_boxes": [
    { "x1": 90, "y1": 195, "x2": 190, "y2": 235, "label": 1 }
  ]
}
```
Field shapes mirror [`TranslationBlock`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/model/PageTranslation.kt#L213) and [`InpaintMaskBox`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/model/PageTranslation.kt#L153) exactly → deserializes straight into the existing model. `textColor`/`strokeColor` are omitted (re-derived on-device by `RenderColorEstimator`).

### `POST /v1/inpaint` (per-page)
- **Request:** `multipart/form-data` — `image`, `mask_boxes` (JSON of the list above), `mode`.
- **Response:** `image/png` bytes (the cleaned bitmap). HTTP 200 + `X-Inpaint-Status: OK`; non-200 → client marks page FAILED.

### `POST /v1/translate_batch` (batch — pre-translate)
- **Request:** `multipart/form-data` with N `images` + `target_lang` + `mode`. The client MUST chunk this request (e.g., max 5 pages per call) to avoid OOM-ing the phone's OkHttp deserializer when receiving the massive multipart response.
- **Response:** an array of the per-page JSON above **plus** each page's cleaned PNG (multipart response). The server runs them with GPU packing and **one shared-context translator call** (mirrors on-device `TranslationContextChunkPlanner` glossary/chunking for translation continuity). This is the high-throughput path.

## A4. Wire Into the Pipeline

### Routing
Edit [`createRecognitionEngine`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L437) to branch on the master switch:
```kotlin
private fun createRecognitionEngine(...): PageRecognitionEngine {
    val backend = translationPreferences.translationInferenceBackend().get()
    if (backend == InferenceBackend.DESKTOP) {
        val baseUrl = translationPreferences.translationDesktopBaseUrl().get()
        if (baseUrl.isBlank()) throw IllegalStateException("Desktop backend requires a base URL")
        return RemotePageTranslationEngine(
            baseUrl = baseUrl,
            authToken = translationPreferences.translationDesktopAuthToken().get().takeIf { it.isNotBlank() },
            toLang = currentToLang,
            inpaintingMode = mode,
            maxConcurrency = translationPreferences.translationDesktopMaxConcurrency().get(),
        )
    }
    // existing ONNX path unchanged
    val onnx = RoiPageRecognitionEngine(context, lang, ocrModel, mode)
    ...
}
```

### Rebuild gate
Extend [`EngineSignature`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L336) and [`computeTranslatorSignature`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L355) with `backend + baseUrlHash + desktopMaxConcurrency` AND a hash of the `models` dictionary from `/v1/health`. This ensures [`ensureEnginesBuiltFor`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L1472) rebuilds immediately when the user flips the switch OR when the server operator hot-reloads a model. 

Switching the master switch must also trigger `close()` on the active engine to instantly cancel the OkHttp `Dispatcher`, preventing zombie network requests.

### Cache Partitioning
Modify `ChapterTranslationStore` to include the `InferenceBackend` type in its cache keys. This prevents on-device translations and desktop translations from overwriting each other, avoiding cache poisoning when the user toggles the mode.

## A5. The Translate-Skip Gate (One Behavioral Change, Two Sites)

Gate the translate call on whether translation already happened. In desktop mode the remote engine sets `translationStatus = READY`; in on-device mode the field stays `PENDING`.

- Single-page `runTranslate` call site ([`#L1794`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L1794)): if `translationStatus == StageStatus.READY`, skip.
- Batch Lane B `textTranslator.translatePage` ([`#L1263`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L1263)): if READY, skip.

Small, well-contained conditional at each site — no pipeline restructure. On-device behavior is identical to today.

## A6. Concurrency Model (Dynamic Parallelism)

Three adjustments, all guarded by the backend flag so on-device behavior is untouched:

1. **Single-page path:** replace the ONNX-phase `withLeakProofPermit(translatorPermit)` serialization ([`#L663`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L663)) with a concurrency semaphore of size `min(server.max_concurrency, desktopMaxConcurrency)`. Multiple pages fly concurrently.
2. **Auto mode:** bypass `hasRoomForPrefetch` — no tensor memory to budget. Window scales to server concurrency.
3. **Batch mode:** Lane A's `withLeakProofPermit` ([`#L1106`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt#L1106)) becomes N concurrent workers pulling from the channel, bounded by the same semaphore **and** the existing `bitmapRegistry` slot count (`countSlots` / `HELD_BITMAP_MAX_COUNT`) — the slot count remains the phone's real bound (held bitmaps). Lane C render stays single-consumer as today. For the **batch endpoint** variant, Lane A can issue one `/v1/translate_batch` call per chunk instead of per-page calls — highest throughput.

### Fallback
On remote failure (timeout, non-200, protocol mismatch), mark the page `FAILED` with a **user-facing error string** (e.g., "Desktop Server Unreachable" or "Server Overloaded") via `markPageFailed`. This ensures the UI displays a clear explanation rather than silently failing. **No silent on-device fallback** in v1 — that would require keeping ONNX engines warm while using remote (the memory cost we're avoiding). Manual `force` retry re-admits the page via `prepareForcedRetry`, consistent with the existing manual-retranslate contract.

## A7. Settings UI

Add a section to [`SettingsTranslationScreen.kt`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt) (`getPreferences`, ~line 44) and mirror it in the reader [`TranslationSettingsSheet.kt`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/presentation/reader/TranslationSettingsSheet.kt) `Advanced` expander (the existing inpaint/engine overlay pattern at lines 115–116):

- **`ListPreferenceWidget`** (reuse `EngineListRow`): "Inference backend" → On-device / Desktop.
- When Desktop is selected:
  - **Base-URL** `EditTextPreferenceWidget` (template: LM Studio's at `TranslationSettingsSheet.kt#L505`).
  - **Auth token** `ApiKeyPreferenceWidget`.
  - **Concurrency cap** `EditTextPreferenceWidget`.
  - **"Test connection"** button → hits `/v1/health`. On success shows a read-only summary: `detector: X · ocr: Y · inpaint: Z · translator: W · gpu: cuda · max_concurrency: 8`. On protocol mismatch shows "update companion server." (Template: [`AiModelFetcher.fetch`](file:///c:/Users/ADMIN/Documents/Coding%20Related/manga_translation_optimized/manga_translation_optimized/android_app/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/app/src/main/java/eu/kanade/translation/translator/AiModelFetcher.kt#L46), which already returns a sealed `Result`.)

---

# Part B — Python Companion Server

New top-level directory `companion_server/` (sibling of `app/`, outside the Android build). Stack: **FastAPI + onnxruntime-gpu + Pillow + numpy + uvicorn**, CUDA via `CUDAExecutionProvider`.

## B1. Module Layout
```
companion_server/
  requirements-cpu.txt      # for setups without NVIDIA GPUs
  requirements-gpu.txt      # fastapi, uvicorn, onnxruntime-gpu, pillow, numpy, pydantic, watchdog, aiohttp
  server.py                 # FastAPI app: /v1/health, /v1/translate, /v1/inpaint, /v1/translate_batch
  config.yaml               # model paths + translator config (HOT-RELOADABLE via watchdog)
  inference/
    detector.py             # port OnnxPageTextDetector (640x640 NCHW, NMS dedup, 3-class labels)
    ocr_manga.py            # port MangaOcrEngine autoregressive loop (encoder/decoder_init/decoder_step + vocab.txt)
    ocr_paddle.py           # optional paddle-v6-small rec + det
    inpaint_aot.py          # port AOTInpainting tiered router + classical fallbacks + AotOutputGuard
    mask_planner.py         # port PageInpaintingPlanner (inpaintMaskBoxes; render-aware blank-text semantics of rev 10)
  translate/
    translator.py           # pluggable OpenAI-compatible HTTP translator (server-side keys/config)
  batch/
    scheduler.py            # GPU packing: accepts N pages, schedules across available VRAM/streaming
  models/                   # the SAME .onnx files as app/src/main/assets/models/ (plus any stronger models)
  README.md
  tests/                    # per-module + parity tests
```

## B2. Hot-Reloadable Model Config (Your Model-Freedom Point)
`config.yaml` lists, per stage, the model path + optional provider args:
```yaml
detector:   { path: models/detection/detector-v4-s_int8.onnx }
ocr:        { engine: manga_ocr, path: models/ocr/ }
inpaint:    { path: models/inpainting/aot.onnx }
translator:
  provider: openai_compatible
  base_url: http://localhost:1234/v1
  api_key_env: TRANSLATOR_KEY
  model: qwen2.5-72b
  temperature: 0.3
server:
  max_concurrency: 8
  device: cuda              # cuda | cpu | directml (Windows)
```
A `watchdog` file watcher reloads `config.yaml` and swaps a stage's loaded model **without a restart**. Each stage holds its model behind a swap-able handle; inference takes a read lock, so a swap mid-flight never serves a page from a half-loaded model. **This is *you* deciding models at runtime** — the phone never needs to know, but `/v1/health` reports the active set for visibility.

## B3. GPU Packing + max_concurrency (Your Parallelism Point)
Sessions use `CUDAExecutionProvider` (fallback `CPUExecutionProvider`; DirectML option documented for Windows in README). `/v1/translate_batch` feeds the GPU scheduler (`batch/scheduler.py`), which packs multiple pages against available VRAM and streams them concurrently. 

**Critical Safeguard:** The server MUST actively poll `await request.is_disconnected()` while a page sits in the GPU queue. If the client cancels the OkHttp call (e.g., user scrolled past the page), the server instantly drops the task to prevent VRAM starvation.

`max_concurrency` is the client-facing cap; the server may internally run more if VRAM allows. **This is the primary speed win and the main reason to choose Python.**

The server may load **stronger/larger** models than the phone bundles (the stated goal) — expose model paths via config; protocol versioning keeps the output contract stable regardless of which model produced it.

## B4. Porting the Pipeline Logic
Each engine's algorithm is ported Kotlin → Python using the **same model files** so behavior matches:

| Stage | Kotlin source to port | Python target |
|-------|----------------------|---------------|
| Detection | `OnnxPageTextDetector.detect` — preprocess to 640×640 NCHW, threshold 0.45, geometric dedupe (IoU/containment from `BoxGeometry`), 3-class labels (0=bubble, 1=text_bubble, 2=text_free). **Enforce a max pixel resolution limit** to prevent VRAM spikes on long-strip webtoons. | `inference/detector.py` |
| OCR (Manga) | `MangaOcrEngine` — `decoder_init` then `decoder_step` until EOS, vocab lookup. **Implement a hard max-token limit** to prevent autoregressive endless loops on garbage text. | `inference/ocr_manga.py` |
| OCR (Paddle) | `PaddleOcrV6SmallEngine` (CTC rec) + `PaddleOcrV6DetEngine` (DB line det) | `inference/ocr_paddle.py` |
| Inpainting | `AOTInpainting.inpaintRegions` tiered router — parented bubbles → classical fill; free-text → neural `aot.onnx`; classical fallbacks (`SmartBubbleTextCleaner`, `LegacyFreeTextInpainter`, `FastMarchingMethod`); `AotOutputGuard` uniform-gray rejection | `inference/inpaint_aot.py` |
| Mask planning | `PageInpaintingPlanner` — assemble `inpaintMaskBoxes` (bubble box + text box per block + detector-only/watermark regions), honoring render-aware rev-10 semantics (blank-text regions keep original pixels) | `inference/mask_planner.py` |
| Block sort/filter | `TranslationBlockSorter`, `OcrTextFilter` equivalents | inline in `detector.py` / `ocr_*.py` |

A `PORTING_NOTES.md` documents each ported algorithm's source file:line for traceability and flags any platform-specific divergence.

---

# Validation & Testing

## Client (no real server needed)
- **Unit-test `RemotePageTranslationEngine`** against an in-app mock backend (`MockWebServer` or an in-memory fake implementing the contract) returning canned JSON + PNG. Verify: blocks deserialize into `TranslationBlock`/`InpaintMaskBox`, `translationStatus = READY`, and the render phase runs and produces a rendered bitmap equivalent to the on-device path for the same inputs.
- **Translate-skip gate:** READY → `runTranslate` not called; PENDING → called.
- **Rebuild gate:** flipping `translationInferenceBackend()` triggers `ensureEnginesBuiltFor` to swap `recognitionEngine` (extend the existing `EngineSignature` equality test).
- **Concurrency:** assert the desktop-path semaphore allows N concurrent in-flight; on-device still serializes (1).
- **Fallback/timeout:** mock server returns 500 / is unreachable → page marked `FAILED` with a backend-specific message; manual `force` retry re-admits.
- **Protocol mismatch:** `/v1/health` reports a different `protocol_version` → switch refuses with a clear message.

## Server
- **Per-module unit tests** (detector NMS, OCR decode loop, inpaint tiered routing, mask planner, GPU scheduler packing) with fixture page images and golden JSON outputs.
- **Parity round-trip test:** feed the same page through the server and the on-device engine; assert block count / bboxes / text are within tolerance (inpainting pixels via a perceptual metric, not exact match).

## End-to-end (manual)
Run the companion server on a LAN PC with GPU, point the phone at it, translate a chapter via manual / auto / pre-translate; confirm faster throughput and visually equivalent output.

---

# Scope, Risks & Assumptions

## In scope
- Master-switch pref (`translationInferenceBackend` + connection config).
- `RemotePageTranslationEngine` + hybrid protocol (per-page + batch).
- `createRecognitionEngine` routing + `EngineSignature` extension.
- Translate-skip gate (two sites).
- Dynamic concurrency (single-page, auto, batch — three paths).
- Settings UI + connection test + model-visibility summary.
- Mock-backend tests.
- Full Python companion server with hot-reloadable config + GPU scheduler.

## Out of scope (v1)
- Silent on-device fallback (explicit non-goal — would require keeping ONNX warm while using remote).
- Phone-side model pickers (decided: server-side config).
- MIT/other backend impls (interface is pluggable for them; not built now).
- LAN discovery / mDNS (manual base-URL entry only).
- Authentication beyond an optional bearer token.

## Risks / Edge Cases
1. **Logic drift** Kotlin ↔ Python over time — mitigated by protocol versioning (`CURRENT_INPAINT_REVISION`), the parity round-trip test, and per-algorithm source-line docs in `PORTING_NOTES.md`.
2. **Round-trip latency vs batch** — per-page for interactivity, batch for pre-translate; the hybrid covers both. Per-page translation loses cross-page context per call (acceptable for interactive single-page; the batch path retains it).
3. **Bitmap upload size** — server accepts JPEG (smaller) as well as PNG; client encodes to JPEG for upload when the source isn't already lossless.
4. **Network reliability mid-batch** — a dropped connection fails the page (not the chapter); the existing `BatchProgressReconciler` / stranded-page handling copes. `markPageFailed` already exists.
5. **Client bitmap budget under high concurrency** — the `bitmapRegistry` slot count (`countSlots` / `HELD_BITMAP_MAX_COUNT`) remains the hard bound; concurrency clamps to it so the phone never OOMs on held cleaned bitmaps.
6. **Security** — the bearer token is optional and the server is LAN-only by default; README must warn against exposing it to the public internet.
7. **Hot-reload race** — a model swap mid-inference could serve a page with mixed models; the swap-able-handle + read-lock design prevents a partially-loaded model from being used.
8. **`EngineSignature` membership** — whatever selects the backend must be part of `EngineSignature` so the rebuild gate fires on switch; if missed, a stale engine lingers across a config change.

## Assumptions
- The phone and PC are on the same LAN.
- The user places the `.onnx` model files into the server's `models/` dir (copied from app assets or re-downloaded).
- OkHttp 5.x multipart upload + the existing `await()` extension are sufficient (already used by every HTTP translator).

---

## Known Limitations & Future Enhancements (v2)
- **LAN Bandwidth Saturation:** Multiple concurrent uploads could saturate congested Wi-Fi despite the semaphore cap. Mitigation in v1 is manual lowering of `desktopMaxConcurrency`. V2 could implement a client-side bandwidth probe.
- **Large Batch Atomicity:** Chunking 5 pages per batch endpoint means if the network drops on page 5, all 5 pages in the chunk fail and must be retried. V2 could have the server report per-page success within a batch response for partial commits.
- **Server Packaging:** V1 relies on manual `python server.py` execution. V2 could provide a PyInstaller-packaged one-click launch executable.
- **Android Keystore:** The `__PRIVATE_` pref stores the auth token in shared prefs, extractable on rooted devices. Acceptable for LAN-only v1; V2 could harden this using the Android Keystore.
- **Per-Page Translation Context:** Per-page `/v1/translate` calls (manual/auto mode) lack the cross-page glossary context that the batch endpoint preserves. Acceptable for interactive translation, but V2 could explore context-window statefulness for manual translations.

---

## Implementation Order

1. **Preferences + `EngineSignature`** (A1, A4 rebuild gate) — smallest safe change; enables the switch.
2. **`RemotePageTranslationEngine` + mock backend + tests** (A2) — proves the contract before the server exists.
3. **Pipeline wiring** (A4 routing, A5 translate-skip gate, A6 concurrency) — the behavioral changes.
4. **Settings UI + connection test** (A7) — user-facing surface.
5. **Companion server** (Part B) — starts once the client contract is proven against the mock.
6. **End-to-end parity validation** — server ↔ on-device equivalence on real pages.

---

> [!IMPORTANT]
> **User Review Required:** This plan covers the full client + companion server vertical, with dynamic concurrency, server-side (hot-reloadable) model config, and a hybrid per-page + batch protocol. All eight design decisions are locked. If everything looks good, click **Proceed** and I will begin implementing (recommended start: Preferences + `EngineSignature`, then `RemotePageTranslationEngine` against a mock backend).
