---
kind: spec
title: "Phase 0 provenance — local model assets and generated arm64 APKs"
comments: none
---

# Capture scope

Baseline source revision: `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`. Capture time: **2026-10-14T20:09:39.7020576+07:00**.

This is a **post-build filesystem snapshot**, not a pre-run asset attestation. It supplements the [build/test record](../build-and-tests). Existing git-ignored assets were read, not fetched, converted, installed or changed. No device or model execution occurred during this capture.

The snapshot contains 13 ONNX/TFLite files under `app/src/main/assets/models`, totalling 236479668 bytes, and the two generated arm64 debug APKs. It does not prove that every listed model was packaged or selected at runtime; configuration, APK contents and device backend selection must be recorded when actual device measurements resume. Other asset types, OCR dictionaries and non-arm64 outputs are not included here.

# Method

Used PowerShell filesystem enumeration and `Get-FileHash -Algorithm SHA256` on existing `.onnx` / `.tflite` files and generated `*arm64*.apk` debug outputs. All results below are actual observed file sizes/hashes. This is read-only provenance work; it supplies no latency, memory or native-lifetime evidence.

# Durable snapshot

Only artifact index files synchronize; the essential provenance is embedded here rather than left only in a machine-local JSON file.

```json
{
  "CapturedAt": "2026-10-14T20:09:39.7020576+07:00",
  "Models": [
    {
      "Path": "app/src/main/assets/models/detection/detector-v4-s_int8.onnx",
      "Bytes": 11120765,
      "Sha256": "5FE9E4F576E49D4E7E8B0E029D6D3CDC252ABD4694113E1CAE120E62C931EA79"
    },
    {
      "Path": "app/src/main/assets/models/inpainting/aot.onnx",
      "Bytes": 23068213,
      "Sha256": "FFD39ED8E2A275869D3B49180D030F0D8B8B9C2C20ED0E099ECD207201F0EADA"
    },
    {
      "Path": "app/src/main/assets/models/inpainting/aot_512_regen_gap_fp16.tflite",
      "Bytes": 11469264,
      "Sha256": "DA17C1CB341D994D54CA5A88A02CF59EF129E2081BFF879FA3136B391B2E0087"
    },
    {
      "Path": "app/src/main/assets/models/inpainting/aot-512.onnx",
      "Bytes": 22854564,
      "Sha256": "B1D0E7C50F95910CF1EA58FAC646699A97CE2EFADF7E2C57AF3785BF0EA4F9EB"
    },
    {
      "Path": "app/src/main/assets/models/inpainting/manga_lama_fused_fp16.tflite",
      "Bytes": 102324796,
      "Sha256": "CB37F4EDC40F7DF953F6F599A2DBB467FC1654D99B4FC42758BE1CAC0252365F"
    },
    {
      "Path": "app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx",
      "Bytes": 962358,
      "Sha256": "8A76C2CCFC71AE68B2A812C00BF8EE917319CB737F7C6E035517C85074C779DF"
    },
    {
      "Path": "app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx",
      "Bytes": 10632516,
      "Sha256": "BDB358216A4715494A7EAB1932A2E10B340231320F846C21CB5D1F9DF7C2DF8C"
    },
    {
      "Path": "app/src/main/assets/models/ocr/paddle-v6-small/litert/manga_det_960_fp16.tflite",
      "Bytes": 945516,
      "Sha256": "B724FE2E1A854620F1E685F0988264CBD1E4953281C55C78F58AE96D69888AFF"
    },
    {
      "Path": "app/src/main/assets/models/ocr/paddle-v6-small/litert/manga_rec_160_fp16.tflite",
      "Bytes": 10619924,
      "Sha256": "31AA784492B265C6E887C41E37BFD0E4DBA542184AB3498FBC61E587D8671BDA"
    },
    {
      "Path": "app/src/main/assets/models/ocr/paddle-v6-small/litert/manga_rec_1600_fp16.tflite",
      "Bytes": 10620216,
      "Sha256": "8200C7F97F0D63B321A29C737337B8A564540689E13F37BF041C983A7BC07E2D"
    },
    {
      "Path": "app/src/main/assets/models/ocr/paddle-v6-small/litert/manga_rec_320_fp16.tflite",
      "Bytes": 10620732,
      "Sha256": "38C845DE888421B3A017E510E7AE23CA36A61C5642D3906A010E6CBBE2926ADD"
    },
    {
      "Path": "app/src/main/assets/models/ocr/paddle-v6-small/litert/manga_rec_480_fp16.tflite",
      "Bytes": 10620120,
      "Sha256": "6AB850DE89C4DD0865E728D8CB1974CFB08A712E21FA9D53FCEA41D1BC48B0BB"
    },
    {
      "Path": "app/src/main/assets/models/ocr/paddle-v6-small/litert/manga_rec_640_fp16.tflite",
      "Bytes": 10620684,
      "Sha256": "A2667F577BF9259F89ACDF73FAC552E92341EA655B5F767A1DAE94DEDFE8BC15"
    }
  ],
  "DebugArm64Apks": [
    {
      "Path": "app/build/outputs/apk/dev/debug/app-dev-arm64-v8a-debug.apk",
      "Bytes": 381582015,
      "Sha256": "E967E054694BEEB18AD468918A82394D6B1D3397FA9E5289856CC75B369E5ED0"
    },
    {
      "Path": "app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk",
      "Bytes": 385619679,
      "Sha256": "D3882447D78AA78752B3420C9F73723D86DD9D956A69C699F3D522D40CFBA062"
    }
  ]
}
```

# Pending measurement inputs

Device identity, workload/page hashes, provider configuration, runtime-selected model/backend, thermal/cold-warm state and actual distributions remain pending. No current device result should be inferred from these APK or model hashes.

