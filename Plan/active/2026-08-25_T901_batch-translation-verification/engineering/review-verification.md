# T901 — Independent Reviewer Verification

Scope: the 8 highest-impact claims from Specialists A (scheduling), B (persistence),
C (UI gating), verified against the current working tree (HEAD `7c78a46` + uncommitted
`MangaScreenModel.kt` / `DownloadCache.kt` edits, confirmed via `git status` / `git diff`).
All evidence below was read by me in source; HEAD behavior verified via `git show`.
Research only — no code changed.

---

## 1. Verdict table

| # | Claim | Verdict | My evidence | Corrections / notes |
|---|-------|---------|-------------|---------------------|
| 1 | Persistence D1 — post-restart status reads only frozen flat JSON | **CONFIRMED (Blocker)** | `ChapterTranslationStore.kt:1724-1731` — `persistLocked` returns early (`authority == ARTIFACTS → return true`) so the flat file is never written post-cutover; `publishLocked` bridges to artifacts first and only calls `schedulePersist` when authority ≠ ARTIFACTS (`ChapterTranslationStore.kt:1148-1168`). Read side: `TranslationManager.kt:426-467` — `persistedChapterStatus` decodes ONLY the flat file (`legacyPageJson.decodeFromStream`, line 437) and bails at `!file.exists() || length <= 2L` (435). Status resolution order: queue → live `activeStores` (in-memory, empty after restart) → flat file (`TranslationManager.kt:362-388`). Completed chapters are dequeued (`ChapterTranslator.kt:291-295`), so the queue cannot feed their status either. | **Amplifying finding:** `ChapterTranslationStore.open()` IS artifact-aware — it rehydrates `livePages`/`committedPages` from the manifest snapshots (`ChapterTranslationStore.kt:1884-1912, 1998-2012`) — but neither `persistedChapterStatus` nor `getChapterTranslation` uses `open()`; both decode the flat file directly. The data is fully recoverable on disk; only the read side never migrated. Also: `DownloadPageLoader.kt:42-58` → `getChapterTranslationForReader` falls back to the flat decode (`TranslationManager.kt:490-509`), so after restart the READER also displays zero translations for post-cutover chapters (reader- and batch-produced alike) until a store is opened by new translation activity. This widens symptom 2 beyond the chapter-list badge. The summary sidecar IS read (`TranslationManager.kt:441`) but only after the flat-file gate passes, so it cannot rescue status. |
| 2 | UI D1 — HEAD partitioned on cached downloadState; Downloader drops downloaded chapters; WT edits fix it | **CONFIRMED** | HEAD (`git show HEAD:...MangaScreenModel.kt`): lines 946-953 — `confirmChapterTranslation` used `group.filter { it.downloadState == Download.State.DOWNLOADED }` (cached). WT: `MangaScreenModel.kt:946-991` — partition on `downloadManager.isChapterDownloaded(..., skipCache = true)` (958-966) with a comment naming exactly this bug. `Downloader.kt:273-287` — `queueChapters` filters out already-downloaded chapters (`.filter { provider.findChapterDir(...) == null }`, line 280) with no callback; `startTranslationAfterDownloadIfRequested` fires only on real download completion (`Downloader.kt:425-437`); the request map is in-memory (`TranslationManager.kt:161-170`). DownloadCache uncommitted diff adds the `renewalFailed` guard (keep previous index, 1-min retry) and `lastRenew = 0` for an empty persisted index. | The WT edits do close the specific misroute (partition now uses the same live provider check as the Downloader's filter). Residual gaps the specialists under-stated: (a) for chapters genuinely not on disk, a download ERROR still leaks the translate-after-download request silently (`Downloader.kt:386-389, 442-443`); (b) `isChapterDownloaded(skipCache = true)` is a synchronous SAF probe per chapter inside the UI event handler — main-thread I/O jank risk for multi-select groups (new, minor); (c) the fix is uncommitted — at HEAD the blocker stands. |
| 3 | UI D2 — popup gate consults only the preference, no state-aware resume bypass | **CONFIRMED** | `MangaScreenModel.kt:827-840` — START branch gates solely on `translationConfirmPretranslate().get()`. `TranslationPreferences.kt:69-77` — default `true`. Grep of `translationConfirmPretranslate` across `app/src/main`: only the settings screen, the dialog itself, and the MangaScreen gate/checkbox — no call site consults queue status, persisted state, or partial artifacts. | None. This is a product-level gap (no resume affordance exists at the UI layer), not a regression. |
| 4 | UI D5 — reader path certifies only READY_WITH_WARNINGS with expectedPageCount = pages-so-far | **CONFIRMED** | `TranslationPipeline.kt:3166-3252` — `translateSinglePageHttpRender` is the reader manual + rolling-auto HTTP phase (rolling-auto consumer calls it at `TranslationPipeline.kt:1080-1083`). After every accepted commit it publishes `expectedPageCount = store.state.value.size` (pages written so far) with `terminalOutcome = READY_WITH_WARNINGS` hardcoded (`TranslationPipeline.kt:3451-3469`, outcome at 3460). `TranslationManager.kt:445-462` — count mismatch → `READY_WITH_WARNINGS`; `TRANSLATED` requires `summary.outcome() == TRANSLATED` (452-453). | Precision: even when the reader translates ALL pages (count matches), the outcome is still hardcoded `READY_WITH_WARNINGS` (3460) — so the count-mismatch mapping is secondary; the outcome itself can never be TRANSLATED without a batch run. Note also that post-restart the summary is unreachable anyway (Claim 1), so D5's visible effect is in-session only; the two defects compound. |
| 5 | Scheduling D1 — provider lane taken only by batch; reader bypasses; 429 backoff sleeps inside the lane | **CONFIRMED** | Grep: `SharedProviderRequestAdmission.withRequest` appears at exactly two sites — `TranslationPipeline.kt:2519` (standard per-page batch) and `2566` (AI envelope batch); both inside the `translateBatch` workers. Reader `runTranslate` (`TranslationPipeline.kt:3218-3252`) and the rolling-auto consumer (`1080-1083`) call the translator with no admission. Documented intent says the lane is "Process-wide ... shared by manual, auto, batch, and revision callers" (`TranslationStageContracts.kt:201-206`) — verified deviation. Retry: `TranslationRetry.kt:26-103` — `maxAttempts = 3` (2 retries), Retry-After `coerceIn(0, 30_000)` (85), `delay(capped)` (99); used inside the translators (`GeminiTranslator.kt:82,104`; `OpenAiCompatibleTranslator.kt:95`), which run inside `withRequest { translateChunkAi(...) }` (`TranslationPipeline.kt:2564-2572`); the coordinator awaits that job before the next OCR discovery pass (`SequentialBatchCoordinator.kt:322-323, 330-386`) — so backoff freezes the chunk barrier. | Also confirmed D7 corollary: the mutex wraps the ENTIRE `translateChunkAi` (store writes, renders, glossary persist), not just HTTP — harmless today only because the reader never takes the lane, but it makes any future fix that merely wires the reader in create a new stall. Fix design should narrow the critical section AND admit the reader. |
| 6 | Scheduling D3 — terminal context gap durably fails every later page | **CONFIRMED** | `BatchContextFrontier.kt:50-79` — terminal non-textless failure enters `failures`; frontier walk sets `gapIndex`; `blocksLaterAi` true for every later index. Deterministic over-budget rejects: `StreamingChunkPlanner.kt:87-88` (single block) and `:114` ("Page text exceeds the ... AI context budget"). Rejected pages are marked durably FAILED and recorded as terminal failures in `completeChunk` (`TranslationPipeline.kt:2604-2623`, `recordContextPage(..., terminalFailure = true)` at 2622). Later pages are durably failed at BOTH the admit path (`2477-2495`) and the envelope path (`1727-1752`) with reason "blocked by non-textless terminal context gap at index N". | On re-run, `seed()` re-derives the gap from the persisted FAILED page (`BatchContextFrontier.kt:34-48` with `translationStatus == FAILED` predicate at 37-39), and the dense page is re-rejected deterministically — so the chapter can NEVER reach TRANSLATED and each run re-burns attempts on the whole tail. Pages before the gap are preserved (reuse) — the poisoning is strictly the tail. Severity: blocker for affected chapters; a plausible major contributor to symptom 1's "huge failure lists". |
| 7 | Persistence D2 — translateChapter silently evicts all other same-source QUEUE entries; shrunken queue persisted | **CONFIRMED** | `TranslationManager.kt:272-278` — `translateChapter` calls `evictStaleQueuedChapters` before enqueue; `321-337` — eviction is log-only (silent). Selection: `ChapterQueueConflictDetection.kt:45-54` — every same-source QUEUE entry except the target. Restored-after-restart entries are status QUEUE (`ChapterTranslator.kt:152-175`, line 158) and therefore evictable. Persistence of the shrunken queue: `removeFromQueueIf → persistQueue()` (`ChapterTranslator.kt:602-615`, `141-143`). | Nuance on severity: artifacts are preserved (only queue entries dropped; comment at `TranslationManager.kt:313-320`), so this is perceived queue loss, not data loss — but combined with Claim 1 (no durable status) and UI D2 (no resume affordance), the user has no way to know the evicted chapters were ever queued or translated. Note the multi-select path `translateChapters` (`TranslationManager.kt:280-288`) does NOT evict — asymmetric (UI D7). |
| 8 | Scheduling D4 — cross-origin lease stranding both directions | **CONFIRMED** | Direction A: `TranslationPipeline.kt:2011-2018` — `runOcrStage` returns null on `LeaseAcquisition.Denied` (reader-owned); the coordinator advances past the page with no in-run retry (`SequentialBatchCoordinator.kt:343-380` — cursor advances at 344; no rescan loop; the "rescanned later" comment at 2007-2010 means a later RUN). Reconciler: non-terminal non-active-generation pages → stranded (`BatchProgressReconciler.kt:53-84`, strands at 60-67/75-82); any stranded page ⇒ `chapterStatus = ERROR` (86-90). Compensating write: `TranslationPipeline.kt:2708-2719` calls `guardedBatchUpdate`, which rejects with "batch page lease missing" when no identity was ever acquired (`TranslationPipeline.kt:1372-1379`) — deferred pages never acquired a lease, so the durable FAILED mark is silently rejected; the ERROR summary is published regardless (`2721-2727`). Direction B: `TranslationPipeline.kt:855-865` — `prepareSinglePage` returns null when the reader lease is denied (batch-owned); `RollingAutoCoordinator.kt:515-518` marks the slot `Failed(retryable = true)`; `isAdmissible` excludes Failed slots (`567-571`, comment "never auto-looped"). | Recovery nuance (matches the report's wording): Failed slots are cleared only when the page leaves the desired set (`evictObsolete`, `RollingAutoCoordinator.kt:593-612`) or on a new coordinator generation (`resetState`, `280-287`) — i.e., scroll away/back or chapter re-entry. Within the window, auto-translate silently stops for those pages. Direction A severity is real but conditional on the reader holding a lease at batch-reconcile time; if the reader COMPLETED the page first, the reconciler counts it done (`BatchProgressReconciler.kt:70`). |

---

## 2. Contradiction check across the three reports

No factual contradictions found. Three interaction points checked deliberately:

1. **"Batch can reuse reader work" (UI §4) vs "everything reads the frozen flat file"
   (Persistence D1).** Not contradictory — reuse works in-session via the shared
   `ChapterTranslationStore`/fingerprints, while D1 governs the post-restart read path.
   But the combination yields a finding stronger than either report states: post-cutover
   chapters translated in the READER also lose both chapter-list status AND in-reader
   display after restart (`DownloadPageLoader.kt:42-58` flat-file fallback), because the
   artifact-aware `ChapterTranslationStore.open()` (`ChapterTranslationStore.kt:1884-1912`)
   is never used by the display/status read side. Symptom 2 therefore applies to reader
   translation, not only batch.
2. **Queue eviction framed as "by design; log only" (UI §3 step 8) vs Major defect D2
   (Persistence).** Same code, severity disagreement only. Given no durable status (D1)
   and no resume affordance (UI D2), the Persistence framing is the right one.
3. **Scheduling D8/D5 (batch commits PARTIAL, no in-chunk retry; reader retries twice)
   vs UI D5 (reader chapters stuck at READY_WITH_WARNINGS).** Consistent — they describe
   the same reader/batch asymmetry from opposite ends.

---

## 3. Root-cause ranking per Director symptom (my assessment)

**Symptom 1 — "batch does not work as intended as chunk with optimized scheduling":**
1. Scheduling D3 gap poisoning (verified: one terminally-failed page, including a
   deterministic over-budget dense page, durably fails the entire chapter tail, every run).
2. Scheduling D1 provider lane not shared + in-lane 429 backoff (verified mechanics;
   magnitude on free-tier Gemini is strong inference).
3. Scheduling D5 strict barrier (design-level throughput ceiling; barrier verified at
   `SequentialBatchCoordinator.kt:322-323`).
4. Scheduling D4 lease interop → chapter ERROR (verified mechanics, conditional reachability).

**Symptom 2 — "lost after app cleared or restart":**
1. Persistence D1 (verified blocker; dominant). Post-cutover chapters — batch OR reader —
   read NOT_TRANSLATED and display untranslated after restart while their artifacts sit
   intact on disk. Fix cost is low: the artifact-aware `open()` already exists.
2. Persistence D3 (no auto-resume; FGS `START_NOT_STICKY` — not re-verified by me, low risk).
3. Persistence D2 queue eviction (verified) + in-memory `translateAfterDownload` leak.

**Symptom 3 — "config popup / dead button / no resume":**
1. UI D1 dead button at HEAD (verified; WT-fixed but uncommitted — the fix must be committed
   before any rebuild, otherwise the Director's APK will still contain the blocker).
2. UI D2 preference-only popup gate, no resume affordance (verified; product decision needed).
3. UI D5 reader chapters never certify TRANSLATED (verified).
4. UI D4 ERROR-chapter stranding while a batch job is alive (logic consistent with
   `ChapterTranslator.kt:188-198, 344`; not independently re-traced end-to-end).

**Symptom 4 — "flawed ownership transfer, disk I/O":**
Scheduling D4 stranding (verified) and the silent memory-ahead-of-disk divergence on
rejected durable writes (Persistence D5/F2 — mechanics visible at
`ChapterTranslationStore.kt:1148-1168`: `artifactAccepted == false` still updates in-memory
state and display is the only thing skipped) are the two ownership defects I can confirm
from my own reading. Write amplification (O(N²) manifest rewrites) is consistent with
`persistArtifactMutationLocked` publishing the full manifest per page registration
(`ChapterTranslationStore.kt:1191-1202`); I did not re-audit ChapterDocumentIo.

---

## 4. Additional findings the specialists missed

1. **(Minor, new — risk of the uncommitted UI fix)** `confirmChapterTranslation` now performs
   `isChapterDownloaded(..., skipCache = true)` synchronously per chapter inside the UI
   event handler (`MangaScreenModel.kt:958-966`). For multi-select groups this is repeated
   SAF/disk probes on the main thread — jank/ANR exposure. Should be moved off main before
   committing.
2. **(Amplification of D1)** `getChapterTranslation(file)` (`TranslationManager.kt:501-510`)
   deletes the flat file on ANY decode failure. For post-cutover chapters whose flat file
   was materialized empty by `publishSummary` (`ChapterTranslationStore.kt:1781-1784`),
   this delete is harmless — but for LEGACY-authority chapters a transient SAF read error
   permanently deletes the only translation store (Persistence D13 stands; the empty-file
   variant is new context).
3. **(Fix-path note)** Because `ChapterTranslationStore.open()` already rehydrates pages
   from the artifact manifest (including when the flat file is absent — the manifest-exists
   branch at `ChapterTranslationStore.kt:1886-1899`), the D1 repair is a read-side swap in
   `persistedChapterStatus` / `getChapterTranslation` / `DownloadPageLoader` — no schema
   work required. This materially de-risks the top-ranked fix.

---

## 5. Overall assessment

All eight verified claims are **CONFIRMED** at the cited mechanics; none required
downgrading. Severity ranking stands, with two adjustments: (a) Claim 1's blast radius is
larger than reported (reader display path also affected, reader-produced chapters
included), while its fix is cheaper than the report implies; (b) Claim 2's fix exists in
the working tree but is uncommitted and carries a minor main-thread-I/O regression risk.
The single most urgent actions: commit the working-tree UI/DownloadCache fix (after moving
the live download probe off main), and migrate the three read-side call sites to the
artifact-aware store open.
