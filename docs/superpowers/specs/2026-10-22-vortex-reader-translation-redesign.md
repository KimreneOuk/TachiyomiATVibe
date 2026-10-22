# Design Specification: Vortex Unified Reader & Translation Settings Redesign

**Date:** 2026-10-22  
**Target:** TachiyomiATVibe Reader & Batch Translation Subsystems  
**Theme:** Vortex Deep-Charcoal (`#121214`) with Electric Orange (`#FF6600`) Accents & Zero-Context-Switch Live Feedback

---

## 1. Problem Statement & Objectives

1. **Context Fragmentation:** Previously, reader visual settings (`ReaderSettingsSheet`) and live translation settings (`TranslationSettingsSheet`) existed as two separate sheets in the reader overlay. Switching between reading mode / brightness and translation models forced closing one sheet and opening another.
2. **Visual Inconsistency:** Reader settings and batch translation dialogs used disparate layouts (standard Material 3 sheets vs cards vs alert dialogs).
3. **Language Selection Friction:** Selecting source and target languages required opening dropdown menus without one-tap quick pairs or instant swap affordances.
4. **Master Presets:** Users had to configure OCR, inpainting, and translation engines separately instead of having 1-tap master profiles (`Speed`, `Balanced`, `Quality`).

---

## 2. Design System Tokens (`VortexTheme.kt`)

Package: `eu.kanade.presentation.components.vortex`

| Token | Hex Value | Application |
|---|---|---|
| `CanvasDimmer` | `Color(0x00, 0x00, 0x00, 0.65f)` | Manga page backdrop dimming during sheet display |
| `CanvasDimmerZero` | `Color(0x00, 0x00, 0x00, 0.0f)` | Transparent backdrop during brightness/filter adjustment |
| `SheetBackground` | `Color(0xFF121214)` | Primary floating modal body |
| `CardFill` | `Color(0xFF1E1E22)` | Tiles, containers, slider tracks |
| `CardBorder` | `Color(0xFF2C2C32)` | 1dp boundary edge stroke |
| `AccentPrimary` | `Color(0xFFFF6600)` | Active chips, slider active tracks, CTA buttons |
| `AccentGlow` | `Color(0x40FF6600)` | 4dp ambient glow under active elements |
| `TextPrimary` | `Color(0xFFFFFFFF)` | Headlines, active chip labels |
| `TextMuted` | `Color(0xFF8E8E93)` | 11sp uppercase category labels, secondary descriptors |
| `TextOnAccent` | `Color(0xFF000000)` | High-contrast black on electric orange |

---

## 3. UI Component Architecture

### 3.1. Reusable Primitives (`VortexComponents.kt`)
1. **`VortexSheetHeader`**: Squircle badge with 15% orange tint, bold title, squircle close button.
2. **`VortexSegmentedPillBar`**: Two-tab capsule switcher: `[ 📖 Reader Setup ]` and `[ 文A AI Engine ]` with smooth crossfade.
3. **`VortexPresetHeroBar`**: 3 master profiles (`Speed`, `Balanced`, `Quality`) plus dynamic `Custom` indicator.
   - `Speed`: Paddle OCR + AOT 512 Inpainting + Fast/Standard Translation
   - `Balanced`: Paddle OCR + LaMa Fused Inpainting + Standard/Balanced AI
   - `Quality`: Manga-OCR + High-Res LaMa Inpainting + High-Precision AI
4. **`VortexSelectionTile`**: 14dp squircle buttons with active electric orange fill and soft shadow.
5. **`VortexSliderSlate`**: Grouped dark capsule (`#1E1E22`) with left icon, active orange track, and `#121214` numeric value pill.
6. **`VortexLanguagePairSelector`**:
   - Frequent language pair quick-chips: `[ JA → EN ]`, `[ KO → EN ]`, `[ ZH → EN ]`, `[ Auto → EN ]`
   - Side-by-side searchable dropdown cards
   - Center animated swap button (`⇄`) with 180° rotation feedback

### 3.2. Reader Floating Sheet (`VortexReaderSheet.kt`)
- Anchored floating bottom sheet (55%–65% screen height, 24dp top radius).
- Contains `VortexSegmentedPillBar`.
- **Reader Setup Tab**:
  - Reading Modes (wireframe tiles)
  - Image Display (Fit Width, Fit Height, etc.)
  - Display Adjustments (Brightness, Color Filter) with auto-dimming to `0.0` on slider interaction
  - Navigation Gestures
- **AI Engine Tab**:
  - Master Preset Hero Bar (`Speed`, `Balanced`, `Quality`)
  - Fluid Language Pair Selector
  - Active Engine & Model Selector
  - Pipeline Switches (Auto-translate, Inpainting)
  - Fine-Tuning Drawer (OCR, Keys, Cache Resets)

### 3.3. Batch Pre-Configuration (`ConfirmTranslationDialog.kt`)
- Replaces legacy dialog layout with full Vortex design system.
- Direct reuse of `VortexPresetHeroBar` and `VortexLanguagePairSelector`.
- Execution Policy visual selection tiles (`Standard` vs `While Charging`).
- Background execution switch slate.
- Solid `#FF6600` action CTA: `Start Batch Translation (N Chapters)`.

---

## 4. State Management & Quality Gates
- All settings directly mutate underlying SharedPreferences / DataStore via existing ScreenModels (`ReaderSettingsScreenModel`, `TranslationPreferences`, `ReaderViewModel`).
- Zero architectural regressions; strict conformance to `AGENTS.md` (<500 LOC per file, 3-line max comment rule).
- Verification with `:app:compileStandardDebugKotlin` and `:app:testStandardDebugUnitTest`.
