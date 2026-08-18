# NPU Acceleration Architecture — Progress

Branch: `feat/npu-acceleration-and-hardware-discovery`

## 2026-08-18 — Root cause of "NPU never initialized/used correctly" + remediation

### Device evidence (OnePlus PKG110, SM8650 / Snapdragon 8 Gen 3, Android 16 / SDK 36)

`device_logcat.txt` shows, for every QNN session (detector, panel detector,
bubble segmenter, Paddle det, AOT fixed-512):

```
HardwareDiscoveryEngine: [hardware_discovery] QNN HTP probe registered OK
HardwareDiscoveryEngine: [hardware_discovery] Latched Qualcomm QNN HTP route
OrtSession: Successfully added QNN HTP EP
E/onnxruntime: [E:onnxruntime:, qnn_execution_provider.cc:1046 GetCapability]
   QNN SetupBackend failed Failed to create device. Error: QNN_DEVICE_ERROR_INVALID_CONFIG
```

followed by `[inpaint] route=qnn_htp init=ok` and ~4.8 s/crop inpainting —
i.e. the latch and every route label said QNN while **everything ran on the
CPU EP**. `cpu_sample.txt` additionally shows the process fully stalled for
2.5 minutes in an earlier session (unexplained; honest per-stage labels will
make any recurrence visible).

### Root-cause chain (verified against ORT 1.27.0 source + QAIRT docs)

1. **False-positive probe.** `probeQnnHtp()` only called `addQnn()` on a
   throwaway `SessionOptions`. EP registration cannot fail this way:
   `libQnnHtp.so` is loaded and `QnnDevice_create()` runs only inside
   `createSession` (`GetCapability` → `SetupBackend`), where failure is
   **non-fatal** — nodes silently fall back to CPU. Identical failure shape as
   unresolved upstream [onnxruntime-qnn#715](https://github.com/onnxruntime/onnxruntime-qnn/issues/715)
   (same artifact family, Android 16, same source lines; reporter
   brute-forced all `soc_model` ints with no luck; ExecuTorch's string-based
   soc override works on the same hardware, pointing at QAIRT SoC-ID
   autodetection).
2. **`soc_model=57` was actually correct** (`QNN_SOC_MODEL_SM8650 = 57` per
   QAIRT `QnnTypes.h`) — but two neighbors were wrong (`SM8475→36` should be
   42; `SM8845→97` doesn't exist in the enum) and `htp_arch` was never passed.
   For SM8650 the arch `75` **is** parseable by ORT 1.27 (`0/68/69/73/75/81`)
   and populates the separate `QNN_HTP_DEVICE_CONFIG_OPTION_ARCH` device
   config — the one upstream-untested lever that may bypass broken SoC
   autodetection (v79/SM8750 is unparseable; PR #31638 unmerged).
3. **Context-binary caching never worked**: `qnn_context_cache_enable`/
   `qnn_context_cache_path` are not provider options in ORT 1.27 (silently
   ignored). Caching requires the `ep.context_enable` +
   `ep.context_file_path` session config entries with a per-model file path.
4. **The CPU fallback route is fiction on this artifact**:
   `onnxruntime-android-qnn` compiles neither XNNPACK
   (`XNNPACK execution provider is not supported in this build`) nor NNAPI
   (`nnapi_provider_not_compiled`). "CPU_XNNPACK" routes are the plain CPU EP.

### Remediation implemented (this branch)

- `DeviceCapability`: soc table corrected against QAIRT `QnnTypes.h`
  (+`SM8350/8635/8850/7435/…`); new `qnnHtpArch` (`SM8650→"75"`,
  `SM8550→"73"`, `SM8450/8475→"69"`, `SM8350→"68"`; SM8750 deliberately null
  — v79 unparseable in ORT 1.27).
- `QnnProbeModel` (new): embedded 91-byte single-Relu ONNX (onnx 1.20,
  round-trip validated) + rewritten end-to-end probes
  (`soc+arch → soc → autodetect` combos, strict `session.disable_cpu_ep_fallback`),
  for both QNN and NNAPI.
- `OnnxRuntimeProvider`: `buildQnnProviderOptions()` single source of truth;
  strict QNN/NNAPI options everywhere; `ep.context_*` config entries replace
  the pseudo provider options; per-model context cache files; provider
  labels surfaced via `providerSink`.
- Honest fallback semantics: per-model strict failures retry that model on
  CPU **without** tripping the breaker (device health is probe-gated);
  breaker still trips on EP registration failures and runtime QNN execution
  failures (`AOTInpainting.qnn_execution_failed`).
- Honest logs: `[translation_perf] … providers(detector=…, segmenter=…,
  ocr=…)`, `inpaintRoute=…`, `[inpaint] route=fixed init=ok provider=CPU`
  when XNNPACK is absent; engines expose `executionProviderLabel`.
- `QnnDiagnostics` (debug builds): one-shot `[qnn_diagnostics]` report —
  device facts, ORT compiled providers, packaged QNN lib inventory, probe
  combo matrix, real-model strict session with context-cache reload timing.
- Settings → Translation → Hardware acceleration picker (AUTO / Qualcomm NPU
  / CPU; NNAPI hidden — not compiled in this artifact).
- QAIRT override experiment: `app/src/debug/jniLibs/arm64-v8a/` +
  `jniLibs.pickFirsts` (see its README).
- Tests: `DeviceCapabilityTest`, `QnnProviderOptionsTest`, `QnnProbeModelTest`.

### Upstream watch (as of 2026-08-18)

- Issue #715 was filed **2026-08-11** (one week ago); still open, assigned to
  two Qualcomm engineers (qti-mbadnara, quic-calvnguy).
- **PR [#736](https://github.com/onnxruntime/onnxruntime-qnn/pull/736)**
  (`dev/qti-mbadnara/fix-mobile-invalid-config`) targets it: `soc_model`
  accepts chip-family name strings ("SM8750", case-insensitive) in addition to
  numeric IDs, adds HTP v79 parsing, and confirms the root cause — on Android
  ORT's `GetSocId()` always returns 0 (Windows/PPTT-only), so QNN autodetection
  cannot work and soc_model must be set manually. Status: open, one approval,
  unresolved review comments; no target release yet.
- The standalone `onnxruntime-qnn` repo is now where QNN EP fixes land:
  v2.3.0 (2026-06-22) added an Android Maven package; v2.4.0 (2026-07-14)
  bundles **QAIRT 2.48.40** (far newer than the classic
  `onnxruntime-android-qnn` AAR's runtime). The plugin package requires
  standard ORT 1.24.1+ with explicit EP registration — not a drop-in swap for
  our `addQnn(Map)` AAR usage.
- **Action when v2.5.0 (or any release containing #736) ships its Android
  package**: migrate the artifact and pass `soc_model` by name string; that is
  the most likely true fix for Android-16 Snapdragons. The v2.4.0 Android
  package (QAIRT 2.48) is also worth a spike as a "newer QAIRT runtime" test
  that replaces the manual jniLibs override — coordinates not yet confirmed on
  Maven Central's stale index; check the repo's README at that time.

### Device verification playbook (pending — requires the OnePlus)

**RESULT 2026-08-18 18:17 — ran on the OnePlus PKG110 (SM8650, SDK 36), `npu_live_logcat.txt`:**

- `[qnn_diagnostics] device=… socModel=SM8650 … qnnSocModel=57 qnnHtpArch=75`,
  `ortProviders=[CPU, QNN]` (confirms the AAR compiles no XNNPACK/NNAPI).
- **All probe combos failed, including `soc_model=57 + htp_arch=75`** — every
  attempt hits the same native `QNN SetupBackend failed Failed to create
  device. Error: QNN_DEVICE_ERROR_INVALID_CONFIG`; strict mode surfaced it as
  `ORT_FAIL … fallback to CPU EP has been explicitly disabled`. The
  `htp_arch=75` bypass hypothesis is **disproven**: the bug (#715) affects
  SM8650/Android 16 exactly like SM8750P, and no provider-option combination
  in ORT 1.27 can fix `QnnDevice_create` here. Upstream PR #736 / newer QAIRT
  is the only remaining path.
- The honesty machinery worked end-to-end: probe latched
  `All QNN HTP probe combos failed; HTP unusable with this device/runtime
  combination` → `route=CPU_XNNPACK`; `[translation_perf]` shows
  `providers(detector=cpu, segmenter=cpu, ocr=cpu)`; `[inpaint] … init=ok
  provider=CPU`; pages translated normally (recognition 743–2919 ms/page;
  AOT crop ~4.8 s — the known CPU cost).
- Minor diagnostics gap: `qnnLibs()` printed empty because modern APK
  packaging keeps `.so` inside the APK (`extractNativeLibs=false`), so
  `nativeLibraryDir.listFiles()` sees nothing. The libs demonstrably loaded
  (the failure came from inside libQnnHtp's deviceCreate). Cosmetic — could
  read APK zip entries instead.

1. Build/install debug, open a chapter, then
   `adb logcat -s qnn_diagnostics HardwareDiscoveryEngine onnxruntime AOTInpainting`.
2. Success criteria for NPU engagement: a `probeCombo … verdict=OK`, no
   `QNN SetupBackend failed`, `[inpaint] route=fixed_qnn_htp accepted` times
   drop from ~4800 ms toward sub-second, `.qnnctx.bin` files appear under
   `tachiyomiat-models/qnn-cache/`, and `providers(...)` show `qnn_htp`.
3. If all combos still fail (upstream #715 outcome): the latch honestly goes
   CPU with truthful logs — that is correct behavior, not a regression.
   Then try in order:
   - QAIRT lib override per `app/src/debug/jniLibs/arm64-v8a/README.md`;
   - ORT bump spike: set `onnxruntime-android-version = "1.29.0"` in
     `gradle/libs.versions.toml` (latest; #715 reporter saw the identical
     failure on 1.28.0, so odds are low) and re-run diagnostics.
4. Record outcomes here.

### Follow-ups deliberately deferred

- Multi-crop tensor batching (`[B,3,224,224]` etc.): every bundled model is
  batch=1; needs re-exported models and only pays off once HTP engages.
- MangaOCR encoder on HTP (fp16) — decoder loop stays CPU by design.
- Partial-partition QNN sessions (mixed QNN+CPU nodes) — current strict
  policy trades that optimization for truthful labels; revisit with profiling
  data from `QnnDiagnostics`.
