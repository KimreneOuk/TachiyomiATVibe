---
title: "Bounded runtime discrimination plan for four ProbeTest failures"
kind: spec
comments: none
---

# Purpose and authorization

This plan follows the [investigation ticket](..), the [accepted corrected store contract audit](../../phase-0-baseline/store-contract-audit), the [Phase 0 review prerequisite ledger](../../phase-0-baseline/review-response), and the [P2.6 validation results](../../phase-2-contracts/tickets/validation/results). Those artifacts provide historical context, not runtime causes.

The original ChapterTranslationStoreArtifactOpenProbeTest class was rerun without source changes at HEAD 999bb9be6563f957ee508f739ba2f042bf1c90ee. This plan requests parent acceptance before diagnostic test instrumentation. No production edits, fixture edits, or commits have been made for this investigation.

# Original narrow baseline

Pre-created evidence directory:

C:/Users/ADMIN/.traycer/epics/699de408-00ac-4fe5-a903-b541c23063cc/scratch/store-baseline-investigation/2026-10-15-999bb9be/baseline

| Flavor | Literal command | Shell / exit | Preserved XML and log |
| --- | --- | --- | --- |
| DevDebug | ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" --rerun --console=plain --stacktrace | 4780e208-f9e5-474c-acfe-b629c56cc7bf / 1 | baseline/dev/xml/TEST-eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.xml (68,361 bytes; SHA-256 EB0F1E8104D4036DF70B8B57D0FC0A040B0454863812A5918E991EE5AA9C9A75); baseline/dev/log/output.log (49,696 bytes; SHA-256 E335B377BAA74B712C78F2FFCA4B734900B868FD8BB72F63306DA655B158215F) |
| StandardDebug | ./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" --rerun --console=plain --stacktrace | 7d84d781-7b3f-4a51-82ab-d68ee1ed7843 / 1 | baseline/standard/xml/TEST-eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.xml (68,366 bytes; SHA-256 618C1ED55FDC3DACE56962C016F85E2FCC5AA2AB519FE45CE9FF2E9BE32C9994); baseline/standard/log/output.log (49,865 bytes; SHA-256 4D8B8066125E76955791CC374432649D979CFB677B08312A299E2D5E6676E864) |

Shell registry records corroborate each command, repository cwd, Git Bash interpreter, creator, exit code, and completion. Both XMLs report 14 tests, 4 failures, 0 errors, 0 skipped (10 passing).

## Observed failures

| Method | Primary failure | TempDir cleanup evidence in that testcase XML |
| --- | --- | --- |
| lazy store cuts over before first candidate and reopens from artifact pointers() | java.lang.AssertionError: Expected "page.cleaned.retry.jpg" but actual was null | Suppressed JUnitException/IOException; Chapter 2.manifest.json undeletable; DirectoryNotEmptyException; FileSystemException says the manifest is being used by another process. |
| pre-registered pages publish one durable baseline with only the changed candidate() | io.kotest.assertions.AssertionFailedError: expected:<["p1.jpg", "p2.jpg", "p3.jpg"]> but was:<[]> | Same suppressed cleanup shape for Chapter 2.manifest.json. |
| artifact-only lazy store writes pages without materializing flat compatibility file() | org.junit.platform.commons.JUnitException: Failed to close extension context | Primary cleanup exception names Chapter 2.manifest.json, with DirectoryNotEmptyException and FileSystemException reporting a file in use. |
| lazy store without parent resolves artifact authority through artifactParentResolver() | org.junit.platform.commons.JUnitException: Failed to close extension context | Same primary cleanup shape for Chapter 8.manifest.json. |

A FileSystemException appears for all four testcases, including the two assertion failures. JUnit temp paths vary. The message does not identify the process or handle owner.

Method names, primary types/messages, and cleanup cause shape match Phase 0 narrow XMLs, Phase 1 full-run XMLs, and P2.6 full-flavor XMLs. This confirms repeated observations, not a shared cause. Those prior XML/log ledgers remain untouched.

# Current source facts

| Fact | Source and effect |
| --- | --- |
| Pre-registration stores placeholder keys in memory and publishes count/partial metadata; it does not publish page entries in that write. | ChapterTranslationStore.kt:3472-3589. Later mutation publication can register pending keys. |
| Compatibility updatePage returns Unit. It can reject stale generation or active lease before calling updatePageGuarded, then discards the guarded result. | ChapterTranslationStore.kt:3015-3044. The guarded API is not an equivalent diagnostic substitute. |
| Lazy publication queues when an update is durable or its page is absent from the manifest, updates live state/display, then schedules drain. | ChapterTranslationStore.kt:3712-3751. |
| Flush drains pending mutations; rejected persistence is requeued. A private diagnostic records rejection; registration rejection also logs a warning. A null artifactManifest can return without publishing a record. | ChapterTranslationStore.kt:1349-1387, 3775-3788, 3801-3806, 3824-3893. |
| READY promotion, durable manifest publication, and later reopened display are distinct observations. | ChapterTranslationStore.kt READY branch; openArtifact reconstructs from manifest and acknowledged journal state. The failing fixture does not inspect session-two manifest before reopening. |
| Cleanup fixtures omit privateStorageRoot; its lazy default is null, and ensureJournalWriter returns null when root is null. These fixtures do not open a journal segment FileChannel. | ChapterTranslationStore.kt:245, 737-760, 5045-5059; ChapterJournalWriter.kt:296-328. |
| Manifest streams are per-write and closed. closeAndFlush joins write-drain work and cancels/joins artifact retention work. The existing test seam observes drain children only. | ChapterDocumentIo.kt:208-210; ChapterTranslationStore.kt:4715-4728; ChapterArtifactEngine.kt:94-98, 2283. |
| Original #3 checks page keys after preregistration plus RUNNING update/flush. #4/#5 leave their stores open. | ProbeTest.kt:237-257, 301-316, 514-566, 569-593. |

# Hypotheses still open

| Failure | Alternatives; none is asserted as cause |
| --- | --- |
| Pre-registration (#3) | Wrapper admission/live update; lazy queue admission or drain timing; unavailable artifact engine/manifest; publication rejection; or a transient RUNNING versus durable-content difference. The missing pages do not identify the branch. |
| Reopened promotion (#1) | Wrapper admission; queued publication rejection or early no-op; successful session-two commit followed by a reopen projection issue. Final display alone cannot distinguish these. |
| Cleanup (#4/#5, plus suppressed cleanup on #1/#3) | Store drain/retention work or another process/handle may block deletion. Exception text does not identify a holder. No journal segment channel is expected from these fixtures. |

# Bounded experiment matrix proposed for acceptance

Instrumentation will remain in the existing ProbeTest file plus a scratch-only, read-only Windows Restart Manager helper. It will preserve the actual Unit-returning updatePage wrapper and leave store policy, assertions, order, leases, generations, and persistence fences unchanged. No guarded updater will replace the wrapper path.

| Experiment | Control A | Control B | Checkpoints and interpretation |
| --- | --- | --- | --- |
| Registration timing (#3) | Reproduce original preRegisterPages(p1,p2,p3), RUNNING-only updatePage("p1.jpg"), flush sequence with snapshots. | Separate identical fixture pre-registers the same keys, then uses the same wrapper with a non-empty-blocks durable mutation. This contrasts transient-only with durable candidate behavior without changing API. | After preregistration, wrapper return, and before/after explicit flush, record disk and in-memory manifest count/page keys, live page status, pending registration keys, pending lazy page/task keys, and private publication rejection diagnostic. Changed live state establishes wrapper acceptance. A rejection diagnostic or requeued mutation identifies publication/drain failure; absent engine/manifest with no rejection points to initialization/availability. Compare both final manifests and candidate pointers. If auto-drain consumed a queue before snapshot, post-flush manifest and diagnostic are authoritative; an empty queue sample alone does not prove it was never queued. |
| Session-two promotion and reopen (#1) | Preserve the original session-two READY mutation through compatibility updatePage with snapshots. | Compare the known-good session-one candidate and the session-two post-close manifest with final reopened state/display; do not invoke guarded update as a parallel path. | Record live state after wrapper return, queue/rejection before and after flush, manifest candidate/committed pointers after flush and closeAndFlush but before reopen, then reopened state/display and manifest. Unchanged live state points to admission; changed live state plus rejection/queued mutation and missing committed pointer points to publication; committed pointer before reopen but absent reopened display isolates reopen projection. Close the final diagnostic reopened store in finally. |
| Cleanup/resource ownership (#4/#5) | Existing unclosed methods remain the immutable baseline; diagnostic reruns take snapshots before each body returns. | Add one diagnostic twin per fixture shape with identical operations and closeAndFlush in finally before JUnit TempDir cleanup. | Before close, record pending drain keys/tasks, hasActivePersistenceChildrenForTests(), active children of artifactEngine.retentionScopeJob via read-only reflection, and whether journalWriter is null. Re-sample jobs after close. Query Windows Restart Manager once against the manifest while the store is open; repeat after close only if the first result names a PID. A process/job disappearing with close and successful cleanup supports a lifecycle connection; only a positive process/job observation attributes an owner. Empty/error Restart Manager output is inconclusive. No handle.exe/Process Explorer command is installed; rstrtmgr.dll is present. |

The Restart Manager helper is scratch-only and read-only. It reports API status and returned PIDs/process names; it does not terminate, restart, or signal processes. Limit it to one pre-close query and, only if it identifies a process, one post-close query. If it cannot identify an owner, retain that limitation rather than infer no handle exists.

## Diagnostic paths and output

- Planned tracked diagnostic edit: app/src/test/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt only.
- No production file is planned. Reflection is limited to the existing write-drain maps, lastArtifactPublicationRejectionDiagnostic, journal writer, and retention job. If reflection cannot expose a needed value, stop and return an amended plan before adding any production seam.
- Scratch helper and per-run observations: scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/.
- Pre-create per-flavor unique XML/log/output directories. Run one Gradle command at a time; immediately preserve each XML and registered shell log, including failures, before the other flavor.
- Write diagnostic values to a per-run file selected by an inherited environment variable. Capture phase, manifest/live state, queues/jobs, rejection text, process-query status, and timestamps. Record the environment assignment, literal command, shell ID, and exit in the results artifact.

# Resource closure and restoration

Every new diagnostic twin closes every opened store in finally, including the final reopened store in #1. Existing failing methods remain unweakened. No sleeps are used for synchronization; no assertion is removed or relaxed.

Before editing, record a byte backup and source hash. ProbeTest currently hashes to 33E01BB37B12019D67E990581EFB27E614364259F579A6510D9DC89185B9E541. ChapterTranslationStore.kt currently hashes to 70C67D83FEB15BCD8F08F52597A6F75786C717DD470040F0B81162D4D40BEF59. Only the test file is planned for edits. After observation, restore it byte-for-byte from HEAD 999bb9be or the saved bytes and verify the hash. Confirm no production diff or staged change remains. Keep baseline XML/logs and the findings artifact.

The untracked architecture report remains unchanged at SHA-256 EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD. Preserve the eleven existing empty-content-diff status-M paths. No commit or push.

# Decision requested

Accept or revise this bounded matrix before diagnostic test edits or further Gradle invocations. The original narrow baseline is complete and preserved; no full app suite is planned.

# Accepted execution constraints after independent critique

Parent authorizes the bounded diagnostic matrix following [Gemini critique](review), with these controlling qualifications where review wording overreaches:

- Changed live state proves in-memory application only. Separately observe pending registration, lazy mutation/task queue snapshots and manifest pointers; neither queue membership nor live display is durable commitment.
- Copy mutable collections under the EXISTING actual store mutex, held briefly for value copies only; release it before diagnostic disk I/O, Restart Manager calls, logging, flush, close or any method that might reacquire the store mutex. Merely returning from flush is not universal proof that no later asynchronous mutation can run. No second mutex, sleeps or new serialization policy. Report observational timing perturbation rather than claiming zero perturbation.
- Restart Manager reports process/resource association. A JVM PID does not prove a particular stream/job owns the file and does not exclude another simultaneous holder; a passing closure variant alone does not prove which holder or mechanism changed. A process-query status/error/empty result remains bounded evidence. Never call Restart Manager shutdown/restart APIs or signal reported processes. Bound scratch-helper execution and use no polling loop.
- The original final reopened store and some cleanup stores are visibly unclosed, but that omission is not yet proof they cause any manifest lock. Diagnostic twins independently close every owned store in finally and observe the session-two manifest before final reopen.
- Preserve original tests/assertions, baseline ledgers, each diagnostic run and the exact temporary test patch/source in scratch before restoring original bytes. No production source change or lasting repair. A diagnostic failure is evidence to investigate, not a reason to retry unchanged until green.

Proceed with diagnostic instrumentation and focused both-flavor runs. If the prescribed reflection or read-only helper cannot be implemented within these bounds, report that limitation and continue the other independent experiments; do not silently add a production seam or install tooling. Return per-failure observation chains, contract conclusions versus remaining hypotheses, minimal repair recommendations and restoration proofs for final review.

# Diagnostic measurement correction and helper-only control

The v1 sampler reused the original readManifest helper, which opens a FileInputStream for Json.decodeFromStream without a scoped close. This can interfere with subsequent Windows manifest replacement as well as deletion. All observations downstream of that first probe read must therefore be marked potentially contaminated, not treated as original publication/holder causality.

Parent authorizes a bounded v2 with only the sampler's stream scoped via use, preserving v1 patch/source/XML/raw observations. After v2, perform one separate helper-only intervention from original HEAD ProbeTest: change only shared readManifest to close its stream with use; keep all original fourteen test bodies, assertions and unclosed-store behavior unchanged, with no diagnostic twins or logging probes. Run original class once per flavor, preserve diff/source/XML/logs separately, then restore exact HEAD bytes. This isolates test manifest-reader lifetime from store-finalization changes. The original preregistration test reads the manifest before its mutation; the promotion test reads a candidate manifest before its second-session mutation, making this a discriminating publication as well as cleanup control.

This temporary control is diagnosis, not a landed fix. No assertion weakening, production changes, additional experiments, full-suite runs, commits or pushes. Outcomes will be reviewed before recommending the minimal repair and deciding which store entry gates are satisfied.
