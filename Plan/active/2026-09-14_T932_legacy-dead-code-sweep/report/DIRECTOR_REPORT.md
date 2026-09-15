# T932 Director Report — Legacy / dead-code inventory & deletion plan

2026-09-14 · HEAD 9c19ad0 · three reference-proved sweeps (prod / test-tools /
build). Main leader independently verified the critical claims (X_images
liveness end-to-end; requestAutoWindow & AnalysisChunkMapping deadness;
guard violation count; repo weights). DETECTION ONLY — nothing deleted yet.

## Verdict

The codebase is cleaner than the Director's fear. There is no large dead
mass in production: ~370 lines of truly dead code, ~195 MB of stray
repo weight (mostly tracked model binaries + untracked probe output), and
one large LEGITIMATE subsystem — the pre-artifact migration machine
(~1,560 lines + ~40 test methods) — whose fate hangs on a single Director
decision, not on deadness. The T924 zero-legacy discipline held: nothing
references deleted machinery as live API anywhere.

## The one decision (Tier 2)

Do any real devices still carry pre-artifact translated chapters (flat
`X.json` / `X.glossary.json` / flag-era run records on disk)?

- **No (or orphaning acceptable)** → delete the entire migration machine in
  one reviewed batch: LegacyArtifactMigration (501 ln), LegacyArtifactRescue
  (489), LegacyChapterMigrationSource legacy halves (416; open path
  collapses to probe→open), LegacyFlatFileDecoder (80; inline its live Json
  config first), TranslationManager/DurableChapterStatusResolver legacy
  branches, glossary flat fallback, `context/` sweep declaration,
  `flagProfilePipeline` field, `legacyPayloadReference` (+ PageWorkPlanner
  simplification). Consequence on old devices: those chapters simply show
  untranslated (re-translate fresh); NO crash; fresh installs unaffected.
  ~40 test methods retire with the code (LegacyArtifactMigrationTest, 13
  legacy-halves of the store migration test, 2 flag-field refs).
- **Yes** → keep until an announced version boundary, then delete.

Structural fact verified: the legacy flat-file WRITE lane is already gone;
what remains is read/rescue/migration only, executes at most once per old
chapter at first open.

## Tier 0 — delete now (reference-proved, zero risk)

1. `AnalysisChunkMapping.kt` (90 ln) + its test — sole reference is its own
   test (verified).
2. `TranslationScheduler.requestAutoWindow` + `logAutoDecision` (~223 ln,
   self-declared @Deprecated "no live callers" — verified: only its own
   manager delegation, itself uncalled).
3. `TranslationManager.requestAutoWindow` (19 ln).
4. `TranslationPendingRequestStore.add(chapterId, phase, reason)` overload
   (19 ln; live callers use the record overload).
5. `ResumeOrdering.forwardFirstThenBackfill` (16 ln; `naturalOrder` stays).
6. `ChapterArtifactStore.LoadResult.resyncedFromLegacy` (always false).
7. `Page15MockRig.kt` eviction (already EXECUTION_ORDER 0.3).
8. `bat/manga_render_lab.bat` + `.gitignore` overlay_lab rule (target dir
   does not exist).
9. `tools/prototype_*.py` (17) + `requantize_seg_int8.py` (July scratch
   cluster; superseded by translation_studio/mangaocr_lab).
10. `experimental/models/manga109-segmentation-bubble/` — **41 MB tracked**
    binaries referenced only by that scratch cluster (incl. one file its own
    script calls corrupted).
11. Local-only: `_gui_probe/` (**154 MB** untracked, regenerable), empty
    dirs `test_crops/`, `backup-external-data/covers/`, ignored logs.
12. Stale-comment sweep (~15 test KDoc sites — rewrite
    TranslationCoexistenceHarness:102 which falsely lists
    SequentialBatchCoordinator; ~20 main-code FF-01 comment sites; 5 docs
    files describing deleted machinery).

## Tier 1 — delete/refactor after compile proof

- `compose-stablemarker` dep + `leakcanary-android` catalog entry (zero
  usage; one assembleDebug proves).
- CI `build-tools;29.0.3` step (2019 relic; next CI run proves).
- `kotlinx.reflect` demote app→testImplementation (only test usage).
- Duplicated kotlinx-serialization force-pin (keep one enforcement point).
- **Guard debt (corrected count, main-leader verified): 13 `= runBlocking {`
  sites across 9 test files** (1 untracked) — not the 4 previously
  reported. Cure batch: `runBlocking<Unit>` conversions; commit or delete
  ManualRenderProbeBaseline.kt so local/CI guard state converges.
- `ARCHITECTURE_LAYERS.md` root copy: pick canonical home (drift risk).

## Tier 3 — rename, NEVER delete (live but mislabeled)

- **`X_images/` companion directory — LIVE hot path (main-leader verified
  end-to-end)**: written by all four render pipelines
  (CleanedPublication:215, SinglePageOnnxPhase:680,
  SinglePageHttpRenderPhase:602, BatchLaneWorkers:154), validated at
  promotion (ChapterArtifactStore:1221), consumed by the reader
  (ReaderViewModel:855, DownloadPageLoader:189). Deleting = broken
  rendering. It is the live cleaned-image store wearing a legacy name.
- `ContextualRequestProtocol.LEGACY` (the single-page prompt shape —
  rename SINGLE_PAGE); `DisplayBaseReference.legacyLayout` (rename
  companionImageLayout); `TextLayoutPlanner.legacyFitW/H`; "flagged
  lane/run" fossil naming in tests.
- `frozenProfile == null` envelope shape: LIVE degraded mode (profile reuse
  probe failure) — relevant to the 8k plan (token-fit splitting does not
  apply in degraded runs).

## Leave alone (with reasons)

- UPSTREAM (Mihon-inherited; deletion = merge cost or behavior change):
  ExtensionInstaller.LEGACY, legacy storage permission cluster (Android
  8–12 still meaningful), extensions-lib ProGuard keeps (ABI contract),
  preview buildType (confirm unused first), empty consumer-rules files.
- `leakcanary-plumber`: runtime auto-init by design — grep cannot prove it
  dead; policy call.
- `ModelDeployment` legacy-cache rule (~10 ln; cheap insurance).
- Active tools/labs incl. `tools/aot_corpus` (51 MB, deliberate, has
  regeneration scripts).
- CI signing lane guards on repo slug `mannu691/TachiyomiAT` — if the repo
  is pushed under a different owner, releases silently never build;
  confirm intentional.

## Recommended sequencing

T932 Tier 0+1 = the "clean-code batch", executed alongside EXECUTION_ORDER
Phase 0 (they share files: rig, guard cures, stale KDoc). Tier 2 after the
Director answers the migration question. Tier 3 renames ride any later
touch of those files (no dedicated commit).
