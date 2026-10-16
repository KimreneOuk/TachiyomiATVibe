---
kind: spec
title: "Detailed plan: fast chapter lists and opening persisted translations"
comments: none
---

# Purpose and status

Reduce the two loading delays the owner identified as most disruptive:

1. Opening a manga and waiting for its chapter list.
2. Opening a chapter with existing translations and waiting to read the first translated page.

**Plan status:** Critiqued and hardened. An independent review was conducted by a fresh agent (see [independent-review](independent-review)). The plan has been updated to incorporate complete, concrete resolutions for 1 P0 (global queue wipe under mixed workloads), 5 P1s (store observed revision primitive, probe refcounting, lock tenure pinning & table, replay/hydration decision gate, and badge derivation single-ownership), and 8 P2s (gate wording, churn starvation, alias LRU eviction, landing key deduplication, teardown mutex non-blocking cleanup, flash baseline, mixed-activity gate, and eager terminal store open removal).

# Plan documents

- [Architecture-audit coverage and gaps](architecture-audit-crosswalk) — Comparison with the owner's October 14 subsystem audit; additional workstreams and source qualifications. This loading plan does not cover the full architecture refactor, and specification-level critique resolutions have not been runtime-validated.
- [Chapter-list implementation](chapter-list) — Two-phase publication, per-document status caching, queue-emission narrowing, probe refcounting, and single-owner derived badge state.
- [Persisted reader entry](reader-entry) — Enumerate landing page early, shared progressive opening, 5-layer lock tenure table, replay snapshot memoization, and verified read-only bundle delivery.
- [Validation and delivery](validation-and-delivery) — Workload matrix, mixed-activity gates, failure testing, test additions, work package dependencies, and rollback rules.
- [Independent critique](independent-review) — Review findings (1 P0, 5 P1, 8 P2) and resolution verification.

# What causes the current delay

| Delay factor | Where it happens | Impact |
| --- | --- | --- |
| Disk I/O inside atomic UI update loop | `MangaScreenModel.kt:483-494`, `:702-757` | Any concurrent update forces a compare-and-set retry that reruns the whole mapping pass |
| Blanket cache wipe on every queue event | `TranslationManager.kt:291-302` | Every batch progress event invalidates all cached durable statuses across all manga |
| Every downloaded chapter probes its translation artifact | `toChapterListItems` / resolver | Base chapter rows wait for every durable status probe to complete |
| Cache invalidation wipes unrelated chapters | `DurableChapterStatusResolver.kt:210-212` | Returning to or refreshing a list re-probes unchanged translations |
| Downloaded page enumeration waits for translation-store open | `DownloadPageLoader.kt:69-101` | Both original and translated page content wait behind artifact opening |
| Store open verifies snapshots across the manifest | `ChapterTranslationStore.kt:4873-4913` | Unrelated pages contribute work before the landing page becomes available |
| Non-preloaded reader paths can probe then actively open | `ReaderViewModel.kt:918-948` and resolver/manager open paths | Some cold entries can repeat opening work for the same chapter |

The reader Activity installs its hierarchy before async initialization (`ReaderActivity.kt:174-194`). A loading screen can therefore appear earlier than page content. Success is measured at readable chapter rows and the persisted translated landing page, not merely at an Activity frame or an earlier flash of the original image.

# Recommended decisions

| Decision | Recommendation | Reason / trade-off |
| --- | --- | --- |
| Chapter-list publication | Publish base rows immediately, enrich unresolved durable badges afterward | Removes badge resolution from the list's display dependency; requires an honest pending state |
| Expensive work in state reducers | Compute outside atomic callbacks; merge only values inside them with latest-wins coalescing | Avoids repeated I/O and side effects when compare-and-set retries |
| Queue-state invalidation (P0-1) | Narrow `TranslationManager.kt:291-302` to identity-scoped transition signals backed by install version checks | Prevents global cache destruction during mixed active batch workloads |
| Status / version coherence (P1-1) | Expose atomic `(state, revision)` store accessor; install only if revision matches identity version | Guarantees probe status cannot overwrite concurrent commits |
| Probe lifecycle (P1-2) | Reference-counted probe lease tracking in registry across probe and durable-store callers | Eliminates close-while-in-use race when concurrent readers share a probe |
| Badge presentation ownership (P1-5) | Single derived projection authority; non-downloaded rows immediately resolve to `NotApplicable` | Prevents dual-writer UI races and stops non-downloaded rows from hanging in Pending |
| Terminal progress details (P2-8) | Remove eager active-store opens from list path; load details on demand | Prevents list scrolling from instantiating full stores for finished chapters |
| Reader opening lock tenure (P1-3) | Hold file opening lock through full registration; derive entered-chapter status directly from opening | Prevents rekey race while eliminating same-chapter probe serialization |
| Reader hydration & memoization (P1-4) | One shared opening task; memoize recovery-verified snapshots for the manifest loop | Reuses replay work and delivers landing bundle before unrelated page hydration |
| First progressive-reader eligibility | Healthy supported-schema, trusted-journal chapters; legacy/untrusted/corrupt cases use full-open fallback | Keeps the initial prototype's recovery surface bounded |
| Replay/Hydration decision gate (P1-4) | Measure `store.journalReplay` vs `store.pageSnapshotHydration` in LP-00 before committing to LP-07 | If replay dominates (>80%), progressive snapshot hydration alone cannot reach 20% target without LP-X2 |
| Quality | Preserve exact committed image/text pairing, block geometry and output | Loading optimization only; zero compromise on display correctness |

Implementation may use ordinary local naming and existing utilities; the responsibility boundaries and invariants below are binding to the proposed design.

# User-visible behavior

## Chapter list

- Rows, titles, read/download state, selection and navigation become usable as soon as their normal database/download inputs are ready.
- Live queue/request state appears immediately and retains precedence over durable badges.
- Known valid cached translation state appears immediately. Unresolved disk badges stay neutral/pending; non-downloaded rows resolve immediately to NotApplicable.
- Badges update in place without resetting selection, scroll position, progress, open dialog or a newer queue event.
- Permission/recovery failures retain an honest unavailable/retry state; normal chapter navigation remains available.

## Reader with existing translations

- Resolve the actual landing page after page enumeration and last-read/requested-page rules.
- Prefer a verified committed translated base and matching text for that page; do not intentionally show the original first merely to improve a headline metric.
- Wait only for the recovery/verification actually required to trust that landing result; hydrate nearby pages afterward.
- A pending candidate never replaces a previous committed result.
- A page with no valid committed translated output follows existing original/error behavior.
- While the shared opening task finishes the full store, writer admissions wait for full readiness. This keeps existing planning, leases and mutation behavior intact.

# Preserved guarantees

1. Acknowledged journal-prefix authority, epoch ordering, tombstones, invalid records and bulk-rekey rules remain correct.
2. Candidate data and committed display data remain distinct.
3. Fingerprints/content identities are verified before display; image and translated blocks refer to the same committed generation.
4. Page leases, store admission locks, generation/version fences, atomic artifact publication and crash recovery remain in force.
5. Unknown/unavailable status is not authority for destructive actions or permission to overwrite an existing translation.
6. Cache hits cannot survive their owning mutation/storage version. Slow lookups cannot reinstate invalidated results.
7. No disk work runs inside a retryable UI state reducer or while holding a registry monitor.
8. Jobs, counters, cache entries, hydrated neighbors and open file handles stay bounded by screen/reader lifecycle.
9. Warm cache optimizations do not conceal revoked permission, deleted files or recovery failures indefinitely.

# Delivery order and expected wins

| Order | Work | Expected benefit | Complexity |
| --- | --- | --- | --- |
| 0 | Add matched loading milestones, structural counters, flash baseline, and replay/hydration ratio; capture baseline | Separates real delay from assumptions; establishes if replay dominates | Low—Medium |
| 1 | Move list lookup outside atomic updates with latest-wins coalescing | Eliminates retry amplification and UI starvation | Low—Medium |
| 2 | Narrow queue wipe (P0-1), store observed revision (P1-1), probe refcounting (P1-2), and per-doc status cache | Avoids reopening unchanged chapters across repeated scans, even during active batches | Medium |
| 3 | Publish base rows before durable badges; single derived badge projection (P1-5); remove eager terminal opens (P2-8) | Makes lists usable before badge resolution; resolves dual-writer UI races | Medium |
| 4 | Scope refreshes and defer unused progress details | Removes unnecessary work and memory churn | Medium |
| 5 | Share reader opening and derive entered-chapter status from opening task (P1-3) | Removes conditional duplicate opens and probe lock contention | Medium |
| 6 | Enumerate pages early, select landing key, and unify `ChapterPageKeyResolver` (P2-4) | Correct landing page prioritized with zero key discrepancy | Medium |
| 7 | Progressive verified landing bundle with snapshot memoization (P1-4) and 5-layer lock tenure | Existing translated content appears before full-store completion | High |
| 8 | Early-to-full store handoff with non-blocking teardown cleanup (P2-5) | Seamless reader display without original-flash regression or deadlock | High |
| 9 | Benchmark, failure corpus, mixed-activity gate validation, and controlled rollout | Establishes benefit and protects existing data | Medium—High |

No speedup is assumed from adding more parallelism. The initial list/cache changes can ship independently of progressive reader opening.

# Conditional later work

Only after the initial phases are measured:

- A durable, versioned status summary/index if full store opening still dominates badge completion.
- A typed, safely invalidated short-lived absence cache if repeated definitive missing-artifact lookup remains material.
- Bounded parallel snapshot reads if the storage provider and memory measurements support it.
- Journal replay/compaction work (LP-X2) if current epoch history is a demonstrated bottleneck (>80% of open time).

# Completion definition

The work is complete when structural tests prove that list rows no longer await all durable badges, unchanged chapters retain their valid cached results across both quiescence and active mixed-batch workloads, progressive-reader eligibility/fallback is safe, and matched device experiments show improvement in the owner's two actual milestones without correctness, display quality or resource regressions. Publish the final measurements, revision, configuration, test results and any deferred limitations in a changeset walkthrough.
