---
kind: spec
title: "Measurement Plan (Draft — unverified until device authorization)"
comments: none
---

# Measurement Plan (Draft)

Status: **PLAN ONLY — no device execution authorized this audit** (user decision, see evidence register F9). Every procedure below is designed to run later without code changes, or behind an explicitly authorized minimal instrumentation patch.

## M1. Existing telemetry harvest (zero code change)

1. Connect: `adb connect 192.168.100.223:<port>` (port varies; see `scripts/trace_stream.ps1` default `46123`, observed live `36539`).
2. Stream: `scripts/trace_stream.ps1 -Device <addr>` → noise-free UID-filtered logcat capture.
3. Exercise each mode with a fixed chapter (use `tools/downloader` corpus pages already on disk for matched inputs).
4. Analyze: `python scripts/analyze_latency_budget.py <capture.log>` → native-vision budget compliance + arrival-wait stutter stats.
5. **Reconcile wall time:** `python analyze_wall_budget.py <capture.log> --csv runs.csv` (this directory; host-side; self-tested against the committed event formats) → schedule decomposition (wall vs busy vs itemized waits), per-run residue, stage overlap detection, percentiles. This parses `schedule_end`/`run_end`/`stage_end` — the events that already contain the full wall decomposition (evidence F11).

Events consumed: `budget_summary`, `arrival_wait_recorded` (legacy analyzer); `schedule_start/end`, `run_start/end`, `stage_start/end`, `render_tail_breakdown`, `tap_to_dispatch`, `display_attach` (extended analyzer).

## M2. Reconciliation procedure (the 11.7 s question)

Per page run, reconstruct from the capture:

- `run_end.totalMs` (run scope) − Σ quoted stage durations (`SOURCE_DECODE`, detection, segmentation, OCR, inpaint, translate) = **unattributed residue**.
- Attribute residue from `stage_end` spans the report did not quote: `LEASE_WAIT`, native-lane/permit wait, `governorSpan`, layout, render, `flushSpan`, `commitSpan`, store spans, engine-setup spans.
- Cross-page: check wall-clock overlaps between consecutive page runs (auto mode prefetch overlap) — the r10 anomaly (10.1 s wall, largest OCR) suggests overlap or mid-run truncation; consecutive `run_start`/`run_end` timestamps resolve this definitively.

Decision rule: the gap is "explained" when attributed residue ≥ 90% of (17.3 − 5.6) s across a ≥ 9-page matched chapter, with each attribution supported by span evidence, not interpolation.

## M3. Warm vs cold execution

- Cold: force-stop app (`adb shell am force-stop app.kanade.tachiyomi.vibe.debug`), launch, translate page 1 → captures engine/session init cost, model load, first-inference JIT effects.
- Warm: continue same session pages 2..N → steady-state.
- Report both separately; never mix in averages (the historical report does not state which it measured — a comparability defect).

## M4. Auto-mode smoothness

Already instrumented: `arrival_wait_recorded`. Collect during scrolling at natural reading pace; report P50/P95 wait and stutter rate per chapter.

## M5. Batch chapter throughput

- Whole chapter, record schedule-level events: `schedule_end`, chapter journal flush spans, per-page run events, resume-after-kill behavior (`am force-stop` mid-chapter, relaunch, verify resume correctness from journal/artifact state).
- Metrics: pages/min sustained, time-to-first-page, time-to-50%-chapter, thermal state (`adb shell dumpsys thermalservice`) sampled every 30 s.

## M6. Resource attribution (only if later authorized)

- Perfetto trace (`adb shell perfetto`) with app sched/GC/binder + `am` + `freq` — no app change needed for system-level view.
- Android Studio Profiler / Java allocation tracker for bitmap churn, if interactive profiling is acceptable.
- ONNX Runtime profiling: ORT enable-profiling session option produces a Chrome-trace JSON per session — requires an authorized app-side toggle only.

## M7. Required capture discipline

- Same chapter file set every session; Wi-Fi-only; device ≥ 50% battery; screen brightness fixed; airplane mode for on-device-only runs (provider translation then fails gracefully — run two variants: online for translate spans, offline for native-path isolation).
- ≥ 3 repetitions per configuration; report median + P95, discard first warm-up run per mode.
- Record app version/commit on device before each session.

## Open items blocking execution

1. User authorization for device workloads (currently withheld).
2. Coordination window with OCR orchestrator (their benchmarks share the device).
3. Host-side analyzer extension for `run_*`/`stage_end`/`schedule_end` events (no app change; safe to build now).
