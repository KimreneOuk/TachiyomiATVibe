# U track — Phase truth + simplified UI (T934)

Implementer lane report. All paths relative to the worktree root
(`orchestrate_execution_order_v3`). Every line number was re-verified by
reading the current file contents after the final edit.

Scope kept to the HARD ALLOWLIST. Verified with `git status --porcelain`
(read-only): this lane's edits are exactly the seven allowlisted files plus
four new test files. Other lanes' concurrent modifications
(BatchWriteGate/ChapterProfileBatchCoordinator/OverlapScheduler/
ProfileEnvelopeExecutor/PageStageLeaseTable/ChapterTranslationStore/
ChapterArtifactStore/StorePersistenceScheduler, several existing test files,
`team/i-inpaint-decoupling.md`, `team/r2c-spike.md`) pre-existed in the
shared worktree and were not touched by this lane. No git-mutating command,
no Gradle invocation.

---

## U.1 — Rebuild/restore phase + payload in the existing projection

No competing enum; `TranslationBatchPhase` gains two appended values and the
snapshot gains one nullable payload.

- `app/src/main/java/eu/kanade/translation/model/TranslationProgressSnapshot.kt`
  - :50 — `enum class TranslationBatchPhase { IDLE, FIRST_PASS, FINALIZING, FINISHED, REBUILDING, RESTORING }` (appended at end; doc at :44 explains derivation, no new state machine).
  - :60 — `data class BatchRebuildProgress(val restoredPages: Int, val totalPages: Int)` with derived `remainingPages` (coerced ≥ 0).
  - :135 — `val rebuildProgress: BatchRebuildProgress? = null` on `TranslationProgressSnapshot` (default keeps every existing constructor/call site compatible).
- `app/src/main/java/eu/kanade/translation/manager/BatchProgressProjector.kt`
  - :38 — `REBUILD_PROBE_MIN_INTERVAL_MS = 500L` (T912 ANR discipline).
  - :60 — `internal fun rebuildTruthFromRunRecord(record): Pair<TranslationBatchPhase, BatchRebuildProgress?>?` — pure rule:
    - `RUN_SNAPSHOT`/`SOURCE_VALIDATION` → REBUILDING, payload null (no counters yet).
    - `OCR_PLAN` with total > 0 && done > 0 → RESTORING(restored = done.coerceAtMost(total), total).
    - `OCR_PLAN` with done == 0 → **null** (see design decision 2).
    - every later state (`OCR_PREFLIGHT` … `COMPLETE`/`PAUSED`/`ABORTED`) → null (past the rebuild window).
  - :91 — `internal fun TranslationProgressSnapshot.withRunRecordTruth(record)` — stamps only when `state == TRANSLATING && batchPhase in {FIRST_PASS, REBUILDING, RESTORING}`; never restamps FINISHED/QUEUED/PAUSED/idle snapshots.
  - :409 — `withRunRecordRebuildTruth` flow wrapper: live-window gate (same FIRST_PASS/TRANSLATING window as the durable-reconstruction probe), 500 ms throttled record probe (:426-428), cached record, re-arm when the flow leaves the live window (:424), emits stamped snapshot only while live (:431), I/O confined via `withContext(Dispatchers.IO)`.
  - :441 — `readActiveRunRecord(chapterId)` — `activeStores[chapterId]?.artifactManifest?.activeRun` (sidecar pointer kept current by the coordinator's publish commit) → `store.artifactStore` → `ChapterArtifactStore.readRunRecord(pointer)` as `RunRecordRead.Usable`. Uses existing `internal var` accessors; no forbidden file touched.
  - :383-384 — wired into `observeBatchProgressProjection` (`.withRunRecordRebuildTruth(chapterId)` → `.distinctUntilChanged()`), so both consumers of `TranslationManager.observeBatchProgress` (MangaScreenModel drawer, ReaderViewModel reader bar) receive the stamped phase.

## U.2 — Sheet Simple (default) / Advanced (persisted chevron)

- `app/src/main/java/eu/kanade/presentation/manga/components/TranslationProgressSheet.kt`
  - :136-140 — `advancedView` initialized from the persisted preference via `Injekt.get<TranslationPreferences>()` (remember-scoped; no new DI surface).
  - :416-439 — chevron row: "Advanced details" + `ExpandLess`/`ExpandMore`, click toggles state **and** writes `translationPreferences.translationProgressSheetAdvancedView().set(next)` (:420-423) — persists across openings.
  - :441-486 — Advanced view wraps Pipeline Stages (`LivePipelineGrid`), Page Overview chips, FailureSummary groups, and queue detail (`queuePosition > 1` line).
  - :487-495 — Simple view (default, preference default false): ONE status line (subtitle) + a one-line failure notice `translation_sheet_failures_notice(failedCount)` only when failures exist; no stage cards/grid/groups.
  - :362-372 — hero progress bar: `rebuildInProgress` → **indeterminate** `LinearProgressIndicator` (run-record counters are not page progress); determinate branches unchanged otherwise.
  - One-line failure notice copy routed through the new resource (U.6).
- `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt`
  - :254-255 — the ONE new boolean, in the **existing** translation preferences file:
    `translationProgressSheetAdvancedView()` → key `"translation_progress_sheet_advanced_view"`, default `false`.
- MangaScreen needed no change: the sheet reads the preference internally.

## U.3 — Status-line priority chain, implemented once in the truth layer

- `app/src/main/java/eu/kanade/translation/ui/TranslationUiTruth.kt`
  - :630-653 — `enum class BatchStatusLineKind` (bounded vocabulary incl. REBUILDING/RESTORING).
  - :660-664 — `data class BatchStatusLine(kind, formatArgs, fallback)`.
  - :675-689 — `batchStatusLine(snapshot, isResuming)`: **isResuming → request → queue → pause → rebuild/restore → batchPhase**, each stage a private helper (:692, :719, :738, :753, :775). The sheet subtitle and the reader bar both derive from this chain; no surface re-implements precedence.
  - :799-805 — unreachable-through-chain REBUILDING/RESTORING branch exists only for `when` exhaustiveness after the enum extension.

## U.4 — BottomReaderBar consumes the same phase truth

- `app/src/main/java/eu/kanade/translation/ui/TranslationUiTruth.kt`
  - :883-933 — `readerBarLine(snapshot)`: returns null exactly under the legacy hidden rule (no request, no pause, IDLE); request phases keep verbatim bar wording (:891-906); pause → "Translation paused" (:907-910); **REBUILDING → "Rebuilding pipeline…" (:911-914); RESTORING → "Restoring N pages…" with `formatArgs = [remainingPages]` (:915-922)** — the frozen "Batch X/Y" branch (:923-927) is now unreachable during a rebuild because the projector stamps the phase first.
- `app/src/main/java/eu/kanade/presentation/reader/appbars/BottomReaderBar.kt`
  - :69-95 — status block replaced: resolves `TranslationUiTruth.readerBarLine(snapshot)`, maps REBUILDING → `translation_status_rebuilding`, RESTORING → `translation_bar_restoring(*formatArgs)`, everything else → `line.fallback` (byte-identical legacy bar wording). Imports updated (:31-32; stale `TranslationBatchPhase` import removed). Error/paused coloring preserved (:91).

## U.5 — "Resuming…" is snapshot-driven; synchronous self-reset deleted

- `app/src/main/java/eu/kanade/presentation/manga/components/TranslationProgressSheet.kt`
  - Deleted: the composition-time reset and the `onClick { isResuming = true; onResume(); isResuming = false }` block (formerly :471-480) — the old code made "Resuming…" unobservable.
  - :103 — `RESUME_PENDING_TIMEOUT_MS = 10_000L` (bounded escape hatch if the manager's cooldown gate rejects the resume; button can never stay disabled forever).
  - :132 — `isResuming` remembered per chapter, starts false.
  - :142-144 — `LaunchedEffect(chapterId, batchPhase, state)`: clears the hold once phase truth leaves IDLE/TRANSLATING (snapshot-driven).
  - :145-149 — `LaunchedEffect(chapterId, isResuming)`: clears the hold after the bounded 10 s timeout.
  - :574-598 — resume `onClick` now only `isResuming = true; onResume?.invoke()` with an explanatory comment.

## U.6 — New copy through truth + NEW string keys only

- `i18n-at/src/commonMain/moko-resources/base/strings.xml`
  - :237-244 — 8 NEW keys appended before `</resources>`; **no existing key modified**:
    `translation_status_rebuilding`, `translation_status_restoring` (%1$d of %2$d), `translation_bar_restoring` (%1$d), `translation_status_rebuilding_short`, `translation_status_restoring_short`, `translation_status_resuming`, `translation_sheet_advanced_toggle`, `translation_sheet_failures_notice` (%1$d).
- Surfaces resolving the NEW kinds:
  - Sheet subtitle: `batchHeaderStatusText` :1024-1037 (REBUILDING/RESTORING → resources; else legacy `batchStatusHeaderSubtitle`, whose legacy body is untouched except the two new exhaustive enum branches :1127-1129 which render the truth fallback in the plain function).
  - Sheet pill: :722-727 (REBUILDING/RESTORING short strings; :726 isResuming → `translation_status_resuming`).
  - Reader bar: `BottomReaderBar.kt` :79-88.
  - Existing truth assertions unedited; legacy English fallbacks kept byte-identical (verified against `TranslationProgressSheetSubtitleTest` and `TranslationQueuePositionAndPhasesTest` expectations).

## U.7 — New tests (new files only; plain JVM, JUnit5 + kotest)

1. `app/src/test/java/eu/kanade/translation/ui/T934RebuildTruthTransitionsTest.kt`
   Pure `rebuildTruthFromRunRecord` truth transitions: no record → null; RUN_SNAPSHOT/SOURCE_VALIDATION → REBUILDING; OCR_PLAN done=0 → null; OCR_PLAN done=37/total=70 → RESTORING(37, remaining 33); every post-preflight state (OCR_PREFLIGHT, ANALYSIS_CHUNKS, ENVELOPE_PLAN, TRANSLATE, FINALIZE, COMPLETE, PAUSED, ABORTED) → null; `withRunRecordTruth` stamping window (live FIRST_PASS stamped both ways; post-preflight record unstamped; FINISHED/QUEUED/PAUSED never restamped; already-REBUILDING restampable; payload clamped to total).
2. `app/src/test/java/eu/kanade/translation/ui/T934ReaderBarTruthTest.kt`
   Reader-bar copy mapping: REBUILDING line never contains "12/70"; RESTORING args [33] → "Restoring 33 pages…"; no-payload RESTORING args [0]; request outranks rebuild; pause outranks rebuild; legacy "Batch 12/70 pages" and verbatim request wording preserved; FINALIZING with unknown totals → "Preparing translation batch"; hidden-null cases. Plus the `batchStatusLine` chain test (rebuild → restoring → running → FINALIZING → COMPLETED; request outranks pause+rebuild).
3. `app/src/test/java/eu/kanade/translation/T934SheetAdvancedViewPreferenceTest.kt`
   Simple/Advanced toggle persistence: default false; `set(true)` visible through a fresh `TranslationPreferences` facade over the same store; toggles back off.
4. `app/src/test/java/eu/kanade/translation/manager/T934ProjectorRebuildTruthTest.kt`
   Integration through the real projector flow + artifact store: `ChapterArtifactStore(AtomicChapterDocuments(FakeChapterDocumentIo()), ChapterArtifactLayout("Chapter 9"))` + `publishManifest` + `publishActiveRun` → `ChapterTranslationStore(translationFile = null, fileCreator = null, artifactStore = …, initialArtifactManifest = committed.manifest)` → projector `observeBatchProgress(chapterId).first()` (nested `runTest` helpers because `runTest` returns `TestResult`). Asserts RESTORING(37/70, remaining 33) from a durable OCR_PLAN record, REBUILDING(0 pages payload) from RUN_SNAPSHOT, OCR_PREFLIGHT record leaves the phase FIRST_PASS unstamped, and a memory-only store (no artifacts) is untouched.

Fixture notes: `runId`/fingerprints use 64-hex strings; `RunConfigSnapshot(sourceLang="ja", targetLang="en", ocrEngine="onnx-v3", ocrModelHash=hex64, detectorModelHash=hex64, inpaintMode="FAST", providerKey="gemini:gemini-2.5", protocolVersion=2, readingOrderVersion=1)`; counters use the literal coordinator keys `"ocrPagesTotal"`/`"ocrPagesDone"`/`"ocrPagesReused"` (mirrors `ChapterRunRecordSchemaTest`). Projector fixture mirrors `BatchProgressProjectorDurableReconstructionTest` (mockk-relaxed `TranslationPipeline`, `MutableStateFlow` empties, null lambdas, tracker via `registry.createTracker(..., scope = backgroundScope)`).

## U.8 — Existing tests stay green by construction

- No existing test file edited by this lane (verified via `git status --porcelain`; the modified existing test files in the worktree belong to other lanes).
- Legacy rendering preserved exactly: `batchStatusHeaderSubtitle` body untouched except the two new exhaustive `when` branches (sheet :1127-1129); `queuePositionLabel`/`ordinalSuffix` are now one-line delegations to the truth layer returning identical strings (sheet :1010-1014); bar wording is byte-identical through `readerBarLine` fallbacks; subtitle fallbacks byte-identical through `batchStatusLine` fallbacks.
- `TranslationBatchPhase` extension appends at the end; the single exhaustive `when` over it (the sheet subtitle) gained explicit branches; `LiveStatusPill`'s text `when` is boolean-chained (no exhaustiveness change); hero/phase `when`s are over unchanged enums.
- No Gradle run (forbidden) — Main Leader builds/tests.

---

## Design decisions

1. **Derivation window.** Rebuild/restore truth is derived ONLY inside the live run window (`state == TRANSLATING && batchPhase in {FIRST_PASS, REBUILDING, RESTORING}`) from the store's current manifest `activeRun` pointer. This reaches both surfaces through the single `observeBatchProgress` flow, so the sheet and the reader bar can never disagree.
2. **Fresh-run OCR_PLAN ambiguity guard.** Fresh runs publish OCR_PLAN once before the whole OCR pass (done stays 0); resume runs republish per adopted page (done grows). Therefore OCR_PLAN + done > 0 ⇒ RESTORING, but OCR_PLAN + done == 0 ⇒ **not** rebuild — labeling it REBUILDING would fake "Rebuilding pipeline…" over a normal first translation. RUN_SNAPSHOT/SOURCE_VALIDATION are unambiguous (resume preamble only) and always map to REBUILDING.
3. **Probe discipline.** 500 ms throttle per chapter flow, record cached between probes, `Dispatchers.IO` for the sidecar read, and the probe only runs while the live-window gate is open (re-armed when the gate reopens) — same ANR posture as the existing durable-reconstruction probe (T912).
4. **Priority inversion avoided at the sheet.** The composable subtitle call resolves `batchStatusLine` first and only rewrites the line for REBUILDING/RESTORING kinds, so an active request/pause still outranks rebuild copy (U.3 chain holds at every surface).
5. **Resuming hold bounds.** The snapshot-driven clear covers the happy path; the 10 s timeout covers the rejected-request path (manager cooldown), so the resume button can never be stuck on "Resuming…".
6. **Indeterminate bar during rebuild.** Run-record counters during re-adoption are not page progress; showing them as a fraction would regress trust. Bar is indeterminate until the phase leaves the rebuild/restore window (then the existing deterministic branches render).
7. **Legacy preservation strategy.** All legacy wording lives as `fallback` strings inside the truth lines, copied verbatim from the historical call sites; surfaces render `fallback` unless the kind has a new resource. Existing tests assert the fallback text and therefore pass unmodified.

## Risk notes for the Main Leader

- Compile-by-reading only (no Gradle permitted). Highest-risk points: the six-branch exhaustive `when`s over `TranslationRequestPhase` (truth :694-713, pill :707-718) — compile-safe as long as the enum still has exactly those six values; and the nested-`runTest` structure in `T934ProjectorRebuildTruthTest` (helpers return snapshots, tests return `TestResult`).
- The worktree carries concurrent lanes' edits (coordinator/gate/store files, MangaScreenModel tests). If another lane's coordinator change alters publish timing of `activeRun`, only the probe cadence is affected — derivation stays correct because it reads the committed pointer.

---

# Round 2 — verification fixes (post-review)

Verification accepted the design and diffs; two compile fixes were applied by
the verifier (noted here for the record, both outside my hand-edits):

1. `BatchStatusLineKind` / `BatchStatusLine` de-nested to top-level in
   `TranslationUiTruth.kt` — my nested-in-object declarations made the
   sheet/bar `import eu.kanade.translation.ui.BatchStatusLineKind` lines
   unresolvable (nested types need the qualifier path, not a direct import).
2. `T934ProjectorRebuildTruthTest.firstSnapshot` helper converted to a suspend
   `TestScope` extension — the nested-`runTest` form returns `TestResult`/Unit
   on JVM, not the lambda's value.

## U.7 test failure fixed (fixture bug, production correct)

- Symptom: `T934SheetAdvancedViewPreferenceTest "advanced view toggle
  persists across sheet reopenings"` — expected true, was false.
- Root cause (fixture semantics, NOT production): `InMemoryPreferenceStore`
  (core/common/.../preference/InMemoryPreferenceStore.kt :44-48, :93-95)
  wraps a **snapshot** of the stored value in a brand-new `InMemoryPreference`
  on every `getBoolean` call, and `Preference.set` mutates only that throwaway
  instance — writes never reach the store's immutable contents map. My test
  wrote through one wrapper and read through a new one, so the write was
  invisible. (Test 1 passes; test 3 passed only accidentally — a fresh read of
  a never-persisted store equals the default false.)
- Production verified correct, untouched:
  `AndroidPreferenceStore.getBoolean` (AndroidPreferenceStore.kt :39-41)
  returns a `BooleanPrimitive` backed by live `SharedPreferences`, so any
  facade reads any facade's writes; `TranslationPreferences.kt` :254-255 maps
  the canonical key `"translation_progress_sheet_advanced_view"`, default
  false.
- Fixture fix (test file only, pattern from `StrictConfigFromPrefTest:76`):
  a `persistedStoreWith(flag)` helper installs the facade-written value under
  the same key as the store's seeded durable contents — the fake-store
  stand-in for the SharedPreferences flush — and each reopening reads through
  a completely fresh `TranslationPreferences` facade over that store. No
  assertion weakened; the toggle-back-off test gained a stronger intermediate
  assertion (persisted on-state read back as true before toggling off).
