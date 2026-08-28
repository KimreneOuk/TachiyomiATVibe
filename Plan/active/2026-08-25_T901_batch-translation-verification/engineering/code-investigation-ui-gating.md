# T901 — Specialist C: UI gating, config popup, and resume-vs-config (Director symptom 3)

Investigation of the working tree as-is (HEAD `7c78a46` + uncommitted edits to
`MangaScreenModel.kt` and `DownloadCache.kt`, verified via `git diff`).
Research only; no code changed. All claims cite `file:line` and are classified
VERIFIED / STRONG INFERENCE / ASSUMPTION / UNKNOWN.

---

## 1. Executive summary

- The "configuration popup" is `Dialog.ConfirmTranslation` (`ConfirmTranslationDialog.kt:38`), gated ONLY by the `translationConfirmPretranslate` preference, which **defaults to true** (`TranslationPreferences.kt:77`). There is no state-aware "resume" decision anywhere in the UI: partially/fully translated chapters, restored queue entries, and error chapters all route through the same popup. That is the direct root of "still shows the configuration page instead of resuming."
- At HEAD (before the uncommitted edit), the popup's Translate button partitioned chapters using the **cached** `downloadState`. A stale/empty `DownloadCache` index misrouted downloaded chapters into "download first" — and `Downloader.queueChapters` silently drops already-downloaded chapters (`Downloader.kt:280`), so the in-memory translate-after-download request never fires: the button does nothing, with zero feedback. The uncommitted working-tree edit (live `isChapterDownloaded(..., skipCache = true)`, `MangaScreenModel.kt:958-966`) fixes this path but is **not committed**.
- Reader (manual/rolling-auto) and batch translation write to the SAME `ChapterTranslationStore`, the SAME fingerprint scheme, and the SAME artifact manifests, so batch CAN reuse reader work. But the reader path never creates a queue entry or batch tracker, and publishes the summary sidecar as `READY_WITH_WARNINGS` with `expectedPageCount = pages-so-far` (`TranslationPipeline.kt:3455-3463`), so a fully reader-translated chapter never reports `TRANSLATED` — the chapter list keeps offering "Translate" (→ popup).
- Additional silent no-ops between button and work: `queueChapter`'s already-in-queue early return combined with `start()`'s `isRunning` early return can strand an ERROR chapter (no reset, no feedback) while any other batch is alive; non-HttpSource chapters return silently; multi-select `translateChapters` skips the conflict preflight and stale eviction entirely.
- Commit `7c78a46` ("lift manual reader suppression") removed the guard that made the reader's per-page Translate button silently no-op whenever the chapter had an active batch entry — if the Director's test build predates this commit, in-reader "button does nothing" reports are explained by it.

---

## 2. Popup decision tree — when config popup vs "resume"

VERIFIED — there is no resume branch at the UI layer.

```
User taps "Translate" (chapter indicator chip: ChapterTranslationIndicator.kt:104-105, 235;
                      ERROR chip: 262-263; multi-select bottom bar: MangaScreen.kt:332, 581)
        │
        ▼
runChapterTranslationActions(START)                     MangaScreenModel.kt:827-840
        │
        ▼
pref translationConfirmPretranslate()?                  MangaScreenModel.kt:835
(default TRUE — TranslationPreferences.kt:77)
        │
   ┌────┴─────────────────────────────┐
   │ true (default)                   │ false
   ▼                                  ▼
Dialog.ConfirmTranslation             confirmChapterTranslation()
(config popup; read-only                (MangaScreenModel.kt:946)
 summary of langs/engine/OCR/tokens)
   │  Translate ──► confirmChapterTranslation()
   │  Cancel ────► dismiss (nothing)
   │  "Don't show again" ──► setConfirmPretranslate(false)   MangaScreenModel.kt:1019-1021
```

Key point: the gate consults **only the preference**. It never consults:

- whether the chapter has a queue entry (QUEUE/TRANSLATING/ERROR),
- whether the chapter has persisted translations (TRANSLATED / READY_WITH_WARNINGS),
- whether a prior batch or reader session produced partial artifacts.

For a chapter showing TRANSLATED/READY_WITH_WARNINGS, the chip's dropdown
offers exactly "Translate" (START) and "Delete"
(`ChapterTranslationIndicator.kt:231-246`) — so the only "continue/retranslate"
affordance a previously-translated chapter has is the one that always opens the
config popup. This is the mechanical root of symptom 3's second sentence.

Where "resume" actually lives (all BELOW the popup, invisible to the user):

1. Queue-level resume: a chapter already in the queue is not re-added;
   `start()` flips non-terminal statuses back to QUEUE
   (`ChapterTranslator.kt:188-198`); after app restart `restoreQueue()`
   rehydrates persisted queue ids as QUEUE (`ChapterTranslator.kt:152-175`).
2. Page-level resume: `BatchResumeGateDecider.decide` +
   `PageWorkPlanner.planPage` decide per-page SKIP_ALL / INPAINT_ONLY / FULL
   from persisted stage statuses, fingerprints, and physical cleaned-image
   presence (`BatchResumeGateDecider.kt:32-48`, `PageWorkPlanner.kt:58-95`,
   `TranslationPipeline.kt:1441-1522`). The comment at
   `MangaScreenModel.kt:853-858` ("artifact scan (BatchResumeGateDecider)
   reuses READY work") refers to this layer — it is entered only AFTER the
   popup is accepted.

---

## 3. Button-click → work-start trace (every silent no-op flagged)

Path: popup Translate → `ConfirmTranslationDialog` confirm button
(`ConfirmTranslationDialog.kt:54-63`, dismiss-then-confirm) →
`MangaScreenModel.confirmChapterTranslation` (`MangaScreenModel.kt:946-991`).

| # | Step | Condition that stops work | Feedback? | Evidence |
|---|------|---------------------------|-----------|----------|
| 1 | `confirmChapterTranslation` | `successState == null` | **none — SILENT** | MangaScreenModel.kt:947 |
| 2 | group resolution from `pendingTranslationGroup` | falls back to `listOf(item)`; harmless | n/a | MangaScreenModel.kt:948-951 |
| 3 | partition on `isChapterDownloaded(..., skipCache = true)` | chapter not on disk → `awaitingDownload` | none from translation side (download icon appears) | MangaScreenModel.kt:958-972 (uncommitted fix; HEAD used cached `downloadState`) |
| 3a | `awaitingDownload` → `queueTranslationAfterDownload` (in-memory map) + `downloadChapters` | Downloader **drops already-downloaded chapters** (`findChapterDir(...) == null` filter) → `startTranslationAfterDownloadIfRequested` (Downloader.kt:437) never fires → request leaks | **none — SILENT** (this was the HEAD behavior whenever `downloadState`/DownloadCache was stale; see Defect D1) | Downloader.kt:273-287, TranslationManager.kt:161-170 |
| 3b | download errors (network/source) | `download.status = ERROR; return` → translate-after-download request leaks | download error icon only; **translation side silent** | Downloader.kt:386-389, 438-444 |
| 4 | `if (downloaded.isEmpty()) return` | nothing downloaded (all went to 3a) | **none of its own — SILENT** | MangaScreenModel.kt:973 |
| 5 | `downloaded.size > 1` → `translateChapters` | (no conflict preflight, no stale eviction on this branch — Defect D9) | queue badges appear only if enqueue succeeds | MangaScreenModel.kt:974-977; TranslationManager.kt:280-288 |
| 6 | single: `translateChapterPreflight` | `RunningConflict` → `Dialog.RunningTranslationConflict` (HAS feedback: dialog) | dialog | MangaScreenModel.kt:983-990; TranslationManager.kt:301-311; ChapterQueueConflictDetection.kt:28-37 (only TRANSLATING entries of same source count) |
| 7 | `NoConflict` → `launchTranslateChapter` → `TranslationManager.translateChapter` | `chapter.id == null` | **none — SILENT** (theoretical) | MangaScreenModel.kt:1008-1012; TranslationManager.kt:272-273 |
| 8 | `evictStaleQueuedChapters` | evicts same-source QUEUE entries other than the target (artifacts preserved) | none (by design; log only) | TranslationManager.kt:321-337 |
| 9 | `ChapterTranslator.queueChapter` | source not `HttpSource` (local manga/EH-ish sources) | **none — SILENT** | ChapterTranslator.kt:343 |
| 10 | `queueChapter` | chapter already in queue (ANY status incl. ERROR) → return | **none — SILENT** (correct for QUEUE/TRANSLATING because start() re-arms; strands ERROR — Defect D4) | ChapterTranslator.kt:344 |
| 11 | `queueChapter` | invalid from/to language pref (STRICT no-fallback) | toast (easy to miss after a modal) | ChapterTranslator.kt:345-360 |
| 12 | `queueChapter` | ML Kit active + unsupported target language | toast | ChapterTranslator.kt:361-368 |
| 13 | `TranslationManager.startTranslation` → `translator.start()` | `isRunning` → return false WITHOUT resetting stale ERROR entries (D4); queue empty → return false | **none — SILENT** | TranslationManager.kt:242-249; ChapterTranslator.kt:188-198 |
| 14 | job loop admission | only first source-group's head chapter admitted (`groupBy{source}.take(1)`); our chapter waits behind ANY other source's chapter | progress idle; no explicit "waiting" UI | ChapterTranslator.kt:254-268 |
| 15 | `translateChapterInternal` | translation file cannot be created → `ERROR` status | chapter badge turns ERROR (indirect) | ChapterTranslator.kt:385-408 |
| 16 | `translateChapterInternal` | chapter dir missing/deleted → `ERROR` status | chapter badge turns ERROR (indirect) | ChapterTranslator.kt:412-427 |
| 17 | `pipeline.translateBatch` | `orderedStreams.isEmpty()` → return | **none — SILENT** (chapter then reconciles from store) | TranslationPipeline.kt:1154 |
| 18 | engine setup timeout (`withNativeLane` null) | releases leases, returns without running | log only; chapter re-queued? — no, silently ends pass | TranslationPipeline.kt:1170-1187 |
| 19 | all pages REUSE | batch runs a scan, reconciles, re-publishes summary, marks TRANSLATED, dequeues | for the user: popup closes and nothing visibly changes ("does nothing" perception on already-translated chapters) | TranslationPipeline.kt:2701-2736; ChapterTranslator.kt:291-295 |
| 20 | exceptions in job | caught → `status = ERROR` | badge (indirect) | ChapterTranslator.kt:299-308 |

The two paths that produce the pure "tap → popup → nothing, ever" experience
are **#3a** (HEAD's stale-cache misroute; D1) and **#13+#10** (ERROR chapter
stranded while translator job alive; D4). #19 explains the milder "nothing
visibly happened" on already-complete chapters.

---

## 4. Reader path vs batch path — what each writes vs what the resume gate checks

Entry points:
- Reader manual: `ReaderViewModel.translateSinglePage`
  (`ReaderViewModel.kt:2096-2211`) → `translationScheduler.translatePage(force=…)`
  (`TranslationScheduler.kt:568`). Rolling auto: same scheduler via
  `requestAutoWindow`/`updateAutoWindow` (`TranslationManager.kt:788-823`).
- Batch: manga screen START → `translateChapter`/`translateChapters`
  (`TranslationManager.kt:272-288`) → `ChapterTranslator.queueChapter` → job →
  `translateChapterInternal` → `pipeline.translateBatch`
  (`TranslationPipeline.kt:1145`).

| Concern | Reader (manual / rolling auto) | Batch (manga screen) | What the resume gate checks |
|---|---|---|---|
| Shared `ChapterTranslationStore` (page JSON) | YES — via `activeStores` registry, `PageWriteOrigin.READER_ADHOC` lease (`TranslationPipeline.kt:776, 790`; `ChapterTranslationStore.kt:1356`) | YES — same registry/store, `PageWriteOrigin.BATCH` (`ChapterTranslator.kt:384`; `TranslationPipeline.kt:2011`) | page map from the same store (`PageWorkPlanner.kt:59-61`) |
| Stage fingerprints | SAME scheme — `batchExpectedFingerprints(fromLang, toLang)` stamped on detection/ocr/inpaint/translation/layout (`TranslationPipeline.kt:3183-3203`) | SAME, stamped per completed stage (`TranslationPipeline.kt:1262-1276`, `4079-4081`) | fingerprint equality incl. source sha256 (`PageWorkPlanner.kt:205-212, 268-274`; source hashes `TranslationPipeline.kt:1254-1260`) |
| Artifact manifest / cleaned images | YES — through `persistArtifactMutationLocked` with `ArtifactOrigin.READER_ADHOC` (`ChapterTranslationStore.kt:1237-1239, 1355-1357`) | YES — `ArtifactOrigin.BATCH` | artifact record status/fingerprint/payload (`PageWorkPlanner.kt:283-350`) |
| Translation queue entry (`queueState`) | **NO** — reader work is invisible to `queueState`, `isBatchTranslationActive`, queue persistence | YES — `addToQueue` + `TranslationQueueStore` persistence; restored as QUEUE on launch (`ChapterTranslator.kt:141-175, 584-590`) | `getQueuedTranslationOrNull` first in `getChapterTranslationStatus` (`TranslationManager.kt:369-370`) |
| Batch progress tracker | **NO** tracker | YES — `batchTrackerFactory` (`TranslationManager.kt:121-124, 844-854`) | `observeBatchProgress` falls back to store-derived progress when no tracker (`TranslationManager.kt:869-926`) |
| Summary sidecar (`ChapterTranslationSummaryStore`) | YES — after every accepted commit, but **always `READY_WITH_WARNINGS` with `expectedPageCount = pages-written-so-far`** (`TranslationPipeline.kt:3451-3469`) | YES — at batch finish with true page count and reconciled outcome (`TranslationPipeline.kt:2721-2732`) | `persistedChapterStatus`: null/short-count summary ⇒ `READY_WITH_WARNINGS`; outcome must be TRANSLATED for `TRANSLATED` (`TranslationManager.kt:426-462`) |
| Foreground service | no | YES (`startTranslation` → `TranslationForegroundService.start`, `TranslationManager.kt:242-249`) | n/a |
| Page keys | `sourceFileName` for downloaded chapters; **URL-derived** for streamed (`PageTranslationKey.kt:4-6`; `ReaderAutoTranslationPageResolver.kt:206-213`) | disk file names, natural order (`ChapterTranslator.kt:435-457`) | exact key equality — mismatch ⇒ page counted missing/stranded (`BatchProgressReconciler.kt:30-66`) |
| Ownership marks read by UI | only store/summary state | queue status + tracker + summary | `toChapterListItems` uses queue + summary, but ONLY when `downloadState == DOWNLOADED` (`MangaScreenModel.kt:643-652`) |

Net: reader→batch reuse is designed and implemented (same fingerprints,
origin-agnostic planner; comment at `TranslationPipeline.kt:3175-3177`
"Reader-adhoc output is displayable; a later batch reuses it when its
fingerprints still match"). The asymmetries that matter for symptom 3 are the
**queue entry**, the **tracker**, and the **summary outcome/expected-count**
(reader chapters can never certify `TRANSLATED` without a batch run), plus
**URL-keying** for streamed chapters (rekey is best-effort and silently aborts
on count mismatch, `TranslationManager.kt:527-540`).

VERIFIED for each row (code cited). The claim that fingerprints match across
paths "when config unchanged" is VERIFIED for the code path; whether the
Director's reader sessions used the same config as the later batch is UNKNOWN.

---

## 5. Defect list

| ID | Defect | Evidence | Severity | Confidence | Explains (symptom 3) |
|---|---|---|---|---|---|
| D1 | Popup Translate routed chapters by cached `downloadState`; stale/empty `DownloadCache` index (empty index was committed and frozen for 1 h on SAF enumeration failure) misclassified downloaded chapters as "not downloaded" → translate-after-download request + download enqueue; Downloader drops already-downloaded chapters → translation never starts, request leaks. **Fixed only by uncommitted working-tree edits** (live `skipCache=true` check; DownloadCache empty-index guard). | HEAD→WT diff of MangaScreenModel.kt:949-972; Downloader.kt:273-287; DownloadCache.kt uncommitted diff (`renewalFailed` guard); Downloader.kt:437 | **Blocker** (at HEAD; WT-fixed but uncommitted) | mechanism VERIFIED; that it is what the Director hit = STRONG INFERENCE | "translation button that does nothing when clicked" |
| D2 | START action's popup gate consults only `translationConfirmPretranslate` (default true). No state-aware bypass for QUEUE/ERROR/partial/fully-translated chapters; the only resume affordance on a translated chapter is START → config popup. | MangaScreenModel.kt:827-839; TranslationPreferences.kt:70-77; ChapterTranslationIndicator.kt:231-246 | **Major** (product/UX root) | VERIFIED | "still shows the configuration page instead of resuming translation" (both batch- and reader-produced cases) |
| D3 | Chapter-list translation state is computed only when cached `downloadState == DOWNLOADED`; stale cache hides real translation/queue state (shows NOT_TRANSLATED + "will download first" badge), pushing users into the popup path and hiding restored QUEUE entries. | MangaScreenModel.kt:632-652 (cached check at 635; gate at 644) | Major | VERIFIED (logic); prevalence depends on cache staleness = STRONG INFERENCE | supports both halves; cross-links symptom 2 |
| D4 | Re-tap on a chapter whose queue entry is ERROR while any other batch is alive: `queueChapter` returns silently (already in queue) and `start()` early-returns on `isRunning` without resetting ERROR → nothing starts, no feedback, until the other batch finishes and stops the job. | ChapterTranslator.kt:344, 188-198, 254-268; Translation.State order Translation.kt:34-46 (ERROR=4 excluded from admission filter `<= TRANSLATING`) | Major | VERIFIED (logic); frequency medium | intermittent "button does nothing" on errored chapters |
| D5 | Reader path always certifies the chapter as `READY_WITH_WARNINGS` with `expectedPageCount = pages-so-far`; a fully reader-translated chapter therefore never reaches `TRANSLATED` (unless a batch completes), so the manga screen keeps treating it as partial and offers Translate (→ popup), never a distinct resume. | TranslationPipeline.kt:3451-3469; TranslationManager.kt:426-462, 376-384 | Major | VERIFIED | "chapters that … translation directly in the reader page" still show config |
| D6 | For streamed chapters reader keys are URL-derived; post-download rekey aborts silently on any count mismatch, leaving URL-keyed entries; the subsequent batch sees missing expected keys → full re-translation (+ "unexpected page" warnings). Wasteful, not a user-visible failure. | PageTranslationKey.kt:4-6; ReaderAutoTranslationPageResolver.kt:206-213; TranslationManager.kt:527-551; BatchProgressReconciler.kt:31-36 | Minor | VERIFIED code path; occurrence UNKNOWN | contributes to "resume redoes work" perception |
| D7 | Multi-chapter START uses `translateChapters`, which skips both the `RunningConflict` preflight and `evictStaleQueuedChapters` (asymmetric with the single path); stale QUEUE entries of the same source can sit ahead of requested chapters. | MangaScreenModel.kt:974-977; TranslationManager.kt:272-288 | Minor | VERIFIED | batch-START inconsistency; delayed start can read as no-op |
| D8 | (Historical, pre-`7c78a46`) reader per-page Translate was silently suppressed whenever the chapter had an active/queued batch entry ("manual translate suppressed"). Lifted by HEAD commit. If Director's build is older, this explains in-reader no-op taps. | git show 7c78a46 (removed `isBatchTranslationActive` guard in ReaderViewModel.translateSinglePage) | Minor (now fixed at HEAD) | VERIFIED history; build provenance UNKNOWN | reader-side "button does nothing" |
| D9 | Config-invalid and MLKit-unsupported guards in `queueChapter` surface only a toast AFTER the modal popup closes; the popup itself does not pre-validate the configuration it just displayed. | ChapterTranslator.kt:345-368; ConfirmTranslationDialog.kt (no validation) | Minor | VERIFIED | easy-to-miss failure feedback |
| D10 | Already-TRANSLATED chapter re-START: batch performs a full reuse scan and re-publishes; from the UI nothing changes except the popup closing (perceived no-op; arguably correct behavior but invisible). | TranslationPipeline.kt:1282-1293, 2701-2736 | Minor | VERIFIED | mild "button does nothing" reports |

Root-cause summary for symptom 3:
- "Button does nothing": **D1 (primary, HEAD), D4 (secondary), D10/D9 (peripheral)** — confidence high for D1 as the dominant cause given the bug-5 fix comments and the Director report; working tree already contains the D1 fix uncommitted.
- "Config page instead of resume": **D2 (primary), D5 (reader-case enabler), D3 (state-hiding enabler)** — confidence high; note this is partly a missing product affordance, not a regression.

---

## 6. Plain-language explanations (non-technical)

- **D1 — the wrong "is it downloaded?" answer.** The app keeps a cached list of what's downloaded to make the chapter list fast. Sometimes that list goes stale or comes up empty. The Translate button used to trust that list: it saw "not downloaded", queued a download, and waited for the download to finish before translating. But the downloader saw "actually, this chapter is already on disk", skipped it, and never told the translation side. Result: you press Translate, the popup closes, and nothing ever happens. A fix for exactly this is sitting **uncommitted** in the working tree (the button now checks the disk directly), plus a second uncommitted fix that stops the cache from going stale-empty for an hour.
- **D2 — the popup doesn't know what you're resuming.** Before any batch translation, the app shows a settings-review popup. Showing it is controlled by a single toggle that is ON by default. The app never asks "does this chapter already have translation work I should just continue?" — fresh, half-done, errored, and fully-translated chapters all get the same popup. So "resuming" always looks like "configuring again".
- **D3 — the chapter list can lie about translated chapters.** When the download cache is stale, translated or in-progress chapters display as "not translated", hiding the resume state and steering you to the Translate/config flow.
- **D4 — an errored chapter can be stuck in line.** If a chapter previously failed and another translation is still running, pressing Translate quietly does nothing: the app won't re-arm the failed chapter until the other job finishes. No message tells you this.
- **D5 — reader-translated chapters are permanently "partial".** Translating page-by-page in the reader saves each page, but the chapter's completion certificate is only ever marked "partial". So even if you translated every page by hand, the manga screen treats the chapter as unfinished and offers Translate — which shows the config popup.
- **D10 — pressing Translate on a finished chapter does "nothing" visibly.** The app scans the chapter, finds every page done, marks it complete again, and closes the popup. Correct, but indistinguishable from a dead button.

---

## 7. Open questions

1. Build provenance: did the Director's APK include commit `7c78a46` and/or the uncommitted `MangaScreenModel.kt`/`DownloadCache.kt` edits? D1 vs D4/D10 weighting for "button does nothing" depends on this. (Needs Director/device logs: look for "DownloadCache: renewal skipped" and "TachiyomiAT translate START".)
2. Product intent: should a resume path exist that bypasses the config popup (e.g., for QUEUE/ERROR/partial chapters), or should the popup be suppressed after first successful use per manga? D2 fix is a product decision, not just code.
3. Should the TRANSLATED-chapter menu offer "Translate" at all (D10), or a "retranslate (new settings)" label distinct from resume?
4. Does `translateAfterDownload` need persistence? It is in-memory only; process death after "download first" loses the translation request (overlaps symptom 2, Specialist B).
5. UNKNOWN: whether reader sessions that produced the Director's "already translated" chapters used the same engine/language config as the later batch attempts — fingerprint reuse only holds when config is unchanged (`PageWorkPlanner.kt:211-212`).
6. Minor verification opportunity: the `showAgain` checkbox binding reads the preference imperatively in composition (`MangaScreen.kt:344`); confirm recomposition behavior when the checkbox toggles mid-dialog (no bug observed in code reading, but it is not a state-hoisted pattern).

