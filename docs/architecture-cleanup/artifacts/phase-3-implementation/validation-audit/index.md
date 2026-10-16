---
kind: review
title: "Validation audit and targeted-run safeguards"
comments: none
---

# Scope and disposition

The user requested auditing stalled/costly checks and preventing recurrence in future phases. This audit concerns validation execution, not a relaxation of production native-exit or writer-borrow safety. Implementation remains with GLM5.3Flash/max; independent actual-code/evidence review remains with Gemini through Antigravity.

# Verified findings

| Priority | Finding | Evidence and action |
| --- | --- | --- |
| High | A targeted test spun for over 23 minutes | ba7c4e23 filtered only AttemptAuthorityTest and CleanedOwnerTest. Worker 37548 thread dump showed runTest scheduler executing CleanedOwnerTest's open-borrow release test, with roughly 1394 seconds CPU in 1409 seconds elapsed. Parent preserved evidence and stopped only the verified worker. This is failed/aborted evidence, not a successful test. |
| High | Production release used busy waiting | At the inspected S1 snapshot, AttemptAuthority.kt:275 used `while (openBorrows.get() != 0) yield()` inside NonCancellable. Replace busy waiting with an actual suspendable completion signal; keep the obligation to wait for real borrower exit. |
| High | Controlled test borrows/gates were not cleaned up on assertion failure | CleanedOwnerTest held borrows/receipts, asserted, and only then released them; no finally ensured release if an assertion threw. Tests also used a fixed count of yield calls as synchronization. Require deterministic arrival/completion signals and finally cleanup, plus bounded host watchdogs. |
| Medium | Earlier command-result handoff stalled after successful compilation | Main-flavor compilation completed at 20:11:14 on 15 October 2026 in 2m24; daemon then went idle and logged a client-finished message timeout. The apparent thirty-minute command duration was not build duration. Agent was stopped/resumed; completed compilation was not rerun solely to reconstruct console output. |
| Medium | Initial new tests did not compile | e59f6172 failed in 34s on unsupported verifyExactly calls and nullable patch returns. No tests executed. Compile failure and absent fresh XML must not be reported as executed test failures or passing coverage. |

The production/test findings above describe the inspected pre-repair snapshot. GLM is correcting them; this artifact does not claim the corrected implementation has already passed.

# Standing run policy for remaining phases

1. **Target by changed behavior.** During iteration use the smallest failing method/class and one variant. At the reviewed slice boundary run the relevant focused classes in both variants. Translation-package sweeps are broader than S1 needs; full app suites belong at planned consolidation or a newly justified broad regression surface.
2. **Avoid redundant work.** Use incremental compilation. Do not clean or force all dependency tasks without evidence of stale outputs. A successful compile is not rerun just because a tool result was lost. Formatting-only or docs-only edits do not trigger repeated full suites.
3. **One Gradle owner.** Long calls run in registered monitored shells with actual Gradle exit retained, log tee/redirect, unique evidence directories and immediate fresh XML preservation. Outer shell success is not substituted for the Gradle result.
4. **Bound host validation, not production cleanup.** Host coroutine tests require deterministic gates, finally cleanup and test timeouts. Because NonCancellable code can defeat a cooperative test timeout, an outer run guard must diagnose and terminate only the verified run-owned worker/wrapper if focused test execution stalls. S1 test-execution budget is five minutes; compilation progress is tracked separately. This is a test-run operational limit, never permission to recycle a production bitmap before real exit.
5. **Diagnose rather than retry.** On failure/stall, preserve command, task boundary, log, XML if produced and thread dump where possible. Stop the failed run, fix the demonstrated cause, and rerun only affected coverage. No dev/second-flavor run while the first is unresolved.
6. **Review evidence with the code.** Reviewer checks selector scope, elapsed time, actual task exits, method identities/counts, timeout/cleanup robustness, byte integrity and remaining limits. A no-output interval or long-lived daemon alone is not proof that a build is hung; inspect active client/test-worker state and task log.

# Safeguard delivery gate

Before the next expensive test run, GLM must report the root-cause fix and concrete host watchdog mechanism. Custom scratch watchdog helpers need a cheap controlled hang demonstration; they must not kill unrelated Java/Gradle processes or alter production runtime deadlines. Gemini will verify the guard and test cleanup with S1 actual-code review before S2 proceeds. These safeguards are required; mechanical implementation and verification are pending at this audit timestamp.

Evidence: scratch/phase-3-implementation/s1-command-recovery contains the original daemon observation, stalled-run log and test-worker thread dump. Original failing runs remain preserved. No source edits were made by the parent during this audit.

# Watchdog v2 audit ? not accepted

Parent inspected the actual scratch s1-guarded-run.sh. Its same-project/creation-time worker selection did not uniquely identify this run; PowerShell command strings could match their own process query; CRLF verdict output was not normalized; thread dumping and the post-breach wait lacked bounds; identity was not revalidated after dumping; and a Bash kill fallback treated a Win32 PID as a POSIX PID. The sleep-only demo bypassed the actual selection/termination/exit-124 path. No safety acceptance follows from that demo.

Required correction before new Gradle validation: unique per-run JVM tag plus actual Java/test-worker/project/task identity, creation-time revalidation before each action, structured/normalized process-query output, bounded diagnostics and post-breach grace, native verified termination only, and a cheap actual-path self-test proving the target stops, a nonmatching sibling survives, and the breach is nonzero. A scratch helper is acceptable; no production orchestration framework is requested. GLM owns the bounded correction.

In parallel, Gemini is performing an early code-only S1 audit against the three frozen source/test files, so source defects can be found before another validation run. Final slice acceptance still requires executed focused tests and guard evidence.

# Watchdog v3.3 delivery ? accepted for focused S1 validation

Parent inspected the actual guard and init script on 15 October 2026, then read the preserved worker dump and registered self-test log `c8405d5f-c756-4dc6-b66c-6d0019bfbcbb`. The init script injects invocation-specific JVM run-id and exact task tags. Worker selection also requires Java, GradleWorkerMain and project identity; creation identity is retained at full precision and checked again after the bounded jstack capture before native taskkill. The post-breach wrapper grace is bounded and the Win32-to-POSIX PID fallback is removed.

The actual-route self-test selected Win32 PID 41280, verified its identity twice, captured the sleeping Java thread dump and terminated PID 41280. The differently tagged sibling survived until deliberate self-test cleanup. The guard reported `S1_GUARD_BREACH=1` and inner exit 124; the registered demo wrapper itself exited 0 because it was checking that expected result. This establishes the tested safeguard route, not the production ownership contract or successful S1 tests. The implementation message's reference to terminated PID 9200 does not match the final retained dump, which records PID 41280.

Approved next execution: only AttemptAuthorityTest and CleanedOwnerTest on StandardDebug, incremental compilation, 300-second test-execution cap. If it passes, run the same final pair on DevDebug; then one read-only format inventory. No broad translation-package/full-app sweep. Preserve each attempt separately, verify the init script is present at the guard's evidence-directory lookup, and retain actual Gradle exits and fresh XML. S1 remains pending dual-flavor execution and final Gemini code/evidence review. Busy-wait removal and preliminary review hardening are present on disk but not yet execution-verified.

# Focused execution delivered ? final review pending

Parent independently read the final preserved XML: StandardDebug and DevDebug each executed AttemptAuthorityTest 7/0/0/0 and CleanedOwnerTest 11/0/0/0, 18 tests per flavor. Incremental builds completed in 32 seconds and 38 seconds respectively. The read-only Spotless log's full violations block flags only the known LiteRT file; no S1 formatting violation appears. Source hashes match the S1 results delivery. No package-wide or full-app run was required for this iteration.

The final helper is v3.4: v3.3 worker selection, diagnostics and termination are retained; run-id delivery was changed to env/system/project-property channels after two init failures before test execution. Those failures and the older self-test remain preserved. The v3.3 actual-route demonstration is not represented as a fresh v3.4 demonstration.

Final Gemini actual-code/evidence review has been dispatched. In particular, all controlled pending borrows/receipts must have assertion-failure cleanup; passing runs alone do not establish this. Receipt exceptions must not be described as physical borrower-exit proof without the required store completion contract. S1 is not accepted or committed at this checkpoint; S2 remains held. Results-only command/ID/count precision corrections are pending with the implementer.

# Coordinator follow-up ? S1 promotion held for bounded safety revisions

Gemini approved the delivered S1 snapshot, but the coordinator's final source check found two unresolved items from the review brief:

- `CleanedOwnerTest.a suppressed receipt completion still releases` starts a NonCancellable release and performs assertions before completing the held receipt, without finally cleanup. A failed assertion can again strand the pending release. All controlled pending receipts/borrows need assertion-failure cleanup; prior green runs do not demonstrate that cleanup path.
- `CleanedOwner.release` catches any exception from receipt.await and then recycles. An exceptionally completed or externally cancelled receipt does not itself prove physical worker exit. The intended store protocol only completes normally with true/false after work exits or before work starts. Exceptional/cancelled completion must fail closed, retaining the resource rather than authorizing recycle; test that behavior explicitly. This does not alter normal true/false store semantics or add a production deadline.

GLM owns these bounded source/test corrections and the same focused dual-flavor validation under the accepted guard. Prior 18-test greens remain historical evidence for their exact hashes. Gemini owns the corrected review and revised-snapshot recheck. No commit or S2 start is authorized yet. S2 is writer enrollment; EngineLane timeout adaptation is S3. The registration check is a lifecycle-order guard, not an atomic concurrent enrollment protocol, and the current async writer test does not force contention on a held store operation.

# Revision r1 evidence preservation correction

Current S1 revision r1 XML independently parsed by the coordinator: each flavor has AttemptAuthorityTest 7 and CleanedOwnerTest 12 tests, zero failures/errors/skips. Revised release propagates an exceptional/cancelled receipt instead of authorizing recycle; controlled receipt tests now have finally cleanup. Final Gemini recheck remains pending.

The failed r1 attempt 1 XML was overwritten when attempt 2 copied into the same flavor XML destination. No surviving failed XML copy was found. The retained console contains the failed test name, source frames, 19-tests/1-failure summary and failed build exit. Results disclose this limitation; no run will recreate missing historical evidence.

**Mandatory launch preparation for subsequent phases/attempts:** allocate a new attempt-specific evidence destination before every launch and reject a nonempty destination. Preserve fresh XML immediately on every executed test exit, including failures, together with exact command/exit/log; never copy a retry over an earlier attempt. Clean shared build results only after the prior attempt's evidence is secured. A pre-created per-flavor directory alone is insufficient if reused by retries. This bookkeeping correction does not justify another test run by itself.

# S1 final disposition

Gemini approved revision r1 after the fail-closed receipt and test-cleanup corrections. Coordinator independently verified revised hashes and 19 green XML tests per flavor. S1 committed as `19697f8cd362e126783b95e0b48d5ff620a38605`; prior green18 results are historical. The failed-r1 XML limitation remains disclosed. S2 proceeds under the standing targeted-run/immutable-attempt-evidence policy; no full suite is needed merely to commit the already-tested snapshot.

# S2 delivery audit ? independent review pending

Coordinator matched six delivered source hashes and parsed the immutable attempt-specific XML: attempt 3 preserves the test-premise failure (15 tests/1 failure plus 9/0), final StandardDebug and DevDebug each pass 24 tests with no errors/skips. Build times are 30 seconds and 1m25 respectively. The initial coordinator/implementer LiteRT-only formatting summary was incorrect: the full log also flags CleanedPublication.kt. Its hunk display explicitly collapses 136 lines. Two distinct violating files are visible. No full/package sweep was run.

Two pre-execution failures repeated evidence-directory/init naming mistakes. Subsequent launch preparation must mechanically reject a missing/nonempty attempt destination, missing exact init filename or unavailable guard before Gradle starts; a remembered checklist is insufficient. This change alone does not justify another Gradle run.

Gemini review is explicitly checking pending-worker test finally cleanup and physical-exit proof along every production recycle path, including result delivery after narrow NonCancellable enrollment. The implementer's proposed S5 caller-false completion for cancellation between synchronous registration and NonCancellable entry conflicts with the settled no-suspension protocol and is not accepted. No speculative caller completion after possible queue visibility is allowed. Source remains frozen for review; S2 is uncommitted and S3 has not started.

# S2 review disposition ? confirmed defects and corrected claim

S2 r0 is held for bounded revisions. Confirmed defects: CleanedPublication indentation, two outer-finally bitmap recyclers without receipt joins, and pending-worker test gates lacking finally cleanup. Inner happy-path joins do not protect exception/cancellation unwinding. Alias references must not cause the same bitmap to be recycled twice.

The coordinator's earlier formatting summary missed the second violating file. Future inventory extraction must traverse the entire violation block and list every distinct path; collapsed hunk content is disclosed rather than called untruncated. Source/test hardening must be checked before accepting green runs.

The review's separate claim that a plain withContext(NonCancellable) necessarily throws cancellation on exit is rejected: it does not change dispatcher and has no prompt-cancellation return handoff. This is explicit in [coroutines 1.10.1 source](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.1/kotlinx-coroutines-core/common/src/Builders.common.kt), documentation lines 128-130 and same-dispatcher fast path. The actual lazy publication call chain has no intervening dispatcher wrapper; receipt-result construction/assignment is non-suspending after enrollment. No broad NonCancellable publication wrapper is justified by that false mechanism. Any different real post-enrollment loss path must be demonstrated at its actual source boundary.

GLM owns bounded r1 fixes and focused dual-flavor revalidation. Gemini is correcting review precision and will recheck revised source/evidence. No S3, commit, or broad test sweep is authorized.

# S2 final disposition

Revision r1 approved by Gemini and committed as `c0e3ded43ee52c8bb733c2113284a21dead0e330`. Coordinator verified final six-file hashes, failed-r1 XML retention, 25 green tests per flavor (28s/33s builds), and read the entire final Spotless log: one LiteRT file/two baseline hunks, no collapsed hunk marker. The earlier r0 LiteRT-only summary was wrong and remains explicitly corrected above. Mechanical preflight is now present before Gradle and evidence destinations are attempt-specific. S3 remains narrowly scoped to the timeout callback chain; full-suite validation is deferred to justified consolidation.

# S3 pre-review audit ? variant mismatch, incomplete cleanup, redundant runs

S3 is held. Coordinator verified EngineLane and new-test hashes and read actual final logs/XML. `final-2-std` executes StandardDebug; `final-2-dev` actually executes StandardRelease plus its count-parity finalizer. Each runs six passing tests in 24 seconds. This is not DevDebug flavor evidence; directory names do not establish task identity. Results must label executed tasks truthfully, and the required DevDebug gate remains outstanding.

All four new adapter tests perform scheduler advancement/assertions while the native-exit gate is held, before entering their try/finally region. Thus the claimed finally cleanup does not cover early assertion failures. Cleanup must enclose the entire held-gate lifetime from before the first launch.

The ledger also repeats successful StandardDebug without an intervening semantic change and repeats both variants after only lambda formatting. Those repeats conflict with the standing cost policy. They remain historical evidence; no recreation or compensating broad run is justified. An iteration green counts as the final gate when its semantic code and selectors remain unchanged. Formatting-only changes require formatting verification, not repeated dual-flavor tests without another specific concern.

**Tightened control after repeated violations:** before further S3 validation and in subsequent slices, the implementer provides the current test cleanup structure and exact task/selector launch plan for coordinator inspection before launching costly checks. Task name, guard task tag, result source directory and evidence label must match the intended variant. Mechanical file preflight alone cannot detect the wrong variant or assertions outside cleanup. Independent S3 review is collecting remaining findings before any revised run, so bounded fixes can be combined.

# S3 r1 pre-launch inspection passed

Coordinator read the revised adapter test at SHA256 DEA42CFF3657E043FC102A8E72ED6C09BA386E795B1379550500B1A92C08C197. Each of the four try/finally regions now encloses the first child launch and every scheduler advance/assertion while the exit gate is held. Finally opens that gate before cancelling still-active children; runTest owns their teardown. EngineLane production hash remains the approved r0 snapshot.

The submitted StandardDebug and DevDebug launchers match exact Gradle task, guard task tag, build XML source and evidence label. Authorized one targeted StandardDebug run then, if green, one targeted DevDebug run, six tests per intended selector set (actual XML authoritative), plus formatting verification. No repeat after formatting-only edits.

Added one-use attempt claim before running or overwriting initializer/log output: atomic creation of an attempt-local `.launch-claim` must fail if already present; existing run logs/dumps also reject reuse. Checking only an empty XML subdirectory could otherwise reuse a failed-compilation attempt that had no XML. Evidence directories were absent at parent inspection; launch preparation creates them before Gradle rather than claiming they already existed. No tests were run by the coordinator.

# S3 r1 targeted execution delivered

Coordinator independently verified the corrected tasks and XML: r1-std executes testStandardDebugUnitTest in 29 seconds; r1-devdebug executes testDevDebugUnitTest in 31 seconds. Each runs four adapter tests and two unchanged quarantine tests, all passing with no errors/skips. Both attempt directories have their one-use launch claim. Source hashes match the inspected pre-launch snapshot. The single final format log lists only the two known hunks in LiteRTMangaInpaintingEngine.kt, with no collapsed hunk marker and no S3 violation. No duplicate semantic run or format-only test repeat occurred in r1.

Final independent Gemini recheck is pending; S3 is not yet committed and S4 has not started. R0 wrong-variant and gratuitous runs remain disclosed historical evidence rather than being relabeled as accepted DevDebug coverage.

# S3 accepted, S4 pre-launch gate retained

Final Gemini review approved r1; coordinator committed the two reviewed files as `999bd6a3932a9163f65f3656e426e729584ff0af`. No repeated green run was used for r1. S4 proceeds with the pre-launch source/test cleanup and exact task/selector audit retained, rather than relying on manual labeling or post-run catch-up. Phase publication still follows completed-phase delivery; no device evidence is implied.

# S4 pre-launch gate rejected ? defects caught without Gradle

Coordinator inspected the exact submitted publisher test hash 7D656966BA805420F29295B521248EF9778E00D57106A76594D3732D73D7ED84 before validation. It contains ten test declarations, two sharing the same method name, and a reference to nonexistent `store.pagesSnapshotForTest`. Thus the checkpoint's nine-test/API-clean claim is inaccurate; these compile blockers must be removed before an actual run.

The draft also omits the independent never-accepted-live-name condition: both production callbacks only ask the store whether the name is currently unreferenced. A lazy publication already accepted that name before its hash patch; a subsequent reset can clear current references without establishing that it was never accepted. The required policy is independent provenance/commit knowledge AND store authorization, with unknown/previously accepted names retained. Mutex authorization is point-in-time; it does not prevent mutations after unlock, so a blanket no-race claim is incorrect. GLM is making bounded fixes and Gemini is performing a pre-validation cold read to combine findings before any expensive run.

The submitted launchers also copied per-attempt init files before taking the one-use claim. The claim must precede those writes as specified, rather than merely existing before Gradle. No S4 Gradle run is authorized at this checkpoint.

# S4 revised source pre-launch follow-up

Revised draft removes duplicate/nonexistent test code (11 unique tests) and correctly distinguishes the fresh non-lazy rejection path from the lazy path that already accepted a live name. Lazy rejected-name deletion now fails closed independently of point-in-time store authorization; comments no longer claim the mutex prevents post-unlock interleaving.

Validation remains held for two small corrections: the prior-live-then-cleared test currently accepts gone.jpg but later publishes new.jpg, so it does not exercise history for the same rejected file name; use the same name for acceptance, clearing, authorization and retained publication. Also the approved ERROR ledger for retained unbounded orphan debt must be applied to the unauthorized-rejection retention log. Source proves deletion is outside mutex.withLock; a before-call event alone must not be claimed as a lock-release assertion. No Gradle is needed to catch or correct these draft issues.

# S4 final pre-launch inspection passed

Coordinator verified the revised four-file snapshot, including publisher SHA256 5E164DE8E50D25011A86E19AE996954BB44EAA39639BBAA6290C671ED5163A76 and test SHA256 4B32AD89648DAA4F10400B3D53E46134C600EFFBFA35F457CD39C7AE28E4A0A6. The historical-live/cleared/rejected scenario now uses new.jpg consistently; the rejected-retained debt ledger uses ERROR. There are 11 distinct publisher tests and no duplicate or imaginary accessor. This is source inspection, not test execution evidence.

Authorized the minimum guarded StandardDebug then DevDebug targeted pair (publisher tests plus existing lazy-persistence regressions), followed by one format inventory. Exact task, guard tag, XML source and evidence label match; one-use claim precedes per-attempt copies. A successful iteration counts as final, formatting-only changes do not trigger test repeats, and actual XML counts remain authoritative. S4 promotion awaits executed evidence and independent final review; no S5 start is authorized.

# S4 minimum execution delivered

Coordinator independently verified all four post-run hashes against the pre-launch snapshot and parsed actual task lines/XML. StandardDebug completed in 52 seconds and DevDebug in 46 seconds; each executes 11 publisher plus 16 lazy-persistence tests, all 27 passing without errors/skips. Each immutable attempt has its one-use claim. Both first authorized runs were final, with no duplicate tests. The single complete format inventory lists only the two known hunks in LiteRT; no collapsed marker or S4 violation.

Final Gemini actual-code/evidence review is pending. S4 remains uncommitted and S5 has not started. Safety conclusions remain bounded to source review and executed host tests; physical I/O/native/device proof is not implied.

# S4 accepted and S5 controlled start

Final Gemini approval accepted; the four reviewed S4 files are committed as `ff21fd15cfd7b5d359095ccfc26710452ecc0378`. No new test run was used for the commit. The S5 source-refreshed interface/ownership checkpoint precedes its atomic cutover; actual code/test cleanup and exact variant commands must be inspected before Gradle. This preserves the pre-launch controls that caught S4 compile/policy defects without test cost. Device gates and consolidated verification are still pending.

# S5 source/interface checkpoint ? bounded refresh required before edits

At ff21fd15 the coordinator checked the proposed cutover against actual sources. The reader retry lambda currently calls the unadmitted shared delegate; reader admission is a new S5 change, while the batch already-admitted wiring must stay non-reentrant. Splitting the HTTP/render monolith requires explicit placement of shared locals and translator begin/end borrow, governor, retry-budget, epoch and stream/reclaim cleanup, rather than assuming closures cross function boundaries.

Source bitmap S already exists in DecodedPage.bitmap. OCR-only return must preserve S for segment 2 while abandoned segment-1 returns/failures still dispose it after real exit. Inline resume also returns a cleaned bitmap across the quarantine boundary and blindly recycles its input in the old finally; retaining the inline algorithm does not waive owned-result abandonment or alias transfer obligations. Resume physical tail mechanics may stay, but semantic writes must honor attempt authority at execution, including orphan-inline tails.

The source-refreshed manifest must include all changed owners such as PageTranslationView copy visibility, PreparedPageBoundary and CleanedPublication. GLM is recording concrete resolutions in the S5 preparation artifact and Gemini is independently checking source/interface hazards. No production edits or Gradle have started; the approved memory-first overlap direction is unchanged.
