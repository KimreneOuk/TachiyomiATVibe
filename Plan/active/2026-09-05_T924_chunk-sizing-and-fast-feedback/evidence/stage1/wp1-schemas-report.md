# T924 Stage 1 — WP1 versioned artifact schemas, canonical serialization, crash-safe publication

Implementer report (S1, Phase 1a) · Date: 2026-09-05 · Worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline` (uncommitted, as instructed) · Controlling contract: `Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/stage0/contracts-schemas-fingerprints.md`

## Gates claimed

- **Gate 1.1** — checked-in pre-change v2 manifest fixture `app/src/test/resources/t924/manifest-v2.json` loads cleanly under the new code and survives a write cycle rewritten as schemaVersion 3 with old data intact. (ChapterArtifactStoreTest: `pre-change v2 manifest fixture loads cleanly with new pointer fields defaulted`, `v2 manifest survives a write cycle rewritten as v3 with old data intact`.)
- **Gate 1.2** — ChapterRunRecordSchemaTest (7 tests): wrong kind / schema bounds rejected; unknown newer version unusable-not-deleted with bytes preserved; contract §1.1 v1 example round-trips byte-identically (SC-06).
- **Gate 1.3** — SidecarCrashPublicationTest (8 tests): fault injection at sidecar write, sidecar rename, and manifest publish (plus stale-snapshot preconditions and a corrupt-pointer case) ⇒ prior manifest authoritative, at most an orphan sidecar, pointer never dangles.

## Implemented T924-SC clauses

| Clause | Implementation |
|---|---|
| SC-01 | Every new durable document is a `@Serializable` data class with `schemaVersion` (default = `SCHEMA_VERSION` const) and `kind` const, following the `ChapterGlossary`/`ChapterAttemptLedgerDocument` precedent; each has `validationError()`/`isSemanticallyValid` (wrong kind, wrong version, and bound violations fail semantic validation). |
| SC-02 | Schema-level bounds as named constants + pure-function checks: run record phaseCounters keys ≤32 / values ≥0, frozenConfig ≤64 KB serialized (T); chunk core 1..16, overlap ≤2, terms/entities/relationships ≤128, scenes ≤32, narrative ≤2000, conflictNotes ≤32×500, evidenceRefs ≤512, failure reason ≤500; profile entities/terms ≤512, scenes ≤256, candidate lists ≤128, alias/name/note/evidence bounds; envelope ≤4096; draw-plan/color blocks ≤256; timestamps > 0; `inpaintMaskRevision ≥ PageTranslation.CURRENT_INPAINT_REVISION`; `producedByOrigin ∈ {BATCH, READER_ADHOC}`; fact canonical forms required iff ENTITY_IDENTITY/TERM; `availableFrom`/`applicableRange` present iff their scopes; gender value iff GENDER fact; scene participants must resolve to entity factIds; `ocrArtifactRefs.size == core + overlap`; chunk VALID must not carry a failure reason. |
| SC-03 | `SidecarPointer { fileName, schemaVersion, contentFingerprint }` and flat `ProfilePointer` (+`version`, `profileInputFingerprint`, `toSidecarPointer()`) in `ChapterArtifactManifest.kt`, generalizing `GlossaryPointer`. |
| SC-04 | `ChapterArtifactManifest` gains additive defaulted fields `activeRun`, `ocrCheckpoints`, `analysisChunks`, `profile`, `envelopePlan`, `layoutPlans`, `colorPreparations` (appended at declaration end per SC-06); `SCHEMA_VERSION` 2 → 3. New code reads v2 and v3 (`parseManifest` normalizes supported older versions in memory; bytes untouched on disk until the next publication) and writes v3; the future-schema guard (all `>` comparisons) keeps refusing `schemaVersion > 3` read-only. |
| SC-05/SC-20 | `ChapterArtifactStore.publishSidecarPointers(manifest, sidecars, updatePointers)`: every immutable sidecar published FIRST via `AtomicChapterDocuments.publishJson`, then ONE atomic manifest publication installs all pointers. `publishActiveRun(...)` is the run-record specialization (validates record, publishes into content-addressed `runs/`, installs `activeRun`). |
| SC-06 | New module-level shared `ArtifactDocumentJson` (`ignoreUnknownKeys = true; encodeDefaults = true`) in `ChapterDocumentIo.kt`; `AtomicChapterDocuments.json` now references it, so no bespoke Json instances exist for durable documents. Declaration-order fields; round-trip `encode → decode → encode` asserted byte-identical. |
| SC-08 | No new fingerprint builders in this phase (fingerprint FUNCTIONS are Phase 2 per task). DTO `*Fingerprint` fields are plain `String`s validated as 64-lowercase-hex (`String.isSha256Hex()`). |
| SC-12/13 | `ChapterArtifactStore.readRunRecord(pointer)` returns sealed `RunRecordRead`: `Usable` / `UnsupportedVersion` (unknown newer ⇒ unusable for planning, bytes preserved untouched, never deleted/quarantined; retention keeps the bytes while a pointer references them) / `Absent`. |
| SC-17 | Parse failure, kind mismatch, or bound violation ⇒ treated as absent-for-planning and quarantined as `<name>.corrupt` via the existing document layer (`AtomicChapterDocuments.quarantineCorrupt`); retention now preserves `.corrupt` siblings of pointer-reachable sidecars until no pointer references them. |
| SC-19 | Only `AtomicChapterDocuments.publish`/`publishJson` (sidecars) and `publishManifestInternal` (manifest) are used; no new atomicity primitives, no direct `ChapterDocumentIo.write` for durable documents. |
| SC-21 | `ChapterArtifactLayout`: new directories `runs/ ocr/ analysis/ profiles/ envelopes/ layout/ color/` under `X_artifacts/`, all in `managedDirectories` (retention-bounded); names `f-<sha256(contentFingerprint)>.json` via the existing private `fingerprintSegment`; page-scoped kinds (ocr checkpoints, layout plans, color preps) nest under the injective `pageSegment(pageKey)`; `isSafeSegment` + `isManagedPath` validated in `publishSidecarPointers` for every sidecar name. |
| SC-22 | `@Synchronized` + `staleManifestRejection` idiom reused verbatim; every precondition/publication failure returns `TransactionOutcome.Rejected` with the prior manifest authoritative. |

## Files created (worktree `TachiyomiAT-t924-impl`)

Main (`app/src/main/java/eu/kanade/translation/artifact/`):
- `ChapterRunRecord.kt` — `ChapterRunState`, `RunConfigSnapshot`, `AnalysisPolicySnapshot`, `EnvelopePolicySnapshot`, `ChapterRunRecord` + validation/caps.
- `PageOcrCheckpoint.kt` — `CommittedDisplayRef`, `PageOcrCheckpoint` + validation (DTO only; no transaction semantics).
- `AnalysisChunkResult.kt` — `AnalysisChunkStatus`, `ExtractedTerm(Kind)`, `ExtractedEntity`, `ExtractedRelationship`, `AnalysisChunkResult` + validation.
- `ChapterTranslationProfile.kt` — `EvidenceRef`, `PageRange`, `BlockRange`, `PageBlockRef`, `AnalyzerProvenance`, `FactType`, `EvidenceStrength`, `FactScope`, `ProfileGender`, `FactProvenance`, `FactConflictState`, `ToneFlag`, `SceneRegister`, `ProfileFact`, `ProfileScene`, `ProfileEntity`/`ProfileTerm` typealiases, `ChapterTranslationProfile` + validation.
- `EnvelopePlan.kt` — `PlannedEnvelope`, `EnvelopePlan` + validation.
- `ChapterDrawPlan.kt` — `DrawPlanRect`, `DrawPlanFontIdentity`, `DrawPlanMaskComponentRef`, `DrawPlanPositionedLine`, `DrawPlanAlign`, `DrawPlanBlock`, `PageLayoutDrawPlan`; `ColorImageSourceKind`, `CleanedImageReference`, `ColorStyleEntry`, `ColorStylePreparation` + validation.

Tests:
- `app/src/test/java/eu/kanade/translation/artifact/ChapterRunRecordSchemaTest.kt` (new, 7 tests)
- `app/src/test/java/eu/kanade/translation/artifact/SidecarCrashPublicationTest.kt` (new, 8 tests)
- `app/src/test/resources/t924/manifest-v2.json` (new fixture)

## Files changed

- `artifact/ChapterArtifactManifest.kt` — `SidecarPointer`/`ProfilePointer`/`isSha256Hex()`; seven additive pointer fields; `SCHEMA_VERSION = 3`.
- `artifact/ChapterArtifactStore.kt` — `RunRecordRead` + `readRunRecord` (unknown-version/corrupt rules); `SidecarPublication` + `publishSidecarPointers` + `publishActiveRun`; `parseManifest` normalizes supported older manifest versions to the current one in memory (v2 loads, v3 written).
- `artifact/ChapterArtifactLayout.kt` — seven new sidecar directories + content-addressed file builders + `managedDirectories`.
- `artifact/ChapterDocumentIo.kt` — shared `ArtifactDocumentJson`; `AtomicChapterDocuments.json` = same instance/config (behavior-identical).
- `artifact/ArtifactRetention.kt` — new pointers' sidecars are retention-reachable; `.corrupt` siblings of reachable sidecars preserved while referenced (SC-17).
- Tests: `ChapterArtifactStoreTest.kt` (+2 gate-1.1 tests), `ChapterArtifactLayoutTest.kt` (+1 SC-21 test, updated managed-directory list).

Not touched (as instructed): `ChapterTranslationStore.kt`, `StageFingerprints.kt` (no fingerprint functions added), `model/PageWorkPlanner.kt`, `pipeline/batch/**` (R012 files untouched), `store/PageStageLeaseTable.kt`, `translator/**`, no feature flags. Nothing committed; nothing under MAIN edited except this evidence report.

## Test results (exact invocations, worktree)

1. `./gradlew :app:compileStandardDebugKotlin` — BUILD SUCCESSFUL (only pre-existing warnings).
2. `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.artifact.*"` — BUILD SUCCESSFUL; **124 tests, 0 failures, 0 errors, 0 skipped**: AtomicChapterDocumentsTest 12, ChapterArtifactDeletionTest 2, ChapterArtifactLayoutTest 11, ChapterArtifactStoreTest 45, ChapterRunRecordSchemaTest 7, LegacyArtifactMigrationTest 26, ModelIdentityCacheTest 7, SidecarCrashPublicationTest 8, StageFingerprintsTest 6.
3. `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.ChapterTranslationStoreArtifactMigrationTest"` (consumer of the manifest outside the artifact package) — BUILD SUCCESSFUL; 22 tests, 0 failures.

No existing test was modified to accommodate behavior changes; the only test edits are additions plus the `ChapterArtifactLayoutTest` managed-directory list assertion extended for the seven new directories (its subject matter literally grew).

## Load-bearing assumptions re-verified (file + symbol, worktree HEAD adbe643)

- Future-schema guard is strictly `>`: `ChapterArtifactStore.loadOrMigrate` (schemaVersion guard at :85/:88), `readManifest` (:188), `futureBackupPresent`/`publishManifestInternal` (:1157/:1161), `ChapterArtifactDeletion.kt:112` — bumping `SCHEMA_VERSION` to 3 keeps `> 3` refusal; no exact-equality comparisons on the manifest schemaVersion exist anywhere in main sources (grep-verified).
- Publication order and crash windows: `AtomicChapterDocuments.publish`/`publishJson`/`readValidated`/`quarantineCorrupt` (`ChapterDocumentIo.kt:231-256, 263-311`) — reused unchanged; `publishSidecarPointers` composes them; `@Synchronized` reentrancy verified (publishActiveRun delegates to publishSidecarPointers on the same monitor).
- Retention is reachability-based: `ArtifactRetention.reachablePaths`/`isRetained` — new pointers added to the reachable set; orphan sidecars reclaimed only via sweeps (asserted in crash tests).
- `PageTranslation.CURRENT_INPAINT_REVISION = 10` (`model/PageTranslation.kt:183`) — used as the checkpoint floor.
- Shared Json: `AtomicChapterDocuments.json` was the only artifact Json config (`ChapterDocumentIo.kt`); it now aliases `ArtifactDocumentJson` with identical settings, so SC-06 "single shared instance" holds for old and new documents alike.
- v2 manifests decode losslessly: all seven new manifest fields have neutral defaults (kotlinx `ignoreUnknownKeys` + defaults), proven by the checked-in fixture test.
- `FakeChapterDocumentIo` fault seams (`failWrites`, `writeNamesToFail`, `ownedRenamesToFail`, `renamesToFail`, `deleteNamesToFail`, `writtenNames`) cover every injection boundary used by gate 1.3.

## Deviations / recorded interpretations

1. **"Missing schemaVersion fails validation" (SC-01):** SC-01 mandates the `ChapterGlossary`/`ChapterAttemptLedgerDocument` precedent, and both give `schemaVersion` a kotlinx default. A document with the field physically absent is therefore indistinguishable from an explicit current-version document. Implemented per that precedent: missing ⇒ current version; wrong/future version and wrong kind fail semantic validation. Recorded here rather than silently diverging from the repo idiom.
2. **ProfilePointer shape:** contract says "`ProfilePointer : SidecarPointer` adds…". Kotlin data classes are final, so it is a flat data class mirroring the `GlossaryPointer` precedent plus `toSidecarPointer()`; manifest fields use the exact contract types (`profile: ProfilePointer?`, others `SidecarPointer?`).
3. **Extracted record shapes (AnalysisChunkResult terms/entities/relationships):** T924-AP-* owns the response schema (AP-04 is a sketch); the persisted subset keeps the AP-04 identity fields (`termId/sourceForm/canonicalTarget/aliases/kind`, `entityId/canonicalSourceName/proposedTargetName/...`, relationship type + endpoints). Shapes are additive-optional-evolvable under SC-14; WP5 can extend without a version bump.
4. **`ColorStylePreparation` ORIGINAL_SOURCE:** modeled as `imageSource: ColorImageSourceKind` + nullable `cleanedImageRef` with iff-validation, per the contract's "yes (or explicit ORIGINAL_SOURCE kind)".
5. **SC-07 determinism spot tests:** not applicable — no WP1 helper computes content fingerprints (fingerprint functions are Phase 2; DTO `*Fingerprint` fields are opaque Strings). The determinism that does exist in this slice (content-addressed sidecar naming) is pinned by the new layout test. SC-10/SC-11 helpers likewise land with Phase 2 fingerprints.
6. **Retention `.corrupt` rule:** added minimal preservation of `.corrupt` siblings of pointer-reachable sidecars so SC-17's "reclaimed only when no pointer references them" holds; the manifest's own `.corrupt` was already outside the managed tree.
7. **`LegacyArtifactMigrationTest` not extended:** the migration path always writes fresh current-version manifests by construction; gate 1.1's legacy-compat surface (old bytes loading under new code) is the v2 fixture, which lives in `ChapterArtifactStoreTest` alongside its transaction idioms.

## Remaining risks

- `RunConfigSnapshot`/analysis/envelope policy snapshot inner fields are structural (no declared caps in the contract beyond the 64 KB frozen-config bound); planners (WP3) own their value ranges.
- `analysisChunks` pointers are order-dependent (`chunk-ordinal order` per SC-04); enforcement of list order belongs to the WP5 chunk-persist transaction, not to the generic pointer installer.
- The rollback-window consequence of the v3 bump (old builds preserve such chapters read-only) is Director decision 7.2, taken per the contract recommendation.
