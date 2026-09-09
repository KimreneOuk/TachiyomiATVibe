# Legacy-interference audit — T924 profile pipeline vs legacy machinery

Date: 2026-09-09. Audited code: worktree `TachiyomiAT-t924-impl`, branch
`t924/batch-profile-pipeline`, HEAD `9baa8aa` (verified clean).

Method: two independent read-only sweeps — (A) runtime dispatch / lifecycle /
shared-seam interference, (B) durable-state / storage / reader-surface
interference — followed by Main-Leader selective verification of the
load-bearing HIGH claims (marked **[ML-verified]** below; all other file:line
citations are from the sweeps). This audit intentionally excludes the known
findings F-1..F-4 (fixed `45216e7`/`9baa8aa`), F-5/F-6/F-7, F-W6-3, F-W7-3,
and the lmstudio/lm_studio spelling debt.

Unified finding IDs: LI-1..LI-15. A finding marked **A/B-BLOCKER** must be
fixed before gate 5.7.

---

## Tier 1 — A/B-BLOCKERS (HIGH)

### LI-1 (HIGH, A/B-BLOCKER) **[ML-verified]** — shell projects every flagged-completed chapter as ERROR through the legacy display-committed predicate

The shell's post-pass reconciliation runs for ANY non-PAUSED outcome,
regardless of which coordinator produced it
(`pipeline/batch/BatchChapterTranslator.kt:902-928`):
`BatchProgressReconciler.reconcile(pageMap = store.state.value, ...)` then
`tracker?.finish(reconciliation)`. The reconciler's done-predicate is the
legacy display-committed set `page.hasRenderedResult || page.isTextlessTerminal`
(`pipeline/batch/BatchProgressReconciler.kt:89`), and
`hasRenderedResult` → `singlePageProjection().displayReady` →
`isTranslationDisplayShapeReady()` which requires
`isCleanedImageReady && translation READY/PARTIAL && renderStatus == READY`
(`model/PageDisplayProjection.kt:140-144`).

The flagged lane commits translations WITHOUT an in-pass render (renderStatus
stays PENDING; no cleaned image; display comes later from committed bundles +
persisted layout plans). Its own terminal predicate is deliberately broader —
`t924PageTerminalAtFinalize` (`ChapterProfileBatchCoordinator.kt:1713-1726`)
counts translation READY/PARTIAL/FAILED/SKIPPED/TEXTLESS as terminal and its
doc says reusing the legacy definition "would mark every healthy page
stranded".

Consequence: a healthy flagged completion (translation-committed pages) hits
the reconciler's final `else` branch → every page stranded → `chapterStatus =
ERROR` (`BatchProgressReconciler.kt:106-116`) → `tracker.finish(ERROR)`,
`translateChapterInternal` sets translation status ERROR, and
`StoreStatusProjector.artifactStatus()` re-runs the same legacy reconcile
(`store/StoreStatusProjector.kt:116`, consumed by the manga-screen durable
status, `manager/DurableChapterStatusResolver.kt:148,154`). User retry →
`resumeFinalizeOrComplete` returns zero-work COMPLETED instantly → shell
reconcile → ERROR again: a permanent record-vs-display desync retry loop. The
flag-OFF TreatAsFinished outcome flows into the same post-pass. Mitigating:
the stranded-page write itself is rejected (flagged run deregistered its batch
write identities) so page data is not corrupted — only projected/queue/UI
state is wrong. Test gap: no test drives a flagged run with translatable
pages through the shell (BatchDispatchResumeWiringTest Case 1 used an
all-textless fixture, which escapes via `isTextlessTerminal`).

Fix direction: make the post-pass projection coordinator-aware — for flagged
COMPLETED/resume outcomes, project done-pages from the profile terminal
predicate / committed-bundle evidence instead of the legacy display-committed
predicate (Technical Lead to define exact projection; the reconciler reads
live pages only today, so manifest-awareness or a flagged-aware reconciler is
needed).

### LI-2 (HIGH, A/B-BLOCKER) **[ML-verified]** — flag-ON COMPLETE fast path has no display-evidence gate: chapter/inpaint reset is silently a no-op

All reset paths (`manager/ChapterDataResetController.kt:195-282`:
`resetChapterTranslationData`, `resetChapterInpaintData`, `resetChapterData`)
demote committed displays and clear pages but touch NO T924 durable state —
`activeRun`, run records, checkpoint/profile/envelope pointers all survive
(grep: zero hits for `activeRun|runRecord|ocrCheckpoints` in that file;
`activeRun` is installed at `artifact/ChapterArtifactStore.kt:337,364` and
never cleared anywhere).

On the next flagged dispatch, `resumeFinalizeOrComplete`
(`ChapterProfileBatchCoordinator.kt:1654-1677`, ML-read) returns zero-work
COMPLETED on `COMPLETE + frozenRunConfigFingerprint + orderedSourceDigest`
match — no per-page display-evidence check. Source bytes unchanged → digest
matches → the reset is silently undone: the chapter reads as complete with
demoted/blank translations, and no stage ever re-runs. The flag-OFF lane is
protected by F-4's `allPagesDisplayCommitted` gate
(`BatchChapterTranslator.kt:168-174,678-684`); the flag-ON fast path got no
equivalent. No test pairs a reset with a subsequent flagged dispatch.

Fix direction (recommended): reset paths that demote committed displays must
retire the run's durable identity (clear `activeRun` / publish a terminal
record), making the next dispatch a genuine fresh run; optionally mirror the
F-4 display-evidence gate in `resumeFinalizeOrComplete`'s COMPLETE branch as
belt-and-braces.

---

## Tier 2 — fix before A/B (pollutes measurements; MED)

### LI-3 (MED) — reader/manual per-page writes punch through BATCH-held leases; one foreign write pauses the whole flagged run

`updatePageFromCurrentSnapshot` builds its precondition from the CURRENT
snapshot, whose lease token is the BATCH token
(`store/ChapterTranslationStore.kt:738-742`, `snapshotLocked:2361`,
`pageWriteRejection:1435`) — so a foreign writer can write through a
BATCH-held lease (contrast `updatePage:1471`, which refuses). Writers using
this idiom during a flagged run: per-page cancel and auto-cancel
(`scheduling/TranslationScheduler.kt:808-886,915-929`), plus
`fastCancelInFlightStagesInMemory` (`ChapterTranslationStore.kt:752-778`, no
mutex, no lease check). Scenario: reader open / per-page retry mid-run flips a
RUNNING OCR page to CANCELLED → `checkpointOcr` rejects on pageVersion →
whole preflight FAILED with a durable failure charge on a healthy page; or a
translate-time write lands between envelope dispatch and commit → whole-run
PAUSE after the provider call was paid. The legacy lane survives the same
interference via T917 defer-and-rescan; the flagged lane has no rescan.
Admission is not gated either (`TranslationLifecyclePolicy.shouldSchedule` is
purely page-state-based). Existing lease tests cover MANUAL-vs-BATCH on the
legacy lane only.

### LI-4 (MED) — background `verifyLegacyArtifactHealth` republishes the manifest after the façade cached the pre-verification copy; first durable write of a dispatch is spuriously rejected

For >8-page chapters the ARTIFACTS open path launches
`GlobalScope.launch { verifyLegacyArtifactHealth(...) }` and returns the
PRE-verification manifest to the façade
(`artifact/LegacyChapterMigrationSource.kt:257-270`); the verify publishes a
VERIFIED manifest, bumping the generation (`artifact/LegacyArtifactRescue.kt:258-295`).
A dispatch opened in that window presents the stale façade manifest to its
first `checkpointOcr`/`publishRecord` → `staleManifestRejection`
(`artifact/ChapterArtifactStore.kt:1512-1520`) → spurious PAUSED /
PERSISTENCE_REJECTED on a healthy chapter (F-3's RUN_CLOSURE_REJECTED path at
`ChapterProfileBatchCoordinator.kt:1614-1626`). Most manga chapters are >8
pages; first-open-then-translate is the normal A/B flow. The synchronous ≤8-page
path is unaffected. Fix direction: await verification before returning the
manifest, or re-read once on stale rejection.

### LI-8 (HIGH-if-reachable, same family as LI-4) — mutex-free `store.artifactManifest` façade writes + non-CAS `publishManifest` can durably revert T924 pointers (then retention deletes the orphaned files)

The coordinator writes the façade WITHOUT the store mutex
(`ChapterProfileBatchCoordinator.kt:747,1038,1288,2505`; `BatchRenderJoin.kt:550`;
plain `internal var`, `ChapterTranslationStore.kt:133`), while
`preRegisterPages` (:1746) and the registration branch of
`persistArtifactMutationLocked` (:1916) publish whole manifests via
`store.publishManifest(...)` — the only publish paths with NO
`staleManifestRejection` CAS — built from the façade snapshot. A mutex-held
publish reading a stale façade between two coordinator publications durably
reverts `activeRun`/profile/checkpoint pointers; the next retention sweep
retains only pointer-reachable files (`store/StorePersistenceScheduler.kt:135-139`,
`artifact/ArtifactRetention.kt:88-127`) and physically deletes the orphaned
sidecars. Benign direction: stale façade into a CAS'd transaction → spurious
rejections (LI-4 is the proven reachable instance). The dangerous direction
needs a mid-run page re-registration racing a coordinator façade write —
reachability not proven statically (open question), but the fix is mechanical:
volatile façade + make the two remaining publishers CAS'd, or route all
manifest publication through the mutex. Cheap insurance; schedule with the
next code wave.

---

## Tier 3 — next wave (correctness debts, not A/B-blocking)

### LI-5 (MED) — per-page OCR reset silently undone via checkpoint reuse
`deleteLivePage`/`demoteLivePage` explicitly keep `manifest.ocrCheckpoints[pageKey]`
(`artifact/ChapterArtifactStore.kt:1210-1274`); `reusableCheckpointFingerprint`
matches source-sha only (`ChapterProfileBatchCoordinator.kt:2428-2439`) →
`adoptCheckpointSnapshot` (:1942-1986) resurrects pre-reset OCR on the next
flagged dispatch. Fix: per-page OCR reset must drop the checkpoint pointer.

### LI-6 (MED) — checkpoint reuse identity is source-sha only; engine/model changes silently reuse old-engine OCR
`RunConfigSnapshot` freezes `ocrEngine`/`ocrModelHash`/`detectorModelHash`
(`artifact/ChapterRunRecord.kt:42-69`) and its doc claims checkpoint reuse is
safe "being keyed by content fingerprints" — but reuse validates only the
source sha, so an engine/model change between dispatches yields a "new" run
translating from old-engine checkpoints (and the corpus fingerprint is derived
from that adopted content, `ChapterArtifactStore.kt:644-661`). Product
decision needed: key checkpoints on engine identity, or accept engine-agnostic
reuse explicitly.

### LI-7 (MED) — profile commits omit glossary-version / translationFingerprint / translationOrigin stamps; switching back to the legacy lane re-translates the whole chapter
`ProfileEnvelopeExecutor.commitPages` stamps
`profileContentFingerprint`/`envelopePlanFingerprint` but no
`translationGlossaryVersion`/`translationFingerprint`/`translationOrigin`
(`pipeline/batch/ProfileEnvelopeExecutor.kt:741-813`); the legacy planner's
glossary gate then downgrades every profile-committed page from REUSE to RUN
(`GLOSSARY_MATURED`, `pipeline/batch/PageWorkPlanner.kt:322-332`) whenever the
chapter's glossary version > 0. Flag-ON→OFF direction only (legacy→flagged is
benign); becomes common at Phase 3 (default ON + lane switching). Same
provenance-stamp class as the F-W7-3/OWED schema item — a per-envelope
provenance schema extension is already owed to the retrans-validation stage;
fold this in.

### LI-9 (MED) — legacy lane bypasses the shared Batch sub-limit gate
Flagged executors wrap dispatch in `sublimitGate.executeBatch`
(`AnalysisChunkExecutor.kt:171`, `ProfileEnvelopeExecutor.kt:564`); the legacy
lane calls `translateAiChunkWithAdaptiveRetry` directly
(`pipeline/batch/BatchLaneWorkers.kt:485`) with only bucket-1 governor
admission — no `executeBatch` consumer. Concurrent legacy + flagged chapters
against one credential exceed the intended aggregate allowance, with no
per-lane fairness. Matters only during mid-rollout concurrency: for the A/B,
do not run legacy and flagged chapters simultaneously against the same
credential.

### LI-10 (MED) — frozen-vs-live divergence inside one flagged run
`frozenRunConfig` freezes `inpaintMode` at dispatch (participates in the ST-15
fingerprint) but execution uses the SHARED live native lane whose mode is read
live (`TranslationPipeline.kt:274`); `isAi`/`contextualTranslator` are
captured once while the legacy lane hot-swaps live. A mid-run pref/engine
change produces a mixed-mode chapter whose record claims the old mode, with no
ST-15 restart. Defer (Director protocol already avoids mid-run changes); must
close before default-ON.

---

## Tier 4 — LOW / defer

- **LI-11 (MED/LOW)** `ensureArtifactStoreLocked` force-flip: if open-time
  `migrateArtifactManifest` throws (transient SAF error) while a legacy JSON
  with translations exists, an EMPTY artifact manifest is force-flipped to
  ARTIFACTS authority — the legacy JSON becomes permanently invisible and its
  translations are stranded (`ChapterTranslationStore.kt:2060-2111`,
  `artifact/LegacyChapterMigrationSource.kt:55-65`). Corner-reachability only.
- **LI-12 (LOW)** `frozenRunConfig` omits prompt version and output-token
  budget (`ChapterProfileBatchCoordinator.kt:2788-2813`): the
  `translationAiOutputTokens` pref is inert in the profile lane (constant
  ANALYSIS_MAX_OUTPUT_TOKENS used) and a future prompt change would not
  invalidate in-flight/COMPLETE runs. Latent invalidation-matrix gap.
- **LI-13 (LOW)** FF-02 re-read per page mid-run (`BatchRenderJoin.kt:373,601`)
  → partial layout-plan coverage on mid-run flip. Fail-safe by design.
- **LI-14 (LOW)** `reconcilePaused` counts done via the legacy display
  predicate → flagged-paused chapters show 0/N progress in the tracker
  (`BatchProgressReconciler.kt:147-185`). Cosmetic; folds into the LI-1 fix.
- **LI-15 (LOW)** the shell ignores `guardedBatchUpdate` results for stranded
  writes (`BatchChapterTranslator.kt:913-919`) — silent-rejection idiom
  masked the LI-1 mismatch; folds into the LI-1 fix.

---

## Verified clean (sweep consensus, load-bearing citations)

- **Single dispatch entry / no dual coordinators per chapter:** exactly one
  production `translateBatch` caller chain (manga-screen, download-complete,
  retry, startup admission; startup never auto-starts); queue duplicate-check
  + one-worker-per-source (`ChapterTranslator.kt:388-393,503,783`); FF-01 read
  at exactly one site per run (`BatchChapterTranslator.kt:666-668`).
- **Legacy coordinator writes no T924 sidecars** at the coordinator level:
  `SequentialBatchCoordinator` does zero direct store/artifact writes;
  `publishActiveRun`/`store.checkpointOcr` have no legacy callers (grep).
  (The store-level façade hole is LI-8.)
- **Authority cutover is one-way:** post-cutover legacy JSON is never
  rewritten (`StorePersistenceScheduler.kt:74-85`); open reads legacy bytes
  only when `!isArtifactAuthoritative`; rescue renames legacy input.
- **Retention preserves every pointed T924 sidecar family** (activeRun,
  ocrCheckpoints, analysisChunks, profile, envelopePlan, layoutPlans,
  colorPreparations, attempt ledger; `ArtifactRetention.kt:88-127`); finalize
  sweeps under NonCancellable.
- **Shared governor/gate singletons hold** (no F-W4-2 regression): exactly one
  production construction each of AnalysisChunkExecutor
  (`BatchChapterTranslator.kt:724`), ProfileEnvelopeExecutor
  (`ChapterProfileBatchCoordinator.kt:1353`), OverlapScheduler (:735,
  per-run, never legacy); all providers + retry admit through
  `SharedProviderRequestGovernor.instance`; F-1 single-admission idiom intact.
- **No small-chapter bypass exists in code** (the DB-10 debt is a decision
  not yet implemented — nothing skips the profile pipeline or preflight by
  size today; the only "small chapter" logic is the ≤8-page retention sweep,
  a cleanup bound).
- **Resume gates flag-aware and consulted before any work;** queue restore
  never auto-starts flagged runs; reader backgrounding does not cancel a
  batch (`ReaderTeardownCoordinator.kt:67-79`,
  `TranslationManager.kt:730-737`); explicit user stops do (user intent).
- **Queue/tracker PAUSED semantics intact** (F-3 fix holds at queue level:
  PAUSED → isPaused, retryable; PERSISTENCE_REJECTED → ERROR +
  nonDurableFailure; removal only on TRANSLATED/READY_WITH_WARNINGS,
  `ChapterTranslator.kt:415-444`).
- **Layout persistence is cross-lane safe by construction:** the hydrator can
  only ignore (never mis-apply) a wrong-lane plan — full compatibility matrix
  + user-edit authority + mandatory planner fallback
  (`rendering/PersistedLayoutHydrator.kt`).
- **Fingerprint bases are lane-disjoint:** T924 hashes built exclusively from
  artifact-state inputs (`artifact/StageFingerprints.kt`); legacy-lane
  fingerprints never feed T924 hashes; CURRENT_INPAINT_REVISION honored
  symmetrically.

## Open questions

1. LI-8 dangerous direction: is mid-run page re-registration
   (`pendingArtifactPageRegistrations` non-empty during an active flagged run)
   reachable in production? Needs a targeted trace before/with the fix.
2. LI-1: does the async display pipeline eventually promote
   renderStatus/display state for flagged pages (self-healing the manga-screen
   status) even though the immediate tracker/queue projection is ERROR?
   Determines whether the fix must touch the reconciler or only the shell's
   immediate projection.
3. LI-6: is engine-agnostic checkpoint reuse a deliberate cost-saving product
   decision or an oversight? (Director decision if deliberate.)
4. Whether any UI-level gate prevents reader auto-translation on a chapter
   whose batch is TRANSLATING (if none, LI-3's trigger is one reader-open
   away — assume none and fix LI-3 defensively).

## Recommended sequencing

1. **Pre-A/B wave:** LI-1 + LI-2 (one coherent "COMPLETE semantics +
   coordinator-aware projection" fix), then LI-3 + LI-4. Add the missing
   tests: flagged-run-through-shell with translatable pages;
   reset-then-flagged-redispatch; stale-façade dispatch.
2. **Gate 5.7 A/B** on the wave's commit (fresh install; reconcile request
   counts against per-envelope logcat lines per F-W7-3; no simultaneous
   legacy+flagged chapters per credential per LI-9; no mid-run pref changes
   per LI-10).
3. **Next wave:** LI-8 (façade/CAS hardening) + LI-5 + LI-6 + LI-7 (with the
   owed provenance-schema extension) + LI-10.
4. **Defer:** LI-11..LI-15 (fold LI-14/LI-15 into LI-1's fix).
