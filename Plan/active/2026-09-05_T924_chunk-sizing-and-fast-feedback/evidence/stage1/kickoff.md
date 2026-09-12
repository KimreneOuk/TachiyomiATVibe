# T924 Stage 1 (S1) kickoff record — persistence foundations + checkpoint transaction

Per T924-FF-20 (stage kickoff process).

- **Base commit:** `adbe643df9d99953202dbb8c93c5dd7e504bfd6c` (branch
  `t924/batch-profile-pipeline`, worktree `..\TachiyomiAT-t924-impl`;
  recorded 2026-09-05). Main worktree remains on `main` at the same commit.
- **Scope:** S1 of `../../implementation-sequence.md` = WP1 (schemas,
  serialization, fingerprints, manifest v3, publication) + WP2
  (`checkpointOcr` incl. TX-03.1) + R012 (forced-OCR reuse decoupling).
  No analysis, no coordinator, no flags (FF-01/FF-02 land in S2/S3).
  Expected flag state at kickoff: neither flag exists yet — correct for S1.
- **Accepted decisions governing this stage:** DB-01..DB-05 (Director
  acceptance 2026-09-05, "treat as accepted unless concrete contradiction" —
  Stage-0 review found none: 31/31 citations verified).
- **Load-bearing source assumptions to re-verify during implementation** (from
  `../../stage0/contracts-*.md`, each agent records its own re-verification in
  its report):
  - `AtomicChapterDocuments.publish/publishJson/readValidated/recoverPrimaryFromBackup`
    crash-safe publication semantics (`artifact/ChapterDocumentIo.kt:200-347`).
  - `ChapterArtifactStore.staleManifestRejection` whole-manifest CAS + `@Synchronized`
    transactions (`artifact/ChapterArtifactStore.kt:897-905`).
  - Manifest `SCHEMA_VERSION = 2` and future-schema read-only guard
    (`artifact/ChapterArtifactManifest.kt:59`;
    `artifact/ChapterArtifactStore.kt:85-101, 991-1013`).
  - Candidate write guards incl. origin rejection (`:346-399, 817-839`).
  - Store CAS ladder `PatchPrecondition`/`patchPage`/`mergeOcrLocked`
    (`ChapterTranslationStore.kt:190-202, 548-627, 831-929`).
  - Lease priority matrix + in-memory leases
    (`store/PageStageLeaseTable.kt:44, 71-98`).
  - `PageWorkPlanner.canReuseNative` force coupling
    (`model/PageWorkPlanner.kt:30-41`) and missing-expectation pass-through
    (`:275-287, 366-371`).
  Line numbers are navigation hints only (T924-R025); bind to symbols.
- **Baseline runs:** none required — S1 gates (1.1-1.7) are pure JVM
  deterministic tests; no comparison-to-device gate exists at this stage.
- **Open blocking decisions this stage must not silently resolve:** none —
  DB-01..05 accepted; DR-A retention semantics are S6 territory (WP6), not S1.
- **Orchestration:** Phase 1 = WP1 schemas/publication ∥ R012 (disjoint file
  sets); Phase 2 = `checkpointOcr` + M1 proof ∥ semantic fingerprints; Phase 3
  = integration verification (gates 1.1-1.7); Phase 4 = independent review;
  then one S1 rollback commit on the branch + exit report.
- **Build commands (verified against repo scripts and app flavors):**
  `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`; compile
  `./gradlew :app:compileStandardDebugKotlin`; targeted tests
  `./gradlew :app:testStandardDebugUnitTest --tests "<pattern>"` (flavors:
  standard|dev).
