---
title: "P2.5 — explicit suspending source-fingerprint preparation"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Mechanism gate before editing

Refresh the actual LazySourceFingerprints consumers, caller suspension boundaries and real lock scopes. The older bridge manifest is caller evidence, not an obligation to add a Deferred registry. Begin with the simplicity-first serial suspending read-through cache in the same BatchChapterTranslator source file, if adequate; a suspending cache mutex may intentionally serialize source reads without blocking a JVM monitor. Resolve outside store/document/native locks. Any need for parallel per-key producers must be justified by actual required behavior, not the older optimization plan. Return the concrete design and affected-file manifest to parent before changing this slice.

# Required semantics

## Settled preparation choices

[Pinned caller and lock preparation](../../preparation/source-fingerprints) supports the serial suspending cache. Before implementation, refresh its seven-owner propagation manifest at the accepted P2.4 base; this preparation is not code acceptance.

- Preserve computed-null as cached UNKNOWN for the run, which remains fail-closed rather than a valid fingerprint. Unknown keys return null. Thrown errors and cancellation install nothing; check caller activity after compute exits and before installation.
- Keep cache and computation in the caller's coroutine context with coroutine Mutex serialization, no runBlocking or detached producer registry. Any resolved snapshot is a suspend operation copying under that same Mutex; a separate synchronized monitor would not protect its writers.
- After alias/rekey and before the synchronous resume planner, explicitly resolve only ordered pages that have persisted state. Pass an immutable evidence map to pure planning. Do not eagerly hash a pristine chapter.
- Resolve the standard-tail source observation before acquiring its TRANSLATION lease. Preserve the existing under-lease live terminal recheck and all publication fencing. Cache work must not span store/document/native locks; audit actual lexical lock scopes, not names ending Locked.
- Defer PageDecode read-loop checkpoint changes in this slice. Its existing stream use/close behavior stays intact; a post-compute check prevents cancelled-result installation but cannot interrupt an active blocking read. Document this limitation and retain meaningful close/cancellation tests.
- Deliberately serialize cache misses and existing required whole-corpus legs. This is a simplicity choice, not a claim of identical measured wall time. Preserve fail-closed resume/finalize prerequisites and recorded run-identity semantics.

Remove hidden I/O in Map.get/entries/values and computeIfAbsent/runBlocking. Keep per-run lazy lookup, explicit immutable resolved evidence for synchronous pure planning, baseline UNKNOWN/null behavior, source/rekey/reset identity and whole-corpus completion prerequisites. Do not eagerly hash a pristine chapter to retain a synchronous Map API. Failed/cancelled reads must not install success; cancellation checkpoints between blocking reads do not prove an active OS read immediately exits. Review PageDecode hashing cancellation without changing OCR/model algorithms.

Known propagation includes BatchChapterTranslator, BatchLaneWorkers, StandardLaneWorker, ChapterProfileBatchCoordinator, PreflightWorker, FinalizeWorker and recovery/BatchResumePlanner. Keep BatchExpectedFingerprints unchanged. Pass already-resolved evidence into pure filters/planners and protected validation; revalidate existing leases/generation/version/fingerprints. Update required suspend callback declarations/callers in one compiling slice; no interim blocking adapter.

Test repeat lookup reuse, concurrent/cancelled lookup behavior for the chosen simple mechanism, error/null policy, stream close and run lifetime. Preserve resume, legacy aliases, changed source under same key, context seeding, checkpoint adoption and final corpus closure. Refresh focused suite baseline, use controlled coroutine tests for cache policy, retain real persistence coverage, and capture both flavors. Actual diff and regression evidence require Gemini/Antigravity acceptance. No broad batch callback/capability refactor, model calls, production persistence changes, commits/pushes by child.

# Mechanism accepted at 2efde3b5

The source refresh confirms six production files need edits across the seven-owner caller set. BatchResumePlanner already accepts a plain Map and remains unchanged; resolve only persisted ordered pages after rekey and pass the resulting evidence map to it. SourceFingerprintCache replaces the Map implementation within BatchChapterTranslator, exposing cheap keys/size, suspending get, and resolveAll for the single planning call site. No unused resolvedSnapshot API, Deferred registry or extra production file.

Existing LazySourceFingerprintPushThroughTest directly constructs the retired class and must migrate its two cache cases to the explicit API while retaining the unrelated OCR-offset test unchanged. Add meaningful controlled tests for same-key reuse under contention, distinct-key serialization/cancellable waiter, computed-null caching, thrown-error and cancellation no-install/retry, including cancellation detected only by the post-compute checkpoint. Keep stream closure explicit in test computations; do not claim this interrupts active OS reads.

Implementation may proceed following the focused baseline. Refresh actual selector counts, preserve each run's XML separately, retain source/rekey/completion fences and avoid new store-lock scopes. Return the full actual diff, failure provenance, full formatting filename inventory and focused evidence for independent Gemini/Antigravity review before integration.

# Integration

Independent final recheck accepted; parent committed nine paths as `999bb9be6563f957ee508f739ba2f042bf1c90ee` (+438/-83). Production selection reads current store state after rekey, in stream order. Revision evidence: 53 focused tests per flavor passing; regression RED retained. Final formatter inventory contains only baseline LiteRT debt. Full phase validation and publication remain P2.6.
