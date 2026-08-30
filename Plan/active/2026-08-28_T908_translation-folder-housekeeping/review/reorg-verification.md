# Reorganization Verification — T908 (independent review)

- Reviewer: independent Reviewer agent
- Date: 2026-08-30
- Range: `984e778..5bc5f3e` (branch `optimize_translation_finishing_page`)
- Method: scripted pure-move diffing (python over `git show` blobs), regex
  sweeps over the working tree, XML result parsing, fresh gradle runs.
- No repo files were modified; only this report was created.

## OVERALL VERDICT: REORGANIZATION VERIFIED

All six checklist items PASS. Zero code-level defects found. Three low/informational
findings, all documentation-side, none introduced by the reorg's code changes.

---

## Item 1 — Pure-move integrity: PASS

`git diff --name-status 984e778 HEAD -- app/src` = **154 entries**: 106 renames (R065–R099),
43 modified-in-place, 5 deleted, 0 added.

Normalization applied to both sides of every pair: strip `package`/`import` lines,
replace `AITranslator`→`AiTranslator`, strip `\r`, trim trailing whitespace and
leading/trailing blank lines.

- **Renamed pairs: 106 checked.** 105/106 byte-identical after normalization.
  The 1 residual (`test/.../batch/ChunkTranslationPayloadTest.kt →
  translator/contextual/ChunkTranslationPayloadTest.kt`) differs only in
  fully-qualified body references (`eu.kanade.translation.translator.X` →
  `eu.kanade.translation.translator.contextual.X`).
- **Modified-in-place: 43 checked.** 34 fully identical after normalization; 9
  contain only fully-qualified path rewrites (`eu.kanade.translation.batch.` →
  `eu.kanade.translation.pipeline.batch.`, `translator.AiChunkOutcome` →
  `translator.retry.`, `translator.ContextualRequestProtocol` → `.contextual.`,
  `translator.classifyProviderFailure` → `.retry.`, incl. two KDoc `[...]` refs).
- **Decisive proof:** re-applying the inverse package-path mapping to the NEW side
  of every residual diff yields **0 unexplained diffs across all 149 affected
  files**. Every content change in the range is a pure package-path rewrite.
  Zero logic changes.
- Low-similarity renames audited individually: 65% `AITranslator.kt →
  providers/AiTranslator.kt` (deliberate rename; normalized-identical), 74%
  `detection/Detection.kt → model/Detection.kt` (imports/package only) — both clean.
- The 5 deletions are exactly the removed `remote/` package (4 main + 1 test),
  per commit e811759. Expected.

**Residual non-mechanical diffs: NONE.**

## Item 2 — Stale-reference sweep: PASS

Scanned 1007 files under `app/src` (`.kt/.kts/.java/.xml`):

| Pattern | Hits |
|---|---|
| `eu.kanade.translation.batch.` (old) | 0 |
| `translation.legacy` | 0 |
| `translation.remote` | 0 |
| `AITranslator` (anywhere in app/src) | 0 |
| `overlay_lab` / `companion_server` / `companionServer` | 0 |
| old-style `inpainting.<MovedSym>` imports / qualified body refs | 0 |
| old-style `translator.<MovedSym>` for the 29 moved classes | 0 |

- All 137 `import eu.kanade.translation.translator.<Sym>` references resolve to
  symbols declared in the 8 root contract files (verified programmatically,
  including generic top-level functions `withProviderRequestPriority` /
  `currentProviderRequestPriority` in `ProviderRequestGovernor.kt`).
- The 8 `inpainting.*` subpackage imports are all NEW-path (`inpainting.aot.*` /
  `inpainting.bubble.*`) — correct post-move locations.
- `AITranslator` across the whole HEAD tree: 5 files —
  `docs/superpowers/plans/2026-08-19-*.md` (2, dated historical, acceptable),
  and 3 `Plan/active/2026-08-28_T908_.../` work artifacts (progress.md,
  package-grouping-audit.md, discarded-worktree-snapshot patch) — historical
  task records, informational only (see F3).

## Item 3 — Dependency direction: PASS (1 minor doc-precision finding)

- (a) `translator/` root = **8** files (AiTranslatorKind, ProviderRequestGovernor,
  StandardTranslatorKind, TextTranslator, TextTranslatorLanguage,
  TranslationBlockValidation, TranslationEngineBuilder, TranslatorComputeClass);
  `providers/` = **14**; `contextual/` = **11**; `retry/` = **4**. Total 37 —
  matches the commit message. ✓
- (b) `inpainting/` root = exactly **InpaintingMode.kt, PageInpaintingEngine.kt,
  PageInpaintingPlanner.kt**; `aot/` = **13**; `bubble/` = **5**. ✓
- (c) Inpainting cross-cluster edges (from imports; non-import qualified refs: 0):
  - aot→bubble: `AOTInpainting`→`BubbleMaskBuilder` + `SmartBubbleTextCleaner`;
    **and** `AotReportBubbleFill`→`BubbleMaskBuilder` (extra vs. the claim —
    same direction, pre-existing same-package dependency surfaced by the split; finding F2)
  - bubble→aot: `BubbleCleanerMath`→`AotOutputGuard` ✓
- (d) Cycles: item 1's zero-unexplained-diffs result proves **no new class-level
  dependency was introduced** — every import maps 1:1 to a pre-existing dependency.
  Post-split package-level mutual edges exist inside `translator` (root↔providers,
  root↔retry, providers↔contextual, providers↔retry); these are the mechanical
  consequence of partitioning a formerly single package and each edge mirrors a
  pre-existing same-package usage. The campaign *removed* two previously documented
  cycles (`legacy↔artifact` in 5663494, `model→detection` + detection↔recognition
  in cd34574). `providers→retry`, `providers→contextual` etc. are downstream-only
  edges that did not exist as package edges before but reflect real, pre-existing
  class usage. No new logical coupling.

## Item 4 — Doc/reality parity: PASS (2 doc findings)

Parsed the package map in `docs/TRANSLATION_MODULE.md` and compared to the real
inventory of `app/src/main/java/eu/kanade/translation/`:

- **24/24 packages match; 198/198 files match; 0 discrepancies** — every mapped
  file exists, every real file is mapped, no stale paths. Both the inpainting
  (root 3 / aot 13 / bubble 5) and translator (root 8 / contextual 11 / providers
  14 / retry 4) splits are reflected, plus `pipeline/batch/` (16) and
  `model/Detection.kt`. Uses `AiTranslator` throughout. ✓

Findings:
- **F1 (LOW-MEDIUM, stale doc row):** the test table lists
  `` `translator/TranslationBlockValidationTest` `` (line ~1170) as a live test,
  and contract #17 (line ~874) cites it ("Pinned by ... TranslationBlockValidationTest").
  **This test does not exist** — it was already absent at baseline 984e778
  (removed in the older 04c9311-era commit `04c9311`), so the reorg did NOT lose
  it, but the refreshed doc presents a non-existent test as real. Side effect:
  `TranslationBlockValidation` currently has no dedicated test (only a passing
  textual mention in `NumberedLineResponseParserTest`), while the doc flags other
  gaps honestly ("Known coverage gap" rows). The other 3 "absent" table entries
  (ChapterTranslatedPredicateTest, TranslationLifecyclePolicyTest,
  ocr/MangaOcrDecoderGuardTest) are explicitly marked removed/no-test — correct.
- **F4 (INFO, path imprecision):** table row `` `reader/ReaderPageWarmWindowTest` ``
  exists but at `eu/kanade/tachiyomi/ui/reader/`, not under
  `app/src/test/java/eu/kanade/translation/` as the section scope implies.
- All other 30+ doc test-table references verified to exist at their stated paths.

## Item 5 — Test suite (independent run): PASS

- Fresh `./gradlew :app:testStandardDebugUnitTest`: **BUILD SUCCESSFUL**.
  Parsed `app/build/test-results/testStandardDebugUnitTest/*.xml` (141 files):
  **tests = 1078, failures = 0, errors = 0, skipped = 0** — matches the claimed
  1078/0/0 exactly, sum verified per-class, no class with a nonzero failure/skip.
- `:app:checkTestRunBlocking` (silent-skip guard): **BUILD SUCCESSFUL** (pass).
- (a) Test-tree parity: 133 test files under `eu/kanade/translation/`; **0 test
  files whose package has no corresponding main package**. Additionally 918
  package-declaration-vs-directory checks across app/src: only 2 mismatches, both
  pre-existing upstream Tachiyomi patterns outside the translation module
  (`WebtoonLayoutManager.kt` = `androidx.recyclerview.widget`,
  `EditTextPreferenceExtensions.kt` = `androidx.preference`), untouched in the range.
- (b) Relocations verified: `CancelSyncStoreWriteTest.kt` at translation root
  (`package eu.kanade.translation`); `ChunkTranslationPayloadTest.kt` +
  `BatchEnvelopeLimitsTest.kt` in `translator/contextual/` with matching package
  declarations. Old `test/.../batch|remote|legacy/` dirs absent.
- (c) checkTestRunBlocking: pass (above).
- (d) Test-class parity 984e778→HEAD: 146→145 test files; **47 moved 1:1**
  (15 old `batch/` tests → `pipeline/batch/` + 2 → `contextual/`; 13 inpainting
  tests → `aot/`/`bubble/`; 17 translator tests → providers/contextual/retry;
  CancelSyncStoreWriteTest → root). **0 test classes lost** except
  `RemotePageTranslationEngineTest` — deliberate, it tested the removed remote/
  engine. 0 added, 0 duplicate basenames at HEAD.

## Item 6 — Repo state: PASS

- `git status --porcelain`: empty.
- `git rev-parse HEAD` = `5bc5f3e5de77452e9b41780ed110035b5dbd6388` =
  `origin/optimize_translation_finishing_page` (in sync).
- `git ls-files kilo.json` = empty (untracked); file present on disk (362 bytes).
- Absent from working tree **and** HEAD tree: `companion_server/`, `overlay_lab/`,
  `app/src/.../remote/`, `app/src/.../legacy/`, old `app/src/.../batch/` (main +
  test), `detection/Detection.kt`, `translator/AITranslator.kt`.

---

## Findings summary (ranked)

1. **F1 — LOW-MEDIUM (doc):** `docs/TRANSLATION_MODULE.md` test table lists
   `translator/TranslationBlockValidationTest` as live; the test has not existed
   since before the reorg baseline (absent at 984e778). Contract #17 cites it too.
   Consequence: `TranslationBlockValidation` is an untested public-ish contract the
   doc claims is pinned. Fix: delete/annotate the row like the other "no test"
   rows, and open a coverage-gap debt entry.
2. **F2 — LOW (doc precision):** the claimed inpainting cross-cluster edge list
   omits `AotReportBubbleFill → BubbleMaskBuilder` (aot→bubble). Direction is as
   documented; the enumeration is just incomplete.
3. **F3 — INFO:** `AITranslator` persists in 3 `Plan/active/2026-08-28_T908_.../`
   work artifacts (progress note, grouping audit, discarded patch snapshot).
   Historical task records, not living docs; acceptable.
4. **F4 — INFO:** doc table row `reader/ReaderPageWarmWindowTest` lives outside
   the `eu.kanade.translation` test root the section scopes; file exists.
5. **INFO:** package-level mutual imports inside `translator` post-split
   (root↔providers, root↔retry, providers↔contextual, providers↔retry) — all
   mirror pre-existing class-level dependencies (proven by item 1); no new
   coupling, but the cluster graph is not strictly layered. Worth one sentence in
   TRANSLATION_MODULE.md if layering is intended as aspirational.

## Evidence pointers

- Range file list: `git diff --name-status 984e778 HEAD -- app/src` (154 entries).
- Normalized-diff method: strip package/import lines + `AITranslator→AiTranslator`
  + `\r`, then inverse package-path mapping; result 0 unexplained diffs / 149 files.
- XML results: `app/build/test-results/testStandardDebugUnitTest/*.xml`
  (141 files, Σ = 1078/0/0/0).
- Doc parse: package map block of `docs/TRANSLATION_MODULE.md` vs
  `app/src/main/java/eu/kanade/translation/` (24 pkgs, 198 files, 0 diff).
