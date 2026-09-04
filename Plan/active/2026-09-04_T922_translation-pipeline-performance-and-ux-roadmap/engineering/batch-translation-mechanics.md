# Engineering Report: Batch Translation Mechanics & RPM Governance

> **Context:** TachiyomiAT Chapter Batch Translation Subsystem  
> **Target Files:** [`StreamingChunkPlanner.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/translator/contextual/StreamingChunkPlanner.kt), [`BatchChapterTranslator.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt), [`BatchLaneWorkers.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt)

---

## 1. Architectural Purpose: Why Chunk-by-Chunk?

Chapter batch translation in TachiyomiAT processes pages in sequential chunks via [`StreamingChunkPlanner.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/translator/contextual/StreamingChunkPlanner.kt). 

This design exists to satisfy two hard constraints:

### 1.1 The 15 RPM Rate-Limit Ceiling
- Free and tier-1 tiers of modern AI translation providers (Google Gemini, DeepSeek, OpenRouter) strictly enforce **15 Requests Per Minute (RPM)**.
- If a 45-page chapter were translated page-by-page, the app would fire 45 HTTP requests in ~25 seconds, instantly triggering HTTP 429 quota exhaustion.
- `StreamingChunkPlanner` packs whole pages into token-bounded envelopes (typically **4 to 8 pages per chunk**), reducing the 45-page chapter from 45 HTTP requests to **5–8 requests total**, operating safely below the 15 RPM threshold.

### 1.2 Multi-Page Dialogue Continuity
- Speech bubbles frequently cross panel and page boundaries (e.g., a character speaks a sentence that finishes on the next page).
- When translated page-by-page, models hallucinate or lose track of speaker pronouns (flipping he/she) and honorifics.
- Translating 4–8 pages in a single context window allows the LLM to understand narrative flow, speaker identities, and scene tone.

---

## 2. The Pipelined Concurrency Model

TachiyomiAT does not wait for all 200 pages to be OCR'd before translating, nor does it translate page-by-page. It uses an overlapping **two-lane streaming pipeline**:

```
[Native Lane]   [OCR Page 1..6] ──► [OCR Barrier Hit] ──► [Inpaint Page 1..6 (Concurrent)]
                                           │
                                           ▼ (Chunk 1 Ready)
[AI Lane]                           [AI Translate Chunk 1] ──► [Commit & Render 1..6]
                                           │
[Native Lane]                       [OCR Page 7..12] ──► [Inpaint Page 7..12]
                                           │
                                           ▼ (Chunk 2 Ready)
[AI Lane]                           [AI Translate Chunk 2] ──► [Commit & Render 7..12]
```

1. **Native OCR Lane:** Scans pages sequentially under [`NativeRunQuarantine`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt) to honor bounded Android memory constraints (keeping max 1–2 decoded bitmaps resident in memory).
2. **Streaming Chunk Flush:** As pages pass OCR, `StreamingChunkPlanner.accept()` packs them until the token budget is filled, then flushes a `TranslationContextChunk`.
3. **Pipelined Overlap:** While the network HTTP call for Chunk $N$ is in flight, the native lane runs inpainting on Chunk $N$ and immediately begins OCR on Chunk $N+1$.
4. **Render Join:** When both translation and inpainting are complete for a page, `tryRender` commits the rendered text and marks the page `READY`.
