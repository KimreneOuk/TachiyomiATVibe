# T924 Stage 4 — Pure Planners Report (corpus manifest / analysis chunks / envelope plan)

Date: 2026-09-05 · Implementer slice: S4 subset (coordinator scope items 1-3 + tests).
Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`, base HEAD `7c301bc`. Uncommitted (per instructions — no git add/commit).
Scope guards honored: no IO, no coroutines, no flags, no pipeline wiring, no provider/network code; `rendering/**`, `pipeline/batch/**` untouched; no existing file edited.

## Files created (all NEW)

| File | Kind |
|---|---|
| `app/src/main/java/eu/kanade/translation/translator/contextual/OcrCorpusManifest.kt` | planner |
| `app/src/main/java/eu/kanade/translation/translator/contextual/AnalysisChunkPlanner.kt` | planner |
| `app/src/main/java/eu/kanade/translation/translator/contextual/GlobalEnvelopePlanner.kt` | planner |
| `app/src/test/java/eu/kanade/translation/translator/contextual/OcrCorpusManifestTest.kt` | test |
| `app/src/test/java/eu/kanade/translation/translator/contextual/AnalysisChunkPlannerGoldenTest.kt` | test |
| `app/src/test/java/eu/kanade/translation/translator/contextual/GlobalEnvelopePlannerGoldenTest.kt` | test |
| `app/src/test/resources/t924/golden/envelope-plan-small.json` | golden fixture |

## Placement decision

WP3 verified entry points (`stage0/work-packages.md` §WP3 "NEW files") place the
planners in `translator/contextual/` (OcrCorpusManifest.kt, AnalysisChunkPlanner.kt,
GlobalEnvelopePlanner.kt). That directory is outside both parallel-agent-owned trees
(`rendering/**`, `pipeline/batch/**`), so the WP3 placement was kept verbatim. The
coordinator's fallback `model/profile/` package was NOT used — deviation from the
coordinator prompt in the permissive direction only (prompt allowed "wherever
work-packages.md's verified entry points say").

## Contract anchors

| Anchor | Location |
|---|---|
| EnvelopePlan / PlannedEnvelope DTO + validation, MAX_ENVELOPES=4096 | `artifact/EnvelopePlan.kt` ( landed S1; read-only) |
| AnalysisChunkResult bounds MAX_CORE_PAGES=16 / MAX_OVERLAP_PAGES=2 | `artifact/AnalysisChunkResult.kt:144-145` |
| `StageFingerprints.ocrCorpusFingerprint` (T924-FP-03, naturalOrderProven handling) | `artifact/StageFingerprints.kt:251-272` |
| `StageFingerprints.pageOcrContentFingerprint` (T924-FP-02) | `artifact/StageFingerprints.kt:183-231` |
| Chunking policy (whole pages, cap tokens/pages/blocks, small adjacent overlap, core vs context) | design `chapter-profile-batch-design.md` §6.1 (lines 288-300) |
| Envelope budgets 32 blocks / 8 pages as experiment, scene boundary not a fence, oversized single page rejected, page atomicity | design §8 (lines 378-404) |
| Chunk schema: chunkId `chunk-<ordinal>-<corpus8>` deterministic, core 1..16 (T), overlap ≤2 adjacent (T), contributing corpus fp over set in order | schemas contract §1.3 (`contracts-schemas-fingerprints.md` lines 108-131) |
| Envelope schema §1.5 (planFingerprint/planInputFingerprint/plannerVersion; every pending block exactly once; page never split) | schemas contract §1.5 (lines 214-226) |
| FP-01 exclusion (timestamps operational only), SC-08 encoding, SC-10 content fingerprint by canonical re-encode | schemas contract §2/§5 (T924-SC-08/SC-10, T924-FP-01) |
| Evidence V1/V9 pure subset (page/block membership, blockId prefix guard) | provider contract T924-AP-05 table (`contracts-provider-analysis.md` lines 253-265) |
| Invalidation matrix row 7 (envelope-policy-only change keeps translations) | schemas contract §6 row 7 |
| WP3 entry points + tests + "page atomicity / gap-free / determinism" hooks | work-packages.md §WP3 (lines 155-199) |
| S4 sequence position (parallel track, provider-free) | implementation-sequence.md §S4 (lines 170-184) |

## Planner algorithms

### OcrCorpusManifest (pure corpus assembly + gap detector)
`OcrCorpusManifest.assemble(pages, expectedPageCount, expectedPageCountTrusted)` sorts
entries canonically (pageKey, then nulls-last natural index), records and dedupes
duplicate pageKeys (retention = first in canonical order, so input permutation never
matters), derives `naturalOrderProven` (all distinct non-null in-range indexes and no
duplicate keys — never-guess rule), orders naturally when proven else by sorted
pageKey, and computes the whole-corpus fingerprint via
`StageFingerprints.ocrCorpusFingerprint`. The gap detector reports
missing/ duplicate/ beyond-expected natural indexes, trusted vs untrusted counts,
contiguous gap-free prefix length, order degradation, and `isComplete`.
`contributingFingerprint(pageKeys)` emits the T924-FP-03 fingerprint over any subset
in canonical manifest order (used downstream for chunk/envelope contributing sets).

### AnalysisChunkPlanner (pure windowing skeleton)
`plan(pages, policy)` canonicalizes page order (natural index, else pageKey), rejects
duplicate pageKeys/blockIds/negative tokens, skips zero-block (textless) pages
(reporting them), then greedily accumulates whole-page CORE windows under
maxCorePages / maxBlocksPerChunk / maxEstimatedInputTokens caps. A page exceeding a
cap alone still forms its own single-page chunk (chunking never splits a page; caps
bound multi-page windows, not admission). CONTEXT overlap for window i>0 = last
`overlapPages` core pages of window i-1 (adjacent predecessors), shrunk farthest-first
until core+overlap respects the token cap. Each chunk gets the deterministic
`chunk-<ordinal>-<corpus8>` id and a contributing corpus fingerprint computed by the
`StageFingerprints.ocrCorpusFingerprint` oracle over (pageKey, per-page content
fingerprint) pairs in contributing order. `evidenceResolves(pageKey, blockId)`
implements the pure subset of V1/V9: contributing-page membership, block-list
membership, blockId-prefix guard. Response parsing, excerpt-hash recompute (V8),
status persistence and network protocol stay out of scope (WP5).

### GlobalEnvelopePlanner (pure whole-page envelope planning)
`plan(pages, corpusFingerprint, policy, createdAtEpochMs)` canonicalizes page order,
rejects duplicate pageKey/blockId, empty pending sets, and any single page whose
blocks/tokens alone exceed a budget (page atomicity: rejection, never splitting).
Greedy accumulation closes an envelope when the next page would exceed
maxBlocksPerEnvelope (32), maxContributingPages (8), input or output token caps, or
— when `preferSceneBreaks` is on — at a `sceneBoundaryBefore` page start (preference,
not a fence; `crossesSceneBoundary` is computed generally and is false in the
preferred mode). Envelopes are emitted as `e-<ordinal>` with whole-page orderedPageKeys,
reading-order blockIds, per-envelope contributing fingerprint, token estimates
(estimator v1: ceil(chars/4)+8 input framing, ceil(chars/2) output — PROPOSED-GATE).
`planInputFingerprint` = local SC-08-style length-prefixed hash over corpus
fingerprint + planner version + policy fingerprint + ordered pending block set.
`planFingerprint` = SHA-256 over canonical re-encoded JSON of the DTO with
`planFingerprint` blanked and `createdAtEpochMs` zeroed (T924-SC-10 + FP-01).
Before returning success the planner re-verifies: DTO `validationError() == null`,
exact-once coverage, canonical page+reading order total, all budgets, and a
serialization sanity bound of 256 KiB per contributing page (pure in-memory size
check, no IO). Any violation returns `Rejected` — success is never partially shaped.

## Constants table (owner)

| Constant | Value | Owner / status |
|---|---|---|
| `EnvelopePlannerPolicy.maxBlocksPerEnvelope` | 32 | design §8 experiment (PROPOSED-GATE, measured constant, never a flag) |
| `EnvelopePlannerPolicy.maxContributingPages` | 8 | design §8 experiment (PROPOSED-GATE) |
| `EnvelopePlannerPolicy.maxEstimatedInputTokens` | 16384 | S4 planner default (PROPOSED-GATE; design §6.1 "reserve ≥ half context") |
| `EnvelopePlannerPolicy.maxEstimatedOutputTokens` | 8192 | S4 planner default (PROPOSED-GATE; matches T924-AP-03 outputBudget example) |
| Token estimator v1 (chars/token 4 in, 2 out; +8 block framing) | — | S4 planner, versioned via `PLANNER_VERSION` (PROPOSED-GATE calibration) |
| `AnalysisChunkPolicy.maxCorePages` | 16 | schema bound `AnalysisChunkResult.MAX_CORE_PAGES` (T, tunable) |
| `AnalysisChunkPolicy.overlapPages` (default) | 1 | design §6.1 initial ("up to one adjacent page"); hard cap 2 = `MAX_OVERLAP_PAGES` (T) |
| `AnalysisChunkPolicy.maxBlocksPerChunk` | 512 | S4 planner default (PROPOSED-GATE; design §6.1 requires a block cap, no number given) |
| `AnalysisChunkPolicy.maxEstimatedInputTokens` | 16384 | S4 planner default (PROPOSED-GATE) |
| `SERIALIZATION_CAP_BYTES_PER_PAGE` | 256 KiB | coordinator directive (cap check, not IO) |
| `GlobalEnvelopePlanner.PLANNER_VERSION` / `AnalysisChunkPlanner.PLANNER_VERSION` | 1 | S4 planner algorithm version |
| `EnvelopePlan.MAX_ENVELOPES` | 4096 | schema bound (S1, read-only) |

## Tests and property list

Three classes, JUnit5 + kotest matchers (repo idiom). Determinism loops: 100 seeded
LCG iterations each (`seed*6364136223846793005 + 1442695040888963407`), asserting
full-plan equality under input permutation.

OcrCorpusManifestTest (13): natural-order assembly regardless of input order; oracle
equality with `StageFingerprints.ocrCorpusFingerprint`; unproven order degrades to
sorted pageKey and changes the fingerprint; missing middle page (missing=[2],
prefix=2); missing tail; trusted/untrusted counts; duplicate pageKey recorded +
deterministic dedupe + order degradation; duplicate natural index; beyond-expected
index; empty corpus (0 expected) complete + deterministic; expectedCountTrusted flag
reaches fingerprint; contributing subset fingerprint order-sensitivity; 100-seed
permutation invariance.

AnalysisChunkPlannerGoldenTest (17): core/overlap windows; fewer pages than core cap
(single chunk, no overlap); overlap 0; overlap 2; block-cap flush; token-cap flush +
overlap shedding (farthest-first) staying in budget; textless skip + report; empty
input; deterministic `chunk-<ordinal>-<corpus8>` shape across permutations;
contributing fingerprint = oracle over contributing set in order; evidence universe
matrix (core ok, overlap ok, foreign page, unknown block, wrong prefix); core sets
partition corpus exactly once; duplicate pageKey / negative tokens rejected; policy
bounds; unproven-index pageKey fallback order; 100-seed permutation invariance;
content-fingerprint sensitivity.

GlobalEnvelopePlannerGoldenTest (16): 32-block budget close; 8-page budget close;
token budget close; exact-once coverage in canonical order; page atomicity
(single-envelope ownership + per-page block contiguity); scene preference close +
`crossesSceneBoundary` both modes; oversized single page rejected whole (blocks,
input tokens, output tokens — never split); invalid policy / empty pending /
textless-only / duplicate pageKey / duplicate blockId rejections; textless page
exclusion; 4096-envelope schema bound rejection; planInputFingerprint sensitivity
(corpus, policy, pending set) + operational-field exclusion (createdAtEpochMs,
permutation); token estimator arithmetic; serialization budget formula + encoded-size
bound; produced plan passes its own schema validation; 100-seed permutation
invariance; golden fixture byte-equality (`t924/golden/envelope-plan-small.json`) +
golden planFingerprint literal.

Golden fixture: 5 pages (6/6/6/6/3 blocks, scene boundary before p3, policy
maxBlocks=12/maxPages=8), fixed corpus fingerprint `1a…1a` (64 hex) and
createdAtEpochMs=1757050000000; the canonical JSON bytes are committed as the
resource and compared byte-for-byte; the planFingerprint literal is pinned in the test.

## Deviations / recorded decisions

1. **Placement**: WP3 `translator/contextual/` used instead of the coordinator's
   `model/profile/` fallback — allowed per prompt ("OR wherever work-packages.md's
   verified entry points say"); outside both parallel-owned trees.
2. **Contributing corpus fingerprints for chunks/envelopes** are computed over
   (pageKey, pageOcrContentFingerprint) pairs supplied by the caller rather than
   re-derived from payloads: planners are pure and accept the S1 DTO fingerprints as
   inputs (matches the S1 substrate boundary; `OcrCorpusManifest` shows the assembly
   pattern for callers).
3. **Planner-local canonical hasher** (`PlannerFingerprints`, internal) for
   `planInputFingerprint`/`policyFingerprint`: `StageFingerprints` encoding discipline
   mirrored exactly, but `StageFingerprints.kt` is read-only for this slice and its
   builders are per-stage named; conformance pinned by construction (same
   `len:value|` + indexed + `<null>` + sha256-hex routine). If the Reviewer prefers a
   single source, a follow-up can move the two composite builders into
   `StageFingerprints` (existing-file edit, flagged here for ratification).
4. **planFingerprint hashing view**: mirrors the S1 `profileContentFingerprint`
   pattern — copy with `planFingerprint=""`, `createdAtEpochMs=0`, canonical
   re-encode via shared `ArtifactDocumentJson`, SHA-256 (T924-SC-10/FP-01).
5. **Serialization cap interpretation**: "≤256 KiB/page" implemented as
   `256 KiB × contributingPageCount` total plan budget (per-page scaling; a
   single-page chapter plan must fit 256 KiB). Recorded here for ratification.
6. **Zero-block pages**: excluded from chunk windows and envelope contributing sets
   (nothing translatable); reported by the chunk planner, excluded silently-but-
   detectably by the envelope planner (they never appear in `orderedPageKeys`).
7. **Scene/profile subset fields**: `sceneRefs`/`profileSubsetRefs` stay empty in
   this slice — frozen-profile scenes and the relevant-subset matcher are separate
   S4/WP5 scope not in the coordinator's three-item list; `crossesSceneBoundary` is
   already computed from `sceneBoundaryBefore` inputs.
8. **Chunk planner output is planner-shaped, not a persisted DTO**: `PlannedAnalysisChunk`
   carries the planning-owned subset of `AnalysisChunkResult` (ids, contributing sets,
   fingerprints, evidence universe). `status`/excerpt-hash validation/persistence
   belong to WP5 (out of scope per coordinator).
9. **Contributing-set order** (ratification request): the per-chunk/per-envelope
   contributing corpus fingerprint hashes the contributing set in CORE-first-then-
   CONTEXT order (the T924-AP-03 request payload order), and the corpus-level
   function is invoked with `naturalOrderProven=true` because the caller asserts
   that payload order. Alternative reading ("always natural page order") would
   change only the fingerprint input order; pinned by oracle test.
10. **Deterministic dedupe retention** (`OcrCorpusManifest.assemble`): duplicate
    pageKeys are resolved by the minimum under a total canonical comparator
    (pageKey, natural index, content fingerprint, trusted) — first-in-input-order
    retention would have leaked iteration order into the fingerprint (caught by
    the permutation property test, fixed).
11. **Zero-block pages in envelopes**: planner now excludes them explicitly
    (`plannable` filter) from budgets, accumulation, `planInputFingerprint` and
    coverage verification; they stay in the corpus manifest with their content
    fingerprints (caught by the textless property test, fixed).

## Risks

- Budget/token constants are PROPOSED-GATE experiments (design §8 explicitly: "not
  accepted product constants"); device/provider measurement may retune defaults —
  policy is data, planner algorithm unchanged, invalidation matrix row 7 keeps
  translations compatible across envelope-policy-only changes.
- `maxBlocksPerChunk` (512) and token defaults are S4-invented defaults where the
  contract names no number; flagged for the S5 analysis-stage owner to ratify.
- Parallel-agent compile contention: `pipeline/batch/**` was mid-edit during this
  slice's builds; my verification loops exclude foreign errors per protocol (wait
  60 s, retry ×10).
- `isSha256Hex` remains internal to `artifact/**`; planners rely on the DTOs' own
  validation rather than re-checking hex shapes (single source of truth).

## Verification (final)

- `./gradlew :app:compileStandardDebugKotlin` — exit 0, 0 errors (main worktree
  files untouched; early iterations of the run showed only parallel-agent
  `pipeline/batch/**`/`rendering/**` in-flight errors, never planner files; jar
  lock/flaky-daemon retries followed the wait-60s protocol, all transient).
- `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.translator.contextual.*"`
  — **BUILD SUCCESSFUL; 121 tests, 0 failures, 0 errors, 0 skipped** across the
  whole `translator/contextual` package, including all pre-existing suites
  (StreamingChunkPlannerTest, ContextualResponseParserTest,
  BatchEnvelopeLimitsTest, TranslationPromptsTest, etc. — untouched and green).
  Planner slice contributions: `OcrCorpusManifestTest` 13,
  `AnalysisChunkPlannerGoldenTest` 17, `GlobalEnvelopePlannerGoldenTest` 16
  (46 new tests; each determinism loop executes the full plan equality 100
  times ⇒ 300 property iterations).
- Golden fixture: `app/src/test/resources/t924/golden/envelope-plan-small.json`
  (1498 bytes), planFingerprint
  `5643a00c7f98e158e61246c6ad7413f933ff1eaade91b3efa06f45e6b0339df8` — byte
  comparison plus literal fingerprint assertion both green.
- Test-first catches fixed during verification (kept as regression properties):
  zero-block pages leaked into envelope contributing sets; duplicate-pageKey
  dedupe depended on input iteration order; the earlier contributing-fingerprint
  oracle used natural order instead of core-then-context payload order.
- Git: no add/commit performed (per instructions); worktree carries the seven
  new files listed above plus nothing else — `git status` shows only the new
  planner/test/fixture paths as untracked.

## Result

Stage-4 coordinator scope items 1-3 complete: pure corpus manifest + gap
detector, pure analysis chunk windowing skeleton, pure global envelope planner
with deterministic coverage/budget/ordering properties, exhaustive property
tests incl. 100-seed permutation invariance and a byte-stable golden fixture.
Ready for S4 remainder (pre-merge, relevant-profile matcher, taxonomy, retry
ledger, parser completeness) and downstream S5 consumption.
