# PP-OCRv6 CPU optimization findings

Date: 2026-09-18  
Host: Windows AMD64, Python 3.11.0, ONNX Runtime 1.24.1, 12 logical CPUs  
Providers: `CPUExecutionProvider` (XNNPACK is not installed/exposed)

## Scope and protocol

`research/benchmark_cpu.py` measures the Android PP-OCRv6 small detector and
recognizer independently on three real faithful-corpus manga pages. Inputs are
single-image, single-crop calls; there is no batching, FP16, INT8 conversion,
or other precision change. Each row records cold-session and warm-session
inference, load time, p50/p90/p95, RSS, and a per-input correctness signature.

The canonical thread sweep is in [`results/cpu/cpu_benchmark.json`](../results/cpu/cpu_benchmark.json)
and [`results/cpu/cpu_benchmark.csv`](../results/cpu/cpu_benchmark.csv). It covers
intra-op threads 1/2/4/8 with sequential execution, inter-op 1, graph
optimization `all`, CPU arena enabled, and memory pattern enabled. The focused
ORT option sweep is in [`results/cpu_options_final/cpu_benchmark.json`](../results/cpu_options_final/cpu_benchmark.json)
and CSV; it covers basic/extended/all graph optimization, sequential/parallel
execution, inter-op 1/2, arena/memory-pattern toggles, and I/O binding.

The separate end-to-end fixture is in
[`results/cpu_pipeline_fresh/cpu_benchmark.json`](../results/cpu_pipeline_fresh/cpu_benchmark.json).
It is a fresh-process, Android-shaped detector-v4 → PP-OCR det → AOT path. The
optional YOLO segmenter is not an Android asset in this worktree, so it is not
included. AOT is bounded to one 512×512 working tile to avoid measuring an
unbounded full-page allocation; this is a cost fixture, not an image-quality
claim.

## Confirmed component results

Warm p50 from the canonical thread sweep (two samples per configuration):

| intra-op threads | DET p50 (ms) | REC p50 (ms) | DET RSS peak (MB) | REC RSS peak (MB) |
|---:|---:|---:|---:|---:|
| 1 | 4178.7 | 1345.6 | 259.5 | 153.1 |
| 2 | 2291.4 | **477.1** | 260.8 | 154.0 |
| 4 | **1166.1** | 503.7 | 263.0 | 154.6 |
| 8 | 1593.9 | 850.7 | 262.7 | 157.4 |

The stable signal is that one thread is materially slower and four threads is
the best DET point in this run (72% lower p50 than one thread). Eight threads
regresses relative to four on both components on this host, consistent with
oversubscription/contention. Four threads is the conservative shared default;
two threads is a reasonable REC-specific candidate if recognition dominates.

All canonical thread rows pass correctness parity. DET parity rounds only the
raw probability map to 1e-2 before hashing to tolerate harmless ORT graph
rewrite floating-point ulps; REC parity compares exact argmax token IDs.

## ORT option sweep

The option sweep used one real page and one warm sample per row, so its values
are directional and need confirmation before shipping. All rows pass parity.
At eight intra-op threads, notable warm p50 observations were:

| Configuration | DET p50 (ms) | REC p50 (ms) | RSS note |
|---|---:|---:|---|
| sequential + all + arena + mem pattern | 2218.8 | 706.4 | baseline |
| parallel + all + arena + mem pattern | 1848.0 | 738.3 | DET candidate; REC slightly slower |
| inter-op 2 + parallel + all | 1535.9 | 879.4 | promising DET, not REC |
| sequential + extended | 2101.3 | 537.2 | REC candidate |
| sequential + all, arena off | 2482.1 | 780.3 | lower RSS (~96/109 MB), slower DET |
| sequential + all, mem pattern off | 2032.8 | 798.0 | no clear memory win |
| sequential + all, I/O binding | 2558.5 | 936.2 | negative result here |

Graph optimization `all`, arena, and mem-pattern defaults remain the safest
baseline. Parallel/inter-op-2 are candidates for a second run with at least
5–10 warm samples; they are not yet a production recommendation because the
single-page option sweep has high variance.

## End-to-end CPU path and memory interpretation

In a fresh process at eight threads, the bounded pipeline measured:

| Phase | p50 (ms) | RSS peak (MB) |
|---|---:|---:|
| cold session (model load excluded from duration) | 43,522.7 | 654.1 |
| warm session | 35,690.2 | 604.4 |

This completed artifact has one cold and one warm sample; a follow-up attempt
to collect two reused-session warm samples was stopped under host memory
pressure before it could write new output. Treat these pipeline numbers as a
bounded smoke measurement, not a stable percentile estimate.

The component rows are intentionally separate from the pipeline. Pipeline RSS
is the fresh process's peak while three model sessions and AOT intermediates
are resident; it must not be compared to DET/REC RSS as if it were additive
per-call memory. In the component sweep, each process runs multiple variants,
so RSS is a post-session-process measurement and may retain allocator pages.
The `pipeline_fresh` run is the clean first-process RSS reference.

## Negative results and recommendation

No XNNPACK provider is available. I/O binding did not improve this CPU path;
arena-off reduced RSS but slowed DET. More threads did not monotonically help,
and the eight-thread point was worse than four in the representative thread
sweep. No production code should change based on the option sweep alone.

Recommended next step: validate a four-intra-op-thread, sequential, graph-all,
arena-on, mem-pattern-on session policy on-device, then separately A/B the
parallel/inter-op-2 candidate with a 5–10 sample warm run and Android RSS
instrumentation. Keep the AOT pipeline budget separate because its memory and
latency dominate the bounded end-to-end fixture.
