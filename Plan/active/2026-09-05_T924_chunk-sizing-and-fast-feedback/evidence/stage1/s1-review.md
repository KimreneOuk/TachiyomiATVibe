# T924 Stage 1 — independent Reviewer report

Date: 2026-09-05 · Reviewer: independent (role `docs/roles/reviewer.md`) · Scope: T924 Stage 1 in worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`, base `adbe643`.

## Verdict

**ACCEPT-WITH-FIXES** — Stage content conforms to TX/SC/FP contracts and all Director safety rules; two MINOR fixes required before the Stage-1 exit commit: produce gate-1.5 evidence (F-2) and pin the facade content-fingerprint pageKey (F-1).

## Verified state

- `git log`: exactly `732f7ff` (R012) and `bc94045` (WP1) above `adbe643`; no agent-made commits beyond those. Working tree: exactly 3 modified main files (`ChapterTranslationStore.kt`, `artifact/ChapterArtifactStore.kt`, `artifact/StageFingerprints.kt`) + 3 new test files (`CheckpointOcrTransactionTest.kt`, `OcrCheckpointRestartReuseTest.kt`, `SemanticFingerprintTest.kt`). No scope leaks: commit `732f7ff` touches only `model/PageWorkPlanner.kt` + `PageWorkPlannerForceReuseTest.kt`; commit `bc94045` touches only the declared artifact files/tests/fixture. `store/PageStageLeaseTable.kt` untouched (TX-06). No feature flag added or touched (grep for `flagProfilePipeline`/`T924_FF`/`profilePipelineEnabled` over `app/src/main/java`: zero hits).

## Findings

### F-1 — MINOR — Facade and artifact canonicalizers use different pageKey sources for the TX-03.1 content fingerprint

- Evidence: facade builds `checkpoint.ocrContentFingerprint` with `pageKey = page.sourceFileName.orEmpty()` (`app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt:1117`), while the adopt-side comparison builds the committed bundle's fingerprint with `pageKey = checkpoint.sourceIdentity.pageKey` (= the transaction `pageKey`, `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt:616-618` and facade write at `ChapterTranslationStore.kt:1044-1050`).
- Today both are equal: `sourceFileName` defaults to `pageKey` at every live-page construction (`ChapterTranslationStore.kt:299`, `:433`, `:1636`, `:1776`; rename keeps it equal at `:1542`; `SinglePageOnnxPhase.kt:411,427,447,517,593`; `LegacyChapterMigrationSource.kt:386`). If a future writer ever sets them apart, every TX-03.1 adopt spuriously rejects as `"committed OCR content drift"` (`ChapterArtifactStore.kt:552-556`).
- Direction is fail-closed (spurious rejection, re-plan; never wrong adoption), so not a defect today.
- Required fix (1 line): in `ChapterTranslationStore.pageOcrContentFingerprint` pass `pageKey = pageKey` (the transaction parameter) instead of `page.sourceFileName.orEmpty()`, or assert equality before building the checkpoint.

### F-2 — MINOR — Gate 1.5 (REQUIRED) has no Stage-1-specific evidence yet

- Contract: `stage0/feature-flags-stage-gates.md` §2.1 row 1.5 — "Extend `ChapterTranslationStorePersistenceTest` + `coexistence/D5GlossaryAwareReuseTest` idiom: user-edited block survives every new migration and invalidation transition". Neither file was extended (git diff: additions only to `ChapterArtifactStoreTest.kt`/`ChapterArtifactLayoutTest.kt`; both gate-1.5 files untouched).
- Existing indirect coverage: v2 fixture preserves the committed bundle end-to-end (`ChapterArtifactStoreTest.kt:1209`, `:1250`, fixture `app/src/test/resources/t924/manifest-v2.json` carries `committed` + `hasManualEdits`); checkpoint adopt/CLOSE tests assert committed display untouched (`CheckpointOcrTransactionTest.kt:339-343`; `OcrCheckpointRestartReuseTest.kt:215`); FP-02 exclusion test proves user edits do not alter the OCR content key (`SemanticFingerprintTest.kt:242-267`). But no test exercises a user-edited block across the new checkpoint/migration transitions explicitly.
- Required fix: before the Stage-1 exit commit, add one test to `ChapterTranslationStorePersistenceTest` (user-edited committed page survives `checkpointOcr` CLOSE/adopt with `committed` and `userEditedAt` intact) and one assertion to the D5 idiom (glossary-repair reuse still authoritative after manifest v3 rewrite). ~2 small tests; no production change.

### F-3 — MINOR — SC-08/gate-1.4 delimiter-collision fixtures exist only for FP-02

- Contract: T924-SC-08 "the delimiter-collision resistance cases in `StageFingerprintsTest` extend to every new builder"; T924-FP-09(c).
- Evidence: `SemanticFingerprintTest.kt:317` (`page ocr content resists delimiter forgery`, single-field + two-block split) is the only forgery fixture; FP-03/04/06/07/08 rely on determinism + sensitivity tests only (`SemanticFingerprintTest.kt:342,364,408,528,582,609`).
- Mitigation: FP-04/06/07/08 are mostly hex/fingerprint/hex-string parameters where length-prefixing makes forgery the only risk, and all go through the same pre-existing `fingerprintIndexed` core (unchanged, `StageFingerprints.kt:507+`). Residual risk low.
- Required fix: add one forgery case each for `profileInputFingerprint` (language strings) and `translationProvenanceFingerprint` (block ids) before Stage-2 consumes them.

### F-4 — NOTE — Gate oracle naming deviations (substance present)

- 1.1: fixture is `app/src/test/resources/t924/manifest-v2.json`, oracle named `manifest-v-current.json` — same intent, real pre-change v2 bytes with committed display, glossary, legacy migration.
- 1.2: oracle named `ChapterArtifactSchemaVersionTest`; implemented as `ChapterRunRecordSchemaTest` (7 tests, incl. `unknown newer version is unusable for planning with bytes preserved untouched`, `ChapterRunRecordSchemaTest.kt:165`) plus sealed `RunRecordRead`/`OcrCheckpointRead` (`ChapterArtifactStore.kt:272`, `:735`) and the strictly-`>` guard set (`ChapterArtifactStore.kt:104,107,207,302,764,1504,1509`; `ChapterArtifactDeletion.kt:112` — all compare against `SCHEMA_VERSION` now 3, no equality checks anywhere).
- 1.6: oracle named `OcrCheckpointRebaseTest`; implemented as `CheckpointOcrTransactionTest` (12) + `OcrCheckpointRestartReuseTest` (3).
- Acceptable; record in the exit report mapping.

### F-5 — NOTE — Force-path source check is stricter than the batch OCR stage (dormant)

- `forceOcrEvidenceMatches` rejects when `sourceFingerprint != null && page.sourceFingerprint != sourceFingerprint`, including null recorded under known current hash (`PageWorkPlanner.kt:427-429`). The batch OCR stage alone does NOT compare source fingerprints (`expectedSourceFingerprint` is taken only for DETECTION/INPAINT, `PageWorkPlanner.kt:474-476`).
- Dormant: the only caller (`SinglePageOnnxPhase.kt:279`) supplies neither expectation set; batch goes through `planChapter`/`stageEvidence`, not this helper. Behavior is defensible (forced reuse spans detection+OCR, and detection does compare source). No action; document when S3 wires evidence providers.

### F-6 — NOTE — Checkpoint reads do not re-hash sidecar bytes (implementer R4, ratified)

- `readOcrCheckpoint`/`readRunRecord` (`ChapterArtifactStore.kt:755-775`, `:293-313`) trust the pointer's `contentFingerprint` written at publication; corrupt bytes quarantine at parse time. Matches the `readRunRecord` precedent and M1 read recovery. If a Stage-3 planner consumes checkpoints as reuse authority, add a read-time re-hash only if the review bar for reuse evidence requires it; not required now.

### F-7 — NOTE — Adopt branch skips post-commit retention reconcile

- `sweepAfterCommit` is true only for CLOSE/REBASE (`ChapterArtifactStore.kt:596-606`). Adopt publishes exactly one content-addressed checkpoint sidecar (idempotent name, `ChapterArtifactLayout.kt` `pageContentAddressedFile`), so it cannot create orphans. Correct as written.

### F-8 — NOTE — Bespoke `Json` instances outside SC-06 scope are pre-existing

- `ModelIdentityCache.kt:49`, `LegacyFlatFileDecoder.kt:24` keep private `Json { ignoreUnknownKeys = true }`. Neither serializes a T924 durable document; SC-06 ("single shared instance for durable documents") holds via `ArtifactDocumentJson` (`ChapterDocumentIo.kt:207-212`) with `AtomicChapterDocuments.json` aliased to it (`:232-234`).

## Contract clause verification (spot-checked against code, not reports)

- **TX-03 REBASE single publication** — VERIFIED: CANCELLED(G) + ACTIVE(G′) sidecars are appended to the same `sidecars` list as snapshot+checkpoint and installed by ONE `publishSidecarPointers` call (`ChapterArtifactStore.kt:468-478, 511-545, 583-595`); test asserts successor seeded + stale fenced + one publication (`CheckpointOcrTransactionTest.kt:232-312`).
- **TX-03.1 both sides canonicalized through the same builder** — VERIFIED: `committedOcrContentFingerprint` calls `StageFingerprints.pageOcrContentFingerprint` on the committed snapshot (`ChapterArtifactStore.kt:615-635`), the same builder the facade uses (`ChapterTranslationStore.kt:1112-1128`); drift rejects (`:549-556`); proven by `CheckpointOcrTransactionTest.kt:314-347` and `OcrCheckpointRestartReuseTest.kt:219-259`.
- **TX-06 lease after publication** — VERIFIED: facade never touches the lease table (ladder only reads `pageLeases`, `ChapterTranslationStore.kt:1021-1022`); `PageStageLeaseTable.kt` absent from all diffs; M1 harness orders lease → merge → checkpoint → release (`OcrCheckpointRestartReuseTest.kt:100-131`).
- **TX-07 committed display never mutated** — VERIFIED: no branch writes `committed`/display pointer (`ChapterArtifactStore.kt:484-547` CLOSE replaces only `ocr` + displayState restore per `cancelCandidate` idiom; REBASE sets `REFRESHING_WITH_COMMITTED_RESULT`/`CANDIDATE_RUNNING`; adopt touches only `ocr` + pageVersion `:558-560`); tests assert identity (`CheckpointOcrTransactionTest.kt:339-343`).
- **TX-02.1 no-grace direction** — VERIFIED: store-level `expectedDependencyFingerprint == null` rejects outright in the standard branch (`ChapterArtifactStore.kt:461-463`, comment cites C2); facade duplicates it (`ChapterTranslationStore.kt:1030-1032`); the `patchPage` grace clause (`:587-590` pre-change) is not copied. Lease mandatory in BOTH branches (`:1021`), matching TX-03.1 preconditions.
- **SC-06 single shared Json** — VERIFIED (F-8).
- **SC-21 managed dirs** — VERIFIED: seven directories + `managedDirectories` (`ChapterArtifactLayout.kt:60-71, 162-169`), content-addressed names, `isSafeSegment` + `isManagedPath` enforced per sidecar in `publishSidecarPointers` (`ChapterArtifactStore.kt:717-723`).
- **Future-schema guard refuses >3 read-only** — VERIFIED (F-4 evidence); v2 loads normalize in memory only (`parseManifest`/`normalizeSupportedSchema`, `ChapterArtifactStore.kt:1455-1477`); bytes rewritten only inside a publication by new code (intended per SC-04/decision 7.2).
- **SC-19/SC-20/SC-22** — VERIFIED: only `documents.publishJson` + `publishManifestInternal` used (`ChapterArtifactStore.kt:724-733`); sidecars first, one manifest publication, rejections before/after leave prior manifest authoritative; crash windows covered by `SidecarCrashPublicationTest` (8 tests) and `CheckpointOcrTransactionTest` (5 fault tests).
- **FP-01 exclusion rule** — VERIFIED by signature audit of all seven builders (`StageFingerprints.kt:183, 251, 281, 317, 346, 395, 442`): no parameter carries generation ids, page versions, lease tokens, timestamps, or `userEditedAt`; `profileContentFingerprint` zeroes `version`/`frozenAtEpochMs`/`sourceRunId` and blanks `contentFingerprint` in a hashing copy only (`:317-333`), stored bytes never mutated. Proven for FP-02 and FP-05 by tests (`SemanticFingerprintTest.kt:242, 479`); other builders exclude by construction (see F-3).
- **SC-04 additive fields** — VERIFIED: seven fields appended at declaration end with neutral defaults (`ChapterArtifactManifest.kt:57-77`), `SCHEMA_VERSION = 3` (`:87`).
- **SC-12/13** — VERIFIED: `readRunRecord`/`readOcrCheckpoint` return `UnsupportedVersion` (bytes preserved, never deleted) / `Absent` (corrupt quarantined via `documents.quarantineCorrupt`).

## Safety rules (Director's) — all PASS

1. No feature flag touched — VERIFIED (zero grep hits; no flag file in any diff).
2. No pipeline/batch behavioral change — VERIFIED: `pipeline/**`, `translator/**` untouched by all diffs; the only production behavior change outside `artifact/**` is the R012 force branch.
3. Manual/Auto non-force path byte-identical — VERIFIED: `git diff adbe643..732f7ff` on `PageWorkPlanner.kt` shows the signature gained two defaulted parameters; `planPage`/`planChapter`/`decideStage`/`stageEvidence`/`fingerprintMatches` untouched (0 deleted lines outside the force branch/KDoc; non-force `plan()` still delegates to `planPage`). Characterization tests: `PageWorkPlannerTest` 10/10 and `non-force plan stays an exact delegation into planPage` (`PageWorkPlannerForceReuseTest.kt:299`) green.
4. No new atomicity primitives — VERIFIED: checkpointOcr composes `publishSidecarPointers` (`ChapterArtifactStore.kt:707`) over `AtomicChapterDocuments.publishJson` + `publishManifestInternal`; no direct IO.
5. Older-schema data never rewritten by older builds — VERIFIED: v3 manifests hit the `> SCHEMA_VERSION` guard in old builds (guard set in F-4); v2 bytes on disk are not touched by load (`normalizeSupportedSchema` is in-memory; only publications write).
6. No user-edit overwrite — VERIFIED: checkpointOcr never writes translation content; committed bundle untouched in all branches (TX-07 above); forced planner change does not alter translation merge CAS (`mergeTranslationLocked` untouched).
7. No committed display revoked — VERIFIED (TX-07 above; retention adds `.corrupt`-of-reachable preservation `ArtifactRetention.kt:61-64` and never treats checkpoint snapshots as candidate-owned after CLOSE — the page `ocr` record is re-owned by the manifest, `ChapterArtifactStore.kt:489-495`).

## R012 deferral ruling (SinglePageOnnxPhase evidence wiring)

SAFE, ratified. The only caller (`SinglePageOnnxPhase.kt:279`, `force` defaults true) passes no evidence, so `forceOcrEvidenceMatches` returns true whenever `ocrReady` — the mandated status/payload pass-through, identical to pre-R012 force strength on every dimension except the decoupled one. The newly reachable plan state (`runOcr=false, runInpaint=true`) is handled by pre-existing resume branches (`SinglePageOnnxPhase.kt:460-512`: inpaint-resume or inpaint+translate+render; forced translation still re-runs; user edits protected by the translation CAS). Behavior matrix: only OCR-ready(+valid)+inpaint-not-ready changes (`runOcr=true→false`); the other three combinations are identical (`PageWorkPlanner.kt:46-58`). Actual caller-visible change: forced reader retries on such pages reuse committed OCR instead of re-recognizing — exactly R012's intent. Wiring a real evidence provider is correctly deferred to the S3 preflight-reuse stage.

## Deviations ratified

| Deviation | Ruling | Basis |
|---|---|---|
| WP1-1 missing `schemaVersion` = current (SC-01) | RATIFIED | Follows `ChapterGlossary`/`ChapterAttemptLedgerDocument` kotlinx-default precedent; wrong/future kind+version still fail (`ChapterRunRecord.kt:107-109`); recorded, not silent. |
| WP1-2 flat `ProfilePointer` + `toSidecarPointer()` | RATIFIED | Kotlin data classes final; manifest fields keep contract types (`ChapterArtifactManifest.kt:389-412`). |
| WP1-3 persisted subset of AP-04 record shapes | RATIFIED | AP-* owns response schema; additive-optional evolution per SC-14 keeps WP5 unblocked. |
| WP1-4 `ColorImageSourceKind` + nullable ref | RATIFIED | Contract's "yes (or explicit ORIGINAL_SOURCE kind)" (§1.7). |
| WP1-5 no SC-07/10/11 helpers in WP1 slice | RATIFIED | Content fingerprints landed in Phase 2 (FP-05 uses shared Json canonical re-encode, `StageFingerprints.kt:317-333`); content-addressed naming pinned by layout test. |
| WP1-6 retention `.corrupt` sibling rule | RATIFIED | Required for SC-17 "reclaimed only when no pointer references them" (`ArtifactRetention.kt:61-64`); crash test `SidecarCrashPublicationTest.kt:212`. |
| WP1-7 `LegacyArtifactMigrationTest` not extended | RATIFIED | Migration writes fresh current-version manifests by construction; gate-1.1 legacy surface is the v2 fixture in `ChapterArtifactStoreTest`. |
| FP I-1 (FP-02 consumes persisted `ocrFingerprint` value) | RATIFIED | Same information content as the 5 raw `StageFingerprints.ocr` inputs, avoids a divergent re-derived config hash; matches the checkpoint DTO field (`PageOcrCheckpoint.ocrFingerprint`). Invalidation matrix row 2 still binds (ocr config change changes the value). |
| FP I-2 (FP-03 hashes (pageKey, fp) pairs) | RATIFIED | Adds order-provability; pageKey already embedded per-page; deterministic stable sort (`StageFingerprints.kt:262`). |
| FP I-3 (FP-08 explicit `DisplayBaseKind` mode field) | RATIFIED | Prevents mode aliasing; contract allows explicit kind. |
| TX D1 (facade/artifact API split) | RATIFIED | Facade cannot own `ChapterArtifactLayout`; split keeps DTO construction with the layout owner; semantics unchanged (TX-09 was PROPOSAL). |
| TX D2 (CANCELLED generation records swept post-close) | RATIFIED | Identical to `cancelCandidate` reachability semantics; CANCELLED record is durably published before the pointer moves (B3 window), then swept; asserted via `outcome.deletedFiles` (`CheckpointOcrTransactionTest.kt:258-260`). |
| TX D3 (explicit `sourceOrientation`/`sourceSha256`) | RATIFIED | `SourceIdentity.isComplete` requires orientation (`ArtifactContracts.kt:133-135`) and `mergeOcrLocked` does not copy `result.sourceFingerprint`; incomplete identity fails closed (`ChapterTranslationStore.kt:1053-1055`). Related latent issue tracked as F-1. |
| Gate naming deviations | RATIFIED (F-4) | Record mapping in exit report. |

## Test-gap list (before Stage-1 exit)

1. Gate 1.5 explicit user-edit-authority tests (F-2) — REQUIRED.
2. Delimiter-forgery fixtures for `profileInputFingerprint` + `translationProvenanceFingerprint` (F-3) — REQUIRED by SC-08 letter, low risk.
3. FP-09(a) determinism: covered for all seven builders individually; FP-09(d) goldens exist for profile content + 200-page corpus (`SemanticFingerprintTest.kt:518, 643`) but are single-machine (implementer risk note) — re-run goldens on a second process/device at the Stage-2 exit audit.
4. No test asserts `pageOcrContentFingerprint` equality across a `candidateGenerationId`/`pageVersion` variation because no builder accepts them — acceptable (exclusion by construction), noted for the gate-1.4 evidence row.
5. M1 covers store-level origin-neutral reuse; planner-level checkpoint consumption (TX-10 planner extension) is correctly out of Stage-1 scope — do not claim it in the exit report.

## Independent verification commands and results (run by this reviewer)

Environment: worktree `C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-t924-impl`, `JAVA_HOME=/c/Program Files/Android/Android Studio/jbr`.

1. `git log --oneline -12`, `git status --porcelain`, `git diff --name-status adbe643..HEAD`, `git show --stat 732f7ff`, `git show --stat bc94045` — exactly the declared slices; no extra commits; uncommitted set = 3 modified + 3 new test files.
2. `./gradlew :app:compileStandardDebugKotlin` — BUILD SUCCESSFUL in 18s (176 tasks).
3. `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.artifact.*" --tests "eu.kanade.translation.model.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.ChapterTranslationStore*" --tests "eu.kanade.translation.OcrCheckpointRestartReuseTest"` — BUILD SUCCESSFUL in 1m 16s; JUnit XML aggregate over `app/build/test-results/testStandardDebugUnitTest/`: **48 classes, 353 tests, 0 failures, 0 errors, 0 skipped** — independently reproduces the orchestrator's reported counts.
4. `git diff` deletions audit: `PageWorkPlanner.kt` (R012 slice) deletions confined to the replaced force-branch lines + KDoc; `ChapterArtifactStoreTest.kt` + `ChapterArtifactLayoutTest.kt` — 0 deleted lines (additions only); `StageFingerprints.kt` — 386 insertions, 0 deletions (additions-only confirmed).

## Bottom line

Stage 1 is contract-conformant, crash-safe by construction and by test, and behaviorally inert for legacy paths. Accept the three slices as reviewed; land F-1 (one line) and produce F-2/F-3 evidence in the same series as the Stage-1 exit commit, then proceed to Stage 2.
