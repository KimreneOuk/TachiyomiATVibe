---
kind: review
title: "S5 owner-cutover destination cold review (HEAD 1c7df290) + Windows watchdog disposition"
---

# Destination cold review — S5 owner-cutover

- Reviewer: independent GLM review agent (destination agent), pinned to **committed HEAD `1c7df290`** on branch `architecture-cleanup`; working tree excluded (source corrections by the implementation agent were in flight).
- Baseline: `ff21fd15`. 13 Kotlin files changed (9 production, 4 test).
- Constraints honored: no source edits, no Gradle runs, no agents spawned, no physical-device claims. **This is not an approval of the corrected tree**; the frozen S5 candidate was not yet available when this review was written.
- Prior review artifacts preserved; this artifact records the completed cold review and the Windows watchdog disposition only.

## P0 — compile blockers (new, absent from combined prior lists)

1. **`TranslationPipeline.kt:688-693`** — segment-2 native branch calls `withNativeLane` without the required `onTimeout` argument (no default at the pipeline-level signature, line 291). Fix must supply a real timeout action (orphaned-flag/marker side effects decided), not a compile stub.
2. **`TranslationPipeline.kt:1619-1624`** — `retryInpaintDownscaledAdmitted` same defect. The `attempt?.markTimeout` path for a null result (1633-1640) is good; the missing `onTimeout` argument still blocks compilation.
3. **`TranslationPipeline.kt:1169-1178`** — calls the private wrapper `persistOnnxCleanedImage` (`1589-1595`, 5 parameters) with `owner`/`attempt`/`deferredPublications`. Second instance of the earlier wrapper-forwarding defect class. Must retarget `cleanedPublication.persistOnnxCleanedImage` (387-395, parameters exist); otherwise the prepared path silently loses owner/receipt enrollment and attempt fencing even after the compile fix.

## P1 — correctness

4. **Owner-bypass recycle** — `SinglePageOnnxPhase.kt:846-851`: the `persistCleanedBitmap`-failure tail in resume-inpaint recycles `cleaned` directly while the owner already produced it (808-810). Sole-recycler contract break (double-dispose shape; a benign no-op on ART but a discipline violation). Disposal belongs to `owner.release()`.
5. **Mask-only gap** — `TranslationPipeline.kt:670-678` early-`Completed` return on `blocks.isEmpty()` keeps mask-only pages (`inpaintMaskBoxes` non-empty) at `inpaintStatus=PENDING` before segment 2 ever runs. Contract verification complete: `finalizePostOcrStage` (`PostOcrStageSemantics.kt:10-18`) deliberately keeps PENDING for mask-only; `isTextlessTerminal` (`PageTranslationState.kt:177-182`) correctly excludes PENDING; the batch planner treats blocks-empty as inpaint-owed (`hasCurrentInpaintMask`, 62-68). The fix disposition (finish owned cleaning before `Completed`, zero provider calls for empty blocks) is contract-consistent and required. Additional constraints: preserve the masks-empty SKIPPED textless path; note the early return currently bypasses the attempt entirely; regression-first + one smallest red run matches standing policy.
6. **Prepared-path timeout seam bypass** — `TranslationPipeline.kt:1382`: `translatePreparedPage` uses the constant `SINGLE_PAGE_TIMEOUT_MS` instead of the `singlePageTimeoutMs` test seam; the prepared-path typed timeout is not deterministically testable.
7. **Prepared-path local owner shape** — `TranslationPipeline.kt:1396-1414`: the `produce` branch is dead in the current prepared flow (`cleanedBitmap` nulled at 1344, never disk-loaded into the draft); if ever populated, the C6-retry `owner.replace` would throw "replace over an owned cleaned bitmap". Cleanup/consistency item.

Confirmed present from prior findings (not re-litigated): OCR finally recycles the source when `inlineInpaint=false`, leaving empty custody for segment 2 (`TranslationPipeline.kt:700` typed `SoftNull`); T3 does not cover the native child (async child of the outer `coroutineScope`, launched outside the `withTimeout` at 751); render-borrow strand window `SinglePageHttpRenderPhase.kt:683-698` (cancellation rethrow at 692-693 precedes the `try` at 698).

## P2 — coverage and consistency

8. **Attempt-write coverage residuals** beyond the known error/timeout/OOM list: retry SUCCESS persist (`SinglePageHttpRenderPhase.kt:793-807`, direct fresh-precondition write) and resume-inpaint SUCCESS persist (`SinglePageOnnxPhase.kt:835-845`, plus its null branch 846-851) are attempt-unfenced success-path writes.
9. **Certified absorption** exists only in `patchPage`; `checkpointOcr`/`mergeOcr`/`publishPreparedPageFromOcr` fences do not self-heal. Current S5 ordering runs those pre-enrollment, so no live defect today — consistency note.
10. **`AttemptArtifactLease`** remains pageKey-only (`ChapterTranslationStore.kt:123`; prior P2 confirmed). Mitigant: each `AttemptAuthority` binds exactly one store at construction and only that store stamps; construction-time store-identity binding remains the right fix.
11. **`CertifiedArtifactProgress`** field types `Int`/`Long?` vs actual `Long`/`String?` (`ChapterTranslationStore.kt:113-120` vs `PageSnapshot:467-480`/`PatchPrecondition:482-494`) — prior P0 confirmed; also makes the absorption `Long == Int` comparison non-compiling.
12. **CLEANING stage event regression** — the removed detached async fired the `stageListener` CLEANING event; the segment-2 native branch (`TranslationPipeline.kt:686-748`) emits the trace span only. UI stage contract check needed.

## Tests — actual seam meaning

13. Overlap test (`SinglePageOverlapBoundaryTest.kt:69-127`): still manual `translationOverlapped = true` (line 110) with no provider-arrival observation; the start/complete gates wire nothing observable. Proves nothing about overlap (prior finding, unfixed). The `runBlocking` inside the fake inpaint (83) runs on the harness `nativeRunScope` (`Dispatchers.Default`, real thread) — no deadlock, but irrelevant to the claim.
14. C1/C2, C4, C7, both-orders, and C6 tests are primitive-level choreography carrying pipeline-level names; no production pipeline/native-lane/HTTP seam is exercised.
15. Lease test (430-468): contradictory as written — with lazy persistence disabled, the direct-stamp path makes `certifiedProgress shouldBe null` (440) fail; with lazy enabled, the foreign write forces `pageVersion=999` and conflicts with store version normalization, making the reason assertion (467) fragile. Needs a real lazy fixture, a sanctioned foreign mutation, and a typed rejection assertion.

## Verified sound — preserve during corrections

Enrollment receipt-before-NC-enrollment order with lexical borrow `finally` (`CleanedPublication.kt:257-384`); Onnx wrapper forwarding; mask test constructor fixed (validates against the actual `BubbleMaskRle(width, height, bounds, runs, score)`); lazy receipt protocol exactly-once for normal true/false including cancel/defunct paths (`enqueueLazyPersistence` + `StoreWriteDrainCoordinator`); lazy stamp only on successful persist (`ChapterTranslationStore.kt:1446-1448`) and direct stamp only on actual persist (`ChapterTranslationStore.kt:3823-3825`); authority-mediated enrollment writes; `CleanedOwner` fail-closed release with no busy-wait; outermost reader lease release plus LIFO `finally` chain; detached async/`inpaintJob` removal; typed `SoftNull` lane result.

## Windows watchdog disposition (bound to this review)

- **Verdict: accepted** for focused S5 validation mechanics as demonstrated by `guard-selftest-20261016T0152` (tagged plain-java target verified twice, `jstack` exit 0, `taskkill` exit 0, differently-tagged sibling survived, guard 124 / outer 0), the honestly preserved failed first attempt `0151`, and `guard-exit-check-20261016T0200` (actual exit preserved, no breach). Identity matching (token equality + `CreationDate` revalidation before `jstack` and before kill, no `/T`), setup+test deadlines (armed at the `TRAYCER_TEST_START` marker), and immutable evidence handling all conform to standing policy. No unrelated-kill risk found.
- **R1 (required before first guarded Gradle run):** tag visibility on a real Gradle daemon/worker command line is designed (jvmargs daemon, `JAVA_OPTS` client, init-script `jvmArgs` on Test workers, in-process Kotlin) but unevidenced — the selftest target was plain java. Coordinator accepted a manual mid-run identity capture during the first authorized run. Until proven, guard 124 is not proof of JVM termination.
- **R2 (optional helper fix) implemented and rechecked: accepted.** `windows-run-guard.py:162-168` now nests `try/except (OSError, TimeoutExpired)` around the owned wrapper `kill()`+`wait(10)`, appends `monitor_error`, and `result.json` (line 180) is always written (`actual_exit` may be null); the selftest correctly fails on non-empty `monitor_error` (line 183). No broader kill introduced; success-path mechanics and prior selftest evidence remain valid, so no redundant selftest.
- **Accepted as-is:** wrapper-only `child.kill()` (unverified tree never blanket-killed; relies on R1); per-second log re-read; deadline semantics assume exactly one Test task per invocation — keep the single-exact-task launch rule.
