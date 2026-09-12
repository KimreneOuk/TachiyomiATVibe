# T924 Wave 3 — Independent Review (S5 preconditions, S3 durable ledger, WP9 persisted layout)

Reviewer: independent Reviewer (did not author any reviewed diff)
Date: 2026-09-06
Worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`
Range: `ee858f9..HEAD` (3 commits: c2705c8 S5 preconditions, 2cc209e S3 ledger, 74302ec WP9 + orchestrator store fix)
Main-source paths relative to `app/src/main/java/eu/kanade/translation/`; test paths relative to `app/src/test/java/eu/kanade/translation/`.

---

## Verdict

## ACCEPT-WITH-FIXES

All three slices conform to their contracts and to the wave-2 obligations they
executed; no BLOCKER and no MAJOR finding. Acceptance is conditioned on one
MEDIUM fix (F-W3-1, OCR-stage durable-failure cleanup) being scheduled before
FF-02/FF-01 get any user-facing surface, and two LOW comment/process items at
next touch. The special review items 1–6 all ruled safe; every obligation
assigned to this wave is DONE with primary evidence; the full orchestrator
suite was independently reproduced at 833/0.

---

## 1. Findings

### F-W3-1 — MEDIUM (design limitation, pre-existing pattern made more reachable): OCR-stage durable-failure entries have no success-path clear; a recovered chapter stays status-PAUSED

`persistDurablePreflightFailure` writes `durableFailures["$pageKey:OCR"]`
(`pipeline/batch/ChapterProfileBatchCoordinator.kt:656-676`). The generation-less
store fix makes that entry survive every later `cancelCandidate`
(`artifact/ChapterArtifactStore.kt:1364-1369` derives strip keys only from
candidate-owned stage records; the failure record is now stamped
`generationId = null`, `ChapterArtifactStore.kt:982`). But nothing ever clears
an OCR key on success:

- `checkpointOcr` CLOSE (the success transaction, `ChapterArtifactStore.kt:412`,
  CLOSE branch ~:505-540) re-owns the `ocr` stage record but performs **no**
  `durableFailures` mutation;
- `promoteLiveCandidate` clears only the TRANSLATION key
  (`ChapterArtifactStore.kt:1169`: `durableFailures - "$pageKey:${ArtifactStage.TRANSLATION.name}"`);
- `StoreStatusProjector.artifactStatus()` returns
  `Translation.State.PAUSED` whenever ANY durable failure is
  `FAILED_RETRYABLE` (`store/StoreStatusProjector.kt:72-74`, `:92`), before
  any readable-output check.

Consequence: after the user retries and p2's OCR fully succeeds (checkpoint
CLOSE + later promotion), the stale OCR entry keeps the chapter projected
PAUSED indefinitely. Bounded (map keyed `pageKey:stage`, ≤ pages × stages;
counts conservative), never a wrong-draw or data-loss issue, and the same
residue already exists for crash-recovery OCR entries written by
`loadOrMigrate` (`ChapterArtifactStore.kt:1442-1474`) — so this is a
pre-existing projection characteristic, not a wave-3 regression. The s3 report
(R-B) documents "stale records linger for later-stage cleanup" but does not
spell out the PAUSED-projection consequence; the WP9 commit message and the
store-fix comment (`ChapterArtifactStore.kt:975-981`, "Success-path cleanup
stays explicit") acknowledge the deferred cleanup without landing it.

**Fix (before flag surface widening):** clear `"$pageKey:OCR"` in the
`checkpointOcr` CLOSE Committed outcome (mirror of the TRANSLATION clear at
`:1169`), plus a store test. Store is shared/legacy — schedule deliberately.

### F-W3-2 — LOW (comment drift): `ChapterProfileBatchCoordinator.kt:240-242` says the failure is made durable "BEFORE the teardown"

The actual write happens in `finally` AFTER the B0 cancel
(`:288-298`: `cancelPageStageWork` :297, then `recordPageFailure` :298), and
the `finally` comment (:289-296) states the correct post-cancel order and
rationale. The :240-242 comment contradicts it and would mislead the next
reader. Fix: reword :240-242 to "record the failure for the `finally` writer
(the legacy shell persists after the coordinator's teardown)".

### F-W3-3 — LOW (process): task workspace is split across two `Plan/` trees

`s5-preconditions-report.md` exists only under
`<outer>/TachiyomiAT-1.16.8-dev/Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/evidence/stage5/`,
while `stage0/`–`stage4/`, `implementation-sequence.md` and this review live
under the main-repo `…/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/Plan/…`
tree. The two trees are not linked; the assignment's `$TASK/evidence/stage5/…`
path does not resolve inside the main-repo tree. Fix: consolidate the stage5
directory into the canonical tree (and this report is written to the canonical
`evidence/`, with a mirror copy placed beside the s5 report).

### F-W3-4 — NOTE: hydrator user-edit check skips plan blocks absent from the current block list

`PersistedLayoutHydrator.kt:139-144` compares only blocks found in the current
list (`current != null`). A plan block whose translation block has disappeared
entirely is not caught by check 10 — it is caught downstream by the F5 count
contract (`:150-159`: `rehydrate` resolves against current blocks, missing
input shrinks the list, mismatch = `Lossy` = fallback). Safe, but the
cover relies on check 11, not check 10; document or fold into one check.

### F-W3-5 — NOTE: `TranslationOverlayView.init` installs the font digest loader unconditionally (`ui/reader/viewer/TranslationOverlayView.kt:80-89`)

Not flag-gated. Cost is one branch in `init` (the digest itself is computed
lazily only on the FF-02-ON publication path, `PersistedLayoutRuntime.kt:60-65`),
so FF-02-OFF behavior is preserved; the same idempotent install exists on the
batch side (`pipeline/batch/BatchRenderJoin.kt:571-580`). No action.

### F-W3-6 — NOTE: ledger write is best-effort by design

`recordPageFailure` swallows non-cancellation writer failures with a WARN and
the honest `FAILED` outcome stands (`ChapterProfileBatchCoordinator.kt:356-367`).
Consistent with ST-06 (per-page checkpoints authoritative, ledger advisory) and
R2's "durability ADDITION, never a new failure source". No action.

---

## 2. Special review items — rulings

### Item 1 — Orchestrator's store fix (generation-less durable-failure stage records): SAFE, with F-W3-1 as the bounded cost

Change verified: `persistLiveCandidate` stamps the failure `StageArtifactRecord`
with `generationId = null` (`ChapterArtifactStore.kt:982`); the 74302ec diff
touches exactly 4 hunks in this file (imports, `SidecarRead`, sidecar helpers,
this fix) — `cancelCandidate`/`isCandidateOwned` themselves are untouched.

(a) **No existing test relied on candidate-cancel stripping a durable failure —
VERIFIED.** All `durableFailures` assertions in the test tree concern
persist/readback (`ChapterArtifactStoreTest.kt:487,491,700-701`), promotion
clearing the TRANSLATION key (`:1088`, `:1104`), restart re-derivation
(`:1131`), or migration entries (`LegacyArtifactMigrationTest.kt:289,304`).
The `cancelCandidate` test at `ChapterArtifactStoreTest.kt:1189` asserts
displayState only. The legacy shell never candidate-cancels a page that has a
durable record anyway (`pipeline/batch/BatchChapterTranslator.kt:824-830`:
`durableFailurePageKeys` pages take the release-only branch), so the strip path
was reachable only for cross-run residue — exactly the bug (s3 defect 7.2).
Independent reproduction: 833/0 (§5), including `ChapterArtifactStoreTest` and
`D9AttemptLedgerTest`.

(b) **R-B consequence bounded and documented — VERIFIED with the F-W3-1
addendum.** Documented in s3-remainder-report §8 R-B, the commit message, and
the in-code comment (`ChapterArtifactStore.kt:975-981`). Bounded: ≤ one entry
per (page, stage); counts conservative (may cap early, never unlimited —
`ChapterProfileBatchCoordinator.kt:633-634`). The understated PAUSED-projection
consequence is F-W3-1.

(c) **`isCandidateOwned` semantics otherwise untouched — VERIFIED.** Same
predicate (`record != null && record.generationId == generationId`) at both
sites (`checkpointOcr` CLOSE :507-508 region; `cancelCandidate` :1364-1365);
candidate-owned detection/inpaint/translation/layout records are still stripped
with their ledger keys; only the deliberate `generationId = null` stamp changed.

### Item 2 — Test-assertion change `errorMessage` → `activeError` (`OcrPreflightRejectedMidRunDurabilityTest.kt:247`): HONEST

`errorMessage` is a class-body var, not a constructor property
(`model/PageTranslation.kt:100-117`): not serialized, dropped by
copies/`detachedCopy()` round-trips. Its setter routes a non-null value into
the FAILED stage's durable field (`:105`:
`ocrStatus == StageStatus.FAILED -> ocrError = value`); the writer sets
`ocrStatus = StageStatus.FAILED` before assigning the message
(`ChapterProfileBatchCoordinator.kt:668-669`), so the message lands in
`ocrError` (constructor property, `PageTranslation.kt:25`, serialized).
`activeError` reads `ocrError` first (`:119`). The durable surfaces are
therefore genuinely asserted: `failure.lastFailureMessage` at test :236 and
`p2.activeError` at :247. Asserting `errorMessage` instead would have tested a
transient field that is legitimately dropped by the snapshot round-trip — the
original failure ("Expected value to not be null") was exactly that. Not
masking any loss: the message survives restart in `ocrError` and in the
manifest record, and the restarted-run test (:261-282) reads it from fresh
stores.

### Item 3 — Slice-B ordering deviation D2 (ledger write AFTER B0 teardown): SAFE

In the same attempt, after `cancelPageStageWork` (:297) and the ledger write
(:298), the only further actions are `releaseBatchLease` (:300) and
`listener.ocrFinished` (:301) — nothing strips. The flagged path never enters
the legacy shell's per-page `cancelPageStageWork` teardown
(`BatchChapterTranslator.kt:823-831` iterates `batchWriteIdentities`, which the
flagged coordinator never populates) and the shell only `releaseAllPageLeases`.
The next attempt's `cancelCandidate` cannot strip the entry because the record
is generation-less (item 1). The re-opened-candidate side effect (the failure
write goes through `ChapterTranslationStore.persistDurableStageFailure` :688 →
`persistArtifactMutationLocked` → `openCandidate` :1935-1949 +
`persistLiveCandidateAndFailure` :1954) is the same store path the legacy
`persistUnexpectedBatchStageFailure` uses via `updatePageFromCurrentSnapshot`
(`BatchChapterTranslator.kt:868-905`) — legacy-equivalent, VERIFIED
structurally and by the two restart-cycle tests passing.

### Item 4 — F2 acceptance (goldens byte-valid, un-re-pinned): PASS

- `git diff ee858f9..HEAD -- app/src/test/resources/t924/golden/envelope-plan-small.json
  app/src/test/resources/t924/golden/analysis-chunks-small.json` = **0 lines**
  (fixtures untouched in c2705c8 and across the whole range).
- Literal un-re-pinned: `GlobalEnvelopePlannerGoldenTest.kt:394-395` still
  carries `5643a00c7f98e158e61246c6ad7413f933ff1eaade91b3efa06f45e6b0339df8`;
  the test-file diff is exactly 1 call + 1 import
  (`PlannerFingerprints.sha256` → `StageFingerprints.canonicalFingerprint`).
- Byte-identity by construction verified line-by-line: the retired
  `PlannerFingerprints.encode` core (old `GlobalEnvelopePlanner.kt:417-435`)
  and `StageFingerprints.fingerprintIndexed`
  (`artifact/StageFingerprints.kt:618-634`) are the same discipline (`len:value|`
  fields, `[$index]` list elements, `<null>`, SHA-256 lowercase `%02x` over
  UTF-8), and every consolidated builder feeds the identical field sequence
  (policy: old `:66-77` vs new `envelopePolicyFingerprint`; contributing: old
  `:244-253` vs new `envelopeContributingCorpusFingerprint`; plan input: old
  `:368-383` vs new `envelopePlanInputFingerprint`; content: old `sha256Bytes`
  `:287` vs new `envelopePlanContentFingerprint`).
- Empirically pinned: `GlobalEnvelopePlannerGoldenTest` re-run green, 16/16
  (§5), including the byte-stability + literal assertions.

### Item 5 — F3 gate parity: PASS

The legacy contextual-lane gate is `isAi` (`BatchChapterTranslator.kt:348-349`:
`translationEngineCategory().get() == TranslationEngineCategory.AI_MODEL &&
textTranslator is ContextualTextTranslator`). Dispatch passes that exact value
(`:652` `contextualAiParity = isAi`, computed once, ~same pass) into the pure
helper `profilePipelineDispatchKind` (`:930-937`) which composes
`dispatchKind(translationBatchProfilePipeline = flagOn && contextualAiParity)`.
`ChapterProfileBatchCoordinator.dispatchKind` itself is untouched (coordinator
absent from c2705c8/74302ec file lists). Flag-OFF path verbatim: the diff only
changes the `when` subject expression; the `LEGACY_SEQUENTIAL` branch body is
byte-identical (diff reviewed). `frozenConfig.flagProfilePipeline` still
records the raw flag (`:663-664`), which is necessarily `true` in the flagged
branch. Truth table pinned by `ProfilePipelineDispatchGateTest` (4 tests,
green). R-F3a (flag ON + non-AI now never creates a run record) is correct
and reported.

### Item 6 — WP9 D2 bridge seam (fail-safe) + publication still runs: VERIFIED

- `PersistedLayoutReaderBridge` has **zero production install sites** (grep
  over `app/src/main`: only the definition `rendering/PersistedLayoutHydrator.kt:234-255`
  and test installs in `PersistedLayoutHydrationTest.kt:89,182,203`). In
  production `source == null` → `hydrate` returns null (:253-254, `runCatching`
  also swallows throwing sources) → `TextLayoutCoordinator` falls back to the
  planner (`rendering/TextLayoutCoordinator.kt:103-109`, throw caught :105-108,
  mandatory fallback per FF-02b) → the overlay's hydrate lambda returns null
  (`TranslationOverlayView.kt:108-112`). FF-02 ON without Stage-7 wiring
  behaves as OFF for reading. Fail-safe at every layer.
- Publication is independent of the bridge and still runs when FF-02 ON:
  `BatchRenderJoin.kt:311` dispatches on the render `Accepted` snapshot,
  `:368-383` flag gate + non-fatal catch, `:385-562` the TX-23 body and the
  one-transaction `publishSidecarPointers` (:500-544). Write-only interim is
  the designed state (wp9-report §6 row 4 records the owed install site).

---

## 3. Wave-2 obligations executed this wave — DONE/OWED

| Obligation | Status | Evidence |
|---|---|---|
| gap 2 — flagged-run queue-restore test | **DONE** | `ChapterTranslatorQueueRestoreTest` +199 lines: real interrupted flagged pass (worker killed mid-preflight), durable record + p1 checkpoint, `decideResume` OFF→`DropToLegacy` / ON→`RunFlaggedPath`, no-side-effect proof (byte-equal manifest, no leases). 4/4 green on explicit run (§5). Boundary honestly documented (production `restoreQueue`→`decideResume` wiring stays with F1/gap 1). |
| gap 4 — `PlannedAnalysisChunk`→`AnalysisChunkResult` mapping | **DONE** | `translator/contextual/AnalysisChunkMapping.kt` (pure, core-then-context order, status init VALID/INVALID, `evidenceBoundaryError` over `evidenceResolves`) + `AnalysisChunkMappingTest` 6 tests green (ids/order/fingerprint preservation, status both ways, DTO boundaries, evidence boundaries, canonical round-trip). |
| gap 7 — hydration-loss contract (F5) | **DONE** | `HydratedLayout.Lossy` (`PersistedLayoutHydrator.kt:150-159,209-214`) + caller-side typed fallback (`TextLayoutCoordinator.kt:100-109`) + `DrawPlanDtoRoundTripTest` :307 (`unresolvable inputs are a typed LOSSY, never a silently partial draw`). |
| gap 6 JVM part — font digest fn + pinned-const mechanism | **DONE (mechanism)** | `DrawPlanFingerprint.fontAssetSha256` wired to production loaders: `BatchRenderJoin.kt:571-580` and `TranslationOverlayView.kt:80-89`, cached in `PersistedLayoutRuntime.productionFontSha256()` (`PersistedLayoutRuntime.kt:60-65`), fail-safe null until pinned. Note: the actual digest hex value is not recorded in any report — folds into the Stage-7 device rows (wp9-report §6), where the real resource is read. |
| R2 — preflight stop wired into durable failure ledger | **DONE** | Recorder ctor param + default writer (`ChapterProfileBatchCoordinator.kt:107-108`), triggers at `:243-247` (REJECTED) and `:271-275` (worker exception), writer `:626-683` mirroring `persistDurableStageFailure` idiom, cap `:633-637` at `MAX_CONSECUTIVE_UNRESOLVED` with INTERRUPTED restamp. Gap-9 tests: `OcrPreflightRejectedMidRunDurabilityTest` 2/2 green incl. 4 restart cycles (cross-restart cap works post-store-fix). |
| F2 — hasher consolidation | **DONE** | `StageFingerprints.kt` +4 builders + public `canonicalFingerprint`/`sha256Hex` + `EnvelopePlanInputPage`; `PlannerFingerprints` deleted (grep: comments only); goldens unmodified and green (item 4). |
| F3 — non-AI dispatch gate | **DONE** | Item 5. `ProfilePipelineDispatchGateTest` 4/4 green. |
| (Delivered early) Stage-7 oracle files | **DONE (files)** | `DrawPlanDtoRoundTripTest` (7) and `DrawPlanCompatibilityTest` (14) — the gate 7.1/7.2 oracle file names from wave-2 wp8 deviation 2 — exist and are green; device legs remain. |
| Still OWED (not this wave's assignment) | **OWED** | F1/gap 1 (`decideResume` production wiring when COMPLETE first publishes), gap 3 (request-builder core-then-context pin + schemas §1.3 note), D4 (real model identity), bridge production install + all wp9-report §6 device rows, gate 3.4/3.6 device measurement (R1), F-W3-1 store cleanup. |

---

## 4. Standard checks

- **TX-23 fence completeness in `publishPersistedLayout`:** authority
  (`BatchRenderJoin.kt:392`), artifact pageVersion (:401-408), candidate
  generation (:411-418, fail-closed when candidate null), dependency
  fingerprint (:419-427), OCR block identity (:432-439), color/cleaned
  identity embedded in content-addressed names + compat fp (:440-443,
  `LayoutPlanPublication.kt:130-145`), single `publishSidecarPointers`
  transaction with whole-manifest CAS (:500-544), Committed façade update
  (:550), Rejected WARN non-fatal (:555-560). Complete as claimed.
- **FF-02 OFF byte parity:** `git diff ee858f9..HEAD -- rendering/TextLayoutPlanner.kt
  ui/reader/viewer/ReaderPageImageView.kt` = **0 lines** (both zero-diff
  claims VERIFIED). Publication returns before any store mutation when OFF
  (`BatchRenderJoin.kt:373`); reading unchanged (item 6). Both golden fixtures
  zero-diff. `SequentialBatchCoordinator.kt` untouched (absent from all three
  file lists).
- **Scope audit:** `git diff --name-status ee858f9..HEAD` = 22 files; per-commit
  lists match the three reports' owned sets exactly (c2705c8: 8, 2cc209e: 2,
  74302ec: 12). No file outside declared ownership. Note: `BatchChapterTranslator.kt`
  appears only in c2705c8 (F3), `ChapterProfileBatchCoordinator.kt` only in
  2cc209e — the orchestrator store fix is inside 74302ec's `ChapterArtifactStore.kt`
  and is disclosed in the commit message.
- **Safety rules:** no OCR parallelization — `grep async|launch|runBlocking` in
  `ChapterProfileBatchCoordinator.kt` = 0; serial `for` loop intact, native
  handoff released in `finally` before the next page (:287), `releaseBatchLease`
  strictly after the checkpoint attempt (:300 vs :223; TX-06). Committed
  display untouched: slice B asserts `committed == null` on every page record
  (`OcrPreflightRejectedMidRunDurabilityTest.kt:255-257`), only
  `OcrCheckpointMode.CLOSE` used (:393); WP9 writes only layout/pointer/sidecar
  state, `Rejected` render path unchanged. No user-edit overwrite: publication
  never touches translation blocks; hydrator check 10 re-plans on translation
  change (`PersistedLayoutHydrator.kt:139-144`), covered end-to-end by
  `DrawPlanDtoRoundTripTest` :334 (user-edited translation invalidates, OCR
  identity untouched).
- **Test adequacy vs gates 7.3–7.7 JVM legs:** 7.3 user-edit (:334), 7.4
  bind-generation stale defense (`PersistedLayoutHydrationTest` :281),
  7.5/7-5b stored compat fp mismatch (`DrawPlanCompatibilityTest` :260),
  7.6 planner-zero hydration ×3 (:97, :115, :142), 7.7 fallback green ×4 +
  corrupt leg (:172, :181, :202, :208; round-trip :390). 31 gate-oracle tests,
  all green. Device legs correctly owed (wp9-report §6).

---

## 5. Independent verification run

Environment: worktree `TachiyomiAT-t924-impl`, `JAVA_HOME=/c/Program Files/Android/Android Studio/jbr`.

| Command | Result |
|---|---|
| `./gradlew :app:compileStandardDebugKotlin` | exit 0, BUILD SUCCESSFUL |
| `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.artifact.*" --tests "eu.kanade.translation.model.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.rendering.*" --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.translator.contextual.*" --tests "eu.kanade.translation.ChapterTranslationStore*" --tests "eu.kanade.translation.OcrCheckpointRestartReuseTest"` | BUILD SUCCESSFUL, exit 0 |
| JUnit XML tally | **classes=107 tests=833 failures=0 errors=0 skipped=0** — matches the orchestrator's reported run exactly |
| New-suite XML: OcrPreflightRejectedMidRunDurabilityTest 2, ProfilePipelineDispatchGateTest 4, AnalysisChunkMappingTest 6, DrawPlanCompatibilityTest 14, DrawPlanDtoRoundTripTest 7, PersistedLayoutHydrationTest 10 | all 0 failures |
| Explicit supplement (classes outside the orchestrator filter set): `ChapterTranslatorQueueRestoreTest`, `GlobalEnvelopePlannerGoldenTest` | **4/4 and 16/16 green** |
| `git diff ee858f9..HEAD` on TextLayoutPlanner.kt, ReaderPageImageView.kt, both golden fixtures | empty (0 lines) |

---

## 6. Evidence classification summary

Verified against primary evidence (code line-reads, per-commit diffs, XML
tallies), not reports: the generation-less stamp and untouched
`isCandidateOwned` (item 1); `errorMessage`/`ocrError`/`activeError` model
semantics (item 2); post-cancel write ordering and empty flagged-path shell
teardown (item 3); fixture zero-diff + un-re-pinned literal + core
byte-identity + 16/16 golden run (item 4); `isAi` parity anchor and verbatim
OFF branch (item 5); null-bridge fail-safe with zero production install sites
plus running publication (item 6); TX-23 fences; zero-diff claims; scope
audit; 833/0 reproduction. STRONG INFERENCE: the PAUSED-projection consequence
of F-W3-1 (code path read directly, not observed on a device). ASSUMPTION
carried from wave-2 (not re-reviewed): S1 store transaction internals outside
the hunks touched here. Process note F-W3-3 documents the split Plan trees.

Per T924-FF-21 this verdict covers wave-3 code conformance; stage-exit gate
tables and device evidence remain open per their own kickoffs.
