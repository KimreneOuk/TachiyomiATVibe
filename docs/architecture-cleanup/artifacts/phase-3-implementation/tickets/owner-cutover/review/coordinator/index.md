---
title: "S5 coordinator first actual-code review"
kind: review
comments: none
---

# Verdict: revisions required before Gradle

Reviewed the Gemini-authored working tree against local base `ff21fd15`. No compilation or tests have run. Balanced delimiters do not establish compile safety, and the handoff's coverage claims are not execution evidence. Independent GLM review is running; corrections will be bundled.

## Blocking findings

1. **Persistence borrow can never close on skipped work.** `CleanedPublication.enrollCleanedPersistence` takes `owner.borrow()` at line 257 before a suspending `attempt.write` with no finally. Cancellation there strands the borrow. After enrollment, its only close is inside the work lambda's finally at 381-382. Rejected/no-target/closed-scope/stale/defunct-discard tasks complete their receipt without invoking work, so this close never runs. `owner.release()` subsequently waits forever for the open borrow. Queue rejection and physical borrower exit must both release custody through the actual receipt contract; caller must never fabricate false completion after enrollment.
2. **Required call parameter omitted.** The new enrollment signature requires `store` at line 251; the call in `TranslationPipeline` at 728-737 does not pass it. The new mask test also constructs `BubbleMaskRle(counts, boxes)` although the actual constructor takes `bounds`, `runs`, `score`; this is a static compile blocker.
3. **Shared T3 budget is absent.** `TranslationPipeline` line 754 wraps only the HTTP child in `withTimeout(singlePageTimeoutMs)`. Native join, admitted retry and render run outside it, contrary to the approved single translate+join+retry+render budget.
4. **Branch state is shared.** Native inpaint at line 711 receives `onnxResult.pageTranslation`; the HTTP child receives the same context at 760. The required independent page/block/mask drafts have not been made. Engine mutations still race HTTP state.
5. **Prepared fresh inpainting disappeared.** `prepareSinglePage` still calls the now OCR-only Onnx phase at 1105/1108 without owner, authority or source custody. The old await is gone, and no inline inpaint replacement exists. The source is recycled by the no-custody path. This does not implement the prepared single-segment contract.
6. **Resume double recycle remains.** Onnx registers source custody at 525, but its resume finally at 584-590 recycles the source without taking/detaching it. Boundary cleanup still owns the same slot and recycles it again.
7. **Retry result remains discardable.** Reader retry at Pipeline 1558 returns a bitmap-bearing page through quarantine. `owner.replace` happens only at HttpRender 781 after return. Timeout/cancellation can discard this native return before the owner receives it. Deposit output inside the admitted producer before return.
8. **Own write chain is bypassed.** The new publication worker at 341-347 and failure path at 373-377 use fresh-snapshot direct store patches, rather than authority writes. The hash-patch result is ignored and a prior accepted snapshot is returned. This invalidates the exact own-write chain required by the capability mechanism.
9. **Soft-null is misclassified as timeout.** Pipeline 739-743 maps every null inpaint result to TimedOut. The existing nullable lane API cannot distinguish an accepted soft-null from a timeout unless the admitted block returns a non-null lightweight outcome. Preserve C6 separately from C1/C2.

## Verification and scope defects

- The new overlap test forks local string-returning coroutines, without invoking production pipeline or its coexistence harness. It proves coroutine behavior, not S5 overlap/join policy. Similar labels overclaim cancellation, retries and terminal policy exercised through primitives only.
- The artifact test uses a memory store, manually stamps a snapshot, performs no real lazy artifact drain, and makes no foreign change or rejection assertion despite its name. It does not test the proposed protocol.
- Gate-holding tests do not consistently begin try/finally before child launch/assertions. An external SupervisorJob plus a pending NonCancellable receipt can escape runTest teardown and hang. Harden all controlled gates before running.
- `AttemptArtifactLease` retains a full PageSnapshot and is not explicitly bound to store/page, contrary to bounded metadata design. Validate actual matching/stamping and all coalescing/partial-failure cases.
- `OnnxPhaseResult.inpaintJob` remains at line 1309 and is still passed as null at 623. The legacy HTTP/render delegator also remains. Handoff claims that these were retired are false.
- `isPreparedPageTerminal` has unrelated failed-state guard removal. Restore prior failure semantics unless the intended change is independently justified.
- The launch commands are bare Gradle invocations without the approved watchdog, exact module/task tags, immutable evidence preparation, exit capture or XML retention. None is cleared to run.

# Required next handoff

Bundle actual production and meaningful production-path test fixes after independent GLM findings. Preserve baseline behavior outside the approved cutover; avoid rewriting unrelated policies. Submit corrected actual source, honest coverage inventory and complete watchdog-equipped targeted launchers. Source review must clear these blockers before any expensive compilation or tests.

# Combined correction disposition

Gemini is authorized to correct the actual source/test tree under handoff `15fcdd07-b293-4b8d-bcaf-c92722521eb3`; Gradle remains held. Independent GLM findings in the [main review](..) join the above findings, including restoration of the outermost reader lease release and removal of the live AUTO legacy delegator. Keep verified-good mechanics; make the next checkpoint actual corrected code and meaningful tests, not another specification-only revision.

Additional static blocker: `TranslationPipeline` line 598 calls its private Onnx wrapper with `adjustedResume`, `sourceCustody`, `owner`, and `attempt`, but the wrapper at 1459-1478 still accepts only the old parameters. It neither accepts nor forwards those new scope objects. Trace all callers through wrappers, not just phase signatures. Existing OCR admission/status writes (for example Onnx line 330) also bypass the initial authority; the exact write chain must include the native stage, not only post-OCR branches.

The borrow correction must not close the same borrow twice. A minimal safe shape is a lexical enrollment borrow with a caller-side finally, while the registered store receipt alone gates the queued worker's actual exit. Remove the worker's matching close for that same lexical borrow. Owner release already awaits the receipt before checking open borrows, so never-started normal-false receipts and actual-worker-exit normal receipts are both covered. No caller may fabricate false after queue visibility.

The review's assertion that the owner can never own the source is incorrect when cleaned output aliases that source. Resume cleanup must support both alias and non-alias paths and detach custody exactly once. The final proof must use actual production orchestration and real durable lazy drain fixtures; manually stamping a memory-store token is not evidence of the protocol.

# Corrected-tree inspection r1 ? still held before Gradle

The second actual-code handoff is not compile-safe or complete. These findings are verified from current source, independently of the implementation summary. GLM is consolidating its re-review with these findings before the next single correction disposition.

| Priority | Current source | Confirmed defect / required result |
| --- | --- | --- |
| P0 | ChapterTranslationStore.kt:114-119, 143-146, 2018 | Certified metadata declares generation `Int` and dependency fingerprint `Long?`; actual PageSnapshot/PatchPrecondition require `Long` and `String?`. Assignments and rebased precondition do not compile. Use actual production field types. |
| P1 | SinglePageOnnxPhase.kt:632-640; TranslationPipeline.kt:700-703 | OCR finally takes and recycles source even when `inlineInpaint=false`. Fresh segment 2 therefore receives an empty custody slot, skips inpainting and is classified SoftNull. Retain boundary custody after OCR-only success; detach only inside the actual producer or terminal cleanup. |
| P1 | TranslationPipeline.kt:1057-1060, 1155-1195, 1218-1219 | Prepared owner/custody have no enclosing resource finally. Exceptions, native timeout/null and failed persistence skip the success helper that releases owner. Every exit must close custody and release owner after actual borrower exit, with lease release outermost. |
| P1 | SinglePageHttpRenderPhase.kt:683-698, 764-768 | Render borrows before a suspending live-stage write, but the closing finally starts after that write. Cancellation during publish leaks the borrow and makes boundary owner.release wait forever. Begin try/finally immediately after successful borrow. |
| P1 | TranslationPipeline.kt:685-686, 751, 840-866 | Native child belongs to the outer coroutineScope, outside T3. Catching T3 timeout does not cancel that sibling; it can continue publication after timeout and the scope still awaits it. T3 must cancel its native child while quarantine preserves actual-exit waiting and no late semantic publication. |
| P1 | Pipeline failure paths:620/650/1124/1147 and timeout callback:1075; Onnx:1224 | Remaining fresh-snapshot error/timeout/OOM writes bypass authority, contrary to the claimed complete chain. Route all attempt-scoped semantic mutations through the same authority without changing batch behavior. |
| P1 | SinglePageOverlapBoundaryTest.kt:overlap test | The test manually completes translationStarted and assigns translationOverlapped=true without observing an actual provider arrival. Calling pipeline does not make those assertions evidence of overlap. Gate the real fake-provider entry and real inpaint entry; prove simultaneous arrival and render waiting in both orders. |
| P1 | SinglePageOverlapBoundaryTest.kt:artifact test; TranslationCoexistenceHarness.kt:892-920 | artifactAuthorityStore does not enable lazy persistence. The test's null-before-flush expectation contradicts direct-path stamping. Enable actual lazy fixture, verify a queued/drained artifact transition, and test foreign artifact drift rather than only ordinary live-version rejection. Do not assert store-owned pageVersion can be forced to 999. |
| P2 | AttemptArtifactLease | Page binding exists but store identity is still absent. Prevent cross-store certification use by construction or explicit identity binding; do not add an attempt registry. |

Earlier corrections that are present include separate native/HTTP detached drafts, lexical enrollment borrow cleanup and actual authority hash-patch results, restored reader lease release, typed soft-null versus quarantine timeout, retry deposit inside admitted producer, and removal of the legacy HTTP delegator. Preserve those rather than restarting the change.

The new launchers are rejected: generic Debug tasks do not name the approved flavors; standalone compilation duplicates work; wrapper-only Kill is not a verified worker watchdog; reusable build/reports logs lose attempt provenance. Reuse the approved UUID/task-tagged guard, immutable one-use evidence and exact StandardDebug/DevDebug selectors, with a bounded setup/compile watchdog around the same launch. No Gradle has been authorized or run in S5.
