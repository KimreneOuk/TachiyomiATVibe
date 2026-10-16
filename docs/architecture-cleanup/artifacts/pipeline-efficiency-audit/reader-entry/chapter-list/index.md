---
kind: review
title: "Reader entry — chapter list loading hot path (Agent D)"
comments: none
---

Agent D · branch `inpainting-modes-redesign` @ 4de121bf · READ-ONLY. VERIFIED = confirmed by direct source read at cited lines. SUSPECTED = plausible from code shape, needs a targeted check before implementation. No timing numbers: none were measured; wins are stated as cost-model changes only.

# Path under investigation

Manga screen entry → `MangaScreenModel` init combine → `toChapterListItems` → per-downloaded-chapter `getChapterTranslationStatus` → `TranslationProgressProjection` → `DurableChapterStatusResolver` → `ChapterTranslationStore` open/probe over SAF/UniFile.

# F1 — VERIFIED (HIGH): the whole sequential status pass runs inside the `MutableStateFlow.update` CAS loop

- `MangaScreenModel.kt:483-495`: `collectLatest { updateSuccessState { it.copy(chapters = chapters.toChapterListItems(manga)) } }`; `updateSuccessState` (`:173-180`, `private inline fun`) wraps `mutableState.update {}`; `toChapterListItems` is `internal suspend fun` (`:702-769`) doing the sequential per-chapter map with `getChapterTranslationStatus` for EVERY downloaded chapter (`:723-733`). Because both `updateSuccessState` and `MutableStateFlow.update` are `inline`, the suspend mapping genuinely executes inside the CAS-retry loop.
- Consequence (kotlinx `update` semantics): while the O(chapters) I/O pass runs, the state publish is pending; ANY other `update` landing mid-pass (download progress `:683-695`, translation state `:661-672`, requests `:674-681` — all fed by frequently-emitting flows) fails the `compareAndSet` and re-runs the ENTIRE pass. All other updaters also iterate the same loop while the pass is in flight.
- Triggers: cold — screen entry first emission (`:473-496`) plus the pre-observation pass at `:525-528` (that one is OUTSIDE update — good, but it may OVERLAP the combine's first in-update pass; whether that wastes work is unmeasured without traces); warm — every emission of the 5-flow combine `:474-481`, i.e. any global download-cache/queue/translation-queue/request event during the visit. `collectLatest` additionally cancels an in-flight pass when a newer emission arrives, then starts a fresh full pass.
- Expected win: compute the item list first (suspend, outside any update), then publish with one `update`. Removes CAS re-run amplification and updater contention. Complexity **Low**.

# F2 — VERIFIED (HIGH): per-chapter open→close churn collapses the positive status cache on repeated sweeps

- `DurableChapterStatusResolver.kt:104-125`: on `durableStatusCache` miss → memoized `findTranslationDocument` → `probeArtifactManifest` → `withProbeStore { store.artifactStatus() }`.
- `withProbeStore` (`:187-233`): `getOrCreateProbe` → when `result.created` → **`durableStatusCache.clear()`** (`:210-212`); in `finally`, `releaseProbe` (`:224`). A list probe is released and closed (`:227 closeAndFlush()`) only when NOT adopted — `ActiveChapterStoreRegistry.releaseProbe` (`:166-173`) keeps the store if a watcher/reader concurrently opened it as an active or file store. Concurrent watcher opens (F5) therefore CAN adopt a probe, making that query created=false/held (see `DurableStatusWipeGateTest.kt:223-233` for the held shape).
- For the common non-adopted case, the next query for the same chapter misses `probeStores` (`ActiveChapterStoreRegistry.getOrCreateProbe :116-138`) → fresh open → `created=true` → another whole-cache wipe. So repeated same-order sweeps of ≥2 non-active translated chapters collapse the cache: each pass of T such chapters = T full artifact opens + T `closeAndFlush`, ending with ONE surviving `durableStatusCache` entry (the last chapter). Not every individual query wipes — an adopted/held probe pass does not — but the sequential-list sweep shape hits the created path each pass.
- Only these legs are cheap on repeat: the last-resolved chapter (single cache survivor), the active-store leg (`TranslationProgressProjection.kt:287-295`), and the queue leg (`:285-286`). Document-location memoization survives, so SAF manga-dir walks amortize; the per-query `probeArtifactManifest` (full manifest JSON decode, `ChapterArtifactManifestReader.kt:18-35`) and the open do not.
- Triggers: same as F1 — worst on entry with many downloaded translated chapters.
- Expected win: (a) stop releasing status probes per query — e.g. a bounded probe-pool architecture with explicit lifecycle safeguards (adoption, eviction, flush-on-close) is ONE alternative and must be designed against the existing open/adopt/release protocol, not assumed the cheapest fix — or (b) cache derived statuses keyed by a per-document generation. Complexity **Medium**. Correctness fence: F6 must be preserved.

# F3 — VERIFIED (HIGH): one status query can read + hash-verify every page snapshot, even with a trusted journal

- `ChapterTranslationStore.openArtifactOnly` (`:4857-4913`): `manifest.pages.mapValues` reads the candidate AND committed snapshot per page (`readVerifiedSnapshot` `:4895-4899` → `artifact.readPageSnapshot` + `StageFingerprints.pageSnapshot` hash) — before and independent of the `authoritativeJournal` check (`:4914-4915`), which only shapes fallback keys (`:4919-4925`). Journal replay (`:4884-4893`) reads app-private epochs (internal storage; cheaper than SAF).
- Under F2 churn, this full verification runs once per chapter per rebuild pass.
- Expected win (SUSPECTED — needs design check): a status-only open mode deriving `artifactStatus()` from the manifest header + run record. `StoreStatusProjector.artifactStatus()` (`StoreStatusProjector.kt:91-139`) mostly consumes manifest fields (`activeRun`, `pages`, `durableFailures`, `expectedPageCount*`); the page-state-dependent part is `hasReadableOutput` (`:102-104`). Either add a terminal-status stamp to the manifest written at completion, or verify manifest committed-records alone satisfy the projector's artifact-first evidence rules (`:79-90` comment). Full opens remain for snapshot-detail consumers (progress sheet). Complexity **Medium-High**; correctness-critical — status/recovery fences must hold.

# F4 — VERIFIED (MEDIUM): rebuild fan-out from global flows

- Combine inputs (`:474-481`): DB chapters + GLOBAL `downloadCache.changes` + GLOBAL `downloadManager.queueState` + `translationManager.queueState` + `pendingTranslationRequests`. Any emission (even for other manga) re-runs the full pass.
- `observeTranslationRequests` (`:448-457`) → `updateTranslationRequests` (`:674-681`) maps all items per request emission (equality-guarded write at `:679` — verified); `observeTranslations` (`:440-444`) updates per `Translation`.
- Expected win: scope emissions to this manga / diff-update single items instead of full remap. Complexity **Medium**.

# F5 — VERIFIED (MEDIUM): per-chapter watcher + active-store opens from the list path

- `toChapterListItems:750-757` starts `observeTranslationProgress(id, durableStateHint)` for queued/requested/durableTerminal chapters; it opens an ACTIVE store per such chapter (`:578-586`), job-guarded (`:572`) once per screen lifetime. Manga with many durable ERROR/READY_WITH_WARNINGS chapters pay opens on entry.
- Terminal registry misses go through `reconstructDurableTerminalSnapshot` (TranslationProgressProjection.kt:423-434 → TranslationManager.kt:1177-1209) — another read-through store open per terminal chapter, uncached by design (`:1165-1172`).
- Expected win: defer progress-detail opens until the indicator/sheet is actually shown; list badge needs only the cheap status. Complexity **Medium**.

# F6 — VERIFIED correctness fence (must preserve): created-probe wipe is recovery invalidation, not waste

- Rationale: `DurableChapterStatusResolver.kt:66-75` (invalidation protocol), `:205-209` (why created wipes; also documents the OLD bug where wiping per probe-pass collapsed the cache — the current single-entrant wipe still collapses it across a sequential pass), `ActiveChapterStoreRegistry.kt:210-217`.
- Test coverage GAP (verified): `DurableStatusWipeGateTest.kt:207-236` pins created-vs-held for ONE chapter; no test covers multi-chapter sequential survival (that N-1 statuses survive chapter N's probe creation) nor recovery-still-invalidates across a pass.
- Required shape: targeted identity/generation invalidation (e.g., generation-stamped cache keys per registryKey, or clear only entries belonging to the same document) — NOT removal of the fence.

# F7 — VERIFIED: negative results uncached on the common untranslated path

- `durableStatusCache` caches positives only (`:97-101`); document negatives not memoized by protocol (`:29-30`). An untranslated downloaded chapter therefore re-runs its document lookup on EVERY pass — the cost varies by case and is NOT a fixed SAF-op count: if the manga/source dirs are missing, `findMangaDir` early-returns (`TranslationFileProvider.kt:80-83`); if the document directory IS found (positive memo in `findTranslationDocument`, `DurableChapterStatusResolver.kt:140-149`), the lookup amortizes but the manifest probe still runs its primary + backup lookups per query (`ChapterArtifactManifestReader.kt:18-30`). Expected win: per-screen negative memo with the same invalidation protocol (including F6-created wipes). Complexity **Low-Medium**.

# Telemetry and device experiment

- Existing: `ReaderEntryTrace` (diagnostics/ReaderEntryTrace.kt) already stages the exact hot spots — `translation.findDocument` (resolver `:139`), `probe.status` (`:218`), `probe.closeAndFlush` (`:225`) — logcat `[reader_entry] begin/end` with elapsedMs, 15s watchdog, main-heartbeat. GAP: no stage spans a full `persistedChapterStatus` or an entire `toChapterListItems` pass, and no counter logs opens/closes per pass — add pass-level stages + open/close counters before/after any fix so wins are measured, not assumed. A device experiment can run on existing logs today (grep `[reader_entry]`) but cannot yet attribute per-pass totals.
- No existing benchmark/device harness covers the chapter-list path (OCR benchmark runners exist but are unrelated).

# Fix order (all VERIFIED unless noted)

1. F1 hoist out of `update` — Low, removes re-run amplification; no correctness fences touched. (Initial-await vs combine-pass overlap should be quantified with the new pass stages first; no duplicate-work count is asserted without traces.)
2. F2 stop per-query probe release/close — bounded probe-pool with lifecycle safeguards (adoption/eviction/flush) is one architecture to evaluate, not assumed cheapest — plus F6 targeted generation invalidation + extend `DurableStatusWipeGateTest` with sequential-survival and recovery cases — Medium, largest repeated-cost reduction.
3. F7 negative memo — Low-Medium.
4. F3 status-only open (SUSPECTED win, needs projector-evidence design check) — Medium-High.
5. F5 lazy progress opens — Medium.
6. F4 scoped rebuilds — Medium.

# Round-2 corrections (orchestrator source review)

1. Probe adoption is possible (watcher/reader concurrently opening the same document); "never adopted" corrected in F2 — the wipe-on-created churn claim now scopes to repeated non-adopted sweeps of ≥2 non-active translated chapters, not every query.
2. "Warm passes don't exist" corrected: up to one translated chapter stays warm-cache per sweep, and held/adopted probe passes do not wipe (per `DurableStatusWipeGateTest:223-233`).
3. Removed the fixed "3 SAF findFiles per untranslated chapter" claim (F7): cost varies with missing-dir early returns and positive document-memo hits; the negative-lookup concern is retained without a count.
4. Probe-pool demoted from assumed fix to evaluated alternative with lifecycle safeguards (F2 win wording, fix order 2).
5. Initial await mapping (`:525-528`) may overlap the combine mapping — flagged as unmeasured rather than counted as duplicated work (F1 triggers).
