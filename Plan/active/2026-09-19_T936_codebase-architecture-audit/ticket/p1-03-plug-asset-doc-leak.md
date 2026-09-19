# Ticket P1-03: Stop packaging OCR model docs (`.md` / `.yml` / `.gitattributes`) into the APK

**Phase:** 1 — Zero-Risk Purge | **Risk:** Zero (tracked documentation relocation only) | **Type:** Asset relocation/build config edit

## Evidence (verified in main worktree @ `7262bf4`, 2026-09-19)

`app/build.gradle.kts` previously excluded only the ROOT copies:

```
142:  "assets/models/ocr/paddle-v6-small/README.md",
144:  "assets/models/ocr/paddle-v6-small/inference.yml",
```

The model card files also exist one level deeper and ride into every APK:

```
app/src/main/assets/models/ocr/paddle-v6-small/det/README.md        16,076 bytes
app/src/main/assets/models/ocr/paddle-v6-small/det/inference.yml       885 bytes
app/src/main/assets/models/ocr/paddle-v6-small/det/.gitattributes    1,519 bytes
```

(~18 KB of documentation leaked per build. The root `.gitattributes` (1,519 bytes) and its
`det/` counterpart are also not covered by any existing exclude and may leak as well.)

The planned `packaging.resources.excludes` recursive globs were verified empirically and do not
filter files from `src/main/assets` with this Android Gradle Plugin: a Dev APK built with those
globs still contained all four documentation files. `androidResources.ignoreAssetsPattern` is
also unsuitable here because it is global/name-pattern based and would replace the safety
defaults rather than provide a scoped asset-tree filter.

## Changes

Before moving, verify that no app source-set code references the documentation paths. Then move
the six documentation/metadata files out of the packaged asset tree while preserving their model
structure:

```
app/src/main/assets/models/ocr/paddle-v6-small/README.md
  -> docs/models/paddle-v6-small/README.md
app/src/main/assets/models/ocr/paddle-v6-small/inference.yml
  -> docs/models/paddle-v6-small/inference.yml
app/src/main/assets/models/ocr/paddle-v6-small/.gitattributes
  -> docs/models/paddle-v6-small/.gitattributes
app/src/main/assets/models/ocr/paddle-v6-small/det/README.md
  -> docs/models/paddle-v6-small/det/README.md
app/src/main/assets/models/ocr/paddle-v6-small/det/inference.yml
  -> docs/models/paddle-v6-small/det/inference.yml
app/src/main/assets/models/ocr/paddle-v6-small/det/.gitattributes
  -> docs/models/paddle-v6-small/det/.gitattributes
```

Remove the now-dead OCR documentation entries from `app/build.gradle.kts`; do not add a
replacement glob or `androidResources.ignoreAssetsPattern`. Keep unrelated excludes (including
`META-INF/README.md` and the OCR `inference.json` entry) untouched.

## Constraints

- Keep all six files tracked in the repository; only their location changes, and they must not
  remain under `app/src/main/assets`.
- Do not use an over-broad APK-scope pattern or `androidResources.ignoreAssetsPattern`; the
  deterministic fix is to keep documentation outside packaged Android assets.

## Verification

1. Confirm the pre-move source grep has no documentation-path references.
2. `./gradlew :app:assembleDebug` (or the repo's flavor-qualified equivalent) — green.
3. Unzip/inspect the APK: no `.md`, `.yml`, or `.gitattributes` under `assets/models/ocr/`.
4. `inference.onnx` files (det + recognizer) still present in the APK.

## Commit

`build: exclude OCR model docs and metadata from APK packaging`
