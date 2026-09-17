# T934 Fix Brief: stale-publication fatal abort on batch resume + UI completion oracle

## Device evidence (OnePlus PKG110, build 506, Chapter 37, 24 pages, resume of interrupted run)

Log cascade (logcat, 2026-09-17):

```
ChapterProfileBatchCoordinator: t924 preflight run record publication rejected: manifest publication failed; prior manifest remains authoritative   (×2)
ChapterTranslationStore: artifact candidate open rejected: pageKey=011.jpg reason=stale manifest snapshot: chapter=Chapter 37
<façade>: stage patch rejected: pageKey=011.jpg generation=1 ... reason=ARTIFACT_PUBLICATION_FAILED
SinglePageOnnxPhase: batch OCR persist rejected (stale writer): pageKey=011.jpg ...
BatchChapterTranslator: batch candidate aborted ... reason=Batch persistence publication rejected
ChapterProfileBatchCoordinator: t924 preflight ocr failed ... error=BatchPersistenceRejectedException
ChapterProfileBatchCoordinator: t924 preflight durable failure record rejected ... error=IllegalStateException
BatchChapterTranslator: batch stopped before tail reconciliation ... status=FAILED
```

Earlier run on build 497 hit the identical cascade at a different page (002.jpg) → timing-dependent race, not page content.

## Root cause (two compounding defects)

**Defect A — documents layer: unserialized publication with a deterministic tmp name.**
`AtomicChapterDocuments.publish` (app/src/main/java/eu/kanade/translation/artifact/ChapterDocumentIo.kt:335-365) runs write(`name.tmp`) → read-back validate → delete `.bak` → rename `name`→`name.bak` → rename `name.tmp`→`name`. The tmp name is deterministic (`tempNameFor`, :466) and the sequence takes NO lock of its own. Concurrent writers to the same chapter from different store instances/threads (the batch preflight publishing the run record vs. the >8-page open path's background `verifyLegacyArtifactHealth` manifest republisher — the exact racer the T924 LI-4 comment in ChapterArtifactStore.kt:1749-1803 already documents) collide on the same `name.tmp` and on the rotate/rename sequence; the loser gets a mechanical `false` from publish with zero diagnostic logging. Every caller maps `false` to a fatal Rejected.

**Defect B — store layer: the LI-4 one-shot stale-manifest retry covers only `publishActiveRun` (:349-359) and `checkpointOcr` (:478-489).**
The resume path's first-publication seams — `openCandidate` (:1509-1588), the stage-patch/candidate-persist transactions, `promoteLiveCandidate`, `retireActiveRun` (:413-432) — perform `staleManifestRejection(manifest)` and on mismatch return `Rejected("stale manifest snapshot: …")` with NO retry. The façade (ChapterTranslationStore.kt:2131-2146) logs and returns false → ARTIFACT_PUBLICATION_FAILED → BatchPersistenceRejectedException → whole-batch abort.

**Defect C — UI: the translation sheet showed "Completed / All pages translated" while the trace recorded outcome=failure** (with "2% Page 1 of 24" and "1 pages need attention" visible simultaneously). The completed oracle doesn't gate on failure/attention state.

## Required changes

### 1. ChapterDocumentIo.kt — serialize publication per document name + log failures
- In `AtomicChapterDocuments`: add a process-wide `java.util.concurrent.ConcurrentHashMap<String, Any>` of per-name locks (companion or instance field; instance is fine — one documents instance per store, but the racing writer may hold a DIFFERENT store instance, so the lock map MUST be process-wide `companion object` state keyed by document name). In `publish`, wrap the whole write→validate→rotate→rename sequence in `synchronized(lockFor(name))`.
  - Rationale: eliminates the tmp collision and rotate/rename interleaving for every pair of in-process writers regardless of which store instance they hold. No nesting: `publish` never calls `publish`; the inner io calls take no other locks → no deadlock risk.
- Add `logcat(LogPriority.WARN)` on each false-return path inside `publish`: tmp write failed / read-back null-or-mismatch / rotation rename failed / final rename failed (include document name; no payload bytes). The device run must be diagnosable from logs alone next time.

### 2. ChapterArtifactStore.kt — extend the LI-4 retry to the batch resume seams
Mirror the EXACT existing pattern (public wrapper + `...Once` private body + `retryOnStaleManifest(seam=...)`, doc comment referencing T924 LI-4):
- `openCandidate` → wrapper + `openCandidateOnce` (seam="openCandidate").
- The stage-patch seam: find the store transaction behind the façade's stage persistence (grep ChapterTranslationStore.kt for "stage patch rejected" to identify which store call maps to ARTIFACT_PUBLICATION_FAILED there — likely `persistLiveCandidate` and/or `promoteLiveCandidate`). Wrap whichever of those perform a leading `staleManifestRejection` check.
- `retireActiveRun` → wrapper + `retireActiveRunOnce` (seam="retireActiveRun").
- Generalize `retryOnStaleManifest` ONLY if needed (it is currently typed to TransactionOutcome; RecordOutcome for recordDurableFailure is explicitly OUT of scope — do not touch recordDurableFailure's store body).
- Contract to preserve: a retry that also fails, or a vanished manifest, returns the ORIGINAL Rejected outcome; every non-stale rejection reason is returned as-is; the retry re-runs the WHOLE body against the fresh manifest (never republishes the caller's stale object). Comment text mirrors the existing LI-4 javadoc, adapted per seam.
- Do NOT wrap demote/delete/reset/user-action seams (out of scope).

### 3. ChapterProfileBatchCoordinator — durable-failure recording must be non-fatal
Log anchor: "t924 preflight durable failure record rejected ... error=IllegalStateException". Investigate the coordinator site that logs `t924 preflight durable failure record rejected` (grep in app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt). When the durable-failure RECORD fails (store NotStored or façade throw), the run is already failing — recording must never escalate or throw; log WARN and continue so the original failure surfaces cleanly. Do not alter what counts as a run failure.

### 4. Translation sheet — failure must never render as Completed
- Grep the presentation layer for the completion strings ("All pages translated", "Completed") in the translation sheet UI; find the state derivation feeding them.
- Fix the oracle: the completed/all-pages-translated state requires zero pages needing attention (and no failed/paused run outcome). A failed or attention-present run renders the attention/partial/error state — never the celebratory completed state. Keep changes presentation-side only; do not invent new pipeline state.

## Tests (extend existing files; NEVER weaken an assertion)

- `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactStoreStaleManifestRetryTest.kt` — extend with the same pattern used for publishActiveRun/checkpointOcr: for each newly wrapped seam, a stale-manifest rejection triggers exactly ONE fresh-read retry that commits; a second consecutive rejection returns Rejected; non-stale reasons are returned as-is with no retry.
- New `AtomicChapterDocumentsPublicationLockTest` (artifact package, alongside SidecarCrashPublicationTest.kt): two threads publishing different values to the SAME document name concurrently → both publishes return true, the final read returns one of the two complete values (never corrupt/empty), and at most one orphan tmp remains. Use the file-backed test IO pattern from existing tests in that package.
- UI oracle: if the completed-state derivation is a testable pure function/presenter, add a unit test: a run with ≥1 attention page / failed outcome never classifies as completed. If it is inline composable logic with no existing test seam, extract the predicate minimally so it is testable.
- Full store contract: existing tests in app/src/test/java/eu/kanade/translation/artifact/ must stay green unchanged (ChapterCommitPointContractTest, CheckpointOcrTransactionTest, ChapterRunRecordSchemaTest, SidecarCrashPublicationTest, ChapterArtifactStoreRetireActiveRunTest).

## Constraints

- NO git commands. NO gradle commands. The orchestrator builds and commits.
- NO weakening/removing existing assertions or tests.
- Allowed files (disjoint, do not touch others):
  - app/src/main/java/eu/kanade/translation/artifact/ChapterDocumentIo.kt
  - app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt
  - app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt
  - the presentation file(s) holding the sheet completion oracle (locate via grep)
  - test files: ChapterArtifactStoreStaleManifestRetryTest.kt, new AtomicChapterDocumentsPublicationLockTest.kt, new/adjusted oracle test file
- Read ChapterArtifactStore.kt:1749-1803 first — the LI-4 comment block is the contract for the retry pattern; reuse `staleManifestRejectionOrNull` / `STALE_MANIFEST_REJECTION_REASON` as-is.
- Report back exactly: "Completed. <one-line result>. Report: <path>" with the report written to Plan/active/2026-09-16_T934_resume-rebuild-and-parallelism/team/t934-stale-publication-fix-report.md listing per-file changes + test results.
