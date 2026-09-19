---
kind: report
title: "Ticket 06 Paddle batching validation handoff"
comments: none
---

# Ticket 06 handoff

## Result

The staged/debug policy, provider matrix seam, strict no-fallback checks,
rolling-p95 thermal downgrade policy, and benchmark wake-lock/foreground lease
are implemented. Production release defaults remain B1. Debug and benchmark
builds may request a larger batch, but the device policy requires a confirmed
device-profile cell and otherwise activates the explicit CPU B1 emergency
fallback.

Follow-up (a) is resolved by setting `filterConfidence=false` for the
detector-missing, non-tall whole-region plan. This matches the old inline
`isUsable()`-only behavior; the detector-present path retains confidence
filtering. `VerticalLineOcrPlanContractTest` covers the low-confidence fixture
contract.

## Device evidence

The only connection attempt was:

```text
adb connect 192.168.100.223:45317
cannot connect ... target machine actively refused it. (10061)
```

All 32 matrix cells (CPU/QNN-GPU/QNN-HTP/NNAPI x B1/B2/B4/B8 x 640/1600),
on-device B1 parity, PSS delta, thermal, responsiveness, manual
page-boundary wait, and sustained 50-page/full-corpus runs are `UNTESTED`.
No Qualcomm result is inferred from host measurements and no combination is
approved. The exact install/start/pull sequence is in the task README and the
benchmark README.

## Verification

- `:app:compileDevDebugKotlin --no-daemon`: `BUILD SUCCESSFUL`, 174 tasks.
- Focused `:app:testDevDebugUnitTest --no-daemon` selectors for coordinator,
  planner, executor, parity, lifecycle, decoder/preprocess, provider
  provenance/configuration, device policy, rolling-p95 policy, and degraded
  whole-region contract: **51 tests, 0 failures, 0 skipped**.
- `:app:compileDevBenchmarkKotlin --no-daemon`: `BUILD SUCCESSFUL`, 174 tasks.
- Host results are `CONFIRMED` only for compilation/JVM behavior; they are not
  device-provider evidence.

## Policy and gate notes

Accelerator matrix cells set strict ORT no-CPU-fallback options and only pass
after real inference records provider provenance and every batch telemetry row
has `lastBatchTelemetry.downgradeReason == null`. The benchmark records actual
provider, telemetry batch sizes, downgrade reasons, PSS delta, thermal status,
peak input/output tensor bytes, and rolling-p95 actions. Long runs hold a partial wake lock and foreground
service; the sampler remains 75 ms. A fresh post-commit run is required before
any promotion.
