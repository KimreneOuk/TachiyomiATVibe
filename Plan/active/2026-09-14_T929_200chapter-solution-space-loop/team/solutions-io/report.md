# T929 Round 2 — solutions-io: durable storage architecture, 200-chapter baseline

Base `main` @ `9c19ad0`. Paths relative to `app/src/main/java/eu/kanade/translation/`. Units (verify-io, binding):
io-call = one `ChapterDocumentIo` call (≥2 SAF round trips, `ChapterDocumentIo.kt:91-101`); MP=9 io-calls (3
full-manifest reads + 6-op publish: `ChapterArtifactStore.kt:1641-1649`, `:1787-1808`,
`ChapterDocumentIo.kt:236-255`); SP=6. Baseline 200 ch × 15 pg = 3,000 pages ⇒ ~7 publishes/page (~21k/run), ~113-122
io-calls/fresh page (~330-390k/run), manifest rewrites 7P/chapter (~21k/run), bytes O(P²), crawls O(P²)
listings/chapter (DERIVED from verified counts). SAF latency [P 1-30 ms] ⇒ mutex-serialized I/O adds [P 0.1-2.5 h] of
the model's 3.6-12.1 h; local storage: noise. Invariants: no partial commit; never revoke committed display; resume
without re-paying OCR; keep `.bak` rotation + sidecar-before-pointer.

## Catalog

| # | Solution | Class | Ops/page now→new | Run effect | Risk | Crash-window change |
|---|---|---|---|---|---|---|
| S1 | OPT-1 group manifest commit/page | known | ~110-120→~55-70 | −40-45% io; MP 21k→3k | HIGH-MED (CAS redefinition) | mid-page stage loss = stale-writer recovery |
| S2 | OPT-2 guard cache + registry CAS coordination | known | MP 9→6-7 | −40-70k manifest reads | MED (corrected) | none if disk CAS kept on LI-4 seams |
| S3 | OPT-3 merge candidate+promotion + probe cache | known | −10-14 display pages | −30-40k + 3k decodes | LOW | none (already same-lock) |
| S4 | OPT-4 event-driven retention | known | removes per-page RC | −450-600k listings | LOW | GC deferral = space only |
| S5 | OPT-5 batched glossary | known | −17 term pages | −≤51k | LOW-MED | lose last page's terms |
| S6 | OPT-6 storage lane off mutex | known | 0 (latency) | removes [P 0.1-2.5 h] stall | MED | none (lane preserves order) |
| S7 | OPT-7 read-back elision (local) | known | −6-7 | −~20k | LOW | torn-tmp caught by parse |
| S8 | OPT-8 manifest WAL/journal | known | ~110→~25-35 | −~65% | MED-HIGH | bounded replay; = S1 guarantees |
| S9 | Per-page manifest shards | NOVEL | stage MP 9→6, O(1) bytes | bytes O(P²)→O(P) | MED-HIGH | same, scoped per page |
| S10 | Event journal primary + snapshots | NOVEL | ~110→~10-15 | −80-85% | HIGH | replay ≤ one chapter journal |
| S11 | Content-addressed store, derived manifest | IMAGINATIVE | publish 16→~7-9 | −~45%; dedupes R10 bytes | MED | torn blob never pointed to |
| S12 | SQLite chapter DB (media on SAF) | IMAGINATIVE | stage = 1 tx | −~90% structured ops | HIGH | real ACID; portability loss |
| S13 | Write-behind registry + ordered flush | NOVEL | = S1 level | S1 ops without CAS rewrite | MED-HIGH | crash → last defined flush point |
| S14 | fsync at commit points vs page-cache-only doc | decision | +1-2 force/page | seconds/run | LOW | closes power-loss on local |
| S15 | Queue/pending store debounce | known-ext | n/a | −80-240k pref puts | LOW | ≤debounce window of queue loss |
| S16 | Raw-path fast lane (local backends) | NOVEL | publish 6→3 local | −~30% local | LOW-MED | stronger: atomic move + force |
## S1 — OPT-1: group manifest commit per page
**Mechanism:** stage writes hold sidecar payloads + pointer deltas in memory; `persistArtifactMutationLocked`
(`ChapterTranslationStore.kt:1912-2098`) publishes ONE combined manifest update at the page's terminal/checkpoint
boundary (debounce precedent `StorePersistenceScheduler.kt:141-153`). The 7 MP sites/page: registration `:1947-1968`,
openCandidate `:2020`, mergeOcr persist `:2054`, cleaned commit `CleanedPublication.kt:144`, glossary
`ChapterGlossaryStore.kt:84`, final persist `:2054`, promotion `:2076`. **200-ch:** ~110-120→~55-70 io-calls/page; MP
rewrites 21k→3k/run; bytes ÷7 (O(P²) constant shrinks, exponent stays); −6 SAF chains × [5-30 ms] ≈ −[0.3-1.9 s]/page
serialized — biggest reader-responsiveness lever (scenario A). **Safety:** crash mid-page loses unpublished stages;
recovery = today's RUNNING→FAILED_RETRYABLE (`ChapterArtifactStore.kt:1581-1627`); committed display and checkpoints
intact if the checkpoint stays a commit point. Scenario F (ch.137): re-run ≤ the interrupted page's stages after
checkpoint adoption; ch.1-136 zero-work. **Risk:** verify-io §5.1 — CAS compares the DURABLE manifest
(`staleManifestRejection`, `:1641-1649`); staged state makes EVERY intermediate CAS reject ⇒ redefine CAS equality
everywhere ⇒ HIGH-MED. **Interactions:** sched (checkpoint must stay durable or resume re-pays OCR); reader (scenario
C shows committed pages only).
## S2 — OPT-2 (corrected): future-schema guard cache + registry-wide CAS coordination
**Mechanism:** read future-schema state once per store (flag changes only at upgrade,
`ChapterArtifactStore.kt:1787-1808`); replace per-publish disk CAS with registry-wide authoritative comparison. T928's
premise is broken: probe stores are a SECOND writer (`DurableChapterStatusResolver.kt:207`,
`ActiveChapterStoreRegistry.kt:23,137`, rescue publication `:222-225`, health-verify republish
`LegacyChapterMigrationSource.kt:249,262`). **200-ch:** MP 9→6-7; −40-60k manifest parses under the mutex (without
S1), 6-9k with. **Safety:** writer detection moves disk→registry truth; keep the disk CAS + `retryOnStaleManifest`
tiebreaker (`:1671-1686`) on the LI-4 seams (`:1657-1670`). **Risk:** MED. **Interactions:** reader-entry probe cost
(scenario D); prerequisite for S13.
## S3 — OPT-3: merge candidate-promotion; cache the display-base probe
**Mechanism:** display-ready writes publish committed snapshot + generation record + manifest directly; the candidate
publish remains only on the durable-failure/resume branch (`ChapterTranslationStore.kt:2054-2095`; promotion rejects
without a candidate, `ChapterArtifactStore.kt:1210-1211`; read-back skipped on match `:1229-1234`). Fold in: cache
`displayBaseIsValid` (`:1695-1706`, per-promotion probe `:1217-1226`: exists+length+open+JPEG decode under the
mutex). **200-ch:** −1 MP −1 CAS −1 probe ≈ −10-14 io-calls/display-ready page ⇒ ~30-40k/run (manual/auto/standard
lane + finalize; batch pages don't promote, render PENDING, `ChapterProfileBatchCoordinator.kt:1636-1647`) + 3k
decodes gone. **Safety:** none — candidate+promotion already share one lock. **Risk:** LOW. **Interactions:** reader
display-ready latency.
## S4 — OPT-4: event-driven retention
**Mechanism:** checkpoint CLOSE / cancelCandidate delete exactly the names they unlink; full reachability crawl only
at open ≤8 pages (already deferred >8, `ChapterArtifactStore.kt:166-178`), teardown, reset. Per-page crawl today:
`sweepAfterCommit` `:632,668,742-755`, coordinator CLOSE per page `:2859`, each dir listed twice
(`ArtifactRetention.kt:39-43`). **200-ch:** crawl ≈ 150-200 io-calls × 3,000 pages = ~450-600k pure listings under
`@Synchronized` — largest single count in the run ⇒ O(P²)→O(P) listings/chapter. **Safety:** none — deferred GC costs
space only (content-addressed names). Keep the teardown sweep for 6 GB-device footprint; open stall already fixed
(T921, `StorePersistenceScheduler.kt:104-112`). **Risk:** LOW. **Interactions:** reader entry.
## S5 — OPT-5: batched glossary commits
**Mechanism:** keep glossary dirty in memory; publish sidecar+pointer at page-terminal/phase boundaries via S1's
group commit. Today per term-bearing contextual page: SP 6 + version dir listing (`ChapterArtifactStore.kt:1818-1823`)
+ MP 9 ≈ 17 io-calls (`ChapterGlossaryStore.kt:55-99`; publish `:239-256`; no-op guard `:63`). **200-ch:** ≤3,000 term
pages × 17 = ≤51k io-calls. **Safety:** crash loses last page's terms; D5 reuse-gate must stamp the PENDING version
(`SinglePageHttpRenderPhase.kt:451-459`) or resume issues repair calls; dirty-retry exists
(`StorePersistenceScheduler.kt:93-101`). **Risk:** LOW-MED. **Interactions:** provider/quality (continuity unchanged —
in-memory terms still applied to later pages).
## S6 — OPT-6: single-writer storage lane off the store mutex
**Mechanism:** one coroutine lane owns all `AtomicChapterDocuments` work; callers enqueue materialized jobs, await
only where the resulting snapshot is required (checkpoint CAS, promotion) — those seams must release the mutex while
awaiting or serialization is recreated (verify-io nuance). Mutex-across-publish today: `ChapterTranslationStore.kt:579,667,839,968,1029`;
ordering precedent `pipeline/DeferredPagePublications.kt`.
**200-ch:** 0 op delta; removes [P 0.1-2.5 h] of serialized SAF latency from scheduler/pipeline callers; reader taps
already lock-free (`resolveDisplayPage` `:2228-2229`). **Safety:** lane order = mutex order; bounded queue mandatory,
overflow fails closed. **Risk:** MED. **Interactions:** sched (native permit no longer covers disk waits);
prerequisite for S13.
## S7 — OPT-7: read-back elision (local backends)
**Mechanism:** skip the `.tmp` byte-compare read (`ChapterDocumentIo.kt:239-244`) on File-backed IO; parse-validation
remains (`:257-260`). Keep on SAF (silent provider corruption). **200-ch:** −1 read × ~7 SPs/page ≈ −20k io-calls/run;
bytes/publish ÷≈1.8. **Safety:** torn-tmp caught at parse-at-publish; rename never exposes partial files. **Risk:**
LOW; backend-dependent. **Interactions:** none.
## S8 — OPT-8: WAL/journal for the manifest (fallback to S1)
**Mechanism:** append seqno-fenced per-page delta records to one journal (one write per delta); rewrite the manifest
at chapter boundaries; `loadOrMigrate` replays idempotently; pointer-commit records preserve sidecar-before-pointer by
construction; compaction reuses the atomic publish (`:1791-1809`). **200-ch:** ~4-6 io-calls/page vs 55-122 ⇒ −~65%
run io-calls; bytes O(P·deltas) between compactions; the journal seqno IS the CAS — no S1-style CAS redefinition.
**Safety:** same guarantees as S1; the replay path concentrates the risk (crash-idempotent, bounded). **Risk:**
MED-HIGH. **Interactions:** S10 is its full form.
## S9 — NOVEL: per-page manifest shards
**Mechanism:** split `pages: Map<String, PageArtifactRecord>` (`ChapterArtifactManifest.kt:20`) into per-page (or
per-K-page, K≈8) shard documents beside the existing page dirs (`ChapterArtifactLayout.kt:93-96` precedent); the
chapter manifest keeps only chapter-level pointers + registration (`:63-75`). Page mutations publish a shard (SP=6);
chapter MP only for chapter-level events. Every `publishManifestInternal` caller (`:423,802,877,1068,1146,1314,1368`)
goes shard-aware. **200-ch:** manifest bytes O(P²)→O(P) structurally (S1 only shrinks the constant 7×); page
stage-publishes cost 6 not 9 even without staging; open reads P small shards vs 1 large manifest — shard-packing
bounds SAF round trips. Run page-work io-calls −~30%. **Safety:** identical tmp/bak machinery per shard — crash
windows scoped to one page; cross-page invariants (expectedPageCount, activeCandidate set) stay chapter-level.
**Risk:** MED-HIGH: schema v4 migration; probe/rescue read paths need a shard-aware façade. **Interactions:** composes
with S1; scenario F reads only ch.137's shards.
## S10 — NOVEL: append-only page-event journal as primary store + periodic snapshots
**Mechanism:** generalize S8 — the journal is primary for page state AND chapter-level deltas (run records,
checkpoints become journal records, not sidecar+MP pairs); snapshots at chapter completion/journal length; open =
snapshot + replay tail; committed bundles stay immutable sidecar files referenced by pointer records. **200-ch:**
~10-15 io-calls/page ⇒ run ~40-60k io-calls (−85%); checkpoint's double publish (`ChapterArtifactStore.kt:445-511`)
becomes one append; scenario F resume = ms-scale replay of ch.137's tail + checkpoint adoption as today. **Safety:**
checksum per record; torn tail truncated to last valid record — strictly stronger than a torn manifest; bundle
sidecars keep their backup discipline (no-go respected — `.bak` dropped for the journal only). **Risk:** HIGH (new
primary store, full migration). **Interactions:** store façade absorbs it; sched resume simplifies.
## S11 — IMAGINATIVE: content-addressed page store with lazy/derived manifest
**Mechanism:** stage payloads written once as immutable blobs named by content hash (write + `renameNoReplace`
admission, local-only — `ChapterDocumentIo.kt:150-178`); page records become pointer swaps; open can rebuild state by
scanning blobs when the manifest is missing. Immutable blobs need no tmp/bak (never overwritten) — the no-go covers
mutable documents; the pointer manifest keeps full publish discipline. **200-ch:** publish = blob write + in-memory
dedupe check + pointer MP ≈ 7-9 io-calls vs 16 ⇒ −~45% run io-calls; the triple write of the same PageTranslation JSON
(candidate `:1108`, OCR snapshot `:559-561`, committed `:1235-1238` — T928 R10) dedupes to one blob ⇒ −~2×
PageTranslation bytes/page. **Safety:** torn blob never pointed to (hash check at read); blob-before-pointer enforced
by admission ordering. **Risk:** MED: blob GC needs S4; name safety (`ChapterArtifactLayout.isSafeSegment`); pointer
migration. **Interactions:** provenance/quality improves (content hash = physical identity).
## S12 — IMAGINATIVE: SQLite chapter database; media stays on SAF/UniFile
**Mechanism:** one app-private DB (pages/candidates/checkpoints/run records/glossary/queue/pending as rows);
per-stage write = one transactional UPDATE (real CAS via WHERE version=?); WAL gives concurrent readers (kills the
probe second-writer hazard at the storage layer); JPEGs stay files in the manga dir. Replaces the MP/SP machinery,
queue prefs (`TranslationQueueStore.kt:42-49` — the documented rejection flips at this scale), pending prefs
(`TranslationPendingRequestStore.kt:79-165`). **200-ch:** structured io-calls −~90%; retention crawl deleted (row
delete); open = indexed query; size ≈ 3,000 × 10-40 KB ≈ 30-120 MB (fine). **Safety:** WAL + `synchronous=FULL` at
commit points closes the no-fsync gap properly (S14 free for structured state); crash = transactional rollback. NEW
trade: app-private DB — uninstall/clear-data destroys translations that today sit beside the manga (possibly SAF/SD);
needs export or SAF-hosted DB (weaker WAL on some providers). **Risk:** HIGH: substrate migration, rescue-machine
rewrite. **Interactions:** façade shields reader/sched; red-team portability before commitment.
## S13 — NOVEL: in-memory page-state registry with write-behind durability
**Mechanism:** invert authority: the in-memory registry is truth during a page's life (it already feeds
`_state`/`_display`, `ChapterTranslationStore.kt:1894-1895`); disk is write-behind — a sequenced flusher drains
materialized page states at DEFINED commit points (page terminal, OCR checkpoint, phase boundary, close). No
staged-CAS problem (S1's risk): disk never mediates between writers, the registry does; flush is one-way, ordered.
Requires S2's registry-level probe coordination (one writer per chapter). **200-ch:** durable ops = S1-level
(~55-70/page) without redefining durable CAS; native lane never waits on disk (S6 folded in). **Safety:** crash loses
post-last-flush-point state by design (S1's external contract); the commit-point list MUST include OCR checkpoints
(resume-without-re-paying-OCR) and every promotion (never-revoke). **Risk:** MED-HIGH: memory ceiling — cap unflushed
payloads, spill-to-journal backpressure (6 GB devices). **Interactions:** sched defines flush points; scenario C
unchanged (in-memory stages visible as today).
## S14 — DECISION: targeted fsync at commit points vs documented page-cache-only
**Mechanism A:** `FileChannel.force`/`FileDescriptor.sync` at the two true commit points — committed-bundle publish
and the pointer-moving manifest publish (`ChapterDocumentIo.kt:141-148` gains a sync path used when `filePath !=
null`; SAF streams cannot fsync portably). **Mechanism B:** formally document page-cache-only durability (process
death safe; power loss can land pointer before sidecar, defeating sidecar-before-pointer, T928 §c). No sync call
exists today (verified). **200-ch:** after S1/S3, commits ≈ 1-2/page + chapter events ≈ 4-8k forces × [P 0.1-10 ms] =
seconds per run — cheap BECAUSE commits became rare; fsync before S1 would be expensive. **Safety:** A closes the
power-loss window on local storage; SAF stays B regardless. **Risk:** LOW. **Interactions:** S12 supersedes for
structured state.
## S15 — Queue/pending store churn
**Mechanism:** `TranslationQueueStore.save` rewrites the whole queue with `commit=true` (clear + N puts,
`TranslationQueueStore.kt:42-49`); 6 `persistQueue()` sites (`ChapterTranslator.kt:386,853,865,882,906`); pending
store 3 `commit=true` sites (`TranslationPendingRequestStore.kt:79,150,165`). Debounce (250 ms precedent) or save only
on membership change / chapter admission-completion. **200-ch:** today ≈ 400-1,200 synchronous commits × 200-entry
rewrites = 80-240k pref puts on the queue thread (scenario A tap path); debounced ⇒ ≤~10 commits. **Safety:** crash
loses ≤ debounce window of queue mutations (re-queue cost only; resume requires explicit re-arm anyway,
`TranslationQueueStore.kt:19-22`). **Risk:** LOW. **Interactions:** sched queue semantics untouched.
## S16 — NOVEL: raw-path fast lane on local backends
**Mechanism:** when `UniFile.filePath != null`, perform publish over `java.nio.file`: write temp + `force` +
`Files.move(ATOMIC_MOVE)` — 3 io-calls with real durability vs the 6-call exists/rename dance; SAF keeps today's path.
The split exists implicitly: `renameNoReplace` requires raw paths, UNSUPPORTED on URI backends
(`ChapterDocumentIo.kt:159-167`). **200-ch:** local publish 6→3 ⇒ with S1 ~55→~30/page; enables S14-A. **Safety:**
STRONGER locally (atomic move + fsync); `ATOMIC_MOVE` failure falls back to the current sequence (fail-closed).
**Risk:** LOW-MED: two paths to test; probe `filePath` once per store open and lock the lane. **Interactions:**
composes with S1/S3/S7; SAF remains the reference semantics.

## Backend-dependency (SAF vs raw paths)
Store-layer, backend-independent: S1-S5, S8-S11, S13, S15. Local-only: S7 (skip read-back), S14-A (fsync), S16
(ATOMIC_MOVE + force). SAF hard limits: no multi-op batching in DocumentsProvider (each io-call ≥2 round trips,
`ChapterDocumentIo.kt:91-101`); `renameNoReplace` unsupported (`:161`); fsync not portable — on SAF only op-count
reduction helps, so the O(P²) manifest and per-page-crawl fixes (S1/S4, or S9/S10) are mandatory regardless of
backend. `dirCache` (`ChapterDocumentIo.kt:69-89`) already amortizes traversal; shard layout (S9) should keep one
directory per segment.
## Sequencing
S3+S4+S7+S15 (LOW, independent; with S2 ≈ −50-55% run io-calls) → S1 (structural per-page commit; the only
non-substrate fix for serialized reader-facing latency) → S14-A local → S6. If S1's CAS redefinition proves too
invasive: S9 (bytes) or S13 (ops without CAS rewrite); S10 if a journal model is preferred; S11 composable with any;
S12 is the long-horizon rewrite — red-team portability before commitment.
