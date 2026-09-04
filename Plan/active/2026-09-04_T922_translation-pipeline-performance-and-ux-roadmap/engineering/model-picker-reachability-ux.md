# Engineering Report: Model Picker UX & Pre-Flight Reachability Architecture

> **Context:** TachiyomiAT AI Model Settings & Translation Admission  
> **Target Files:** [`AiModelPickerWidget.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/presentation/more/settings/widget/AiModelPickerWidget.kt), [`AiModelFetcher.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/translator/providers/AiModelFetcher.kt), [`SettingsTranslationScreen.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt), [`TranslationPipeline.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt)

---

## 1. The Problem: The "Wasted Effort" Pipeline Inversion

Under the current implementation, translation executes sequentially:

```
[Page Image]
     │
     ▼
[Step 1: Text Detection (YOLOv4 / DBNet)] ──► Takes 200 - 450 ms
     │
     ▼
[Step 2: Bubble Segmentation (YOLO11-seg)] ─► Takes 150 - 300 ms
     │
     ▼
[Step 3: OCR Recognition (MangaOCR)] ───────► Takes 500 - 1,200 ms
     │
     ▼
[Step 4: Neural Inpainting (AOT-GAN)] ──────► Takes 1,500 - 3,500 ms
     │
     ▼
[Step 5: Cleaned Image Disk Write] ─────────► Takes 100 - 200 ms
     │
     ▼
[Step 6: Network HTTP Call to AI Model] ────► 💥 401 Unauthorized / 429 Quota / Host Unreachable
```

### Consequences:
1. **Wasted Battery & Heat:** If the user has a typo in their API key, has exhausted their free-tier quota, or is offline, the phone spends **3 to 6 seconds per page** running heavy neural inference for nothing.
2. **Batch Failure Cascade:** In chapter batch translation, dozens of pages might be processed through OCR and inpainting before the first HTTP chunk fails, stranding partial state.
3. **No Immediate Feedback in Settings:** Users have no way to verify whether their API key or custom LM Studio base URL actually works until they open a manga and attempt translation.

---

## 2. Solution Architecture: Dual-Level Reachability Guard

We introduce a two-tier reachability mechanism:
1. **Tier 1 (Interactive Settings):** An immediate "Test Connection" button and live status badge in Settings.
2. **Tier 2 (Pipeline Pre-Flight Gate):** A zero-cost / cached reachability gate that checks endpoint health *before* admitting any native OCR or inpainting work.

```
┌─────────────────────────────────────────────────────────────┐
│                   Batch / Reader Admission                  │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│ Step 0: Endpoint Pre-Flight Gate                            │
│                                                             │
│ • Valid cached reachability token within 5 minutes?         │
│   ├── YES ──► Proceed immediately to native lane (0 ms)     │
│   └── NO  ──► Send lightweight ping (prompt: "1", maxTok: 1)│
│                                                             │
│ • Ping Passed?  ──► Cache token; Proceed to OCR & Inpaint   │
│ • Ping Failed?  ──► HALT PIPELINE IMMEDIATELY!              │
│                     Surface actionable error (401/429/host) │
│                     Zero native compute wasted!             │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. UI / UX Design: Modernized Model Selector

### 3.1 Settings Layout Improvements
In [`SettingsTranslationScreen.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt), the AI Configuration section is updated to include:

1. **Connection Status Pill:**
   - `🟢 Connected (142ms)` — verified working.
   - `🟡 Quota Exceeded (HTTP 429)` — rate limited.
   - `🔴 Invalid Key (HTTP 401)` — authentication failed.
   - `⚪ Offline / Unreachable Host` — network or server down.
2. **"Test Connection" Action:**
   - Triggers an instant probe via [`AiModelFetcher.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/translator/providers/AiModelFetcher.kt) without needing to leave the screen.

### 3.2 Categorized Model Picker Dialog
Update [`AiModelPickerWidget.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/presentation/more/settings/widget/AiModelPickerWidget.kt) to organize models by utility:
- **Recommended for Translation:** Curated fast/cost-effective models (e.g., `gemini-2.0-flash`, `deepseek-chat`, `gpt-4o-mini`).
- **Recently Used Models:** Quick selection of the last 3 active models.
- **Provider Catalog:** Searchable list with capability chips (e.g., `Fast`, `Thinking`, `Vision`).
- **Manual Input:** Fallback for custom or private fine-tuned endpoints.
