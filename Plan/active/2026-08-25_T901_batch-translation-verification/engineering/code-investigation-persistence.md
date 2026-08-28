# T901 — Specialist B: Persistence, Restart/Clear, Ownership Transfer, Disk I/O

Assignment: Director symptoms 2 ("Batch translation is lost after app cleared or restart")
and 4 ("Investigate flawed ownership transfer, disk I/O, and more").
All line numbers refer to the current working tree. Evidence classes: VERIFIED (read in
source/test), STRONG INFERENCE, ASSUMPTION, UNKNOWN.

---

## 1. Executive summary

- **The write side moved to the artifact store, but the read side never did.** After the
  first durable page write, the legacy flat JSON is frozen forever (empty for fresh
  chapters), yet post-restart chapter status — `TranslationManager.persistedChapterStatus`
  — reads *only* that flat file. Fully batch-translated chapters therefore show
  NOT_TRANSLATED in the manga screen after restart. This is the dominant root cause of
  symptom 2. (VERIFIED: ChapterTranslationStore.kt:1731, TranslationManager.kt:426-467,
  test ChapterTranslationStoreArtifactMigrationTest.kt:241-271)
- **The persisted queue survives restarts but is silently gutted on use**: starting any
  single chapter evicts every other same-source QUEUE entry — including entries restored
  after a restart — without confirmation. (VERIFIED: TranslationManager.kt:272-278,
  ChapterQueueConflictDetection.kt:45-54)
- Restart behavior is "rehydrate but require Start" by design; the foreground service is
  a notification monitor only (`START_NOT_STICKY`), so all in-flight work stops at
  process death. Committed per-page artifacts genuinely survive; the *perception* of loss
  comes from D1/D2/D3 plus in-memory-only progress. (VERIFIED)
- Ownership transfer is mostly well-fenced (leases + candidate preconditions + atomic
  promotion), but there are real holes: a compat writer can echo the current holder's
  lease token and mutate a batch-owned page; failed artifact writes still update in-memory
  state (silent divergence, memory ahead of disk); unfenced post-restart writes inherit a
  dead batch's provenance. (VERIFIED mechanics, medium reachability)
- Disk I/O is crash-safe where it matters (manifest/sidecars: temp→validate→rotate→rename)
  but massively amplified: every stage emission rewrites the entire chapter manifest plus
  full page snapshots; the legacy flat file and summary/glossary sidecars are still
  non-atomic direct overwrites; `getChapterTranslation` *deletes* flat files that fail to
  decode. (VERIFIED)

---

## 2. Persistence inventory

| # | What | Where (path/format) | Written when (commit timing) | Durable? |
|---|------|---------------------|------------------------------|----------|
| 1 | Batch queue membership + order (chapter ids) | SharedPreferences `translation_queue`, key = index (TranslationQueueStore.kt:34-75) | On every queue mutation: add/remove/clear (ChapterTranslator.kt:141-143, 589, 599, 614, 636). `apply()`, not `commit()` | Yes, modulo async-flush window (minor) |
| 2 | Translation state per Translation | In-memory only (`Translation._statusFlow`, Translation.kt:23-32) | Status changes | **No** — rebuilt as QUEUE on rehydrate (ChapterTranslator.kt:152-175) |
| 3 | Legacy flat chapter JSON `X.json` (full page map) | Manga dir in user translations dir (TranslationProvider.kt:81-84) | `persistLocked` — debounced 250 ms, **direct overwrite, non-atomic** (ChapterTranslationStore.kt:1724-1756, 1807-1819) | Only pre-cutover; **frozen empty after Phase-3 cutover** (1731) |
| 4 | Artifact manifest `X.manifest.json` (+`.bak`,`.tmp`,`corrupt`) | Manga dir (ChapterArtifactLayout.kt:43) | Every artifact transaction, atomically (ChapterDocumentIo.kt:127-146) | Yes (crash-safe rotate + backup) |
| 5 | Candidate/committed page snapshots `X_artifacts/pages/<page>/{candidate,committed}-g-<hash>.json` | Artifact tree (ChapterArtifactLayout.kt:77-81) | `persistLiveCandidate`/`promoteLiveCandidate` per durable stage emission, synchronously under store mutex (ChapterTranslationStore.kt:1177-1316; ChapterArtifactStore.kt:307-463) | Yes |
| 6 | Generation records `X_artifacts/generations/g-<hash>.json` | Artifact tree (layout:88-89) | open/promote/cancel candidate | Yes |
| 7 | Per-stage sidecars `X_artifacts/artifacts/...` + RUNNING markers (`beginStage`/`commitStagePayload`) | Artifact tree (layout:56-62) | **No production callers** (grep: defined ChapterArtifactStore.kt:637-755, only tests call them) | N/A — dead in production (VERIFIED) |
| 8 | Glossary | Legacy `X.glossary.json` (non-atomic overwrite, ChapterTranslationStore.kt:1643-1657) or versioned `X_artifacts/glossary/chapter.glossary.N.json` (atomic, ChapterArtifactStore.kt:172-188) | On `updateGlossary` | Yes (legacy variant corruptible) |
| 9 | Completion summary `X.summary.json` | Manga dir (ChapterTranslationSummaryStore.kt:84-86) | At batch end only (TranslationPipeline.kt:2721-2727); **direct overwrite, non-atomic** (58-63) | Mostly |
| 10 | Cleaned display images `X_images/<page>.cleaned.<version>.jpg` | Legacy companion dir, outside managed artifact tree (TranslationPipeline.kt:3650-3661; layout:73-74) | Written before store commit; unique versioned name; length-verified | Yes once committed; orphans on crash are never swept |
| 11 | ActiveChapterStoreRegistry, page leases, committedDisplay, retiredCleanedImages, batch trackers, terminal snapshot cache, foreground-service monitor | In-memory (ActiveChapterStoreRegistry.kt:20-85; ChapterTranslationStore.kt:89-107; TranslationBatchTrackerRegistry.kt) | Live | **No** — gone on process death (by design) |

Key timing fact (VERIFIED): in `publishLocked` the artifact bridge runs *before*
`schedulePersist`, and after cutover `persistLocked` returns without writing
(ChapterTranslationStore.kt:1155-1165, 1731). So in the current build **the flat-file
write path is effectively dead except when the artifact bridge fails or the store is
memory-only** (test ChapterTranslationStorePersistenceTest.kt:24-37 exercises the
memory-only shape). `tempFileNameFor` (1839) is dead code — the planned atomic flat-file
write was never implemented.

---

## 3. Restart / app-clear scenario matrix

| Scenario | Queue entries | Committed page artifacts | In-flight page | Chapter-list status after restart | Why |
|---|---|---|---|---|---|
| (a) Normal restart (process ends cleanly or is cached out) | Survive as ids; rehydrated to **QUEUE** status when TranslationManager is first constructed (lazy — manga/reader screen) (TranslationManager.kt:128; AppModule.kt:132; ChapterTranslator.kt:152-175) | Survive (atomic artifact tree) | Last persisted candidate snapshot survives; statuses inside it drive resume (BatchResumeGateDecider.kt:32-48) | **WRONG for fresh chapters: NOT_TRANSLATED** (see D1) | Read side reads frozen flat file |
| (b) Process killed (force stop, LMK, crash; swipe-recents usually keeps FGS alive, force-stop does not) | Same as (a); a pending `apply()` can lose the last queue mutation (D9) | Survive; a kill inside an atomic publication leaves a `.tmp` orphan or falls back to `.bak` (ChapterDocumentIo.kt:100-196) | Same as (a); no production RUNNING stage markers exist to recover (D11), resume uses snapshot statuses | Same wrong status (D1) | Same |
| (b′) Kill mid-write of legacy flat file (only reachable when artifact bridge failed / legacy authority, D6) | Survive | Flat file corrupt → `open()` starts empty (ChapterTranslationStore.kt:1845-1880); `getChapterTranslation` **deletes** it (TranslationManager.kt:501-510) | — | NOT_TRANSLATED | Non-atomic overwrite + destructive read |
| (c) "Clear app" storage | **Lost** (SharedPreferences wiped) | Lost if translations dir is app-private; may survive if user pointed the translations dir at external SAF storage (storageManager.getTranslationsDirectory, TranslationProvider.kt:22-23) — but with prefs wiped the app no longer knows settings | — | NOT_TRANSLATED | By design; nothing app-side can survive clear-storage of its private dirs (ASSUMPTION on dir location) |

Additional restart facts (VERIFIED):
- No auto-resume: restored entries require an explicit Start (comment ChapterTranslator.kt:148-150).
- `TranslationForegroundService` is a monitor/notification, not a worker
  (TranslationForegroundService.kt:72-87), returns `START_NOT_STICKY` (line 60). It does
  not protect work; it only raises process importance while a batch is running.
- Queue rehydration is lazy and racy vs. first chapter-list build (D3).
- On re-open, `loadOrMigrate` reconciles retention and (for ARTIFACTS manifests)
  recovers interrupted RUNNING stages to FAILED_RETRYABLE (ChapterArtifactStore.kt:109-124)
  — but since production never persists stage RUNNING markers (D11), this fires only for
  legacy-migrated RUNNING pages.

---

## 4. Ownership-transfer analysis (symptom 4)

The intended model (VERIFIED): one writer origin owns a page at a time.
- In-memory fencing: `pageLeases` with monotonic tokens; acquisition denied when another
  origin holds the page (ChapterTranslationStore.kt:305-356); guarded writes must echo
  generation + pageVersion + leaseToken + candidateGenerationId + dependencyFingerprint
  + artifactPageVersion (873-900); late/stale writes rejected (425-429).
- Durable fencing: candidate preconditions in the artifact store — candidate mismatch,
  stale page version, dependency-fingerprint change, provenance mismatch
  (ChapterArtifactStore.kt:1004-1026); stale-manifest rejection by full-manifest equality
  (1068-1076).
- Handoff: reader request on a batch-owned page attaches to the owner's emissions
  (TranslationPipeline.kt:770-791, 2011-2020); batch defers reader-owned pages and
  rescans.
- Teardown: batch `finally` cancels candidates and releases all BATCH leases
  (TranslationPipeline.kt:2738-2746); OOM abort path releases too (2689-2695); reader
  releases in `finally` (759-761); `markDefunct` clears leases (ChapterTranslationStore.kt:225).
- CleanedImagePublisher ordering: file first, store commit second, previous file deleted
  only when it backs no committed/retired display bundle (CleanedImagePublisher.kt:24-87;
  `mayDeleteCleanedImage` ChapterTranslationStore.kt:1442-1444). Sound.

Flaws found:

- **F1 (major, VERIFIED)** — Lease-token echo hole. `updatePageFromCurrentSnapshot` builds
  its precondition from `snapshot(pageKey)`, which returns the *current holder's* token
  (ChapterTranslationStore.kt:486-490, 1552-1557). Any caller of this compat API can
  mutate a batch-leased page mid-flight; only `updatePage` (not the FromCurrentSnapshot
  variant) checks active-lease ownership (930-934). Mitigated because the dangerous
  callers (chapter/page resets) cancel and join work first (TranslationManager.kt:961-995,
  1063-1097) — but the fence itself does not hold.
- **F2 (major→minor, VERIFIED mechanics)** — Silent memory-ahead-of-disk divergence.
  When the artifact bridge rejects a write (SAF failure, provenance/precondition
  mismatch), `updatePageGuarded`/`patchPage` have *already* updated the in-memory page and
  return Accepted to the caller; only display promotion is skipped
  (ChapterTranslationStore.kt:449-452, 476-479, 1155-1162). The prior device run showed
  exactly this diverging state (manifest at page 21, native work at page 60 —
  Plan/active/2026-08-21-batch-store-recovery/brief.md). The batch now aborts page
  candidates on rejection (TranslationPipeline.kt:1548-1557), but a rejected durable
  write is still silently accepted in-memory: work the user watched complete can vanish
  on restart. (Symptom 2/4.)
- **F3 (minor, VERIFIED)** — Unfenced post-restart writes inherit dead provenance. With
  no lease held (after process death), `persistArtifactMutationLocked` resolves origin as
  `leaseOrigin ?: candidate?.origin ?: BATCH` (ChapterTranslationStore.kt:1237-1239), so
  a reader-side resume continues a stale BATCH candidate and is durably recorded as BATCH.
  Provenance/metadata wrong; not a loss.
- **F4 (minor, VERIFIED)** — Cross-origin candidate takeover churn. If an origin change is
  detected mid-candidate, the bridge cancels the live candidate and opens a new one inside
  the same locked write (1244-1269). The displaced owner's next guarded write is then
  rejected ("candidate mismatch") and the page aborts and re-runs — bounded churn, not
  corruption.
- **F5 (minor, VERIFIED)** — Cancel semantics release in-flight leases while the worker
  may still be unwinding: `clearTransientQueuePages` retains leases only for pages with
  rendered results (ChapterTranslationStore.kt:1121-1127); the unwinding worker's late
  durable writes are rejected, discarding completed native work for that page. Intentional
  for "cancel", but it also fires on the replace-chapter flow
  (TranslationManager.cancelRunningChapterForReplace:346-360).
- **F6 (minor, VERIFIED)** — Registry bypass. Reset/preflight paths construct
  `ChapterTranslationStore.open(file)` directly instead of through
  ActiveChapterStoreRegistry (TranslationManager.kt:1012, 1088, 1135, 1193, 1244). Two
  ChapterArtifactStore instances can then interleave on the same documents; safety rests
  entirely on `staleManifestRejection`'s full-manifest equality (ChapterArtifactStore.kt:1068-1076).
- **F7 (minor, STRONG INFERENCE)** — Lease held until batch end for pages whose native
  call timed out (`withNativeLane` returns null; release happens only at terminal
  boundary or batch finally — TranslationPipeline.kt:236-265, 2738-2746): reader requests
  on that page "attach to owner" and wait. Bounded; not a leak past batch end.
- No unbounded lease leak found: every acquire site is paired with release in `finally`
  or batch teardown (grep evidence: TranslationPipeline.kt:760, 790, 1185, 2693, 2737-2745;
  ReaderViewModel.kt:2538). (VERIFIED)

---

## 5. Disk I/O audit

| Item | Pattern | Atomic? | fsync | Amplification | Risk |
|---|---|---|---|---|---|
| Manifest + sidecars (`AtomicChapterDocuments.publish`, ChapterDocumentIo.kt:127-146) | write `.tmp` → read back → validate → delete `.bak` → rename primary→`.bak` → rename `.tmp`→primary | Yes | No explicit fsync (UniFile/SAF semantics; close-time flush only) | Every publication = ~5 SAF ops + full read-back | Crash-safe vs torn writes; power-loss durability depends on provider (minor) |
| Whole-chapter manifest rewrites | Manifest contains ALL page records; rewritten on *every* stage emission and promotion (ChapterTranslationStore.kt:1196, 1347, 1296→store; ChapterArtifactStore promitions) | Yes | — | **O(N²) bytes per chapter** (N pages × O(N) manifest per emission) | Major on SAF/storage-provider media; widens crash windows |
| Full page snapshots | `persistLiveCandidate` + `promoteLiveCandidate` serialize the complete PageTranslation (blocks, mask geometry, detections) — promotion writes the same snapshot **twice** (candidate file + committed file, ChapterArtifactStore.kt:398-406) | Yes | — | ~2 full snapshots per terminal page + 1 per intermediate emission | Major |
| Retention sweep on every promote | `reconcileRetention` lists managed dirs after each promotion/cancel (ChapterArtifactStore.kt:1133-1147, called at 461, 515, 537, 866, 934) | — | — | Directory walks per page | Moderate |
| Legacy flat JSON (when written at all) | Direct `openOutputStream` overwrite, 250 ms debounce (ChapterTranslationStore.kt:1746-1750, 1822) | **No** (dead `tempFileNameFor` at 1839 proves intent unimplemented) | No | Whole-map rewrite | Crash mid-write corrupts chapter (D6) |
| Summary sidecar | Direct overwrite (ChapterTranslationSummaryStore.kt:58-63) | No | No | Once per batch | Crash → uncertified completion (fail-safe direction) |
| Legacy glossary | Direct overwrite (ChapterTranslationStore.kt:1650-1656) | No | No | On update | Corrupt → empty glossary (graceful) |
| Cleaned images | Unique versioned name, direct write + length check (TranslationPipeline.kt:3650-3661) | Name-versioned (effectively) | No | New file per inpaint attempt; old deleted via publisher/retire | Crash orphans files in `X_images/` that retention never sweeps (outside managed tree) — unbounded growth (minor) |
| Queue SharedPreferences | `apply()` (async) | N/A | — | One batch per mutation | Kill during flush loses last mutation (minor) |
| Blocking I/O placement | Store-mutex synchronous SAF I/O on every emission (batch IO workers, reader IO flows) — acceptable; but resets run `runBlocking` bridges from `screenModelScope.launchNonCancellable` (main) e.g. TranslationManager.kt:351-359, 961-995 via MangaScreenModel.kt:902-923; `getChapterTranslationStatus` does `runBlocking(IO)` per chapter during list builds (TranslationManager.kt:377, 431) on IO threads | — | — | Chapter list rebuild re-reads+decodes every chapter's flat file + summary on **every queue emission** (MangaScreenModel.kt:182-197 → 624-666) | Jank/ANR exposure (minor-major) |
| Destructive read | `getChapterTranslation` deletes flat files that fail to decode (TranslationManager.kt:501-510) | — | — | — | Converts transient decode failure into permanent deletion (D13) |

Crash-mid-write outcomes (VERIFIED against ChapterDocumentIo recovery logic):
manifest torn → backup promoted / corrupt primary quarantined; snapshot torn → orphan
`.tmp`/sidecar swept later; flat file torn → chapter treated as untranslated and
eventually deleted by the destructive read.

---

## 6. Defect list

| ID | Defect | Evidence | Severity | Symptoms | Confidence |
|---|---|---|---|---|---|
| D1 | Post-restart chapter status reads only the frozen/empty legacy flat file; write side cut over to artifacts, read side never did. Fresh chapters' flat file stays 0 bytes forever. Manga screen shows NOT_TRANSLATED for fully translated chapters after restart; reader's initial page load also decodes the empty flat file. | TranslationManager.kt:426-467 (flat-file only), 435 (`length <= 2L` bail); ChapterTranslationStore.kt:1731 (freeze), 1331 (empty file creation); test ...ArtifactMigrationTest.kt:258-270; DownloadPageLoader.kt:42-58 | **Blocker** | 2 (primary), 3 (feeds "shows config page") | VERIFIED (code+test); UI impact STRONG INFERENCE |
| D2 | Single-chapter Start silently evicts all other same-source QUEUE entries (including restored ones); queue persistence then records the shrunken queue. | TranslationManager.kt:272-278, 321-337; ChapterQueueConflictDetection.kt:45-54; ChapterTranslator.kt:602-615 | Major | 2 | VERIFIED |
| D3 | Queue rehydration is lazy (first TranslationManager construction) and async; no auto-resume by design; FGS `START_NOT_STICKY`. List built before restore shows NOT_TRANSLATED; user must find and press Start. | TranslationManager.kt:128, AppModule.kt:132; ChapterTranslator.kt:148-175; TranslationForegroundService.kt:55-61 | Major (perceived loss) | 2 | VERIFIED behavior / attribution STRONG INFERENCE |
| D4 (F1) | `updatePageFromCurrentSnapshot` echoes current lease token — compat writers can mutate a batch-owned page; ownership check only in `updatePage`. | ChapterTranslationStore.kt:486-490, 1552-1557 vs 930-934 | Major (fence hole; mitigated by caller discipline) | 4 | VERIFIED |
| D5 (F2) | Rejected durable writes still update in-memory state and report Accepted — silent divergence; un-persisted work lost on restart. | ChapterTranslationStore.kt:449-452, 476-479, 1155-1162; 2026-08-21 brief (device repro) | Major | 2, 4 | VERIFIED mechanics / HIGH for recurrence |
| D6 | Legacy flat-file write is non-atomic direct overwrite (dead `tempFileNameFor` proves unimplemented atomicity). Only reachable when artifact bridge fails or legacy authority persists; crash corrupts → empty-start → destructive delete. | ChapterTranslationStore.kt:1746-1750, 1839; TranslationManager.kt:501-510 | Major (conditional) | 2, 4 | VERIFIED mechanics / medium reachability |
| D7 | Write amplification: full-manifest + full/duplicate snapshot rewrites per stage emission; retention sweep per promotion; O(N²) per chapter on SAF. | ChapterTranslationStore.kt:1177-1316; ChapterArtifactStore.kt:398-406, 1133-1147; ChapterDocumentIo.kt:127-146 | Major (perf + wider crash windows) | 4 (also 1 indirectly) | VERIFIED |
| D8 | Summary sidecar non-atomic direct overwrite. | ChapterTranslationSummaryStore.kt:58-63 | Minor | 2 (fail-safe direction) | VERIFIED |
| D9 | Queue persistence uses async `apply()`; kill during flush loses last queue mutation. | TranslationQueueStore.kt:41-48 | Minor | 2 | VERIFIED (API semantics) |
| D10 | Legacy glossary non-atomic overwrite; corrupt → silently empty. | ChapterTranslationStore.kt:1632-1657 | Minor | — | VERIFIED |
| D11 | Per-stage artifact transactions (`beginStage`, `commitStagePayload`, `promoteCandidate`, `markCandidateStageFailed`) have **no production callers** — documented stage-level durability/recovery (RUNNING→FAILED_RETRYABLE) is dead code; production durability rides entirely on page-snapshot bridge. | grep across app/src/main: only definitions + ChapterTranslationStore.kt:1991 (`materializeLegacyCommittedSnapshot`, migration-time) | Minor↔Major (design intent vs reality; interacts with symptom 1) | 4 (and 1 for Specialist A) | VERIFIED |
| D12 | Orphaned cleaned images in `X_images/` (outside managed retention) accumulate on crash/timeout; only prefix-deleted on explicit resets. | TranslationPipeline.kt:3650-3661; ChapterArtifactStore.kt:104-108 (scope note); TranslationManager.kt:1210-1226 | Minor | 4 | VERIFIED |
| D13 | Destructive read: `getChapterTranslation` deletes flat file on any decode failure. | TranslationManager.kt:501-510 | Minor-Major (compounds D6) | 2, 4 | VERIFIED |
| D14 (F5) | Cancel/replace flows release in-flight page leases before workers unwind; completed native results for that page are discarded (rejected writes). | ChapterTranslationStore.kt:1121-1127; TranslationManager.kt:346-360 | Minor (intentional cancel semantics) | 4 | VERIFIED |
| D15 (F6) | Reset/preflight paths bypass ActiveChapterStoreRegistry and open second store instances over the same documents. | TranslationManager.kt:1012, 1088, 1135, 1193, 1244 | Minor | 4 | VERIFIED |

---

## 7. Root cause per Director symptom (assigned: 2 and 4)

**Symptom 2 — "Batch translation is lost after app cleared or restart."**

Root causes, in order of contribution (HIGH confidence overall):
1. D1 — status/read-side never migrated: completed chapters read as NOT_TRANSLATED
   after restart (the artifacts are still on disk; the app just looks at a file it froze
   empty).
2. D2 — restored queue entries silently evicted when any single chapter of the same
   source is started.
3. D3 — lazy, non-auto resume + dead foreground service after process death.
4. D5/D6 — durable writes that were rejected (or flat writes torn by a crash) are lost
   while the UI showed them complete.
5. If "cleared" means system "Clear storage": loss is by definition total for
   app-private storage (ASSUMPTION about translations directory location).

**Symptom 4 — "flawed ownership transfer, disk I/O, and more."**
Covered by F1-F7 (ownership) and D6-D13 (I/O), with D5 the most consequential overlap:
ownership fencing can silently discard completed work instead of committing it.

---

## 8. Plain-language explanations (non-technical)

- **D1 (the big one):** The app changed *where it saves* translations (a new filing
  system next to the old one) but forgot to change *where it looks*. After a restart it
  reads the old, now-empty file, concludes "nothing was translated", and shows the
  chapter as untranslated — even though every finished page is still safely on disk.
- **D2:** If you restart the app and then start translating any one chapter, the app
  quietly throws the other waiting chapters of the same series out of the line, without
  asking.
- **D3:** When the app is killed, translation stops by design and nothing starts by
  itself; you must press Start again — and depending on D1/D2, what you come back to may
  not look like what you left.
- **D5:** Sometimes the app fails to file a finished page but still shows it as done on
  screen. The on-screen success was never actually saved; after a restart it is gone.
- **D6/D13:** The old save file is overwritten in place (like re-writing a letter while
  someone may cut the power). If that is interrupted, the file is left broken — and a
  later read responds by throwing the broken file away entirely.
- **D7:** For every small step of every page, the app rewrites the chapter's entire
  index card catalogue and re-copies the whole page — many times over. It is safe but
  slow, hammers the storage, and multiplies the windows where a crash can interrupt it.
- **F1:** The "only one worker per page" rule can be bypassed by certain callers who
  quote the current worker's badge number instead of their own.

---

## 9. Open questions

1. Was the read side (persistedChapterStatus / getChapterTranslation / DownloadPageLoader)
   scheduled to migrate to manifest reads in a later phase ("UI phases switch
   consumption", phase-2 doc) that never landed? (Phase 7 reference in
   phase-2-artifact-schema.md:64-65 suggests yes — UNKNOWN.)
2. Are `beginStage`/`commitStagePayload` intended to be wired into the live pipeline
   (stage-level reuse/recovery), or is the page-snapshot bridge the accepted end-state?
   (D11 — UNKNOWN intent, VERIFIED absence.)
3. Does "app cleared" in the Director's report mean force-stop/recents-swipe or system
   "Clear storage"? Determines whether scenario (b) or (c) dominates. (UNKNOWN.)
4. Where is the default translations directory (app-private vs external SAF)? Affects
  clear-storage survivability of artifacts. (ASSUMPTION: user-configurable via
   storageManager; default not verified.)
5. Reader key-matching for pre-translated pages (`resolvePageKey` vs store keys /
   `sourceFileName`) — the in-code diagnostic note (ReaderViewModel.kt:2714-2717)
   indicates a separate display-path bug ("pre-translated page shows its original
   image") that would compound symptom 2's perception. Owned by Specialist C.
6. Does any SAF provider exhibit the atomic-rename failure mode assumed recoverable
   (backup retained when primary rename fails — ChapterArtifactStore.kt:97-103)?
   Device-dependent; UNKNOWN.
