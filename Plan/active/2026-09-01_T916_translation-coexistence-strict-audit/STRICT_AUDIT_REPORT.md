# Strict Audit — Translation Subsystem Coexistence

## Verdict

**REJECT the document's `Canonical Reference Specification` status.**

`docs/architecture/translation-subsystem-coexistence.md` does not describe the current batch/manual/rolling-auto arbitration correctly. Its central claims—manual preemption, batch chapter ownership, bounded contention, strict FIFO admission, same-pass rescan, and uninterrupted reader work during batch stop—are either contradicted by live code, contradicted by existing tests, or unsupported by any executable evidence.

The most serious problem is not wording. The implementation can discard a manual user intent when batch owns the page, can skip a batch page when reader work owns it without actually rescanning that page in the same pass, and deliberately permits rolling auto to be rearmed while a batch for the same chapter is active. Those three behaviors make the advertised ownership model impossible.

Recommendation: withdraw the document's canonical label, treat current coexistence behavior as unsafe/undefined, define an explicit three-origin arbitration contract, implement deterministic interleaving tests, then rewrite the document from the proven behavior.

## Evidence standard

- **Confirmed defect:** direct production-code path or executable test demonstrates the contradiction.
- **Strong risk:** reachable code path exists, but an integration test is still needed to prove the user-visible outcome.
- **Documentation defect:** the prose, filename, line reference, metric, or state transition is factually wrong or unsupported.
- **Unknown:** the document asserts an outcome for which neither code nor a representative test supplies proof.

Passing component tests confirm only the scenarios encoded by those tests; they do not validate the architecture as a whole.

## Executive findings

| ID | Severity | Classification | Finding |
|---|---:|---|---|
| C-01 | Critical | Confirmed code/spec defect | Manual translation does not preempt or attach to a batch-owned page. Lease denial ends the manual boundary without waiting, retrying, or replaying user intent. |
| C-02 | Critical | Confirmed code/spec defect | A batch page denied by reader ownership is skipped for the pass. The coordinator does not rescan it, and reconciliation can strand/error it. |
| C-03 | Critical | Confirmed policy contradiction | Rolling auto may be rearmed while a batch for the same chapter is queued/active; an existing test requires this behavior. |
| H-01 | High | Confirmed model defect | Auto and manual share `READER_ADHOC`; the lease layer cannot express manual-over-auto priority. |
| H-02 | High | Strong code risk | Auto and manual can repeat native preparation and issue overlapping provider/render work; the duplicate-work guard covers only the native phase. |
| H-03 | High | Documentation/logic defect | The claimed “strict FIFO” and “manual becomes the next waiter” are not provided by the native `Mutex` and no priority queue exists there. |
| H-04 | High | Strong lifecycle risk | Stopping or completing batch closes shared engines without a reader-work ownership/refcount contract. “Manual is uninterrupted” is not established. |
| H-05 | High | Test-gap defect | There is no full production-path test that overlaps batch, manual, and rolling auto and asserts final state, artifact, provider-call count, and UI truth. |
| H-06 | High | Specification gap | No authoritative state machine defines lease denial, cancellation, handback, retry, stale completion, or batch completion with reader-owned pages. |
| M-01 | Medium | Confirmed behavior contradiction | Rapid flinging does not cancel already-started speculative auto work; the document says out-of-window in-flight work is retired. |
| M-02 | Medium | Documentation defect | Performance and memory numbers are presented as verified SLOs without device profiles, percentiles, measurements, or representative tests. |
| M-03 | Medium | Documentation defect | Storage filenames, JPEG quality, fingerprint algorithm, textless transition, and several source references are wrong or stale. |
| M-04 | Medium | Specification gap | Settings changes, provider/key changes, process death, reset/delete, service stop, and source mutation have no cross-mode transaction contract. |

## Critical coexistence failures

### C-01 — A batch-owned page can swallow a manual request

The document says manual work preempts batch work on the tapped page and “attaches” when another owner already has it. Production code does neither:

1. `TranslationScheduler.translatePage` launches a manual job.
2. `TranslationPipeline.runSinglePageBoundary` tries to acquire a `READER_ADHOC` page lease.
3. If batch owns the lease, `TranslationPipeline.acquireReaderPageLease` logs language describing attachment but returns `false`.
4. The boundary returns immediately.
5. The scheduler removes the completed manual job. No completion signal, lease wait, retry, requeue, or user-intent replay exists.

Evidence:

- `app/src/main/java/eu/kanade/translation/TranslationScheduler.kt:568-629`
- `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt:367-378`
- `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt:441-456`
- `app/src/main/java/eu/kanade/translation/TranslationScheduler.kt:603-611`

This is a confirmed semantic defect: a log message cannot turn an early return into attachment. At minimum the UI needs an explicit “owned by batch” result; if manual priority is the intended contract, the implementation must cancel/yield the batch page or await its result and complete the manual intent.

### C-02 — Reader ownership makes batch skip, not defer/rescan

The batch lane describes lease denial as “skipped this pass and rescanned later,” but returns `null`. The sequential coordinator records the null reference as a skip and proceeds. An existing test explicitly expects the reader-owned page not to be inpainted or translated during that pass.

At reconciliation, a nonterminal page becomes stranded/error. The cleanup attempt is additionally suspect because it uses a guarded batch update after the batch lease was never acquired; `BatchWriteGate` rejects writes without a matching batch identity.

Evidence:

- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt:744-763`
- `app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt:60-106`
- `app/src/main/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinator.kt:420-425`
- `app/src/test/java/eu/kanade/translation/pipeline/batch/SequentialBatchCoordinatorTest.kt:372-406`
- `app/src/main/java/eu/kanade/translation/BatchChapterTranslator.kt:613-631`
- `app/src/main/java/eu/kanade/translation/store/BatchProgressReconciler.kt:72-100`
- `app/src/main/java/eu/kanade/translation/store/BatchWriteGate.kt:85-112`

The document must not call this deferral until it defines and tests a wakeup/rescan path. Current behavior can turn ordinary reader/batch overlap into a failed or incomplete batch.

### C-03 — Same-chapter auto is intentionally rearmed during batch

The manager shuts down the current auto coordinator once before enqueuing batch work. That is not an ongoing batch admission gate. Reader page selection can call `updateAutoWindow` again, which creates a new coordinator while batch remains active.

An existing production-policy test is named `manager keeps auto window active while the chapter batch is queued` and expects the coordinator to rearm. This directly contradicts the document's statement that batch assumes full-chapter execution.

Evidence:

- `app/src/main/java/eu/kanade/translation/TranslationManager.kt:664-691`
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt:713-748`
- `app/src/main/java/eu/kanade/translation/TranslationScheduler.kt:139-207`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:1172-1186`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:1202-1257`
- `app/src/test/java/eu/kanade/translation/TranslationManagerAutoArbitrationTest.kt:74-180`

This is not an uncovered corner case; it is the tested policy. The architecture must choose one of two mutually exclusive contracts:

- suppress same-chapter auto for the lifetime of the batch, or
- support true concurrent ownership with explicit stage-level handoff and completion semantics.

It currently claims the first while implementing and testing the second.

## High-risk design flaws

### H-01 — The ownership vocabulary cannot represent the promised priority

`PageStageLeaseTable` distinguishes only `BATCH` from `READER_ADHOC`. Manual and rolling auto are the same origin. A same-origin acquisition reuses the origin's identity rather than expressing a conflict, so the lease table cannot encode manual-over-auto priority.

The scheduler hides future auto-window work when a manual page job is active, but it does not preempt an already-running auto operation. Launching a manual job does not cancel the auto page.

Evidence:

- `app/src/main/java/eu/kanade/translation/store/PageStageLeaseTable.kt:59-110`
- `app/src/main/java/eu/kanade/translation/TranslationScheduler.kt:132-157`
- `app/src/main/java/eu/kanade/translation/TranslationScheduler.kt:568-629`

Required correction: model `BATCH`, `AUTO`, and `MANUAL` separately, or explicitly declare that manual and auto are cooperative aliases and define how their shared completion is observed. The document cannot promise priority that the type system erases.

### H-02 — Duplicate/overlapping work remains possible

`inFlightPageKeys` guards the native phase only. Rolling auto splits preparation from provider/render and releases the reader lease between boundaries. Manual and auto can therefore serialize native work yet overlap downstream provider/render work, or repeat preparation after version/fingerprint invalidation.

Evidence:

- `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt:135-147`
- `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt:386-406`
- `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt:540-621`
- `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt:675-795`
- `app/src/main/java/eu/kanade/translation/rolling/RollingAutoCoordinator.kt:338-459`
- `app/src/main/java/eu/kanade/translation/rolling/RollingAutoCoordinator.kt:529-660`

The missing invariant is not merely “one native call at a time.” It is: for a `(chapter, page, source version, settings version, stage)`, which origins may compute, call a paid provider, and commit, and how many times?

### H-03 — Native admission has neither documented priority nor proven fairness

The global native lane is a plain `Mutex.withLock`. There is no priority queue at this boundary. Manual cannot jump batch/auto work already waiting, and the code does not establish the document's “strict FIFO” contract.

The asserted bound of at most two contenders is also false as an architecture bound: batch, rolling auto, and manual can all exist, including work across chapters. Provider work can continue while later native work runs.

Evidence:

- `app/src/main/java/eu/kanade/translation/pipeline/NativeRunQuarantine.kt:20-96`
- `app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt:45-73`

The native lock proves mutual exclusion, not priority, maximum queue length, bounded wait, or reader responsiveness. Those are separate properties requiring an admission controller and latency tests.

### H-04 — Shared-engine shutdown has no cross-mode lifetime owner

Batch stop/clear and normal batch completion can call `pipeline.closeEngines()`. That path clears in-flight bookkeeping and closes recognition/translation engines after obtaining the native gate. Provider implementations close their HTTP executor and connection pool. No reader-work reference count or epoch ownership contract is visible at this lifecycle boundary.

Evidence:

- `app/src/main/java/eu/kanade/translation/TranslationForegroundService.kt:63-68`
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt:627-630`
- `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt:291-315`
- `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt:415-443`
- `app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt:229-247`
- `app/src/main/java/eu/kanade/translation/engine/OpenAiCompatibleTranslator.kt:205-208`
- `app/src/main/java/eu/kanade/translation/engine/GeminiTranslator.kt:218-221`
- `app/src/main/java/eu/kanade/translation/engine/GoogleTranslator.kt:169-172`

This is a strong lifecycle hazard rather than a claimed reproduced crash. It invalidates the unconditional statement that stopping batch leaves manual/auto work uninterrupted. A deterministic integration test must pause reader work at native, provider, and commit boundaries while stopping/completing batch.

### H-05 — Component tests do not prove coexistence

The current tests isolate selected layers:

- lease tests prove that different origins cannot simultaneously hold one page lease;
- coordinator tests normalize a reader-owned page into a skipped batch input;
- manager arbitration tests use mocked/uninitialized collaborators and prove that auto rearming is permitted;
- no test runs the real manager, scheduler, pipeline, store, batch coordinator, and rolling coordinator together.

The missing oracle must assert all of the following after controlled interleavings:

- the manual request is completed or visibly rejected, never silently lost;
- every requested batch page reaches a defined terminal outcome;
- provider-call count is bounded/exactly-once where promised;
- stale generations cannot publish or overwrite a newer candidate;
- UI state matches durable state;
- shared engines remain usable until all owners release them;
- cancellation/reset/process restart cannot resurrect or strand work.

## Other contradicted or unsupported claims

| Document claim | Actual evidence / problem |
|---|---|
| Out-of-window rolling-auto work is retired on rapid fling | `RollingAutoCoordinator.evictObsolete` explicitly allows started work to finish and only drops bookkeeping for work no lane owns (`RollingAutoCoordinator.kt:697-717`). |
| Foreground service hosts batch execution | The service starts notification monitoring and polls manager state. `ChapterTranslator` owns worker execution. The service is a liveness/notification facade, uses `START_NOT_STICKY`, and restored queue work is not automatically resumed (`TranslationForegroundService.kt:33-60,91,109-147`; `TranslationManager.kt:234-264,529-553`). |
| Cleaned JPEG quality is 92 | `CleanedPublication.kt:126-130` writes JPEG quality 90. |
| Source fingerprint is first 64 KiB plus length | `PageDecode.kt:119-137` streams the complete source into SHA-256 using a 64 KiB buffer. |
| Zero bubbles sets `ocrStatus = TEXTLESS` | `PostOcrStageSemantics.kt:6-23` only acts when OCR is already `READY`; it marks downstream stages skipped. It does not set OCR to `TEXTLESS`. |
| Manual escape hatch is `ReaderViewModel.translatePage(force=true)` | The public path is `translateSinglePage(page, force)` (`ReaderViewModel.kt:2130-2170`). Default forcing is conditional, so the claimed detector-false-negative escape requires UI/caller proof. |
| Corrupt cleaned artifact sets `FAILED_RETRYABLE` | The cited batch join path writes `StageStatus.FAILED`, records an error, and aborts the candidate (`BatchRenderJoin.kt:154-170`). |
| Local-LLM coexistence halts prefetch below 15% JVM headroom | No corresponding 15% or local-LLM-specific production rule was found. The rolling coordinator delegates to a memory gate (`RollingAutoCoordinator.kt:545-560`). |
| Held bitmap registry proves total translation memory is bounded and spills to disk | The registry's 4-item/48 MiB bounds apply only to that registry (`HeldBitmapRegistry.kt:28-57`), not model weights, native scratch, provider payloads, decoded pages outside it, or total process memory. The file is already persisted before bitmap recycling; this is not a general spill system. |
| Global native serialization bounds total native memory | It bounds concurrent execution, not resident model/session memory. |
| “Zero polling delay” and sub-millisecond propagation | `StateFlow` removes an explicit polling loop but does not guarantee end-to-end delivery latency and may coalesce intermediate states. |
| Strict manual acquisition within 2.5 s; textless under 100 ms; 60 FPS; ten pages under 600 ms | No device matrix, percentile, fixture, benchmark, trace, or representative automated test was found. These are unverified aspirations, not verified SLOs. |
| Inpainting-mode change mid-batch skips OCR/AI entirely | Resume planning can reuse valid upstream artifacts, but the document does not specify settings snapshot timing or prove active mid-flight mutation. The live preference mapping is `FAST` vs `QUALITY`, not the UI/engine taxonomy used in the prose (`EngineLane.kt:208-212`). |

## Storage and document integrity defects

- The artifact layout is oversimplified. The live layout includes `X.manifest.json`, `X_artifacts`, an artifacts image subtree, and legacy companion handling (`ChapterArtifactLayout.kt:9-15,42-44`).
- Cleaned versions are generated from the live version value; the filename cannot be specified as always `001.cleaned.1.jpg`.
- Several references point to stale line ranges or nonexistent members, such as `RollingAutoCoordinator.inFlightJobs`.
- Abbreviated component names are presented as Kotlin filenames even when no such files exist.
- Absolute `file:///c:/Users/User/...` links make the purported canonical document machine-specific.
- The document labels itself canonical and “hardened” without a commit identifier, generated evidence, changelog, or acceptance record; in this checkout the document is untracked.
- Historical downloader/reader fixes dominate the roadmap while the actual cross-mode ownership failures are absent. History should be separated from the normative coexistence contract.

## Missing edge-case matrix

The replacement specification needs explicit expected outcomes—not just prose—for these interleavings:

| Scope | Required interleavings |
|---|---|
| Same page, same chapter | batch→manual at native/provider/render/commit; manual→batch at each boundary; auto→manual; manual→auto; all three; owner succeeds/fails/cancels/timeouts. |
| Different pages, same chapter | batch page N with auto/manual page N±1; rapid navigation; page ordering change; batch reconciliation while reader page remains active. |
| Different chapters | batch A + auto B + manual C; same basename/page key; provider cooldown shared vs isolated; global native queue starvation. |
| Source mutation | download completes/replaces bytes mid-stage; archive page changes; fingerprint/generation changes between prepare and commit; raw file disappears. |
| Settings mutation | source/target language, OCR, detector, provider, model, API key, inpainting mode, reading order changed at every stage boundary. |
| Cancellation | stop batch, cancel manual, retire auto window, chapter switch, reader background, service stop, app/process death, reset/delete cache. |
| Storage | corrupt/missing manifest, cleaned candidate, mask, OCR artifact; disk full; rename failure; legacy migration; simultaneous reset and commit. |
| Lifecycle | batch completes while reader provider call runs; engine close/rebuild; network retry/cooldown; activity recreation; restored queue before explicit resume. |
| UI truth | manual denied/attached/completed; batch page skipped/failed; stale cleaned image currently displayed; progress totals after reader-owned page; normal manga unaffected. |

## Required normative contract

Before implementation changes, the architecture must answer these as enforceable rules:

1. **Origin and priority:** distinguish `MANUAL`, `AUTO`, and `BATCH`; define priority separately for native, provider, render, and commit.
2. **Manual intent:** decide whether a tap cancels lower-priority work, joins an equivalent future/result, queues behind it, or returns a visible rejection. Silent completion is forbidden.
3. **Batch handback:** define how a reader-owned page returns to batch, including wakeup, retry bound, reconciliation, and the batch terminal result.
4. **Auto admission:** state whether same-chapter auto is suppressed for the full batch lifetime or supported concurrently.
5. **Work identity:** use `(chapter, page, source fingerprint, settings fingerprint, stage, generation)`; define when two origins may share results and when they must restart.
6. **Exactly-once/bounded work:** state the allowed native and provider invocation counts per identity, including retry and stale-result cases.
7. **State machine:** enumerate allowed stage/page/chapter transitions for success, textless, skipped, partial, retryable failure, permanent failure, cancellation, and stale completion.
8. **Commit authority:** specify lease/epoch validation at every durable or display-visible write, not only selected batch writes.
9. **Cancellation ownership:** enumerate what each stop/reset/background/process event cancels, preserves, or resumes.
10. **Engine lifetime:** introduce a process-scoped owner/refcount/epoch or prove that close cannot race any reader work.
11. **Settings lifetime:** identify which settings are snapshotted per request/chapter and which may change live; specify provider cooldown/key scoping.
12. **UI contract:** expose queued, joined, preempting, skipped, failed, and completed outcomes so the reader never mistakes a vanished request for success.
13. **Performance evidence:** turn absolute numbers into measured targets with device class, corpus, warm/cold state, percentile, sampling method, and regression thresholds.
14. **Normal-manga isolation:** test that chapters with translation disabled do not enter translation arbitration, storage observation, or extra decode paths.

## Questions that attack the current document

1. If manual must outrank batch, why does a denied reader lease return instead of canceling, waiting, or subscribing to the batch result?
2. What exact code wakes or replays a swallowed manual request after the batch owner succeeds, fails, or is canceled?
3. Where is the promised batch rescan after a reader-owned page returns `null` from the lane worker?
4. Why does an existing test require rolling auto to rearm while the same chapter's batch is queued if batch has full-chapter ownership?
5. How can the lease layer enforce manual-over-auto priority when both are `READER_ADHOC`?
6. What prevents two paid provider calls when auto and manual share an origin and the duplicate guard ends after native preparation?
7. How can “at most two contenders” be true with batch, rolling auto, and manual active across chapters?
8. Which primitive guarantees strict FIFO, and which code makes manual the next waiter rather than merely another coroutine behind existing waiters?
9. Who owns the shared recognition/provider engines, and what prevents batch completion or `ACTION_STOP` from closing them under reader work?
10. What is the chapter's terminal batch result when a page was skipped because reader work held its lease and batch reconciliation lacks a valid write identity?
11. What user-visible state distinguishes “manual request attached” from “manual job returned without doing anything”?
12. Which settings are immutable for a page attempt, and what happens when provider, model, key, language, or inpainting mode changes after prepare but before commit?
13. How are reset/delete and source replacement coordinated transactionally with active stage leases, artifact publication, and display candidates?
14. What evidence justifies the numeric SLOs and the word “verified”? On which Android 8+ devices, corpus, percentile, and build?
15. Which end-to-end test proves final correctness—not merely mutual exclusion—under all three modes?

## Recommended action

1. Mark the current document **Draft / Rejected by audit**, not canonical.
2. Freeze further coexistence claims until C-01 through C-03 have explicit product decisions.
3. Replace `READER_ADHOC` with a three-origin policy or define a real join protocol with completion signaling.
4. Add a deterministic concurrency harness with controllable barriers at native acquire/release, provider start/end, render, durable commit, engine close, and reconciliation.
5. Write failing tests for the critical interleavings before changing production behavior.
6. Resolve lifecycle ownership and cancellation semantics.
7. Rewrite the architecture from the executable contract; keep historical bug records and aspirational performance targets in separate documents.

Until those steps are complete, the safest description of batch/manual/auto coexistence is: **mutual exclusion exists at selected boundaries, but priority, handoff, completion, lifecycle, and final-state correctness are not coherently specified or proven.**

---

# Round 2 — Continuation Audit (2026-09-01)

## Round 2 scope

Round 2 attacked axes Round 1 did not touch: cross-mode semantic consistency (glossary/frontier/fingerprints), resource and latency coupling (store mutex vs native permit vs disk), provider quota and cost accounting, cancellation cost semantics, restart accounting, lock-hierarchy deadlock freedom, and the document's self-coherence (prose vs prose, prose vs git). Every checklist hypothesis was tested against live code; several were CONFIRMED and several were REFUTED; both are reported below.

## Executive addendum

Round 1's REJECT verdict stands and is strengthened. Round 2 adds confirmed evidence that the document's flagship semantic claim — the "Forward-Evolving Glossary Consistency Model" (§8.2.2) with fingerprint-gated reuse (§8.1.1) — cannot deliver terminology consistency across modes: the translation-stage REUSE gate is glossary-blind, so a page translated manually before the glossary matured is permanently reused and never re-translated under the established terminology. Additionally, the document's own risk register (§5) contains zero coexistence findings and its roadmap (§6) prescribes fixes that git shows are already HEAD. Single most important Round 2 finding: **H-07 — the translation fingerprint omits glossaryVersion and chunk context from its inputs, making cross-mode terminology inconsistency structural and unrecoverable by any later batch run.**

## Round 2 findings table

| ID | Severity | Classification | Finding |
|---|---:|---|---|
| H-07 | High | Confirmed defect (code/spec) | Translation REUSE fingerprint excludes glossaryVersion and chunk context; manual-then-batch chapters keep stale terminology permanently. Doc §8.1.1 omits the translation fingerprint's inputs entirely. |
| H-08 | High | Documentation defect (behavior confirmed) | Native "watchdog" timeout (90 s) keeps the lane and blocks the caller until the hung native call really exits; the doc's "≤2.5 s worst-case permit acquisition" is not a bound the code provides. |
| H-09 | High | Confirmed defect (lifecycle) | Notification ACTION_STOP closes the shared translator under in-flight reader work; SinglePageHttpRenderPhase's own comment admits the reader page fails mid-provider-call. §4.3's "manual jobs uninterrupted" is false. |
| H-10 | High | Documentation defect + strong risk | No startup "Reset RUNNING→PENDING" exists; the only sweep runs at reader open, writes CANCELLED, and skips batch-retained chapters. An in-flight provider call at process death leaves no durable attempt record — crash loops re-bill without bound. |
| M-05 | Medium | Documentation defect | §2 claims a "Bounded Channel(1)" between auto lanes; the real prepared-channel capacity is tier-based 2/4/6 and the trigger is CONFLATED. |
| M-06 | Medium | Documentation defect | §3.2.2 "Ownership Handback" presupposes auto is idle during batch; the re-arm path's guard set contains no batch-lifetime gate, so the premise is unreachable (extends C-03 with the exact guards). |
| M-07 | Medium | Documentation defect | §8.3.2 misattributes chapter-PAUSED to `AiTranslationRetryController` (envelope-scoped, stateless) and conflates artifact-manifest `FAILED_RETRYABLE` with page stage status, which has no such value; reader-path governor rejection actually writes PARTIAL. |
| M-08 | Medium | Strong risk | Batch on a partially-downloaded chapter silently processes only the downloaded subset; expectedPageCount is derived from the download dir itself, never cross-checked against the source page list. |
| M-09 | Medium | Confirmed behavior (extends M-01) | Evicted out-of-window auto work still commits durably to the store (window-un-gated `patchPage`); Collision-B shutdown conversely cancels in-flight provider calls mid-request, wasting a paid call the batch later repeats. |
| M-10 | Medium | Documentation defect / strong risk | §3.2.2 Collision C "without starvation" is false as stated: the governor's cooldown/token bucket is chapter-agnostic and defers foreground waiters beyond a 15 s budget into PAUSE, even for manual requests on other chapters. |
| M-11 | Medium | Documentation defect / strong risk | §4.1.1 "transactional" overstates: manifest publication holds the store mutex across blocking (SAF) I/O with no fsync, and after a promotion failure the durable candidate persists while only the in-memory projection is rolled back. |
| L-01 | Low | Documentation defect | ReaderActivity is `launchMode="singleTask"` only; the doc's "singleTop / singleTask" half-matches. |
| L-02 | Low | Audit erratum | Round 1 cited wrong package paths for `TranslationScheduler`, `RollingAutoCoordinator`, and `TranslationForegroundService` (see corrections below); the doc's §2 paths for `NativeRunQuarantine`/`TranslationForegroundService` were correct. |

## Detailed findings

### H-07 — Translation reuse is glossary-blind; §8.1.1 omits the translation fingerprint

`StageFingerprints.kt` defines named builders only for detection, ocr, inpaint, layout, glossaryVersion, failure, committedBundle, and pageSnapshot (`app/src/main/java/eu/kanade/translation/artifact/StageFingerprints.kt:16-192`); there is no translation builder. The real translation fingerprint is computed in `PageDecode.batchExpectedFingerprints` as `StageFingerprints.configuration(TRANSLATION, translatorSignature, fromLang, toLang)` (`app/src/main/java/eu/kanade/translation/pipeline/PageDecode.kt:164-169`), where `translatorSignature` is `EngineSignature.toString()` covering engine category, provider, key hash, base URL, model, temperature, maxTokens, reading order, and languages — and nothing else (`app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt:121-133`).

Consequences, verified:

1. The single-page path stamps the same batch-computed value onto manually translated pages (`app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt:164-184`), so manual and batch outputs are fingerprint-identical whenever the config matches.
2. Batch REUSE compares only this fingerprint (`app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt:341-347, 390-396`; strip check at `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt:1172-1173`).
3. `glossaryVersion` is persisted in the artifact manifest (`app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt:176-186`) but no stage decision ever reads it; `PageWorkPlanner` contains zero glossary references.
4. Therefore a page translated while the store glossary was empty is REUSEd forever under a matured glossary; mixed character-name renderings across a chapter cannot be repaired by any later batch. The doc presents `glossaryVersion` among per-stage fingerprint inputs (doc §8.1.1, line 217) and never defines the translation fingerprint's inputs at all — the enumeration implies a provenance gate that does not exist.

Normative consequence: the spec must either add glossary/context provenance to translation reuse or declare terminology inconsistency across modes accepted.

### H-08 — The native timeout invalidates results; it does not bound lane occupancy

`NativeRunQuarantine.run` selects between the invocation and `onTimeout(timeoutMs)` (`app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt:41-80`). On timeout it bumps the generation, but then calls `awaitExitAndLogLate`, which suspends **inside the `admission.withLock` block** until the native call actually exits (`NativeRunQuarantine.kt:71-73, 82-96`; class contract at line 19: "A timed-out native call keeps exclusive ownership until its real exit"). The configured timeout is `ONNX_PHASE_TIMEOUT_MS = 90_000L` (`app/src/main/java/eu/kanade/translation/TranslationPipeline.kt:117`, used at `TranslationPipeline.kt:380, 553` and `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt:227`).

Normative consequence: §7.1's "≤2.5 s worst-case permit acquisition" is unsupported. The code's own worst case is 90 s plus real exit time, and a hung ONNX call stalls every mode's native admission indefinitely; the timeout only prevents a late result from being used.

### H-09 — ACTION_STOP tears shared engines out from under reader work; code admits it

The notification stop action calls `manager.clearQueue()` (`app/src/main/java/eu/kanade/tachiyomi/data/translation/TranslationForegroundService.kt:65-67`), which runs `translator.clearQueue()` and `translator.stop()` (`app/src/main/java/eu/kanade/translation/TranslationManager.kt:627-631`). `stop()` with a null reason falls through to `pipeline.closeEngines()` (`app/src/main/java/eu/kanade/translation/ChapterTranslator.kt:291-315`), which closes `textTranslator`/`recognitionEngine` whenever the native lane is momentarily idle, deferring otherwise (`app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt:229-248`). The HTTP translate phase runs **outside** the native quarantine, and `SinglePageHttpRenderPhase` states: "The old translator may be closed mid-flight, causing this page to fail and retry with the new instance — an accepted trade-off without the complexity of drain logic" (`app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt:140-145`).

Normative consequence: §4.3's "leaving foreground reader manual jobs uninterrupted" is contradicted by the code's own comment. Additionally, `clearQueue()` never touches the rolling-auto coordinator, so auto keeps running against closed-then-rebuilt engines while a batch chapter is discarded — an asymmetry the spec does not describe.

### H-10 — Startup recovery is not what §4.2 describes; in-flight cost is unrecorded

The manager's startup reconcile touches only request-level state (queue/disk/pending phases) and deliberately never auto-resumes OCR/LLM (`app/src/main/java/eu/kanade/translation/TranslationManager.kt:457-527, 552-553`). There is no RUNNING→PENDING sweep at startup. The only page-state heal is the reader-open stranded sweep, which flips stale non-terminal stages to CANCELLED after `SINGLE_PAGE_TIMEOUT_MS`, skips pages from the current run generation, and skips entirely when the chapter's batch is retained (`app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:2531-2595`, guard at 2532-2534). Page attempt counters are charged only on failure paths (`app/src/main/java/eu/kanade/translation/model/PageTranslationState.kt:119-139`), so a process death during a provider call leaves no durable attempt record: on the next run the page re-runs, and a repeating crash (e.g., an OOM at the same page) re-bills the provider without any recorded bound.

Normative consequence: §4.2's "Reset RUNNING→PENDING" names a mechanism that does not exist, and the doc has no crash-loop cost accounting for paid providers.

### M-05 — "Channel(1)" is not what the code builds

The prepared-work channel capacity is `TranslationMemoryBudget.recommendedPrefetchCapacity()` — 6/4/2 by device tier (`app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt:310-311`; `app/src/main/java/eu/kanade/translation/util/TranslationMemoryBudget.kt:53-57`); the wake-up trigger is a CONFLATED channel (`RollingAutoCoordinator.kt:136`). §2's concurrency matrix row is factually wrong about the coupling mechanism it advertises.

### M-06 — The handback premise is unreachable

The only guards in the auto re-arm path are `readerStopInFlight`, `globalAutoCancellationInFlight`, and `chapterCancellationEpochs` (`app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt:158-160`). Neither `TranslationManager.openTranslationSession` (`TranslationManager.kt:1334-1353`) nor the reader's auto handler (`app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:1209-1258`) consults batch state. §3.2.2's "Auto translation remains idle [while batch runs]" (doc line 114) therefore describes a state the implementation does not maintain; the handback narrative collapses into C-03's contradiction.

### M-07 — §8.3.2 misattributes the pause mechanism and conflates two status layers

`AiTranslationRetryController.kt` (real path `app/src/main/java/eu/kanade/translation/translator/retry/AiTranslationRetryController.kt`) is an envelope-scoped retry controller with no access to chapter/Translation state (lines 33-110, 230-518); it cannot "transition the chapter to PAUSED" — that is applied downstream from `Paused` outcomes. The doc's "sets FAILED_RETRYABLE" also conflates layers: page `StageStatus` has no FAILED_RETRYABLE value (`app/src/main/java/eu/kanade/translation/model/PageTranslation.kt:232-250`); the reader-path governor rejection writes PARTIAL plus a typed Paused outcome (`app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt:340-352`), while `FAILED_RETRYABLE` exists only as artifact-manifest failure metadata written by the batch gate (`app/src/main/java/eu/kanade/translation/pipeline/batch/BatchWriteGate.kt:148-152`).

### M-08 — Partial downloads produce a silently partial batch

The batch worker fails cleanly only when the chapter directory is absent (`app/src/main/java/eu/kanade/translation/ChapterTranslator.kt:572-594`). Streams are enumerated from whatever files exist (`app/src/main/java/eu/kanade/translation/util/ChapterPages.kt:22-49`), and the manifest's expectedPageCount is derived from the enumerated streams themselves (`app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt:1245-1281, 1421-1433`), never from the source page list. A batch started on a half-downloaded chapter completes over the subset with a "success" projection. Whether batch admission can even be reached while the downloader is active is runtime evidence — Unknown.

### M-09 — Cancellation cost cuts both ways (extends M-01)

`evictObsolete` keeps lanes-owned slots and their durable results (`app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt:697-718`); `consumeTranslations` drops only not-yet-started stale work (`RollingAutoCoordinator.kt:343-345`), so an in-flight `executor.translatePreparedPage` runs to completion and its commit — a window-un-gated `store.patchPage` (`SinglePageHttpRenderPhase.kt:509-513`) — publishes a translated page durably outside the window. Conversely, Collision B's `shutdownAutoCoordinator` cancels the coordination job and its consumer child (`TranslationScheduler.kt:214-223, 966-982`; `RollingAutoCoordinator.kt:253-257, 313-315`), cancelling the in-flight provider HTTP call mid-request; the batch then re-translates the page — one wasted paid call per overlap, unaccounted.

### M-10 — Provider starvation exists; it is bounded, chapter-agnostic, and undocumented

`SharedProviderRequestGovernor` is application-lifetime and buckets by backend+model+credential with no chapter dimension (`app/src/main/java/eu/kanade/translation/translator/ProviderRequestGovernor.kt:22-41, 624-642`). Interactive priority is bounded at `interactiveMaxAgeMs = 30 s` and wired only for the reader-stream manual path (`ProviderRequestGovernor.kt:502-515`; `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt:323` — note `translateSinglePageFromStream` at 336-354 stays BACKGROUND). Selection priority does not bypass the shared token/request window, in-flight cap, or cooldown (`ProviderRequestGovernor.kt:431-438`); a waiter exceeding `maxForegroundWaitMs = 15 s` is deferred into `ProviderRequestPausedException` (`ProviderRequestGovernor.kt:461-471`). A 200-page batch draining the token window therefore pauses a manual request on a different chapter for up to a window slide. §3.2.2's "gracefully without starvation" is false as an unconditional claim; §7.2's "sets FAILED_RETRYABLE" is wrong at the page layer (see M-07).

### M-11 — The "transactional" store commit couples modes and is not power-loss durable

`updatePageGuarded`/`patchPage`/`applyStagePatch` hold the store mutex across `publishLocked` → `persistArtifactMutationLocked` → synchronous artifact publication (`app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt:426-531, 1367-1388, 1403-1588`). These are invoked while the native permit is held on the ONNX paths (`TranslationPipeline.kt:379-408`; `BatchChapterTranslator.kt:226-227`), so one chapter's slow SAF manifest flush also delays native-lane progress for the other modes. The I/O has no fsync — `UniFileChapterDocumentIo.write` is write+flush only (`app/src/main/java/eu/kanade/translation/artifact/ChapterDocumentIo.kt:106-113`); the `.tmp`→`.bak`→primary rename chain (`ChapterDocumentIo.kt:189-208`) is crash-safe for process death but not power-loss durable. And "On I/O failure, the in-memory update is rolled back" is incomplete: after a promotion failure the candidate snapshot already persisted in the manifest (`ChapterTranslationStore.kt:1555-1586`) stays durable while only the in-memory projection reverts (`ChapterTranslationStore.kt:524-527`). Lock-order survey across `pendingRequestMutationLock`, store mutex, lease-table dual locking, native admission mutex, `engineRebuildMutex`, registry monitor + opening locks, and governor mutex found **no opposite-order acquisition** (the one runBlocking, `markDefunct`'s bounded 2 s persist join, executes on IO dispatchers — `ChapterTranslationStore.kt:237-254`; `app/src/main/java/eu/kanade/translation/manager/ChapterDataResetController.kt:114-154`).

### L-01 / L-02 — Minor accuracy items

ReaderActivity declares only `android:launchMode="singleTask"` (`app/src/main/AndroidManifest.xml:139-142`). Round 1 erratum: `TranslationScheduler.kt` and `RollingAutoCoordinator.kt` live under `eu/kanade/translation/scheduling/` (not `eu/kanade/translation/` and `eu/kanade/translation/rolling/`), and `TranslationForegroundService.kt` lives under `eu/kanade/tachiyomi/data/translation/` (as the doc's §2 correctly states); Round 1's `pipeline/NativeRunQuarantine.kt` path was wrong — the doc's `scheduling/` path is correct. Findings stand; paths are corrected here.

### Doc-internal contradiction checks (A–G) — all confirmed

- **A. Confirmed.** §6's "roadmap" prescribes as future P0 work exactly what HEAD already contains: `3392234` (F-01 SAF/AbortFlow downloader fixes), `4f365df` (F-02 eager translated-stream attachment), `bd80e4d` (merge) are the three most recent commits (`git log --oneline -20`); §6 never references them.
- **B. Confirmed.** §5 (doc lines 164-173) contains no cross-mode coexistence entry; F-04 frames native contention as latency-only "INTENDED" while C-01/C-02 prove intent loss and page stranding.
- **C. Confirmed.** §9's eight items (doc lines 281-290) include no Collision A/B/C test, and §9.5's "must retire out-of-window auto jobs" contradicts `evictObsolete`'s documented keep-running-and-commit behavior (M-09).
- **D. Confirmed.** §8.1.1 (doc lines 211-218) enumerates detection/ocr/inpaint/layout and lists `translationFingerprint` only as a pageSnapshot field, never defining its inputs (H-07).
- **E. Confirmed.** Collision arbitration is ~10 lines (doc lines 110-115) in a 291-line document dominated by storage layout, historical bug records, and a stale roadmap.
- **F. Confirmed.** Core Goals 1 and 3 (doc lines 15-17) with F-04 conceding 1.2-2.5 s stalls (line 171); no combined native/provider/disk/memory/thermal budget exists anywhere in the document.
- **G. Confirmed and extended.** See H-09/H-10: ACTION_STOP's engine teardown is code-admitted to fail in-flight reader work, and ACTION_STOP does not touch rolling auto at all.

## Refuted / weakened hypotheses

1. **mmap claim (H13) — REFUTED as a defect.** `ArchiveReader` genuinely mmaps the archive: `Os.mmap(0, size, PROT_READ, MAP_PRIVATE, ...)` (`core/archive/src/main/kotlin/mihon/core/archive/ArchiveReader.kt:10-14`); entries inflate on demand over the mapping. The doc's §4.1 claim holds.
2. **"Mutex fairness undocumented" (Round 1 H-03 wording) — WEAKENED.** kotlinx-coroutines 1.10.1 (build: `gradle/kotlinx.versions.toml:13`) documents `Mutex()` as fair and `lock` as FIFO ("The mutex created is fair: lock is granted in first come, first served order", verified against the 1.10.1 `Mutex.kt` source). Fairness is real; what remains false in the doc is "manual becomes the immediate next waiter": FIFO orders already-waiting coroutines, so a manual request queues behind any already-waiting auto/batch native work, and inter-stage gaps let batch re-acquire before a newly arrived waiter. Priority and bounded wait remain unprovided.
3. **Main-thread stall on the warm-window channel (H5) — REFUTED.** The reader path dispatches to IO before touching the coordinator (`ReaderViewModel.kt:1203-1206`); `updateWindow` uses non-suspending `synchronized` sections plus `trigger.trySend` (`RollingAutoCoordinator.kt:121-122, 144-185, 301-303`); producer and consumer run on `Dispatchers.Default` (`RollingAutoCoordinator.kt:70-71`). No send/receive suspends on the main thread. The residual defect is the doc's wrong mechanism description (M-05), not a stall.
4. **"Manual/auto paths lack glossary/chunk context" (H2a) — REFUTED.** The single-page path builds glossary text and rolling context from the shared store (`SinglePageHttpRenderPhase.kt:222-252`) and folds its pairs back into the glossary (`SinglePageHttpRenderPhase.kt:324-332`); batch seeds its stats from `store.translatedPairs()`, which includes manually translated pages (`BatchChapterTranslator.kt:276-278`; `app/src/main/java/eu/kanade/translation/store/ChapterGlossaryStore.kt:32-39`).
5. **"Batch frontier excludes manually translated pages" (H2c) — PARTIALLY REFUTED.** Frontier seeding is plan-based (REUSE/TERMINAL_COMPLETE eligibility, `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchResumePlanner.kt:109-136`), and config-matching manual pages plan REUSE, so they can seed context. The real cross-mode defect is glossary-blind reuse (H-07), not frontier exclusion.
6. **"Repeated force taps hammer the provider" (H7 suspicion) — PARTIALLY REFUTED.** `force` resets only page-level counters (`PageTranslationState.kt:107-117`); `ProviderRequestGovernor.clearCooldown` has no production callers; the governor's cooldown and window still gate every tap. With an active cooldown the doc's "immediate retry" (§3.1) is still wrong — the retry is paused, which is the §3.1-vs-§8.3.2 tension resolved by code.
7. **"No native watchdog at all" (H11 suspicion) — REFUTED in the narrow sense.** A timeout exists (`NativeRunQuarantine.kt:41-80`); it simply does not bound lane occupancy (H-08).
8. **launchMode (H16) — essentially confirmed**; only the doc's stray "singleTop" alternative is inaccurate (L-01).

## New attack questions

16. Where is `glossaryVersion` enforced as a stage-input gate, given `PageWorkPlanner` never reads it and the translation fingerprint excludes it?
17. What bounds manual acquisition when a native stage hangs, given the 90 s timeout explicitly keeps the lane until the call's real exit?
18. Why does §3.2.2 Collision C claim "without starvation" when the governor's cooldown/token bucket is chapter-agnostic and foreground waiters are deferred beyond 15 s into PAUSE?
19. Which code keeps rolling auto idle for the batch lifetime, given the re-arm guards are only readerStop, globalCancel, and cancellation epochs?
20. What does §4.2's "Reset RUNNING→PENDING" refer to, when the only sweep writes CANCELLED at reader open and skips batch-retained chapters?
21. How is a partially-downloaded chapter's batch "verified", when expectedPageCount is derived from the download directory itself?
22. Why does §9 contain no test for the document's only original contribution, Collisions A/B/C?
23. Who accounts for the provider call aborted mid-request by Collision B's `shutdownAutoCoordinator`, and the duplicate call the batch then makes?
24. What makes a manifest publication "transactional" with no fsync, and why does the rollback story omit the durable candidate that survives a promotion failure?
25. Which part of §4.3 remains true when ACTION_STOP's `closeEngines` closes the shared translator under an in-flight reader HTTP call, per `SinglePageHttpRenderPhase`'s own comment?

## Round 2 recommended-action addenda

8. Add glossary/context provenance to the translation REUSE gate, or document cross-mode terminology inconsistency as an accepted limitation.
9. Replace the "≤2.5 s worst-case" SLO with the code's real bound (timeout ≠ occupancy) or add a genuine lane-occupancy bound before calling it an SLO.
10. Correct §4.3's ACTION_STOP claim and §8.3.2's mechanism attribution; define the engine epoch/refcount contract before any canonical coexistence claim (see Round 1 action 6).
11. Describe startup recovery truthfully (reader-open CANCELLED sweep; no auto-resume; batch-retained skip) and bound crash-loop provider re-billing.
12. Add Collision A/B/C interleaving tests to §9 and align §9.5's "retire" wording with `evictObsolete`'s actual semantics before retaining §9 as a matrix.
13. Fix the §2 mechanism matrix (Channel capacity, `NativeRunQuarantine` framing) and the §3.1 "Force-resets … immediate retry" wording to distinguish page-counter reset from provider-cooldown enforcement.
