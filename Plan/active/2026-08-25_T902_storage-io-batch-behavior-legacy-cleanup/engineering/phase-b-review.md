---
kind: review
title: "T902 Phase B independent review"
---

# T902 Phase B — Independent Review

Reviewer: T902 Phase Reviewer (independent; same session as the Phase A review).
Scope: uncommitted working-tree diff relative to `901c721` (Phase A commit),
production + test files under `eu/kanade/translation*`. AGENTS.md / docs / Plan
churn excluded (main-lead owned). All file:line references are the current
working tree.

## Final verdict: APPROVE

The storage-I/O work is sound and well tested: summary production removal is
complete, the glossary moved to the atomic artifact path, manifest
registration is coalesced, promotion no longer double-writes identical
candidates, live retention sweeps are deferred to serialized boundaries, and
the probe registry closes the Phase A O1 retention gap. The final delta also
resolves the original status-contract and cleanup-race findings; it is ready
for the Phase B commit gate.

---

## 1. Findings (priority order)

### R1 — REQUIRED — False TRANSLATED certification for partial chapters without a trusted baseline

`ChapterTranslationStore.artifactStatus()` (ChapterTranslationStore.kt:1636-1649)
falls back to `manifest.expectedPageCount ?: manifest.pages.size`. Combined
with the baseline capture in `registerPageArtifactLocked`
(ChapterTranslationStore.kt:1215-1243: `maxOf(manifest.expectedPageCount ?: 0,
pendingExpectedPageCount ?: pages.size)`), chapters whose true page total was
never recorded are certified TRANSLATED as soon as every *touched* page is
terminal-done:

- **Reader/manual chapters** — `preRegisterPages` has exactly one caller, the
  batch path (ChapterTranslator.kt:466). Reader chapters never register the
  chapter total, so the baseline becomes pages-so-far: translate 1 page of a
  30-page chapter → `expectedPageCount = 1` → status TRANSLATED (live branch
  TranslationManager.kt:413-418 once display-ready; durable probe after
  restart likewise). Old behavior: reader chapters deliberately never
  certified (summary `READY_WITH_WARNINGS`, removed code at old
  TranslationPipeline.kt:3448-3466; T901 UI D5).
- **Pre-Phase-B ARTIFACTS chapters** — manifests written before this change
  have `expectedPageCount == null`; an interrupted 5-of-10 chapter has 5
  records → fallback 5 → TRANSLATED. Old: READY_WITH_WARNINGS (no summary).
- **Migrated LEGACY chapters** — flat JSON holds only translated pages;
  migration (probe open → `loadOrMigrate`) records those pages, baseline
  falls back to that count → partial legacy chapter certifies TRANSLATED.

The removed summary was the only completion oracle; the manifest baseline
replaces it only when the batch pre-registered. Fail-safe direction should be
the opposite: **no trusted baseline ⇒ cannot certify TRANSLATED** (return
READY_WITH_WARNINGS when any page is readable and expectedPageCount is absent
or was never explicitly set). Suggested fix shape: distinguish
"`expectedPageCount` explicitly captured" from "derived fallback" (e.g. keep
the fallback out of the manifest entirely — derive a *warnings* result when
`manifest.expectedPageCount == null`), and for reader-first capture, record
the true total (reader page-list size at store open) instead of pages-so-far.

Note: the new test `artifact authority status and reader reads survive a fresh
manager` (TranslationManagerArtifactReadTest.kt:118-141) uses a genuinely
complete 1-page chapter — it cannot distinguish correct certification from
overcertification; that is why this escaped the suite.

### R2 — REQUIRED — ERROR status while work is in flight (reconciler reused for live status)

`getChapterTranslationStatus`'s live branch now returns
`store.artifactStatus()` (TranslationManager.kt:413-418), but
`BatchProgressReconciler.reconcile` treats any non-terminal page as stranded
→ `failedCount++` → chapter ERROR (BatchProgressReconciler.kt:71-77:
`isStageCancelled || isStageRunning || isNonTerminalWithoutOutput()` →
stranded; :85-88 `failedCount > 0 → ERROR`). The reconciler was designed for
the batch boundary (post-join, all work wound down). Consequences:

- Reader translating page N of a chapter with an earlier committed page:
  mid-flight RUNNING page (with an open candidate, hence a manifest record)
  → live badge **ERROR** for the duration of the work. Old: READY_WITH_WARNINGS.
- First-ever page translation after the candidate opens durably: no
  display-ready gate → durable probe → active store → ERROR. Old:
  NOT_TRANSLATED.
- Re-translate/refresh of a page in a finished chapter: demote + RUNNING →
  ERROR until the new commit lands. Old: TRANSLATED throughout (summary).

Status flaps NOT_TRANSLATED/TRANSLATED → ERROR → … are user-visible on every
manga-screen badge update (`observeChapterTranslationStatus` recomputes on
every store emission). Fix shape: when used for *status* (not batch
terminalization), pages with running stages / open candidates / held leases
must count as "in progress", not stranded-failed — e.g. gate on
`scheduler.isPageActive`-equivalent store state or treat non-terminal pages as
warnings while any page is RUNNING.

### R3 — SHOULD FIX (one-liner) — `getOrCreateFile` can hand out a probe-owned store that a concurrent probe then closes

`ActiveChapterStoreRegistry.getOrCreateFile`
(ActiveChapterStoreRegistry.kt:98-108) returns `probeStores[fileKey]`
directly — without `takeProbe` and without `registerFile`. The store stays in
`probeStores`; a concurrent `withProbeStore` → `releaseProbe`
(ActiveChapterStoreRegistry.kt:139-148) then sees
`probeStores[fileKey] === store`, not present in `stores`/`fileStores`, removes
it and returns true → caller runs `closeAndFlush()` → `persistScope.cancel()`
on a store a reader still holds (openExistingChapterTranslationStore with
`chapterId == null`, TranslationManager.kt:636-638; `getChapterTranslation(file)`
:614-616). For ARTIFACTS chapters durable writes still work (synchronous
bridge), but the store is left with a cancelled persist scope and dueling
lifecycles. Reachability is narrow (id-less fallback paths), the fix is
trivial: mirror the `getOrCreate` takeProbe branch (take + `registerFile`).
The chapter-keyed path is already correct; the new test
(`durable probe is evicted unless an active chapter adopts it`,
ActiveChapterStoreRegistryTest.kt:128-138) covers adoption via `getOrCreate`
but not via `getOrCreateFile`.

### R4 — DECISION — Interrupted-and-dequeued chapters now read ERROR (was READY_WITH_WARNINGS)

With a batch-registered baseline (expectedPageCount = 10) and 5 done pages,
restart + queue removal (clear queue / replace chapter) → 5 synthetic
`__missing_expected_page_*` keys (ChapterTranslationStore.kt:1641-1643) →
failed → ERROR. Old: READY_WITH_WARNINGS (no summary published mid-batch).
ERROR is arguably more honest for an incomplete chapter, but it is a visible
semantic change the Director did not explicitly request. Recommend confirming
intent; if unwanted, missing-expected pages should map to warnings unless a
durable failure exists.

### R5 — SHOULD FIX (small) — Orphan sweep can delete a just-written cleaned image inside the write→commit window

`sweepOrphanedCleanedImages` (TranslationManager.kt:918-948) protects
referenced names, active leases, and `mayDeleteCleanedImage`, but
CleanedImagePublisher's ordering is file-write **before** store commit; in
that window the new name is in none of the protective sets
(`referencedCleanedImageNames`, ChapterTranslationStore.kt:1487-1497, reads
only live/committed/retired/manifest display bases). Chapter-open sweeps can
run concurrently with an in-flight batch on the same chapter (reader re-entry
resolves the same active store). Result: freshly written image deleted, then
display promotes to a missing file (reader FileNotFound). Narrow, but the
cheap guard is a freshness window: skip files whose `lastModified()` is within
N seconds. Note also `take(64)` applies before the referenced/lease filters,
so referenced files consume sweep budget (harmless, just less effective).

### R6 — FOLLOW-UP (perf) — Durable status now fully reopens stores per cache miss

With the summary fast path removed, every durable-status cache miss for an
ARTIFACTS chapter opens the complete store — rehydrating all page snapshots
(`openInternal` → `migrateArtifactManifest` → `readPageSnapshot` per record,
ChapterTranslationStore.kt:2090-2103) — and `withProbeStore`'s
`closeAndFlush` additionally runs a retention directory walk
(ChapterTranslationStore.kt:1862-1866). On the first manga-screen build after
restart this is O(chapters × pages) SAF reads (a 200-page × 50-chapter manga ≈
10k file reads), a measurable regression vs Phase A's bounded summary read.
The `durableStatusCache` (TranslationManager.kt:448-458) keeps repeat reads
cheap, so this is restart-path only. Recommend a manifest-only status fast
path (PageArtifactRecord already carries per-stage StageArtifactRecords and
committed pointers) in Phase C when the summary store is deleted.

---

## 2. Checklist verdicts (requested areas)

| Area | Verdict | Evidence |
|---|---|---|
| Crash safety / atomic publish | PASS | No changes to `AtomicChapterDocuments` semantics. Glossary now atomic via `publishGlossary` + manifest pointer (ChapterTranslationStore.kt:1710-1722); legacy glossary via `documents.publishJson` (:1724-1733) — closes T901 D10. Summary store converted to atomic `publishJson` (ChapterTranslationSummaryStore.kt:44-49) with zero production callers (grep: only its own definition). Manifest publication failures keep fail-closed returns (`registerPageArtifactLocked` returns false; preRegisterPages retries via retained pending set). |
| expectedPageCount non-shrinking | PASS (mechanics) / FAIL (semantics, → R1) | `maxOf(pending ?: 0, …)` never shrinks (ChapterTranslationStore.kt:1070, 1218-1221); `resyncManifest` preserves the max and nulls zero (LegacyArtifactMigration.kt:97-103); additive manifest field (ChapterArtifactManifest.kt:22). The defect is what counts as a baseline, not the max logic. |
| Chunk-boundary + NonCancellable glossary flush; reader cadence unchanged | PASS | Batch finally: `withContext(NonCancellable) { store.flush() }` then `reconcileArtifactRetention()` + `onBatchClosed` (TranslationPipeline.kt:2736-2743); flush under store mutex serializes against glossary writes. Reader single-page path unchanged except summary removal (:3446-3466 deleted); `NonCancellable` import only used in the finally. |
| Manifest coalescing / promotion reuse | PASS | Pending registrations batched into the next publication (ChapterTranslationStore.kt:1215-1243); promotion skips rewriting an identical candidate via `readValidated` equality (ChapterArtifactStore.kt:397-403); test pins no candidate tmp write, single committed tmp write, no retention listing during promote (ChapterArtifactStoreTest.kt:677-733). Corrupt/unreadable candidate → re-publish (fail-safe). |
| Deferred retention | PASS | Live promote/demote/deletePage no longer sweep (ChapterArtifactStore.kt:461-538); sweeps retained at load/recovery (:867, :934 dead stage APIs keep theirs, per plan) and added at serialized boundaries — `close`/`closeAndFlush` (ChapterTranslationStore.kt:1856-1873) and batch end. Crash between commit and sweep leaves bounded orphans cleaned at next open — deletion is optional cleanup, no correctness dependence. |
| Probe ownership / release | PASS with R3 | `getOrCreateProbe`/`releaseProbe`/`takeProbe` correctly re-home an adopted probe for chapter-keyed opens (ActiveChapterStoreRegistry.kt:111-171); release is a no-op once adopted; O1 (Phase A follow-up) is genuinely closed — probes close immediately after resolution. The `getOrCreateFile` hole is R3. |
| Orphan sweep protection | PASS with R5 | Referenced-name set covers live/committed/retired/manifest display bases (ChapterTranslationStore.kt:1487-1497); lease check sums readers across page keys (TranslationStreamRegistry.kt:285-300); `mayDeleteCleanedImage` consulted per page (TranslationManager.kt:937). Write→commit window unprotected → R5. |
| Summary removal completeness | PASS | Zero production references to `publishSummary`/`readSummary`/`ChapterTranslationSummaryStore`/`ChapterTranslationSummary(` outside the store's own file (grep). `deleteTranslation` no longer purges the sidecar (TranslationManager.kt:1216-1222) — stale files ignored until Phase C, as documented. |
| Legacy rescue boundary | PASS | LEGACY flat decode preserved for status/reader with quarantine (TranslationManager.kt:524-531, 560-577); artifact-only chapters with no flat file resolve via `openArtifact` + `findTranslationDocument` fallbacks (:594-640); lazy artifact-only stores never materialize the flat file (test: `artifact-only lazy store writes glossary without materializing flat compatibility file`). Migration write-back on probe open is the existing rescue design. |

## 3. Verified positives

- B1 achieved: no empty flat files for fresh chapters — lazy stores carry
  `artifactParent/artifactFileName` and bootstrap the manifest without the
  flat sibling (ChapterTranslationStore.kt:1362-1380; pinned by two new
  migration tests).
- `resetOcrData` now flushes immediately after `deletePage`
  (TranslationManager.kt:1483-1486) — closes a durability gap.
- `ChapterTranslator` terminal status no longer depends on the summary sidecar
  (ChapterTranslator.kt:499-505) and batch pre-registration feeds the
  reconciler the true expected set — batch terminal statuses are correct.
- Test quality: promotion-dedupe and probe-eviction tests assert *mechanism*
  (write counts, listings), not just outcomes.

## 4. Independent validation

- Focused 4-class run (my execution): 55/55 PASS — matches the implementer's
  claim exactly (4 + 16 + 5 + 30).
- Full-suite/spotless/domain claims (999/1 known AotReportBubbleFillTest,
  spotless, domain 68) accepted from the implementer's runs; consistent with
  my Phase A reruns on this machine and no new test-affecting production code
  touched since.

## 5. Re-review — all commit blockers resolved

| Finding | Re-review evidence | Result |
|---|---|---|
| R1 — false completion without a trusted total | `expectedPageCountTrusted` is additive and defaults to `false` in `ChapterArtifactManifest.kt:22-25`; migration preserves the maximum count and ORs trust (`LegacyArtifactMigration.kt:100-105`). Reader-first registration persists only an untrusted baseline (`ChapterTranslationStore.kt:1226-1238`), while `artifactStatus` calls `takeIf { expectedPageCountTrusted }` before it can reconcile to `TRANSLATED` (`:1685-1687`). Restart coverage expects `READY_WITH_WARNINGS` with an untrusted migrated manifest (`TranslationManagerArtifactReadTest.kt:129-140`); a trusted batch completion alone certifies (`ChapterTranslationStoreArtifactMigrationTest.kt:264-281`). | RESOLVED |
| R2 — in-flight pages read as ERROR | `artifactStatus` now checks durable failures first, then recognizes candidate/RUNNING pages as in-flight and reports warning/null—not ERROR—before terminal reconciliation (`ChapterTranslationStore.kt:1666-1684`). A real durable failure remains ERROR; the focused fixtures assert both outcomes (`ChapterTranslationStoreArtifactMigrationTest.kt:284-301`, `TranslationManagerArtifactReadTest.kt:167-189`). | RESOLVED |
| R3 — file-keyed probe close race | `getOrCreateFile` takes the probe and registers it as the file owner while holding the common `openingLock` (`ActiveChapterStoreRegistry.kt:104-116`). A later `releaseProbe` therefore cannot close the adopted store. The dedicated concurrent adoption test passes (`ActiveChapterStoreRegistryTest.kt:145-170`). | RESOLVED |
| R4 — interrupted/dequeued batch semantics | Missing expected keys are still synthesized, but an ERROR caused solely by those missing/non-terminal placeholders becomes `READY_WITH_WARNINGS`; a persisted failed stage remains ERROR (`ChapterTranslationStore.kt:1689-1701`). This implements the Director’s decision and is directly asserted (`ChapterTranslationStoreArtifactMigrationTest.kt:284-301`). | RESOLVED |
| R5 — cleaned-image write→commit race and cap | The 30-second freshness grace treats unknown/zero timestamps conservatively (`TranslationManager.kt:61-65`). The sweep filters references, active leases, page protections, and freshness **before** `.take(64)` (`:935-945`), so its cap applies to eligible deletion candidates. Boundary tests cover fresh, expired, and unknown timestamps (`TranslationManagerArtifactReadTest.kt:192-198`). | RESOLVED |

The legacy read/rescue boundary remains intact: ARTIFACTS status/reader paths
probe and rehydrate manifests; LEGACY status remains warning-only from flat
JSON and corrupt files are quarantined. Summary files remain ignored with no
production reads or writes.

## 6. Independent validation after fixes

- Focused review suite: 61/61 passed — `TranslationManagerArtifactReadTest`
  (6), `ChapterTranslationStoreArtifactMigrationTest` (18),
  `ActiveChapterStoreRegistryTest` (7), and `ChapterArtifactStoreTest` (30).
- Translation slice (`--tests 'eu.kanade.translation.*'`): 945 tests; its only
  failure was the known, pre-existing `AotReportBubbleFillTest` pixel mismatch.
  `BatchTranslateBlockMergeTest` passed 2/2.
- `git diff --check` passed.

Final recommendation: open the Phase B commit gate. R6 (a manifest-only
status fast path) remains a non-blocking Phase C performance follow-up.
