---
title: "Store baseline investigation - experiment plan review"
kind: review
comments: none
---

# Verdict: ACCEPTED WITH REQUIRED CAUSAL-SAFETY REVISIONS

Independent review and pressure-test of the [Store Baseline Investigation Plan](../index.md) (`artifacts/store-baseline-investigation/investigation-plan/index.md`) using the `traycer-artifact-critique` skill.

The experiment plan is **ACCEPTED WITH REVISIONS**. The planned containment boundaries — reversible instrumentation strictly in `ChapterTranslationStoreArtifactOpenProbeTest.kt`, a read-only scratch Windows Restart Manager helper, zero production modifications, pinned file hashes, and byte-for-byte restoration — are sound and approved. Four critical causal-safety, measurement, and concurrency risks must govern execution.

## 1. Critical Causal-Safety & Measurement Findings

### Finding 1: In-Memory Mutation Admission vs. Durable Persistence Acceptance (Critical)

- **Plan Assertion**: *"Changed live state establishes wrapper acceptance."*
- **Pressure-Test & Vulnerability**: In [`ChapterTranslationStore.publishLazyLocked`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt#L3712-L3751), in-memory state updates (`_state.value = snapshotPages()` and `displaySnapshotLocked`) execute unconditionally once basic mutation admission succeeds. This occurs **regardless** of whether `shouldQueueArtifact` evaluates to `true` or `false`.
- **Why It Matters**: Observing changed `store.state.value` or `store.display.value` after `store.updatePage(...)` proves only that the in-memory mutation was applied. It does **not** prove durable acceptance into `writeDrain.pendingLazyMutations` or that the mutation is eligible for manifest persistence. Inferring durable acceptance from live state will lead to false conclusions regarding registration timing.
- **Required Plan Revision**:
  1. Clearly bifurcate checkpoints into *in-memory admission* vs. *durable queueing*.
  2. To verify durable acceptance, the diagnostic must directly inspect:
    - `pendingArtifactPageRegistrations` (page keys awaiting manifest registration).
    - `writeDrain.pendingLazyMutations` (pages queued for disk serialization).
    - `artifactManifest.pages` (pages committed or present as candidates in the durable manifest).

### Finding 2: Windows Restart Manager (RM) In-Process Attribution Limits (Critical)

- **Plan Assertion**: *"A process/job disappearing with close and successful cleanup supports a lifecycle connection; only a positive process/job observation attributes an owner."*
- **Pressure-Test & Vulnerability**:
  1. **Process Boundary vs. Internal Handle**: Windows Restart Manager (`rstrtmgr.dll`: `RmRegisterResources`, `RmGetList`) identifies locking entities at the **Process ID (PID)** level only. A positive PID confirms that the reported process holds a registered resource, but a JVM PID does **not** prove which internal thread, coroutine scope, stream, or channel within the JVM holds the file open, nor does it exclude simultaneous external or concurrent file holders.
  2. **Coincidence vs. Causality**: An active background job (such as a debounced `writeDrain.drainJob` or `artifactEngine.retentionScopeJob`) coinciding with an open file lock does **not** prove that job holds the manifest handle. For instance, `ChapterDocumentIo.write()` closes its stream immediately; an unclosed `FileInputStream` from a manifest reader would persist regardless of whether drain work is active or idle. Coincidence is correlation, not causal attribution.
  3. **Closure Twin Indeterminacy**: If a closure-controlled twin passes TempDir cleanup, it demonstrates that closing the store allows cleanup to proceed, but does **not** prove which specific handle, stream, or background job was released or held.
- **Why It Matters**: Attributing the lock to a specific coroutine or job based on process-level RM detection or temporal coincidence risks misdiagnosing the underlying resource leak.
- **Required Plan Revision**:
  1. Explicitly bound the RM probe: a positive PID confirms process association; it does not prove internal handle identity or rule out simultaneous external holders.
  2. Coincidence between an active job and an open lock must be recorded strictly as a correlation, never as definitive causal attribution.
  3. Empty, error, or generic in-process RM results must be treated as inconclusive, without adding synthetic delays or polling loops.

### Finding 3: Reflection Thread-Safety & Measurement Timing under Asynchronous Drain (High Risk)

- **Plan Proposal**: Using reflection to sample `writeDrain.pendingLazyMutations` (`LinkedHashMap`), `pendingLazyTasks` (`ArrayDeque`), and `drainJob`.
- **Pressure-Test & Vulnerability**:
  1. `StoreWriteDrainCoordinator`'s mutation collections are plain, non-concurrent collections mutated under `store.mutex`. Reading them without synchronization while `scheduleDrain()` runs asynchronously on `persistenceDispatcher` risks `ConcurrentModificationException` or reading torn state.
  2. Returning from `flush()` guarantees that pending work at invocation was drained, but does not provide universal proof that no subsequent asynchronous mutation can be scheduled.
  3. Probes and reflection introduce unavoidable observational timing perturbations; claiming zero timing impact is unrealistic.
- **Why It Matters**: Diagnostic probes must avoid crashes while acknowledging observational timing impact honestly.
- **Required Plan Revision**:
  1. Diagnostic value copies of mutable collections must be acquired under the **existing actual `store.mutex`**, held briefly for snapshot copies only. Release the mutex before any disk I/O, Restart Manager queries, logging, flush, or close calls that could reacquire the store mutex.
  2. Never introduce a second mutex, sleep-based delays (`delay()` / `Thread.sleep()`), or artificial serialization policies.
  3. Acknowledge and document observational timing perturbations rather than claiming zero perturbation.

### Finding 4: Resource Leak Isolation in Session-Two Promotion and Cleanup Twins (Medium Risk)

- **Plan Proposal**: Session-two promotion check (#1) and cleanup twins (#4/#5).
- **Pressure-Test & Vulnerability**:
  1. In the original `#1` test ([`ProbeTest.kt:557`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/test/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt#L557)), the final store instance `val promoted = ChapterTranslationStore.openArtifact(root, "Chapter 2.json")` is opened and left unclosed. While this unclosed store is an observable source fact, it is not yet definitive proof that it alone causes a secondary manifest lock.
  2. In `#4` and `#5`, stores are also left open without close or teardown calls.
- **Why It Matters**: To isolate candidate-to-committed promotion from reopen display projection, the diagnostic must distinguish whether the failure occurred during session-two commit or during session-three reopen.
- **Required Plan Revision**:
  1. In diagnostic twins for `#1`, capture the on-disk manifest (`readManifest("Chapter 2")`) *after* session-two `closeAndFlush()` but *before* invoking `openArtifact()`.
  2. In all diagnostic twins, ensure every opened store (session-one, session-two, and promoted) is enclosed in independent `try / finally` blocks with `closeAndFlush()` to guarantee clean teardown.

## 2. Plan Review Verdict & Authorization

### Verdict: ACCEPTED WITH REQUIRED CAUSAL-SAFETY REVISIONS

Luna is authorized to proceed with diagnostic test instrumentation under the following binding constraints:

1. **Scope Boundaries**:
  - Diagnostic edits are restricted strictly to `app/src/test/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt`.
  - Windows Restart Manager probe is restricted strictly to a read-only scratch helper in `scratch/store-baseline-investigation/...`.
  - **Zero production source changes**, **zero test assertion weakening**, and **zero repository commits or pushes**.
2. **Measurement & Concurrency Rules**:
  - Distinguish in-memory mutation admission from durable queueing and manifest commitment.
  - Acquire snapshot copies of mutable collections under the brief hold of `store.mutex`; release before diagnostic disk I/O, RM calls, or store calls.
  - Interpret Restart Manager PIDs as process-level association only; do not assert job-level handle ownership based on coincidence, and do not rule out simultaneous external holders.
  - Enforce zero sleep-based synchronization and acknowledge potential measurement timing perturbations.
3. **Restoration & Hygiene Protocol**:
  - Retain full command logs and XMLs in separate per-flavor directories.
  - Upon completion of diagnostic data capture, restore `ChapterTranslationStoreArtifactOpenProbeTest.kt` byte-for-byte to match HEAD `999bb9be` (SHA-256 `33E01BB37B12019D67E990581EFB27E614364259F579A6510D9DC89185B9E541`).
  - Confirm untracked architecture report remains untouched at SHA-256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`.

<TRAYCER_NEXT_STEPS>
Experiment plan critique updated with parent qualifications and verdict recorded. Ready to await diagnostic results.

- [] Await completion of Luna's diagnostic instrumentation runs across both flavors.
- [] Review raw diagnostic observation logs, manifest snapshots, and restoration proofs.
  </TRAYCER_NEXT_STEPS>
