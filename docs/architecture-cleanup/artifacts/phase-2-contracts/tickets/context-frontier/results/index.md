---
kind: spec
title: "P2.3 context-frontier — results and evidence"
comments: none
---

# P2.3 implementation results

Ticket `phase-2-contracts/tickets/context-frontier/index.md` on `architecture-cleanup` @ `f015a33f` (P2.2 commit). Move `BatchContextFrontier` from `pipeline/batch` to `context/` — package/import wiring only; class name, declaration and full body retained (synchronization, failures, natural page order, gap behavior, snapshots). No rename, no wrappers, no policy change, no test path/assertion changes, no ordering/frontier-semantics change, no callback decomposition or persistence edits. Sole implementation/Gradle owner; no commits/pushes/P2.4/device/provider/models/subagents; report SHA256 unchanged `EFC11B90…88DDD`.

# Reference refresh (pre-edit, base f015a33f)

Source references resolved to exactly the contracted 7 logical files: explicit imports at `BatchResumePlanner.kt:16` (recovery), `ProfileEnvelopeExecutor.kt:53` (envelope), `ChapterContextService.kt:10` (context); same-package executable users `BatchLaneWorkers.kt:118` (constructor property), `BatchChapterTranslator.kt:409` (construction), `BatchContextFrontierTest.kt` (5 constructions); the moved file itself. Non-source scan (tree-wide, all file types): one prose mention in the user's architecture report `.md` — immutable, hash-protected, untouched. No fully-qualified executable references, no reflection, no config/doc references.

# Exact change (7 logical files = 1 move + 5 production wiring + 1 test wiring; 8 filesystem paths old/new counted separately)

| Path | Δ | Content |
| --- | --- | --- |
| `pipeline/batch/BatchContextFrontier.kt` → `context/BatchContextFrontier.kt` | moved | package line only (`pipeline.batch` → `context`); 110 lines; all six imports unchanged (engines/model imports remain valid) |
| `pipeline/batch/recovery/BatchResumePlanner.kt` | 1/1 | import swapped to `context.BatchContextFrontier`, placed before the `engines.*` block (ASCII-correct) |
| `pipeline/batch/envelope/ProfileEnvelopeExecutor.kt` | 1/1 | import swapped into the existing `context.*` block, before `context.ChapterContextService` |
| `context/ChapterContextService.kt` | 0/1 | now-same-package import removed |
| `pipeline/batch/BatchLaneWorkers.kt` | 1/0 | `import eu.kanade.translation.context.BatchContextFrontier` added (was same-package) |
| `pipeline/batch/BatchChapterTranslator.kt` | 1/0 | same import added |
| `test/…/pipeline/batch/BatchContextFrontierTest.kt` | 1/0 | same import added; test stays at its path, zero assertion changes |

Tracked `git diff --numstat`: 0/110 (deletion) + 1/1 ×2 + 1/0 ×3 + 0/1 + one new untracked 110-line file. All seven touched `import eu.*` regions verified ASCII-ordinal sorted; this slice used Write/Edit tools and byte-safe shell only — no `Get-Content`/`Set-Content` text rewriting anywhere (P2.2's encoding incident class explicitly excluded).

# Fidelity proof (vs base `f015a33f`)

Ordered-body equality, verified programmatically: after removing the package line from both versions, `git show f015a33f:<old path>` vs the new file compare **109/109 lines ordered-equal (True)** — KDoc, declaration, all members and comments byte-identical; the only difference in the file is the package declaration.

Reference scan after edits: zero tree-wide matches for `translation.pipeline.batch.BatchContextFrontier`; plain-symbol scan resolves to exactly the 8 files above.

# Validation

Selectors: `BatchContextFrontierTest`, `ProfileEnvelopeDispatchTest`, `StandardPipelineCoordinatorTest`, `context.*`, plus the incremental package gate `TranslationPackageBoundaryTest` — 6 groups, 6 classes. `--rerun` fresh module compilation per flavor.

| Run | Shell | Exit | Result (executed XML authoritative) |
| --- | --- | --- | --- |
| Pre-baseline | `95013f95` | 0/0 | **58/0 per flavor**: frontier 5, dispatch 20, coordinator 12, context.* 18 (LegacySnapshot 2 + RollingHistory 16), boundary 3 — matches the 55 forecast + 3 gate |
| Post | `b1ff47db` | 0/0 | **58/0 per flavor**, fresh compile: identical class/test counts and identities, zero new failures |
| `:app:spotlessCheck` (read-only, once) | same | 1 | two files flagged: the known pre-existing `LiteRTMangaInpaintingEngine.kt` wrapping debt and `pipeline/TranslationPipeline.kt` (whole-file CRLF working-copy residue originating from the P2.2 encoding-incident repair path — committed blob is LF-normalized; initially missed by an over-narrow log extraction, fully surfaced during P2.4 accounting). Zero violations from any P2.3 change; the TranslationPipeline residue was resolved during P2.4 with the same authorized exact-HEAD-bytes restoration Disposition note (P2.4 finalization): this slice's spotless reporting is reclassified as partial-extraction until the final verified run; the complete verified inventory across all Phase-2 runs is consolidated in the chapter-replacement results (final state: LiteRT only, a5aff896). used for RoiPage in P2.2 finalization |

The `context.*` selector set resolved to 2 test classes / 18 tests (the ticket's "5 classes" counts the full selector set: frontier + dispatch + coordinator + 2 context classes). Evidence dirs pre-created before every copy; contents-copy form used; no executed-test XML discarded, no retries.

# Limits

- Focused suites only (per ticket); full-suite and parity runs remain the parent's gate.
- The moved test stays at `pipeline/batch/BatchContextFrontierTest` by contract; a later slice may relocate it to mirror the production package — out of scope here.
- LiteRT wrapping debt, ArtifactOpenProbe ledger, JDK/device items: unchanged, deferred.
- The combined preparation set (65) includes the next slice's `TranslationManagerDeleteResetOrderingTest` and `ChapterQueueConflictDetectionTest` — deliberately not run (unaffected by this slice).

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- Baseline XML: `scratch/phase-2-contracts/context-frontier/pre-baseline/{dev,std}/xml`
- Post XML: `scratch/phase-2-contracts/context-frontier/post-change/{dev,std}/xml`
- Logs: `commands/…/{95013f95-4b53-4d9a-84e5-a999b3701630,b1ff47db-8cfc-4b9b-89a9-f07937ce8e42}/output.log`
- Ticket: `artifacts/phase-2-contracts/tickets/context-frontier/index.md`

# Next

Stopped for Gemini/Antigravity actual-diff review. No commits/pushes, no further Phase 2 code until review verdict.
