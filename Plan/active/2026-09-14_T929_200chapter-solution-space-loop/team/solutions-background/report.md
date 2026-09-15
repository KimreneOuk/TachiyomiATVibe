# T929 Round 4 — N3 download-ahead + N4 background-execution durability

- Owner: Technical Lead (background/download slice). Base `main` @ `9c19ad0`, read-only for code.
- Abbreviations: `translation/` = `app/src/main/java/eu/kanade/translation/`,
  `download/` = `app/src/main/java/eu/kanade/tachiyomi/data/download/`.
- OS-behavior claims (Doze, FGS limits) are labeled **[P-OS]** — not in repo code.
- Headline: the red team's N3/N4 premises are BOTH substantially stale. A fenced
  download→translation handoff pipeline EXISTS (N3), and a dataSync foreground
  service already runs the batch (N4). The real gaps are narrower: download
  failure/starvation UX, zero wake locks, and a never-auto-start resume policy.

## Catalog

| # | Lever | Kind | Anchor (file:line) | Scenario D (cold) | Scenario F (crash) | Wall-clock effect | Quality risk |
|---|---|---|---|---|---|---|---|
| M1 | Batch is LOCAL-FILE-ONLY; download handoff pipeline exists (red-team premise corrected) | MECHANISM | `ChapterTranslator.kt:639-661,669-690`; `MangaScreenModel.kt:1109-1177` | defines D | defines F | baseline | none |
| M2 | FGS already runs the batch; reader pause PRESERVES batch (T928-ui claim corrected) | MECHANISM | `TranslationManager.kt:725-732`; `ReaderTeardownCoordinator.kt:67-79` | n/a | core | baseline | none |
| S1 | De-facto download/translate pipelining already covers D; residual risk is download STOP, not missing integration | SOLUTION (exists, needs hardening) | `Downloader.kt:244-284,535`; `MangaScreenModel.kt:1981-1997` | 15–40 min [P] prefix, overlapped | n/a | zero (already pipelined) | none |
| S2 | Download-failure re-arm: auto-retry w/ backoff; today downloader stop STARVES translation silently | SOLUTION | `Downloader.kt:179-204`; `TranslationRequestCoordinator.kt:252-256`; `ChapterTranslator.kt:373-392` | prevents D stall | prevents F-loop | run-completability | none |
| S3 | K-ahead download governor (radio sleep) aligned to translation ETA instead of drain-everything | NOVEL | `MangaScreenModel.kt:1171-1177` (enqueue-all today) | battery/bytes −60–90 % [P] | n/a | ~0 if K≥2 (download ≫ faster than translate) | none |
| S4 | Raise per-chapter page concurrency for batch downloads (2→k) under per-source client limits | SOLUTION | `Downloader.kt:530-535` (`flatMapMerge(2)`) | prefix ÷~k [P] | n/a | minutes, once | source-ban risk |
| S5 | Reader-position-first enqueue order for download+translation (A/B steering on undownloaded library) | SOLUTION | `MangaScreenModel.kt:1146-1177`; queue FIFO `ChapterTranslator.kt:431-445` | jump-B TTFP from ~ch-order to user order | n/a | scenario-B only | none |
| S6 | Partial wake lock while any chapter TRANSLATING (WAKE_LOCK declared, zero usages today) | SOLUTION | `AndroidManifest.xml:17`; grep WakeLock = 0 hits; FGS 1 s poll `TranslationForegroundService.kt:159` | enables screen-off run | fewer F entries | 12.1 h run becomes completable screen-off | battery |
| S7 | WorkManager-backed resume contract (opt-in auto-rearm of restored queue) — Director decision vs never-auto-start invariant | SOLUTION (policy) | `ChapterTranslator.kt:184-233` (restore→PAUSED); pattern `DownloadJob.kt:35-85` | n/a | F cost → ~20 s, no user tap | run-completability | paid-work auto-start |
| N1 | Checkpoint-on-suspend hook: flush + honest paused notification when FGS/Doze tears down; ≤1 page re-OCR per event | NOVEL | per-page TX `ChapterProfileBatchCoordinator.kt:414-483`; resume `:2019-2069` | n/a | each Doze stop costs ~20 s + ≤1 page, not hours | resilience | none |
| N2 | Unified FGS truth: monitor download phases (WAITING_FOR_DOWNLOAD) not just translator queue | NOVEL | `TranslationForegroundService.kt:123-158`; `BatchHeroProjection.kt:148-161` | visible "downloading 37/200" | fewer "is it dead?" abandons | 0 (visibility budget) | none |
| N3 | Charging + battery gates: batch proceeds freely only while charging / battery-ok; overnight-job framing | NOVEL | `PermissionStep.kt:73,113`; `SettingsAdvancedScreen.kt:148-152` | makes Doze moot while charging [P-OS] | fewer F entries | converts 12.1 h to viable | none |
| N4 | FGS dataSync 6-h cap at targetSdk 35 — session-rotation plan BEFORE the SDK bump | NOVEL (forward constraint) | `AndroidConfig.kt:7-9` (target 34) [P-OS] | n/a | 12.1 h LAN run > cap | future run-completability | none |

## Detail

### M1 — the batch consumes local files only, and a full download→translate handoff exists
`translateChapterInternal` resolves `downloadProvider.findChapterDir(...)` (`ChapterTranslator.kt:639-644`);
null ⇒ chapter FAILS with "Chapter files not found — the download may have been deleted" (`:648-661`). Streams
come only from the local dir: shared `ArchiveReader` mmap for CBZ (`:671-686`) or direct file opens (`:687-690`),
consumed via `streamsByKey` (`BatchLaneWorkers.kt:318-324`). There is NO network streaming path in the batch.
Undownloaded requests route through a fenced pipeline: acknowledge group (`MangaScreenModel.kt:1118`) → partition
downloaded vs awaiting (`:1150-1160`) → `WAITING_FOR_DOWNLOAD` + generation attach (`TranslationRequestCoordinator.kt:73-84`)
→ `enqueueTranslationDownloads` (`MangaScreenModel.kt:1981-1997` → `DownloadManager.downloadChapters`+`startDownloads`)
→ completion `handOffAfterFinalization` (`Downloader.kt:772-860`) → `startTranslationAfterDownloadIfRequested`
(`TranslationRequestCoordinator.kt:499-532`, atomic generation fence + PREPARING) → `translateChapter(gen, autoStart)`
(`TranslationManager.kt:779-838`). Rekey maps online→onDisk page keys (`Downloader.kt:777-800`). **This contradicts
red-team N3 "no DownloadManager integration exists in the batch path (grep)".**

### M2 — durability baseline (red-team N4 partially corrected)
`TranslationManager.startTranslation` starts `TranslationForegroundService` whenever any batch is active
(`TranslationManager.kt:725-732`); the service is `dataSync` type (`AndroidManifest.xml:202-205`) with progress
notification + Stop/Retry actions, self-stopping when no QUEUE/TRANSLATING remains
(`TranslationForegroundService.kt:113-161`; `BatchTranslationForegroundPolicy.kt:7-9`). Batch work runs on the
process-lifetime `SupervisorJob()+IO` scope (`ChapterTranslator.kt:270`) — **Activity death cannot cancel it**.
`ReaderActivity.onPause` cancels manual/auto page jobs only; `translatorStop` fires ONLY `if (!isAnyBatchTranslationActive)`
(`ReaderActivity.kt:266-275` → `ReaderViewModel.kt:2510-2517` → `ReaderTeardownCoordinator.kt:67-79`); T928-ui's
"onPause cancels ALL translation" is true for MANUAL/AUTO, false for BATCH. Zero `WakeLock` usages exist in
`app/src/main/java` (grep); `WAKE_LOCK`/`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are declared
(`AndroidManifest.xml:17-18`); battery-exemption is a user-gated onboarding/settings prompt
(`PermissionStep.kt:73,113`; `SettingsAdvancedScreen.kt:148-152`). Process death ⇒ `restoreQueue` rehydrates
entries PAUSED, `isPaused=true`, never auto-starts (`ChapterTranslator.kt:184-233`); attempt-ledger startup
reconciliation can further pause after repeated interrupts (`TranslationManager.kt:600-639`).

### 200-chapter wall-clock under N3/N4
- **D (fresh install, nothing downloaded):** downloads drain one chapter at a time per source
  (`Downloader.kt:244-284`, `take(5)` across DISTINCT sources — single-manga batch = strict serial), 2 pages
  concurrent per chapter (`:530-535`). 200×15 pages ≈ 27–85 min [P] of network prefix that OVERLAPS translation
  (download ~8–25 s/chapter ≪ 64–219 s/chapter translation), so per-chapter readiness stays ahead of the
  translation frontier. D adds a prefix, it is NOT "network-bound and unbounded" as red-team stated — unless S2's
  stall mode hits. Ch.1 TTFP gains its download time (~10–25 s [P]).
- **F amplified:** every Doze/LMK stop re-enters the ~20 s resume path (model §4.4) PLUS a manual re-arm
  (S7 gap). S6/N1/N3 are what make the 3.6–12.1 h headline completable on stock Android **[P-OS]**: a dataSync
  FGS keeps the process out of cached-freeze, but with no wake lock and no battery exemption a screen-off,
  unattended, uncharging device may defer CPU/network between Doze windows — 12.1 h LAN is realistic only
  charging, screen-on, or exemption-granted.
- **Quality:** none of M1–N4 touches OCR/provider/display truth; S7 alone carries the paid-work-auto-start
  policy risk (T929 no-go class is untouched: no parallelism, no quota change).

### Interactions
- Scheduling: S5 changes admission ORDER only (FIFO per source stays); checkpoints make any reorder free
  (verify-sched S7). S2's re-arm rides existing `requeueExisting` cooldown (`ChapterTranslator.kt:373-392`).
- Durability/io: N1 reuses per-page TX + `resumeFinalizeOrComplete` — no new write shapes; do NOT coalesce
  checkpoint cadence (io catalog owns that trade).
- Provider: S3/S4 touch only the Mihon image downloader, never the LLM governor.
- Reader: S6 wake lock must be released on `clearQueue`/terminal (FGS monitor already polls queueState at 1 s —
  `TranslationForegroundService.kt:159` — and is the natural lock owner).
- Provider/quality catalogs: none needed.

### Recommended portfolio (Director-facing)
1. S2 + N2 (visibility/failure hardening of the EXISTING pipeline) — low risk, closes D's real hole.
2. S6 + N3 (wake lock while TRANSLATING, charging gate) — makes 3.6–12.1 h completable unattended.
3. S7 as an explicit Director decision: keep never-auto-start (current invariant) vs opt-in auto-rearm;
   N1 is the no-policy-change fallback that bounds each interruption's cost.
4. N4: adopt before any targetSdk 35 bump — otherwise the 12.1 h LAN run exceeds the dataSync FGS cap **[P-OS]**.
