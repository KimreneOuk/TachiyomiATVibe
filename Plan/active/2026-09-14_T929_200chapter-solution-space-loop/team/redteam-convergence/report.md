# T929 Round 5 — red-team convergence pass (Round-4 catalogs + premise verification)

Base `main` @ `9c19ad0` (confirmed HEAD; only untracked `Plan/` dirs). Read-only.
Scope: invariant-check the 25 Round-4 entries (solutions-native, solutions-background),
verify both premise corrections and the biggest claims in code, rule on convergence.
Paths: `translation/` = `app/src/main/java/eu/kanade/translation/`, `tachiyomi/` = `app/src/main/java/eu/kanade/tachiyomi/`.

## 1. Verification of premise corrections and major claims — ALL CONFIRMED

- **(a) Fenced download→translation handoff EXISTS (red-team N3 premise corrected).**
  `ChapterTranslator.kt:639-644` resolves `downloadProvider.findChapterDir`; null ⇒ typed
  `failBeforePipeline("Chapter files not found…")` (:648-661); local-only streams via shared
  mmap `ArchiveReader` (:669-690). Fenced pipeline: `MangaScreenModel.kt:1148-1177` (partition
  downloaded/awaiting + fenced WAITING writes) → `MangaScreenModel.kt:1981-1997`
  (`enqueueTranslationDownloads` → `downloadChapters`+`startDownloads`) → `Downloader.kt:759,772,818`
  (`handOffAfterFinalization` → `startTranslationAfterDownloadIfRequested`) →
  `TranslationRequestCoordinator.kt:73-84` (generation attach) and `:499-532` (atomic
  generation fence + PREPARING under one lock; stale callbacks dropped).
- **(b) dataSync FGS EXISTS in the translation path (red-team N4 premise partially corrected).**
  `app/src/main/AndroidManifest.xml:202-205`: `TranslationForegroundService`
  `android:foregroundServiceType="dataSync"`. Started whenever any batch is active
  (`TranslationManager.kt:725-732`); 1 s queue poll, self-stops with no QUEUE/TRANSLATING
  (`TranslationForegroundService.kt:113-161`, `PROGRESS_UPDATE_INTERVAL_MS = 1_000L` :243).
- **(c) Zero wake locks CONFIRMED.** grep `WAKE_LOCK|wakeLock` over `app/src/main`: exactly one
  hit — the manifest declaration (`AndroidManifest.xml:17`, alongside
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` :18). Zero Kotlin usages. S6's premise holds.
- **(d) Never-auto-start resume CONFIRMED (three layers).** `ChapterTranslator.kt:184-233`
  `restoreQueue` rehydrates every durable entry to PAUSED, sets `isPaused = true` — comment:
  "never auto-start background OCR/LLM work on launch". Second layer: T924 hotfix — startup
  reconciler admits download-completed requests with `autoStart=false` ⇒ enqueues PAUSED
  (`TranslationRequestCoordinator.kt:499-504`). Third layer: attempt-ledger startup
  reconciliation pauses chapters after repeated interrupts (`TranslationManager.kt:600-639`).
  No WorkManager/AlarmManager re-arm of translation exists anywhere (grep). Restart after
  process death is user-action-only (in-session `requeueExisting` cooldown `:373-392`).
- **(e) targetSdk = 34.** `buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt:8`
  (`TARGET_SDK = 34`, applied via `mihon.android.application.gradle.kts:14`). The 6-hour
  dataSync FGS cap is Android-15/targetSdk-35 behavior ⇒ N4 is correctly a FORWARD constraint,
  not a current bug.
- **(f) MangaOcr KV write-slot + per-run engine profile legality.** Host writes returned KV
  slices at `pos` (`MangaOcrEngine.kt:281-282` call; `writeCacheAtPos` dstOffset =
  base + `pos*64` :409) — host-side half of the off-by-one confirmed in code; the
  "graph convention pos-1" half rests on T927 desktop lab (asset-level, not in repo); entry
  correctly gates on device confirmation. EngineLane: `ensureEnginesBuiltFor`
  (`pipeline/EngineLane.kt:405-444`) re-reads `OcrModelCatalog.selectedModel` at EVERY
  translation entry point and rebuilds `recognitionEngine` on any change (model, language,
  inpainting, reading order, closed). Stable per-run selection ⇒ zero rebuilds; per-page
  switching ⇒ rebuild per page (init thrash) — the entry's per-run-only restriction IS the
  legality condition. **But see conflict C1:** `selectedModel(..., persistCorrection = true)`
  writes the user preference durably (`OcrModelCatalog.kt:75-87`).
- Supporting claims spot-checked and held: MangaOcr 3 CPU sessions + `executionProviderLabel
  = "cpu"` policy comment (:31-36,60-85); decoder threads `min(cores/2, 2)` (:38); 2×1 MiB KV
  pools maxPoolSize=2 (:39-40); `recognizeBatch = map { recognize(it) }` (:155-158); 300-step
  loop with 128-position cap (:256-261, :433-441); catalog = 3 fixed entries, JA→MangaOcr
  default, no variant slots (`OcrModelCatalog.kt:21-58`); segmenter CPU-forced with recorded
  cause (`OnnxBubbleSegmenter.kt:132-144`, CPU rebuild :300-312); detectors accelerator-first
  with CPU fallback (`OnnxPageTextDetector.kt:45-49`, `OnnxPanelDetector.kt:62-65`); EP ladder
  (`HardwareDiscoveryEngine.kt:98-142`); UNSUPPORTED latch + SUPPORTED-requires-executed-inference
  (`ModelRoutingEngine.kt:86-93,111-117`); webtoon windows (aspect≥2, width×1.4, 250 px
  overlap; 800×12k ⇒ ~14 windows; `WebtoonSlidingDetector.kt:22-24,41-69,152-190`); ML Kit
  kept inline on native lane (`BatchLaneWorkers.kt` comment "ML Kit (LOCAL_COMPUTE) is kept
  inline on the native lane", ~:306-313); SKIP_ALL resume gate (~:383-415); reader onPause
  preserves batch (`ReaderActivity.kt:266` → `ReaderViewModel.kt:2505-2517` →
  `ReaderTeardownCoordinator.kt:67-79`: `translatorStop` only `if (!isAnyBatchTranslationActive)`);
  battery-exemption prompt exists (`PermissionStep.kt:72-73,108-113`);
  `StageFingerprints.detection` is OCR-engine-independent, `ocr` carries `ocrModelHash`
  (`StageFingerprints.kt:29-66`). Minor line drift only: flatMapMerge(2) is `Downloader.kt:551`
  (catalog cited :530-535); battery prompt path is
  `presentation/more/onboarding/PermissionStep.kt`.

## 2. Per-entry verdicts — solutions-native (12)

| ID | Verdict | Reason (code-cited) |
|---|---|---|
| N1-1 | NEEDS-REWORK | Mechanism as detailed = "preference override of `OcrModelCatalog.selectedModel`" — durable, process-global (`persistCorrection=true` :75-87). A batch writing it leaks into concurrent MANUAL/AUTO page translation (EngineLane re-reads the pref at every entry point :405-444) and survives process death (resume + user's setting silently changed). Condition: run-scoped NON-durable selection (engine construction already takes `ocrModel` as a param, `RoiPageRecognitionEngine.kt:187-215`) or an explicit user-facing batch profile. Quality trade itself is honestly priced. |
| N1-2 | SAFE (binding condition) | Serial rescue on the same lane (single-lane intact); +~4 MiB KV bounded; recall-limited gate labeled. Condition: fingerprints MUST composite the effective engine per crop (`StageFingerprints.ocr` carries `ocrModelHash` :51-66 — record the per-crop truth) or re-runs adopt un-rescued fast-engine text. |
| N1-3 | SAFE | Batching reduces lane acquisitions; one run() at a time inside nativeGuard preserved; KV 4B MiB bounded at construction, drained (:321-325); desktop-only caveat labeled. Gate behind T927 device measurement as cataloged. |
| N1-4 | SAFE | Mixed-EP page is the existing production shape (nativeGuard serial; per-engine providers logged `RoiPageRecognitionEngine.kt:630-635`); UNSUPPORTED latch self-heals (`ModelRoutingEngine.kt:86-93`). |
| N1-5 | SAFE | Host write-at-`pos` verified (:281-282, :409); pure correctness; device-confirmation gate stated. |
| N1-6 | SAFE (rider) | Tuning-only, batch-profile-restricted; rider: thermal + reader contention risks are real (see §4) — must not degrade tap latency, per binding priority order. |
| N1-7 | SAFE (gated) | Quality unbounded until benchmarked (labeled); `ocrModelHash` change invalidates OCR corpora in the SAFE direction (re-OCR, never stale adoption — verified fingerprint inputs :51-66). |
| N1-8 | NEEDS-REWORK | Same defect as N1-1 (its implementation inherits N1-1's override): per-run profile must be run-scoped/non-durable, else manual lane quality changes and the override outlives crashes. Per-run-only restriction (EngineLane rebuild triggers :405-444) is verified correct. |
| N2-1 | SAFE | CPU-force is a recorded build-artifact decision (:132-144); failing attempt = one session creation + latch. No invariant touched. |
| N2-2 | SAFE (rider) | Webtoon-only (standard = 1 window, verified); segmentationMask feeds render/inpaint only (:456-458). Rider: deferred segmentation must keep render gated on the page's mask — no committed display without its segmentation; seam recall labeled. |
| N1-9 | SAFE | `StageFingerprints.detection` takes detector/segmenter hashes only — engine-independent as claimed (:29-48); additive artifact on the existing merge pattern. |
| N1-10 | SAFE | Draft-lane only, truncation labeled; EOS loop already 128-capped (:256-261). Restriction to draft lane is binding. |

## 3. Per-entry verdicts — solutions-background (13)

| ID | Verdict | Reason (code-cited) |
|---|---|---|
| M1 | SAFE | Verified verbatim (see §1a). Mechanism entry, no behavior change. |
| M2 | SAFE | Verified verbatim (see §1b + reader-pause chain). Corrects T928-ui. |
| S1 | SAFE | Factual + hardening framing; the residual hole (download STOP) is S2's entry. |
| S2 | SAFE | Rides verified `requeueExisting` cooldown (`ChapterTranslator.kt:373-392`); in-session retry, never a launch auto-start; handoff fence gates admission. |
| S3 | SAFE | Image-downloader-only; K-ahead files are disjoint from the chapter being read; fenced per-chapter handoff (`Downloader.kt:818`) means a chapter translates only when ITS files exist. |
| S4 | SAFE (condition) | flatMapMerge(2)→k touches Mihon downloader, not the governed LLM quota (no-go class untouched). Condition: k must stay under per-source client/rate limits or the batch starves on a source ban (risk labeled). |
| S5 | SAFE | Reorder-only; live TRANSLATING never preempted (active-preference verified `ChapterTranslator.kt:416-425`); checkpoints make any order resumable. |
| S6 | SAFE (rider) | WAKE_LOCK declared, zero usages (§1c); FGS 1 s monitor is a coherent owner (:159,:243). Rider: acquire only while TRANSLATING exists, release on the publishPaused/stopSelf paths — never hold across PAUSED-only states. |
| S7 | SAFE (decision item) | Opt-in auto-rearm vs never-auto-start is explicitly a Director decision (same treatment as provider P2/P4 decision items); current invariant verified (§1d). |
| N1(bg) | SAFE (precision note) | Reuses per-page TX + `resumeFinalizeOrComplete` (both verified) — no new write shapes. Precision: a Doze/LMK PROCESS KILL runs no hook; its ≤1-page/~20 s bound comes from the existing per-page TX, the hook only improves graceful stops. |
| N2(bg) | SAFE | Visibility-only; rides the existing FGS monitor loop. |
| N3(bg) | SAFE | Opt-in charging/battery gating uses the existing PAUSED state (crash-safe by construction); policy choice, no invariant touched. |
| N4(bg) | SAFE | Forward constraint verified: target 34 (§1e); plan-before-bump is the right shape. |

**Round-4 tally: 23 SAFE / 2 NEEDS-REWORK / 0 REJECT.**

## 4. Cross-catalog interactions (new entries vs previously-SAFE)

- **C1 — N1-1/N1-8 per-run engine profiles × EngineLane global re-read × manual/auto lane (the one real conflict).** `ensureEnginesBuiltFor` re-reads the persisted preference for EVERY entry point including manual/auto page translation, and the detailed N1-1 mechanism writes that preference durably. Consequences: manual taps silently change engine mid-run; the override survives process death (resume runs the fast engine; user setting changed). Also collides with P6/S14 ("analyzer/provider pinned per series/run-block") — two writers to engine-config identity. **Reconciliation:** engine-selection gets ONE owner: run-scoped, non-durable selection injected at engine construction (`RoiPageRecognitionEngine.kt:187-215` already parameterizes `ocrModel`), or a first-class user-facing batch profile. Until then N1-1/N1-8 are NEEDS-REWORK (recorded in §2).
- **C2 — N1-3 decoder batching × single-native-lane occupancy (sched S11 waves, model).** Batching keeps one run() at a time inside nativeGuard and REDUCES lane acquisitions — the invariant is strengthened, not stressed. KV growth is bounded at construction (16-32 MiB) and independent of the X2/N3-redteam bitmap budget. Only follow-up: S11 wave arithmetic must be recomputed under the new t_ocr term (catalog §4 already says so). No conflict.
- **C3 — S3/S4 download-ahead waves × ArchiveReader/store-open serialization.** The shared ArchiveReader is created per chapter AFTER the fenced handoff (`ChapterTranslator.kt:669-690`); ahead-downloads write disjoint chapter dirs; page stores are leased per page (`tryAcquirePageStageLease`). No read/write overlap; disk cost bounded by K. No conflict.
- **C4 — S6 wake lock × onPause cancellation (M2) × FGS self-stop.** Verified: reader pause preserves the batch (`ReaderTeardownCoordinator.kt:67-79`), so lock ownership by the FGS monitor is coherent — the batch outlives the reader and the monitor polls at 1 s. Rider from §3/S6: release on publishPaused/stopSelf. No structural conflict.
- **C5 — N1(bg) checkpoint-on-suspend × io S15 / X7 flush-on-terminal.** Same reconciliation as Round-3 X7: flush on STOP/terminal transitions; N1(bg) already forbids coalescing checkpoint cadence. Consistent; no conflict.

## 5. Convergence ruling: CONVERGED

No genuinely new solution CLASSES remain. The candidates posed were already mapped or are
not solution classes:

- **UI rendering cost of translated pages** — render is outside the native permit
  (`TranslationPipeline.kt:122-130`), sub-second; judged S2-class in redteam-arithmetic §3. Covered.
- **Database/Coil caching** — persistence cadence is owned by the io catalog (S1/S8/S9/S13);
  reader memory by B2's lazy queries; Coil's reader cache is not on the translation critical
  path. Covered.
- **Source-site rate limiting** — owned by Round-4 S2/S3/S4 (re-arm, governor, concurrency).
  Covered.
- **Multi-manga parallelism** — owned by sched S11 + provider P5 against the verified
  `take(1)` claim (`ChapterTranslator.kt:416-425`). Covered.
- **Thermal/energy throttling** — REAL wall-clock factor for sustained 3.6-12.1 h native work
  and nobody modeled it numerically; but it is NOT mappable from code: no thermal/power-API
  usage exists in the repo, and derating is device-specific sustained-load physics. Ruling:
  a DEVICE-MEASUREMENT GAP, not a solution class — fold a measured sustained-load derating
  factor (plausibly ±20-30 %) into the model before any Director ETA is committed; the
  scheduling-side responses already exist (N3 charging/overnight gating, N1-6 caution, S6
  screen-off viability). It caps every wall-clock number in every catalog equally; it spawns
  no new lever.
- Residual sub-item (not a class): first-run OCR asset DEPLOY (scenario D) is a one-time
  minutes-scale step owned by sched S3 warm-up + N1-7 assets; no new entry needed.

Per the loop protocol (converge when a round adds no genuinely new solutions): Round 4 added
real entries and corrected two stale premises, but this pass finds only mechanism-level
rework (C1) and a measurement gap — no Round-5-worthy lever class. **The loop converges with
this report**, subject to: (i) N1-1/N1-8 rework per C1 before any engine-selection adoption;
(ii) N1-2's fingerprint-compositing condition; (iii) P2's 8k-vs-16k Director decision from
Round 3 still stands unresolved; (iv) thermal derating factor measured on device before
portfolio ETAs are quoted.

## 6. Final loop tally (6 catalogs, all rounds)

| Catalog | Entries | SAFE | NEEDS-REWORK | REJECT |
|---|---|---|---|---|
| solutions-sched (R2) | 17 | 8 | 7 | 2 |
| solutions-io (R2) | 16 | 11 | 5 | 0 |
| solutions-provider (R2) | 16 | 10 | 6 | 0 |
| solutions-reader (R2) | 17 | 14 | 3 | 0 |
| solutions-native (R4) | 12 | 10 | 2 | 0 |
| solutions-background (R4) | 13 | 13 | 0 | 0 |
| **Total** | **91** | **66** | **23** | **2** |

Note: re-tabulating the Round-3 invariants report entry-by-entry gives 43 SAFE / 21
NEEDS-REWORK / 2 REJECT for the 66 R2 entries — its own bottom-line tally ("42/22")
undercounts SAFE by one (provider is 10 SAFE/6 NR, reader 14/3, per its verdict table).
This pass adds 25 judged entries (23 SAFE / 2 NEEDS-REWORK / 0 REJECT) and confirms both
premise corrections in code.
