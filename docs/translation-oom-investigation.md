# Translation OOM Investigation — Heap-Dump Runbook

> Companion to `TRANSLATION_MODULE.md` Memory contract #11. Captures the
> 2026-06 investigation into the pre-translation OOM on a 200-page chapter,
> what was fixed, what remains unconfirmed, and the exact steps to convert
> the remaining hypothesis into fact.

## Symptom

Pre-translating a long chapter (200 images, MangaOCR + QUALITY inpainting)
fails on every page with `OutOfMemoryError`, on a device whose Java heap is
already at its `largeHeap` ceiling (512 MB = `dalvik.vm.heapsize`). GC frees
only KB per pass (`freed 97(19KB)` in the captured logcat), i.e. the heap is
pinned by strong references GC cannot collect — not by a transient allocation
spike.

Earlier in the chapter (roughly page 20) it had degraded progressively before
failing outright.

## What was fixed (committed)

### ONNX CPU arena + memory-pattern optimizer disabled

`OnnxRuntimeProvider.createSessionOptions` now calls
`setCPUArenaAllocator(false)` + `setMemoryPatternOptimization(false)`. With the
defaults ON, six concurrent ONNX sessions (detector + MangaOcr ×3 + AOT +
cpuFallback) each pre-allocate an arena sized to their largest tensor workspace
and hold it for the process lifetime — the dominant source of runaway native
pressure on the target device class. See `TRANSLATION_MODULE.md` Memory
contract #11 for the full rationale.

This is the verified, low-risk fix. It addresses the **native-heap** pressure
that tripped the `ActivityManager.lowMemory` gates in `canStartAnalyze` /
`canStartInpaint` — i.e. the symptom where pages were deferred as "low memory"
even though the Java heap had headroom.

## What remains UNCONFIRMED (do not guess — capture the dump)

The **Java-heap** retainers that pin the heap at 494/512 MB have NOT been
confirmed by a heap dump. The strong suspects, in priority order, are:

1. `TranslationPipeline.translateBatch`'s `analyzed: MutableMap<String,
   PageTranslation>` — accumulates blocks + `allTextDetections` for the
   ENTIRE chapter across stage 1, then is read by stage 2. For a 200-page
   chapter this is 200 PageTranslation objects retained simultaneously.
2. The matching `inpainted: MutableMap<String, PageTranslation>` in stage 2.
3. `ChapterTranslationStore.snapshotPages()` — emits a full 200-entry copy
   of the persistent map on every `updatePage`, thousands of times per batch.
4. `CoroutineScope(coroutineContext).launch` created per-page in stage 2
   (`translateBatch` line ~772) — a new scope per page is wasteful, though
   the jobs themselves are joined.
5. The `cpuFallbackSession` in `AOTInpainting` — once NNAPI returns zeros
   (which it will, given the captured 10/450 node-support ratio), a second
   full AOT session is created and held until `close()`, doubling the AOT
   model's native footprint.

Items 1–3 are the most likely culprits. **But "likely" is not "confirmed"** —
the honest engineering move is to capture a heap dump and read the actual
retained-shallow-size leaderboard before editing.

## Heap-dump capture procedure

### Prerequisites
- A debug build installed: `app.kanade.tachiyomi.at.debug`
- `verbose_logging` ON (Settings → Advanced) so `logcat()` calls emit
- `translation_diagnostics` ON (Settings → Translation) so `[translation_mem]`
  snapshots fire — these show `heap=…/…MiB avail=…MiB nativeAlloc=…MiB` per
  stage and are the cheapest first signal

### Method A — Android Studio Profiler (preferred, GUI)
1. Open Android Studio → View → Tool Windows → Profiler
2. Attach to `app.kanade.tachiyomi.at.debug`
3. Select the **Memory** timeline
4. Reproduce: open a 200-page chapter → manga screen → "Translate chapter"
5. The moment the first `OutOfMemoryError` fires (watch logcat for
   `Throwing OutOfMemoryError`), click **Capture heap dump** in the Profiler
6. Save the `.hprof` and open it in the Profiler's heap viewer
7. Group by class, sort by **Retained Size** (NOT shallow size), and read the
   top 10 retainers. The leaderboard names the actual culprit.

### Method B — ADB + `am dumpheap` (no GUI)
```sh
# Trigger a heap dump for the running app process (writes to /data/local/tmp)
PID=$(adb shell pidof app.kanade.tachiyomi.at.debug)
adb shell am dumpheap $PID /data/local/tmp/translation-oom.hprof

# Pull it (the file is owned by shell; pull to host)
adb pull /data/local/tmp/translation-oom.hprof

# Convert ART hprof to standard J2SE hprof for MAT/VisualVM
# (Android Studio Profiler reads the ART format directly; for Eclipse MAT:)
hprof-conv translation-oom.hprof translation-oom-std.hprof
```
Then open in Eclipse MAT → "Leak Suspects Report" → "Dominator Tree", sort by
Retained Heap.

### What to look for
- A single `ConcurrentHashMap` / `PersistentMap` holding 200 PageTranslation
  entries → confirms items 1/2/3 (window the metadata per-page, the batch
  analogue of `ReaderPageWarmWindow`).
- `OnnxTensor` / `OrtSession` retained native wrappers → confirms the ONNX
  session set; the arena fix above already addresses the native side, but a
  retained tensor leak would be a separate bug.
- `ByteArray` instances of ~2 MB each (compressed page bytes) retained
  concurrently → confirms the `decodePageBitmapForTranslation`
  `readBytes()`-into-ByteArray peak; the fix is to stream-decode or reuse a
  single buffer.

## Why the committed logcat captures are insufficient

The captured `app_logcat.txt` / `app_pid_log.txt` / `current_logcat*.txt`
were taken with `verbose_logging` OFF, so every `logcat()` call in the
pipeline was a no-op. That's why none of the `[translation_mem]`,
`[translation_analyze]`, `decode_decision`, or `skip_neural` lines appear —
the only translation-relevant signal left in those captures is the raw ART
GC + `Throwing OutOfMemoryError` lines, which prove the heap is exhausted
at the moment of failure but NOT which retainers pin it.

For the next capture, both diagnostic toggles MUST be on.

## "Can we just ask Android for more memory?"

No — not beyond what's already set.
- `AndroidManifest.xml` already has `android:largeHeap="true"`. The 512 MB
  ceiling observed in the logcat IS that enlarged heap (`dalvik.vm.heapsize`
  with largeHeap). `largeHeap` is the **only** app-side lever, and its
  ceiling is set per-device at ROM build time; it cannot be raised
  programmatically (root/system-property edits only).
- The **native heap** (ONNX, NNAPI, `DirectBufferPool`) is NOT bounded by
  `heapsize` — that's the whole point of contract #11 (reducing native
  pressure) and why the arena fix is the first move.
- The one remaining "more memory" lever is architectural: run translation in
  a separate `android:process=":translation"` Service so it gets its own
  512 MB heap independent of the reader/UI process. This is real but heavy
  (IPC for store state, more complex lifecycle) and should only be
  considered AFTER the heap dump identifies whether the leak is fixable in-
  process.

## References
- microsoft/onnxruntime#11627 — `enable_cpu_mem_arena` native-memory impact
- [ONNX Runtime Java API — OrtSession.SessionOptions](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html)
- [ONNX Runtime NNAPI EP docs](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html)
- [Stack Overflow — largeHeap and dalvik.vm.heapsize](https://stackoverflow.com/questions/10557219/setting-application-specific-max-heap-size-for-the-android-dalvik-vm)
