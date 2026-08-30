# Repo Structure Audit (root QC)

- Date: 2026-08-30
- Branch: `optimize_translation_finishing_page`
- Working tree: CLEAN (`git status --porcelain` empty; onnx_check*/ dirs are empty so git does not report them)
- Method: read-only. Sizes via `du` (working tree) and `git ls-tree -r -l HEAD` (tracked payload). "Disk" includes ignored build outputs; "Git" is the tracked payload.

## 1. Inventory

Legend: Class = GRADLE-MODULE / BUILD-ARTIFACT / DOC / ACTIVE-DEV / SCRATCH-PROBE / TOOLING / AGENT-STATE / OTHER.

| Entry | Git state | Disk | Git payload | Files | Last commit | Class | Purpose (verified) |
|---|---|---|---|---|---|---|---|
| .editorconfig | tracked | 1K | <1K | 1 | 2026-06-11 | OTHER | editor formatting config |
| .github | tracked | 4.8M | 4.7M | 13 | 2026-06-11 | DOC | CI workflows (17K) + 4 PNGs of 1.2 MB each (raw/mlkit/gt/gemini) used by README |
| .gitattributes | tracked | 1K | <1K | 1 | 2026-08-24 | OTHER | git attrs |
| .gitignore | tracked | 1K | <1K | 1 | 2026-07-18 | OTHER | ignore rules (see gaps below) |
| .gradle | ignored (.gitignore:2) | 41M | 0 | 18 | — | BUILD-ARTIFACT | gradle cache |
| .idea | tracked (only icon.svg) | 137K | <1K | 19 | 2026-06-11 | OTHER | IDE dir; `!.idea/icon.svg` whitelist works as intended |
| .kotlin | ignored (.gitignore:3) | 52K | 0 | 3 | — | BUILD-ARTIFACT | kotlin session cache |
| .superpowers | ignored (.gitignore:34) | 63K | 0 | 22 | — | AGENT-STATE | local agent runtime |
| .zcode | ignored (.gitignore:32) | 96K | 0 | 9 | — | AGENT-STATE | local agent runtime |
| AGENT.md | tracked | 4K | <1K | 1 | 2026-07-16 | DOC | agent instructions |
| AGENTS.md | tracked | 4K | <1K | 1 | 2026-08-28 | DOC | workspace agent guide (referenced by tooling) |
| CHANGELOG.md | tracked | 40K | 37K | 1 | 2026-06-11 | DOC | upstream changelog |
| CLAUDE.md | tracked | 4K | <1K | 1 | 2026-08-23 | DOC | agent instructions |
| CODE_OF_CONDUCT.md | tracked | 8K | 5K | 1 | 2026-06-11 | DOC | doc |
| CONTRIBUTING.md | tracked | 4K | 2K | 1 | 2026-06-11 | DOC | doc |
| LICENSE | tracked | 12K | 10K | 1 | 2026-06-11 | DOC | license |
| Plan/ | tracked | 2.0M | 1.6M | 135 | 2026-08-29 | DOC | task plans + reports (active process area) |
| README.md | tracked | 8K | 5K | 1 | 2026-06-11 | DOC | doc |
| _build.bat | tracked | 1K | <1K | 1 | 2026-08-24 | TOOLING | gradle build helper |
| _build_install.bat | tracked | 1K | <1K | 1 | 2026-08-24 | TOOLING | build + adb install helper |
| _compile.bat | tracked | 1K | <1K | 1 | 2026-08-24 | TOOLING | compile helper |
| _verify.bat | tracked | 1K | <1K | 1 | 2026-06-18 | TOOLING / SCRATCH | adb verification script with a HARDCODED device serial (192.168.100.207:37625) |
| app/ | tracked | 3.7G (3.3G = app/build, ignored) | 334M | 14205 (on disk) | 2026-08-29 | GRADLE-MODULE (:app) | main app; 193M of tracked ONNX models under src/main/assets |
| backup-external-data/ | tracked | 100K | 0.1M | 2 | 2026-06-18 | SCRATCH-PROBE | two ~50 KB files named `*.apk` (real APKs are 10-100x larger — truncated/stub data files) used for external-backup restore testing |
| bat/ | tracked | 8K | <1K | 1 | 2026-07-12 | TOOLING | `manga_render_lab.bat` — one-file server helper for companion_server |
| build/ | ignored (.gitignore:4) | 405K | 0 | 8 | — | BUILD-ARTIFACT | root build output |
| build.gradle.kts | tracked | 1K | 1K | 1 | 2026-08-17 | OTHER | root build script |
| buildSrc/ | tracked | 19M (17M = buildSrc/build, ignored) | <1K | 2048 on disk (2022 are build outputs) | 2026-08-24 | GRADLE-MODULE (implicit) | convention plugins |
| companion_server/ | tracked | 749K | 0.6M | 55 | 2026-07-12 | ACTIVE-DEV (server) | Python translation server (detector/OCR/inpaint pipeline); has own README + start.bat; referenced by docs/TRANSLATION_OVERLAY.md, docs/experimental_notes/, bat/manga_render_lab.bat |
| core/ | tracked | 10M (mostly ignored build) | 0.1M | 989 on disk | 2026-06-11 | GRADLE-MODULE (:core:archive, :core:common) | core modules |
| core-metadata/ | tracked | 3.9M | <1K | 385 on disk | 2026-06-11 | GRADLE-MODULE (:core-metadata) | metadata module |
| data/ | tracked | 12M (mostly ignored build) | 0.1M | 1179 on disk | 2026-06-11 | GRADLE-MODULE (:data) | data layer |
| docs/ | tracked | 612K | 0.5M | 36 | 2026-08-29 | DOC | architecture/translation docs; contains stray `Untitled-1.txt` (committed gradle console dump) |
| domain/ | tracked | 21M (mostly ignored build) | 0.2M | 2047 on disk | 2026-08-29 | GRADLE-MODULE (:domain) | domain layer |
| experimental/ | tracked | 41M | 40.4M | 5 | 2026-07-12 | SCRATCH-PROBE | ONLY 5 model binaries: best.pt (11.4M), best.onnx (11.3M), best_fp32.onnx (11.1M), best_int8.onnx (3.3M), best_int8_broken.onnx (3.2M). No code, no README. `best_int8_broken.onnx` is by name a failed artifact |
| gradle/ | tracked | 69K | 0.1M | 6 | 2026-08-24 | TOOLING | wrapper jar/props + 3 version catalogs |
| gradle.properties | tracked | 1K | 1K | 1 | 2026-08-24 | OTHER | build props |
| gradlew / gradlew.bat | tracked | 12K/4K | | 2 | 2026-06-11 | TOOLING | wrapper |
| i18n/ | tracked | 48M (mostly ignored build) | 3.6M | 782 on disk | 2026-06-11 | GRADLE-MODULE (:i18n) | upstream strings |
| i18n-at/ | tracked | 1.4M | <1M | 144 | 2026-08-24 | GRADLE-MODULE (:i18n-at) | AT-specific strings |
| kilo.json | tracked | 1K | <1K | 1 | 2026-07-02 | OTHER / SCRATCH | **Kilo AI MCP config with a HARDCODED API KEY committed at root** |
| local.properties | ignored (.gitignore:13) | 1K | 0 | 1 | — | BUILD-ARTIFACT | local SDK path |
| macrobenchmark/ | tracked | 17K | <1K | 5 | 2026-06-11 | GRADLE-MODULE (:macrobenchmark) | benchmark module |
| onnx_check/ | UNTRACKED, NOT ignored | 0 | 0 | 0 | never | SCRATCH-PROBE | completely EMPTY dir (Jun 17); never in git history |
| onnx_check2/ | UNTRACKED, NOT ignored | 0 | 0 | 0 | never | SCRATCH-PROBE | completely EMPTY dir (Jun 17); never in git history |
| overlay_lab/ | tracked | 1.9M | 1.7M | 34 | 2026-07-12 | SCRATCH-PROBE (functional lab) | Python backend + JS frontend web lab for overlay/inpaint experiments; own README; debug PNG outputs correctly ignored; referenced by docs/experimental_notes/text_overlay_rendering.md |
| prefs-device.xml | tracked | 4K | <1K | 1 | 2026-06-18 | SCRATCH-PROBE | device preferences dump at root (test-fixture material) |
| prefs.xml | tracked | 4K | <1K | 1 | 2026-06-16 | SCRATCH-PROBE | app preferences dump at root |
| presentation-core/ | tracked | 11M (mostly ignored build) | 0.2M | 640 on disk | 2026-06-11 | GRADLE-MODULE (:presentation-core) | presentation layer |
| presentation-widget/ | tracked | 5.2M | 0.4M | 257 on disk | 2026-06-11 | GRADLE-MODULE (:presentation-widget) | widget |
| progress.md | tracked | 20K | 19K | 1 | 2026-07-15 | SCRATCH-PROBE | stale progress log ("Branch: fix-translation-pipeline", last updated 2026-07-15); superseded by Plan/ |
| settings.gradle.kts | tracked | 1K | 1K | 1 | 2026-06-11 | OTHER | includes 14 modules |
| source-api/ | tracked | 5.3M | <1M | 553 on disk | 2026-06-11 | GRADLE-MODULE (:source-api) | source API |
| source-local/ | tracked | 2.7M | <1M | 262 on disk | 2026-06-11 | GRADLE-MODULE (:source-local) | local source |
| tools/ | tracked | 51M | 49.9M | 446 (all tracked) | 2026-08-18 | TOOLING + heavy test data | prototype python scripts (17 `prototype_*.py`) + `aot_corpus/` = 51M of QA/corpus PNGs (qa_output alone 30M) |

Notes:
- Several GRADLE-MODULE dirs (i18n, domain, core, data, presentation-*) show inflated `du` numbers purely from ignored `build/` outputs — the tracked payload is small. Not a problem.
- `git ls-files | grep -E "(^|/)build/"` → 0 matches. NO build artifacts are tracked.

## 2. app/src tree sanity

Source sets present (file counts, all non-empty): `main` (904), `test` (468), `debug` (4), `standard` (3), `dev` (1), `androidTest` (1).

- `standard`/`dev` are the product flavors (upstream Mihon layout): standard = AndroidManifest + google-services.json + FirebaseConfig.kt; dev = stub FirebaseConfig.kt. Expected.
- `debug` = launcher icon drawables + `jniLibs/arm64-v8a/` containing only a `.gitignore` + README placeholder (intentional empty-dir keep). Expected.
- `androidTest` = 1 file (PageTextRendererInstrumentedTest.kt). Thin but not empty — matches the earlier test-orphans report scope; not a structure issue.
- Nothing unexpected, nothing empty. No audit of file contents performed (out of scope).

## 3. Red flags

1. **SECRET COMMITTED: `kilo.json` (root, tracked, 2026-07-02)** — Kilo AI MCP launcher config containing a hardcoded `Z_AI_API_KEY` value in plaintext. `.gitignore` covers `.kilo/` but NOT `kilo.json`. The key is in all clones/forks of this history. Rotate the key; remove the file; add `kilo.json` to .gitignore. HIGHEST priority.
2. **`tools/aot_corpus/` = ~51 MB of tracked PNGs** (qa_output 30 MB — single files up to ~1 MB each). This is QA visual output / test corpus, not source. It is the second-largest contributor to repo payload after app assets.
3. **`experimental/` = 40.4 MB of tracked model binaries with zero code** — includes an explicit failure artifact (`best_int8_broken.onnx`, 3.2 MB) and near-duplicates of models already shipped under `app/src/main/assets/models/`. 5 files, no README, untouched since 2026-07-12.
4. **`app/src/main/assets/models/` = 193 MB tracked ONNX** — aot-512.onnx (58.6 MB), ocr decoder_init/decoder_step/encoder (84 MB), detection/segmentation (~10 MB). These are shipping assets so they are functional, but they dominate clone size; consider LFS or slimming if repo weight matters.
5. **`docs/Untitled-1.txt`** — accidentally committed console output (CLIXML header, gradle task log from a different machine path `C:/Users/ADMIN/...`). Clear accidental commit.
6. **`_verify.bat`** — hardcoded LAN device serial `192.168.100.207:37625` and a debug applicationId; local-environment config in git.
7. **Root clutter tracked in git**: `prefs.xml`, `prefs-device.xml` (preference dumps), `progress.md` (stale one-off log pointing at a dead branch).
8. **`backup-external-data/`** — two ~50 KB files named `*.apk` (v1.4.19 / v1.4.37). Genuine APKs of those apps are orders of magnitude larger, so these are stub/truncated data files committed to the repo root under a vague name.
9. **`onnx_check/`, `onnx_check2/`** — empty, untracked, and NOT matched by .gitignore. Clutter; git cannot see them precisely because they are empty.
10. **.gitignore gaps**: no rules for `kilo.json`, `prefs*.xml`, `progress.md`, `onnx_check*/`, `backup-external-data/`. The existing rules for `build`, agent dirs, logs, and `overlay_lab/debug_*.png` are otherwise sound and correctly scoped.
11. Verified clean: no tracked `build/` outputs; `.gradle`, `.kotlin`, `.idea` (except icon.svg), agent dirs, `local.properties` all correctly ignored; `google-services.json` under `app/src/standard` is standard Android practice.

## 4. Scratch/probe verdicts + recommendations

| Entry | Tracked? | Size | Last touched | Deletion cost | Recommendation |
|---|---|---|---|---|---|
| onnx_check/ | No (never) | 0 B, empty | Jun 17 (dir mtime) | NONE — zero history/data loss | DELETE now. Zero risk. |
| onnx_check2/ | No (never) | 0 B, empty | Jun 17 (dir mtime) | NONE | DELETE now. Zero risk. |
| experimental/ | Yes | 41M (40.4M in git) | 2026-07-12 | Blobs stay recoverable in history; future clones shrink by ~40 MB | ARCHIVE the 2 production-relevant models (best_int8.onnx → already duplicated as app asset `segmentation/best_int8.onnx`? verify byte-identity first), then `git rm`. Definitely drop `best_int8_broken.onnx`. Low risk; no code references it. |
| backup-external-data/ | Yes | 100K | 2026-06-18 | History retains files; future clones shrink trivially | MOVE out of repo (Director's local backup location) + `git rm` + ignore rule. Low risk. |
| prefs.xml / prefs-device.xml | Yes | 4K each | 2026-06-16 / 06-18 | History retains | Move to `tools/` fixtures or delete + ignore. Low risk. |
| progress.md | Yes | 20K | 2026-07-15 | History retains | DELETE (superseded by Plan/active task READMEs). Low risk. |
| docs/Untitled-1.txt | Yes | <1K | — | History retains | DELETE. Accidental commit. Zero risk. |
| kilo.json | Yes | 1K | 2026-07-02 | History retains (secret stays in history — rotate key regardless) | `git rm` + ignore + ROTATE THE API KEY. Urgent. |
| companion_server/ | Yes | 749K, 55 files | 2026-07-12 | Real loss of the serving counterpart of the translation pipeline | KEEP. Documented in docs/ and driven by bat/manga_render_lab.bat. Not scratch — it is the server side of AT translation. Optionally relocate under `tools/` later. |
| overlay_lab/ | Yes | 1.9M, 34 files | 2026-07-12 | Real loss of the visual debugging lab | KEEP as dev lab (its generated PNGs are already ignored). Optionally relocate under `tools/` later. |
| tools/ | Yes (446/446 files) | 51M (49.9M in git) | 2026-08-18 | Scripts = small; corpus PNGs = 49.9M of history | KEEP scripts; ARCHIVE `tools/aot_corpus/qa_output/` (30M) out of git (regenerable QA output). Medium value, low risk. |
| _verify.bat / _build*.bat / _compile.bat / bat/ | Yes | <1K each | 06-18 to 08-24 | Trivial | KEEP (genuine tooling), but scrub the hardcoded device serial from `_verify.bat` (make it a parameter). Low risk. |

### Priority-ordered action list (recommendations only — nothing was changed)
1. Rotate the Z_AI API key, `git rm kilo.json`, add `kilo.json` to .gitignore.
2. `rmdir onnx_check onnx_check2` (zero-cost).
3. `git rm docs/Untitled-1.txt progress.md` (+ optional prefs.xml / prefs-device.xml, backup-external-data/), with .gitignore additions.
4. Archive `experimental/` models (drop `best_int8_broken.onnx`) and `tools/aot_corpus/qa_output/` out of git; consider LFS for `app/src/main/assets/models/`.
5. Parameterize `_verify.bat` device serial.
