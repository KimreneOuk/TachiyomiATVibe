# T924 Stage 3 — WP4 slice report: OCR-preflight coordinator shell behind FF-01

Date: 2026-09-05 · Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`,
base `7c301bc` (Stage-1 head + FF-01/FF-02 accessors). All work left **uncommitted**
(no `git add`/`git commit` executed). MAIN worktree untouched except this report.

Slice scope implemented: WP4 coordinator SHELL only — the durable machine runs
through `OCR_PREFLIGHT` and then STOPS with a durable diagnostic; analysis,
profile, envelope, translation and inpaint are later stages and are NOT touched.
Legacy `SequentialBatchCoordinator` file is byte-identical (untouched).

---

## 1. Contract anchors (file:line at this worktree, re-verified during the slice)

| Anchor | Where | Role in this slice |
|---|---|---|
| T924-FF-01a (dispatch point) | `feature-flags-stage-gates.md` §1.3; code `pipeline/batch/BatchChapterTranslator.kt:632` (`runBatchPass1`), flag read `:637` | Single FF-01 consultation per run; ON constructs `ChapterProfileBatchCoordinator`, OFF constructs `SequentialBatchCoordinator` with verbatim legacy args |
| T924-FF-01b (default OFF, legacy byte-for-byte) | flags §1.3; `TranslationPreferences.kt:248` (`translation_batch_profile_pipeline`, default `false`) | Legacy construction expression unchanged inside the OFF branch; characterization + coexistence suites green unmodified |
| T924-FF-01c (flag OFF: new artifacts never required) | flags §1.3 | Flag-OFF resume decision leaves all T924 sidecars untouched (pure decision, test-asserted manifest equality) |
| T924-FF-01d (flag captured at run snapshot) | flags §1.3; `artifact/ChapterRunRecord.kt:81` (`ChapterRunRecord`) | Flag value frozen as operational `phaseCounters["flagProfilePipeline"]=1` (deviation D1, §6) at the RUN_SNAPSHOT publication |
| T924-FF-01e (flag-off-mid-run) | flags §1.3; `ChapterProfileBatchCoordinator.kt:464` (`decideResume`) | 3 cases encoded: flag ON continues; flag OFF + non-COMPLETE drops to legacy (sidecars untouched); flag OFF + COMPLETE = finished. Case 2a unreachable in this slice (preflight never commits final displays) |
| T924-FF-10 (queue restore no auto-start) | flags §1.5 | Coordinator runs only from the dispatch point after explicit admission; `decideResume` signature carries no queue input; no-auto-start test asserts zero OCR / no durable writes before `runPass1` |
| T924-ST-01.5 (one pointer publication per transition) | `contracts-state-transactions.md` :45; `ChapterArtifactStore.publishActiveRun` :320 | RUN_SNAPSHOT and OCR_PLAN transitions are separate `publishActiveRun` M1/M2 publications; per-page counters ride their own best-effort publications (ST-06 advisory) |
| T924-ST-03 (RUN_SNAPSHOT freeze) | contracts :67; `ChapterRunRecord.frozenConfig` :87 | Frozen `RunConfigSnapshot` + `frozenRunConfigFingerprint` (length-prefixed SHA-256 over canonical config JSON) + `orderedSourceDigest` at run start |
| T924-ST-03.1 (freeze semantics) | contracts :74; `ChapterProfileBatchCoordinator.kt` runId selection (fingerprint match continues the recorded `runId`, mismatch starts a new run id) | Proven by the mid-preflight-death test (same `runId` across process restart) |
| T924-ST-05 (OCR_PLAN persists nothing) | contracts :89 | Plan recomputed in-memory from ordered pages + store state; only the phase-transition publication |
| T924-ST-06 (OCR_PREFLIGHT serial loop) | contracts :100; `ChapterProfileBatchCoordinator.runPass1` :76, `checkpointPage` :271, `reusableCheckpointFingerprint` :303 | One page at a time: reuse check, OCR via `NativeLaneWorker.runOcrStage`, `checkpointOcr` CLOSE, lease release in `finally` AFTER the checkpoint attempt, native handoff released before the next page |
| T924-TX-01..TX-09 (checkpoint transaction) | `ChapterTranslationStore.kt:983` (facade), `ChapterArtifactStore.kt` `checkpointOcr` | Consumed unchanged; facade identity taken from the fresh `store.snapshot(pageKey)` + `OcrReadyPageRef` fencing fields |
| T924-TX-03 (CLOSE default) | contracts :239; `OcrCheckpointMode.CLOSE` passed at `ChapterProfileBatchCoordinator.kt:289` | Preflight closes the BATCH candidate; no successor candidate is planned by this slice |
| T924-TX-06 (ordering) | contracts :306 | Lease release strictly after checkpoint outcome, inside the page `finally`; handoff (`releaseNativeHandoff`) before loop continues |
| T924-TX-07 (committed display preserved) | contracts :315 | Guaranteed by the unchanged facade/store transaction; test asserts `committed == null` on every touched page record and `isTranslationDisplayReady == false` |
| ST-16 / FF-01e.2 (startup resume) | contracts :204 | Resume = re-entry of `runPass1` with flag still ON; per-page checkpoints are the authoritative skip evidence |
| T924-FP-03 (corpus fingerprint) | `StageFingerprints.kt:251` (`ocrCorpusFingerprint`) | Computed at preflight completion from the per-page `ocrContentFingerprint` values in natural order; stored in `ChapterRunRecord.ocrCorpusFingerprint` (DTO field exists — used) |
| Reuse harness idioms | `OcrCheckpointRestartReuseTest.kt` (M1), `SequentialBatchCoordinatorTest.kt` fakes | Store over `FakeUniFile`/`@TempDir`; fake `NativeLaneWorker` acquiring the lease, merging under the token, returning the fencing identity |

## 2. Coordinator design summary

`pipeline/batch/ChapterProfileBatchCoordinator.kt` (NEW, 540 lines):

- Constructor inputs: `ChapterTranslationStore`, `NativeLaneWorker` (the SAME
  legacy OCR lane — engine warmth, native admission and decode idioms are
  reused, not reimplemented), frozen `RunConfigSnapshot`, ordered
  `(pageKey, sourceSha256)` pairs (the shell's precomputed source
  fingerprints), `flagProfilePipeline` (the once-per-run FF-01 value),
  `releaseBatchLease` lambda (the shell's existing `releaseBatchPageLease`),
  optional `BatchScheduleListener`, `nowEpochMs`.
- `runPass1(orderedPages, computeClass)` keeps the legacy call shape so the
  dispatch point branches without any other shell change. `computeClass` is
  accepted for parity only — no provider lane exists in this stage.
- Per run: artifact-authority precheck (fail fast, zero OCR burned) →
  RUN_SNAPSHOT record → OCR_PLAN record → serial page loop → STOP publication.
- Page loop: `yield()` between pages (reader-priority suspension, never a
  sleep); checkpoint reuse skip (usable `PageOcrCheckpoint` AND source-sha
  equality with the current source digest input — changed/unknown source
  re-runs); otherwise `runOcrStage` → fresh `snapshot(pageKey)` → facade
  `checkpointOcr(CLOSE)` → counter publication. A checkpoint REJECTED or a
  worker exception STOPS the pass (FAILED outcome, anchor page) — ST-06
  terminal, no later stage runs. `finally` per page: `releaseNativeHandoff`,
  `releaseBatchLease`, listener events.
- STOP (last page checkpointed): final record `state=OCR_PREFLIGHT` with
  `ocrCorpusFingerprint` (only when every page carries a usable checkpoint —
  gaps counter otherwise), counters `ocrPagesTotal/ocrPagesDone/ocrPagesReused/
  preflightStop/preflightCheckpointGaps/flagProfilePipeline`; outcome is
  `BatchPass1Status.PAUSED` with `STOP_REASON` ("T924 OCR preflight complete;
  analysis/profile/translation arrive in later stages"). PAUSED (not
  COMPLETED) is deliberate: the reconciler (`BatchProgressReconciler.
  reconcilePaused`) keeps OCR-ready pages pending without synthesizing
  stranded failures — stopped-not-finished, resumable, no visual regression.
- All run-record publications are best-effort: a rejected publication logs a
  WARN and never fails the preflight (per-page checkpoints in the manifest are
  authoritative; counters are advisory per ST-06). After each committed
  publication the facade's `artifactManifest` snapshot is refreshed (internal
  var, same module) so the next `checkpointOcr` whole-manifest CAS stays valid.

Dispatch edit (`BatchChapterTranslator.kt`): the `val coordinator =
SequentialBatchCoordinator(...)` construction moved verbatim into the
`LEGACY_SEQUENTIAL` branch of a local `suspend fun runBatchPass1(...)`; the
single flag read happens immediately before the branch; the call site changes
from `coordinator.runPass1(...)` to `runBatchPass1(...)`. Nothing else in the
shell changed.

## 3. Exact diff (owned files only)

Files: modified `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt`
(+57/−18, shown verbatim below); new
`app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt`
(540 lines); new tests `OcrPreflightCoordinatorTest.kt` (365 lines),
`OcrPreflightFlagOffMidRunTest.kt` (276 lines). `SequentialBatchCoordinator.kt`
untouched (0-byte diff). Foreign working-tree files
(`rendering/*`, `translator/contextual/*`, `test/resources/t924/golden/`)
belong to the parallel agents and were not touched.

```diff
--- a/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt
+++ b/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt
@@ -619,23 +619,62 @@ internal class BatchChapterTranslator(
                 )
 
 
-                val coordinator = SequentialBatchCoordinator(
-                    nativeWorker = batchLaneWorkers.nativeWorker,
-                    translatorWorker = batchLaneWorkers.translatorWorker,
-                    renderJoin = renderJoin,
-                    listener = batchScheduleListener,
-                    scheduleTrace = scheduleTrace,
-                    awaitLeaseHandback = { pageKey ->
-                        // T917 D3 (design §3.2): the deferring owner commits its
-                        // terminal stage BEFORE releasing its lease (the manual
-                        // boundary's finally), so observing the release is
-                        // sufficient — the terminal state is already published
-                        // when the rescan re-offers the page and the worker's
-                        // externally-completed gate routes it to SKIP_ALL.
-                        store.awaitPageLeaseRelease(pageKey, LEASE_HANDBACK_WAIT_MS)
-                    },
-                    deferredPages = deferredPages,
-                )
+                /**
+                 * T924-FF-01a: THE single FF-01 dispatch point. The flag is
+                 * read ONCE per run here (T924-FF-01d — the value is frozen
+                 * into the flagged run's ChapterRunRecord; mid-run settings
+                 * changes never re-read it, T924-FF-01e/ST-15). Flag ON
+                 * constructs the T924 [ChapterProfileBatchCoordinator] (WP4
+                 * shell: OCR preflight through its stop/diagnostic terminal);
+                 * flag OFF constructs the legacy [SequentialBatchCoordinator]
+                 * unchanged (FF-01b byte-for-byte legacy behavior).
+                 */
+                suspend fun runBatchPass1(
+                    orderedPages: List<PageKey>,
+                    computeClass: TranslatorComputeClass,
+                ): BatchPass1Outcome {
+                    val profilePipelineEnabled = translationPreferences
+                        .translationBatchProfilePipeline()
+                        .get()
+                    return when (ChapterProfileBatchCoordinator.dispatchKind(profilePipelineEnabled)) {
+                        ChapterProfileBatchCoordinator.BatchCoordinatorKind.PROFILE_PIPELINE ->
+                            ChapterProfileBatchCoordinator(
+                                store = store,
+                                nativeWorker = batchLaneWorkers.nativeWorker,
+                                listener = batchScheduleListener,
+                                frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
+                                    sourceLang = fromLang.code,
+                                    targetLang = toLang.code,
+                                    ocrEngine = recognitionEngine::class.java.simpleName,
+                                    inpaintMode = inpaintingModeFromPref().name,
+                                    providerKey = textTranslator::class.java.simpleName,
+                                ),
+                                orderedSourcePairs = orderedStreams.map { (pageKey, _) ->
+                                    pageKey to (sourceFingerprints[pageKey] ?: UNKNOWN_SOURCE_FINGERPRINT)
+                                },
+                                flagProfilePipeline = true,
+                                releaseBatchLease = { pageKey -> releaseBatchPageLease(store, pageKey) },
+                            ).runPass1(orderedPages, computeClass)
+                        ChapterProfileBatchCoordinator.BatchCoordinatorKind.LEGACY_SEQUENTIAL ->
+                            SequentialBatchCoordinator(
+                                nativeWorker = batchLaneWorkers.nativeWorker,
+                                translatorWorker = batchLaneWorkers.translatorWorker,
+                                renderJoin = renderJoin,
+                                listener = batchScheduleListener,
+                                scheduleTrace = scheduleTrace,
+                                awaitLeaseHandback = { pageKey ->
+                                    // T917 D3 (design §3.2): the deferring owner commits its
+                                    // terminal stage BEFORE releasing its lease (the manual
+                                    // boundary's finally), so observing the release is
+                                    // sufficient — the terminal state is already published
+                                    // when the rescan re-offers the page and the worker's
+                                    // externally-completed gate routes it to SKIP_ALL.
+                                    store.awaitPageLeaseRelease(pageKey, LEASE_HANDBACK_WAIT_MS)
+                                },
+                                deferredPages = deferredPages,
+                            ).runPass1(orderedPages, computeClass)
+                    }
+                }
 
                 var pass1Outcome: BatchPass1Outcome? = null
                 try {
@@ -644,7 +683,7 @@ internal class BatchChapterTranslator(
                             pageKey to (resolvedNaturalPageIndexes[pageKey] ?: index)
                         }
 
-                        pass1Outcome = coordinator.runPass1(orderedPages, computeClass)
+                        pass1Outcome = runBatchPass1(orderedPages, computeClass)
                     }
                 } finally {
                     // Only the registry's REMAINING entries need a release here: consumed/
```

(The new coordinator and the two new test files are NEW files; their full
contents are in the worktree — summarized in §2 and §5.)

## 4. Test list and counts

Final verification run (required scope):

```
./gradlew :app:testStandardDebugUnitTest \
  --tests "eu.kanade.translation.pipeline.batch.*" \
  --tests "eu.kanade.translation.coexistence.*" \
  --tests "eu.kanade.translation.OcrCheckpointRestartReuseTest"
BUILD SUCCESSFUL — 33 test classes, 132 tests, 0 failures, 0 errors
```

Compile: `:app:compileStandardDebugKotlin` BUILD SUCCESSFUL (re-verified after
the final test edits; note the first two compile attempts hit transient
Kotlin-daemon/gradle-lock contention with the two parallel agents and were
retried per protocol).

New tests (JUnit XML counts):

| Class | Tests | What each proves |
|---|---|---|
| `pipeline/batch/OcrPreflightCoordinatorTest` | 4 | 1) happy path over 3 fake pages: serial natural-order OCR (`maxInFlight == 1`), every handoff + lease released at the page boundary, 3 origin-neutral checkpoints, outcome PAUSED with `STOP_REASON`, final record `state=OCR_PREFLIGHT`, counters (`ocrPagesTotal=3, ocrPagesDone=3, ocrPagesReused=0, preflightStop=1, gaps=0, flagProfilePipeline=1`), `frozenConfig` + `frozenRunConfigFingerprint` + hex digests, corpus fingerprint equals `StageFingerprints.ocrCorpusFingerprint` over the checkpoint content fingerprints, pages remain `translation/render PENDING` and `isTranslationDisplayReady == false` (no completion redefinition). 2) mid-preflight death: p2 worker kill leaves p1 checkpointed, p2 lease-free, FAILED outcome anchored at p2; simulated process restart (fresh `openArtifact`) re-OCRs ONLY `[p2, p3]`, same `runId` (ST-03.1), final `ocrPagesReused=1`, record `OCR_PREFLIGHT` complete. 3) changed source identity: replaced page (different sha) re-OCRs despite a usable checkpoint, `ocrPagesReused=0`, corpus fingerprint changes. 4) empty page set: COMPLETED no-op, no run record published |
| `pipeline/batch/OcrPreflightFlagOffMidRunTest` | 5 | 1) `dispatchKind(false) == LEGACY_SEQUENTIAL`, `dispatchKind(true) == PROFILE_PIPELINE` (FF-01a mapping). 2) interrupted flagged run + flag flipped OFF: record carries `flagProfilePipeline=1` (FF-01d durable), `decideResume(record, flagOn=false) == DropToLegacy`, manifest byte-equal before/after decision, checkpoints untouched, `committed == null` everywhere (FF-01c). 3) never-published run: `decideResume(null, false) == DropToLegacy` (FF-01e.3), `decideResume(null, true) == RunFlaggedPath(null)`. 4) `COMPLETE` record: flag OFF yields `TreatAsFinished` (FF-01e.2a), flag ON yields `RunFlaggedPath`. 5) queue-restore no-auto-start: construction + decision only — zero OCR, no `activeRun` pointer, no checkpoints, no lease; decision signature carries no queue input (FF-10) |

Existing suites covering the FF-01b obligation (all green, unmodified):
`Phase0BatchTranslationCharacterizationTest`, the whole
`pipeline/batch/*` set (16 classes incl. `SequentialBatchCoordinatorTest`),
the whole `coexistence/*` set (D1–D10 + harness-based suites),
`OcrCheckpointRestartReuseTest` (3 M1 tests).

## 5. FF-01b byte-for-byte proof

1. The OFF branch of the dispatch constructs `SequentialBatchCoordinator` with
   the IDENTICAL argument list as the pre-change construction (diff §3 — the
   expression moved, no argument altered; `runPass1` invoked on the same
   locals). `SequentialBatchCoordinator.kt` itself: `git diff` empty.
2. The flag default is OFF (`TranslationPreferences.kt:248`), so every
   existing test run exercised the OFF path. The required scope ran green with
   the characterization + coexistence suites UNMODIFIED
   (`git status` shows no existing test file touched by this slice).
3. The legacy path executes one extra pure read
   (`translationBatchProfilePipeline().get()` on the preference store) and one
   extra branch — no scheduler, store, or reader behavior change.

## 6. FF-01e / FF-10 evidence and deviations

- FF-01e case analysis lives in `decideResume` (typed, pure) and is proven by
  `OcrPreflightFlagOffMidRunTest` cases 2–4. Production entry point is the
  dispatch itself: flag OFF constructs the legacy coordinator, which uses only
  legacy artifacts (FF-01c) and never re-enters the new path; the in-flight
  run was already gone (process death) in every state this slice can produce.
- FF-10: queue restore never auto-starts — the coordinator executes only via
  `runPass1` reached through `BatchChapterTranslator` admission; proven by the
  construction-without-`runPass1` test. Flag provenance is re-derived from the
  run record + current flag only (no queue input exists in the decision).
- Deviation D1 (DTO gap, recorded not silently resolved): FF-01d specifies the
  flag be persisted "into `ChapterRunRecord.frozenRunConfig`", but
  `RunConfigSnapshot` has no flag field and `artifact/**` is READ-ONLY for
  this slice (ownership boundary). The flag is frozen as an operational
  `phaseCounters` key `flagProfilePipeline` (1/0) — counters are excluded from
  all fingerprints (T924-FP-01), so the flag never invalidates reuse, and
  validation bounds are respected. A one-line optional field append to
  `RunConfigSnapshot` (schemas contract §1.1 allows appended optional fields)
  is the clean fix and belongs to an artifact/**-owner slice; the coordinator
  reads its frozen flag from constructor state, so no resume logic depends on
  the counter key.
- Deviation D2 ("OCR_PREFLIGHT complete" durability): the DTO has no
  `activePhase` field and no free-text diagnostic field, so "state shows
  OCR_PREFLIGHT complete + diagnostic summary" is realized as
  `state=OCR_PREFLIGHT` + `phaseCounters[preflightStop=1,
  preflightCheckpointGaps=n]` + the corpus fingerprint. Resumability is
  unaffected (resume reads checkpoints, not the record state, per ST-01.4).
- Deviation D3 (collapsed source-validation): the shell computes the ordered
  source fingerprints BEFORE coordinator construction (legacy
  `SOURCE_FINGERPRINT` stage, `BatchChapterTranslator.kt:390-409`), so the
  RUN_SNAPSHOT record already embeds `orderedSourceDigest` and
  SOURCE_VALIDATION is complete at that publication; no separate
  SOURCE_VALIDATION transition is published. Consistent with ST-04's "per-page
  identities ride the existing ... `source` field".
- Deviation D4 (frozen-config placeholders): `ocrModelHash`,
  `detectorModelHash` have no stable engine accessor yet; they are pinned to
  the explicit constant `"unspecified"` (`MODEL_HASH_UNSPECIFIED`) — recorded,
  stable, never silently empty. Real engine/model identity threading is a
  later-stage task (engine-identity work is not in this slice's scope).
- Interpretation I1 (unknown current source hash re-runs): a page whose
  current source sha is `UNKNOWN_SOURCE_FINGERPRINT` fails the equality check
  and re-OCRs (fail-closed); cheap because UNKNOWN means the hash stage failed.

## 7. Risks / notes for review

- R1: per-page counter publications rewrite the manifest file once per page
  (contract-shaped M1 pointer moves). At ~200 pages this is ~200 extra
  manifest publications; if device measurement (gate 3.4/3.6) shows cost,
  counters can de-slide to every-N-pages without touching correctness
  (advisory per ST-06).
- R2: a checkpoint REJECTED page returns a FAILED pass; the shell teardown
  then cancels that page's candidate (legacy durability idiom), so the
  candidate-held OCR of that page is lost and re-OCR'd next attempt
  (crash-boundary B0 semantics). Durable failure records are NOT yet written
  by the coordinator itself (the legacy `persistUnexpectedBatchStageFailure`
  path in the shell handles unexpected-stage persistence only for the legacy
  coordinator); wiring the preflight stop into the durable-failure ledger is
  deliberately deferred to the next S3 slice.
- R3: non-AI translators with FF-01 ON also run the preflight-only path (the
  dispatch consults FF-01 alone, per the task scope). Translation stages do
  not exist yet, so such a run stops after preflight by construction.
- R4: orientation for the checkpoint `SourceIdentity` is derived from the
  decoded page dimensions (`PORTRAIT` when `height > width`, else
  `LANDSCAPE`), not from EXIF; matches the M1 harness convention; revisit if
  an EXIF-accurate orientation source is threaded into the lane later.
- R5: under three concurrent agents, the timing-sensitive coexistence suites
  (10 s wall-clock awaits in D2/D3/D10) flaked once each across runs, always a
  different test, each green on isolated re-run; the final full-scope run was
  green end-to-end (132/132). No flag-related cause (OFF path unchanged).
