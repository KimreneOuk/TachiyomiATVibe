# TachiyomiAT Architecture Overview

> **TachiyomiAT v1.16.8-dev** — An Android manga reader fork of Mihon/Tachiyomi with
> AI-powered automatic manga translation (OCR → Translate → Inpaint → Render).

---

## 1. Project Identity

| Attribute | Value |
|-----------|-------|
| **Fork base** | [Mihon](https://github.com/mihonapp/mihon) (which itself forked [Tachiyomi](https://github.com/tachiyomiorg/tachiyomi)) |
| **Unique feature** | Automatic manga page translation via AI pipeline |
| **Author** | [mannu691](https://github.com/mannu691/TachiyomiAT) |
| **License** | Apache 2.0 |
| **Min SDK** | 26 (Android 8.0) |
| **Target SDK** | 34 |
| **Compile SDK** | 35 |
| **Build system** | Gradle with Kotlin DSL + convention plugins |
| **CI** | GitHub Actions (PR checks + tagged release builds) |

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        APP MODULE (:app)                        │
│  ┌───────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐   │
│  │   UI/UX   │ │  Reader  │ │Translate │ │  Settings/More   │   │
│  │ (Compose) │ │ (Viewer) │ │ Pipeline │ │    Screens       │   │
│  └─────┬─────┘ └────┬─────┘ └────┬─────┘ └────────┬─────────┘   │
│        │            │            │                 │            │
│  ┌─────┴────────────┴────────────┴─────────────────┴─────────┐  │
│  │              Presentation Layer (eu.kanade.presentation)  │  │
│  │     Compose Screens, Components, Widgets, Theme, Icons    │  │
│  └───────────────────────────┬───────────────────────────────┘  │
└──────────────────────────────┼──────────────────────────────────┘
                               │
┌──────────────────────────────┼──────────────────────────────────┐
│          DOMAIN LAYER (:domain)                                 │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌───────┐    │
│  │ Manga   │ │ Chapter │ │ Category │ │  Track   │ │Translate   │
│  │UseCases │ │UseCases │ │ UseCases │ │ UseCases │ │UseCase│    │
│  └────┬────┘ └────┬────┘ └────┬─────┘ └────┬─────┘ └───┬───┘    │
│       └───────────┴───────────┴────────────┴───────────┴────────┘│
│                   Repository Interfaces & Models                │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────┼──────────────────────────────────┐
│          DATA LAYER (:data)                            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────────┐  │
│  │ Manga    │ │ Chapter  │ │ History  │ │   SQLDelight DB   │  │
│  │ Repository│ │ Repository│ │ Repository│ │  (SQLite + Views) │  │
│  └──────────┘ └──────────┘ └──────────┘ └───────────────────┘  │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────┼─────────────────────────────────┐
│       CORE LAYER (:core)                                       │
│  ┌────────────────────┐ ┌──────────────────────────────┐       │
│  │  core/common       │ │  core/archive                │       │
│  │  ─ Networking      │ │  ─ CBZ/CBR/EPUB readers      │       │
│  │  ─ Preferences     │ │  ─ ZIP writer                │       │
│  │  ─ Storage         │ │                              │       │
│  │  ─ Utilities       │ │                              │       │
│  └────────────────────┘ └──────────────────────────────┘       │
│  ┌────────────────────┐ ┌──────────────────────────────┐       │
│  │  core-metadata     │ │  source-api                  │       │
│  │  ─ ComicInfo XML   │ │  ─ Source interfaces         │       │
│  │  ─ MangaDetails    │ │  ─ Catalogue/Http/ParsedSrc  │       │
│  └────────────────────┘ │  ─ Filter, Page, SManga etc  │       │
│                         └──────────────────────────────┘       │
│  ┌────────────────────┐ ┌──────────────────────────────┐       │
│  │  source-local      │ │  source-api (cont.)          │       │
│  │  ─ Local manga src │ │                              │       │
│  │  (CBZ/EPUB/dir)    │ │                              │       │
│  └────────────────────┘ └──────────────────────────────┘       │
└────────────────────────────────────────────────────────────────┘
```

### Module Dependency Graph

```
:app
 ├── :domain
 │    ├── :source-api (KMP)
 │    └── :core:common
 ├── :data
 │    ├── :domain
 │    ├── :source-api
 │    └── :core:common
 ├── :presentation-core
 │    ├── :core:common
 │    └── :i18n
 ├── :presentation-widget
 │    ├── :domain
 │    ├── :presentation-core
 │    ├── :core:common
 │    └── :i18n
 ├── :core-metadata
 │    └── :source-api
 ├── :source-local (KMP)
 │    ├── :source-api
 │    ├── :core:archive
 │    ├── :core:common
 │    ├── :core-metadata
 │    └── :domain
 └── :i18n-at
```

---

## 3. Build Variants

The project uses **two product flavors** with **two build types**:

| Flavor | Build Type | Application ID | Notes |
|--------|-----------|---------------|-------|
| `standard` | Debug | `app.kanade.tachiyomi.at.debug` | Standard debug build |
| `standard` | Release | `app.kanade.tachiyomi.at` | Standard release build |
| `dev` | Debug | `app.kanade.tachiyomi.at.debug` | Dev resources / pseudolocale config |
| `dev` | Release | `app.kanade.tachiyomi.at` | Dev release build |

Flavors do not add their own applicationId suffixes here; build types do.

---

## 4. Translation Pipeline (TachiyomiAT Exclusive)

The automatic translation system is the defining feature of this fork:

```
┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐
│  Detect  │ → │   OCR    │ → │ Translate│ → │ Inpaint  │ → │  Render  │
│  Regions │   │ Extract  │   │   Text   │   │  Clean   │   │  Overlay │
│          │   │  Text    │   │          │   │  Bubble  │   │ Translate│
└──────────┘   └──────────┘   └──────────┘   └──────────┘   └──────────┘
     │              │              │              │              │
  ONNX/Mobile   PaddleOCR v6   ML Kit /       AOT-based      Font-based
  Detect Model   MangaOCR       Gemini /       Inpainting     Text Renderer
                 ML Kit         DeepSeek /
                                 OpenRouter /
                                 LM Studio /
                                 DeepL /
                                 Google Translate
```

**Key components:**
- `eu.kanade.translation.ChapterTranslator` — orchestrates the full pipeline
- `eu.kanade.translation.TranslationManager` — manages translation jobs, auto-prefetch, cancellation
- `eu.kanade.translation.detection.OnnxPageTextDetector` — text region detection via ONNX
- `eu.kanade.translation.ocr.*` — OCR engines (PaddleOCR v6, MangaOCR, ML Kit)
- `eu.kanade.translation.translator.*` — text translators (Gemini, DeepSeek, DeepL, Google, OpenRouter, LM Studio, ML Kit) + shared pure helpers (`TranslationPrompts`, `OcrArtifactSanitizer`, `AiModelFetcher`)
- `eu.kanade.translation.inpainting.AOTInpainting` — AOT-based bubble cleaning; mask/morphology math in `BubbleMaskBuilder`
- `eu.kanade.translation.recognition.BoxGeometry` — shared bbox IoU / geometric-dedupe used by both the detector and OCR stages
- `eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayView` — renders translated text; color policy in `RenderColorEstimator`

> 📖 For the complete file map, the test-coverage table, and the deduplication/
> god-file-split history of this module, see
> [`docs/TRANSLATION_MODULE.md`](./TRANSLATION_MODULE.md).
>
> 📖 For the multi-modal concurrency model, storage tiers, native ML arbitration,
> and lifecycle specifications, see
> [`docs/architecture/translation-subsystem-coexistence.md`](./architecture/translation-subsystem-coexistence.md).

---

## 5. Key Technology Stack

| Layer | Technology |
|-------|-----------|
| **Language** | Kotlin (with Kotlin Multiplatform for source-api and source-local) |
| **UI** | Jetpack Compose + Material3 |
| **DI** | Injekt (compile-time dependency injection) |
| **Database** | SQLDelight (SQLite with coroutine + Flow support) |
| **Paging** | Android Paging 3 |
| **Image Loading** | Coil |
| **Networking** | OkHttp + Okio |
| **HTML Parsing** | Jsoup |
| **Archive Reading** | libarchive + UniFile |
| **OCR** | PaddleOCR (ONNX), ML Kit, MangaOCR |
| **ONNX Runtime** | Custom provider with device capability detection |
| **Widgets** | Compose Glance |
| **Code Quality** | Spotless (formatting), Detekt (lint) |
| **CI/CD** | GitHub Actions |

---

## 6. Package Naming Convention

| Package Prefix | Origin |
|---------------|--------|
| `eu.kanade.tachiyomi` | Original Tachiyomi (legacy) |
| `eu.kanade.translation` | TachiyomiAT translation pipeline |
| `eu.kanade.domain` | App-specific domain layer |
| `tachiyomi.` | Refactored/migrated code from original |
| `mihon.` | Newer code from Mihon fork |
| `app.kanade.tachiyomi.at*` | Final build application ID |

The codebase is in active migration from `eu.kanade.tachiyomi` → `tachiyomi.` / `mihon.` packages.
