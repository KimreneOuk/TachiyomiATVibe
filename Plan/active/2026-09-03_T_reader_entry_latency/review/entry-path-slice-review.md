# Review: reader entry latency slice (optimize_reader_lazy_loading)

**Reviewer**: Reviewer / Failure-mode Auditor
**Date**: 2026-09-03
**Scope**: commits `221f01e..520e0f0` (7 commits: sweep removal on closeAndFlush, instrumentation + JVM-safety fix, manifest/glossary read skips, wipe narrowing, document memo, registry fast path)
**Method**: `git show` per commit + full-context reads of DurableChapterStatusResolver.kt, TranslationManager.kt, ActiveChapterStoreRegistry.kt, ChapterArtifactStore.kt, LegacyChapterMigrationSource.kt, LegacyArtifactRescue.kt, StorePersistenceScheduler.kt, BatchProgressProjector.kt, ChapterDataResetController.kt, ReaderTeardownCoordinator.kt, LegacyFlatFileDecoder.kt, TranslationProvider.kt, BatchChapterTranslator.kt, ChapterTranslator.kt, DownloadPageLoader.kt.

---

## Verdicts per review question

### Q1 — Wipe narrowing safety: CONCERN (one real hole found; other two gates sound)

The invalidation protocol requires every durable transition to be followed by `clearDurableStatusCache()` before a later read can serve the stale entry. Post-slice wipe sites: probe **creation** (DurableChapterStatusResolver.kt:222-230), queue **membership change** (TranslationManager.kt:249-262), fresh open when `!hadActive` (TranslationManager.kt:1188-1223), unconditional sites retained at TranslationManager.kt:1165 (`getChapterTranslation(file)` artifact path), 1313/1320 (register/unregister active store), and the five delete/reset sites (ChapterDataResetController.kt:170, 250, 286, 361, 428) plus reader teardown (ReaderTeardownCoordinator.kt:100, 130, 159 → unregister → wipe).

**1a. Probe-creation gating — PASS.** A reused probe (`ProbeResult(it, owned=true)`, created=false — ActiveChapterStoreRegistry.kt:127, 134) exists only while another concurrent probe pass holds it; probes are removed in `releaseProbe` in the `finally` (DurableChapterStatusResolver.kt:236-243). The rescue happens inside `create()` (i.e. `ChapterTranslationStore.open/openArtifact`) and the wipe at DurableChapterStatusResolver.kt:228-230 runs **after** `getOrCreateProbe` returns, i.e. strictly after the open that may have advanced durable truth — a status cached in that window is cleared by the wipe. Probes adopted from `fileStores` (owned=false) performed no open. The active-store early return (DurableChapterStatusResolver.kt:212-214) never wiped before this slice either (verified against `git show 221f01e:...`), so nothing regressed there.

**1b. Queue-membership gating — CONCERN (F1).** The queue-first claim itself is VERIFIED: `BatchProgressProjector.getChapterTranslationStatus` answers from `getQueuedTranslationOrNull` before any durable lookup (BatchProgressProjector.kt:131-132), and batch completion orders final durable writes (`store.flush()` at BatchChapterTranslator.kt:658 + NonCancellable flush at 678) **before** returning, after which the queue entry is removed → membership change → wipe. So the normal batch-completion transition is ordered correctly. The hole: `translator.queueState` is a **StateFlow** (ChapterTranslator.kt:144-145), which conflates. The collector compares set-equality of `chapter.id`s against the last seen value (TranslationManager.kt:254-260). Concrete stale-serving scenario:
1. Batch for chapter 5 completes: final manifest written; queue entry removed (membership `{}`).
2. `requeueTranslation`/`requeueExisting` (TranslationManager.kt:707, ChapterTranslator.kt:342) re-adds chapter 5 within the same collector dispatch (membership `{5}` again).
3. StateFlow conflates `{}` away; the collector sees `{5}` with `lastQueueMembership == {5}` → **no wipe**.
4. `persistedChapterStatus` for chapter 5 hits the cache and serves the pre-batch status (e.g. TRANSLATING) indefinitely — the cache has no TTL — and the document memo (F2) is likewise not dropped. Pre-slice, any probe pass or openExisting pass would have healed this; post-slice nothing does until an unrelated wipe or process death.
Likelihood LOW (needs remove→re-add of the same id inside one IO-dispatcher collector window), consequence MEDIUM (chapter permanently misreported). Minor secondary nit: membership is a `Set<Long?>`, so two distinct queued chapters both having `chapter.id == null` collapse to one element.

**1c. Registry-hit gating (`hadActive`) — PASS.** `hadActive` is read before `activeStores.getOrCreate/getOrCreateFile` (TranslationManager.kt:1191-1195); every path where `create()` actually runs (real open, possible one-way rescue / preservation-marker advance) has `hadActive == false` → wipe. Adoption paths (file store adopt, probe adopt inside the registry — ActiveChapterStoreRegistry.kt:61-72, 81-89, 108-115) perform no open; a promoted probe's creation already wiped. Deletes/refreshes/resets wipe via ChapterDataResetController; migration/rescue on other threads only happens inside an open, and every open site either has `!hadActive` or `created` or the retained unconditional wipe at TranslationManager.kt:1165. TM:1144→1165 (`getChapterTranslation(file)`) staying unconditional is the safe direction (over-wipe, slight perf cost only; its caller is the DownloadPageLoader legacy fallback, DownloadPageLoader.kt:78). Not a FAIL.

**Scenarios requested and checked**: batch completion writing manifests → covered (ordering VERIFIED above + membership wipe); page commits during reader session → same-chapter only, masked while the store is registered by the projector's live-store preference (BatchProgressProjector.kt:133-141) and wiped at teardown; glossary changes → do not move durable chapter State (status enum untouched) — no wipe needed; migration/rescue on other threads → only inside opens, all wipe-covered; deletes/refreshes → CDRC wipes; legacy decode path → `getChapterTranslationForReader` takes the legacy branch before openExisting (TranslationManager.kt:1135, 1146-1147), and the name-based fallback `getChapterTranslation` is memo-free (TranslationManager.kt:1099-1119); TM:1144 → still unconditional (safe direction).

### Q2 — Document memo staleness: CONCERN (F2), narrow but unbounded once triggered

Memoization: DurableChapterStatusResolver.kt:161-184, key (chapterName, scanlator, mangaTitle, sourceId) at :39-44. Invalidated **only** by `clearDurableStatusCache` (:87-90); negative results not memoized.

Caller enumeration and stale-memo impact:
- `resolveDurableChapterStatus` (:134-159): probes by **name** from `document.parent` (parent is a stable directory handle) → artifact chapters self-heal; legacy decode uses `document.file` handle with `if (!file.exists()) return emptyMap()` (LegacyFlatFileDecoder.kt:55) → stale handle degrades to "no status", same as a miss, and null is not cached.
- `withDurableStore` (:193-205) → `reconstructDurableTerminalSnapshot` (TranslationManager.kt:1060-1093): same shape; terminal snapshot reconstruction would serve empty/stale only in the same degraded way.
- `openExistingChapterTranslationStore` (TranslationManager.kt:1183-1187): explicit `document.file?.exists() != true && !manifestProbe.exists → null` guard; artifact path re-resolves by name via `openArtifact(parent, fileName)`.
- `openOrCreateActiveChapterTranslationStoreImpl` (:1395-1422): create lambda checks `file?.exists()` before using the handle; LAZY fallback otherwise.
- `getChapterTranslationForReader` (:1121-1152): probe by name; legacy decode by handle (degrades to empty, pre-slice identical behavior for a quarantined file).
- ChapterDataResetController (via findTranslationDocumentFn, TranslationManager.kt:1638-1639): resolves to delete, then wipes (:170 etc.) → memo dropped after mutation.

Scenarios where the memo legitimately goes stale mid-session:
- **Quarantine rename** (LegacyFlatFileDecoder.kt:66-77): renames the corrupt file, no wipe. Benign — same empty result as a fresh walk would produce.
- **File recreated at the same name** (retry/re-download): under SAF a recreated file has a **new document URI**; the memo holds the dead handle. Artifact chapters self-heal via name-based probe/openArtifact; LEGACY flat-file chapters serve empty pages until a wipe — pre-slice the next walk healed this (regression window, not permanent).
- **Manga rename / translation-root migration** (F2): the directory is title-keyed (TranslationProvider.kt:67-70, 114-116) — `getMangaDirName(mangaTitle)`. If the tree is renamed/moved without any translation-side mutation, **every memo entry for that manga keeps a dead parent forever**; each retry returns the memo without re-walking, so `persistedChapterStatus` resolves null every time (null never cached but the memo is) → all chapters of the manga read as untranslated until restart. No wipe site covers a manga rename or storage migration (no handling found: grep for storage-migration handlers touching translations returned nothing). This is the strongest Q2 scenario: no caller that skips the `document.file` existence check crashes, but the *lookup itself* never heals.
- Memory: `durableDocumentCache` is an unbounded ConcurrentHashMap of UniFile-holding entries (TranslationManager.kt:187), same growth pattern as the pre-existing `durableStatusCache` — LOW note against the bounded-memory constraint.

### Q3 — Registry fast path: PASS

`openOrCreateActiveChapterTranslationStoreImpl` fast path (TranslationManager.kt:1388-1394) returns `activeStores.get(chapterId)` directly. Verified no caller relies on document-resolution side effects: on a registry hit, `activeStores.getOrCreate` returned the existing store without ever consulting the document/probe results (ActiveChapterStoreRegistry.kt:60 — first check short-circuits); the document/manifestProbe values were consumed **only** by the create lambda, which cannot run on a hit. The skipped work (findTranslationDocument + probeArtifactManifest) never validated on-disk state on a hit before this slice — behavior is identical. `scheduleRetiredCleanedImageCleanup` is invoked on the fast path (TranslationManager.kt:1392), preserving the unconditional call previously made at the end of the body (:1424). The only genuinely dropped side effect is memo/probe warming on the hit path — perf-positive, not a dependency.

### Q4 — Glossary gating: PASS (one theoretical race noted, LOW)

`isArtifactAuthoritative` is threaded from the correct source: computed in `openInternal` from `ChapterArtifactManifestReader.probeArtifactManifest(parent, fileName)` (LegacyChapterMigrationSource.kt:49-56) — the same parent/fileName that `loadOrMigrate` reads via `layout.manifestFileName` — passed through :103 → :149-157.
- ARTIFACTS-authoritative manifests never read the snapshot: verified both loadOrMigrate consumers — fast path (:131-149) touches only `existing`; future-schema paths (:85-101) touch only the parsed documents. Fresh-migrate (:158) and rescue (:155, :175) paths read `legacy.glossary`, and both are unreachable when the primary manifest is ARTIFACTS.
- Rescue path check: `existing.authority == LEGACY` implies the probe could not have said ARTIFACTS (authority cutover is one-way), so `isArtifactAuthoritative == false` and the glossary **is still read** (LegacyChapterMigrationSource.kt:191-207) — the rescue's `attachGlossaryIfNeeded` (LegacyArtifactRescue.kt:57, 438-442) receives real data.
- LEGACY-authority + missing-translation-file edge: `legacyBytes == null` → `legacyIdentityOf(null, …) == null` → `rescueLegacy` bails at LegacyArtifactRescue.kt:44-45 (returns prior read-only, source retained). The glossary is still read in this branch (gating is on manifest authority only, not on translation-file presence) — identical outcome to pre-slice.
- Theoretical race (F6): probe says ARTIFACTS, then the primary is deleted externally before `loadOrMigrate`'s read, and a stale LEGACY **backup** is recovered (ChapterArtifactStore.kt:104-105) → rescue would run with an empty glossary and drop the prior glossary pointer (staging sets `glossary = null`, LegacyArtifactRescue.kt:50-51; empty-glossary branch keeps `priorPointer = null`). Requires an external deletion between two reads milliseconds apart; pre-slice code would have read the glossary unconditionally and survived this race. LOW likelihood; noting only.

### Q5 — loadOrMigrate conditional re-read: PASS

`existing` is bound **before** the guard (ChapterArtifactStore.kt:104-106: `val existing = primary ?: recoverPrimaryFromBackupOrNull(backup)`); the guard is at :117. When `primary != null`, the old unconditional re-read could only (a) succeed — a no-op — or (b) fail because the primary was deleted between the two reads, in which case the old code logged a WARN and returned the **in-memory** primary read-only without publishing; the new code instead falls through and may publish recovery from that in-memory primary. No freshness dependency exists: a concurrent publish between the first read and the guard was never picked up by the old code either (`existing` was already bound), and the freshly published primary is picked up on the next load. Behavior change is confined to a crash-race deletion window where the new code attempts recovery-publication instead of returning read-only — the backup remains the crash-safe net. VERIFIED against the pre-change code path.

### Q6 — Test adequacy: CONCERN (core invalidation logic unpinned)

Pinned well: memo positive-hit across resolver instances, clearDurableStatusCache invalidation, negative-not-memoized (DurableDocumentMemoTest.kt); probe `created` flag for create/reuse/adopt (ActiveChapterStoreRegistryTest.kt:145-168).
Unpinned, in priority order:
1. **The resolver consuming the flag** — `if (result.created) durableStatusCache.clear()` (DurableChapterStatusResolver.kt:228-230) has no test; the whole Q1a gate rests on this one line. A test driving `persistedChapterStatus` twice with a shared probe registry asserting one wipe would pin it.
2. **Re-creation after release** — `releaseProbe` then a new `getOrCreateProbe` must report `created = true` again (per-creation, not per-store). The existing test only covers create → reuse → adopt.
3. **Memo invalidation on delete flows** — no test that `deleteTranslation`/reset drops the *document* memo (the CDRC wipe clears both caches only because they share one method; nothing pins that coupling).
4. **The F1 queue collector** — no test at all for the membership-gating collector, including the conflation scenario.
5. Put-after-clear race on the memo (concurrent `findTranslationDocument` re-inserting after a clear) — unpinned, inherent to ConcurrentHashMap; document as accepted limitation or fix with a generation counter.

### Q7 — closeAndFlush sweep removal: PASS

All production `closeAndFlush()` callers enumerated: registry adoption-losers (ActiveChapterStoreRegistry.kt:69, 86, 112 — closing a losing duplicate that performed no reads the winner didn't) and the probe `finally` (DurableChapterStatusResolver.kt:236-243). None depended on retention reconciliation for correctness — the sweep is orphan-file cleanup; manifests never reference orphan files. Retention still runs at the batch chapter-completion boundary (BatchChapterTranslator.kt:680) and in `StorePersistenceScheduler.close()` (async, StorePersistenceScheduler.kt:119-128; `store.close()` has no production callers found, so that path is effectively dormant). Consequence (F4): orphaned artifact sidecars can now accumulate for chapters that are only ever read/probed, never batch-translated — a storage design limitation explicitly owned by the event-driven retention follow-up (root-cause-deduction.md addendum, "Sweep call sites remaining"), not a stability defect. Normal-manga read path is unaffected.

---

## Findings summary (severity / likelihood / classification)

| ID | Finding | Severity | Likelihood | Class |
|----|---------|----------|------------|-------|
| F1 | Queue-membership gating can miss a remove→re-add conflation in `queueState` (StateFlow) → durable status + document memo stay stale indefinitely for that chapter (TranslationManager.kt:254-260) | MEDIUM | LOW | Defect (race) |
| F2 | Document memo never heals after a mid-session manga-rename / translation-root move; LEGACY flat-file chapters serve empty until a wipe after same-name recreation (DurableChapterStatusResolver.kt:161-184) | MEDIUM | LOW | Design limitation |
| F3 | `durableDocumentCache` unbounded (UniFile-holding entries) | LOW | — | Design limitation (bounded memory) |
| F4 | Retention orphans accumulate for probe/read-only chapters after sweep removal from open+closeAndFlush paths | LOW-MEDIUM | over time | Design limitation (documented follow-up) |
| F5 | Invalidation core (`created`-gated wipe, delete→memo coupling, queue collector) untested | MEDIUM | — | Test gap |
| F6 | Probe/read authority race can skip glossary read then run a LEGACY rescue with empty glossary (probe ARTIFACTS → primary externally deleted → LEGACY backup recovered) | LOW | VERY LOW | Design limitation |
| F7 | loadOrMigrate skip re-read: in a primary-deleted-mid-load window, new code may publish recovery where old code returned read-only | LOW | VERY LOW | Accepted behavior change |

## Evidence classification

VERIFIED (code/primary): queue-first projection (BatchProgressProjector.kt:131-132); batch flush-before-return ordering (BatchChapterTranslator.kt:654-680); pre-slice withProbeStore shape (`git show 221f01e:…`); title-keyed dirs (TranslationProvider.kt:114-116); closeAndFlush caller set; loadOrMigrate `existing` binding order; rescue glossary gating; quarantine rename (LegacyFlatFileDecoder.kt:66-77).
STRONG INFERENCE: StateFlow conflation miss (F1) — conflation semantics are certain, the remove→re-add window is inferred from real requeue APIs (ChapterTranslator.kt:342).
ASSUMPTION: manga rename/storage migration actually relocates the translation tree without a wipe (no handling code found; device behavior not exercised).
UNKNOWN: frequency of same-chapter requeue inside one collector dispatch.

## Overall verdict: ACCEPT-WITH-NOTES

The slice achieves its goal (probe/open paths no longer sweep or re-walk SAF; sequential chapter-list status resolution no longer thrashes the cache to one entry) without breaking reader stability; all delete/reset/teardown transitions remain wipe-covered and the artifact path self-heals via name-based probes. Two items should be follow-ups on this task, not blockers:

1. **F1 minimal fix**: make the collector conflation-immune — e.g. have `ChapterTranslator` maintain a monotonically increasing `queueMembershipVersion: StateFlow<Long>` bumped on every membership mutation, and collect that comparing `!=` on the Long (any skipped intermediate version still differs → wipe; over-wipes at most). Or revert the collector to wipe-on-every-emission if version plumbing is unwanted (cost: one clear per progress tick, the original behavior).
2. **F5**: add the resolver-level `created`-gate test and a re-create-after-release registry test before the instrumentation is trimmed.
3. Record F2/F3/F4/F6 as accepted limitations in the task README / retention follow-up scope (F2's worst case should get a one-line mitigation in the retention follow-up, e.g. drop memo entries whose `parent` fails a cheap `exists()` check on resolve).

---

## Disposition (Main Leader, 2026-09-03 evening)

- **F1 (Q1) — FIXED by reverting the membership gate.** Verified against ChapterTranslator emission sites (`_queueState.value/update`: 206, 211, 354, 785, 794, 806, 831) that **no batch progress tick emits queueState** — only real membership mutations plus the arm-after-pause same-membership re-emission at :354. The audit premise ("wipes on every emission including progress ticks") was wrong, so the gate bought nothing and cost the conflation hole. Collector restored to wipe-on-every-emission (the Reviewer's sanctioned alternative). Net: Q1 closed with less code.
- **Full-suite failures (9) triaged and fixed:**
  - 7× NPE `getDurableDocumentCache() is null`: the Unsafe-built test managers never ran field initializers; the three Unsafe harnesses (ArtifactRead, DeleteResetOrdering, ReaderTeardown) now set `durableDocumentCache` like they already did `durableStatusCache`.
  - ChapterArtifactStoreTest orphan-temp failure: **pre-existing regression from 722e955** (hotfix removed `reconcileRetention` from the whole open path, over-removing). Restored the pre-hotfix ≤8-page synchronous sweep (small chapters only; the 56.7s large-chapter serializer stays dead). Retention is now: ≤8-page opens sweep sync (cheap), batch-completion boundary (680) and StorePersistenceScheduler.close cover the rest; large read-only chapters defer to the event-driven retention follow-up (F4 stands for them).
  - D1OriginPriorityTest `UncaughtExceptionsBeforeTest`: downstream pollution from the NPEs (leaked coroutine on a SupervisorJob-built manager scope); passes in isolation and after the harness fix.
- **F5/Q6 — deferred** to a follow-up commit after device validation (resolver-level created-gate drive + re-create-after-release + delete→memo coupling). F1's fix makes the created-gate a cache-thrash optimization, not a correctness gate, which lowers its priority.
- **F2/F3/F4/F6/F7 — accepted** for the task README / retention follow-up scope as recommended.

