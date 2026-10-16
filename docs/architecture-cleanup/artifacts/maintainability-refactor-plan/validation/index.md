---
title: "Maintainability plan: change isolation, regression gates and coordination"
kind: spec
comments: none
---

<user_quoted_section>Requirements revision — 14 October 2026: The user has since prioritized simplicity over preserving optimization machinery. This document records the previous plan/review; its source evidence remains useful, but mechanism choices, execution readiness and file-count forecasts are version-specific. Read the simplicity-first investigation before using this artifact for implementation. Native ownership, block merging, fingerprint caching and admission priority are being reassessed. No repository changes have been made.</user_quoted_section>

<user_quoted_section>Reader-policy update, October 14: The user's subsequent Step 1 reader rebuild supersedes this document wherever it requires separate manual/auto scheduling or preservation of same-page HTTP/inpaint overlap. Reader tap/viewport now use one queue and common runner; local preparation finishes before HTTP. Batch independence, store authority, quarantine and other correctness constraints remain. This is a planning change; implementation is not authorized or delivered.</user_quoted_section>

# Evidence policy

Return to the [master plan](..). All current findings are static verification; tests below are required future execution, not passed results. Record baseline and treatment revision, source/model/runtime/configuration provenance, executed tests and device conditions. No build, test, benchmark or model conversion was executed while writing this plan.

Classify each observation as structural fact, executed behavior, measured performance, hypothesis or deliberate tradeoff. A clean compile cannot demonstrate race safety, and fewer constructor parameters cannot demonstrate reduced change amplification.

# Change-isolation targets

For each exercise, define one precise edit before performing it. A list of all possible future metadata changes is not a reproducible exercise. Record production files with semantic changes, files with signature-only forwarding, affected contract tests, unrelated scheduling/UI files, temporary adapters and reason for every remaining dependency.

| Exercise | Verified baseline | Target after migration |
| --- | --- | --- |
| A1: add engine-private OCR metadata to the existing result value | Can already require zero forwarding edits | Preserve zero unrelated orchestration edits; do not claim an improvement over this baseline |
| A2: extend recognition output contract with metadata consumed by a true stage consumer | A deliberately changed callback shape can propagate through wrappers/constructors | Producer, result contract and real consumer change; no `BatchChapterTranslator`/worker constructor rewrite or scheduler-policy change merely to forward metadata |
| B: extend inpaint retry input with a fixed retry-budget value | Five production files in the explicit top-level-parameter variant, including two intermediate files with no retry semantics | Responsible capability/request, retry implementation and real caller(s) change; zero forwarding-only edits in `TranslationPipeline` and `BatchChapterTranslator`; at least those two mechanical layers removed |
| C1: extend an existing `PatchPrecondition` field or enforcement | Can already require zero top-level callback-signature changes | Preserve this property and all applicable fencing; report actual semantic caller changes honestly |
| C2: extend cleaned-publication operation input | Top-level-parameter variant reaches six production files, with two pure intermediary forwarding files | Publication request/implementation and actual decision-making callers change; no callback-chain signature ripple through intermediary constructors |

Acceptance is not an arbitrary maximum total file count. A real new requirement may legitimately require new semantic consumers. The target is **zero nonsemantic forwarding edits in the named intermediary layers**, plus no unrelated manual/Auto/batch policy edits. Publish baseline/treatment diffs for the chosen variants and explain remaining coupling.

Additional maintainability gates:

- Every mutable working page, decoded bitmap, cleaned bitmap, native session and queued persistence borrow has a documented owner and terminal disposition.
- Stage interfaces omit reader windows, batch scheduling registries and independent commit permission.
- Changes in diagnostic wording cannot change outcome severity, retry actions, accessibility labels or state eligibility.
- Engine code does not reference pipeline/workflow/scheduling implementations after the narrow dependency migration; documented exceptions are explicit and tested.
- Recognition-only tests instantiate recognition without an inpainting backend; inpaint-only configuration changes do not rebuild OCR unless a real shared resource dependency requires it.
- Removed callback wrappers are not replaced by a large parameter/service bag or duplicate stage implementation.
- No new durable state machine or removal of active `PageView`/lifecycle contracts is included without separate evidence.

# Deterministic race matrix

Use barriers/latches to control event order. Each cancellation test should assert write disposition, resource/task/admission counts, captured session lifetime and prior committed display, not merely eventual successful translation. Do not use arbitrary sleeps as proof of an ordering.

| Scenario | Required invariant / specific assertion | Existing anchor families or proposed extension |
| --- | --- | --- |
| Cancel before native admission | No native call/input allocation; operation removed without losing another caller's work | `NativeRunQuarantineTest`, pipeline concurrency fixtures |
| Cancel during OCR before recognition milestone | Registered invocation drains according to quarantine policy; late handoff does not start provider work; cleanup owner survives discarded caller result | Extend quarantine and pipeline concurrency tests |
| Cancel immediately after OCR-ready | Provider starts only while request still eligible; inpaint/task handle remains registered even if handoff pickup loses race | New operation handoff contract test |
| Cancel while inpaint/native call parked | Input/session/admission remain valid until actual exit; successful abandoned output disposed once | Quarantine, inpaint fake-native and lifetime tests |
| Manual HTTP and inpaint overlap | Park inpaint after recognition; provider enters before inpaint release; no shared mutable page/block data | `TranslationPipelineConcurrencyTest`, new stage isolation test |
| Cancel while provider is running | Preserve mode-specific paid-call drain/cancel policy; no unauthorized commit; native exit and disposal remain tracked | `AutoProviderCallDrainsNotCancelsTest` plus manual fixtures |
| Result claim races abandonment | Both event orders: exactly one owner receives/disposes output; no double recycle or output retained only in an unobserved Deferred | New owned-result contract test |
| Native exits before slow provider, then requester cancels | Finalizer retains live unclaimed output; late abandonment disposes it once; eager recycle at native exit is forbidden | New registered-finalizer/result-slot test |
| Lazy encoder paused with claimed bitmap | Bitmap remains valid during encode; release occurs after worker acknowledgment; unrelated native work is not blocked by its guard | `PreparedPageRuntimeBoundaryTest`, `NextPageAdmittedDuringParkedPublicationTest` |
| Chapter switch / manual preempts Auto | Old generation/lease loses write authority; current cancellation acknowledgment preserved | `ReaderManualPreemptsAutoLeaseTest`, `CancelSyncStoreWriteTest`, isolation fixtures |
| Engine close/rebuild while native work parked | Exact retiring references drain; new epoch not closed by old callback; OOM release cannot free active buffers | EngineLane and quarantine fixture extensions |
| Separate admissions with queued inpaint | Engine-epoch borrow/input ownership prevents close/rebuild/reclamation; recognition scratch cannot mutate retained stage-two data | New epoch-borrow/admission-gap test; required before fallback is enabled |
| Batch and reader contend | Existing priority/admission policy remains; occupancy counts include inpaint through actual exit; no nested native acquire | Coexistence and batch telemetry/terminal tests |
| Repeated request for same page | Deduplication/attach and lease rules remain; no second provider/inpaint run from observer attachment | Existing coexistence/page outcome fixtures |
| Low-memory inpaint retry | Same retry/fallback/quality semantics; old input and superseded result disposed exactly once | Inpainting engine/provider recovery tests |
| Translation-only/inpaint-only resume | Only eligible stages run; source/config/revision gates preserved; adapters do not force fresh OCR | Prepared-page/planner and batch resume fixtures |
| Sorted/duplicate/missing legacy block IDs | Styles/translations associate deterministically with the original OCR block set; invalid mappings become typed partial/rejection | New stable identity/result-join tests |
| Diagnostic wording changes | Same typed kind/owner yields identical UI label, severity, retry/actions/accessibility | `P5OutcomeProjectionTest` extensions |
| Empty text or empty erase plan | Correct terminal/no-op distinction; detector-only masks retained; no unnecessary stages | `PostOcrStageSemanticsTest`, inpaint planner/engine tests |
| Refresh existing committed output | Running/failed/stale candidate preserves old matching image/text bundle; no original-image flash introduced | `PageDisplayProjectionTest`, published-page and sidecar fixtures |
| First write races reset/close/rekey | Initialization result installs only into the still-current identity/store; waiting stale writer is rejected | New store initialization race tests; existing queue/rekey/recovery anchors |
| First write invoked during internal artifact opening | No second acquisition of the same non-reentrant mutex and no public initialization wait under opening/page locks | New opening-context/lock-order test |
| Fingerprint miss coalescing/cancellation | One computation per valid source identity; cancelled subscriber does not remove required shared work; bounds hold | New suspending fingerprint contract tests |

Test paths are under `app/src/test/java/eu/kanade/translation/` unless stated otherwise. Existing scheduling anchors include `scheduling/PreparedPageBoundaryTest.kt` and `scheduling/TranslationPipelineConcurrencyTest.kt`; the quarantine anchor is `pipeline/execution/NativeRunQuarantineTest.kt`. These were located/read, not executed for this plan. New test names are proposals, not claimed existing coverage.

# Durable publication, recovery and parity matrix

| Interruption / fixture | Required result |
| --- | --- |
| Before new image verification | No eligible new committed pointer; prior committed display remains |
| After candidate write, before manifest commit | Recover valid prior committed state or eligible candidate according to existing protocol; no mixed image/text generation |
| After accepted manifest/commit, before caller resumes | Recovery retains acknowledged durable result; cancellation cleanup does not delete its file |
| Accepted commit whose receipt continuation is cancelled | Internal publication owner retains accepted disposition; exception cleanup does not delete a referenced new image |
| Memory-first name published, lazy hash/commit cancelled | Live-pointer, file and durability dispositions are reconciled under authority; neither exception nor lost response alone authorizes deletion |
| Lazy write generation superseded | Queued stale write rejected/discarded with correct bitmap/file cleanup; newer state retained |
| Journal append, ACK, snapshot or compaction failure | Existing acknowledged-prefix/epoch/tombstone semantics retained; no live/durable split-brain |
| Process restart with partially completed batch | Resume only eligible stages; context contiguous-prefix/gap rules preserved |
| Source/config/inpaint revision changes | Invalid OCR/inpaint outputs cannot be reused or committed under current identity |
| Reset/delete/download rekey during lookup or initialization | Old document identity cannot reinstall stale state; committed retention and renamed artifacts remain consistent |
| Legacy/missing optional fields | Format remains readable; operation-local IDs do not rewrite durable block IDs/fingerprints |

Use existing `ChapterJournalRecoveryTest`, `ChapterJournalCompactionTest`, `ChapterTranslationStoreJournalCaptureTest`, `StagedJournalCreditOwnershipTest`, atomic document/sidecar publication tests, stale-manifest retry tests, `PublishedPageStoreStateTest` and `ReadOnlyPageViewTest`. The initial refactor keeps serialization contracts unchanged; validate recovery equivalence against representative corpus fixtures rather than demanding incidental timestamps or JSON field order remain identical.

Recognition and inpaint stage output parity includes coordinates, masks, detector-only regions, panel/bubble metadata, fallback/degradation facts, revision/stamps and visual output. Text parity includes context, provider protocol, block association, partial outcomes and retry behavior. Use deterministic recorded/fake provider responses for contract comparisons; avoid double paid calls or double native execution in production.

# CI and build gates

The inspected `.github/workflows/build_pull_request.yml` currently invokes:

```text
./gradlew spotlessCheck assembleStandardRelease testDevReleaseUnitTest testStandardReleaseUnitTest
python3 -m unittest discover -s scripts -p 'test_fetch_models.py'
```

Use the Windows wrapper equivalent for local authorized implementation (`.\gradlew.bat`), preserving the same task names. Model provisioning checks remain owned by existing CI; this plan does not change manifests/conversion inputs. Add package/contract tests to the standard JVM test tasks so they cannot be omitted by running only a special architecture command.

For each phase run affected tests first, then the required integration/CI set before its release checkpoint. Broaden/repeat checks when edits/failures or unresolved concerns warrant it. Keep dependency/setup failures separately reported from application regressions. Android/device tests are additional gates for native lifetime, image quality, GPU affinity and resource behavior; JVM mocks alone cannot satisfy them.

# Device performance and resource gates

Primary device: Snapdragon 8 Gen 3 / Adreno 750 when available. Include a lower-memory device, directory and archive chapters, local and SAF-backed storage, dense and textless pages, cold/warm engines, all inpainting modes/backends actually supported, manual bursts, Auto page flips and sustained batch work.

Hold APK configuration, models/weights/runtime/provider settings, source pages, diagnostics overhead and storage provider constant within a comparison. Randomize or alternate baseline/treatment order and record thermal/background conditions. Use deterministic provider fixtures for stage isolation and a separately labeled live-provider sample for user-path behavior; compare matched executions and report sample count and uncertainty.

| Metric | Required decision |
| --- | --- |
| Manual end-to-end median/P95 and provider-entry latency | Preserve observed HTTP/native overlap; investigate any unexplained regression |
| Auto prepared handoff, native wait and first translated display | Preserve durable bitmap-free boundary and useful cross-page overlap; no new head-of-line stall |
| Batch pages/minute and chapter completion | Preserve context/resume scheduling and sustained throughput; admission accounting must not starve batch |
| Native service versus queue/admission wait | Attribute changed occupancy/fairness honestly; occupancy includes actual inpaint lifetime |
| Cancellation acknowledgment and real native exit | Report separately; do not advertise task cancellation as native completion or immediate disposal |
| Peak Java/native memory, held bitmaps and allocation volume | No sustained resource growth; stage copying does not create unexplained peak increase |
| Session counts, GPU affinity and close/rebuild | No duplicate session initialization, use-after-close or premature backend disposal |
| Reader responsiveness/frame stalls | No new Main-thread blocking; IO-carrier blocking removed where migrated |
| Output/recovery parity | No quality/correctness loss hidden by an aggregate speed improvement |

Suggested investigation triggers are >5% median/throughput change, >10% P95/peak-memory increase, or any persistent growth/ordering violation. These are proposed review triggers, not measured baselines or automatic allowances: establish noise bands and final budgets in MR-00, and investigate reproducible changes outside them. A justified tradeoff requires recorded evidence and an explicit decision; there is no permission to hide a regression within the trigger.

MR-00 must record empirical baseline distributions for latency, queue/native occupancy and fairness on the reference workload/device before selecting the numeric budgets. Repeated unchanged-baseline runs establish between-run/thermal/provider variance. The suggested percentages do not pass a treatment when confidence/noise is insufficient to distinguish it; repeat or record the measurement gate as incomplete.

No-device conditions permit a reviewed code/prototype checkpoint with limitations, but block a claim of completed native/performance validation and production readiness.

# Coordination with OCR/LiteRT and loading work

| Shared surface | Coordination requirement |
| --- | --- |
| `RoiPageRecognitionEngine`, `PaddlePageOcrCoordinator`, `PageRecognitionEngine` | Agree on exact source revision and seam patch; keep recognition logic/config/backend changes owned by OCR initiative |
| `EngineLane` creation/rebuild/close and runtime availability | Jointly validate captured instance lifetime, GPU/thread affinity, backend availability and rebuild signatures |
| `PageInpaintingEngine`, neural/LiteRT coordinators and model deployment | This plan owns capability/lifetime extraction only; no weights, conversion, route or preprocessing changes |
| `TranslationPipeline`, phase adapters and test harnesses | One integration owner per shared changeset; rebase consumers after seam lands |
| `ChapterTranslationStore`, registry/open locks, reader teardown | Coordinate with loading-plan owner before initialization/opening/rekey/lifecycle edits; cache revisions/probe leases remain that plan's scope |
| Benchmarks and source fixtures | Keep baseline inputs immutable; record each branch SHA and equivalent model/runtime settings |

Before a shared-file implementation package starts, read current peer transcripts/branch state, record which symbols each workstream owns, agree a bounded patch and expected contract, and serialize its merge/rebase. No peer messaging or external coordination was performed while drafting this plan. The user has authorized this document, not unrequested messages to other orchestrators.

During implementation, a separately authorized coordination handoff should carry exact SHA, capability API/caller map, protected invariants, file ownership, relevant tests and removal/deprecation order. Do not merge competing branches independently. Revalidate facts when the shared baseline moves; source line numbers alone are not durable authority.

# Phase acceptance and completion

Each phase record includes its deliverables, executed checks, before/after interaction ledger, changed contracts, unchanged invariant evidence, unresolved failures and rollback checkpoint. Temporary adapters list owner and deletion package; a new service layer with old forwarding intact fails the maintainability gate.

The final release needs all affected regression/recovery/quality checks, enforced architecture/outcome tests, repeated change-impact exercises and matched device evidence. Conditional extractions may be rejected with a documented reason; required lifetime/coupling migrations cannot be declared complete while old unsafe or forwarding paths remain active.

Publish the final changeset walkthrough with measured results and remaining limitations. Do not replace the original subjective 5/10 with another invented score; demonstrate what changed through the targets above.
