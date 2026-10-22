# Vortex Unified Reader & Translation Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a unified, zero-context-switch floating bottom sheet for reader settings and AI translation, plus redesign batch translation pre-configuration using the Vortex deep-charcoal (`#121214`) and electric-orange (`#FF6600`) design system.

**Architecture:** Create a focused `eu.kanade.presentation.components.vortex` module containing tokens and reusable primitives (header, segmented pills, preset hero bar, selection tiles, slider slates, language pair selector). Unify Reader Setup and AI Engine in `VortexReaderSheet.kt` with live background dimming and wire it into `ReaderActivity.kt`. Upgrade `ConfirmTranslationDialog.kt` to use the same primitives.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 & Foundation), Compose Animation, Tachiyomi Reader Architecture.

## Global Constraints
- Target 200–500 LOC per file, threshold <700 LOC.
- Any comment block is 3 lines maximum.
- All colors must use Vortex tokens (`#121214`, `#1E1E22`, `#2C2C32`, `#FF6600`, `#8E8E93`, `#FFFFFF`).
- Zero regressions in existing settings persistence.

---

### Task 1: Vortex Design Tokens & Visual Primitives
**Files:**
- Create: `app/src/main/java/eu/kanade/presentation/components/vortex/VortexTheme.kt`
- Create: `app/src/main/java/eu/kanade/presentation/components/vortex/VortexComponents.kt`

- [ ] **Step 1: Define `VortexTheme.kt` with color tokens and squircle shapes.**
- [ ] **Step 2: Implement `VortexSheetHeader`, `VortexSegmentedPillBar`, `VortexSelectionTile`, and `VortexSliderSlate`.**
- [ ] **Step 3: Verify compilation with `:app:compileStandardDebugKotlin`.**

---

### Task 2: Language Pair Selector & Master Preset Bar
**Files:**
- Create: `app/src/main/java/eu/kanade/presentation/components/vortex/VortexLanguagePairSelector.kt`
- Create: `app/src/main/java/eu/kanade/presentation/components/vortex/VortexPresetHeroBar.kt`

- [ ] **Step 1: Implement `VortexLanguagePairSelector` with animated `⇄` swap button, frequent quick-chips (`JA → EN`, `KO → EN`, `ZH → EN`, `Auto → EN`), and searchable dropdown cards.**
- [ ] **Step 2: Implement `VortexPresetHeroBar` with `Speed`, `Balanced`, `Quality`, and `Custom` states.**
- [ ] **Step 3: Verify compilation with `:app:compileStandardDebugKotlin`.**

---

### Task 3: Redesign Batch Pre-Configuration (`ConfirmTranslationDialog.kt`)
**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/manga/components/ConfirmTranslationDialog.kt`

- [ ] **Step 1: Replace legacy card layout with Vortex sheet structure.**
- [ ] **Step 2: Embed `VortexPresetHeroBar` and `VortexLanguagePairSelector`.**
- [ ] **Step 3: Update Execution Policy with `VortexSelectionTile` and background switch slate.**
- [ ] **Step 4: Update primary CTA to solid `#FF6600` button.**
- [ ] **Step 5: Verify unit tests and compilation.**

---

### Task 4: Unified Floating Reader Sheet (`VortexReaderSheet.kt`)
**Files:**
- Create: `app/src/main/java/eu/kanade/presentation/reader/VortexReaderSheet.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`

- [ ] **Step 1: Implement `VortexReaderSheet` with 24dp top radius and 55%-65% height.**
- [ ] **Step 2: Implement `ReaderSetupTab` (Reading mode wireframe tiles, Image display tiles, Brightness/Color Filter `VortexSliderSlate` with live 0.0 dimming).**
- [ ] **Step 3: Implement `AiEngineTab` with preset hero bar, language pair selector, translator picker, and fine-tuning drawer.**
- [ ] **Step 4: Wire `VortexReaderSheet` in `ReaderActivity.kt` for both `Settings` and `TranslationSettings` dialog states.**

---

### Task 5: Build APK & Deploy to Device
**Files:**
- Build: `app-standard-arm64-v8a-debug.apk`

- [ ] **Step 1: Run full unit tests (`:app:testStandardDebugUnitTest`).**
- [ ] **Step 2: Assemble APK (`assembleStandardDebug`).**
- [ ] **Step 3: Stream and install APK to `192.168.100.223:43465`.**
- [ ] **Step 4: Launch and verify live appearance on device.**
