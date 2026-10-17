---
kind: spec
title: "Phase 4 partial-S2 checkpoint and cross-device handoff"
comments: none
---

# Status

Prepared 17 October 2026. Partial-S2 checkpoint: focused33 rerun verified green; scoped Kotlin formatting/apply/check passed. The user stopped the final GLM review because usage was exhausted and explicitly directed checkpoint commit. Root inspected the formatting-only delta; independent final postformat review was not completed. Earlier mechanism/source reviews were completed before focused tests. This checkpoint does not accept S2 as complete.

Phase 4 S1 is independently accepted at `e2ebf51f`. S2 remains IN PROGRESS; the reader facade has NOT switched to the queue. S3–S5 remain, then Phases 5–8. Earlier Phase 3 closure/device gates remain open. No full-suite or device claims.

Remote: `https://github.com/KimreneOuk/TachiyomiATVibe.git`, branch `phase4-s2-facade-wiring`. The user explicitly authorized THIS checkpoint push, overriding the prior never-push instruction for this operation only. Do not push further without authorization. Never force-push or overwrite another workspace's changes.

# Read first

- [Phase 4 index](../index.md), [plan](../plan/index.md), [S2 ticket](../tickets/s2-facade-wiring/index.md).
- [Master queue API](../../translation-step1-plan/index.md), [reader caller/lifecycle map](../../translation-step1-plan/reader-queue/index.md).
- Repository `AGENTS.md` and execution policy under `docs/architecture-cleanup`.

# Implemented in this checkpoint

- S1 standalone queue: generation/binding fences, one local operation, at most two physically live automatic workers, bounded explicit backlog, event-gated retry, source-null deferral, same-operation promotion supplier, truthful physical-exit drain handles. Executor stream/priority seams are additive; default callers preserved.
- Governor release diagnostics moved outside admission mutex. Replayable wake generation closes release-before-subscription race and works with injected clocks; wake and timer children are canceled together.
- Optional state-only `OperationPaidCallLifecycle`: permanent stop refuses every new provider admission, including nested retry; post-grant handshake denies before budget/network and returns unused permit. No Job capture, no global stop flag, no weakening AttemptAuthority. Null-context batch/manual callers retain behavior.
- Automatic-birth queue demands carry that lifecycle through promotion. Stop retains already-entered paid workers on their original Job through commit; unentered automatic work cancels. Explicit page cancellation remains immediate. A stop-relative 210s timer requests cancellation of the exact worker, never fabricates physical exit; store/resource custody stays until actual exit. Deferred demand retries keep lifecycle usable.
- AttemptAuthority has shared terminal-close machinery: genuine operation cancellation can close and publish under NonCancellable using the captured store precondition; healthy callers cannot mark cancellation; foreign rejection still closes old authority. Ordinary writes remain cancellation-suppressed.
- Pipeline outer cancellation catch orphans deferred publications before marker, rethrows cancellation, then releases resources. Healthy inner cancellation is condition-gated; existing timeout handling unchanged. Manual scheduler's late current-snapshot reset removed in same delta. Legacy chapter-wide sweeps remain until facade cutover and are NOT safe to reuse for queue-owned operations.

# Verified focused evidence

Local `validation/` logs/XML are intentionally not committed. These are focused gates, not full suite coverage.

| Slice | Actual result |
| --- | --- |
| S1 queue | StandardDebug48/0 and DevDebug48/0; scoped format checked; independent acceptance |
| Governor callback lock | Genuine red1, then class10/0 |
| Governor replay wake | Genuine injected-clock red1, then class11/0 |
| Paid lifecycle governor handshake | Genuine red4/3, then lifecycle4+governor11 =15/0 |
| Queue retention | Genuine red2/2, then queue54+lifecycle4+governor11 =69/0 |
| Authority | First fixture compile error corrected (not behavioral red); genuine class10/3, then10/0 |
| Real boundary cancellation | Genuine red1: translation remained RUNNING after canceled caller and physical native cleanup |
| Boundary candidate01 | Focused33/2: wrong fixture expected pending inpaint to be canceled; translation cancellation and healthy inner-CE passed |
| Boundary corrected candidate | StandardDebug33/0/0/0 = boundary23 + authority10,305.34s, guard0/no breach; fixture asserts stored PENDING before stop and unchanged afterward |

# Settled decisions to preserve

1. No whole-runner or paid-tail NonCancellable. Retain the original worker Job after paid entry so ledger resolve, render and commit keep their normal authority. Narrow NonCancellable only for won terminal markers and permit return.
2. Lifecycle stop and provider entry synchronize through the per-demand monitor. Queue lock order is control -> lifecycle; no reverse acquisition/callback/suspend under control. Cancellation, time reads, timer launch/start and diagnostics run outside locks.
3. Deadline expiry is a cancellation request, never an eviction/recycle receipt. `PendingNativeDrain` completes only at physical worker exit; teardown must respect it.
4. Do not reintroduce snapshot-based per-operation reset or draining-key exclusion receipts. Attempt-fenced boundary cleanup owns operation cancellation. Queue-owned chapter-stop sweeps must be removed/rerouted during cutover.
5. Reprepare cap3 belongs ONLY to the typed prepared-page-null stale producer. Keep old coordinator protection while it invokes prepared APIs. Fused single-page queue has no such producer; do not invent generic retry counters or infer retryability from diagnostics. Ledger caps and cooldown budgets are separate.
6. Provider wait budget15s remains measured from original enqueue across promotion. Device BACKGROUND -> FOREGROUND promotion gets a fresh foreground wait deadline; BG itself remains unbounded. Do not apply MANUAL-origin foreground mapping to prefetch.
7. Snapshot adapters must preserve real demand/stage/deferral truth. Terminal outcomes plus fake Queued fallback are insufficient; Completed is not by itself rendered readiness proof.

# Remaining S2 work (do not mark ticket complete)

1. Queue external reconcile/event API with optional factory refresh, callback resolution outside lock and revision fencing; memory/source-ready events rearm deferred work.
2. Source factory plumbing: registry.peek first; downloaded fallback uses existing DownloadProvider/findChapterDir/getChapterPages lazily on worker. True local-loader source requires resolver/source-ready refresh; never treat placeholder non-null factories as verified readiness. Manual missing index uses documented -1 sentinel and null trace index.
3. Typed demand/stage/deferral observation and truthful temporary AutoTranslationSnapshot adapter. Feed actual stageListener; preserve ready/paused/failure semantics.
4. Live provider priority cell/handle and promotion wake; same waiter/reservation/sequence and single charge. Atomic reads under governor mutex, no arbitrary supplier callbacks. Whole-call context handle, no-handle parity.
5. Live native priority across all four sites (OCR, inpaint, retry, render): prefetch BACKGROUND despite MANUAL lease origin, current FOREGROUND, same waiting operation promotion. Implement/test fresh FG deadline and wake; preserve no-handle callers.
6. Scheduler/manager production cutover to one queue, serialized lifecycle transitions, compute/identity/rekey fences, retained BATCH includePaused suppression; old coordinator no longer constructed only after replacement coverage.
7. Remove/reroute ALL queue-owned current-snapshot stop sweeps. Keep legacy user-intent APIs scoped correctly. markPageJobStuck needs a real queue-owned operation mapping, not silent no-op.
8. Teardown awaits physical drain before store eviction; lock-order regression. Rehome arbitration/coexistence tests, same-job promotion and N HTTP / N+1 native overlap.
9. S2 final focused gates both flavors, scoped Spotless, independent review. S3 UI migration, S4 retirement, S5 consolidation follow.

# Device/build operation

Use Git Bash on Windows with exact environment from AGENTS.md:

```bash
export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
export ANDROID_HOME='C:\Users\User\AppData\Local\Android\Sdk'
./gradlew --max-workers=2 "-Dkotlin.daemon.jvmargs=-Xmx3g" <tasks>
```

One Gradle process machine-wide. Confirm idle before first build and before one-time `./gradlew --stop` in a fresh worktree. On another OS, verify installed JDK/SDK and adapt paths explicitly; do not reuse Windows paths blindly. Do not copy local.properties or secrets from the old machine.

Use fresh evidence dirs, watchdog `docs/architecture-cleanup/validation/windows-run-guard.py` on Windows, explicit selectors and fixed `-PvalidationBuildTime=20261017000000`. Diagnose failures before rerun. House JVM file-backed store fixtures; no Robolectric workaround. JUnit String-returning tests can disappear: verify declared/quarantined/XML arithmetic. Red-first for behavior changes; scoped Spotless before commit. Do not rerun broad suites just to recreate the old machine's evidence.

# Start on another device

Fetch origin, inspect local status, and create a clean checkout/worktree tracking `origin/phase4-s2-facade-wiring`; never reset a dirty checkout. Verify HEAD against the exact SHA supplied in the final handoff message and require this document's final status before continuing. Read the linked plan and ticket, inspect actual source, then continue the remaining S2 mechanisms. New device cannot use old machine's local Traycer agents or validation paths as live tools. Preserve decisions and evidence in tracked Markdown so they travel with Git.


# Portable verification references

All rows use StandardDebug unless S1 explicitly names DevDebug. Run IDs identify retained evidence on the original host; those log directories are not required to continue on another device.

| Evidence name | Guard run | Result |
| --- | --- | --- |
| s2-queue-paid-green-01 | f2b8146a-ec74-4ad7-aff0-a41de763afbb |69/0/0/0,488.17s |
| s2-cancel-authority-green-01 |9e75c7a0-f599-44e4-adef-3cb00d88180f |10/0/0/0,487.09s |
| s2-boundary-cancel-red-01 |d8e602d9-99ed-4751-9b2b-e2455a61b86e |1/1/0/0, expected CANCELLED actual RUNNING |
| s2-boundary-cancel-green-02 |b0f1b6d8-a56b-4788-8606-949e62dec38e |33/0/0/0,305.34s |
| s2-checkpoint-format-02 |93b921fc-4a03-4e26-9ad9-2a893ee875fa |apply+check executed, exit0/guard0,109.41s |

No watchdog breach or monitor error in these completed gates. Formatting renamed the lifecycle source to match its sole class, sorted imports, adjusted whitespace/trailing commas, added braces around one else branch returning the same async job, and replaced three test statement semicolons with newlines preserving the last expression. Unrelated LiteRT formatting was restored before checkpoint. No postformat test rerun is claimed.

Postformat Kotlin SHA256 manifest (the checkpoint commit, not machine-local evidence paths, is the portable source of truth):

```json
{
  "app/src/main/java/eu/kanade/translation/engines/translator/ProviderRequestGovernor.kt": "4e60f56b6b57c50fe02c7b0d45b2bfdf038629b765a8d9f2992e2ebab8b48125",
  "app/src/main/java/eu/kanade/translation/pipeline/AttemptAuthority.kt": "355c7eeee509f53d85dcfedb6a9dd10a2de544539f6a4ef9aca1d8be88a0fd3d",
  "app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt": "9afc6745c538e1b1b4725e89f59a14cde16163605e669cf2830d3f7bb03e3b54",
  "app/src/main/java/eu/kanade/translation/scheduling/OperationPaidCallLifecycle.kt": "cb53fffa6b06a19e3318c7b9219679f57260dfc1f5e6d55d68e6304aeb9d93cb",
  "app/src/main/java/eu/kanade/translation/scheduling/ReaderTranslationQueue.kt": "b99257fda5c8781bc3eb5c82fd69691cd15feb3cffda1094aa2b08dcdc16a059",
  "app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt": "e3c55a38d4870ac81d496b19aaab00d31083ed1a1db0c8fbcf9ef07c2314a034",
  "app/src/test/java/eu/kanade/translation/engines/translator/ProviderRequestGovernorTest.kt": "d26f4d63ad61048d959112c4f16cd9d1452073ca278db1777336f3b1a1b8506d",
  "app/src/test/java/eu/kanade/translation/pipeline/AttemptAuthorityTest.kt": "b443b29daea20c1e1f65dc9cc1c6c5a40b2418ad1a76a7c370f57bbfd32c082f",
  "app/src/test/java/eu/kanade/translation/pipeline/SinglePageOverlapBoundaryTest.kt": "c66b33b693f2b55e506d192c5e7017b31610a669617aa11db7eb40bc9cf10e2c",
  "app/src/test/java/eu/kanade/translation/scheduling/PaidCallLifecycleTest.kt": "74051dbba439560956d81e2fc1b570ed5ef84453560ac987451b2eec8afc0bc6",
  "app/src/test/java/eu/kanade/translation/scheduling/ReaderTranslationQueueTest.kt": "668ea4241c1ae9dc0167d4426e1d77737ee4c6684c406c058810d42b601057ca"
}
```
