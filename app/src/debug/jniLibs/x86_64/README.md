# ONNX Runtime x86_64 override (debug builds only)

This directory is git-ignored for `*.so` on purpose. Dropping the stock
ONNX Runtime libraries here lets a **debug** build run the translation
pipeline on x86_64 emulators: the dependency
`com.microsoft.onnxruntime:onnxruntime-android-qnn` (see
`gradle/libs.versions.toml`) ships **arm64-v8a native libraries only**, so
x86_64 builds otherwise crash at engine init with
`UnsatisfiedLinkError: libonnxruntime4j_jni.so not found`.

## What to copy here

From Maven Central, artifact
`com.microsoft.onnxruntime:onnxruntime-android:<version matching the qnn AAR
in gradle/libs.versions.toml>` (AAR), extract:

- `jni/x86_64/libonnxruntime.so`
- `jni/x86_64/libonnxruntime4j_jni.so`

The version MUST match the qnn AAR version: the Java classes come from the
qnn AAR, so the JNI surface of these overrides has to match (verified for
1.27.0 — full OCR/detect/translate pipeline runs on the emulator,
`route=CPU_XNNPACK`).

## How to verify

    adb logcat -s RoiPageRecognitionEngine OnnxPanelDetector OnnxRuntimeProvider

A working run logs `OnnxRuntimeProvider: Creating ONNX Runtime environment`
with no `UnsatisfiedLinkError`, and `RoiPageRecognitionEngine` reports
`providers(detector=cpu, segmenter=cpu, ocr=cpu)`.

Release builds and physical arm64 devices are unaffected (the qnn AAR supplies
their libraries).
