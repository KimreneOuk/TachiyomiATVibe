# Task T902 — Storage I/O Deslimming, Batch Behavior, Legacy Cleanup

## Director direction (2026-08-25)

- Manual/auto reader translation is considered working; do not regress it.
- Priorities: storage I/O and batch translation behavior.
- Many legacy remnants are not fully deleted and keep producing noise.
- Delegation: use persistent agent sessions (continuable), not single-turn subagents.

## Inputs

- T901 investigation (verified): `Plan/active/2026-08-25_T901_batch-translation-verification/engineering/`
  - `code-investigation-persistence.md` (defects D1-D15, F1-F7)
  - `code-investigation-scheduling.md` (D1-D10)
  - `code-investigation-ui-gating.md` (D1-D10)
  - `review-verification.md` (8 claims CONFIRMED)

## Verified inventory (2026-08-25, this working tree)

Legacy / dead code:
- Stage-transaction APIs `beginStage` / `commitStagePayload` / `promoteCandidate` /
  `markCandidateStageFailed` — ChapterArtifactStore.kt:637-942, production callers:
  NONE (only ChapterArtifactStoreTest.kt). ~300 lines dead.
- `tempFileNameFor` — ChapterTranslationStore.kt:1839, dead (unimplemented atomic
  flat-file write).
- Flat-JSON write path (`persistLocked` legacy branch, 1724-1756) — reachable only
  for LEGACY-authority chapters; post-cutover the flat file is frozen empty.
- `materializeLegacyCommittedSnapshot` (ChapterArtifactStore.kt:266) — one caller,
  LEGACY-manifest migration (ChapterTranslationStore.kt:1987-1991). KEEP (needed
  to upgrade old chapters); revisit after migration burn-in.
- Empty flat files on disk: `publishSummary` materializes the flat file even for
  ARTIFACTS-authority chapters (ChapterTranslationStore.kt:1781-1784). Pure noise.
- Legacy glossary non-atomic overwrite (ChapterTranslationStore.kt:1643-1657).

Read side (root cause of "lost after restart", reader display included):
- `persistedChapterStatus` TranslationManager.kt:426-467 (callers 386, 423) —
  flat-file decode only.
- `getChapterTranslationForReader` TranslationManager.kt:490-509 — flat-file decode.
- `DownloadPageLoader.kt:46` calls the above.
- Destructive read: `getChapterTranslation` deletes flat file on decode failure
  (TranslationManager.kt:501-510).
- Artifact-aware rehydration ALREADY EXISTS: `ChapterTranslationStore.open()`
  (ChapterTranslationStore.kt:1884-1912) — read side just never used it.

I/O amplification:
- Full-manifest rewrite per stage emission (ChapterTranslationStore.kt:1191-1202)
  → O(N^2) bytes per chapter on SAF.
- Promotion double-writes the same full snapshot (candidate + committed files,
  ChapterArtifactStore.kt:398-406).
- Retention directory sweep after every promote/cancel
  (ChapterArtifactStore.kt:1133-1147, called 461/515/537/866/934).
- Summary sidecar non-atomic direct overwrite (ChapterTranslationSummaryStore.kt:58-63).
- Orphaned cleaned images in `X_images/` never swept (outside managed tree).

Pending uncommitted fixes in working tree (must land first):
- MangaScreenModel.kt dead-button fix (live `skipCache=true` download check) +
  DownloadCache.kt renewal guard + new DownloadCache test.
- Known risk to fix before commit: the live disk probe runs on the main thread
  inside the UI event handler (MangaScreenModel.kt:958-966) — move off main.
- Working tree also contains unrelated docs/AGENTS.md churn — stage selectively.

## Phases

### Phase A — Batch behavior stabilization (small)
A1. Move `skipCache=true` probe off main thread; commit dead-button fix
    (code + test only, no docs churn).
A2. Read-side migration: `persistedChapterStatus`, `getChapterTranslationForReader`,
    `getChapterTranslation` resolve through artifact-aware `open()` with legacy
    flat-file fallback for LEGACY-authority chapters. Fixes "lost after restart"
    for batch AND reader chapters.
A3. Stop destructive flat-file delete on decode failure (quarantine/rename instead).

### Phase B — Storage I/O deslimming
Director amendment (2026-08-25): the summary sidecar concept is REMOVED
entirely - no X.summary.json writes or reads anywhere; chapter status derives
purely from the artifact manifest (+ expectedPageCount manifest field, set at
batch pre-registration / reader first write, never shrunk); glossary is the
only batch-end sidecar write (atomic). Old X.summary.json files on disk are
ignored; swept in Phase C.

B1. (absorbed by the amendment above)
B1a. Status certification is fail-safe: a chapter without a trusted expected
     page total cannot be certified TRANSLATED. Live in-flight work is not an
     ERROR. After an interrupted/dequeued batch, missing expected pages alone
     produce READY_WITH_WARNINGS; ERROR requires a real durable failure.
B2. Reduce manifest write cadence: batch page registrations to chunk/commit
    boundaries (or debounce) without weakening crash recovery; dedupe promotion
    double snapshot write.
B3. Retention sweep once per batch/chapter close, not per promote.
B4. Atomic glossary sidecar via ChapterDocumentIo pattern (summary half
    superseded by the amendment). Batch translation updates its in-memory
    glossary immediately, but persists it only at committed chunk boundaries
    and at successful completion / orderly cancellation / close. Reader and
    manual single-page glossary cadence remains unchanged.
B5. Sweep orphaned cleaned images on chapter open / batch end.
O1. Evict probe-opened stores / bound the registry (separate non-published
    probe map per investigation).

### Phase C — Legacy deletion

Approved technical plan:
`epics/dbbdec28-0cb7-4db7-abba-b867272157f0/artifacts/phase-c-one-way-legacy-migration-plan/index.md`

C1. Replace ongoing legacy support with a lazy, per-chapter, one-way rescue.
    Before any mutation, fully materialize and validate artifact snapshots,
    then publish one final ARTIFACTS-authority manifest. No startup/library scan.
C2. Preserve incomplete legacy pages through the existing schema-1 committed
    snapshot pointer with provisional LEGACY semantics. Keep it through
    candidate failure/cancel/recovery; retire only after validated commit.
C3. Quarantine the legacy JSON/glossary through the durable
    INTENT -> PRESERVED -> DELETED state machine. Keep it for the migrating app
    version; a later version may delete only after per-chapter health validation
    and an immediate identity match of the exact resolved file. Mismatch or IO
    uncertainty retains it. Corrupt sources are preserved collision-safely.
C4. Remove every new legacy writer/file-creation fallback and legacy resync.
    Glossary becomes artifact-only after its one-time import. New translations
    never create X.json, legacy glossary, or summary files.
C5. Delete dead stage APIs/tests, `tempFileNameFor`, summary helper, and obsolete
    resync code. Retain only read/mapping/quarantine functions required by the
    one-way importer.
C6. Recoverable rescue/probe failures are not cached. Failed rescue rejects
    mutation before memory changes and uses existing retryable batch/single-page
    error mappings.

## Boundaries

- Do NOT touch reader translation behavior (manual/rolling-auto pipelines,
  scheduler design, provider-lane sharing, barrier changes) — separate decisions.
- Do NOT weaken crash safety: atomic publish pattern stays; only cadence/scope
  of writes changes, validated by existing characterization tests.
- Commit hygiene: selective staging; no docs/AGENTS.md churn in code commits.

## Validation

Per phase: focused unit tests, then `spotlessCheck :app:testStandardDebugUnitTest
:domain:testReleaseUnitTest --no-daemon`. Known pre-existing failure:
AotReportBubbleFillTest pixel mismatch — not a regression signal.

### Final translation-module audit (Director direction, 2026-08-25)

After C1-C5 are implemented and regression-tested, a separate GLM-5.3 reviewer
at maximum reasoning must audit the cumulative translation module before device
installation. Per Director direction, intermediate Phase C review stops are
consolidated into this single final independent audit to reduce delivery
latency; focused validation still runs at each implementation boundary. The
audit covers storage and SAF I/O;
manual, rolling-auto, and batch translation; artifact/legacy ownership and
migration; UI/UX entry points and state transitions; scheduling, cancellation,
recovery, and lifecycle behavior. Installation is gated on an APPROVE verdict,
or on all audit findings being fixed and independently re-reviewed.

## Reports

Implementers write to `engineering/` under this folder; checkpoints.md updated
per phase.
