---
kind: review
title: "S5 coordinator source check: lazy artifact receipt ordering"
comments: none
---

# Open blocker before atomic implementation

At HEAD `ff21fd15`, neither blanket artifact-fence removal nor a claim that every accepted patch captures the final artifact state is accepted. The former disables protections; the latter overlooks the lazy path. Gemini is correcting the mechanism; GLM is independently rechecking it. No S5 source edits or Gradle are authorized until the concrete mechanism is sound.

## Actual lazy ordering

- `ChapterTranslationStore.patchPage` lines 1993-1999 installs the owned live page, calls `publishLocked`, then returns `Accepted(snapshotLocked(pageKey))`.
- `publishLocked` lines 3715-3718 branches on `lazyPersistenceEnabled` and calls `publishLazyLocked`, rather than synchronously persisting the artifact.
- `publishLazyLocked` lines 3748-3779 captures the current snapshot and queues a detached `PendingLazyMutation`; it schedules a later drain and returns.
- `StoreWriteDrainCoordinator.flush` line 92 calls `flushPendingLazyMutationsWithJournalCapture`; the store calls `flushLazyMutationsLocked` at line 1433. That invokes `persistArtifactMutationLocked` for the queued mutation at lines 1390-1397.
- The direct artifact path at lines 4057-4083 persists or promotes the candidate. Artifact manifest fields can therefore advance after the earlier accepted live receipt. No live `ownedPage` call is needed for this artifact drift.

An exact chain of authority-mediated live patches remains necessary, but verbatim accepted snapshots alone do not resolve subsequent asynchronous artifact progression. A concrete mechanism must preserve foreign candidate/source/edit fences while distinguishing authorized own persistence progression. The old final-commit refresh comment is not evidence that a broader refresh is safe.

## Required proof

State the actual API and ordering, cover own lazy drain followed by the next attempt patch, and demonstrate rejection of foreign live and artifact/candidate changes. Avoid a speculative registry or generalized provenance framework. The holder/detach, complete render extraction and nested cleanup findings in the [independent critique](../glm) remain open until corrected in the preparation spec.

# r4 disposition

Revision r4 is not cleared. Its six-observable refresh still infers causal ownership from equivalent state rather than an actual store-linked artifact completion. A bounded store API change is allowed if necessary; the draft's seven-file manifest is not a hard scope limit. The proposed `stableFingerprint` comparison is not a source-grounded implementation.

The holder sketch also retains a double-recycle path: only aliases set `transferred`, while a non-alias producer recycles the source and leaves it in the boundary holder for a second recycle. Use a single atomic slot with explicit take/detach into the producer's guarded local lifetime; boundary cleanup only consumes what remains after real native exit. Cleanup must nest each potentially throwing release, not merely list their order.

The r4 C1-C7 table changes approved timeout dispositions and case identities. The approved plan's coordination policy remains authoritative; no automatic native-timeout retry/fallback policy was authorized by the r4 revision. The HTTP governor settles at first invocation or phase exit, not at first provider response. Gemini is correcting these; GLM is independently investigating the minimum real artifact-result chain. No source edits or Gradle are cleared.

# Atomic implementation clearance after r5 / GLM concrete capability proposal

The operation-scoped store-certified own-drain capability direction is accepted for implementation, with the following corrections to the reviewer sketch. This is permission to author and review the single atomic S5 source/test tree, not approval of unimplemented behavior or permission to run Gradle. Gemini's implementation handoff is `31870c70-cb3d-4f85-b148-2d0d6cdabac0`.

- Keep the capability in the store/persistence layer, with a visibility-safe internal API and compatibility for existing public patch callers. Do not add a persistence-to-pipeline dependency.
- Store only bounded fence metadata tied to this store, page and attempt. No registry, history, durable provenance framework, or retained full page snapshot.
- Stamp only after successful own lazy artifact persistence while holding store.mutex. Rejected persistence, including partial recoverable manifest transitions, must not certify advancement.
- Match the exact certified post-drain artifact fields and expected live generation/version under store.mutex; candidate-less dependency fingerprints use the same fallback as snapshotLocked. No nulling or equivalence-based refresh.
- Preserve lease, block, generation and live version fences. Carry the effective expected fields through downstream publication/queued mutation validation.
- Lock order remains authority mutex then store mutex; drain stamps plain data under store mutex and never calls back into the authority.
- Current r5 resolves the earlier AtomicReference holder and full render-range omissions in direction. Implement nested cleanup for each resource, including streams; preserve the approved coordination table and translator frame lifetime.

Actual changed code, deterministic race/teardown tests and exact dual-debug/watchdog launch commands must be inspected before validation. No S5 source or execution approval is inferred from historical review verdicts. Independent GLM actual-code review follows authoring; parent owns commit/push.
