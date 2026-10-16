---
title: "S5 - atomic fork/join page-owner cutover"
kind: ticket
status: 1
assignee: ""
comments: none
---

# Governing contract

[Execution](../..) and the [revised overlap plan](../../../phase-3-planning/plan) govern this ticket (the revised overlap plan is FINAL — independent Gemini recheck approved it; the plan's own §1/§16 record the approval). Follow the plan's mechanism, failure matrix and physical gates. Current user-selected roles: Gemini3.8Flash/high through Antigravity implements; GLM5.3Flash/max reviews actual diff and evidence. The [current execution policy](epics/699de408-00ac-4fe5-a903-b541c23063cc/artifacts/execution-policy/index.md) overrides older role assignments. No subagents or concurrent Gradle owner.

# Dependencies

S1-S4 accepted.

# Scope

One atomic producer/consumer/boundary slice implementing plan sections 3, 11 and 14 (plan sections FINAL and approved — this scope restates the approved mechanism, no new design choice): after OCR the reader boundary forks a structured scope — native branch: inpaint segment (own `withNativeLane`/quarantine run + `nativeTimeoutMs`) holding ONLY JNI inference + `CleanedOwner.produce` + local release; receipt registration + NC enrollment + persist-tail enqueue at BOUNDARY scope after the segment exits; external branch: `translateSinglePageHttpOnly` (SMALL EXTRACTION of SinglePageHttpRenderPhase:211-660 at the :662 await seam — provider call, classification, translation publication; NO native permit; `singlePageTimeoutMs`); bounded join of the two bounded branches; POST-JOIN `renderSinglePageTranslated` (:704-795 content + persist block minus dead await; render permit, SINGLE authoritative estimator, draw, commit via attempt.write). Remove the detached scope, `Pair`/`inpaintJob`, the await/cancel branches (662-695, 1069-1079) and the early estimator (:1204) with ALL dependent consumers together; prepare path = OCR + inline inpaint in ONE lane segment; branch-private working `PageTranslation` instances with defensive `immutableCopy()` masks; store via FIELD-SPECIFIC authority merges only; idempotent `markTimeout` with the terminal marker written POST-JOIN per the section 14 coordination table (native outcome NEVER cancels the sibling — paid-provider policy preserved; only genuine caller cancellation cancels provider work); reader-only admitted retry (segment 3) with in-block output capture and its estimator re-run retained (background changed); physical/semantic tail split with the stated queue-site moves; adapt every listed recycler including prepared boundary. Preserve batch admission/wiring, algorithms, textless/null/alias behavior and committed-display/source/user-edit fences. No new provider calls; no new DTO/framework class.

# Verification and delivery

Plan sections 13-17 and the full race matrix govern (C1-C7 coordination table included). Include abandonment (both native segments), actual borrower exit, alias/no-erase/textless, stale tails both drain/orphan, TRUE overlap via a narrowly added harness inpaint gate (CoexistenceBarrier has no native-inpaint point today — test-only seam, no invented production seam), BOTH completion orders, cancel mid-join with a paid-call test (provider-call cancellation only via genuine caller cancellation), native-timeout-with-completed-translation (store truth outranks timers), both-terminal race, two concurrent attempt writers, field-merge isolation (no stale whole-object replacement), defensive mask copies verified by test, retry reader+batch non-reentrancy, tail suppression drain+orphan, and publication fences. Complete all consumers before compilation; no always-null compatibility field for sequencing. Fresh focused verification both flavors before consolidation. Device D1-D4 remain explicit.

Preserve existing user files, baseline failure evidence and protected report. Use byte-safe edits and unique pre-created evidence paths. Record results and exact meaningful manifest under this ticket. Report completion to parent with expectReply true; stop for review before dependent work. Parent commits/pushes; do not stage or publish independently.

# Start pin and implementation gates

S1-S4 are accepted; S4 committed as `ff21fd15cfd7b5d359095ccfc26710452ecc0378`. The final overlap plan review approved this direction; stale proposed wording above has been removed. This is one atomic producer/consumer cutover, not separately published partial wiring. First provide the source-refreshed manifest and concrete interface/ownership orchestration checkpoint so current S1-S4 APIs and all consumers are accounted for. Do not redesign the approved product behavior. Before any Gradle run, coordinator inspects the actual changed cleanup/test structure and exact targeted StandardDebug/DevDebug launch plan per the [validation audit](../../validation-audit).

# Current implementation checkpoint — 2026-10-16

This checkpoint supersedes the stale implementer/model wording above. Actual worktree branch is `architecture-cleanup`, HEAD `1c7df290dd0474d14165d86ead16f98eae442c08`; directory retains historical name `inpainting-modes-redesign`. Agent config verified `codex / gpt-6-luna / max`. No source commit, staging, build, test, Spotless run, or XML exists. Coordinator owns Gradle. Shared checkout also contains coordinator edits to cleanup docs and Windows run guards; this implementation did not edit those files.

## Corrected source and test inventory

Production files changed:

- `app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt`
- `app/src/main/java/eu/kanade/translation/pipeline/AttemptAuthority.kt`
- `app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt`
- `app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt`
- `app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt`
- `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt`
- `app/src/main/java/eu/kanade/translation/pipeline/execution/PreparedPageBoundary.kt`

Test/fixture files changed:

- `app/src/test/java/eu/kanade/translation/coexistence/FakeEngines.kt`
- `app/src/test/java/eu/kanade/translation/coexistence/TranslationCoexistenceHarness.kt`
- `app/src/test/java/eu/kanade/translation/pipeline/SinglePageOverlapBoundaryTest.kt`

Store leases now bind to store identity plus page key, with corrected scalar types for generation and dependency fingerprint. The previous field-specific OCR merge body is extracted unchanged and shared by the fenced store patch and attempt-authority writes. Reader and prepared paths publish OCR/failure state through `AttemptAuthority`; native owner release remains after native exit and deferred storage drain. The reader T3 scope now bounds both branches, accepts output only from the owned native lane, and keeps HTTP translation independent. Cleaned publication success paths in retry render and resumed inpaint wait for the store receipt before rendering. Failure cleanup routes owned bitmaps through `CleanedOwner.release()`. Prepared translation uses `singlePageTimeoutMs`; fresh reader segment 2 restores the actual-entry CLEANING listener event. Both missing native timeout callbacks now have actions: segment 2 marks deferred publication orphaned and logs in the callback, then writes its semantic timeout marker after the native branch joins; retry timeout writes through attempt authority. Prepared cleanup covers acceptance, rejection, and thrown persistence outcomes.

Three real pipeline regressions are present and intentionally remain red until coordinator confirms the tree is compile-correct, proves them red, and authorizes the narrow behavior patch:

1. `masked textless reader cleans without provider call and reaches durable terminal`
2. `masked textless prepared page cleans without provider call and reaches durable terminal`
3. `unmasked textless prepared page needs no cleaned worker and reaches durable terminal`

The first two exercise detector-only masks with empty OCR blocks, actual inpaint entry, zero provider calls, and durable terminal projection. The third requires a strict no-erase prepared handoff with no fabricated worker receipt. Do not apply the pending guard changes until the coordinator's red run. Exact selector for the three tests only:

```text
--tests 'eu.kanade.translation.pipeline.SinglePageOverlapBoundaryTest.*textless*'
```

## Frozen red-first patch

Apply only after the selector above fails for the expected lifecycle causes. Reader skips only when OCR failed or both blocks and masks are empty. Existing HTTP phase already avoids provider calls when blocks are empty. Native inline inpaint admits blocks OR masks. A masked textless native soft-null must persist an inpaint failure through attempt authority and return failure. Prepared persistence is skipped only for strict no-erase terminal state: OCR READY; blocks and masks empty; inpaint, translation, and render SKIPPED; no cleaned bitmap, name, or hash. This branch still drains deferred work, uses the existing authority OCR/stage merge, flushes lazy durability, and returns a reference only if terminal state is durable and generation current with no pending lazy mutations/tasks. It creates and awaits no receipt when no worker enrolled.

Replace the reader early-return condition with:

```kotlin
if (onnxResult.pageTranslation.ocrStatus == StageStatus.FAILED) {
    return SinglePageOutcome.Failed(
        pageKey,
        onnxResult.pageTranslation.errorMessage ?: "OCR failed",
    )
}
if (onnxResult.pageTranslation.blocks.isEmpty() &&
    onnxResult.pageTranslation.inpaintMaskBoxes.isEmpty()
) {
    return SinglePageOutcome.Completed
}
```

Change the fresh inline condition in `SinglePageOnnxPhase` from `pageTranslation.blocks.isNotEmpty()` to `(pageTranslation.blocks.isNotEmpty() || pageTranslation.inpaintMaskBoxes.isNotEmpty())`, keeping its OCR-failure guard. In reader native-branch handling, after the TimedOut case and before generic failure handling, add this masked-textless soft-null arm:

```kotlin
if (nativeResult is NativeBranchOutcome.SoftNull && nativeDraft.blocks.isEmpty()) {
    val message = "Masked textless inpainting produced no cleaned image"
    attempt.write("masked textless inpaint failure") { current ->
        current.apply {
            inpaintStatus = StageStatus.FAILED
            inpaintError = message
            errorMessage = message
            updatedAt = System.currentTimeMillis()
        }
    }
    return@withTimeout TranslationCompletionOutcome.Failed(message)
}
```

In `prepareSinglePage`, compute this no-erase predicate from `onnxReadyResult.pageTranslation`:

```kotlin
val page = onnxReadyResult.pageTranslation
val noEraseTextless = page.ocrStatus == StageStatus.READY &&
    page.blocks.isEmpty() &&
    page.inpaintMaskBoxes.isEmpty() &&
    page.inpaintStatus == StageStatus.SKIPPED &&
    page.translationStatus == StageStatus.SKIPPED &&
    page.renderStatus == StageStatus.SKIPPED &&
    page.cleanedBitmap == null &&
    page.cleanedImageName == null &&
    page.cleanedImageContentHash == null
```

When true, bypass only `persistOnnxCleanedImage` and `owner.awaitPersistence()`. Always drain deferred publications and call `publishPreparedPageFromOcr` with its `attempt`; return null unless its result has `isTerminal == true`. Otherwise keep the current cleaned-worker enrollment, flush, and successful receipt wait.

In `publishPreparedPageFromOcr`, keep the existing lazy `store.flush()`, then include pending-work and generation checks in `PreparedPage.isTerminal`:

```kotlin
val snapshot = store.snapshot(pageKey)
val lazyDurable = if (store.isLazyPersistenceEnabled()) {
    store.mutex.withLock {
        store.isLazyGenerationCurrent(snapshot.generation) &&
            !store.hasPendingLazyPersistenceLocked()
    }
} else {
    true
}
```

Set `isTerminal = lazyDurable && durablePage != null && isPreparedPageTerminal(durablePage)`. Add `kotlinx.coroutines.sync.withLock`. Narrow `isPreparedPageTerminal` to `page.hasRenderedResult || (page.isTextlessTerminal && (page.inpaintMaskBoxes.isEmpty() || page.inpaintStatus == StageStatus.READY))`, so masked pages with inpaint SKIPPED cannot be terminal. These checks keep persistence rejection, stale generation, and unrelated pending lazy debt fail-closed.

Exact red selector, three tests only:

```text
--tests 'eu.kanade.translation.pipeline.SinglePageOverlapBoundaryTest.*textless*'
```


## Counts and evidence

Current overlap class: checkpoint baseline 12, current 21, delta +9; quarantined 0. Focused selectors cover 21 + 7 (`AttemptAuthorityTest`) + 12 (`CleanedOwnerTest`) + 16 (`ChapterTranslationStoreLazyPersistenceTest`) + 9 (`PreparedPageRuntimeBoundaryTest`) + 4 (`scheduling.PreparedPageBoundaryTest`) = 69 declared per flavor. Red10 selected the original three textless cases: XML 3, failures 3, errors 0, skipped 0, with genuine lifecycle failures. The new masked soft-null regression and full focused set remain unrun; no green result or device validation is claimed.

The five-part correction is implemented and frozen for one consolidated independent review. The coordinator restored nonterminal prepared handoffs for pages still needing HTTP, restricted the terminal-only filter to strict no-erase pages, and retargeted two legacy prepared-boundary fixtures through the existing OCR finalization helper. Assertions and their nine-case count remain unchanged. Focused StandardDebug then DevDebug validation will use the corrected code, unchanged watchdog, and stable validation timestamp; no redundant three-case green is planned. Ticket remains status 1.

## Destination-host validation checkpoint

This section supersedes the earlier unrun compiler inventory. The three lifecycle fixes above remain unapplied; no meaningful lifecycle red result has executed through red07.

| Attempt | Actual / guard exit | Seconds | Observed result |
| --- | --- | --- | --- |
| red02 | 1 / 1 | 718.44 | Missing language imports and cascading types |
| red03 | 1 / 1 | 521.98 | Boundary method exceeds JVM size limit |
| red04 | 1 / 1 | 226.61 | Extracted trace type wrongly qualified/imported |
| red05 | 1 / 1 | 355.97 | Boundary method still exceeds JVM size limit |
| red06 | 1 / 1 | 559.66 | Main compilation passed; recovery fixture supplied view-map type |
| red07 | 1 / 1 | 561.36 | Three tests executed; all fail JVM verification before lifecycle assertions |
| red08 | 1 / 1 | 528.27 | JVM verification passed; XML 3/3 failed before designated guards due to pending-page fixture planning |
| red09 | 1 / 1 | 591.53 | Reader reaches intended inpaint-PENDING failure; prepared OCR remains RUNNING before handoff |
| red10 | 1 / 1 | 271.89 | Genuine lifecycle red: analyze count 1 passes in all three; reader inpaint PENDING; both prepared references null |

No watchdog breach or monitor error occurred in these attempts; XML is absent through red06. Full diagnostics remain in fresh local attempt directories; [continuation](../../../continuation-2026-10-16/index.md) records guard recovery and the earlier red01 interruption.

The coordinator resolved repeated method-size failure by extracting the exact nested cleanup chain. Independent inverse reconstruction reproduced the previous source hash. Actual emitted method sizes are 8,673 bytes for the boundary and 7,652 for cleanup, below 65,535. Main Kotlin and Java compilation passed in red06. Test compilation then required the existing mutex-protected `capturePublishedPagesForCompaction()` recovery seam; no cast, new helper or assertion weakening was introduced.

Red07 Pipeline SHA256: `8084e6e6b861bf8cdd1f3b8baf721a00b28f7696216944ff693fdd99afd93e18`. Current overlap-test SHA256: `b1f2f030d48289ddbfca96b49e6af62cc4c4baa56a10032c1117884c50a244de`. The [extraction and fixture review](review/reviewer-glm-extraction/index.md) records the complete ten-file inventory and red07 clearance. Declared overlap cases remain 20; red07 selected 3, quarantined 0, XML 3, failures 3, errors 0, skipped 0. Physical gates remain open.

Red07 failed loading the pipeline class: `VerifyError: Bad local variable type` in `prepareSinglePage` at bytecode offset 4858, local 13 marked `top`. No lifecycle assertion was reached. The implementer is assigned a mechanical separation of the granted prepared body from its outer lease finally, after inspecting the generated release call. Verification flags, semantic fixes and compiler upgrades are excluded. The Test worker PID 24860 carried the exact invocation tags; daemon/client identities and test-deadline arming were also captured, closing the guard's outstanding worker-visibility check.

The independently reviewed prepared-body extraction is now the red08 candidate: Pipeline SHA256 `7b3764aa230610d5a3cf8e1ca89c0bbbf2fc177ab6deb03ce7735e4d1fb753d0`, other nine inventoried files unchanged. Inverse reconstruction reproduced the red07 source exactly. The original lease stays in the override; granted orchestration is a private suspend helper, explicitly returned through the unchanged lease finally. Red08 has passed main compilation and is compiling tests under the same three selectors and watchdog. VerifyError resolution and designated lifecycle failures remain unproven.

Red08 result supersedes its in-progress note above. XML selected 3, failures 3, errors 0, skipped 0. Reader returned `Failed(native phase timed out)`; masked and unmasked prepared snapshots remained PENDING. These outcomes do not authorize the behavior patch. Source triage identified pre-registered PENDING fixture pages rather than fresh-page planning; the existing harness warns this prevents OCR admission. Fixture-only correction and independent review are assigned. Source hash remains `7b3764aa...`; no production changes authorized by these failures.

Independent triage confirmed red08 never reached OCR: the PENDING seed makes every projected stage wait, so ONNX returns null at its resume-plan guard. Reader segment 1 directly labels that null a native timeout without a timer firing; prepared publication returns no terminal reference. No designated lifecycle red is established. Fresh empty-store fixtures use the existing `preRegisterInStore=false` seam; correction review remains pending.

Red09 used empty-store fixtures. Reader OCR READY and one analyze call passed before its intended inpaint-status failure. Prepared results still failed an overly broad setup diagnostic before analyze-count evidence; red10 moves analyze-count and prepared-reference assertions first to distinguish native setup from publication failure. This is observation-only; no lifecycle behavior patch applied. Current test SHA256 `8e06687fbf9ff343b343a330be0ee5415f2061dc986c6bd94ff925c67be6f83f`, declared 20/quarantined 0; red10 selects the same 3.

Validation efficiency: `app/build.gradle.kts` now accepts optional `validationBuildTime`, retaining the existing current-time default. Existing BUILD_TIME changed every invocation, invalidating main compilation even after fixture-only edits. Red10 passes the preceding generated timestamp `2026-10-16T06:46:17Z`; normal builds retain their original metadata behavior. Watchdog mechanics and exact test tasks are unchanged. Red10 confirms BuildConfig, main Kotlin, and main Java UP-TO-DATE; test compilation ran normally. No task was excluded.

Red10 establishes the required red baseline: XML selected 3, failures 3, errors 0, skipped 0. All three pass `analyzeCalls == 1` before their lifecycle assertions. Reader durable OCR READY passes, then inpaint remains PENDING instead of READY; both prepared references are null before terminal handoff. No compile/JVM/fixture-environment failure masks these guards. The coordinator authorized the approved five-part behavior patch as one consolidated pass, plus a genuine pipeline soft-null failure regression if absent. New case counts must be declared at freeze; independent review and focused StandardDebug/DevDebug validation remain required.

## Consolidated corrected freeze

Pipeline SHA256 `85c8549a2ca9cfbee71c5940185c4a217ab1c2b2d4be65af84799563d73458d7`; overlap-test SHA256 `39f532806fcd0aae23158197fb108a4b2a84486c73a35cbad50562956cea1a07`; prepared-boundary fixture SHA256 `8b3ad23a8352f4d81cabf4ea9c17fad7d22f27570e109301fa586ae83f2087b5`. Local immutable twelve-file inventory: `validation/s5-consolidated-source-02.json`. Independent review will record the portable full inventory. New production helpers were unnecessary; existing store, publication, authority and OCR-finalization seams were reused. No Gradle green, formatting gate, commit, push or physical gate is claimed at this freeze.

## Focused StandardDebug result and grouped corrections

Focused StandardDebug 01 executed all 69 declared cases: 63 passed, failures 6, errors 0, skipped 0. Actual/guard exit 1/1, 1076.39 seconds, no breach or monitor error. All five supporting classes except one prepared-boundary case passed; real overlap in both orders, paid-call cancellation, receipt/lazy draining, ownership and prepared cleanup cases passed. No DevDebug result is claimed.

The coordinator corrected three diagnosed causes together, preserving every assertion and the 69-case count:

- OCR field merging omitted canonical textless `translationStatus=SKIPPED`, causing four terminal-state failures. A narrow READY/empty-block/SKIPPED guard now carries that status; text-bearing OCR never replaces concurrent provider status.
- Five new `TranslationCompletionOutcome.Failed(message)` calls bound text to `anchorPageKey`, leaving the generic reason. Explicit named page/reason arguments preserve the actual failure text.
- `PageTranslation.detachedCopy()` lost private compatibility diagnostic metadata. Existing `setErrorMessageRaw` now preserves it without rerouting or clearing copied stage errors. The first-terminal-marker assertion exposed this real copy loss; it was not weakened or retargeted.

Only these three production files changed after the cleared twelve-file freeze; an exact inverse reconstruction verifies the bounded delta. The existing copy helper and setter were reused; no new API/helper/test was added. Model copy semantics are already exercised by the focused prepared-boundary and terminal-marker cases. The corrected thirteen-file inventory is `validation/s5-post-focused-fixes-01.json`; Store SHA256 `7cf3f84bb8c45135996da5f87f702ccc1ac8e120cce7eb0ab524e501fc5c03cb`, Pipeline `e56308f611fa95cfd1d74ff259b98afeb83d66a6d767df2485bca907bf92bba4`, ownership helper `e079b3e9499e12b7c1f9d5ef32aa03dc2b5d2450cb367ed45db07fc38f812bd9`. A bounded independent review of these corrections is pending. StandardDebug 02 is prepared, unlaunched; DevDebug remains held until StandardDebug is corrected. No duplicate full-tree prelaunch review is requested.

## StandardDebug second focused result

The unchanged 69-test selector executed in `s5-focused-standard-02`: 68 passed, one failed, zero errors/skips; actual/guard exit 1, no watchdog breach or monitor error, 844.91 seconds. Daemon and test-worker invocation identities were captured. The remaining unmasked-textless prepared regression reaches terminal live stages but finds artifact display state `CANDIDATE_RUNNING` instead of `TEXTLESS_COMPLETE`.

The coordinator traced this to `shouldPersistUpdate`: a completed textless page has no blocks, cleaned image, rendered result or failure, so its terminal mutation was filtered as transient after initial registration. The correction adds the existing `isTextlessTerminal` predicate alongside `hasRenderedResult` in that durability filter. The artifact bridge already promotes that predicate through its normal fenced transaction. No test, assertion, helper, receipt or fence changed. Exact inverse reconstruction matches the preceding Store hash; all other 12 frozen files remain byte-identical.

Current 13-file freeze: `validation/s5-post-focused-fixes-02.json` (host validation root). Store SHA256: `9b74f64de05fbed51ea2405d0406d3430948e16441d8bed4f1a99eb5b1972ce3`. A bounded independent review of this single condition is pending before the next focused StandardDebug run. DevDebug remains held. Declared=69, quarantined=0, latest XML=69 with one failure; no green claim.

## Current focused validation

The independent reviewer accepted the single durability-filter correction (§7 of [consolidated review](review/reviewer-s5-consolidated)). Frozen source remains `s5-post-focused-fixes-02.json`, 13 files.

| Attempt | Exact task | Declared / XML | Failures / errors / skips | Actual / guard exit | Seconds | Disposition |
| --- | --- | --- | --- | --- | --- | --- |
| s5-focused-standard-03 | `:app:testStandardDebugUnitTest` | 69 / 69 | 0 / 0 / 0 | 0 / 0 | 514.56 | Passed; no watchdog breach/error, daemon and worker identities captured |
| s5-focused-dev-01 | `:app:testDevDebugUnitTest` | 69 / 69 | 0 / 0 / 0 | 0 / 0 | 925.0 | Passed; no watchdog breach/error, daemon and worker identities captured |

Selectors remain the same six focused classes (21+7+12+16+9+4), quarantined=0. Standard03 reused unit-test compilation; no repeated green Standard gate is planned. S5 acceptance, formatting, S6 consolidation and physical gates remain outstanding.

Both focused gates used the same 13-file freeze. The tested bytes are preserved in `validation/s5-tested-source-01.zip`; independent evidence disposition is pending. Scoped formatting is running after the successful tests; its resulting bytes must not be represented as already executed. Full batch coverage will be obtained in S6 consolidation, avoiding an additional redundant batch-only run.
