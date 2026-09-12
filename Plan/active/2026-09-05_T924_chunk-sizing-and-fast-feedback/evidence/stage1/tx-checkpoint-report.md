# T924 Stage 1 Phase 2a — `checkpointOcr` CAS transaction + M1 milestone proof

Implementer report. Worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`.
All work left **uncommitted** for orchestrator review (no `git add`/`git commit` executed).

---

## 1. Contract re-verification (per implementation, file:line anchors)

Contract: `Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/stage0/contracts-state-transactions.md`
(§2 `checkpointOcr` transaction contract, lines 213–359; conflict notes C1/C2 at :427/:429).

| Anchor | What was re-verified against code |
|---|---|
| T924-TX-01 (:217) | `checkpointOcr` = single atomic publication of origin-neutral PageOcrCheckpoint sidecar + manifest pointer; all rejections leave prior manifest authoritative. |
| T924-TX-02 (:221) | Exact CAS inputs: generation, pageKey, pageVersion, leaseToken, candidateGenerationId, dependencyFingerprint (+ artifactPageVersion, prior OCR fingerprints optional). |
| T924-TX-02.1 (:237) | Inputs non-null mandatory in standard branch; exception: input 4 null exactly when no active candidate exists (TX-03.1); no-grace direction confirmed (C2 :429 — store ladder grace at `ChapterTranslationStore` patch path NOT copied). |
| T924-TX-03 (:239–257) | CLOSE vs REBASE decision: candidate present → close G (+ optional successor G′ in ONE publication); REBASE never mutates closed G's record; successor record shape = `openCandidate` shape. |
| T924-TX-03.1 (:260–283) | ADOPT-COMMITTED branch: no active candidate; committed bundle required; input 9 must equal committed OCR **content** fingerprint; committed bundle untouched (TX-07); crash-equivalent to B1–B3. |
| T924-TX-04 (:284) | Snapshot ownership: only the checkpoint's own snapshot pointer is installed; pre-existing stage records handled per branch (see §3 TX-04 row). |
| T924-TX-05 (:288–304) | Candidate lifecycle state machine incl. `(adopt) (none) → —` transition. |
| T924-TX-06 (:306–313) | Ordering: validate → publish sidecars → pointer/candidate → **then** release lease (lease release is a strictly-later caller step; `PageStageLeaseTable` untouched). |
| T924-TX-07 (:315) | Prior committed display preserved: committed bundle and display pointer never mutated by checkpointOcr (verified in all three branches + tests). |
| T924-TX-08 (:319) | Failure reporting: every rejection returns reason string; caller logs WARN (`rejectedCheckpoint`). |
| T924-TX-09 (:327–331) | API surface proposal; implemented with one deviation (§5 D1). |
| T924-TX-10 (:333) | Reuse consumption rule: readers resolve `ocrCheckpoints[pageKey]` pointer → sidecar → snapshot; corrupt/future-version quarantined, not destructive. |
| T924-TX-11 (:337–350) | Crash table B0–BX mapped to implementation; fault-injection tests cover the checkpointOcr-intersecting B rows. |
| T924-TX-12 (:352) | Required fault-injection tests implemented (§3 trace). |

Substrate reuse confirmed: `ChapterArtifactStore.publishSidecarPointers` (sidecar-then-pointer
M2 primitive, :696), `AtomicChapterDocuments.publishJson` (temp→validate→bak-rotate→rename),
`@Synchronized` + `staleManifestRejection` whole-manifest CAS ladder, `ArtifactRetention`
reachability sweep (`reachablePaths` now reaches `ocrCheckpoints` pointers — T924-SC-20, :121).
No new atomicity primitives were added.

Parallel-agent boundary respected: `StageFingerprints.kt` and `SemanticFingerprintTest.kt`
appear in the shared worktree diff but are **owned by the Phase 2b agent**; this task did not
modify them (read-only consumption of `StageFingerprints.pageOcrContentFingerprint` :183 and
`StageFingerprints.pageOcrContentBlocks` :473).

---

## 2. Diff summary (owned files only)

### Modified — `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt` (+347)

| Symbol | Line | Purpose |
|---|---|---|
| `enum class OcrCheckpointMode { CLOSE, REBASE }` | :56 | T924-TX-03 close-vs-rebase decision carrier. |
| `fun checkpointOcr(...)` | :390 | The transaction: `@Synchronized`, `staleManifestRejection` → DTO `validationError()` → pageKey/page/authority/pageVersion checks → snapshot fingerprint CAS (`StageFingerprints.pageSnapshot(ocrSnapshot)`) → branch on `checkpoint.producerGenerationId`. |
| `private fun committedOcrContentFingerprint(...)` | :615 | T924-TX-03.1 comparison: canonicalizes the committed snapshot through the same `pageOcrContentFingerprint` builder as the producer side, so both sides compare identical canonicalizations. |
| `sealed interface OcrCheckpointRead { Usable / UnsupportedVersion / Absent }` + `fun readOcrCheckpoint(pointer)` | :735/:755 | T924-TX-10 consumer: corrupt sidecar quarantined (`.corrupt` bytes kept, pointer left for rescue), future schema version reported as `UnsupportedVersion` (never deleted), absent reported as `Absent`. Mirrors `readRunRecord`. |
| `internal fun ocrStageSnapshotName / ocrCheckpointSidecarName` | :775/:779 | Test-visible layout derivation (`stageArtifactFile(pageKey, OCR, fp)`, `ocrCheckpointFile(pageKey, contentFp)`). |

Behavior detail (all branches):
- Standard branch (non-null `producerGenerationId`): candidate must exist, generationId match,
  origin `BATCH`, dependencyFingerprint non-null (fail-closed) and equal — then sidecars are
  published first (CANCELLED `GenerationRecord` for G; OCR snapshot via
  `documents.publishJson`; checkpoint sidecar), then ONE manifest publication installs
  `ocrCheckpoints[pageKey]` pointer. CLOSE additionally clears the candidate, strips
  candidate-owned stage records and **replaces the page's `ocr` stage record** with a
  generation-less `READY`/`TEXTLESS` record pointing at the snapshot file (retention
  reachability without schema change), restores displayState, increments pageVersion.
  REBASE additionally publishes the ACTIVE `GenerationRecord` for successor G′ and swaps the
  candidate to `CandidateGenerationMetadata(G′, BATCH, dependencyFingerprint =
  checkpoint.ocrContentFingerprint, …)` — G′'s first write therefore validates against the
  checkpoint fingerprint (T924-TX-03c).
- Adopt branch (`producerGenerationId == null`, TX-03.1): manifest candidate must be null
  (otherwise `"standard checkpoint branch required"`); committed bundle must exist and its
  page snapshot must be readable; `committedOcrContentFingerprint(...) !=
  checkpoint.ocrContentFingerprint` → `Rejected("committed OCR content drift…")`; on success
  only `page.ocr` + pageVersion + `ocrCheckpoints` pointer change — committed bundle, display
  pointer and candidate state untouched.
- Post-commit: retention `reconcileRetention` runs with the new manifest; the sweep's
  `deletedNames` ride on `TransactionOutcome.Committed.deletedFiles` (orphan sidecars from a
  previously failed attempt are reclaimed; unreachable closed-generation records swept —
  same semantics as `cancelCandidate`).

### Modified — `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` (+193)

| Symbol | Line | Purpose |
|---|---|---|
| `sealed interface CheckpointOcrResult { Committed(snapshot, manifest) / Rejected(reason) }` | :70 | Facade outcome; `Committed` carries the post-checkpoint snapshot for immediate reuse. |
| `suspend fun checkpointOcr(...)` | :983 | Store-mutex facade: defunct/admit checks → rejection ladder (generation, page missing, pageVersion, **lease presence + token** (`"page lease required for checkpoint"`), artifactPageVersion, candidateGenerationId, dependency-fingerprint-required (no grace, TX-02.1/C2), dependency mismatch, branch-shape mismatch (`"active candidate present; standard checkpoint branch required"`), optional prior OCR fingerprints) → builds `SourceIdentity` (complete: sha256/width/height/orientation; `sourceSha256` param falls back to `live.sourceFingerprint`, `sourceOrientation` explicit — works around `mergeOcrLocked` not copying `result.sourceFingerprint`) → builds `PageOcrCheckpoint` (naturalPageIndex from manifest record, priorCommittedDisplay from committed metadata, `producedByOrigin` from the held lease origin, `producerGenerationId = expectedCandidateGenerationId`) → delegates to `ChapterArtifactStore.checkpointOcr` → on Committed updates `artifactManifest` + `_state`/`_display` and returns the snapshot. |
| `private fun pageOcrContentFingerprint(...)` | :1112 | Delegates to `StageFingerprints.pageOcrContentFingerprint` with `StageFingerprints.pageOcrContentBlocks(page)`; passes the checkpoint's `inpaintMaskRevision` (per Phase 2b note — NOT `PageTranslation.inpaintRevision`). |
| `private fun rejectedCheckpoint(...)` | :1131 | T924-TX-08: WARN log + typed rejection. |

`PageStageLeaseTable.kt`: **not modified** (lease release stays a separate caller step, TX-06).

### New — `app/src/test/java/eu/kanade/translation/artifact/CheckpointOcrTransactionTest.kt` (532 lines, 12 tests)

Artifact-level transaction tests over `FakeChapterDocumentIo` (fault seams `writeNamesToFail`,
`ownedRenamesToFail`, `renamesToFail`, `writtenNames`) and `FakeUniFile`.

### New — `app/src/test/java/eu/kanade/translation/OcrCheckpointRestartReuseTest.kt` (368 lines, 3 `m1_` tests)

Store-level origin-neutrality + process-restart proof (§4).

---

## 3. Requirement trace: TX clause → implementation → proving test

| Clause | Implementation anchor | Proving test |
|---|---|---|
| TX-01 single atomic publication, prior manifest authoritative on any rejection | `ChapterArtifactStore.checkpointOcr` :390 + `publishSidecarPointers` :696 | `close installs the checkpoint pointer…`, `manifest temp write failure keeps the prior manifest authoritative` |
| TX-02 exact CAS inputs | facade ladder `ChapterTranslationStore.checkpointOcr` :983; artifact-level pageVersion CAS | `stale candidate identity rejections leave the prior manifest authoritative` (wrong generation / stale deps / stale pageVersion) |
| TX-02.1 non-null identity; lease mandatory | facade: lease-null → `"page lease required for checkpoint"`; dep-fp null → required rejection | `m1_ checkpoint without a held lease is rejected…`; `stale candidate identity…` |
| TX-03 CLOSE | CLOSE branch (clear candidate, strip candidate-owned records, install generation-less `ocr` stage record) | `close installs the pointer and clears the batch candidate in one publication` |
| TX-03 REBASE (close+successor in ONE publication) | REBASE branch: CANCELLED(G) + ACTIVE(G′) + candidate swap to fingerprint-seeded G′ | `rebase closes the batch generation and opens a successor seeded with the checkpoint fingerprint` (also asserts G′'s first `persistLiveCandidate` validates against the checkpoint fingerprint, and a stale-fingerprint writer is fenced) |
| TX-03.1 ADOPT-COMMITTED happy path | adopt branch :549 | `adopt installs the checkpoint pointer only and never touches the committed display` |
| TX-03.1 content drift → REJECTED | `committedOcrContentFingerprint` :615 + drift rejection | `adopt rejects content drift and keeps the prior manifest authoritative`; M1 test's artifact-level drift step |
| TX-03.1 with candidate present → standard branch required | `"standard checkpoint branch required"` both layers | `adopt with an active candidate present requires the standard branch` |
| TX-03.1 without committed bundle → rejected | committed-bundle precondition | `adopt without a committed bundle is rejected` |
| TX-04 snapshot ownership (no foreign pointers touched) | only `ocrCheckpoints` + branch-defined `ocr` record change | CLOSE/REBASE/adopt tests assert exact manifest deltas (committed/display untouched) |
| TX-05 candidate lifecycle `(none) → adopt` | adopt branch clears nothing, candidate stays null | adopt tests |
| TX-06 ordering; lease released strictly after publication | M1 harness: lease → merge → checkpoint → `releasePageStageLease`; facade never touches lease table | `m1_ checkpointed ocr survives a process restart…` flow order |
| TX-07 committed display preservation | committed bundle/display pointer never written in any branch | adopt test asserts `committed` identity + `displayState` unchanged; CLOSE test asserts display restored per `cancelCandidate` idiom |
| TX-08 failure reporting | typed `Rejected(reason)` + WARN log | every rejection test asserts reason strings |
| TX-09 API surface | facade + artifact transaction (deviation D1, §5) | — (API shape) |
| TX-10 reuse consumption | `readOcrCheckpoint` :755 + M1 restart read | `m1_` test 1 restart step |
| TX-11 B rows: sidecar write fail / rename fail / manifest publish fail / stale snapshot | fault seams | `checkpoint sidecar write failure…`, `snapshot sidecar rename failure leaves at most an orphan temp`, `manifest publication failure after durable sidecars keeps the pointer absent and orphans swept`, `manifest temp write failure…`, `stale manifest snapshot is rejected before any sidecar is written` |
| TX-12 fault-injection requirement | same five crash tests + identity-fencing tests | all above |

---

## 4. M1 milestone proof

Test class `eu.kanade.translation.OcrCheckpointRestartReuseTest`, all tests prefixed `m1_`.

`m1_ checkpointed ocr survives a process restart and is reused by reader and batch origins`:

1. **Batch produces** (store opened via `ChapterTranslationStore.lazy`): acquire page lease →
   `mergeOcr` live OCR → facade `checkpointOcr` (CLOSE, standard branch, BATCH candidate G) →
   `releasePageStageLease` — exact TX-06 order.
2. **Process restart** #1: fresh `ChapterTranslationStore.openArtifact(FakeUniFile, "Chapter 1.json")`
   over the same document set; resolves `manifest.ocrCheckpoints["page.jpg"]` →
   `readOcrCheckpoint` → usable checkpoint; snapshot readable.
3. **Reader-adhoc consumes without batch provenance**: `openCandidate(origin = READER_ADHOC)` —
   legal only because the candidate slot is free post-CLOSE — `persistLiveCandidate` →
   `promoteLiveCandidate` (reader commit). Proves the checkpointed payload is
   origin-neutral in consumption.
4. **Process restart** #2 (fresh `openArtifact` again), then **Batch re-checkpoints over the
   reader-committed page via TX-03.1**: facade `checkpointOcr` with
   `expectedCandidateGenerationId = null` (adopt-committed). Asserts committed bundle byte-identical
   (pointer untouched), pageVersion advanced, checkpoint pointer installed.
5. **Drift refusal**: an adopt checkpoint whose `ocrContentFingerprint` is
   `hex64("drifted-ocr-content")` (snapshot pointer fingerprint deliberately still valid) is
   `Rejected` on the committed-OCR-content-drift rule; manifest unchanged afterwards.
6. **Batch continues**: `openCandidate(BATCH)` succeeds on the final state — no
   legacy-checkpoint/provenance mismatch remains.

Supporting `m1_` tests: lease-less checkpoint rejected with prior manifest authoritative;
stale lease token / stale candidateGenerationId / stale dependencyFingerprint each fence the
checkpoint (three rejections) before a correct attempt succeeds with a **different** content
fingerprint (second OCR pass ≠ first).

---

## 5. Deviations / interpretations

- **D1 (TX-09 API shape).** The contract proposed the transaction receive a prebuilt
  `PageOcrCheckpoint` (which embeds sidecar `SidecarPointer`s). The store layer owns
  `ChapterArtifactLayout`, the facade does not, so the facade cannot name sidecar files. Split:
  facade takes raw identity inputs (`sourceOrientation`, `sourceSha256`,
  `expectedPriorOcrFingerprints`) and builds the DTO; the artifact transaction takes
  `ocrSnapshot: PageTranslation` + `checkpoint: PageOcrCheckpoint` and derives
  `ocrPageSnapshotPointer`/checkpoint sidecar names internally from content fingerprints.
  REBASE's successor `dependencyFingerprint` is derived internally as
  `checkpoint.ocrContentFingerprint` per TX-03(c). No contract semantics changed; recorded for
  review.
- **D2 (retention of CANCELLED generation records).** After CLOSE/REBASE the closed
  generation G is unreachable (`activeCandidateGenerationIds` drops it, `page.candidate` moves
  or clears), so the post-commit retention sweep reclaims G's `GenerationRecord` file —
  identical to existing `cancelCandidate` behavior. The CANCELLED record IS published durably
  before the manifest pointer moves (crash-window B3 safety); tests assert publication +
  post-sweep reachability via `outcome.deletedFiles` and the surviving successor record
  instead of post-sweep file presence.
- **D3 (SourceIdentity completeness).** `PageTranslation.sourceIdentity(pageKey)` never
  completes (orientation null) and `mergeOcrLocked` does not copy `result.sourceFingerprint`
  onto the live page, so the facade takes `sourceOrientation`/`sourceSha256` explicitly
  (sha falls back to `live.sourceFingerprint`). Fail-closed preserved: an incomplete identity
  is rejected by `PageOcrCheckpoint.validationError()`.
- **Interpretation (TX-04).** "Snapshot ownership" read as: the checkpoint claims only its own
  snapshot; in CLOSE the pre-existing candidate stage records are stripped exactly like
  `cancelCandidate`, with the OCR stage record replaced (not removed) so the snapshot stays
  retention-reachable — required by TX-03's warning (:258) that closing without keeping the
  snapshot reachable would destroy reuse.

## 6. Build / test evidence

Environment: `JAVA_HOME=/c/Program Files/Android/Android Studio/jbr`, worktree
`TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`.

| Invocation | Result |
|---|---|
| `./gradlew :app:compileStandardDebugKotlin` | BUILD SUCCESSFUL (re-verified after final test edits; 18s, 6 executed / 170 up-to-date) |
| `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.artifact.CheckpointOcrTransactionTest" --tests "eu.kanade.translation.OcrCheckpointRestartReuseTest"` | BUILD SUCCESSFUL — 15 tests, 0 failures, 0 errors, 0 skipped (`CheckpointOcrTransactionTest` tests=12; `OcrCheckpointRestartReuseTest` tests=3) |
| `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.artifact.*" --tests "eu.kanade.translation.ChapterTranslationStore*" --tests "eu.kanade.translation.coexistence.*"` | BUILD SUCCESSFUL — 32 test classes, **236 tests, 0 failures, 0 errors** (aggregated from JUnit XML in `app/build/test-results/testStandardDebugUnitTest/`) |

Worktree status (mine): modified `ChapterTranslationStore.kt` (+193),
`artifact/ChapterArtifactStore.kt` (+347); new tests
`artifact/CheckpointOcrTransactionTest.kt` (532 lines),
`OcrCheckpointRestartReuseTest.kt` (368 lines). `artifact/StageFingerprints.kt` and
`artifact/SemanticFingerprintTest.kt` in the shared diff are Phase 2b's, untouched by this task.

## 7. Remaining risks

- **R1.** Retention sweeps CANCELLED generation records after checkpoint (D2). If the
  orchestrator wants closed-generation records retained as audit evidence, that is a
  `ArtifactRetention` policy change owned elsewhere; the crash-safety window is unaffected.
- **R2.** The facade requires callers to pass `sourceOrientation`/`sourceSha256` explicitly
  (D3). Batch integration (later phase) must thread real values; forgetting them fails closed
  at validation, not silently.
- **R3.** `close installs…` asserts display restoration via the `cancelCandidate` idiom;
  if `cancelCandidate` display semantics change upstream, these tests will correctly flag it.
- **R4.** `OcrCheckpointRead.Usable` does not re-verify the snapshot file's content hash on
  read (pointer fingerprint vs sidecar content is checked at write time; corrupt bytes
  quarantine at parse time). A read-time content re-hash can be added if the review
  requires it; current behavior matches `readRunRecord`.
