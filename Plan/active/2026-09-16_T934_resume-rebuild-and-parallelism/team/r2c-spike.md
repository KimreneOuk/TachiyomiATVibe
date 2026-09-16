# T934 R2c — Consolidation spike report (2026-09-16, read-only investigator)

## What resume pays today

- Whole-chapter source re-hash: `BatchChapterTranslator.kt:398`
  (`LazySourceFingerprints(orderedStreams...)`) opens/reads every page source
  at run start → `orderedSourcePairs` (`ChapterProfileBatchCoordinator.kt:178,268`)
  → `orderedSourceDigest` (`:301`, recomputed at :800/:1162/:1503/:1844/:1898/:2040)
  → runId match `:306-309`.
- Per-page checkpoint adoption: `ChapterProfileBatchCoordinator.kt:408-457` — per
  page: sidecar read + JSON decode (`reusableCheckpointFingerprint` :3245-3256),
  then `adoptCheckpointSnapshot` (`:2693`) does a SECOND sidecar read +
  `store.mergeOcr` transaction (`:2713`).

## Option A — Consolidated per-chapter resume snapshot (cache/index over sidecars) — M

- Write seam: ride the group-commit tail — `ChapterTranslationStore.flushStagedMutationsLocked`
  (:282-320) next to `publishManifestInternal(current, syncToDisk=true)` (:316) +
  empty-stage publish (:286-291); `StorePersistenceScheduler.flushDirtyLocked`
  (store/StorePersistenceScheduler.kt:96-105) is the single chokepoint.
- Atomic publication idiom already proven: `ChapterDocumentIo` temp→validate→
  `.bak`-rotate→rename with `fd.sync()` (:314-361, :211-213), registered as a
  `SidecarPublication` in `ChapterArtifactStore.publishSidecarPointers` (:889-917).
- Manifest: one additive optional pointer `resumeSnapshot: SidecarPointer?`
  (schema 4 at `ChapterArtifactManifest.kt:85`; append-only-nullable is the
  established compat rule, `ChapterRunRecord.kt:60-71`).
- Invalidation: rewrite only under the same mutex-held flush that updates the
  manifest; on load validate per-page `pageVersion`/pointer fingerprints against
  the live manifest; mismatch → discard. Must also drop on `replaceAll`/
  `rekeyPages` (`ChapterTranslationStore.kt:1650/:1690`), `markDefunct` (:344),
  retention deletions (`StorePersistenceScheduler.kt:142-161`).
- Crash semantics: torn snapshot untrustable by construction (SidecarPointer
  sha256 fingerprint); corrupt → quarantine → fall back to the :408-457 sidecar
  walk (mirrors `readOcrCheckpoint` corrupt handling `ChapterArtifactStore.kt:940-957`).
- Size: index-only ≈ 200-400 B/page → 15-30 KB (70p) / 40-80 KB (200p); with
  hydration summaries 0.1-1 MB one read vs 200 SAF round-trips.
- Tests: existing crash families stay green (fallback is default); new:
  roundtrip, torn/stale→fallback.

## Option B — DB-backed chapter-state store — L

- No Room in the repo; the DB standard is SQLDelight 2.0.2
  (`gradle/libs.versions.toml:8`, `data/build.gradle.kts:37`, driver in
  `AppModule.kt:55-56`). B must extend SQLDelight in `data`, not add Room.
- Dual-write sites: `persistLiveCandidate` (:1172), `persistLiveCandidateAndFailure`
  (:1250), `publishSidecarPointers` (:889), run-record publish, attempt ledger,
  glossary. DB writes inside the store-mutex-held flush on a single-thread
  dispatcher, never calling back into the store. WAL commit maps to
  `CommitPoint.BATCH_CHUNK/EXPLICIT_FLUSH`.
- Test surface: artifact/ (19 files) + pipeline/batch/ (~33 files) must stay
  green unchanged — feasible but large review surface.

## Recommendation

Do A first; A does not block B. A is additive, lands beside R2a, and kills both
dominant resume costs (one-read restore + recorded digests end the re-hash).
B is the long-term primary switchover behind the SAME load interface A
introduces — extend SQLDelight, never Room. Sequence: A with/after R2a; B later.
