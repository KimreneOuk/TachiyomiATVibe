---
kind: spec
title: "Loading plan: measurements, test gates, delivery and rollback"
comments: none
---

# Baseline rules

Return to the [overall plan](..). Every experiment records revision, APK/build variant, device/Android/storage provider, storage-root type, chapter/page counts, artifact schema/journal trust, diagnostics configuration and thermal/background conditions. Use Snapdragon 8 Gen 3/Adreno 750 as the primary device if available; add a lower-memory Android device and at least one SAF provider before release.

No model conversion or weights change between baseline and treatment. Disable active Auto/batch translation for the primary persisted-entry comparison. Test live activity separately for correctness/contention, not mixed into the primary loading baseline.

# Workload matrix

Test across four representative fixtures:

| Workload | Profile | What it isolates |
| --- | --- | --- |
| Small healthy | ~15 chapters, 20 pages/chapter, clean journal | Quick path; ensures low fixed overhead |
| Large healthy | ~100 chapters, 45 pages/chapter, clean journal | Chapter-list probe scaling and snapshot hydration delay |
| Multi-generation / recovery | ~30 chapters with superseded runs, tombstones and crash history | Recovery correctness, memoization efficiency and legacy fallback |
| Mixed active queue (P0-1 / P2-7) | Active batch queue running on Manga B while opening and scrolling Manga A | Queue invalidation scoping, cache survival under churn, and contention |

Include at least one directory-based storage fixture and one archive-based chapter fixture.

# Metrics and sampling

## Primary user milestones

1. **Chapter list usable:** Time from screen entry to first usable chapter rows with titles, read status and download state available.
2. **First translated frame:** Time from chapter tap to the first displayed frame containing both the translated background and matching translated text.
3. **Badge completion:** Time until all durable badges for downloaded chapters are fully resolved. Reported separately so it is not mistaken for list availability.

## Mechanism and resource metrics

- Count of `ChapterTranslationStore` creations per screen open / chapter entry.
- Count of disk status probes (`exists`, file-open, manifest read) per list visit.
- Replay vs Hydration breakdown (P1-4): Explicitly separate duration of `store.journalReplay` from `store.pageSnapshotHydration`.
- Number of snapshot reads during store opening (measuring replay-to-manifest memoization efficiency).
- Pre-existing original-flash baseline (P2-6): Measure instances of original-image flash in `ReaderTranslationController.kt:331-343` on baseline before evaluating progressive entry to prevent confounding.
- Peak and retained native/JVM heap across 20 sequential chapter navigations.
- Open file handles and active coroutine jobs before and after screen exit.

## Sampling protocol

Discard the first cold run as JIT/cache warmup unless cold launch is the explicit subject. Take at least 7 iterations per condition and report Median, P90, Min and Max. Record outliers rather than trimming them silently.

# Proposed acceptance gates

These thresholds are targets for evaluating engineering investment, not measured outcomes. Baseline capture may justify tightening them; relaxing them must be documented with evidence.

| Gate | Requirement |
| --- | --- |
| Structural list publication | Holding every durable status lookup does not prevent usable rows or normal navigation |
| Atomic reducer purity | Zero disk lookup/observer-start side effects inside atomic UI reducers; one expensive mapping per accepted structural input |
| Warm status cache (P2-1) | Unchanged A/B/C sequential second sweep creates zero additional probes for unchanged **Known** entries; B-only mutation invalidates B and preserves valid A/C |
| Mixed-activity cache gate (P0-1 / P2-7) | Unchanged Known entries in Manga A retain valid cached status and produce zero new disk probes during active batch queue processing on Manga B |
| Coalesced status work (P1-2) | Concurrent same-identity misses use one opening; probe lease refcounting guarantees no close-while-in-use across probe and durable-store callers |
| Status / version coherence (P1-1) | Store-supplied observed revision prevents stale status installation when commits interleave with probe projections |
| Progressive page dependency | Eligible landing bundle can draw while an unrelated post-recovery page verification is held; active store/writers remain blocked until full readiness |
| Replay memoization gate (P1-4) | Opening manifest loop reuses replay-verified page snapshots without duplicate disk reads; LP-00 confirms hydration represents sufficient share to justify LP-07 |
| Lock tenure safety (P1-3) | Opening lock held through full store registration; reader status (`vm.translationStatus`) derives from opening task without contention |
| Single-owner badge state (P1-5) | Single derived projection authority for badge state; non-downloaded rows resolve immediately to `NotApplicable` |
| Correctness | All targeted state, lifecycle, recovery and committed-bundle parity tests pass; no candidate promotion, tombstone resurrection or stale cache install |
| Chapter-list performance | Aim for at least 30% median reduction in first-rows latency on previously disk-bound fixtures; report badge-completion cost separately |
| Translated reader performance | Aim for at least 20% median reduction in first committed image-plus-text frame on eligible large translated fixtures |
| Tails/unaffected paths | No sustained P95 regression above 10% on paired representative unaffected paths without documented justification; repeat noisy results before deciding |
| Memory/lifecycle | No persistent growth after repeated navigation; no increase above the existing decoded/hydrated window solely due to unbounded caches, jobs or handles |
| Display quality | Same committed image content hash and block/geometry identity; equivalent readable overlay/layout; no regression in original-image flash against baseline |

If a reader phase passes dependency tests but fails the user-visible performance target because unchanged journal replay dominates, retain useful proven list fixes, record the failed reader hypothesis, and evaluate the separately scoped recovery/index experiment.

# Test suites and additions

| Area | Existing anchor | Hardened cases / new assertions |
| --- | --- | --- |
| Status off Main | `ChapterTranslationStatusOffMainThreadTest` | Off-main behavior retained; reducer itself has no disk calls |
| Cache/recovery invalidation | `DurableStatusWipeGateTest` | A/B/C survival, B recovery, stale in-flight reinstall, aliases/root changes, LRU eviction |
| Queue-state invalidation (P0-1) | `QueueStateScopedInvalidationTest` (new) | Active batch queueState emissions do not clear cache for unrelated chapters |
| Store revision coherence (P1-1) | `StoreObservedRevisionInterleavingTest` (new) | Commit landing during probe projection increments document version and prevents stale cache write |
| Probe refcounting (P1-2) | `ProbeRefCountConcurrentUseTest` (new) | Concurrent `withProbeStore` and `withDurableStore` on same key both complete on live store with single close |
| Lock tenure ordering (P1-3) | `LockTenureOrderingTest` (new) | 5-layer lock hierarchy verified; no deadlock and no probe contention during progressive open |
| Replay snapshot memoization (P1-4) | `ChapterTranslationStoreArtifactOpenProbeTest` | Manifest loop skips disk reads for snapshots already validated during journal recovery |
| Single-owner badge state (P1-5) | `DerivedBadgeStateSingleOwnerTest` (new) | UI components consume derived resolution; non-downloaded rows get `NotApplicable` |
| Page key derivation (P2-4) | `ChapterPageKeyResolverTest` (new) | Parity between reader controller and page loader on odd filenames and zero padding |
| Chapter switch non-blocking (P2-5) | `ReaderTranslationControllerTest` | Chapter leave cancels subscription without blocking on `readerTeardownMutex` |
| Registry lifecycle | `ActiveChapterStoreRegistryTest`, writer registry tests | Shared progressive opening, adoption, subscriber cancellation, one close, full-store-only publication |
| List state retention | `ChapterTranslationSnapshotRegistryTest` | Delayed badge plus current selection/progress/dialog; structural revisions; latest-wins coalescing |
| Journal recovery | `ChapterJournalRecoveryTest`, related rekey/compaction tests | Tombstone/invalid latest winner, ACK truncation, epoch ordering, untrusted inventory and migration fallback |
| Overlay/layout | `TextLayoutCoordinatorTest`, `PersistedLayoutHydrationTest` | Entry/source switch generation fencing, early bundle to full-store handoff |

# Work packages and dependencies

| ID | Work / deliverable | Depends on | Complexity | Exit evidence |
| --- | --- | --- | --- | --- |
| LP-00 | Loading IDs, milestones, structural counters, flash baseline, and replay vs hydration split measurement | Current revision truth | Low—Medium | Matched timeline, fixture inventory, flash baseline, and replay/hydration ratio |
| LP-01 | Pure chapter-list publication reducer, structural-version owner, and latest-wins coalescing | LP-00 | Low—Medium | Contention tests show no repeated I/O; latest UI fields retained under churn |
| LP-02 | Per-document status versioning, `TranslationManager` queue wipe narrowing (P0-1), store-supplied observed revision (P1-1), probe refcounting (P1-2), and alias LRU eviction (P2-3) | LP-00 | Medium | Mixed-activity gate passes; interleaving commit race test passes; no probe close-under-use; A/B/C tests pass |
| LP-03 | Base rows plus pending/known/not-applicable badge enrichment, single-owner derived badge projection (P1-5), and removal of eager terminal store opens (P2-8) | LP-01, LP-02 | Medium | First rows independent of status; non-downloaded rows resolve immediately; no eager terminal store opens |
| LP-04 | Scoped refresh and lazy terminal details | LP-03 | Medium | Unrelated events cause no new reads; restarted terminal details stay actionable |
| LP-05 | Shared reader opening for conditional non-download duplication, extending status derivation to entered chapter (P1-3) | LP-00, LP-02 | Medium | One open on relevant path; reader status derives from in-flight open without lock contention |
| LP-06 | Early stable enumeration, actual landing-key selection, and unified `ChapterPageKeyResolver` (P2-4) | LP-05 | Medium | Key/landing/directory/archive parity tests across odd filenames and zero padding |
| LP-07 | Progressive opening with unchanged recovery, replay snapshot memoization (P1-4), 5-layer lock tenure enforcement (P1-3), and verified read-only bundle | LP-06, LP-00 split evidence | High | Full-store-only registry, zero duplicate snapshot reads, candidate/committed/hash and lifecycle parity |
| LP-08 | Reader holder/controller early-to-full handoff, neighbor bounds, and non-blocking teardown cleanup (P2-5) | LP-07 | High | First matched bundle frame; no stale overlay/image or regression in flash baseline; clean non-blocking chapter leave |
| LP-09 | Device comparison, failure corpus, sustained navigation and rollout report | LP-03/04 and LP-08 for relevant tracks | Medium—High | Proposed gates, mixed-activity gate, limitations and rollback verified |
| LP-X1 | Optional durable status summary / absence cache | Measured need after LP-03 | Medium—High | Typed error semantics, versioned invalidation and schema parity |
| LP-X2 | Optional replay-derived acceleration/index | Measured need after LP-08 / LP-00 | High | Separate reviewed recovery design and old/new reducer parity |

# Review and rollout

1. Start from a refreshed revision check and clean isolated implementation branch/worktree; rebase the plan's source assumptions if current code changed.
2. Land diagnostics and each list/cache package independently with targeted checks.
3. Keep presentation enrichment and progressive reader selection behind separate internal experiment controls during validation.
4. Run the baseline/treatment comparison for each changed dependency. Keep diagnostic overhead equivalent between variants.
5. Use the existing full opener as the progressive-reader fallback. All writer admissions continue to rely on its complete recovered state.
6. Enable progressive entry only after correctness and eligible-reader performance gates pass. Leave unsupported/untrusted cases on full open until explicitly covered.
7. Deliver an independent code review and changeset walkthrough with source revision, measured milestones, resource behavior, failures tested and known limitations.

# Rollback and data compatibility

- Initial phases change process-local presentation, caches and scheduling; no new artifact/journal schema is required.
- Turning off list enrichment restores the old synchronous badge path; clear affected process-local cache state so stale versions do not survive a toggle.
- Turning off progressive entry routes future opens to the existing full opener. An opening already in progress completes or cancels under its owner; do not replace its locks or close a borrowed store mid-use.
- Version invalidation can conservatively fall back to namespace clearing if an unhandled mutation identity is discovered.
- Preserve recovery data, committed pointers, backups and journals; rollback never deletes translations to recover performance.

# Risks and stopping rules

| Risk | Mitigation / stop condition |
| --- | --- |
| Stale statuses reinstalled after reset/delete | Observed-version installation check (P1-1); block rollout if race test fails |
| Probe closed while in concurrent use | Probe lease refcounting (P1-2); block rollout if concurrent probe test fails |
| Queue churn wipes warm cache | Narrow `TranslationManager` queue wipe to identity-scoped signals (P0-1); block if mixed-activity gate fails |
| Pending badge treated as untranslated | Single-owner derived projection and explicit `NotApplicable` resolution (P1-5); block until resolution is respected |
| Progressive open blocks on file lock | Pin lock tenure and derive status from opening task (P1-3); block if reader status deadlocks |
| Replay dominates opening delay | Snapshot memoization (P1-4) and LP-00 decision gate; if replay >80%, pause LP-07 to scope LP-X2 |
| Early candidate mistaken for committed output | Separate verified display/candidate APIs; exact pointer/hash/journal-outcome tests |
| Faster shell but same time to text | Primary milestone is matched image-plus-text frame; retain honest limitation |
| Memory growth from caching/progressive jobs | Bounded entries/window and lifecycle close tests; stop if repeated navigation accumulates |
| SAF parallelism slows storage or increases memory | Start sequential bounded disk resolution; parallel experiments only after evidence |
| Chapter switch deadlocks on teardown mutex | Non-blocking cancellation and lock hierarchy enforcement (P2-5) |

The final report must separate observed behavior, measured changes, rejected experiments and deferred proposals. This plan is ready to guide implementation sequencing; performance claims become valid only after the corresponding gates produce evidence.