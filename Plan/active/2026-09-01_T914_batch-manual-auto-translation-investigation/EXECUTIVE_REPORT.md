# Executive Report: Translation System Architecture, Co-Existence, and Root Cause Analysis (Task T914)

**Prepared for:** Executive Leadership & Decision Makers  
**Prepared by:** Main Leader (Synthesizing Technical Lead & Independent Reviewer Audits)  
**Date:** September 1, 2026  
**Status:** COMPLETE & VERIFIED  

---

## 1. Executive Summary: The Translation Engine in Plain English

TachiyomiAT translates foreign manga/manhwa chapters into the user's native language using on-device Artificial Intelligence (machine learning for text detection, OCR, and inpainting) coupled with cloud-based Large Language Models (Gemini, OpenAI, Claude, DeepSeek).

The system operates across three distinct modes:
1. **Batch Translation (The Overnight Factory):** Translates entire chapters in the background while the user does other things.
2. **Rolling Auto-Translation (The Smart Assistant):** As the reader turns pages, the app predicts what page is coming next and silently prepares it 2–4 pages ahead in real time.
3. **Manual Translation (The On-Demand Button):** The reader taps a button on a specific page to force an immediate translation or fix an error.

### The Big Picture: How They Co-Exist
Think of the translation system like a **restaurant kitchen**:
* **The Shared Bulletin Board (Storage):** Both the background prep cook (Batch) and the live line chef (Reader) post their finished dishes on the exact same bulletin board (`ChapterTranslationStore`). If the Batch cook finishes page 5 while the user is reading page 4, the moment the user turns to page 5, it is already waiting for them.
* **The Single-Burner Stove (Quarantine):** On-device AI (running optical character recognition and cleaning Japanese/Korean text out of speech bubbles) requires heavy phone memory and graphics power. To guarantee the phone never runs out of memory or crashes, there is only **one single-burner stove** (`NativeRunQuarantine`). Batch and Reader politely take turns using this stove.
* **Why Batch Translation Was Broken:** The factory was completely healthy, but the **delivery truck** (the file downloader) dropped the package right before handing it over to the factory door. Specifically, when saving downloaded pages to phone storage, the system failed to confirm whether the files finished renaming from temporary files. The download system falsely thought the chapter was incomplete and aborted before the translation engine ever got to start.

---

## 2. Deep-Dive Subsystem Breakdown

### 2.1 Storage & Artifacts (Where Everything Lives)

| Storage Layer | Physical Location | What Lives Here? | Analogy |
| :--- | :--- | :--- | :--- |
| **Downloaded Chapter** | Local Downloads or `.cbz` archive | Original raw Japanese/Korean manga images (`001.jpg`, `002.jpg`). | The raw ingredients straight from the farm. |
| **Cleaned Images** | Companion Folder (`_images/`) | High-quality WebP images with the original foreign text cleanly erased from speech bubbles. | The prepared canvas, wiped clean and ready for painting. |
| **Translation Data & Boxes** | Companion File (`.manifest.json` / `.json`) | Exact bounding box coordinates of bubbles, original text, translated English text, font sizes, and colors. | The blueprint specifying what text goes into which bubble. |
| **Online Cache** | App internal cache (`cacheDir`) | Temporary 100MB ring-buffer for streaming chapters without saving them permanently. | The kitchen tray for immediate consumption. |

### 2.2 Input/Output (I/O) & Performance Strategy
1. **Memory-Mapped Reading (`mmap`):** When processing downloaded `.cbz` archive files, the app uses zero-copy memory mapping instead of decompressing the whole file into RAM. This keeps RAM usage low even on 60-page webtoon chapters.
2. **Memory Bitmaps vs Disk Spilling:** In-memory cleaned images are capped at 4 pages (max 48 MB). If the background batch runs ahead of the reader, completed images spill to disk (`001.cleaned.webp`) to prevent phone memory exhaustion.
3. **Atomic File Commits:** Artifacts and manifests are saved using a safe rename strategy: write to a temporary file first, then atomically rename. If the battery dies mid-save, data is never corrupted.

### 2.3 Subsystem Ownership & Process Boundaries
* **The Manga Screen (UI):** Owns user triggers, dialogs, and progress tracking.
* **The Downloader:** Owns fetching raw images from the internet and writing them to storage.
* **The Translation Manager & Foreground Service:** Owns background execution, keeps the Android OS from killing the job, and coordinates the queue.
* **The Reader View:** Owns displaying the pages, handling zoom/pan gestures, and drawing the translated text on screen.
* **The Active Chapter Store Registry:** The single source of truth connecting all components.

---

## 3. Concurrency, Race Conditions & Communication

### 3.1 What Happens When Modes Collide?

#### Scenario A: Reading a chapter while it is being Batch Translated in the background
* **Mechanism:** The Reader opens the chapter and connects to the active store.
* **Result:** As the background service completes pages, they pop into the Reader in real time with 0-second delay. There is no duplicate work.

#### Scenario B: User starts Batch Translation on the chapter they are currently reading
* **Mechanism:** Batch translation gracefully stops the Reader's rolling auto-worker and takes over chapter-wide processing.
* **Result:** The Reader continues seamlessly displaying the newly completed pages.

#### Scenario C: User taps Manual Translate on Page 10 while Batch is processing Chapter A
* **Mechanism:** Both requests share the single-burner stove (`NativeRunQuarantine`).
* **Result:** Batch finishes its current 1-second OCR step, yields the stove to the Reader for Page 10, and then resumes Chapter A.

### 3.2 Race Conditions & Current Defenses
1. **Generation Tokens:** Every batch job has a unique generation number. If a user cancels a job and starts a new one, late results from the old job are discarded.
2. **Atomic Write Gates:** If a user manually edits a translated bubble while a batch job finishes in the background, the user's edit is protected from being overwritten.
3. **Queue Rehydration:** If Android kills the app during a batch run, non-terminal RUNNING tasks reset to PENDING on restart, allowing the queue to resume without missing pages.

---

## 4. Lifecycle: Startup, Before, During, and After

```
┌────────────────────────────────────────────────────────────────────────┐
│ 1. APP STARTUP                                                         │
│  - Reconnects to persisted translation queues from disk.               │
│  - Cleans up any orphaned image files older than 30 seconds.           │
│  - Self-heals any tasks that were interrupted by a power cut.          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 2. BEFORE TRANSLATION (Preflight Checks)                               │
│  - Validates AI keys and target language configurations.               │
│  - Verifies all pages are fully downloaded on disk.                    │
│  - Hashes images (fingerprints) to detect if pages changed.            │
│  - Pre-warms AI models in memory for instant execution.                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 3. DURING TRANSLATION (The Assembly Line)                              │
│  - Posts an ongoing Android system notification with a live progress % │
│  - Passes pages through OCR -> Inpaint -> AI Translate -> Render.      │
│  - Monitors phone RAM: pauses prefetching if memory reaches 85% full.   │
│  - Automatically handles cloud AI rate limits (backoff and retry).     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 4. AFTER TRANSLATION (Wrap-up & Release)                               │
│  - Saves the final chapter manifest to disk.                           │
│  - Releases all temporary bitmaps and tensors from RAM.                │
│  - Dismisses the foreground service notification.                      │
│  - Updates the UI checklist: green checkmarks across all pages.        │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Root Cause Analysis: Why Batch Translation Was Broken

Our technical lead and independent reviewer verified the exact failure points:

### 1. The Pre-Download Gate Failure (Primary Root Cause)
* **The Bug:** Batch translation requires downloaded files. When downloading a chapter, the app saves each page as a temporary file (`.tmp`) and renames it to its final name (`001.jpg`). On Android Scoped Storage, this rename operation can silently fail without throwing an error.
* **The Consequence:** The downloader thought 20/20 pages finished, but when checking disk files, it only found 18 valid images. The downloader marked the entire chapter as `ERROR` and refused to hand it off to the batch translator.
* **The Fix:** We enforce a strict checked publication rule (`publishDownloadedFile()`) that validates every rename operation immediately.

### 2. The Reader Flash of Untranslated Text (Visual Glitch)
* **The Bug:** When opening a pre-translated chapter in the Reader, an internal page index defaulted to `-1` instead of `0`.
* **The Consequence:** The reader assumed the page was outside the active reading window, discarded the translated image stream, and decoded the raw Japanese/Korean image first. 1.5 seconds later, it swapped to the translated image, causing a jarring visual flicker.
* **The Fix:** Initialize the page index properly to `0` and load the cleaned image immediately on frame 1.

---

## 6. Strategic Recommendations & Action Plan

1. **Immediate (P0): Complete Downloader Checked Publication:**
   * Finalize the verified fix for SAF temporary file renames so no chapter fails download validation before batch translation starts.
2. **Immediate (P0): Fix Reader Frame 1 Initialization:**
   * Initialize reader landing index to `0` and attach cleaned image streams immediately to eliminate the visual flash.
3. **Near-Term (P1): Polish the Bottom Progress Drawer:**
   * Ensure clear visual phase indicators (Downloading, Translating, Ready) so the user always understands what phase their chapter is in.
