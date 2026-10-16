---
kind: review
title: "S5 owner-cutover corrected frozen snapshot review r2 (prelaunch verdict)"
---

# Corrected-source review r2 — S5 owner-cutover (prelaunch)

- Reviewer: independent GLM review agent (destination agent). Snapshot: working tree on branch `architecture-cleanup`, HEAD `1c7df290dd0474d14165d86ead16f98eae442c08`; all 10 frozen files verified by SHA256 against the handoff (9 unchanged + `SinglePageOverlapBoundaryTest.kt` refrozen at `51d844d4e458fa789b3e44c268f6fc369768df039d607189999b5041591dbbe3` after the authorized comment-only trim; the removed line was the only banned execution-status prose).
- Baseline `ff21fd15`. No Gradle, no source edits, no device claims, ticket remains status 1. **This review grants a bounded PRELAUNCH clearance only — it is not implementation approval and not test-result approval.** The cold review artifact is preserved unchanged.

## Verified fixes in the frozen tree (all prior findings)

1. **P0 compile wiring**: both `withNativeLane` calls now carry `onTimeout` with real actions — segment-2 orphans deferred publications and warns (`TranslationPipeline.kt:731-736`), retry writes through `attempt.markTimeout` (`1678-1691`). T3 now bounds both branches (`withTimeout(singlePageTimeoutMs)` at 723 with the native child inside); the store-truth-outranks-timers guard is applied in both the in-band TimedOut arm (823-849) and the T3 timeout catch (880-905).
2. **P0 wrapper retarget**: `TranslationPipeline.kt:1637-1648` delegates all eight parameters to `CleanedPublication.persistOnnxCleanedImage` (385-457); the prepared path (1209-1257) therefore keeps owner/receipt enrollment and attempt fencing.
3. **Owner-bypass recycles eliminated**: `CleanedPublication.kt:443-452` and `SinglePageOnnxPhase.kt:884-891` route failure disposal through `owner.release()`; raw `recycle()` remains only in ownerless fallbacks. Ownership-aware source recycles at `TranslationPipeline.kt:762-768`, `SinglePageOnnxPhase.kt:596-604`, `634-641`.
4. **OCR custody**: OCR-only runs retain source custody for segment 2 (`SinglePageOnnxPhase.kt:634-641`); the boundary disposes it after the native branch joins (reader finally chain 915-945).
5. **Render borrow strand**: no suspension between `owner.borrow()` (683) and the guarding `try` (686); retry borrow closed in its own `finally` (886-888).
6. **Store identity**: `AttemptArtifactLease` binds the store instance (`ChapterTranslationStore.kt:123-128`); `stamp` no-ops on a foreign store (138); absorption consumption is identity-guarded (2002). `CertifiedArtifactProgress` is `Long`/`String?` typed (113-120). Covered by two real tests (cross-store no-certify 546-578; absorption 509-543).
7. **Attempt-write coverage**: retry SUCCESS publish enrolls and awaits the receipt before rendering (`SinglePageHttpRenderPhase.kt:787-811`); resume SUCCESS publish enrolls + flushes + awaits receipt (`SinglePageOnnxPhase.kt:850-871`); final commit writes through `attempt.write` (914-923). Unfenced direct persists remain only behind `attempt == null` fallbacks (pre-existing no-authority callers).
8. **Seam**: `translatePreparedPage` uses `singlePageTimeoutMs` (1433); prepared render uses an empty local owner with no dead produce branch (1447-1462).
9. **CLEANING stage event** restored on the segment-2 native branch (750) and resume paths (`SinglePageOnnxPhase.kt:546,571`).
10. **Tests are genuine now**: overlap tests drive the real pipeline with real barrier arrival/release gates (both INPAINT and PROVIDER_START), assert single provider call and no early render; the cancellation test verifies the provider is cancelled only by caller cancellation and the cleaned bitmap recycles exactly once after native cleanup joins; the three textless tests assert durable artifact state (`TEXTLESS_COMPLETE` manifest), zero provider calls, exact inpaint call counts, lease release, and single source recycle against a real lazy artifact store; lease/absorption tests use `enableLazyPersistence` with the sanctioned `cancelArtifactCandidateLocked` foreign mutation and typed rejections. Prior overclaim class is gone.
11. **Comment rule**: touched comments are code-explaining and short; the one execution-status line was trimmed and the file refrozen.

## Deliberate reds — correctly unfixed and correctly scoped

The three behavioral gaps exist only where the pending patch targets them, and each test fails for exactly its intended cause:

- Reader mask-only: early return `blocks.isEmpty()` (`TranslationPipeline.kt:707-715`) completes before segment 2 → `assertTextless` fails on `inpaintStatus`/`inpaintCalls` (231/233), not on the outcome line.
- Prepared mask-only and unmasked textless: `persistOnnxCleanedImage` enrolls only when the owner holds a bitmap; with none, the prepared handoff aborts null (`CleanedPublication.kt:395-407` → `TranslationPipeline.kt:1231-1236`) → `reference.shouldNotBeNull()` (238) fails; `isPreparedPageTerminal` (`PreparedPageBoundary.kt:124-133`) is the un-narrowed version, as the patch expects.
- Provider silence for empty blocks is grounded (`SinglePageHttpRenderPhase.kt:392`), so the zero-paid-call assertions will hold post-patch.

## Pending patch audit (ticket "Frozen red-first patch") — scope is safe

The five edits (reader early-return split, inline condition `blocks || masks`, masked-textless soft-null arm, prepared no-erase bypass, terminal narrowing + lazy-durability checks) are narrow and map 1:1 to the reds; no unrelated sweep, no provider-call introduction, fail-closed durability preserved (drain + OCR persist + flush + `isTerminal` gating). Two bounded cautions for the applying pass: (a) the `lazyDurable` block reaches `store.mutex` / `isLazyGenerationCurrent` / `hasPendingLazyPersistenceLocked` from `PreparedPageBoundary`'s package — confirm visibility/imports (`kotlinx.coroutines.sync.withLock`) or fold the check into a store-internal helper; (b) keep the soft-null arm strictly after the TimedOut arm and before generic failure handling, as written.

## Launcher audit (`planned-s5-red-01.sh`) — safe, bounded

One `:app:testStandardDebugUnitTest` task; exactly three `--tests` method selectors that match the frozen test names verbatim; guard `--task` identical to the Gradle task; fresh evidence dir `s5-red-01` (must not pre-exist); 1200s/300s deadlines; `--max-workers=2` and `-Dkotlin.daemon.jvmargs=-Xmx3g` are benign (the guard forces in-process Kotlin compilation, so no untagged Kotlin daemon is spawned); no clean and no duplicate standalone compile. Guard mechanics accepted (cold review disposition stands): R1 manual live tag capture by the coordinator during this run remains required before guard 124 is treated as JVM termination.

## Prelaunch verdict

**CLEARED for the single guarded red run exactly as specified in `planned-s5-red-01.sh`** — one StandardDebug task, the three exact textless methods, guard active, coordinator manual live tag capture. Expected outcome: the selector fails for the three lifecycle causes above. Nothing else should run in this invocation.

Not granted by this review: implementation acceptance of the pending patch (requires the red evidence + coordinator authorization), green-run/test-result approval, device claims, and any statement that the corrected tree is "done". The cold-review artifact's findings remain historical record only.

## Guard update r3 — daemon marker fix, probe01 evidence, and red02 disposition

- **R1 was a real catch.** `s5-red-01/java-process-inspection.json`: client 11656 carried all three `-Dtraycer.validation.*` tags; daemon 28332 carried the injected `org.gradle.jvmargs` (`-Xmx4g -Dfile.encoding=UTF-8`) but **no** `-D` tags — Gradle 8.12 strips mutable `-D` properties from daemon startup arguments while applying the jvmargs. The manual owned-stop (separately verified ancestry, full `CreationDate` revalidation before/after jstack, kill 0, no `/T`) is safe evidence; the run was stopped in configuration at 79.27s, actual exit 1, before any test ran.
- **Marker fix reviewed and cleared** (`windows-run-guard.py`): `daemon_marker = -XX:ErrorFile=<evidence>/jvm-<runUUID>-<sha256(task)[:16]>-<projectDigest>-%p.log` injected into `org.gradle.jvmargs` — the channel proven to reach the daemon. `matches()` accepts the three exact D tokens OR the exact marker token AND the exact `org.gradle.launcher.daemon.bootstrap.GradleDaemon` token; the client carries the marker string inside `-Dorg.gradle.jvmargs` but never the `GradleDaemon` token, so it matches only via D tags (correct — it is invocation-owned). `terminate_owned` ordering (workers → daemon → client) is pure ordering applied before unchanged identity filtering/revalidation. `invocation.json` records the exact marker. No blockers.
- **Probe01 evidence** (`guard-daemon-probe-01`, scratch Gradle project, no plugins/deps): `live-marker-proof.json` — daemon 26736 with the exact invocation marker + `GradleDaemon` token; client 26864 with the exact three D tags. `termination.json` — daemon FIRST, `verified termination`, jstack 0, kill 0; already-exited client safely skipped. `result.json` — guard 124, actual 1, setup-deadline breach, `monitor_error` null, 29.7s; no Java remained. Selftest02 re-ran the plain-java route after the `matches()` signature change: sibling survived, target killed, guard 124, outer 0 (11.38s).
- **Honest caveat**: `GUARD_PROBE_ENTERED` was not reached — the 25s setup deadline was consumed by daemon startup and init-script loading (jstack showed the daemon worker RUNNABLE, actively compiling the script, not stalled). Probe01 therefore validates the **setup-phase route only**.
- **Disposition: setup-route actual-daemon proof suffices for red02.** Every `terminate_owned` ingredient on the real Gradle route is now evidence-backed (sentinel match, jstack, verified kill, ordering, revalidation, client handling). The test-deadline arming logic is unchanged since the originally cleared helper and its failure mode is benign: if the `TRAYCER_TEST_START` marker were never detected, the guard falls back to the proven setup route (1200s ceiling) and the same proven termination path. A task-entry probe would not have exercised Test-worker tag visibility either (the scratch project has no Test task), so it adds zero safety coverage; per the no-redundant-greens policy, no follow-up probe is required (budget: none).
- **red02 proceeds** on the previously cleared launcher shape — same three exact selectors, fresh evidence path, reviewed marker fix — with the standing midrun captures: confirm the live daemon sentinel and additionally capture **one forked Test worker command line** to evidence init-script D-tag visibility, the one leg no evidence covers yet.
