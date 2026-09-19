# T936 Phase 1 — Independent Review Report

Date: 2026-09-19
Reviewer: Independent Reviewer (adversarial verification; evidence re-derived, implementer claims not trusted)
Branch reviewed: `t936/phase1-zero-risk-purge` @ `ffa2c91`, base `main` @ `7262bf4` (merge-base confirmed `7262bf4`)
Commits reviewed: `9fbeb00` → `9286840` → `4c9c50f` → `a53a552` (amended) → `530d0d0` → `ffa2c91`

## Verdict

**PASS WITH NOTES**

No blocking issues. All hard safety invariants hold; every ticket delivered exactly its scoped
change; the build and test evidence in the implementation report was independently reproduced
and matches. Three non-blocking documentation-drift notes below.

## Checklist findings

### A. Ticket conformance — PASS

Per-commit scope verified against the (updated) tickets via `git log main..HEAD --stat` and
`git show` per commit. Full branch diff is exactly 78 files, decomposing into precisely the
expected change classes: 11 Plan docs (9 plan artifacts, 1 amended ticket, 1 report), 1
`.gitignore`, 1 `app/build.gradle.kts`, 1 deleted duplicate asset, 8 deleted test files, 6
R100 renames, 52 untrack deletions (5 `experimental/` weights + 45 `qa_output` files + 1
research JSON). No scope creep, no missed items.

- `9fbeb00` — plan artifacts only (9 files, all under `Plan/`). Pre-arranged plan commit.
- `9286840` — exactly the 8 ticket-named test files (P1-01).
- `4c9c50f` — `best_int8.onnx` deletion + the single packaging-exclude line (P1-02).
- `a53a552` — 6 renames + 2 gradle entries removed + the P1-03 ticket amendment (see C).
- `530d0d0` — `.gitignore` + 51 untrack deletions (P1-04); 52 files in commit.
- `ffa2c91` — implementation report only.

The P1-03 ticket was amended by the implementer (mechanism changed from APK exclude globs to
relocation of the doc files). Reviewed as legitimate: the amendment documents the empirical
finding (AGP `packaging.resources.excludes` globs do not filter `src/main/assets` with this
AGP version; a Dev APK built with the globs still contained all four doc files), the original
mechanism was demonstrably ineffective for the ticket's goal, and the replacement achieves the
ticket's stated goal (docs not packaged) deterministically. The amendment is transparent and
the pre-amendment state is recoverable from git history.

### B. Safety invariants — PASS (all hard-fail checks hold)

Independently verified via `git diff main...HEAD` scoped per path — every diff empty:

- Hardware-routing code untouched: `HardwareDiscoveryEngine`, `OnnxRuntimeProvider`,
  `TranslationPreferences`, `AOTInpainting`, `PaddleOcrSessionFactory`,
  `OnnxBubbleSegmenter`, `QnnDiagnostics`, `domain/` — zero diff.
- `app/src/main/assets/models/inpainting/aot-512.onnx` and `aot.onnx`: tracked, byte-identical
  to main, and both present in the inspected APK (1 entry each).
- `app/src/main/assets/models/segmentation/manga109_bubble_int8.onnx`: tracked, unchanged,
  present in APK. Runtime confirms it is the live asset (`OnnxModelStore.kt:114` copies it to
  `bubble_segmenter.onnx`).
- OCR runtime assets intact and unchanged: both `inference.onnx`, `inference.json`,
  `vocab.txt`, `PP-OCRv6_small_rec.txt` (all present in APK under `assets/models/ocr/`,
  alongside `decoder_init.onnx`, `decoder_step.onnx`, `encoder.onnx`).
- `tools/aot_corpus/real_corpus*` + `synthetic_corpus`: still tracked (380 tracked files under
  `tools/aot_corpus/`). `tools/gpu_isolation/`: zero diff.
- No changes to downloaded manga data or databases (nothing outside the 78-file scope).

### C. P1-03 correction quality — PASS

- All 6 moves are `R100` in `git diff main...HEAD --name-status` — pure renames, 0 content
  delta, structure preserved (`README.md`, `inference.yml`, `.gitattributes` at root and
  `det/` levels) into `docs/models/paddle-v6-small/`. All 6 present on disk at destination.
- `app/build.gradle.kts` combined diff vs main removes exactly three lines: the P1-02
  `best_int8.onnx` exclude and the two OCR doc excludes (`README.md`, `inference.yml`).
  Nothing else. `META-INF/README.md` and `inference.json` excludes retained as required.
  No glob and no `androidResources.ignoreAssetsPattern` added, per amended ticket.
- Reference greps: `git grep 'paddle-v6-small/README'` and `'paddle-v6-small/inference.yml'`
  return no hits under any source set. Remaining hits are Plan documentation, one provenance
  entry in tracked research data, and stale docs (see Note 3). `git grep` for code loading
  `.md`/`.yml`/`.gitattributes` from assets: zero hits under `app/src/main/java` and
  `app/src/test/java`.
- `app/src/main/assets/models/ocr/` now contains 0 files matching `*.md`, `*.yml`,
  `.gitattributes` (recursive filesystem check).

### D. P1-04 hygiene — PASS

- `.gitignore` additions match the ticket block verbatim (3 purge targets + comment + 6
  agent-clutter patterns), nothing more.
- `git ls-files experimental/ tools/aot_corpus/qa_output/
  research/ppocrv6/qnn_compatibility_raw.json` → empty.
- All three purge targets present on disk (`Test-Path` → True/True/True), consistent with
  `--cached`-only removal. Report's note about restoring ignored payloads from `7262bf4`
  before the final check is consistent with observed state.
- Deliberate scope holds: `tools/aot_corpus/real_corpus*`/`synthetic_corpus` and the rest of
  `research/` remain tracked; the 78-file total confirms nothing else was accidentally
  untracked.

### E. P1-01 — PASS

- Exactly the 8 named files deleted under `app/src/test/`; no other test file touched
  (confirmed in both the per-commit stat and the full-branch name-status list).
- Rendering package: 27 suites on `main` → 19 on HEAD (matches report claim).
- `git grep "Superseded by Desktop 1:1 text layout engine port"` under `app/src/test/` → no
  hits (ticket verification step 1 satisfied).

### F. Build evidence — PASS (independently reproduced, full suite NOT re-run)

Inspected `app/build/outputs/apk/dev/debug/app-dev-universal-debug.apk` directly (ZIP central
directory, 2,042 entries):

| Check | Claimed | Measured |
|---|---|---|
| `best_int8.onnx` entries | 0 | **0** |
| OCR `.md`/`.yml`/`.gitattributes` | 0 | **0** |
| `manga109_bubble_int8.onnx` | present | **present (1)** |
| `inference.onnx` entries | 2 | **2** (`paddle-v6-small/inference.onnx`, `det/inference.onnx`) |
| SHA-256 | `1A50A374…D3419F0` | **exact match** |
| Size | 362,755,733 | **exact match** |
| `aot-512.onnx` / `aot.onnx` | (safety) | **1 + 1 present** |

Test results verified from `app/build/test-results/` XML (suite not re-run — no discrepancy
found): `testDevDebugUnitTest` 294 XML files, tests=2079, failures=0, errors=0;
`testStandardDebugUnitTest` 294 XML files, tests=2079, failures=0, errors=0.

### G. Git hygiene — PASS

- Working tree clean; no staged or unstaged leftovers (`git status`, `git diff --cached`,
  `git diff` all empty).
- Branch sits exactly 6 commits on `main` @ `7262bf4` (merge-base verified).
- No secrets tracked: `kilo.json` (gitignored credential file) absent from index; no
  credential/`.env`-pattern files added by the branch.
- Temporary `app/google-services.json` used for Dev Gradle runs is absent from the worktree
  and was never committed; only `app/src/standard/google-services.json` remains tracked.
- Report commit contains only the report file.

## Notes (non-blocking)

1. **Stale doc: `docs/TRANSLATION_OVERLAY.md:33`.** It still states "The packaged asset is
   `models/segmentation/best_int8.onnx`". That file no longer exists after P1-02; the live
   asset is `manga109_bubble_int8.onnx` (`OnnxModelStore.kt:114`). The statement was already
   inaccurate on `main` (the file was packaging-excluded there), so this is pre-existing doc
   drift made staler by the purge — not introduced by this branch and outside P1-02's
   verified scope (zero *code* references). Recommend a one-line docs correction in a later
   phase.
2. **P1-02 verification expectation wording.** Ticket step "git grep best_int8 → only hits
   under experimental/" understates the remaining hits (Plan docs, `docs/`, `tools/*.py`,
   `research/`). The substantive claim — zero references under `app/src/` — holds. Cosmetic.
3. **Provenance references to old paths.** `research/ppocrv6/results/bootstrap.json:622`
   records the old `inference.yml` asset path; historical research data, not a code path.
   Acceptable as-is.

## Conclusion

Phase 1 is a faithful, minimal, evidence-backed execution of the four zero-risk tickets. All
safety invariants verified independently of the implementer's report; the report's claims were
reproduced exactly where reproducible without re-running the suite. Recommend merge readiness
from this reviewer's standpoint, subject to the Director's process gates.
