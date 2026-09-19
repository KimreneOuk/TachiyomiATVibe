# PP-OCRv6 Stage-1 pipeline scheduling findings

Status: **research complete / production implementation not authorized**

The companion simulator is [`research/pipeline_simulation.py`](../pipeline_simulation.py); its raw output is [`research/results/pipeline_simulation.json`](../results/pipeline_simulation.json). The fixed-corpus run is a deterministic discrete-event model for the currently accessible 44 pages and 312 annotated regions. The manifest covers 76 pages, with 32 currently unavailable; those 32 are recorded as coverage metadata and are not presented as runnable inputs (seed `924`; no page binaries are copied). It exercises:

`decode(page N+1) → DET(page N) → crop extraction(page N-1) → width-bucketed REC`

with one worker/session per stage, bounded edges, priority-aware recognition admission, cancellation, durable checkpoints, resume, accelerator selection/fallback, and thermal throttling.

## Evidence boundary

The Android timing profile is **modelled**, not measured. It is an initial budgeting profile (`decode=42 ms/page`, `DET=68 ms/page`, crop extraction `3 + 0.7×regions ms/page`, and a width/batch-dependent REC cost). The page/region counts and width proxies come from the manifest's annotated OCR boxes; they are real-page geometry, not a fresh DET execution. These numbers are intentionally exposed in the raw JSON so they can be replaced by device traces.

The same run records a separate host calibration in [`host_onnx_timing.json`](../results/host_onnx_timing.json). With ONNX Runtime CPU, one thread, two warmups and six measurement iterations, the PP-OCRv6 small det input `(1,3,736,736)` measured a 1,421 ms median; rec `(1,3,48,320)` measured 187 ms, `(4,3,48,320)` 624 ms, and `(8,3,48,320)` 1,507 ms. Width probes measured rec B=1 at 640/960 as 470/391 ms and B=4 at 640/960 as 1,226/2,380 ms. These are **host CPU observations only** and must not be used as Android latency claims. They do confirm that batch size, width and provider/session settings materially affect cost.

Repository evidence also matters: the app documents PP-OCRv6 small det as optional/best-effort, with an ink-gap fallback when the asset or per-ROI call fails; the bundled det graph accepts dynamic batch/height/width input, and rec uses a dynamic width with a 48-pixel height. The simulator therefore treats accelerator availability and det failure as runtime statuses, not build-time assumptions.

## Simulated results

| Policy | Wall time | Complete pages | Relative result |
| --- | ---: | ---: | --- |
| Serial reference, B=1 | 12.271 s | 44/44 | baseline |
| Overlapped, width-bucketed REC | 3.319 s | 44/44 | 3.70× faster than serial model |
| Overlapped, naive mixed-width padding | 3.475 s | 44/44 | 4.7% slower than bucketed |

The result is a scheduling model, not a performance promise. The useful conclusion is directional: overlap removes the page-stage bubble, while width buckets avoid paying the maximum crop width for every crop in a mixed batch.

The queues reached their configured high-water marks (`decode→DET=2`, `DET→crop=2`, `crop→REC=32`) and recorded backpressure events. This is intentional: a chapter-sized producer must not be allowed to materialize every decoded bitmap or crop while REC is the bottleneck.

## Recommended policy

Use the following as the Stage-1 experiment default, behind instrumentation:

| Request class | REC batch cap | Width buckets | Admission rule |
| --- | --- | --- | --- |
| Manual/current page | 1 | 320/640/960/1280/1600 | highest priority; do not wait for a full batch |
| Auto/reader look-ahead | 4 through 640; 2 above 640 | same | may coalesce briefly, but manual work preempts queued auto work |
| Background batch | 8 at 320; 4 at 640/960; 2 at 1280/1600 | same | fill only spare capacity; never hold a manual crop behind a batch |

Bucket by the post-rotation recognition width, rounding up to the next bucket and capping at 1600. Do not combine widths into one padded batch unless the queue is otherwise idle. Enforce both a batch cap and a byte cap (the initial simulator uses a 32-crop edge cap; the Android implementation should additionally cap `B × 3 × 48 × bucket × sizeof(float)` plus crop-object overhead).

Use bounded queues of approximately 2 pages between decode/DET and DET/crop, and a crop queue sized for roughly 2–4 pages (the simulator uses 32 crops as a conservative first value). If the crop queue is full, stop admitting decode work; this is the backpressure boundary that protects the 6 GB minimum-memory target.

## Cancellation, checkpoints and resume

The cancellation run stops at 3.0 s with 39/44 pages fully committed. Its checkpoint records page stage completion, recognized crop count, queue sizes, thermal state and in-flight stage statuses. Resume completes the remaining work in 0.567 s in the model; in-flight work is treated as cancelled and replayed from the last durable stage. That replay is safer than claiming a crop whose REC result was not atomically committed.

Required production semantics:

1. Checkpoint only after the page's DET/crop artifact and each REC result are durable.
2. On cancellation, close producers, drain/abort in-flight sessions, and release decoded bitmaps/crops before publishing the checkpoint.
3. Resume from the first incomplete stage; a page with a durable crop artifact need not rerun DET, while an uncommitted crop batch may be replayed.
4. Preserve request priority on resume: manual/current-page work may jump ahead, but page order remains stable within a request class.

## Accelerator and thermal statuses

The cancellation/resume scenario requests NNAPI while the host simulation declares it unavailable. Both runs report `accelerator_selected=cpu` and `fallback_reason=nnapi_unavailable_in_host_simulation`. Production should expose the same status and keep a CPU path always available; an accelerator failure must not strand a page or leave a queue lease held.

Thermal state is checkpointed. When the configured threshold is crossed, the policy should reduce background REC batch caps first, then pause background admission, while keeping a manual B=1 path responsive. The model uses a 39 °C threshold and a 1.5 °C/s synthetic rise for the cancellation scenario; this is a control-flow test, not a device thermal measurement. Device validation should replace it with skin/SoC temperature and throttling traces.

## Status and next gate

- **Green:** topology, queue bounds, width-bucket selection, priority ordering, cancellation, checkpoint/resume and CPU fallback are represented and reproducible in the host model.
- **Yellow:** Android timing profile, memory overhead, thermal threshold, and actual NNAPI/GPU delegate behaviour are model assumptions.
- **Blocked for production default:** no on-device DET/REC timings or complete 60–70 real-page trace is present in this worktree; the fixed accessible workload is currently 44 pages.

Recommended next action: instrument one representative Android device with per-stage timestamps, queue high-water marks, crop byte counts, provider/accelerator status, and thermal samples over 60–70 real pages. Feed those measurements back into the script before choosing final batch caps or enabling background overlap by default.
