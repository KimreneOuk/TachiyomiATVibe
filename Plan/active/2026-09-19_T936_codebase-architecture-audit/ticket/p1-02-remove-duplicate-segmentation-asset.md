# Ticket P1-02: Remove duplicate segmentation asset `best_int8.onnx`

**Phase:** 1 — Zero-Risk Purge | **Risk:** Zero | **Type:** Asset deletion + one-line gradle edit

## Evidence (verified in main worktree @ `7262bf4`, 2026-09-19)

```
app/src/main/assets/models/segmentation/best_int8.onnx          3,444,163 bytes
app/src/main/assets/models/segmentation/manga109_bubble_int8.onnx  3,444,163 bytes
SHA-256 (both): 2C80DAB0B9DF4455B40501614EBDF4BAE7A90C3880635635E8D87BAA50CDFA94
```

- Byte-identical duplicate. The runtime asset is `manga109_bubble_int8.onnx`;
  a `findstr /s /i "best_int8"` sweep over `app/src/main` and `app/src/test` returns **zero**
  code references to `best_int8`.
- `app/build.gradle.kts:141` already excludes it from APK packaging
  (`"assets/models/segmentation/best_int8.onnx"`), so it is pure Git dead weight (3.28 MB),
  never shipped.

## Changes

1. `git rm app/src/main/assets/models/segmentation/best_int8.onnx`
2. Remove the now-dead packaging-exclusion entry at `app/build.gradle.kts:141`
   (`"assets/models/segmentation/best_int8.onnx",`).

## Constraints

- **Do not touch** `app/src/main/assets/models/segmentation/manga109_bubble_int8.onnx` (live asset).
- **Do not touch anything under `app/src/main/assets/models/inpainting/`** — `aot-512.onnx` and
  `aot.onnx` are required (QNN HTP static-shape + dynamic-shape inpainting respectively).
- The similarly-named `experimental/models/manga109-segmentation-bubble/best_int8.onnx` is handled
  by Ticket P1-04 (repo bloat), not here.

## Verification

1. `git grep -n "best_int8"` → only hits under `experimental/` (until P1-04 removes those).
2. `./gradlew :app:assembleDebug` — green.
3. Inspect the built APK (`app/build/outputs/apk/debug/`): `manga109_bubble_int8.onnx` present,
   no `best_int8.onnx`, APK size unchanged (asset was excluded from packaging already).

## Commit

`chore(assets): drop byte-identical duplicate best_int8.onnx (3.28 MB) from git`
