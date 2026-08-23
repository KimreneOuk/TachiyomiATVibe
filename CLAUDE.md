# TachiyomiAT - Project Context & Instructions for Claude

## Overview
TachiyomiAT is a Kotlin Android manga/manhwa/manhua reader based on Mihon with on-device OCR/ONNX detection, inpainting, AI batch translation, and text overlay rendering.

## Current Working Branch
- **Branch**: `optimize_translation_finishing_page`
- **Location**: `C:\Users\User\.gemini\antigravity\worktrees\TachiyomiAT-1.16.8-dev\optimize_translation_finishing_page`

## Build & Test Commands
Use the Gradle wrapper:
```bash
# Build Dev Debug APK
./gradlew assembleDevDebug

# Run Unit Tests
./gradlew :app:testDevDebugUnitTest

# Code Formatting & Verification
./gradlew spotlessCheck
./gradlew spotlessApply
```

## Recent Batch Translation Architecture & Changes
- **Clean Protocol**: AI batch requests emit direct line-oriented prompts (`p0001_b0000|Japanese text`) without verbose `TACHIYOMI_AT_BATCH_*` envelope headers.
- **Contextual Parsing**: `ContextualResponseParser.kt` parses `p0001_b0000|Translated text` directly, with robust recovery for markdown code fences.
- **Fast Resumes**: `PageWorkPlanner.kt` reuses completed `READY` / `TEXTLESS` / `SKIPPED` pages from disk and fast-forwards resumes without re-submitting to LLMs.
- **Live Progress Tracking**: `TranslationBatchProgressTracker.kt` reads committed disk snapshots (`toPageDisplayProjection(committed).displayReady`) for accurate drawer UI percentages.
- **Parallel Startup**: `TranslationPipeline.kt` parallelizes source file fingerprinting across `Dispatchers.IO`.

## Key File Locations
- Pipeline & Coordination: `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
- Batch Chunker & Tracker: `app/src/main/java/eu/kanade/translation/batch/`
- AI Translators & Protocol: `app/src/main/java/eu/kanade/translation/translator/`
- Work Planner: `app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt`
- Storage & Persistence: `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`
- UI Sheets & Dialogs: `app/src/main/java/eu/kanade/presentation/manga/components/`
