---
kind: review
title: "Independent critique of the loading-performance plan"
comments: none
---

# Independent critique — loading-performance-plan

Fresh-agent review requested via the traycer-artifact-critique skill (technical-design lens). Reviewed against the repo at `4de121bf` (`inpainting-modes-redesign`), read-only. Sources audited: `MangaScreenModel.kt`, `DurableChapterStatusResolver.kt`, `ActiveChapterStoreRegistry.kt`, `TranslationManager.kt`, `TranslationProgressProjection.kt`, `ChapterDataResetController.kt`, `DownloadPageLoader.kt`, `ChapterLoader.kt`, `ReaderViewModel.kt`, `ReaderTranslationController.kt`, `ChapterTranslationStore.kt`, `ChapterJournalRecovery.kt`, `TranslationUiProjection.kt`, `ReaderActivity.kt`, and the test anchors named in [validation](../validation-and-delivery).

**Citation accuracy:** every baseline citation checked is accurate — the reducer-side I/O (`MangaScreenModel.kt:483-494`, `:702-757`), the whole-manifest snapshot loop (`ChapterTranslationStore.kt:4901-4913`), the per-probe global wipe on creation (`DurableChapterStatusResolver.kt:210-212`), the candidate-first lazy hook (`ChapterTranslationStore.kt:4398-4431`), and the post-`getPages` `requestedPage` override (`ChapterLoader.kt:60-62`). All named test anchors exist. The refusal to add a persisted UNKNOWN state is correct — `Translation.State` (`Translation.kt:40-55`) has no such value and every consumer assumes its absence.

**Verdict:** the plan's structure is sound and its honesty gates are real. The findings below are the places where the plan, as written, either cannot pass its own gate or leaves a load-bearing mechanism unspecified.

## P0 — critical

### P0-1: The queue-emission global cache wipe voids the L2 warm-cache gate under the exact mixed workload the plan tests

`TranslationManager.kt:291-302` wipes the entire durable status cache on **every** `translator.queueState` emission, for any manga:

```kotlin
translator.queueState.collect { durableStatusResolver.clearDurableStatusCache() }
```

The in-code comment explains why the obvious replacement is unsound: queue emissions are conflated, so "a conflated remove→re-add of the same chapter reproduces the same membership set while durable truth changed" — a membership diff cannot replace the wipe. The [chapter-list](../chapter-list) invalidation matrix has no row for this event, and the audit instruction ("retain conservative invalidation for genuinely broad events") preserves this wipe as-is.

Consequence: while any batch runs (the plan's own "Mix of translated, warnings/errors, active queue" workload), every enqueue/state transition destroys all Known entries. The acceptance gate "unchanged A/B/C sequential second sweep creates zero additional probes" then holds only in artificial quiescence, and phases LP-02/LP-03/LP-04's warm-cache benefit silently degrades to the status quo precisely when the user is watching a list during a batch.

**Fix:** make this site an explicit deliverable of LP-02. Safe narrowing needs per-identity durable-transition signals (the translator/store knows *which* chapter mutated) or a per-identity version bump on transition — and must solve the conflated re-add case the comment warns about (e.g., identity-scoped version check at install, which LP-02 already builds). Add a gate: warm second sweep **with one active unrelated-manga batch** also creates zero new probes.

## P1 — major

### P1-1: Status/version coherence rests on a store-supplied observed revision that does not exist, and no current hook serializes mutation against a probe projection

L2's contract (steps 5–6) and the coherence paragraph correctly identify that "reading the current epoch after projecting an old snapshot is not sufficient." But the store exposes only `artifactStatus()` (`ChapterTranslationStore.kt:4637`) — no revision accessor — and the writer registry is diagnostic-only: `registerWriter`/`hasActiveWriter` (`ActiveChapterStoreRegistry.kt:237-279`) are consulted by tests, never by a mutation path. Nothing today prevents a commit from landing between a probe's status projection and cache install.

**Fix:** promote "store-supplied observed revision" to a named LP-02 deliverable with semantics: one counter bumped under the same lock that publishes page/display state; status projection returns `(state, revision)` atomically; the resolver installs only if the identity's version still matches. Add the interleaving test (commit held during projection, released before install → no install).

### P1-2: A shared-probe close-while-in-use race exists today, and L2's coalescing as scoped does not cover all shared users

`getOrCreateProbe` returns `owned = true` for a **reused** probe (`ActiveChapterStoreRegistry.kt:124-127`). Two concurrent callers of `withProbeStore`/`withDurableStore` on the same fileKey both believe they own it; the first releaser gets `releaseProbe → true` and calls `store.closeAndFlush()` (`DurableChapterStatusResolver.kt:224-231`) while the second may still be inside `block(result.store)`. The same adopt-then-close pattern exists in `getOrCreate`/`getOrCreateFile` (`ActiveChapterStoreRegistry.kt:76-84`, `:102-109`). L2 coalesces concurrent status *misses*, but `withDurableStore` (terminal snapshot reconstruction, `DurableChapterStatusResolver.kt:158-185`) is an independent shared user, and the required test ("held probe and active adoption do not double-close") covers double-close, not close-under-concurrent-use.

**Fix:** make probe ownership refcounted, or extend the resolver-owned in-flight lifecycle to `withDurableStore` users. Add a test where a status lookup and a durable-store read overlap on one probe and both complete on a live store.

### P1-3: The progressive opening's lock tenure is unpinned — it either serializes same-chapter status behind the full open, or reopens the rekey race

Registry `openingLocks` is keyed by fileKey and shared by `getOrCreate`, `getOrCreateFile`, `getOrCreateProbe`, and `withFileOpeningLock` (`ActiveChapterStoreRegistry.kt:68-152`). The download-rekey transaction relies on that same lock: `withChapterStoreAdmissionLock → withFileOpeningLock → hasStoreForFile` (`TranslationManager.kt:1560-1570`, rationale `:151-158`). Reader status today flows through the same lock via a probe (`ReaderViewModel.kt:918-928` → `TranslationProgressProjection.kt:296` → `DurableChapterStatusResolver.kt:197-204`).

[reader-entry](../reader-entry) R3 requires "preserve per-file opening exclusion while reconstruction completes" but never states *when the opening holds the file opening lock*:

- **Held to full registration:** `vm.translationStatus` and any badge probe for the entered chapter block until the full store is ready. The landing-page win is then partially masked on the state-update path unless R1's shared-authority status covers the entered chapter — but LP-05 is scoped only to "conditional non-download duplication."
- **Released early (to unblock probes):** a rekey's `hasStoreForFile` check can pass while the opening is mid-flight, and the guarded transaction will open/mutate/delete the artifact under the progressive opener — the exact race that transaction exists to prevent.

**Fix:** pin the tenure decision and its consequence. Either (a) exclusion through full registration **and** extend LP-05 so the entered chapter's status is derived from the shared opening, or (b) early release plus re-validation of store-absence under a re-acquired lock before any destructive step. Add a lock-tenure table: `ARTIFACT_OPEN_LOCKS`, registry `openingLocks`, chapter admission locks, `readerTeardownMutex` (`TranslationManager.kt:148-149`), and the new R3 owner scope — which may be held across suspension, and in what order.

### P1-4: R3's measurable win is bounded by unchanged journal replay, which itself reads every winning page snapshot — make the replay/hydration split a pre-LP-07 decision gate

Replay validates each winning record through `artifactStateMatches` → `artifactResolver`, which re-reads and re-hashes `readPageSnapshot` and verifies cleaned-image identity per page (`ChapterJournalRecovery.kt:552-593`, `:416-455`). Store open then re-reads the same snapshots in the manifest loop (`ChapterTranslationStore.kt:4901-4913`). Chapters with multi-generation histories re-read per winner transition (only stale records skip verification early, `:535-544`).

The plan honestly keeps replay unchanged and records the fallback, but LP-07/LP-08 (both High) precede any evidence on the replay-vs-hydration share, and the 20% gate silently depends on one detail being complete: memoized reuse of replay-verified snapshots by the second loop. If that reuse misses even a subset of identities (candidate vs committed keys), the "fast path" re-reads what replay already read and the gate fails for the wrong reason.

**Fix:** require the `store.journalReplay` vs `store.pageSnapshotHydration` split on representative fixtures in LP-00 *before* committing to LP-07, and mark the replay→loop snapshot memoization as load-bearing for the gate, not a nicety. Record the per-winner-transition re-read amplification as explicit LP-X2 input.

### P1-5: `ChapterList.Item.translationState` and the new resolution field lack a single owner — the false-untranslated default can still leak

`translationState` defaults to `NOT_TRANSLATED` (`MangaScreenModel.kt:1957`) and is written in place by four live paths (`updateTranslationState` `:667`, `reconcileAbortedBatch` `:639-644`, `updateTranslationRequests` `:676-679`, `toChapterListItems` `:734-739`), while UI readers consume it directly (`MangaScreen.kt:313-314`, `translationStateProvider` `MangaScreen.kt:831`). The null→NOT_TRANSLATED collapse exists in the projection layer too (`TranslationUiProjection.kt:19-24`; `TranslationProgressProjection.kt:296-298`).

L3 adds `badgeResolution` *alongside* the legacy field and mandates "update every reader," but does not define derivation ownership. With two independently written fields, the enrichment merge can disagree with a concurrent live write (late Known result vs fresh queue event), and any missed reader keeps rendering the default. `reconcileAbortedBatchState` operates on the legacy field and needs defined interplay with resolution semantics. Also undefined: the resolution for **non-downloaded** rows — durable status is queried only when `DOWNLOADED` (`MangaScreenModel.kt:723-733`), so those rows would sit Pending forever unless given an explicit not-applicable outcome.

**Fix:** make `translationState` a derived view of (live inputs, resolution) computed in one projection function, or migrate every reader to the resolution field in one changeset; add a test asserting no composable/model reads the raw field. Define the non-downloaded outcome explicitly.

## P2 — gaps and ambiguities

- **P2-1 (gate wording):** Absent/untranslated documents stay uncached by design (`DurableChapterStatusResolver.kt:97-101`; L2 caches only Known), so sweeps re-probe every untranslated downloaded chapter. Word the warm gate "zero additional probes for unchanged **Known** entries" — the acceptance table currently overclaims. LP-X1's typed absence cache is the right follow-up.
- **P2-2 (L1 starvation):** L1 rejects a derived mapping when the structural revision changed. Under rapid filter/sort churn each pass is discarded before publication. Specify latest-wins coalescing of structural recomputes.
- **P2-3 (alias lifecycle):** "Bounded alias mapping" has no eviction policy, and the canonical keys embed mutable names (`DurableChapterKey`/`DurableDocumentKey`, `DurableChapterStatusResolver.kt:17-37`). Renames orphan entries and cause mass misses — correct but should be stated, with the eviction policy named.
- **P2-4 (landing keys):** Page-key derivation is duplicated today — `resolvePageKey` lives in `ReaderViewModel.kt:1813` (used by `ReaderTranslationController.kt:206`) while the loader enumerates `sourceFileName` keys (`DownloadPageLoader.kt:146-172`). R2 should name the single shared key function and its owner, and include odd-filename/zero-padding parity fixtures.
- **P2-5 (switch cleanup):** Chapter switch awaits reader teardown (`cancelTranslationForChapter` → `awaitReaderStop`, `ReaderTranslationController.kt:655`). R3's owner-scope cleanup on chapter leave must be specified to never block on `readerTeardownMutex` or on the new chapter's locks, with a bounded cancel wait; extend the existing switch-during-held-verification test to cover the teardown mutex path.
- **P2-6 (flash metric baseline):** `ReaderTranslationController.kt:331-343` documents a real, unfixed original-flash path (holder built outside the warm window; `observePageView` bailing on a null store). Baseline the flash metric first and scope the display-quality gate to regressions, or the fast path will be blamed for a pre-existing defect.
- **P2-7 (mixed-activity gate):** The workload matrix includes the active-queue mix, but no acceptance gate evaluates warm-cache/coalescing under it (pairs with P0-1).
- **P2-8 (eager terminal store open):** `observeTranslationProgress` opens a full active store from the list path for durableTerminal chapters (`MangaScreenModel.kt:578-586`). L3's lazy terminal details must explicitly remove this eager open, or badge enrichment still triggers registered store opens even after L2.

## Summary

| Priority | Count | Theme |
| --- | --- | --- |
| P0 | 1 | Global wipe on queue emissions makes the headline warm-cache gate untestable outside quiescence |
| P1 | 5 | Unbuilt coherence primitive (P1-1), probe ownership race (P1-2), lock tenure (P1-3), replay-bound benefit (P1-4), dual-field UI ownership (P1-5) |
| P2 | 8 | Gate wording, coalescing, alias/key lifecycle, cleanup ordering, metric baselines |

The plan's delivery order, fallback discipline, and refusal to weaken recovery are its strongest properties. Addressing P0-1 and naming the mechanisms behind P1-1/P1-3 before implementation starts would convert most of the residual risk into testable contracts.
