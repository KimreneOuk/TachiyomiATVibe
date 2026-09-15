# T928 slice `io` — Durable I/O audit of the translation pipeline

Audit base: branch `main` @ `9c19ad0`, tracked files only. All paths relative to
`app/src/main/java/eu/kanade/` unless noted. Every behavioral claim cites
`file:line` read at HEAD; claims are marked VERIFIED (read directly) / DERIVED
(follows from verified code) / SUSPECTED (needs runtime proof).

---

## (a) End-to-end layer trace

### 0. The I/O substrate every path shares

**Document layer — `translation/artifact/ChapterDocumentIo.kt`**

- `UniFileChapterDocumentIo` implements every operation over a `UniFile` manga
  directory; `write()` creates parents, opens an output stream, writes bytes,
  `flush()`es — no fsync/`FileChannel.force` anywhere in the translation I/O
  code (grep for `force()`/`fsync`/`sync()` over `eu/kanade/translation/`
  returns nothing) — ChapterDocumentIo.kt:141-148. VERIFIED. Platform-level
  durability of that flush is therefore DERIVED-weakest (page cache, not disk).
- `renameNoReplace` uses raw-file `Files.move` without `REPLACE_EXISTING` and
  returns `UNSUPPORTED` on URI/SAF backends; `renameOwned` allows replacement
  via `UniFile.renameTo` — ChapterDocumentIo.kt:150-189. VERIFIED.

**Atomic publish — `AtomicChapterDocuments.publish` (ChapterDocumentIo.kt:236-255)**

Every durable document (manifest, sidecar, glossary, ledger) goes through one
sequence. VERIFIED:

1. write `name.tmp` (full bytes),
2. re-read `name.tmp`, byte-compare, JSON-validate,
3. delete stale `name.bak`,
4. rename primary → `name.bak` (if primary exists),
5. rename `name.tmp` → name.

= **5 backend ops + 2 full payload transfers per publish** (write + read-back).
Crash windows: pre-rename leaves an orphan `.tmp`; between renames leaves the
prior version in `.bak`; `readValidated` restores `.bak` and quarantines a
corrupt primary as `name.corrupt` (ChapterDocumentIo.kt:213-355). VERIFIED.

**Manifest publication — `ChapterArtifactStore.publishManifestInternal`
(artifact/ChapterArtifactStore.kt:1791-1809)** + CAS
(`staleManifestRejection`, ChapterArtifactStore.kt:1641-1649). VERIFIED:

- caller-level CAS: read + parse the FULL primary manifest,
- future-schema guard: read + parse primary again,
- `futureBackupPresent()`: read + parse the backup,
- then the 5-op atomic publish.

= **~3 full manifest reads + 5 write-path ops ≈ 8 backend ops per manifest
publication**, with manifest size O(chapter page count) every time
(`pages: Map<String, PageArtifactRecord>` — artifact/ChapterArtifactManifest.kt:16-75). VERIFIED.

**Store transaction bridge — `ChapterTranslationStore.persistArtifactMutationLocked`
(ChapterTranslationStore.kt:1912-2098).** Every durable store write fans out
into up to 4 artifact transactions: registration manifest publish (:1947-1968),
`cancelLiveCandidate` (:2013), `openCandidate` (:2020), `persistLiveCandidate`
(:2041-2063), then `promoteLiveCandidate` for display-ready pages (:2075-2095). VERIFIED.
All of this executes **while holding the `ChapterTranslationStore` mutex**
(`publishLocked` at :1876 is only called inside `mutex.withLock` bodies, e.g.
`patchPage` :579, `updatePageGuarded` :667) and inside `@Synchronized`
ChapterArtifactStore methods (20 `@Synchronized` entry points). So **every disk
round trip serializes behind the store mutex**. VERIFIED.

### 1. MANUAL path (reader per-page tap)

Trigger: `ReaderActivity`/`PagerPageHolder` → `ReaderViewModel.translateSinglePage`
(ui/reader/ReaderViewModel.kt:2202) → scheduler `translatePage` → pipeline.

| Stage | Class/function | file:line | Durable I/O |
|---|---|---|---|
| Admission / lease | `translateSinglePage` → `acquireReaderPageLease` (INTERACTIVE priority) | TranslationPipeline.kt:392-429 | none |
| Native lane admission | `withNativeLane` → `EngineLane.withNativeLane` (single permit) | TranslationPipeline.kt:251-258, 554-590 | none |
| Decode | `PageDecode.decodePageBitmapForTranslation` | TranslationPipeline.kt:1473-1474 | none |
| OCR start placeholder | `updatePageFromCurrentSnapshot("single-page OCR start")` | pipeline/SinglePageOnnxPhase.kt:515-528 | memory-only if page already in manifest; else registration manifest publish (ChapterTranslationStore.kt:1884, :1947-1968) |
| Detect+OCR merge | `store.mergeOcr(OcrStagePatch)` | SinglePageOnnxPhase.kt:928-942; ChapterTranslationStore.kt:875-973 | durable (blocks non-empty passes `shouldPersistUpdate`, ChapterTranslationStore.kt:2522-2548): `openCandidate` (generation record sidecar + manifest) + `persistLiveCandidate` (candidate snapshot sidecar + manifest) |
| Inpaint | fused in `processSinglePage` (recognize) | SinglePageOnnxPhase.kt:533 | in-memory bitmap |
| Cleaned publish | `persistOnnxCleanedImage` → `CleanedImagePublisher.publish` | TranslationPipeline.kt:646-662; pipeline/CleanedPublication.kt:96-200; CleanedImagePublisher.kt:24-87 | JPEG encode+write+verify (~3 ops, CleanedPublication.kt:121-133) + `patchPage("publish cleaned image")` → persistLiveCandidate (candidate + manifest) |
| Translate (HTTP) | `translateSinglePageHttpRender` → contextual/plain translator | pipeline/SinglePageHttpRenderPhase.kt:161-533 | attempt ledger record/resolve (store.mutex; fail-open) |
| Glossary fold | `store.updateGlossary` | SinglePageHttpRenderPhase.kt:449; store/ChapterGlossaryStore.kt:55-99 | **new glossary sidecar version + dir listing + manifest publish** per page (ChapterGlossaryStore.kt:78-92; ChapterArtifactStore.kt:239-256, :1818-1823) |
| Render (layout/colors) | `RenderColorEstimator.recomputeFor` | SinglePageHttpRenderPhase.kt:560-568 | none |
| Final commit | `store.patchPage("commit single-page translation and render")` | SinglePageHttpRenderPhase.kt:718-734 | persistLiveCandidate (candidate + manifest) + **promoteLiveCandidate** (candidate read-back compare, committed snapshot, generation record, manifest — artifact/ChapterArtifactStore.kt:1188-1318) |
| Publication to UI | `promoteDisplayIfReadyLocked` → `_display` StateFlow | ChapterTranslationStore.kt:1890, :2176-2199 | none (in-memory) |
| Tail | `deleteRetiredCleanedFile`, `store.flush()`, stream clear | SinglePageHttpRenderPhase.kt:748, :765-779 | 0-1 deletes; `flush()` is a **no-op** under ARTIFACTS authority (store/StorePersistenceScheduler.kt:74-85) |

**Per fresh manual page: ~7 manifest publications + ~7 sidecar publishes + 1 JPEG
+ 1 candidate read-back ≈ 95-100 backend ops** (see inventory, section b).

### 2. AUTO path (rolling auto window)

Trigger: `ReaderViewModel.handleAutoTranslation` (150 ms debounce;
ReaderViewModel.kt:1272-1279) → `handleAutoTranslationOnIo` (:1281-1330) →
`TranslationManager.updateAutoWindow` (TranslationManager.kt:1606-1636) →
`TranslationScheduler` window dispatch → `executor.translateSinglePage(...,
origin = PageWriteOrigin.AUTO)` (scheduling/TranslationScheduler.kt:401-408) or
`translateSinglePageFromStream` (:447-454).

**Divergences from manual:**

- **Same pipeline, different lease origin.** AUTO work runs the identical
  `runGrantedSinglePageBoundary` body with `PageWriteOrigin.AUTO`
  (TranslationPipeline.kt:465-716); the durable write sequence is byte-for-byte
  the manual sequence. Origin maps to durable provenance via
  `origin.toArtifactOrigin()` (SinglePageHttpRenderPhase.kt:175;
  TranslationStageContracts.kt). VERIFIED.
- `markAutoPageStarting` writes a RUNNING placeholder before dispatch
  (TranslationScheduler.kt:589-615) — memory-only for pages already registered
  (ChapterTranslationStore.kt:2522-2548 gate). VERIFIED.
- Denied lease → attach-and-observe instead of a competing writer
  (TranslationPipeline.kt:729-750); zero I/O. VERIFIED.
- Batch suppression: auto windows are suppressed while a chapter batch is
  retained (TranslationManager.kt:1579-1590, :1615-1626). VERIFIED.
- Per-page I/O cost: same as manual (~95-100 ops) for fresh pages; resume/render-only
  paths (workPlan skip, SinglePageOnnxPhase.kt:294-331) reduce it to the render
  tail's one `persistPageWithOomRecovery` (candidate + manifest + possible promote).

### 3. BATCH path

Two coordinators exist. `BatchChapterTranslator` (pipeline/batch/BatchChapterTranslator.kt)
is the legacy shell; `ChapterProfileBatchCoordinator` (pipeline/batch/ChapterProfileBatchCoordinator.kt)
is the T924 transactional shell that adds run records, OCR checkpoints, analysis
chunks, profile freeze, envelope plan, and layout plan publications.

**Trigger / admission:** chapter queue → `ChapterTranslator.translateChapterInternal`
(ChapterTranslator.kt:595; queue membership persisted in `TranslationQueueStore`,
ChapterTranslator.kt:163-219) → batch shell begins generation
(`store.beginGeneration`, BatchChapterTranslator.kt:290).

**Per-page lane (both shells; T924 shell adds checkpoints):**

| Stage | Class/function | file:line | Durable I/O |
|---|---|---|---|
| Pre-register pages | `preRegisterPages` | ChapterTranslationStore.kt:1698-1794 | 1 manifest publish (expected counts; memory-only placeholders otherwise) |
| Page lease | `tryAcquirePageStageLease(BATCH)` | ChapterTranslationStore.kt:530-534 | none |
| Decode + detect/OCR | `analyzePage` → `store.mergeOcr` | pipeline/SinglePageOnnxPhase.kt:837-953 | openCandidate + persistLiveCandidate (2 sidecars + 2 manifest publishes; fenced via `BatchWriteIdentity`, pipeline/batch/BatchWriteGate.kt:85-134) |
| Inpaint RUNNING stamp | `inpaintPage` guarded write | SinglePageOnnxPhase.kt:971-994 | memory-only (transient placeholder) |
| Cleaned JPEG + commit | `persistCleanedBitmap` → `patchPage` | CleanedPublication.kt:96-200 | JPEG (~3 ops) + persistLiveCandidate + promoteLiveCandidate (candidate+committed+generation+manifest) |
| **OCR checkpoint (T924)** | `checkpointPage` → `store.checkpointOcr` CLOSE | ChapterProfileBatchCoordinator.kt:2841-2866; ChapterTranslationStore.kt:1013-1144; ChapterArtifactStore.kt:477-756 | OCR snapshot sidecar (full PageTranslation JSON) + checkpoint sidecar + CANCELLED generation record + manifest publish + **retention crawl** (`sweepAfterCommit`, ChapterArtifactStore.kt:742-755) |
| Layout plan publication | `BatchRenderJoin` → `publishSidecarPointers` | pipeline/batch/BatchRenderJoin.kt:494-530, :597 | layout plan sidecar + manifest publish |

**Per-chapter batch-level publications** (each = sidecar + manifest publish ≈ 14 ops):

- `publishActiveRun` at run start (ChapterArtifactStore.kt:349-392; coordinator
  call ChapterProfileBatchCoordinator.kt:2940).
- Run records at every phase transition — 10+ `publishRecord` call sites with
  states `RUN_SNAPSHOT, PROFILE_FROZEN, OCR_PLAN, OCR_PREFLIGHT, ANALYSIS_PLAN,
  ANALYSIS_CHUNKS, PROFILE_RECONCILE, ENVELOPE_PLAN, FINALIZE, COMPLETE`
  (ChapterProfileBatchCoordinator.kt:334, 343, 373, 414, 480, 545, 632, 710,
  740, 785, 1569, 1673). A full fresh run publishes ~10-15 records. VERIFIED.
- Profile freeze: `ProfileFreezePublication.publish` (pipeline/batch/ProfileFreezePublication.kt:45-93; coordinator :1115).
- Envelope plan: `EnvelopePlanPublication.publish` (pipeline/batch/EnvelopePlanPublication.kt:43-80; coordinator :1369, :2201).
- Analysis chunks: one `AnalysisChunkPublication.publish` per chunk
  (pipeline/batch/AnalysisChunkPublication.kt:45-89; coordinator :820).
- Glossary publication (same `ChapterGlossaryStore` path as manual).
- Teardown: NonCancellable `store.flush()` + `reconcileArtifactRetention()`
  (full-tree sweep) + COMPLETE record (ChapterProfileBatchCoordinator.kt:1660-1700;
  BatchChapterTranslator.kt:995-1039; artifact/ArtifactRetention.kt:14-50).

**Publication to reader UI:** batch pages surface through the same
`_display`/`_state` StateFlows and the committed-display promotion
(ChapterTranslationStore.kt:2176-2199); the batch shells read
`store.state.value` for progress (BatchChapterTranslator.kt:947, :977). VERIFIED.

---

## (b) Durable I/O inventory

Op-counting unit = one backend operation against the manga directory
(create/findFile/open/read/write/delete/rename/list). On SAF each is a
provider round trip (sub-ms to tens of ms). VERIFIED op sequences; counts DERIVED.

Fixed costs:

- **Manifest publication (MP)** = 3 full manifest reads+parses (CAS + 2
  future-schema guards) + tmp write + tmp read-back + bak delete + 2 renames
  ≈ **8 ops** (≥3 of them O(manifest bytes)).
- **Sidecar publish (SP)** = tmp write + read-back + bak delete + 2 renames ≈ **5 ops**.
- **Retention crawl (RC)** = recursive `io.list`+`io.exists` over the whole
  managed tree (ArtifactRetention.kt:14-50) ≈ O(files+dirs) ops.

### Per page — fresh page, full pipeline (manual ≈ auto; batch adds checkpoint+layout)

| Write | Where | Ops |
|---|---|---|
| Registration manifest publish (first write only) | ChapterTranslationStore.kt:1947-1968 | 8 |
| openCandidate: generation record SP + MP | ChapterArtifactStore.kt:1403-1483 | 13 |
| mergeOcr: candidate snapshot SP + MP | ChapterArtifactStore.kt:1079-1150 | 13 |
| Cleaned JPEG write+verify | CleanedPublication.kt:121-133 | 3 |
| Cleaned commit: candidate SP + MP | CleanedPublication.kt:144 → same path | 13 |
| Glossary SP + listing + MP (contextual translators) | ChapterGlossaryStore.kt:78-92 | 14 |
| Final commit: candidate SP + MP | ChapterArtifactStore.kt:1079-1150 | 13 |
| Promotion: candidate read-back (1) + committed SP + generation SP + MP | ChapterArtifactStore.kt:1188-1318 | 19 |
| Retired cleaned delete | CleanedPublication.kt:39-70 | 0-1 |
| `store.flush()` | StorePersistenceScheduler.kt:74-85 | 0 (no-op) |
| **Total ≈** | | **~95-110 ops / page** |

Batch adds per page: checkpoint (snapshot SP + checkpoint SP + cancelled-gen SP
+ MP = 21 + RC crawl) and layout plan (SP + MP = 13) ⇒ **~130-150 ops/page**.

### Per chapter

| Item | Count | Ops |
|---|---|---|
| Open path: manifest probe + `loadOrMigrate` primary+backup reads (+ re-reads for T921 guard, interrupted-stage recovery, backup delete) | ChapterArtifactStore.kt:120-224 | ~6-12 reads |
| `preRegisterPages` MP | 1 | 8 |
| `publishActiveRun` | 1 | 14 |
| Run records (full fresh run) | ~10-15 | ~140-210 |
| Profile freeze + envelope plan | 2 | 28 |
| Analysis chunks | N chunks | 14·N |
| Per-page work (P pages) | P × ~130-150 | ~130-150·P |
| Final flush + retention crawl + COMPLETE | 1 + 1 RC | ~RC |
| SharedPreferences queue/pending updates (`commit=true`) | per queue mutation | 1 each (TranslationQueueStore.kt:42-49; TranslationPendingRequestStore.kt:78-104) |
| Attempt ledger document | chapter-scoped, fail-open | SP when persisted |

**Example: 100-page chapter batch ≈ 100 × 140 + 15 × 14 ≈ ~14,000 backend ops,
~700 of which are full-manifest rewrites** (manifest bytes grow linearly with P,
so total manifest bytes written ≈ 7·P × O(P) = **O(P²)**). A 20-page auto
reading session ≈ ~2,000 ops. DERIVED.

Durations are not measurable from code (SUSPECTED), but the structure — every
op synchronous, under the store mutex, on SAF — is VERIFIED.

---

## (c) Safety accounting + redundancy findings

### What each write buys

| Write | Protects against | If removed, loses |
|---|---|---|
| Manifest `.tmp` write + read-back + `.bak` rotation (ChapterDocumentIo.kt:236-255) | torn write / partially-written manifest; crash between pointer moves | last-good manifest on crash mid-publish (backup restores) |
| Sidecar-before-pointer ordering (ChapterArtifactStore.kt:855-881) | dangling committed pointer at a missing file | reader resolving a missing committed bundle after crash |
| `openCandidate` + generation records (ChapterArtifactStore.kt:1403-1483) | concurrent/stale writers (version+dependency-fingerprint CAS); process death mid-stage → RUNNING recovered to FAILED_RETRYABLE (:1581-1627) | stale batch worker clobbering newer page state |
| `persistLiveCandidate` (ChapterArtifactStore.kt:1079-1150) | losing in-flight OCR/translation progress across process death | resume re-runs the completed stage |
| `promoteLiveCandidate` committed snapshot + pointer (ChapterArtifactStore.kt:1188-1318) | showing a half-promoted page; candidate retries hiding last-known-good display | committed display loss on candidate retry |
| `checkpointOcr` (ChapterArtifactStore.kt:477-756) | batch resume re-paying OCR per page; provenance fencing (candidate CLOSE/REBASE in ONE publication) | OCR cost on resume; clean checkpoint provenance |
| Glossary sidecar + version pointer (ChapterGlossaryStore.kt:78-92) | term-continuity across sessions; reuse-gate version stamp | glossary resets; extra repair calls |
| Run records (ChapterProfileBatchCoordinator.kt:2924-2940) | resume knowing phase + counters; COMPLETE short-circuit | resume restarts from planning |
| `shouldPersistUpdate` gate (ChapterTranslationStore.kt:2522-2548) | (negative protection) keeps transient placeholders OUT of durability | — |
| SharedPreferences `commit=true` queue/pending | queue lost on crash | user re-queues chapters |

**No fsync finding:** the whole crash-safety model relies on rename ordering and
read-back verification, but no code ever forces data to stable storage
(ChapterDocumentIo.kt:141-148). Process death is survivable (page-cache coherent);
**power loss is not** — a "successful" manifest publish can vanish or, worse,
land before its sidecar bytes, defeating the sidecar-before-pointer invariant.
VERIFIED (no sync call exists) / platform behavior DERIVED.

### Redundancy findings

| # | Severity | Finding | Evidence |
|---|---|---|---|
| R1 | **HIGH** | **Whole-manifest rewrite per stage write.** Every stage merge of every page rewrites the entire chapter manifest (O(P) bytes) through the 5-op atomic publish; a chapter pays O(P²) manifest bytes and ~7 MPs per page. The manifest mixes per-page mutable state with rarely-changing pointers in one document. | ChapterTranslationStore.kt:1876-1897, :1912-2098; ChapterArtifactManifest.kt:16-75 |
| R2 | **HIGH** | **Triple full-manifest read per publication.** Every publish does a CAS read (`staleManifestRejection`) + primary future-schema read + backup future-schema read — 3 parses of a document that was just parsed by the same call chain, all under the store mutex. The future-schema flag changes only at app upgrade. | ChapterArtifactStore.kt:1641-1649, :1791-1808 |
| R3 | **MED** | **Candidate written then immediately re-written as committed.** On a display-ready write, `persistArtifactMutationLocked` runs `persistLiveCandidate` (candidate SP + MP) and then `promoteLiveCandidate` (read-back compare + committed SP + gen SP + MP) in the same call. The intermediate candidate publish is redundant whenever promotion immediately follows. | ChapterTranslationStore.kt:2041-2095; ChapterArtifactStore.kt:1227-1238 |
| R4 | **MED** | **Per-page glossary sidecar + manifest publish.** Every manually/auto translated page with a contextual translator bumps the glossary version: a full glossary SP + dir listing + MP — two more manifest publications per page. | SinglePageHttpRenderPhase.kt:444-449; ChapterGlossaryStore.kt:55-99 |
| R5 | **MED** | **Retention crawl per page in batch.** `checkpointOcr` CLOSE and `cancelCandidate` run `reconcileRetention` (recursive tree crawl) per page, inside the store `@Synchronized` — O(P) crawls of an O(P)-file tree = O(P²) listings per batch. | ChapterArtifactStore.kt:742-755, :1548; ArtifactRetention.kt:14-50 |
| R6 | **MED** | **All durable I/O serializes behind the store mutex.** `patchPage`/`updatePageGuarded`/`mergeX` hold the `ChapterTranslationStore` mutex across the entire publish chain (3 reads + 5 writes per MP). A page's disk commit blocks every other page's store snapshot/patch/attach, including reader resolveDisplayPage callers that need the mutex-adjacent flows. `DeferredPagePublications` moves tails off the native permit but not off the mutex. | ChapterTranslationStore.kt:579, :644, :667, :968; pipeline/DeferredPagePublications.kt:31-55 |
| R7 | **LOW** | **`.tmp` read-back byte-compare doubles payload bytes on every publish.** The write already validates by parse for JSON; the read-back is an extra full read of every sidecar and the manifest per publish. | ChapterDocumentIo.kt:239-244 |
| R8 | **LOW** | **Legacy flat file + dual vocabulary retained.** `persistLocked()` is a deliberate no-op under ARTIFACTS (StorePersistenceScheduler.kt:74-85) — dead code path retained, plus `translationFile` probes on open. Costs reads at open only. | StorePersistenceScheduler.kt:74-85; LegacyChapterMigrationSource.kt:50, :212-229 |
| R9 | **LOW** | **`store.flush()` API is a no-op under artifact authority** yet is still called on several tails (SinglePageOnnxPhase.kt:565; SinglePageHttpRenderPhase.kt:765; batch teardown). Misleading but harmless. | StorePersistenceScheduler.kt:74-85 |
| R10 | **SUSPECTED** | Manual and auto pay checkpoint-less writes; batch re-pays OCR merges that the checkpoint then re-serializes (OCR snapshot sidecar duplicates the candidate snapshot content published moments earlier in `mergeOcr`). The same PageTranslation JSON is written to up to 3 stores per page (candidate, OCR snapshot, committed). | ChapterArtifactStore.kt:558-565 vs :1108 vs :1236 |

### Divergences worth noting

- Manual/auto never produce OCR checkpoints or run records — resume for those
  paths relies on committed/candidate snapshots only; batch additionally relies
  on `ocrCheckpoints` + `activeRun`. A manual page's durable state is *not*
  reusable by the batch OCR-preflight checkpoint adoption unless a committed
  bundle with matching OCR content fingerprint exists
  (ChapterArtifactStore.kt:671-716).
- Batch pages skip in-pass render promotion in the flagged pipeline
  (`renderStatus` stays PENDING; terminal-without-render is the new completion
  predicate — ChapterProfileBatchCoordinator.kt:1636-1647), so their per-page
  I/O ends at the checkpoint + layout plan, whereas manual pages end at a
  committed render bundle.

---

## (d) Ranked optimization recommendations (with risk)

Constraints: no partial commits, durable resume, manual/auto must stay usable,
bounded memory (6 GB devices), reader stability first.

### OPT-1 — Group manifest commits per page (one MP per page instead of ~7)
**Change:** stage writes stage their sidecar payloads and pointer deltas into
an in-memory pending set (they are already content-addressed and immutable);
`persistArtifactMutationLocked` publishes ONE combined manifest update at the
page's terminal or checkpoint boundary, or on a 250 ms debounce (the debounce
machinery already exists — StorePersistenceScheduler.kt:141-153).
**Safety change:** a crash mid-page loses pointer visibility of that page's
intermediate stages; recovery re-runs the stage from the last published state —
identical to today's stale-writer recovery (RUNNING→FAILED_RETRYABLE,
ChapterArtifactStore.kt:1581-1627). Sidecar-before-pointer is preserved because
sidecars still publish when staged (or even later, since the manifest is the
only committer).
**Recovery story:** unchanged for committed pointers; resume re-derives page
stage state from the last committed manifest.
**Risk:** MED. The OCR checkpoint's fingerprint preconditions compare against
the *durable* manifest — staged-not-yet-published candidate state must be
included in the CAS comparison or the batch preflight will reject. Reader
"resume mid-page" fidelity drops one stage back. Estimated win: **~70% of all
backend ops and ~85% of manifest bytes** (6 of 7 MPs per page + 2 future-schema
reads each).

### OPT-2 — Cache the future-schema guard; drop the redundant CAS read
**Change:** read primary/backup future-schema state once per store instance
(invalidate on migration/upgrade); replace the per-transaction
`staleManifestRejection` disk read with an in-memory comparison against
`artifactManifest` (all writers already funnel through one store mutex — the
CAS is process-internal).
**Safety change:** protects against the same scenarios (concurrent writer
detection moves from disk-truth to mutex-truth; multi-process access to one
manga dir is not a supported scenario).
**Risk:** LOW-MED. Keep the single re-read in `retryOnStaleManifest`
(ChapterArtifactStore.kt:1671-1686) as the tiebreaker. Win: 2-3 full manifest
reads per MP — with OPT-1 still ~2 reads per chapter-level publication.

### OPT-3 — Merge candidate-promotion into one transaction (kill R3)
**Change:** when a write reaches display-ready state, publish committed
snapshot + generation record + manifest directly; skip the candidate SP + MP
immediately preceding it (they already share one call site — only the
"keep retryable candidate" case needs the candidate copy, and the committed
snapshot content-equals it).
**Safety change:** none for committed display; the page simply never has a
published candidate at the final stage (a crash before the combined commit
loses the final stage only — today it can lose it too, since promotion follows
in the same lock).
**Risk:** LOW. Win: 1 SP + 1 MP + 1 read-back per display-ready page (~14 ops).

### OPT-4 — Retention: replace per-checkpoint crawl with event-driven sweep (kill R5)
**Change:** `checkpointOcr` CLOSE and `cancelCandidate` collect the known-orphan
names they just unlinked from the manifest (cancelled generation record,
superseded candidate) and delete exactly those; keep the full reachability
crawl at open (>8 pages: deferred), batch teardown, and user reset.
**Safety change:** none — reachability deletion is garbage collection; deferred
GC only costs disk space, never correctness (content-addressed names can't
collide semantically).
**Risk:** LOW. Win: O(P²)→O(P) listings per batch.

### OPT-5 — Batch glossary commits (kill R4)
**Change:** keep glossary updates in memory with the existing dirty flag and
publish the sidecar+pointer at the same boundaries as OPT-1's group commit
(page terminal / batch phase boundary), instead of synchronously per page.
**Safety change:** a crash loses at most the last page's glossary additions;
the D5 version stamp logic must stamp the *pending* version, not the durable
one (SinglePageHttpRenderPhase.kt:451-459 already reasons about this).
**Risk:** LOW-MED (reuse-gate versioning must be checked against staged
versions). Win: ~14 ops per contextual page.

### OPT-6 — Move sidecar/JPEG writes off the store mutex (mitigate R6)
**Change:** a single-writer storage lane (coroutine channel) owns all
`AtomicChapterDocuments` work; store transactions enqueue fully-materialized
publish jobs and await a `CompletableDeferred` only where the *resulting
manifest snapshot* is required (checkpoint CAS, promotion). Non-conflicting
pages' JPEG encodes and sidecar temp writes proceed while the mutex is released.
**Safety change:** none — publication order is preserved by the single lane;
failure propagation stays fail-closed (the DeferredPagePublications pattern
already proves the ordering discipline).
**Risk:** MED. Store mutex no longer implies disk-quit; every caller that reads
`artifactManifest` after a publish must observe the deferred snapshot. Bounded
queue required (memory constraint).

### OPT-7 — Drop the `.tmp` read-back byte-compare for sidecars on local files (R7)
**Change:** keep parse-validation, skip the byte compare (or skip the read-back
entirely where `File`-backed, keeping it on SAF).
**Safety change:** torn `.tmp` detection moves from byte-compare to
parse-at-publish (JSON parse of a torn write fails) — the rename still never
exposes a partial file.
**Risk:** LOW. Win: 1 full read per SP (~7 per page).

### OPT-8 — WAL/journal for the manifest (alternative to OPT-1, larger change)
**Change:** append per-page deltas to a single journal file (one write per
delta), rewrite the manifest only at chapter boundaries; `loadOrMigrate` replays
the journal.
**Safety change:** same guarantees as OPT-1 with fewer renames; journal replay
must be idempotent and bounded (compaction at boundaries).
**Risk:** MED-HIGH (new recovery path, schema churn); recommend only if OPT-1
proves insufficient.

**Explicitly not recommended:** removing the `.bak` rotation or the
sidecar-before-pointer ordering — those are the only crash windows protecting
the committed display; weakening them risks the reader's last-known-good page,
violating the reader-stability-first constraint.

### Sequencing
OPT-2 + OPT-3 + OPT-4 + OPT-7 are low-risk, independent, and together remove
~40% of ops. OPT-1 is the structural fix for the O(P²) manifest cost and the
biggest latency win; OPT-5/6 follow it. OPT-8 only if a journal model is ever
preferred over the group-commit model.
