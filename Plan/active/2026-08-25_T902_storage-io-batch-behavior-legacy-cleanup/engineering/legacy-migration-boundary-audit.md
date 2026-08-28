# T902 legacy-migration boundary audit

Date: 2026-08-25

## 1. Legacy entry points still present

Verified against the current working tree:

- `TranslationManager.findTranslationDocument` (TranslationManager.kt:620-630) discovers the flat `X.json` path. `resolveDurableChapterStatus` (482-507) uses artifact status for `ARTIFACTS` manifests and flat decode only for `LEGACY`/manifest-absent chapters. `getChapterTranslationForReader` (556-577) and `getChapterTranslation(UniFile)` (579-588) use the same authority split. `DownloadPageLoader` remains the reader caller.
- The flat decoders (`decodeLegacyChapterStatus`, 509-525, and `decodeLegacyChapterTranslation`, 656-668) are the rescue read path. Decode failure is quarantined to `<name>.corrupt` by `quarantineCorruptTranslationFile` (671-681); it is not deleted.
- `ChapterTranslationStore.open/openInternal` (1919-2001) reads legacy bytes, then `migrateArtifactManifest` (2024-2101) builds/opens the artifact store. `ChapterArtifactStore.loadOrMigrate` (67-155) performs initial migration, LEGACY resync, ARTIFACTS recovery, and retention reconciliation. `LegacyArtifactMigration.migrateChapter/resyncManifest` (LegacyArtifactMigration.kt:75-112) maps legacy pages and preserves additive metadata such as `expectedPageCount`. `materializeLegacyCommittedSnapshot` remains the one LEGACY upgrade helper (ChapterTranslationStore.kt:2072; ChapterArtifactStore.kt:266).
- The legacy flat writer branch remains in `persistLocked` (ChapterTranslationStore.kt:1778-1810), but it is bypassed once the manifest authority is ARTIFACTS. Legacy glossary compatibility remains in `legacyDocuments`/`persistGlossaryLocked` (1708-1726) only before artifact authority. `ChapterTranslationSummaryStore` remains as an unreferenced class; no production summary read/write caller remains.

## 2. Can new translations still write legacy format?

**Verified answer: normal manager-created translations do not write page data back to legacy JSON, but empty legacy-file creation and compatibility escape hatches remain.**

- `TranslationManager.openOrCreateActiveChapterTranslationStoreImpl` (TranslationManager.kt:829-857) passes an explicit artifact parent/name to `ChapterTranslationStore.lazy`. The Phase B artifact-only bootstrap in `ensureArtifactStoreLocked` (ChapterTranslationStore.kt:1361-1394) creates the manifest without materializing the flat file; the first mutation opens a candidate and cuts authority to ARTIFACTS. `persistLocked` then returns without flat writes.
- Existing-file opens in `TranslationManager`, `ChapterTranslator` (ChapterTranslator.kt:386-410), and the single-page fallback in `TranslationPipeline` (TranslationPipeline.kt:2863-2871) still create/open `X.json` before the first artifact mutation. The normal first mutation immediately opens an artifact candidate, so the file is an empty compatibility artifact rather than an ongoing legacy page-data stream. This is remaining physical noise, not a durable legacy page write.
- `ChapterTranslationStore.lazy { fileCreator }` without the explicit artifact parent/name intentionally retains its compatibility behavior. It is used by tests; the production manager uses the artifact-parent overload. This is the main remaining API escape hatch for new flat-file creation.

## 3. Migration lifecycle and failure behavior

1. On open, legacy bytes and the sibling legacy glossary are read and validated. Companion cleaned-image references are probed with bounded decode/dimension checks; malformed flat JSON is represented as an empty legacy snapshot and logged.
2. If no manifest exists, `LegacyArtifactMigration` produces a LEGACY-authority manifest and `ChapterArtifactStore` publishes it atomically. Legacy bytes remain the authority; a later `openCandidate` performs the ARTIFACTS cutover.
3. While authority is LEGACY, a changed legacy identity triggers a deterministic resync. Existing expected-page metadata, compatible glossary pointer, and durable failure records are retained. A valid committed legacy page may receive an immutable artifact snapshot through `materializeLegacyCommittedSnapshot`.
4. Once authority is ARTIFACTS, legacy bytes are never resynced over candidates/committed pointers. Reopen reconstructs live/committed pages from artifact snapshots and recovers interrupted RUNNING stages. Stale summary files are ignored.
5. Atomic manifest/snapshot/glossary publication failure retains the previous authoritative document; the in-memory store logs/retries where its dirty flag permits. A failed initial manifest publication leaves the legacy file untouched and allows a later open to retry migration. Flat decode failures in the manager quarantine/rename the original, preserve bytes under `.corrupt`, and return an empty/not-translated result.

## 4. Bloat estimate

- Source footprint is measurable but not large in APK terms: `LegacyArtifactMigration.kt` is 417 lines; the compatibility portions of `ChapterTranslationStore.kt` are roughly 250-350 lines (open/decode/identity/glossary/flat writer); the manager rescue path is roughly 150 lines. The whole store/manager files are much larger because artifact and scheduling behavior are colocated (2,052 and 1,550 lines respectively), so deleting migration code would not remove those files.
- Runtime cost is paid only for legacy or first-open chapters: one flat JSON read/decode, SHA-256 identity calculation, bounded cleaned-image probes, manifest read/resync, and occasional snapshot/glossary publication. ARTIFACTS reopen avoids flat resync and uses the manifest/page snapshots; durable status is cached by chapter.
- Storage cost for a rescued legacy chapter is one manifest plus artifact metadata/snapshots alongside the retained flat file and companion image directory. New artifact-only manager chapters avoid creating the flat file; fallback callers can still leave an empty `X.json`. Existing `X.summary.json` files are ignored and intentionally not swept until Phase C. The largest removable code/storage noise is the dead stage-transaction API (~300 lines plus tests), compatibility flat writer/fallback creation, and the unused summary class/files—not the rescue migration itself.

## 5. Phase C policy recommendation

To enforce “no ongoing legacy support; rescue chapters already produced by the legacy method”:

- **Retain until migration burn-in:** LEGACY-authority flat decode, `ChapterArtifactStore.loadOrMigrate`/`LegacyArtifactMigration`, identity-based LEGACY resync, cleaned-image validation, quarantine, and `materializeLegacyCommittedSnapshot`. These are the rescue path for chapters that already have flat output.
- **Then remove or make one-way/read-only:** the `persistLocked` flat write branch, legacy glossary overwrite path, and all production fallback creation of `X.json` in `ChapterTranslator`/`TranslationPipeline`. New work should require an artifact parent/name and never create a legacy document. Keep flat decode only behind an explicit legacy-rescue/migration boundary until the burn-in decision is made.
- **Delete in Phase C:** dead stage-transaction APIs and tests (`beginStage`/`commitStagePayload`/`promoteCandidate`/`markCandidateStageFailed`), `tempFileNameFor`, all summary callers and then `ChapterTranslationSummaryStore`, and stale summary-file cleanup can be handled as the planned physical sweep. Retain versioned atomic artifact glossary publication; remove only the pre-cutover legacy glossary writer after rescue burn-in.

The current Phase B implementation therefore has a clean durable authority boundary, but it does not yet satisfy the final “no empty flat files from every fallback caller” policy; that is a focused Phase C cleanup, not a reason to remove rescue migration now.
