# Ticket P1-04: Untrack prototype models & benchmark outputs (~74 MB) from Git

**Phase:** 1 — Zero-Risk Purge | **Risk:** Zero (untrack only — files stay on disk) | **Type:** Git hygiene

## Evidence (verified in main worktree @ `7262bf4`, 2026-09-19)

| Path | Tracked | Size | Nature |
|---|---|---|---|
| `experimental/models/manga109-segmentation-bubble/` | 5 files | 40.4 MB | Obsolete prototype weights (`best.pt`, `best.onnx`, `best_fp32.onnx`, `best_int8.onnx`, `best_int8_broken.onnx`) |
| `tools/aot_corpus/qa_output/` | 45 files | 29.3 MB | Regenerable benchmark QA output images |
| `research/ppocrv6/qnn_compatibility_raw.json` | 1 file | 4.3 MB | Raw dump; derived analysis already lives beside it |

None of these are referenced by app code (`experimental/` and `tools/` are dev-side only;
`qa_output` is produced by the aot corpus tooling).

## Changes

1. Untrack (keep on disk): `git rm -r --cached experimental/tools` — precisely:
   - `git rm -r --cached experimental/`
   - `git rm -r --cached tools/aot_corpus/qa_output/`
   - `git rm --cached research/ppocrv6/qnn_compatibility_raw.json`
2. `.gitignore` additions:

```
/experimental/
/tools/aot_corpus/qa_output/
/research/ppocrv6/qnn_compatibility_raw.json
# local agent working files (untracked clutter in git status)
/AGENT_HANDOFF_*.md
/ARCHITECTURE_LAYERS.md
/BRAINSTORM_HANDOFF.md
/EXECUTION_HANDOFF.md
/_gui_probe/
/_merge_backup_*/
```

## Scope decisions (deliberate)

- `tools/aot_corpus/real_corpus*` / `synthetic_corpus` (~20 MB, 369 files) are benchmark
  INPUTS, not outputs — they stay tracked. If the Director wants them gone too, that is a
  separate decision (they are the reproducibility base for inpainting benchmarks).
- `research/` as a whole was deliberately committed on 2026-09-18 with branch tips tagged
  `archive/*` (commit `4f75665`). Only the 4.3 MB raw JSON is untracked here; the rest stays.
  History for anything untracked remains recoverable from `main` history regardless.
- Files remain on disk after `--cached` removal — no local work is destroyed.

## Verification

1. `git status` — staged deletions are index-only; directories still exist on disk
   (`Test-Path experimental/models` → True).
2. `./gradlew :app:assembleDebug` — green (app never referenced these paths).
3. `git ls-files experimental tools/aot_corpus/qa_output` → empty.

## Commit

`chore(repo): untrack prototype weights and regenerable benchmark outputs (~74 MB)`
