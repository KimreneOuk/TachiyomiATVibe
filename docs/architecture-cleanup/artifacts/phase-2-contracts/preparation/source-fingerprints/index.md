---
title: "P2.5 preparation — LazySourceFingerprints caller/lock/suspension trace and serial suspending cache contract (pinned d8252cbe)"
kind: review
comments: none
---

# P2.5 source-fingerprints preparation (read-only)

Pinned: `d8252cbe7e71a59b09f59656a3434d210d025094` ("move shared vision diagnostics out of pipeline"), clean tracked tree. Phase 1 commits have landed; the fingerprint slice is **untouched** at the audited revision — all lines below verified at `d8252cbe`. No source/test/config edits, no Gradle/git mutations, no subagents. Mechanism critique of P2.2 is a separate agent's task; this artifact claims only fingerprint-slice source facts.

# 1. LazySourceFingerprints anatomy (BatchChapterTranslator.kt:1203–1228)

- `internal class LazySourceFingerprints(streams, computeFn) : Map<String, String>`; `computeFn` is declared `suspend` and invoked only inside `runBlocking`: it CAN suspend internally (dispatcher hops, cooperative points), while `runBlocking` parks the calling thread — so the synchronous `get` blocks its caller thread until the whole computation completes.
- Cheap, I/O-free members: `size` (:1209), `keys` (:1210), `isEmpty`, `containsKey` — all delegate to `streams`.
- **Eager whole-corpus blockers, currently unused**: `values` (:1211), `entries` (:1212–1215), `containsValue` (:1219) hash every page on access. No production caller touches them (verified: call sites use only `get`, `.keys`, `.size`).
- `get` (:1221–1228): `streams[key] ?: return null`, then `ConcurrentHashMap.computeIfAbsent(key) { runBlocking(Dispatchers.IO) { computeFn(streamFn) ?: UNKNOWN_SOURCE_FINGERPRINT } }`.
- Computed `null` is converted to `UNKNOWN_SOURCE_FINGERPRINT` ("source-fingerprint-unavailable", TranslationPipeline.kt:198) and **installed as success** — a failed hash is permanent for the run (retry impossible today).

## Hard facts about the current `get`

1. **JVM monitor blocking, not coroutine suspension**: `computeIfAbsent` holds the ConcurrentHashMap bin lock across the entire `runBlocking` hash. A second thread whose key hashes to the same bin blocks on a monitor — the exact pattern P2.5 removes.
2. **Caller cancellation cannot reach the hash**: `runBlocking(Dispatchers.IO)` creates a fresh job that is NOT a child of the caller's context. Once `get` starts hashing, caller cancellation is invisible until the read loop finishes (or the stream throws). `PageDecode.computeSourceFingerprint` rethrows `CancellationException` (:131–132) but has **no cooperative checkpoint in its read loop** (:123–130) — and inside this `runBlocking` nothing ever cancels it anyway.
3. Thread cost: the calling coroutine's dispatcher is blocked for the whole read (`runBlocking` parks the caller thread; the hash runs on a second IO thread).

# 2. Every real consumer, with its authority scope (d8252cbe lines)

| # | Site | Operation | Enclosing context / authority |
| --- | --- | --- | --- |
| 1 | BatchChapterTranslator.kt:429 | `.keys` (cheap) | `translateBatch`, inside `withGeneration`, after engine setup, **no lock held** |
| 2 | BatchChapterTranslator.kt:431–443 | serial `get` over **whole current corpus** | Legacy-alias leg, only when a persisted key is absent from current streams (:429). Blocking gets; `CancellationException` rethrown (:434–435); other failures → page skipped; rekey proceeds only if the full corpus hashed (:444) — fail-closed |
| 3 | BatchChapterTranslator.kt:482 | `.size` (cheap) | `fingerprintSpan.end` — note the SOURCE_FINGERPRINT trace span closes at :480–483, **before** most lazy hashing happens (only alias-leg pages hashed so far). Trace span ≠ actual hash work today |
| 4 | BatchChapterTranslator.kt:494 → BatchResumePlanner | the lazy Map handed to the planner | See #5 — the expensive part is the planner's constructor |
| 5 | **BatchResumePlanner.kt:57 + :88–98** | `get` for **every page with persisted store state** | `val batchPagePlans = buildBatchPagePlans()` is a **property initializer**: constructing BatchResumePlanner (BatchChapterTranslator.kt:486) serially hashes all persisted pages via `runBlocking` on the constructing thread, **no lock held**. Fresh chapters hash nothing here; resume chapters eagerly hash every persisted page. `PageWorkPlanner.planChapter` (PageWorkPlanner.kt:135, object, non-suspend) is a pure transform over the resolved-or-null evidence |
| 6 | BatchChapterTranslator.kt:757 | `get` for one page | `standardTranslateOutcome` (suspend) **while holding that page's TRANSLATION stage lease** (`tryAcquirePageStageLease` granted :727, released :764/:789) — hidden blocking disk I/O under lease authority that fences the reader lane |
| 7 | BatchChapterTranslator.kt:660 | lambda `(String) -> String?` → BatchLaneWorkers:110 | non-suspend function type |
| 8 | BatchChapterTranslator.kt:842/:889 | same lambda → ChapterProfileBatchCoordinator:227 | non-suspend; both lanes (PROFILE_PIPELINE / STANDARD_PIPELINE) |
| 9 | ChapterProfileBatchCoordinator.kt:247–253 `admissionSourceSha` | `get` (via :248) | **non-suspend private fun**; fresh observation wins, malformed fresh → fail-closed null; else recorded/offered fallback. Used by PreflightWorker context (:312) |
| 10 | ChapterProfileBatchCoordinator.kt:283–290 `freshSourcePairsForPages` | serial `get` over the given pages | **non-suspend**; fail-closed — ANY invalid/missing sha returns null for the whole list |
| 11 | ChapterProfileBatchCoordinator.kt:710 | `get` for one entry | Inside `buildEnvelopeDispatchWorkLocked` (:680) — **VERIFIED OUTSIDE the artifact mutex**, see §3 |
| 12 | ChapterProfileBatchCoordinator.kt:885 | `get` (in `stampUnplannablePageTerminal`) | Terminal-stamp adoption; null → WARN + defer (fail-closed) |
| 13 | StandardLaneWorker.kt:44/:62–63/:106–111 | `get` (in `standardPageTerminalAtTranslate`) | **non-suspend private fun**; context default `{ null }` |
| 14 | BatchLaneWorkers.kt:110/:178–182 (`currentSourceIdentityMatches`) | `get` | **non-suspend**; used at :198 (predecessor identity), :416, :451 (lane admission) |
| 15 | BatchLaneWorkers.kt:1075 | `get` before textless guarded commit | hash → stamp `p.sourceFingerprint` → `guardedBatchUpdate` — runs in the OCR lane worker flow, inside that page's batch authority window |
| 16 | BatchLaneWorkers.kt:1244 | `get` for READY/PARTIAL/SKIPPED page | failure/terminal sweep stamp |
| 17 | PreflightWorker.kt:227, :257 | `admissionSourceSha` | checkpoint CLOSE binds `sourceSha256` (:228–240); checkpoint reuse compares recorded sha — mismatch → `SHA_MISMATCH` fail-closed (:258–259). Both call sites are suspend contexts, no encompassing artifact lock (uses guarded store APIs) |
| 18 | **PreflightWorker.kt:298** | `val artifact = store.withArtifactEngineLocked { it }` | **the escaped-handle proof** — the mutex is held only to capture the handle; the entire runPhase flow (incl. #19–#21) runs outside it |
| 19 | PreflightWorker.kt:322–323 | serial whole-corpus `freshSourcePairsForPages` | Terminal-resume leg (prior record FINALIZE/COMPLETE): every page hashed before a FINALIZE/COMPLETE shortcut is allowed; null → no shortcut (fail-closed); fresh pairs retained as run identity (:300, :276–277 `@Volatile`) |
| 20 | FinalizeWorker.kt:117–119 and :193–195 | serial **whole-corpus** `freshSourcePairsForPages` | Corpus-closure digest for the single COMPLETE publication. ANY missing/invalid sha → typed `PAUSED` with `SOURCE_FINGERPRINT_UNAVAILABLE_REASON` (:103–107) — fail-closed pause, not silent acceptance. `runPhase` and idempotent `drainPhase` both recompute |
| 21 | Run identity (Coordinator :255–273, :269 `recordedIdentitySourcePairs` lazy) | **never opens streams** | Run identity uses supplied/recorded shas with UNKNOWN fallback; fresh observation joins identity only via the terminal-resume leg |

# 3. Real lock scopes (traced, not name-inferred)

- `store.withArtifactEngineLocked` = `mutex.withLock { artifactEngine?.let(block) }` (ChapterTranslationStore.kt:298–302) — the store facade **kotlinx Mutex, non-reentrant**.
- The `:668`/`:674` scopes are the **begin/end of the manifest-coalescing window**, each a short independent `withArtifactEngineLocked` scope that releases immediately: :668–670 `beginManifestCoalescing()`, :674–676 `endManifestCoalescing()` in the `finally`. The helper `buildEnvelopeDispatchWorkLocked` is invoked at :672 with the coalescing window OPEN but the **mutex NOT held**.
- Lexical trace of the helper body (:680 onward): it holds no lock and contains **no `withArtifactEngineLocked` scope at all** (next occurrences in the file are :1000/:1007/:1096/:1103/:1207, all outside this helper). It reads the ESCAPED `artifact` handle directly (:685 `corpusEntriesFromCheckpoints(artifact, …)`), takes plain facade reads (:706 `store.snapshot(...)`, not the locked variant), and calls the cache at :710. The earlier claim of "own-body re-entry at :668/:674" was wrong (those lines are the wrapper begin/end, not inner scopes) and is withdrawn; no compilation/deadlock proof is claimed — this is a direct lexical trace of actual scopes at d8252cbe. Conclusion stands on that basis: the `:710` cache read runs with **no artifact-mutex protection**.
- The handle itself escapes via PreflightWorker.kt:298 (`val artifact = store.withArtifactEngineLocked { it }`). The same escaped-handle flow covers `stampUnplannablePageTerminal` (:885, reached from the rebuild loop at :774) and the FinalizeWorker legs (#20) — the `artifact` argument arrives from the escaped handle, not from a live lock scope.
- No `synchronized` blocks exist on the fingerprint path. The ONLY JVM monitor involved is the `ConcurrentHashMap` bin lock inside `computeIfAbsent` (§1.1).
- Held authority during cache reads today: the page TRANSLATION lease at #6, and batch lane authority windows at #15/#16. No store/document mutex is held across any cache read (verify claim for each future edit; this was checked per site above).

# 4. Coroutine-mutex serialization vs JVM-monitor blocking (contract language for P2.5)

- Today: waiter blocked on a **monitor** (uninterruptible at coroutine level), producer pinned to an IO thread inside `runBlocking`, caller cancellation structurally ignored.
- Proposed: a `kotlinx.coroutines.Mutex` around miss computation — **waiting is a suspend point**: a cancelled waiter leaves the wait immediately and never blocks a carrier thread; misses are **deliberately serialized** (acceptable per the ticket: no Deferred registry unless measured product behavior requires parallel per-key hashing — none of the 21 sites above needs it: #2/#19/#20 are inherently whole-corpus sequential, and single-page consumers (#6–#18) read one key).
- Distinction to preserve in tests: serialized misses under `Mutex` must be assertable as "second distinct key waits, then computes" (virtual time, `runTest`) — not as monitor contention.

# 5. Cancellation checkpoints: read vs active blocking read

- `PageDecode.computeSourceFingerprint` (:121–138 area): `withContext(Dispatchers.IO)` + `streamFn().use { ... }` read loop (`read` → `digest.update`), no `ensureActive()` between reads; catches `CancellationException` → rethrow; other `Exception` → logcat + **null**.
- **Fact:** an `ensureActive()` between reads gives up the hash only *between* two completed OS reads; it never interrupts an in-flight `InputStream.read`. Cleanup (stream close via `use`) follows the read's actual exit regardless.
- **Fact:** with today's `runBlocking`, even that between-reads checkpoint would never fire (fresh scope, nobody cancels it). With the suspending cache, the checkpoint becomes meaningful — but only if the cache calls `compute` in the waiter's context (it does) and the caller is actually cancellable there.
- Settled instruction B: the between-reads checkpoint change is DEFERRED (PageDecode behavior untouched in this slice); required instead is the post-compute caller `ensureActive()` plus documentation of the active-read limitation (an in-flight `InputStream.read` cannot be interrupted; `use{}` cleanup follows the read's actual exit).

# 6. Proposed mechanism (concrete, same file — BatchChapterTranslator.kt)

Replace the `LazySourceFingerprints` class in place (:1199–1228) with a serial suspending read-through cache. No new file, no Deferred registry, no `ConcurrentHashMap`, no `runBlocking`:

```kotlin
/**
 * Run-scoped serial suspending read-through cache for page source
 * fingerprints. One instance per translateBatch run; never reused across
 * rekey/source/config changes (the run creates a new one). The suspending
 * mutex deliberately serializes misses; waiting is cancellable and never
 * blocks a carrier thread or a JVM monitor. Resolved evidence is immutable
 * once installed; UNKNOWN is evidence of computed null, never of failure;
 * thrown failures and cancellation never install; snapshots copy under the
 * SAME mutex.
 */
internal class SourceFingerprintCache(
    private val streams: Map<String, () -> InputStream>,
    private val compute: suspend (() -> InputStream) -> String?,
) {
    private val mutex = Mutex()
    private val resolved = HashMap<String, String>()

    val keys: Set<String> get() = streams.keys            // cheap, no I/O
    val size: Int get() = streams.size                    // cheap, no I/O

    suspend fun get(pageKey: String): String? = mutex.withLock {
        val streamFn = streams[pageKey] ?: return@withLock null   // unknown page: null, not UNKNOWN
        resolved[pageKey]?.let { return@withLock it }
        val computed = try {
            compute(streamFn)                                     // owns dispatcher + use/close
        } catch (e: CancellationException) {
            throw e                                               // never installed as evidence
        }
        currentCoroutineContext().ensureActive()                  // checkpoint AFTER compute exits
        val value = computed ?: UNKNOWN_SOURCE_FINGERPRINT        // baseline computed-null policy
        resolved[pageKey] = value
        value
    }

    suspend fun resolveAll(pageKeys: Collection<String>): Map<String, String> =
        pageKeys.associateWith { get(it) ?: UNKNOWN_SOURCE_FINGERPRINT }

    suspend fun resolvedSnapshot(): Map<String, String> =          // plain map for pure planners
        mutex.withLock { resolved.toMap() }                        // SAME mutex; no second monitor
}
```

Notes: `compute` runs in the waiter's context and can suspend internally; the miss serialization is the mutex, and no caller thread is parked. Thrown non-cancellation failures never reach the cache today (`PageDecode` converts to null → UNKNOWN) and never install if `compute` ever throws (the `try` special-cases only cancellation; any other throw propagates and installs nothing — retryable, per settled instruction A). `ensureActive()` after compute prevents installing evidence for an already-cancelled caller; the active read itself still runs to actual exit (settled B keeps the between-reads checkpoint out of this slice).

# 7. Same-commit propagation manifest (all seven owners; d8252cbe lines)

One compiling slice across the **seven production owners** (steps A1–A6, B1, C1–C2, D1–D4, E1, F1); no interim blocking adapter. All touched call sites are inside suspend flows **except** A2/A3/D1/D2/E1 (non-suspend helpers — their signatures change in the same commit) and B1 (property initializer — restructured, not suspended). G1 below is the **optional, deferred** PageDecode step (settled B) — listed for traceability, NOT part of the slice.

| Step | File:lines | Change |
| --- | --- | --- |
| A1 | BatchChapterTranslator.kt:422 | Construct `SourceFingerprintCache(orderedStreams.toMap(), computeSourceFingerprintFn)` |
| A2 | BatchChapterTranslator.kt:431–443 | Alias leg: `mapNotNull { … }` → iterate with `cache.get(pageKey)` (suspend context ✓); keep CancellationException rethrow, per-page failure skip, and the all-pages precondition (:444) |
| A3 | BatchChapterTranslator.kt:486–498 + BatchResumePlanner.kt:57/:88–98 | **Restructure, do not suspend the constructor** (settled pre-resolve rule): after the alias/rekey block (:429–470) and before the synchronous planner call, resolve evidence ONLY for ordered pages WITH persisted store state — `val persistedEvidence = cache.resolveAll(persistedKeys)` — pass the plain map as `sourceFingerprints: Map<String,String>` (planner keeps its pure `buildBatchPagePlans`). A pristine chapter resolves nothing; new pages are NOT resolved here (unchanged) |
| A4 | BatchChapterTranslator.kt:660 | `freshSourceShaByPageKey = { pageKey -> cache.get(pageKey) }` — becomes `suspend (String) -> String?` |
| A5 | BatchChapterTranslator.kt:757 | Source resolved BEFORE `tryAcquirePageStageLease` (settled C — no disk I/O under lease); live terminal check and all lease-fence evidence (:727 granted, :764/:789 released) preserved unchanged |
| A6 | BatchChapterTranslator.kt:842/:889 | same suspend lambda type into ChapterProfileBatchCoordinator:227 |
| B1 | BatchResumePlanner.kt:48 | Constructor parameter type `Map<String, String>` (resolved evidence); planner body unchanged; `PageWorkPlanner` stays pure/sync (PageWorkPlanner.kt:135) |
| C1 | BatchLaneWorkers.kt:110 | Property type → `suspend (String) -> String?`; call sites :179 (`currentSourceIdentityMatches` — becomes suspend), :1075, :1244 (both already-suspend worker flows); callers of `currentSourceIdentityMatches` at :198/:416/:451 are suspend paths |
| C2 | StandardLaneWorker.kt:44 (context) + :62–63 + :106–111 | Context property → `suspend (String) -> String?`; `standardPageTerminalAtTranslate` becomes suspend (its callers are the suspend standard-lane admission/translate paths) |
| D1 | ChapterProfileBatchCoordinator.kt:227 + :247–253 | Property → `suspend (String) -> String?`; `admissionSourceSha` → suspend |
| D2 | ChapterProfileBatchCoordinator.kt:283–290 | `freshSourcePairsForPages` → suspend |
| D3 | ChapterProfileBatchCoordinator.kt:299/:312/:420/:494 | Context-lambda types updated with the same signature |
| D4 | ChapterProfileBatchCoordinator.kt:710, :885 | Direct `freshSourceShaByPageKey(...)` calls — both suspend contexts (`buildEnvelopeDispatchWorkLocked`, `stampUnplannablePageTerminal`) |
| E1 | PreflightWorker.kt:31/:50 + :227/:257/:322 | Context properties → suspend; all three call sites already suspend |
| F1 | FinalizeWorker.kt:21 + :117/:193 | Context property → suspend; whole-corpus resolve stays serial and fail-closed (typed PAUSED on any invalid) |
| G1 (deferred — NOT in slice) | PageDecode.kt:121–138 | Settled B: no between-reads checkpoint in this slice; the cache's post-compute caller `ensureActive()` plus a documented active-read limitation instead. Stream close stays in `use{}`; computed-null → UNKNOWN policy unchanged |

`BatchExpectedFingerprints` (planning/PageWorkPlan.kt:74) unchanged — configuration evidence, not source hashes. Run identity paths (Coordinator :255–273, `effectiveSourcePairs`) never touch the cache and stay non-suspending.

# 8. Settled owner instructions (binding for the upcoming ticket)

- **A — evidence policy (preserved).** Computed null → UNKNOWN (`source-fingerprint-unavailable`), fail-closed at every authority boundary, cached per run. Thrown failures and cancellation NEVER install; a cancelled or failed compute leaves the key unresolved.
- **B — PageDecode untouched in this slice.** The between-reads checkpoint change is deferred. Required instead: the cache's post-compute caller `ensureActive()` before installing evidence, and documentation of the active-read limitation (an in-flight `InputStream.read` cannot be interrupted; `use{}` cleanup follows the read's actual exit).
- **C — standard tail resolves before the lease.** The `:757` source resolution moves BEFORE `tryAcquirePageStageLease`; the live terminal check and all fence evidence under the lease are preserved unchanged.
- **D — same-mutex snapshots.** `resolvedSnapshot()` is suspend and copies under the SAME coroutine Mutex as the writers; no volatile swaps, no second monitor, no `synchronized`.
- **E — intentionally serial misses.** No Deferred registry, no parallel per-key hashing, and NO measured/wall-time performance claim anywhere in the ticket. The alias leg, terminal-resume leg and both finalize legs remain whole-corpus serial by design; any future parallelism is a NEW decision with its own justification.
- **Pre-resolve rule.** Resolve evidence only for ordered pages WITH persisted store state, AFTER the alias/rekey block (:429–470), BEFORE the synchronous planner call (:486). A pristine chapter (no persisted state) triggers zero resolution.
- These choices are settled owner instructions, not open questions; the implementation slice still passes the re-audit gate before land.

# 9. Smallest meaningful tests

New (one file, `pipeline/batch/SourceFingerprintCacheTest.kt`, `runTest` + virtual time):

1. Reuse: two `get`s for one key → one `compute` invocation.
2. Serialized distinct keys: second key's compute starts only after the first finishes (order asserted via launched channels); waiter is cancellable while waiting (cancel → first still completes, second's waiter exits).
3. Unknown page key → `null` (not UNKNOWN), no compute.
4. Computed null → UNKNOWN installed; second get returns UNKNOWN without recompute (today's baseline).
5. Cancelled during compute → exception propagates, nothing installed; next get recomputes (only meaningful with a throwing/cancellable fake compute).
6. `resolvedSnapshot()` (suspend, copies under the SAME mutex) equals resolved evidence and performs no compute for unresolved keys.
7. keys/size cheap: no compute triggered by `keys`/`size`/`resolveAll(emptyList())`.

Existing suites to extend (focused assertions only):

- `BatchResumeAndDisplayContractTest` — resume with persisted pages still plans with resolved evidence (A3); changed-source-under-same-key and legacy-alias rekey unchanged (BatchChapterTranslator :429–470 behavior preserved); pristine chapter (no persisted store state) triggers ZERO cache resolution (settled pre-resolve rule); standard-tail source resolution happens before lease acquisition (settled C) with terminal check/fence evidence intact.
- Coordinator/preflight/finalize tests (StandardPipelineCoordinatorTest, Stage7FinalizeResumeCoordinatorTest, PreflightWorker/FinalizeWorker suites) — SHA_MISMATCH (:258), terminal-resume fresh-pairs fail-closed (:322), finalize PAUSED on unavailable digest (:117/:193) unchanged under suspend signatures.
- `TranslationPipelineConcurrencyTest` / standard-lane terminal tests — `standardPageTerminalAtTranslate` predicate unchanged with suspend evidence source.
- Real persistence coverage (journal/store suites) stays untouched; no test deleted; no sleeps introduced.

# 10. Risks registered

- **R1:** The escaped `ChapterArtifactEngine` handle (PreflightWorker.kt:298 `{ it }`) and the short begin/end coalescing scopes (:668/:674) are load-bearing: P2.5 edits must not wrap `runPhase` or `buildEnvelopeDispatchWorkLocked` in a new encompassing `withArtifactEngineLocked` scope, and must not "fix" the escape by re-locking the flow — the helper legitimately runs with the mutex released (§3 lexical trace).
- **R2:** Constructor-eager persisted-page hashing (BatchResumePlanner:57) moves to an explicit suspending step (A3, settled pre-resolve rule): only pages with persisted state, after rekey, before the synchronous planner; a pristine chapter resolves nothing. Ordering matches where the constructor ran, so store state and rekey outcomes are identical.
- **R3:** The SOURCE_FINGERPRINT trace span (:418–421, ended :480) already closes before most hashing; P2.5 must not claim the span now measures hashing, and should not silently move the span (trace consumers were just aligned in 444b5abf).
- **R4:** `UNKNOWN` is non-hex and fail-closed at every authority boundary today (admission :249, pairs :286, terminal predicates, finalize :119/:195). Any policy change to retryable failures must update ALL of those consumers in the same slice.
- **R5:** Signature widening (`suspend` lambdas) touches the seven production owners plus context data classes (deferred G1 excluded); compile is the gate — no partial-land.
