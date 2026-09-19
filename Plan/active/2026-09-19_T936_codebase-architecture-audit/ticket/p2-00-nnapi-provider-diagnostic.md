# Ticket P2-00: One-shot NNAPI provider diagnostic (P1-05 Option A evidence)

**Phase:** 2 | **Risk:** Minimal (one log line) | **Type:** Diagnostic instrumentation

## Background

P1-05 deferred the NNAPI excision because the pinned `onnxruntime-android-qnn-1.28.0.aar`
contains `NnapiExecutionProvider` in `libonnxruntime.so`, contradicting the audit's
dead-subsystem premise. Option A (adopted by Director): gather runtime evidence instead of
guessing. This ticket adds the evidence instrument.

## Changes

In `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxRuntimeProvider.kt`, at the
point where the shared `OrtEnvironment` is first initialized (find the environment
lazy/init block), add a single INFO logcat line emitted once per process:

```
[onnx_runtime] compiledProviders=<comma-separated list from OrtEnvironment.getProviders()> 
```

Requirements:
- Use the existing logcat utility/pattern used in that file.
- Wrap in runCatching so a provider query failure logs the failure instead of crashing.
- Zero behavior change otherwise. No new state, no gating, no preference reads.

## Constraints

- This is the ONLY ticket authorized to touch `OnnxRuntimeProvider.kt`, and only additively
  (one log statement + import if needed). Do not refactor, reorder, or "improve" anything
  else in the file.
- Do NOT touch `HardwareDiscoveryEngine`, `AOTInpainting`, or any other NNAPI-adjacent code.

## Verification

1. Both-flavor unit tests green (diagnostic must not break them).
2. `git diff` for the ticket commit shows exactly 1 file, minimal lines added.

## Commit

`feat(diagnostics): log compiled ONNX runtime execution providers once at init`
