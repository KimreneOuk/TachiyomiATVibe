# T936 Phase 1 Implementation Report

Date: 2026-09-19  
Branch: `t936/phase1-zero-risk-purge`  
Base: `7262bf4`

## Result

Phase 1 zero-risk purge is complete. The branch contains the plan commit, four ticket
commits, and this report commit (six commits total after the report is committed).

No NNAPI code, hardware-routing code, inpainting assets, live segmentation assets, downloaded
manga data, databases, benchmark inputs, or `tools/gpu_isolation` files were changed.

## Ticket results

### P1-01 — disabled rendering tests

Deleted the eight rendering suites disabled by the desktop layout-engine port:

- `MissingTextReproTest`
- `TextLayoutPlannerMaskMetadataTest`
- `TextLayoutPlannerFreeTextTest`
- `TextLayoutPlannerFinalSafetyTest`
- `TextLayoutPlannerSlice5Test`
- `TextLayoutPlannerQualityRepairTest`
- `TextLayoutPlannerContainedRescueTest`
- `TextLayoutPlannerShiftCeilingRepairTest`

The baseline source tree contained 27 rendering test files; the final tree contains 19. Both
flavor test reports contain 19 rendering suites and none of the eight deleted suite names.

### P1-02 — duplicate segmentation asset

Deleted the byte-identical duplicate `app/src/main/assets/models/segmentation/best_int8.onnx`
and removed its packaging entry. The live
`app/src/main/assets/models/segmentation/manga109_bubble_int8.onnx` remains tracked and is
present in the APK.

### P1-03 — OCR documentation asset leak

The ticket's original recursive `packaging.resources.excludes` mechanism was empirically
invalid for this Android Gradle Plugin: a Dev APK still contained all four OCR documentation
files after the globs were added. The correction was applied by amending the P1-03 commit:

- Verified with `git grep` that no app source references the documentation paths.
- Moved the four OCR documentation files and both `.gitattributes` sidecars, preserving their
  structure, from `app/src/main/assets/models/ocr/paddle-v6-small/` to
  `docs/models/paddle-v6-small/`.
- Removed the now-dead OCR documentation packaging entries; no global
  `androidResources.ignoreAssetsPattern` was added.
- Kept the OCR ONNX models and the existing `inference.json` exclude unchanged.

The six moved files remain tracked, and no `.md`, `.yml`, or `.gitattributes` remains under the
packaged OCR asset tree.

### P1-04 — repository bloat

Untracked the prototype weights, regenerable `tools/aot_corpus/qa_output/`, and
`research/ppocrv6/qnn_compatibility_raw.json` with `.gitignore` protection. The tracked
`tools/aot_corpus/real_corpus*` benchmark inputs remain present. Final index checks show no
tracked paths under the three purge targets, while `Test-Path` confirms the three target trees
are present on disk. The initial checkout lacked those ignored payloads during verification, so
they were restored from `7262bf4` into the ignored working tree before the final check.

## Verification

All Gradle commands used the Android Studio JBR:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
```

The generic `:app:compileDebugUnitTestKotlin` task is ambiguous in this repository because the
app has `dev` and `standard` flavors. Flavor-qualified tasks were therefore used. The Dev
variant also has no checked-in `google-services.json`; for Dev Gradle runs, the tracked
`app/src/standard/google-services.json` was copied temporarily to `app/google-services.json`
and removed in a `finally` block. The temporary file is absent from the final worktree.

### Compile

```text
./gradlew :app:compileDevDebugUnitTestKotlin :app:compileStandardDebugUnitTestKotlin
BUILD SUCCESSFUL in 1m 44s
```

### Full unit suites

The canonical flavor-qualified invocation was run with one Gradle daemon. Earlier unqualified
load runs exposed pre-existing timing flakes in unrelated end-to-end translation tests
(different failures on different attempts); the affected tests passed when retried in
isolation. The final lower-load single invocation passed both flavors:

```text
./gradlew --no-parallel --max-workers=1 :app:testDevDebugUnitTest :app:testStandardDebugUnitTest
BUILD SUCCESSFUL in 6m 59s
```

Final XML result totals:

| Variant | Tests | Failures | Errors |
|---|---:|---:|---:|
| `testDevDebugUnitTest` | 2,079 | 0 | 0 |
| `testStandardDebugUnitTest` | 2,079 | 0 | 0 |

### APK build and inspection

```text
./gradlew :app:assembleDevDebug
BUILD SUCCESSFUL in 1m 42s
```

APK inspected: `app/build/outputs/apk/dev/debug/app-dev-universal-debug.apk`  
Size: `362,755,733` bytes  
SHA-256: `1A50A37436E462824A228DB077CD193BA1A0094A122B9A3634011E339D3419F0`

ZIP inspection results:

| Check | Result |
|---|---:|
| `best_int8.onnx` entries | 0 |
| OCR `.md` / `.yml` / `.gitattributes` entries | 0 |
| `assets/models/segmentation/manga109_bubble_int8.onnx` | present (1) |
| OCR `inference.onnx` entries | 2 |

The two OCR ONNX entries are the detector and recognizer models:

```text
assets/models/ocr/paddle-v6-small/det/inference.onnx
assets/models/ocr/paddle-v6-small/inference.onnx
```

### Git integrity

```text
git diff --check
git status --short --branch
## t936/phase1-zero-risk-purge
```

The final report commit brings `git rev-list --count 7262bf4..HEAD` to `6`. The six commit
subjects are the plan artifact commit, P1-01, P1-02, amended P1-03, P1-04, and this report.
