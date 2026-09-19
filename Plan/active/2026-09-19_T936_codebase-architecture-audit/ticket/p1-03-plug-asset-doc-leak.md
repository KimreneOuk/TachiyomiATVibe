# Ticket P1-03: Stop packaging OCR model docs (`.md` / `.yml` / `.gitattributes`) into the APK

**Phase:** 1 — Zero-Risk Purge | **Risk:** Zero (packaging excludes only) | **Type:** Build config edit

## Evidence (verified in main worktree @ `7262bf4`, 2026-09-19)

`app/build.gradle.kts:142-144` excludes only the ROOT copies:

```
142:  "assets/models/ocr/paddle-v6-small/README.md",
144:  "assets/models/ocr/paddle-v6-small/inference.yml",
```

But the model card files also exist one level deeper and currently ride into every APK:

```
app/src/main/assets/models/ocr/paddle-v6-small/det/README.md        16,076 bytes
app/src/main/assets/models/ocr/paddle-v6-small/det/inference.yml       885 bytes
app/src/main/assets/models/ocr/paddle-v6-small/det/.gitattributes    1,519 bytes
```

(~18 KB of documentation leaked per build. The root `.gitattributes` (1,519 bytes) is also not
covered by any existing exclude and may leak as well.)

## Changes

In `app/build.gradle.kts` packaging block, replace the two root-specific paddle entries with
recursive globs covering the whole OCR model tree:

```
"assets/models/ocr/**/*.md",
"assets/models/ocr/**/*.yml",
"assets/models/ocr/**/.gitattributes",
```

Keep the existing unrelated excludes (`META-INF/README.md`, segmentation `best_int8.onnx`
entry is removed separately by Ticket P1-02) untouched.

## Constraints

- Packaging excludes only. Do NOT delete the files from disk or git — they document the models
  in-repo; only the APK must not carry them.
- Do not use an over-broad `**/*.md` at APK scope — scope globs to `assets/models/ocr/` only,
  so future assets outside that tree are unaffected.

## Verification

1. `./gradlew :app:assembleDebug` — green.
2. Unzip/inspect the APK: no `.md`, `.yml`, or `.gitattributes` under `assets/models/ocr/`.
3. `inference.onnx` files (det + recognizer) still present in the APK.

## Commit

`build: exclude OCR model docs and metadata from APK packaging`
