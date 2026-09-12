# T924 Stage-5 preconditions report — wave-3 slice A (S5 kickoff group)

Date: 2026-09-06 · Worktree: `TachiyomiAT-t924-impl` · Branch:
`t924/batch-profile-pipeline` · Base: `ee858f9` · No commits made (per
directive).

Scope: the S5-KICKOFF obligations from `implementation-sequence.md`
§"Wave-2 review obligations" assigned to this slice — **F2** (hasher
consolidation), **F3** (non-AI dispatch gate), **gap 2** (flagged-run
queue-restore test), **gap 4** (`PlannedAnalysisChunk` →
`AnalysisChunkResult` mapping). F1, gap 1, gap 3/F4-schemas-note, D4 remain
with the S5 analysis/profile stage slices that own
`ChapterProfileBatchCoordinator.kt` / the WP5 executor.

---

## 1. F2 — `PlannerFingerprints` consolidated into `StageFingerprints`

### What moved

The planner-local canonical hasher
(`translator/contextual/GlobalEnvelopePlanner.kt`, former
`internal object PlannerFingerprints` at 411–435) is **deleted**; all four of
its call sites now delegate to additive public builders in
`artifact/StageFingerprints.kt`:

| Retired call site | New builder |
| --- | --- |
| `EnvelopePlannerPolicy.policyFingerprint()` (`sha256(["envelope-policy", …])`) | `StageFingerprints.envelopePolicyFingerprint(maxBlocksPerEnvelope, maxContributingPages, maxEstimatedInputTokens, maxEstimatedOutputTokens, preferSceneBreaks)` |
| per-envelope `contributingCorpusFingerprint` (`sha256(["envelope-contributing", page[i], key, fp, …])`) | `StageFingerprints.envelopeContributingCorpusFingerprint(contributingPages: List<Pair<String, String>>)` |
| `GlobalEnvelopePlanner.planInputFingerprint` (`sha256(["envelope-plan-input", corpus, version, policyFp, page[i], key, block[j], id, …])`) | `StageFingerprints.envelopePlanInputFingerprint(corpusFingerprint, plannerVersion, policyFingerprint, pages: List<EnvelopePlanInputPage>)` with new file-level carrier `EnvelopePlanInputPage(pageKey, orderedStableBlockIds)` |
| `planFingerprint = sha256Bytes(canonical.toByteArray(UTF_8))` | `StageFingerprints.envelopePlanContentFingerprint(canonicalPlanJson: String)` |

Supporting public core additions (the "single source" requirement):

- `StageFingerprints.canonicalFingerprint(fields: List<Any?>)` — public
  non-overloaded alias of the private `fingerprintIndexed(List)` encoding
  core; used by the golden test harness for fixture page-content markers and
  available for future planner-domain composites.
- `StageFingerprints.sha256Hex(bytes: ByteArray)` — the previously private
  byte-level SHA-256 core widened to public (same body, `%02x` lowercase hex).

`AnalysisChunkPlanner.kt` and `OcrCorpusManifest.kt` already hashed through
`StageFingerprints.ocrCorpusFingerprint` and needed no edits (verified: grep
`PlannerFingerprints` in `app/src` matches only explanatory comments).

### Byte-identity proof

The encoding discipline of the retired local core (`len:value|` fields,
`[$index]` list elements, `<null>` literal, SHA-256 lowercase hex over UTF-8)
was line-verified identical to `StageFingerprints.fingerprintIndexed` before
migration (the same verification wave-2 review performed). Every consolidated
builder feeds the **same field sequence, same `toString` values, same UTF-8
bytes** into the same core, therefore every value is bit-identical.

Acceptance criterion — **goldens stay byte-valid WITHOUT re-pinning**:

- `GlobalEnvelopePlannerGoldenTest.golden small-chapter fixture is
  byte-stable` still compares the produced plan JSON byte-for-byte against the
  UNCHANGED `app/src/test/resources/t924/golden/envelope-plan-small.json` and
  still asserts the UNCHANGED literal
  `GOLDEN_PLAN_FINGERPRINT = 5643a00c7f98e158e61246c6ad7413f933ff1eaade91b3efa06f45e6b0339df8`.
  PASS (no fixture bytes touched, no literal re-pinned; `git status` shows the
  resource unmodified).
- `analysis-chunks-small.json` never depended on `PlannerFingerprints`
  (`AnalysisChunkPlanner` already used `StageFingerprints`), unchanged and
  green.
- Test-harness note: the golden test built fixture
  `contentFingerprint`s with `PlannerFingerprints.sha256(listOf("page-content",
  pageIndex))`; after the deletion the harness calls
  `StageFingerprints.canonicalFingerprint(...)` — same encoding, same values,
  same fixture bytes. The only test-file change is that one call plus the
  import (diff: 1 line + 1 import).

`grep -rn PlannerFingerprints app/src` now matches only two explanatory
comments (one in `StageFingerprints.kt`, one in `GlobalEnvelopePlanner.kt`
documenting the retirement).

Implementation note (recorded deviation): the first attempt called the
overloaded private core through `buildList { … }` and crashed Kotlin (FIR)
resolution for the whole file (nested-comment trap `artifact/**` inside a
KDoc — Kotlin block comments nest — plus an unstable-inference cascade). The
landed form uses the file's proven `mutableListOf<Any?>` payload pattern and a
non-overloaded public `canonicalFingerprint` core; behavior identical.

## 2. F3 — non-AI dispatch gate (`BatchChapterTranslator`)

### Gate design

`BatchChapterTranslator.runBatchPass1` (the T924-FF-01a single dispatch point)
now resolves the coordinator kind through a new pure helper on the existing
`internal companion object`:

```kotlin
internal fun profilePipelineDispatchKind(
    flagOn: Boolean,
    contextualAiParity: Boolean,
): ChapterProfileBatchCoordinator.BatchCoordinatorKind =
    ChapterProfileBatchCoordinator.dispatchKind(
        translationBatchProfilePipeline = flagOn && contextualAiParity,
    )
```

The dispatch site passes `flagOn = translationPreferences
.translationBatchProfilePipeline().get()` (still read exactly once per run,
FF-01d) and `contextualAiParity = isAi` — the legacy contextual lane's own
gate already computed four lines above the coordinator wiring
(`BatchChapterTranslator.kt:348`):
`translationPreferences.translationEngineCategory().get() ==
TranslationEngineCategory.AI_MODEL && textTranslator is
ContextualTextTranslator`.

### Legacy-parity anchor

- `ChapterProfileBatchCoordinator.dispatchKind` itself is untouched
  (read-only for this slice) — the gate composes it, never replaces it, so
  the FF-01a/FF-01b mapping (`OcrPreflightFlagOffMidRunTest` row 1) remains
  the single flag→kind mapping.
- Flag OFF + any engine, and flag ON + non-AI (or AI category with a
  non-contextual translator instance), all construct the verbatim legacy
  `SequentialBatchCoordinator` branch — byte-for-byte as before (the OFF
  branch body was not modified; the `when` subject expression changed only).
- The flagged branch is entered exactly when flag ON AND `isAi`; its
  construction arguments are unchanged (`frozenConfig.flagProfilePipeline`
  still records the raw flag, which is necessarily `true` in that branch).

### Tests

New `app/src/test/java/eu/kanade/translation/pipeline/batch/
ProfilePipelineDispatchGateTest.kt` (4 tests): non-AI + flag ON →
`LEGACY_SEQUENTIAL`; AI + flag ON → `PROFILE_PIPELINE`; flag OFF always
legacy (both parity rows); full 2×2 truth table pinned as exactly
`flag && parity` over the `dispatchKind` mapping. Call-site binding is by
construction: `runBatchPass1` consults the helper and nothing else (same
single-call-site argument the wave-2 review used for FF-01a).

## 3. Gap 2 — flagged-run queue-restore test

`app/src/test/java/eu/kanade/translation/ChapterTranslatorQueueRestoreTest.kt`
extended (original 3 merge tests untouched) with the interrupted-flagged-run
harness idiom from `OcrPreflightFlagOffMidRunTest` (real
`ChapterTranslationStore.lazy` + real `ChapterProfileBatchCoordinator` pass
killed at p2 → genuine durable `ChapterRunRecord` + p1 checkpoint + activeRun
pointer), then:

`queue restore of a chapter with an interrupted flagged run never auto starts
the flagged path`:

1. restore-merge idiom: the chapter's queue entry survives
   `mergeRestoredQueueEntries` (durable order) — the entry is what the user
   explicitly starts;
2. flag now OFF: `decideResume(record, currentFlagOn = false)` ==
   `DropToLegacy` — the decision is a pure companion function (constructs no
   coordinator, takes no queue input, T924-FF-10) and writes nothing, proven
   by byte-equal manifest re-read, unchanged `ocrCheckpoints` key set, and
   `pageLeaseOwner(p1/p2) == null` (no OCR, no run-record writes);
3. flag still ON: `decideResume(record, true)` ==
   `RunFlaggedPath(record)` — honored as a DECISION only; executing it
   requires explicit user admission; the restore-level lookup still
   publishes nothing (manifest byte-equal again, no leases).

Boundary honesty: the queue→status mapping and the `decideResume` wiring into
`ChapterTranslator.restoreQueue` are production code this slice does not own
(F1/gap 1 stay with the S5 stage that first publishes
`ChapterRunState.COMPLETE`); the test pins the durable-record + decision +
no-side-effect triangle the wiring must preserve, per the gap-2 obligation.

## 4. Gap 4 — `PlannedAnalysisChunk` → `AnalysisChunkResult` mapping

New `app/src/main/java/eu/kanade/translation/translator/contextual/
AnalysisChunkMapping.kt` (planner-owned package, pure, no IO):

- `PlannedAnalysisChunk.toAnalysisChunkResult(...)` — carries `chunkId`,
  `chunkOrdinal`, `corePageKeys`, `contextOverlapPageKeys` (core-then-context
  order), `contributingCorpusFingerprint` verbatim; `ocrArtifactRefs`
  positionally per contributing page (DTO enforces
  `size == core + overlap`).
- **Status initialization (PENDING-analog per DTO):** the S1 DTO has no
  PENDING state — a chunk document exists only once its validated response is
  persisted, and `INVALID` "is never consumed" (schemas contract §1.3). The
  mapper therefore derives `status` from `validationFailureReason`: null →
  `VALID` (no failure field, DTO-valid); non-null → `INVALID` + reason (the
  parked, not-yet-usable analog; DTO-valid).
- `PlannedAnalysisChunk.evidenceBoundaryError(evidenceRefs)` — the mapping
  side of the excerpt-hash validation boundaries: every ref must resolve into
  core ∪ overlap via the planner's `evidenceResolves` (V1/V9 pure subset:
  contributing page, block membership, blockId-prefix guard); excerpt-hash
  SHA-256 shape stays with `AnalysisChunkResult.validationError()` (DTO) and
  V8 recomputation with the WP5 validator.

Tests: `app/src/test/java/eu/kanade/translation/translator/contextual/
AnalysisChunkMappingTest.kt` (6 tests): identity/sets/order/fingerprint
preservation incl. the `chunk-<ordinal>-<corpus8>` id; status-init both ways
with DTO-valid results; DTO boundary rejections (`VALID` + reason;
`ocrArtifactRefs count != contributing count`; non-hex `sourceExcerptHash`)
and DTO acceptance for the well-formed counterparts; evidence-boundary
violations (stranger page, misattributed `p2_b0` under `p1`, unknown block)
vs resolving core+overlap refs; canonical round-trip through
`ArtifactDocumentJson` encode→decode→`equals` with `isSemanticallyValid`.

## 5. Verification

Environment: worktree `TachiyomiAT-t924-impl`,
`JAVA_HOME=/c/Program Files/Android/Android Studio/jbr`.

| Command | Result |
| --- | --- |
| `./gradlew :app:compileStandardDebugKotlin` | **BUILD SUCCESSFUL (exit 0)** with this slice's changes in place — all main-source edits (F2, F3 gate, mapper) compile |
| `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.translator.contextual.*" --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.artifact.*"` | **NOT COMPLETED — blocked, left to orchestrator.** The module-wide test compilation fails on files this slice does not own, so none of the four suites (mine included) could execute. 10 wait-retry attempts were made per the wait-60s-retry protocol before the orchestrator's stop order |

Exact foreign errors blocking test compilation (last observations, from the
parallel rendering/UI slice working in the same worktree — all files outside
this slice's ownership, changing between attempts):

- `app/src/test/java/eu/kanade/translation/rendering/DrawPlanCompatibilityTest.kt`
  :144/:260 — `Name contains illegal characters` (backtick display-name test
  names containing `:` / `.`);
- `app/src/test/java/eu/kanade/translation/rendering/DrawPlanDtoRoundTripTest.kt`
  :286-301 — `Unresolved reference 'SidecarRead'` / `'document'` /
  `'MaskGeometry'` (references a main-source API mid-refactor);
- one attempt: `app/src/test/java/eu/kanade/presentation/manga/components/
  ChapterTranslationIndicatorRoutingTest.kt` — 1303 module-wide errors,
  `Unresolved reference 'translationIndicatorTapAction'` (UI refactor
  mid-flight);
- non-deterministic infrastructure noise from two agents sharing one build
  dir: `Daemon compilation failed: null`, `FileAnalysisException ... Could
  not read ... bundleStandardDebugClassesToCompileJar/classes.jar`, deleted
  `shrunk-classpath-snapshot.bin`.

Consequence for MY slice's test evidence: `:app:compileStandardDebugKotlin`
green proves the main-side F2/F3/mapper changes compile and
`AnalysisChunkMapping`/gate wiring resolve. The 10 new/extended test classes'
runtime results are UNVERIFIED — the new test files are `??` untracked and
the extended files compile-checked only up to the foreign failure wall.
Orchestrator should re-run the four-suite command once WP9 lands; expected
green, but treat as open until then.

Golden acceptance (static, holds regardless of test execution): the fixture
`app/src/test/resources/t924/golden/envelope-plan-small.json` and the test
literal `GOLDEN_PLAN_FINGERPRINT = 5643a00c…9df8` are byte-UNMODIFIED in the
working tree (git status clean for the resource), and the encoding inputs of
every consolidated builder are provably identical field sequences into the
identical core (§1) — the F2 no-re-pin criterion is satisfied by construction
and bound at runtime by `GlobalEnvelopePlannerGoldenTest` once the suite can
run.

## 6. Diff summary (this slice, owned files only)

- `app/src/main/java/eu/kanade/translation/artifact/StageFingerprints.kt` —
  +126/−1: four envelope builders, `EnvelopePlanInputPage` carrier, public
  `canonicalFingerprint`, private→public `sha256Hex`.
- `app/src/main/java/eu/kanade/translation/translator/contextual/
  GlobalEnvelopePlanner.kt` — +20/−73 approx: delegation to the consolidated
  builders; `PlannerFingerprints` object deleted.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/
  BatchChapterTranslator.kt` — +39/−3 approx: dispatch-gate helper + gate at
  the single dispatch point (dispatch-gate scope only).
- `app/src/test/java/.../GlobalEnvelopePlannerGoldenTest.kt` — 1 call + 1
  import (harness hasher swap; fixture + literal untouched).
- `app/src/test/java/eu/kanade/translation/ChapterTranslatorQueueRestoreTest.kt`
  — +199: flagged-run harness + gap-2 test (original 3 tests untouched).
- New: `AnalysisChunkMapping.kt`, `AnalysisChunkMappingTest.kt` (6),
  `ProfilePipelineDispatchGateTest.kt` (4).

Untouched by design: `ChapterProfileBatchCoordinator.kt`, `rendering/**`,
`BatchRenderJoin.kt`, `ui/**`, `ChapterTranslationStore.kt`,
`ChapterArtifactStore.kt`, all S1 DTOs, both golden fixtures.

## 7. Deviations

1. Gap-2 test binds `decideResume` + queue-merge + durable no-write
   evidence rather than the (not yet existing) production
   `restoreQueue`→`decideResume` wiring — that wiring is F1/gap 1, owned by
   the S5 stage slice; see §3 boundary note.
2. `StageFingerprints.canonicalFingerprint` and public `sha256Hex` are
   slightly more surface than the review's minimum ("two composite
   builders") — required to delete the `PlannerFingerprints` core outright
   (the golden harness hashed ad-hoc fixture markers through it) while
   keeping one encoding core.
3. `envelopePlanInputFingerprint` takes `List<EnvelopePlanInputPage>`
   (new carrier) instead of planner types — `artifact/**` must not depend on
   `translator/contextual` (mirrors the `TranslationProvenancePage`
   precedent).

## 8. Risks / residual

- R-F3a: the parity gate means flag ON + non-AI engine never creates a
  run record (legacy path constructs none). When the flagged coordinator
  later serves non-AI engines, drop `contextualAiParity` deliberately and
  re-run `ProfilePipelineDispatchGateTest`.
- R-F2a: byte-identity is by-construction (same encoding core) and pinned by
  the golden fixture; a future change to either core now moves BOTH
  fingerprint spaces together, which is the point of the consolidation.
- Environment note: two parallel agents share the worktree build dir;
  transient Kotlin-daemon crashes and stale `classes.jar` reads occurred and
  were resolved by wait-retry (no code cause). Foreign
  `rendering/DrawPlan*Test` compile errors during verification belong to the
  parallel rendering slice, not this one.
