# Android PP-OCRv6 memory and I/O findings

Date: 2026-09-18  
Scope: read-only source inspection of the Android-shaped OCR path in this
worktree. No production code or page binaries were changed.  
Evidence labels: **VERIFIED** = directly visible in source/tests; **STRONG
INFERENCE** = follows from the source contract but needs device/ORT validation;
**ASSUMPTION** = planning estimate; **UNKNOWN/UNTESTED** = not observable in
this host-only investigation.

## Executive result

The active PP-OCRv6 path is already designed around reusable direct FP32 input
buffers and one long-lived `OrtSession` per model. The largest predictable
caller-owned buffer is the PP-OCRv6 detector input: `3*736*736*4 = 6,500,352`
bytes (6.20 MiB) per direct buffer, with a pool cap of two (12.40 MiB). The
recognizer is much smaller (`0.88 MiB` per max-width buffer; 1.76 MiB for its
two-entry pool). The detector's output and scratch arrays add about 2.07 MiB
each, while the detector's padded ARGB bitmap is another 2.07 MiB during
preprocess.

The page path is serialized under `RoiPageRecognitionEngine.nativeGuard`; it
does not retain a page-wide list of Bitmap crops for the Paddle path. It creates
one unpadded and one 12-pixel-padded crop per ROI, runs det/rec, and recycles
both in `finally`. The learned Paddle text-line detector then creates and
recycles one line crop at a time. The practical memory risk is therefore peak
page/crop size plus ORT/provider working memory, not `number_of_regions × crop
bytes` in the current serial path.

The main unverified boundary is ORT's native ownership behavior for a direct
`FloatBuffer` on every Android runtime/EP combination. The code intentionally
keeps the direct buffer alive until the tensor and result close; this is the
correct conservative lifetime. No Android device, QNN/NNAPI execution, native
heap profile, or ORT allocator trace was available here.

## Source-confirmed data path

### Page detector (pre-PP-OCR stage)

`OnnxPageTextDetector.detect` (`app/src/main/java/eu/kanade/translation/detection/OnnxPageTextDetector.kt:56-113`):

1. Allocates/acquires a pooled ARGB_8888 `640x640` bitmap and draws the page
   into it (`1.56 MiB` pixel storage).
2. Acquires a pooled direct `FloatBuffer` with
   `3*640*640*4 = 4,915,200` bytes (`4.69 MiB`), writes RGB NCHW planes, and
   creates an ORT tensor over that buffer.
3. Creates a separate 16-byte direct `LongBuffer` for `orig_target_sizes`.
4. Runs a reused `OrtSession`; result tensors are converted to Kotlin arrays
   (`LongArray`, nested `FloatArray`s) before postprocessing.
5. Closes `Result`, both input tensors, returns the direct buffer, and returns
   the resized bitmap in `finally`.

The input buffer pool is capped at two (`OnnxPageTextDetector.kt:24-30`), so
the caller-owned fixed detector-input pool is bounded at 9.38 MiB. The second
entry is useful only if a future caller overlaps calls; the current OCR page
critical section is serial.

### PP-OCRv6 detector

`PaddleOcrV6DetEngine` (`app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6DetEngine.kt`):

- `initialize` creates one reused session through
  `OnnxRuntimeProvider.createSessionWithFallback` and records the actually
  registered provider (`:67-87`). The model is dynamically shaped in ONNX but
  the Kotlin call fixes it to `[1,3,736,736]` and configures the symbolic
  dimensions to 736 for accelerator graph partitioning (`:75-81`).
- `detectLines` acquires one pooled direct FP32 input buffer, preprocesses one
  `Bitmap`, creates one `OnnxTensor`, calls `session.run`, copies the output
  probability map to a `FloatArray`, postprocesses, then closes result/tensor
  and releases the buffer in `finally` (`:104-179`).
- Preprocess uses a pooled resized ARGB bitmap and pooled `736x736` ARGB bitmap,
  plus reusable `IntArray(736*736)` and `FloatArray(736*736)` scratch arrays
  (`:226-271`). It writes three normalized RGB planes into the direct buffer
  (`:250-261`); there is no OpenCV `Mat` or BGR conversion object.
- The output copy is a `FloatArray(mapWidth*mapHeight)` (`:131-138`). If the
  active non-padded region is smaller than the model map,
  `activeRegion` allocates another `FloatArray(activeWidth*activeHeight)`;
  only the full-map case reuses the original array (`:285-309`).
- `close`, `reclaimPooledMemory`, and `forceReleaseNativeBuffers` explicitly
  clear direct/scratch pools (`:197-218`).

### PP-OCRv6 recognizer

`PaddleOcrV6SmallEngine` (`app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6SmallEngine.kt`):

- One `OrtSession` and dictionary are retained after `initialize` (`:27-65`).
- The direct input pool is sized for the maximum bucket, `3*48*1600*4 =
  921,600` bytes per buffer, max two (`:35-42`). Calls use a limit exposing
  only `[1,3,48,640]` or `[1,3,48,1600]` worth of elements (`:80-89`).
- Preprocess obtains pooled `scaledWidth x 48` and `inputWidth x 48` ARGB
  bitmaps, fills a temporary `IntArray(inputWidth*48)`, and writes RGB NCHW
  directly into the direct FloatBuffer (`:124-170`). The largest temporary
  pixel array and each max-width ARGB bitmap are `307,200` bytes (0.293 MiB).
- `recognizeWithConf` closes the ORT input tensor and result before returning
  the direct buffer (`:70-110`). CTC decoding consumes `output.floatBuffer`
  directly; no output-sized Kotlin copy is made in this class (`:92-97`).
- There is no BGR swap: both the detector and recognizer extract Android ARGB
  channels as R/G/B and write RGB planes. The detector applies ImageNet mean /
  std; the recognizer applies `(value/255 - 0.5)/0.5` (`PaddleOcrV6DetEngine.kt:274-283`,
  `PaddleOcrV6SmallEngine.kt:154-165,173-175`).

### MangaOCR fallback/reference path

`MangaOcrEngine` is not PP-OCRv6, but it is the relevant alternate path for
memory comparison:

- It retains three sessions (encoder, decoder-init, decoder-step), all created
  once in `initialize` (`app/src/main/java/eu/kanade/translation/ocr/MangaOcrEngine.kt:25-97`).
- Its encoder input direct pool is `3*224*224*4 = 602,112` bytes per buffer,
  max two (`:42-45`).
- Each recognition first creates a full-size grayscale ARGB bitmap the size of
  the crop, then a resized bitmap and `224x224` padded bitmap. The temporary
  bitmaps are returned to `BitmapPool` in `finally`; the returned
  `IntArray(224*224)` remains alive through encoder and decoder execution
  (`:339-380`). Thus a large source ROI temporarily has both the original crop
  and a same-size grayscale copy.
- The autoregressive decoder retains pooled K/V caches. Each cache buffer is
  `4*1*4*256*64*4 = 1,048,576` bytes (1.00 MiB), with two entries in each of
  the K and V pools: 4.00 MiB total retained cache capacity (`:38-40`).
- Decoder step `OrtSession.Result`s are closed per step, while the init result
  and cross-attention outputs remain alive for the decode and are closed in the
  outer `finally` (`:160-315`). Their exact native tensor footprint is
  model/runtime dependent and not measured here.

## Bitmap/crop lifetime and concurrency

`RoiPageRecognitionEngine.analyze` holds `nativeGuard` across page detection,
segmentation, and the complete ROI OCR loop (`app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt:267-318`).
This prevents a concurrent `close` from freeing sessions while `OrtSession.run`
is active. It also means the current production path does not execute multiple
PP-OCR input tensors concurrently on one engine.

For the Paddle path, each detection creates:

- one tight `Bitmap.createBitmap` crop;
- one padded `Bitmap.createBitmap` crop (12 pixels each side, clamped);
- optional line crops from `VerticalLineOcr.cropBitmap`;
- optional rotation copies for tall/vertical lines.

The outer crops are recycled in `RoiPageRecognitionEngine.kt:464-512`.
Line crops and rotations are recycled immediately in
`VerticalLineOcr.kt:117-186` and `:239-288`. `VerticalLineOcr.cropBitmap`
clamps coordinates and creates a `1x1` fallback for empty boxes
(`VerticalLineOcr.kt:49-56`).

The non-horizontal batch branch materializes all page crops into a `List` before
calling `recognizeBatchWithConf`, but the default interface implementation is
still `crops.map { recognize(it) }` (`RoiOcrEngine.kt:18-21`). This is bounded by
the current page's ROI count, not chapter length, but can be materially larger
than the Paddle path's one-at-a-time crop lifetime. A future batch implementation
must keep a strict crop-count/byte cap.

`BitmapPool` is keyed by exact dimensions, retains at most three bitmaps per
size and twelve ARGB bitmaps total, and rejects individual ARGB bitmaps above
32 MiB (`domain/src/main/java/tachiyomi/domain/translation/pools/BitmapPool.kt:20-27,44-107`).
It recycles excess entries. This bounds retained pooled Bitmap bytes by policy,
but does not bound the active source page or `Bitmap.createBitmap` crops while
they are in use.

## FloatBuffer, tensor-copy, and native lifetime assessment

### What is verified

- All four ORT-backed image engines use direct `ByteBuffer.asFloatBuffer()`
  pools for image input rather than `FloatBuffer.wrap` (`DirectBufferPool.kt:146-150`;
  consumers cited above).
- The buffer remains referenced until both the input tensor and result are
  closed in each engine's `finally`. This is explicit in
  `OnnxPageTextDetector.kt:107-113`, `PaddleOcrV6DetEngine.kt:175-179`,
  `PaddleOcrV6SmallEngine.kt:106-110`, and `MangaOcrEngine.kt:148-152`.
- `DirectBufferPool` tracks in-use buffers by identity, not mutable
  position/limit-dependent `FloatBuffer.equals`; it resets and returns the
  buffer on release and has explicit `clear` support
  (`domain/src/main/java/tachiyomi/domain/translation/pools/DirectBufferPool.kt:17-85`).
  The regression test specifically guards against a prior stranded-buffer leak
  (`DirectBufferPoolTest.kt:91-139`).
- Sessions are reused; no per-ROI `createSession` call appears in these engines.
  `OnnxRuntimeProvider.environment` is a lazy process-wide `OrtEnvironment`
  (`OnnxRuntimeProvider.kt:9-14`).

### What remains untested

The comments cite an ORT heap-buffer/native-copy leak and motivate direct input
buffers, but this repository does not contain an Android allocator trace proving
that every shipped ORT Android build/EP consumes the direct buffer without an
additional native copy. The safe contract is therefore: keep the direct buffer
alive until tensor/result close, pool only a small fixed number, and measure
PSS/native heap on target devices before increasing concurrency.

`OnnxRuntimeProvider` creates sessions with accelerator fallback and bounded
ORT thread settings (`OnnxRuntimeProvider.kt:202-260,405-463`). Session creation
proves only that a provider registered; the source itself documents that real
execution provenance is established only after a successful run. QNN/NNAPI
working-set size, allocator arenas, graph-partition copies, and DSP/GPU memory
are all **UNKNOWN/UNTESTED** here.

## Capacity estimates

These are byte arithmetic from source constants, not device measurements. MiB is
`bytes / 1,048,576`; ORT graph workspaces, model mappings, Java object headers,
allocator fragmentation, and page bitmaps are excluded unless stated.

| Component | Per active/retained object | Current cap/lifetime | Estimate |
|---|---:|---:|---:|
| Page detector 640x640 ARGB preprocess | 1,638,400 B | one active, pooled | 1.56 MiB |
| Page detector FP32 direct input | 4,915,200 B | pool max 2 | 4.69 MiB each / 9.38 MiB retained |
| PP-OCRv6 det FP32 direct input | 6,500,352 B | pool max 2 | 6.20 MiB each / 12.40 MiB retained |
| PP-OCRv6 det scratch pixels | 2,166,784 B | one engine field | 2.07 MiB |
| PP-OCRv6 det scratch plane | 2,166,784 B | one engine field | 2.07 MiB |
| PP-OCRv6 det output map | 2,166,784 B at 736x736 | one call | 2.07 MiB |
| PP-OCRv6 det padded ARGB | 2,166,784 B | one active, pooled | 2.07 MiB |
| PP-OCRv6 rec FP32 direct input | 921,600 B | pool max 2 | 0.88 MiB each / 1.76 MiB retained |
| PP-OCRv6 rec max-width pixel array | 307,200 B | one call | 0.29 MiB |
| PP-OCRv6 rec max-width ARGB bitmap | 307,200 B | up to two transient bitmaps | 0.59 MiB |
| MangaOCR encoder FP32 direct input | 602,112 B | pool max 2 | 0.57 MiB each / 1.15 MiB retained |
| MangaOCR K + V cache pools | 1,048,576 B each | two pools × max 2 | 4.00 MiB retained |

### Peak scenarios (planning bounds)

- **One PP-OCRv6 det call, excluding source crop and ORT workspace:** direct
  input 6.20 + padded ARGB 2.07 + resized ARGB up to 2.07 + scratch pixels
  2.07 + scratch plane 2.07 + output 2.07 ≈ **16.55 MiB**. A second active
  `det` call would add another direct buffer and temporary arrays, but the
  current `nativeGuard` prevents that on one engine.
- **One PP-OCRv6 rec call, excluding source crop and ORT workspace:** direct
  input 0.88 + two max-width ARGB bitmaps 0.59 + pixel array 0.29 ≈ **1.76
  MiB**. The pool's second direct entry is retained only between calls and is
  not simultaneously active in the current serial path.
- **Retained PP-OCR caller pools after warm-up:** detector input 12.40 + page
  detector input 9.38 + recognizer input 1.76 ≈ **23.54 MiB**, before any
  MangaOCR pools, BitmapPool entries, ORT sessions/arenas, model mappings, or
  page bitmaps. This is a deliberately conservative upper bound because each
  direct pool has two slots even though the current page path serializes work.
- **MangaOCR retained pools after warm-up:** encoder input 1.15 + K/V caches
  4.00 ≈ **5.15 MiB**, before ORT output tensors and source/crop bitmaps.

The `16.55 MiB` det-call estimate is not an OOM threshold. On Android, ORT
native arenas and model/session working sets can dominate it, and a source page
decoded at `W x H` in ARGB_8888 costs `4*W*H` bytes. For example, a 1600x2400
source page is about 14.65 MiB before decoder overhead; its active crop can add
another copy while being recognized.

## Realistic batch/page bounds

The existing fixed corpus has 76 pages and 486 annotated regions according to
`research/findings/bootstrap.md`, while the host pipeline simulation models 64
pages / 447 regions and a mean of 6.984 regions per page. Those artifacts are
useful workload shape, not Android memory measurements. In the shipped Paddle
path, page/chapter length does not multiply input tensor memory because OCR is
serial and tensors/crops are closed per call. It multiplies allocation churn and
thermal time instead.

Recommended initial Android concurrency ceilings (to validate, not silently
assume):

| Mode | In-flight PP-OCR page jobs | In-flight ROI crops | Rationale |
|---|---:|---:|---|
| normal foreground | 1 | 1 per engine | Matches `nativeGuard`; avoids duplicating 6.2 MiB det input and page crops. |
| measured high-memory device | 1 | at most 2 only after PSS/native-heap proof | A second det input alone is +6.2 MiB; no current source benefit justifies it. |
| memory-pressure/thermal fallback | 1 | 1, no pre-materialized batch list | Serialize, shrink/release pools between pages, and avoid speculative crops. |

For the non-horizontal batch branch, cap materialized crops by both count and
bytes (for example, `min(8, available memory budget / estimated crop bytes)`),
but do not adopt a number without instrumenting actual decoded page/ROI sizes.
The current Paddle branch is already safer because it does not queue all ROI
bitmaps.

## Recommended optimization direction

1. **Keep the current direct-buffer/session-reuse design.** It is the correct
   baseline. Do not introduce OpenCV `Mat` or a heap `FloatArray` staging path
   unless a target EP proves it is faster and its lifetime is bounded.
2. **Move crop-to-tensor conversion toward a reusable scratch contract.** The
   current det path already reuses `IntArray`/one-channel `FloatArray`; the rec
   path still allocates `IntArray(inputWidth*48)` per call. A per-engine
   bucketed scratch pool (640 and 1600) can remove that churn while keeping
   buffers single-owner. Do not share mutable scratch across calls unless the
   engine guard is widened.
3. **Pool output maps only if profiling shows pressure.** The det output map is
   ~2.07 MiB and `activeRegion` may allocate a second copy. A reusable output
   scratch/row-copy design could remove this allocation, but it must not retain
   a map while returning `TextLine`s and must remain safe across future
   concurrency.
4. **Treat tensor pooling as shape-bucket pooling, not arbitrary wrapper reuse.**
   The source already pools the backing direct buffers. The safe next step is
   one owner per fixed signature (`det:[1,3,736,736]`, `rec:[1,3,48,640]`,
   `rec:[1,3,48,1600]`) and a short-lived `OnnxTensor` wrapper created/closed
   around that buffer. Reusing an `OnnxTensor` across calls is **UNTESTED** and
   is unsafe if ORT retains the input past `run` or if the wrapper captures a
   mutable position/limit. If a device trace proves synchronous aliasing, a
   fixed-shape tensor wrapper can be experimentally cached with an exclusive
   lease; otherwise the existing wrapper-per-call pattern is the safer
   contract. Do not pool tensors across sessions or execution providers.
5. **Do not batch PP-OCRv6 rec blindly.** The graph supports dynamic batch in
   the pinned host graph, but the Android Kotlin recognizer currently runs one
   crop per `OrtSession.run`, and the vertical-line path creates short-lived
   crops/rotations. Batching would retain multiple Bitmap crops and increase
   accelerator tensor memory; first measure whether launch overhead outweighs
   that pressure on real devices.
6. **Reuse a single cropped pixel source only if semantics remain exact.** A
   native/direct crop-to-tensor routine could eliminate the two intermediate
   resized/padded ARGB bitmaps, but it needs golden parity tests for filtering,
   gray/black padding, channel order, and vertical rotation. This is an
   optimization experiment, not a safe source-only change.
7. **Retain explicit `forceReleaseNativeBuffers` hooks.** The existing
   `RoiPageRecognitionEngine` refuses to drain child pools while `nativeGuard`
   is held (`:960-990`), preventing use-after-free. Any new pool must join that
   guarded release protocol.

## Thermal and memory-pressure fallback

`MemoryPressurePolicy` classifies `TRIM_MEMORY_RUNNING_CRITICAL..LOW` and
`>=TRIM_MEMORY_COMPLETE` as critical, while UI-hidden/background/moderate levels
are benign (`app/src/main/java/eu/kanade/translation/MemoryPressurePolicy.kt:21-73`).
The source contract says benign pressure releases discretionary caches without
cancelling work; critical pressure requeues in-flight batch work. For PP-OCRv6,
the recommended policy is:

- **Benign trim:** call guarded `reclaimPooledMemory` after the current native
  call; clear Bitmap/direct/scratch pools, retain sessions, and continue.
- **Critical trim:** stop admitting new page/crop work, finish or cancel only
  at a safe `nativeGuard` boundary, call `forceReleaseNativeBuffers`, and
  requeue the page. Avoid closing a session from a callback while an ORT call
  is active.
- **Thermal/latency signal:** reduce to one page and one ROI, disable optional
  PP-OCRv6 detector refinement first (fall back to existing Stage-1 boxes and
  single-read recognition), then fall back the recognizer/provider to CPU if
  accelerator failures or thermal throttling are observed. This preserves
  normal manga behavior and avoids unbounded retry/queue growth.
- **Recovery:** rebuild only the cleared pools lazily on the next call; do not
  recreate sessions for ordinary trim. Recreate a session only after an
  execution/provider failure, using the existing honest-label CPU fallback.

These fallback thresholds are recommendations. No device thermal telemetry or
Android `Debug.getNativeHeapAllocatedSize`/PSS trace was collected in this
worktree, so the trigger values and temperature cutoffs remain **UNTESTED**.

## Open measurements required before implementation

1. On representative Android 8+ devices, record Java heap, native heap, PSS,
   and peak RSS around: page decode, Stage-1 detector, PP-OCRv6 det, one rec
   crop, and a 6–12 ROI page. Include CPU, NNAPI, and QNN/HTP where available.
2. Verify whether `OnnxTensor.createTensor(environment, directFloatBuffer,
   shape)` aliases or copies the direct buffer for the exact ORT Android/QNN
   artifact used by the app; inspect native allocator deltas after 100–500
   serial ROI calls.
3. Capture actual page dimensions and ROI dimensions from the fixed corpus
   without copying page binaries into the repository. Use the 95th/99th
   percentile crop area to set a byte-based crop admission cap.
4. Compare current serial rec with fixed-bucket batch sizes 2/4/8 only after
   measuring peak PSS and thermal slope. The host CPU graph smoke test confirms
   model validity, but says nothing about Android EP memory or speed.

## Bottom line

Source evidence supports a single serialized PP-OCRv6 page worker, direct
FP32 input pools capped at two, per-call tensor/result closure, immediate crop
recycling, and guarded pool release on pressure. The best next engineering step
is measurement—not adding OpenCV or broad parallelism—followed by a bounded
scratch/output reuse experiment if native-heap traces show allocation churn as
the remaining bottleneck.
