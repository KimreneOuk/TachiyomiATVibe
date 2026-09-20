# T936 Phase 5 — Independent Review Report

Date: 2026-09-20
Reviewer: Independent Reviewer (adversarial verification; per-commit diff classification,
line-level rename audit, independent comment-strip scanner, lexical recount, APK and XML
re-derivation — implementer claims not trusted)
Branch reviewed: `t936/phase5-packages-hygiene` @ `64eb1bb`, base `main` @ `eb4615e`
(merge-base verified). 8 commits; 374 files: 316 M + 53 R + 5 Plan docs.

## Verdict

**PASS WITH NOTES**

All five priority items verified. The package moves and test renames are provably pure, the
comment strip changed **zero executable lines** (stronger than the implementer's own audit),
safety invariants hold, and the evidence is green. Notes: three one-line comment tags missed
by the strip (one is the briefing's known residue; I found two more), and the report's
residual-count ledger does not reconcile internally.

---

## Item 1 — Pure-move verification (P5-01/02): PASS

Per-commit line classification for all four move commits (`87244fe`, `a9aeddb`, `3b185d3`,
`de76bac`) — every changed line bucketed as package/import, `eu.kanade.translation.*` FQCN
reference update, or comment/KDoc:

| Commit | Changed lines | import/pkg | FQCN ref | KDoc | Unaccounted |
|---|---:|---:|---:|---:|---:|
| `87244fe` recognition→ocr | 54 | 50 | 14 | 4 | **0** |
| `a9aeddb` storage | 310 | 298 | 165 | 2 | **0** |
| `3b185d3` orchestration | 139 | 113 | 74 | 22 | **0** |
| `de76bac` pipeline | 521 | 489 | 266 | 10 | **0** |

Zero content edits in any moved or referencing file. The FQCN-reference updates are the
unavoidable mechanical adaptation (fully-qualified call sites, type positions, and KDoc
links re-pointed to the new package — e.g. `eu.kanade.translation.ActiveChapterStoreRegistry
.flushAllActiveStores()` → `eu.kanade.translation.storage.…`, verified as same-symbol
re-pointings). **FQCN check independently confirmed:** `app/proguard-rules.pro` and all
gradle files contain zero `translation.recognition`/`translation.storage`/
`translation.orchestration`/`translation.pipeline` references (none existed to fix — stated
in the report, verified here). Acceptance: `RECOGNITION_REFS=0` in `app/src` (re-run),
root `eu/kanade/translation/*.kt` count = **0** (re-run).

## Item 2 — Test renames (P5-03): PASS

- **23/23 renames present in `331de00`, matching the ticket table exactly** — no deviations,
  no omissions (all pairs enumerated from `git show 331de00 -M --name-status`; all R098-R099).
- **Assertion bodies zero diff, proven mechanically:** the commit's entire diff is 48 changed
  lines, and **every one references an old or new suite name** (class declarations, header
  comments, same-package references). No line outside the rename vocabulary changed at all.
- Old ticket-named files remaining on disk: **0** (all 23 names re-checked).
- Cross-references: no dangling references possible — both flavor compiles and full suites
  are green; test count unchanged (Item 5).

## Item 3 — Comment hygiene (P5-04): PASS WITH NOTES

**Independent code-zero-diff audit (my own method, all of `app/src`).** For commit
`46a46fd`: 4,281 changed lines total. After excluding diff headers, **1,890 removed and 1,789
added lines are comment-only** (`//`, `/*`, `*`, `*/` prefixed); the remaining non-comment
line counts on both sides are **exactly 0**, and the trailing-comment code-prefix pairing
found 0 mismatches. **Not a single executable line changed in the entire phase's comment
commit** — stronger than the report's own `CODE_DIFF_FILES=0` claim. Net −101 comment lines
= the deleted sprint essays.

**16-file sample (method validation across prod + test):** `ChapterProfileBatchCoordinator.kt`,
`orchestration/TranslationManager.kt`, `orchestration/ChapterTranslator.kt`,
`storage/ChapterTranslationStore.kt`, `pipeline/TranslationPipeline.kt`,
`scheduling/RollingAutoCoordinator.kt`, `ui/reader/ReaderViewModel.kt`,
`ui/reader/ReaderTranslationController.kt`, `runtime/onnx/OnnxRuntimeProvider.kt`,
`segmentation/OnnxBubbleSegmenter.kt`, `artifact/ArtifactContracts.kt`,
`ocr/PaddlePageOcrCoordinator.kt`, `coexistence/AutoProviderCallDrainsNotCancelsTest.kt`,
`coexistence/BatchAttemptLedgerDeathCycleTest.kt`, `ui/ReaderBarTruthTest.kt`,
`orchestration/ReaderTeardownCoordinator.kt` — all classified comment-only by the audit; the
zero-code result covers every other changed file identically.

**Sprint-essay deletion kept technical invariants:** the coordinator's pre-class region now
carries typed-contract KDocs only ("The BATCH OCR stage lease could not be acquired…",
"Typed outcome of the checkpoint-reuse probe…", "Why the checkpoint cannot back this run
(typed; the page re-runs — fail closed)") — live contracts retained, delivery chronicles
gone.

**Residual judgments (my call, per directive):**
- `StageFingerprints.kt:481` — ACCEPT. Lowercase `t924/golden/envelope-plan-small.json` is a
  real fixture path (and not even a pattern-class hit: the ticket's `T9\d\d` is case-sensitive).
- `TranslationBatchProgressTrackerTest.kt:101` — **REWORD REQUIRED.** It is a comment
  ("T934 stranded-page fix: the group key is the page's best available reason…") whose
  invariant text is live and correct — only the tag must go, per the ticket's own
  remove-tag-keep-invariant rule.
- **Two additional residues I found beyond the briefing's list:**
  `NextPageAdmittedDuringParkedPublicationTest.kt:59` ("D8 harness note") and `:77`
  ("The D11 seam") — same treatment: one-line tag removals, invariant text kept.

**Ledger discrepancies (doc notes):** (a) the report claims "comment-aware scanner reports
zero targeted tag hits in comments" — **false by three lines** (the three above; the 2
fixture paths are non-hits under the ticket's case-sensitive patterns); my comment-aware
recount: 5 comment-tagged lines total, 3 genuine. (b) The report's residual recount does not
reconcile internally (states total 488 but its per-class numbers sum to 580) and differs from
my independent recount on HEAD (T9=207, D=216, ST=7, TX=0, FF=2, LI=1, R1=0; total 433) —
scanner scope/method undocumented. The acceptance signal (no tags in comments) is treated
here as met-modulo-3-lines; the lexical residual in code/strings (483 lines) is correctly out
of scope per the ticket's string-literal rule.

## Item 4 — Safety invariants: PASS

- Hardware/NNAPI files in the branch diff: `OnnxRuntimeProvider.kt`,
  `OnnxBubbleSegmenter.kt`, `OnnxRuntimeProviderProvenanceTest.kt` (test) — each has **0
  non-comment changed lines** in `46a46fd` (comment-only, exactly what P5-04 authorizes).
  `HardwareDiscoveryEngine`, `AOTInpainting`, `PaddleOcrSessionFactory`, `QnnDiagnostics`,
  `TranslationPreferences`, `domain/`: not in the branch diff at all.
- Assets: no file under `app/src/main/assets/` in the branch diff (byte-identical to main);
  APK presence re-verified (Item 5).
- String resources: `i18n-at/**` not in the branch diff — zero UI-string changes.
- Phase 2/3/4 semantics: move commits are FQCN-only (Item 1), the strip is comment-only
  (Item 3), test renames are name-only (Item 2) → runtime semantics zero-diff by
  construction; 2,025 × 2 green suites corroborate.

## Item 5 — Evidence: PASS

- **Test XMLs: 2,025 tests × both flavors, 292 files each, 0 failures / 0 errors** (fresh
  timestamps 09-20 13:44 Dev / 13:48 Standard — post-phase). Count identical to Phase 4 as
  required.
- **APK** (`app-dev-universal-debug.apk`): 2,042 entries; best_int8 0; OCR docs 0;
  manga109 1; inference.onnx 2; aot-512 1; aot.onnx 1 — matches the report's five-APK table
  claim for the universal variant re-verified here.
- Acceptance greps re-run: `RECOGNITION_REFS=0`, root `.kt` count 0, old ticket test files 0.
- Working tree: only the orchestrator's pending `README.md` close-out modification (ignored
  per directive); no secrets; report commit `64eb1bb` contains only the report.

## Notes (non-blocking; items 1a-c are trivial pre-merge follow-ups)

1. **Three missed comment tags — one-line rewords required before merge:**
   `TranslationBatchProgressTrackerTest.kt:101` ("T934 stranded-page fix:" → keep the
   invariant sentence), `NextPageAdmittedDuringParkedPublicationTest.kt:59` ("D8 harness
   note"), `:77` ("The D11 seam").
2. **Report accuracy:** the "zero targeted tag hits in comments" claim is contradicted by
   note 1; the residual lexical ledger (488 vs 580 sum vs my 433) needs a documented scanner
   scope; the P5-04 section says "301 changed source files" while the branch shows 316
   modified files overall (Plan/docs accounting differs) — state the counting basis.
3. The two lowercase `t924/golden/` fixture-path comment mentions are accepted as legitimate
   paths (documented here so a future case-sensitive scan does not re-flag them).

## Conclusion

Phase 5 is a clean, fully mechanical close-out: packages consolidated with zero content
edits, 23 test suites renamed with byte-identical bodies, and a comment strip that — by
independent line-level audit — changed zero executable lines across 301+ files while
preserving the live contracts. The three missed comment tags are trivial rewords. Merge-ready
from this reviewer's standpoint once note 1 lands.
