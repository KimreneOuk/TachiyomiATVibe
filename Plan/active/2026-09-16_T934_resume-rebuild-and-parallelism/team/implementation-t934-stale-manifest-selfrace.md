# T934 Implementation Report: stale-manifest self-race on resume — envelope-plan seam, façade coherence, adoption-write coalescing

Branch `t934/resume-rebuild-and-parallelism`, HEAD `44b9138`, worktree `orchestrate_execution_order_v3`. Not committed.

## Discovery findings

**Adoption write sites during `buildEnvelopeDispatchWork` (the ~9 min resume rebuild):**

- `ChapterProfileBatchCoordinator.adoptCheckpointSnapshot` (coordinator, called per pending page at `ChapterProfileBatchCoordinator.kt:2774`) → `store.mergeOcr` → `applyStagePatch` → `mergeOcrLocked` → `publishLocked` → `persistArtifactMutationLocked` (`ChapterTranslationStore.kt:2023`), which performs up to four durable manifest publications per page:
  1. **page registration** — was a BLIND `store.publishManifest(added)` built from the façade snapshot (`ChapterTranslationStore.kt:2067` before this change; no CAS at all);
  2. `store.openCandidate` (CAS + LI-4/T934 one-shot stale retry);
  3. `store.persistLiveCandidate` (CAS + retry);
  4. `store.promoteLiveCandidate` when the adopted page is display-terminal (CAS + retry).
  With `GroupCommitConfiguration.enabled = false` (the default; `artifact/GroupCommitConfiguration.kt:9`) every adoption takes the direct persist path — hence the observed ~340 full manifest JSON rewrites on a 206-page chapter.

**Façade behavior:**

- `ChapterTranslationStore.artifactManifest` IS refreshed after every `Committed` store transaction on the adoption path (`persistArtifactMutationLocked` assigns it after open/persist/promote — lines 2147/2173/2198/2223/2249/2270/2273 region). So the adoption publications themselves were NOT the direct façade-staleness source.
- The staleness/clobber source is the **>8-page open path's background health verify**: `LegacyChapterMigrationSource.kt:259-274` launches `verifyLegacyArtifactHealth` on `GlobalScope/IO`, which publishes the `legacyMigration.health=VERIFIED` manifest DIRECTLY into the store via `publishManifestInternal` (`LegacyArtifactRescue.kt:267`) — it never touches the façade. Two consequences:
  - a façade snapshot taken before the stamp lands (coordinator `runEnvelopePlanAndTranslate:1684`, or any snapshot the plan publish presents) is stale → the envelope-plan publication had NO retry → `Rejected("stale manifest snapshot…")` → whole batch `PERSISTENCE_REJECTED` (reproduced 2/2 on device);
  - the registration write (site 1 above) blind-published a manifest rebuilt from the pre-verification façade snapshot, silently reverting `legacyMigration` VERIFIED → INITIAL_CUTOVER (and any interleaved adoption pointer moves, depending on interleaving order).

## Per-task changes

### Task 1 — envelope-plan publication rebase-retry

- `ChapterArtifactStore.kt:1975` — `internal fun isStaleManifestRejection(outcome)`: exposes the existing `STALE_MANIFEST_REJECTION_REASON` prefix check to the one seam whose mutation lives outside the store.
- `EnvelopePlanPublication.kt:43-115` — `publish()` now factors the sidecar-then-pointer publication into a local `publishPointers(on)` and on a stale-manifest rejection ONLY: re-reads the durable manifest once (`artifact.readManifest()`), WARN-logs `seam=envelope-plan staleUpdatedAt= freshUpdatedAt=` in the store's `retryOnStaleManifest` style, and re-runs the SAME pointer move against the fresh manifest. Retry is exactly one-shot; a retry that does not commit (or a vanished durable manifest) returns the ORIGINAL `Rejected` unchanged; every non-stale rejection returns as-is (T924-SC-20/22 preserved). The recomputed content fingerprint check is plan-derived and unchanged. Both call sites (coordinator `:1806` fresh-plan publish and `:2660` superseding re-plan) are covered by the single seam.
- **Semantics note:** the store's `retryOnStaleManifest` could not be reused verbatim because it returns the RETRY outcome rather than the original on second failure; the brief pinned "original Rejected surfaced", so the semantics are replicated locally in `EnvelopePlanPublication` (deliberate deviation, functionally identical for the stale reason string since both carry the same `STALE_MANIFEST_REJECTION_REASON` prefix).

### Task 2 — façade coherence + legacyMigration carry-forward

- `ChapterArtifactStore.kt:941-994` — `internal fun publishSidecarPointersWithStaleRetry(...)`: the generic sidecar-then-pointer transaction wrapped in the store's existing `retryOnStaleManifest` (`seam` parameter), reusable by façade-side seams.
- `ChapterTranslationStore.kt:2058-2128` — the page-registration publication now routes through `publishSidecarPointersWithStaleRetry(seam="page-registration")` with the registration mutation (page-set + expected count) recomputed as a pure function of the base manifest. On a stale-manifest rejection it rebases ONCE onto the fresh durable manifest; the façade is assigned the `Committed` manifest as before. A failure now WARNs with the rejection reason and fails the merge (previously a blind publish could "succeed" while reverting durable fields).
- **Carry-forward mechanism:** the registration and envelope-plan mutations are pure pointer/page-set/count mutations — rebasing onto the fresh durable manifest carries every other field, `legacyMigration` included, by construction. The open/persist/promote adoption transactions already had this property via their `retryOnStaleManifest` wrapping. Verified by `ChapterArtifactStoreManifestCoalescingTest` (VERIFIED marker survives a 3-page adoption chain that starts from a pre-verification façade snapshot).
- The rest of the adoption path needed no change: façade refresh after each `Committed` already existed (see Discovery).

### Task 3 — adoption-write coalescing

- `ChapterArtifactStore.kt:2211-2345` — coalescing window:
  - `beginManifestCoalescing()` (:2252) / `endManifestCoalescing()` (:2265, counted nesting; outermost end performs the mandatory final flush, best-effort with WARN on failure);
  - `publishManifestInternal` (:2289): while a window is open, each publication only STASHES the intended manifest (last-writer-wins under the store monitor) with OR-accumulated `syncToDisk`, performing a durable rewrite at most every `MANIFEST_COALESCING_FLUSH_EVERY = 32` staged publications (:2310) and ALWAYS at `end`. A failed mid-window flush returns `false`, failing the owning transaction exactly like a direct publication failure;
  - `casBaselineManifest()` (:2286): while a window is open the CAS baseline (`staleManifestRejection` :1953, `retryOnStaleManifest` :2006) is the STASHED manifest, not the lagging file — without this the serialized transaction chain would stale-reject against its own deferred writes and rebase onto manifests that drop them. With no window open the baseline is the durable file, unchanged.
- `ChapterProfileBatchCoordinator.kt:2709-2733` — `buildEnvelopeDispatchWork` is now a thin wrapper: `beginManifestCoalescing()` → try → `buildEnvelopeDispatchWorkLocked(...)` (the unchanged body) → `finally endManifestCoalescing()`. Every early-return and exception path flushes before the plan publish; the post-coalescing façade (last `Committed` manifest) equals the durable manifest.
- 100 adoptions now produce 4 durable manifest rewrites (flushes at 32/64/96 + final) instead of 100+. Durability flags (syncToDisk etc.) are preserved via accumulation. Correctness no longer depends on coalescing (Task 1/2 rebase on drift); on any doubt the store flushes.

## Tests

- `app/src/test/java/eu/kanade/translation/pipeline/batch/EnvelopePlanPublicationTest.kt` (+3, reusing its fixtures):
  1. `plan publication rebases onto a drifted durable manifest and preserves the concurrent change` — intervening VERIFIED-stamp publication between snapshot and plan publish → Committed, plan pointer lands, VERIFIED + fresh timestamp survive.
  2. `a non-stale rejection still fails as-is without a retry` — armed sidecar promote-rename failure with a current manifest → `sidecar publication failed` surfaces, exactly one sidecar write attempt, manifest untouched.
  3. `retry exhaustion surfaces the original stale rejection without looping` — stale first attempt + armed failure on the retry → ORIGINAL `stale manifest snapshot` rejection surfaces, exactly one sidecar write attempt (no loop), nothing published.
- `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactStoreManifestCoalescingTest.kt` (new, 3 tests; fixtures mirror `ChapterArtifactStoreStaleManifestRetryTest`):
  4. `coalesced adoptions bound durable rewrites and flush every adopted pointer on end` — 100 chained `openCandidate` adoptions in a window → exactly 3 mid-window durable rewrites + 1 final; ALL 100 candidate pointers + `activeCandidateGenerationIds` present in the final durable manifest; last Committed manifest (façade) == durable.
  5. `coalesced adoptions rebase onto a stale façade and carry the VERIFIED marker forward` — adoption chain from a pre-verification snapshot behind a durable VERIFIED stamp → health stays VERIFIED, all adoptions land, façade == durable, single durable rewrite.
  6. `a failed mid-window flush fails the owning transaction like a direct publication failure` — armed manifest write failure at the 32-publication flush → `openCandidate` Rejected `manifest publication failed`, window closes cleanly, later publication recovers.

### Results (real output)

- `./gradlew.bat :app:testStandardDebugUnitTest --tests eu.kanade.translation.pipeline.batch.EnvelopePlanPublicationTest --tests eu.kanade.translation.artifact.ChapterArtifactStoreManifestCoalescingTest --tests eu.kanade.translation.artifact.ChapterArtifactStoreStaleManifestRetryTest` → BUILD SUCCESSFUL; 7+3+11 = **21 tests, 0 failures, 0 errors** (JUnit XML: failures="0" errors="0").
- Neighbor sweep (`eu.kanade.translation.artifact.*`, `ChapterTranslationStore*`, `OcrCheckpointRestartReuseTest`, `coexistence.BatchDispatchResumeWiringTest`): green on pristine HEAD and green ×2 full fresh (`--rerun-tasks`) runs with the changes. One transient failure of `BatchDispatchResumeWiringTest` (`run 1 never reached COMPLETE within 10000ms … a load-induced typed pause is legal production behavior`) occurred only on the first cold sweep while the new tests were compiling; it passes alone, passes in both subsequent fresh full sweeps with the changes, and the harness's own message documents wall-clock load sensitivity. Assessed pre-existing flake, not a regression (no assertion or behavior of that test interacts with the changed seams).
- Spotless: `:app:spotlessCheck` flags exactly the same single pre-existing androidTest file at HEAD and with the changes (verified by differential); no new violations.

## Deviations from the brief

1. Task 1 retry semantics are replicated locally in `EnvelopePlanPublication` (not routed through the store's private `retryOnStaleManifest`) so the ORIGINAL `Rejected` is returned on a failed retry, exactly as the brief specified; the store-level `publishSidecarPointersWithStaleRetry` used for the registration seam does return the retry's outcome (identical observable contract for stale reasons, consistent with all other store seams).
2. Brief test 3's literal "drift on both attempts" is structurally impossible against the monitor-serialized store (the retry's fresh read and its CAS share the monitor), so exhaustion is forced at the IO layer (sidecar promote-rename failure on the retry); the pinned outcome (original stale rejection, exactly one retry, nothing published) is asserted.
3. Coalescing interval is publication-based (every 32 staged manifest publications, ≈ every 8–32 adoptions) rather than wall-clock; the brief allowed either ("per N adoptions … or every few seconds").

## Tasks 4-6 (second implementer)

Implemented on top of the Tasks 1-3 changes (uncommitted in this worktree); none reverted or reformatted.

### Changes (file:line, current working tree)

**Task 4 — live progress during the envelope-plan rebuild window**

- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchCoordinatorInterfaces.kt:189-219` — `BatchScheduleListener` gains `envelopePlanStarted(totalPages)`, `envelopePlanProgress(done, total)`, `envelopePlanCommitted()` with no-op defaults (existing listeners unaffected).
- `app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt:2728` — `buildEnvelopeDispatchWork` fires `started(orderedPages.size)` before the coalescing bracket; :2778-2779 — the per-page loop (`for → forEachIndexed`, `continue → return@forEachIndexed`) fires `progress(index+1, corpus.entries.size)` per corpus entry; :1826 — `runEnvelopePlanAndTranslate` fires `committed()` after the plan publish block (reached by BOTH the `Committed` branch and the identical-fingerprint `reuse` path; the `Rejected` branch returns before it); :2662, :2677 — `rebuildDispatchWork` fires `committed()` on its `alreadyPublished` and `Committed` paths.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/TranslationBatchEvent.kt:35-49` — new `EnvelopePlanProgress(done, total)` + `data object EnvelopePlanCommitted`.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt:571-587` — adapter maps the three listener methods to new tracker methods.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/TranslationBatchProgressTracker.kt:119-125` — `markEnvelopePlanStarted/Progress/Committed`; :226-241 Projection gains `rebuildProgress: BatchRebuildProgress?`; :303-318 reducer maps Progress → `batchPhase = REBUILDING` + counter, Committed → `FIRST_PASS` + null; the `when` is now exhaustive (redundant `else -> previous` removed); `snapshotFor` copies `rebuildProgress` into the snapshot.
- `app/src/main/java/eu/kanade/translation/manager/BatchProgressProjector.kt:80-90` — `rebuildTruthFromRunRecord` gains `ENVELOPE_PLAN → REBUILDING` (was `null`), so the record-driven path (sheet reopened without a live tracker, probes) also shows the rebuild window.

**How the sheet stays live:** every per-page rebuild event is a Channel trySend; the tracker recomputes the FULL snapshot per event from live `store.state` — so the sheet's AI-translation pending/buffered/running counters, hero page numbers and the notification progress text keep updating during the ~9-minute adoption loop, while `batchPhase = REBUILDING` keeps the sheet's indeterminate LinearProgressIndicator + pulsing "Rebuilding" pill + "Rebuilding pipeline…" status line (all pre-existing T934 U.2/U.6 presentation). `envelopePlanCommitted()` returns the projection to FIRST_PASS so the window never outlives the run; early exits (CorpusDrift/NothingPending/PlannerRejected) end in typed pauses whose terminal events overwrite the phase. Phase choice: REBUILDING (not RESTORING) deliberately — the projector's `withRunRecordTruth` overrides phase+counters from the durable record every ≥500 ms, and the ENVELOPE_PLAN record's counters are static (published once at window entry), so a RESTORING mapping would have made the record fight the tracker's live counters; REBUILDING renders no counter anywhere, so record truth and tracker events agree invisibly.

**Task 5 — render the rejection reason**

- `app/src/main/java/eu/kanade/presentation/manga/components/TranslationProgressSheet.kt:226-252` — new red banner (same `errorContainer` Surface + Warning icon presentation as the abort banner) when `snapshot.nonDurableFailure && !snapshot.aborted`, text `manga_batch_not_saved` = "Batch failed: <reason>", reason truncated to 200 chars. Abort keeps precedence so the banners never stack.
- `i18n-at/src/commonMain/moko-resources/base/strings.xml:161` — new `manga_batch_not_saved` string.
- `app/src/main/java/eu/kanade/translation/ui/TranslationNotificationCopy.kt:54-73` — nonDurableFailure branch appends ": <reason>" (truncated 120 chars) when `nonDurableFailureReason` is non-blank; absent → byte-identical historical copy. `TranslationUiTruth`/`ChapterTranslationIndicator` untouched (kept generic, per brief).

**Task 6 — race-safe verifier publish**

- `app/src/main/java/eu/kanade/translation/artifact/LegacyArtifactRescue.kt:266-283` — before the VERIFIED publish, `stampBase = store.readManifest() ?: manifest` (reuses the store's public `readManifest`; no new accessor needed — the Tasks 1-3 diff added no fresh-read helper) and the `legacyMigration = verifiedMetadata` mutation is applied onto THAT fresh durable manifest; fallback to the open-time manifest preserves old behavior when nothing durable is readable. One-shot, no retry. The subsequent cleanup-marker publication flow is byte-untouched and naturally builds on the fresh-based `verifiedManifest`, so the concurrent change survives the final result too.

### Carry-forward / race-safety notes

- The verifier's health probes still validate the OPEN-TIME manifest (per brief); the re-read only fixes the lost-update at publish time. The residual read-then-publish window remains but is now milliseconds instead of open-to-verify minutes — acceptable for best-effort background work (brief: one-shot).
- The tracker-driven REBUILDING phase is stamped only inside the live run window; terminal snapshots and pauses are never restamped, and the probe's `withRunRecordTruth` overrides are invisible in REBUILDING (no rendered counter), so no record-vs-tracker fighting.
- Fresh (non-resume) runs also traverse `buildEnvelopeDispatchWork`; the plan-derivation window there is short and now honestly labeled "Rebuilding…" instead of silently freezing — no regression path identified (all phase flips end at `committed` or a typed pause).

### Tests (all green in one invocation)

- `TranslationBatchEventContractTest` — sealed-surface lock updated for the two new events + construction tests (was encoding the old surface).
- `TranslationBatchProgressTrackerTest` — +2: plan events flip FIRST_PASS→REBUILDING with live counter, `committed` ends the window; rebuild events never mutate the store.
- `T934ProjectorRebuildTruthTest` — +1: ENVELOPE_PLAN record projects REBUILDING on the live snapshot (was null).
- `T934RebuildTruthTransitionsTest` — ENVELOPE_PLAN moved out of the "no rebuild phase" assertions into a dedicated REBUILDING test (neighbor encoded the old null mapping); class doc updated.
- `P5CopyAndAccessibilityTest` — rejection-reason text asserted WITH reason (neighbor encoded the old reason-less copy), + reason-absent → historical copy unchanged, + 120-char truncation.
- `ChapterArtifactStoreTest` — +1 `verify stamps the CURRENT durable manifest when it changed after open`: durable gains `expectedPageCount` between open and verify → result is VERIFIED **and** retains the concurrent field, in the returned AND durable manifest.
- `./gradlew.bat :app:testStandardDebugUnitTest` over 19 classes touching these seams: **191 tests, 0 failed, 0 errors** (JUnit XML). Spotless untouched; no repo-wide apply.

### Deviations

1. `envelopePlanCommitted()` also fires on the plan-REUSE path (and `rebuildDispatchWork`'s `alreadyPublished` branch), not only the literal `Committed` branch — otherwise the phase would stay REBUILDING while translation starts; the brief's "(the Committed branch)" is covered.
2. Reason truncation is a plain `take(n)` without an ellipsis suffix (~200 sheet / ~120 notification, per brief).

## Protocol-verdict relaxation (third implementer)

Director decision 2026-09-17 ("go"): relax the PROGRESS policy for batch-envelope
`ambiguousProtocol` verdicts, keep the COMMIT policy strict. On-device the model
silently omits specific blocks regardless of envelope size even after the
whole → whole → missing-only → missing-only retry budget; the old
discard-and-pause zeroed all batch progress past the first stubborn envelope.

### Provenance note (disclosed)

This worktree already contained a complete, uncommitted implementation of
exactly this assignment (main + dispatch-test files, written ~30–70 min before
this session started) — evidently an interrupted earlier run of the same brief.
It was verified line-by-line against the Director decision, kept as-is where
correct (all of the executor/contracts code), and the missing pieces were
completed here: the failing ST-12 neighbor test update (required — see
Deviations), all test executions, and this report. Nothing was reverted.

### Changes (file:line, current working tree)

- `app/src/main/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopeExecutor.kt`
  - :217-247 — run loop handles the new NON-pausing
    `EnvelopeDispatchResult.PartiallyParked`: advances to the next envelope
    (batch continues); `pagesParked` counter added to Counters/progress map
    (:137, :158); zero-commit circuit breaker below.
  - :194-198 + :232-247 — breaker: consecutive `PartiallyParked` envelopes with
    ZERO fully-covered commits are counted (any commit resets); at
    `MAX_CONSECUTIVE_ZERO_COMMIT_ENVELOPES = 3` (:1276) the batch returns the
    old typed `PhaseOutcome.Paused` (PROTOCOL/PAUSE-normalized failure, resume-
    eligible via `retryAfterAtEpochMs`, anchor = first parked page key; already
    parked pages stay parked — they remain retryable, so resume re-plans them).
  - :321-331 — new `PartiallyParked(fullyCoveredCommitted, parkedPageKeys,
    failure)` dispatch result variant.
  - :742-758 — `ambiguousProtocol` branch (covers `AiChunkOutcome.Terminal`
    PROTOCOL kind AND PAUSE-class PROTOCOL outcomes with uncovered pages)
    now routes to `commitAndParkProtocolOutcome` instead of
    `discardAndPause`. The REFUSAL branch above (:724-740) is untouched
    (strict discard + terminal pause), and genuine transport pauses
    (`ProviderRequestPausedException` → `Paused`) are untouched (:686-692).
  - :803-873 — `commitAndParkProtocolOutcome`: commits `fullyCovered` pages
    through the SAME `commitPages` TX-20 provenance ladder as MISSING_ONLY
    retention (a rejected commit still pauses WITHOUT parking — store drift
    may have invalidated the held identities), then parks
    `partiallyCovered` pages, then returns `PartiallyParked`.
  - :875-957 — `parkProtocolPages`: per page, computes omitted blocks
    (`dispatchBlocks` whose `stableBlockId` is not in `outcome.blockTranslations`),
    flips the live page to FAILED with a typed summary, and persists a
    `DurableFailureMetadata` (`stage=TRANSLATION`,
    `status=FAILED_RETRYABLE`, `category=PROTOCOL`, `envelopeId`,
    `missingBlockIds`, `missingBlockCharLengths`) via
    `ChapterTranslationStore.persistDurableStageFailure` — one mutex-fenced
    publication so page status and failure metadata are atomic across
    process death. Rejected parks are fail-open (page stays non-READY, so
    resume still re-plans it — parking is a diagnosis upgrade, not a
    correctness gate). One WARN line per parked page:
    `pageKey`, `missing=<n>/<total>`, `omitted=<blockId>:<charLen>c,...`,
    `envelopeId` — the self-describing evidence (char lengths, never text).
  - File header contract (:77-92) updated to the new verdict semantics.
- `app/src/main/java/eu/kanade/translation/artifact/ArtifactContracts.kt:220-226`
  — `DurableFailureMetadata.missingBlockCharLengths: Map<String, Int>`
  (defaulted, so all existing constructors/callers are unaffected).

### How parking surfaces in the sheet

A parked page is a standard durably-FAILED, retryable TRANSLATION failure —
the exact shape the standard lane already produces — so it appears in the
existing "pages need attention" UI with the typed message ("protocol failure:
envelope omitted N of M requested block translations after the retry budget
(…)"), and the progress counters gain `pagesParked`. Because the planner
re-plans anything not READY, "Retry translation" re-sends exactly the omitted
blocks (proven by the new replan test: after parking, a fresh run re-sends
ONLY the parked page's block and the chapter finishes).

### Tests

- `ProfileEnvelopeDispatchTest` (rewrote the old discard test + 3 new):
  - :478 mixed envelope (2 covered commit READY, 1 partial parks with omitted
    id + char length recorded) and the run DRAINS — next envelope proceeds;
  - :538 single-envelope 3-page case with exact durable-metadata assertions
    (stage/status/category/envelopeId/missingBlockIds/charLengths);
  - :578 three consecutive zero-coverage protocol envelopes trip the breaker:
    batch PAUSED, all 9 pages parked (durably FAILED), pagesTranslated=0;
  - :613 parking is retryable across simulated process death: fresh store
    re-plans ONLY the parked block, chapter completes.
  - Refusal guard kept: `structural refusal discards the response and pauses
    terminal` (:759, unchanged, still green).
- `Stage7FinalizeCoordinatorTest` :362 — neighbor encoded the OLD ST-12
  pause contract and failed under the new policy; updated to the new contract
  (drains COMPLETE; middle page parks retryably with omitted-block evidence;
  ST-12's surviving no-strand guarantee asserted). Note: envelope wire block
  ids are 0-based natural page indices (`p1_b1` = storage page p2) — the old
  test's "p2" comment was mislabeled; fixed.

### Results (real output)

- Targeted: `ProfileEnvelopeDispatchTest` 13/13 green;
  `Stage7FinalizeCoordinatorTest` 2/2 green;
  `ProfileEnvelopePromptEnrichmentTest` 6/6 (after a flake, see below);
  `BatchDispatchResumeWiringTest` 2/2 (after a flake, see below).
- FULL `:app:testStandardDebugUnitTest`: two runs each had ONE unrelated
  load flake (run 1: the real Stage7 contract conflict above; run 2:
  `ProfileEnvelopePromptEnrichmentTest` "Failed to close extension context" =
  JUnit @TempDir Windows file-handle cleanup, passes alone; run 3:
  `BatchDispatchResumeWiringTest` "run 1 never reached COMPLETE within
  10000ms — a load-induced typed pause is legal production behavior" — the
  exact pre-existing flake the second implementer documented; passes alone).
  Final fresh `--rerun-tasks` run: BUILD SUCCESSFUL, all 207 tasks executed —
  **2067 tests, 0 failures, 0 errors** across 290 classes.
- No spotless impact: `ktlint_standard_max-line-length` is disabled
  (`buildSrc/src/main/kotlin/mihon.code.lint.gradle.kts:32`); edits mirror the
  files' existing T9xx-tagged style. No repo-wide apply.

### Deviations

1. (Provenance) The implementation was found uncommitted in the worktree from
   an interrupted earlier run of this same brief; I verified it in full rather
   than rewriting it, and completed the test runs + neighbor fix + report.
2. `Stage7FinalizeCoordinatorTest` neighbor was updated (the brief said
   "extend" the dispatch tests; leaving the suite red was not an option — the
   neighbor encoded the superseded ST-12 pause contract).
3. The breaker counts `PartiallyParked` envelopes with zero COMMITS (per the
   brief: "zero fully-covered pages"); parking alone does not reset the
   streak — a provider that returns pure garbage still pauses after 3
   envelopes, exactly as specified.
