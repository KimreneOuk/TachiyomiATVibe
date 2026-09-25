# QAIRT QNN library override experiment (debug builds only)

This directory is git-ignored for `*.so` on purpose. Dropping newer QAIRT SDK
libraries here makes a **debug** build ship a newer QNN runtime than the one
bundled inside the `onnxruntime-android-qnn` AAR — the Gradle
`jniLibs.pickFirsts` rules in `app/build.gradle.kts` resolve the resulting
duplicate in favor of these files. Release builds are unaffected.

## Why

On Snapdragon devices running Android 16 (e.g. OnePlus PKG110 / SM8650),
`QnnDevice_create` fails with `QNN_DEVICE_ERROR_INVALID_CONFIG` even with a
correct `soc_model`, matching unresolved upstream
[onnxruntime-qnn#715](https://github.com/onnxruntime/onnxruntime-qnn/issues/715).
One hypothesis is that the AAR's QNN runtime lacks a SoC-ID table entry for
these firmware combinations; a newer QAIRT runtime may fix device detection.
(An earlier soc_id gap was fixed in QAIRT 2.43.0.)

## What to copy here

From a QAIRT SDK download (https://qpm.qualcomm.com → "Qualcomm AI Runtime SDK"),
`<sdk>/lib/aarch64-android/lib64/`:

- `libQnnHtp.so`
- `libQnnHtpPrepare.so`
- `libQnnSystem.so`
- `libQnnHtpV75Stub.so` (match your SoC's Hexagon arch: SM8650 → V75)
- `libQnnHtpV75Skel.so`

## How to verify

Install the debug build, open a chapter, then check logcat:

    adb logcat -s qnn_diagnostics onnxruntime HardwareDiscoveryEngine

`[qnn_diagnostics] qnnLibs(...)` reports the packaged file sizes (larger than
the AAR's copies means the override took effect), and `probeCombo ... verdict=`
reports whether `deviceCreate` now succeeds per soc_model/htp_arch combo.
