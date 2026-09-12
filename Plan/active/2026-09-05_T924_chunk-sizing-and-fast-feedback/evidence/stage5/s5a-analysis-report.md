# T924 Stage 5 Slice A — Analysis Provider, Chunk Persistence, Governor Sublimit

Implementer report. Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`, base HEAD `a56f232`. No commits made (orchestrator commits).

## 1. Contract anchors (file:line)

Contract files under `Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/stage0/`:

- `contracts-provider-analysis.md`
  - T924-AP-01 protocol principles — :67
  - T924-AP-03 request envelope — :112
  - T924-AP-04 response schema — :165
  - T924-AP-05 evidence syntax + V-rules — :240
  - T924-AP-06 field caps — :282
  - T924-AP-08 governor/retry bridge — :364
  - §2 capability gate — :395; §4 authority inputs — :515; §5 no-work policy — :590
  - §6 DR-A mixed malformed retention — :635
- `contracts-schemas-fingerprints.md`
  - T924-SC-20 sidecar-then-pointer publication — :314; T924-SC-21 content-addressed names
  - F4/FP-04 opaque credential signature (never raw credential)
- `contracts-state-transactions.md`
  - T924-ST-07 ANALYSIS_PLAN transition; T924-ST-08 ANALYSIS_CHUNKS transition + resume prefix rule
- `wave2-review.md` F1 (do not wire decideResume / publish COMPLETE), F4/gap-3 (core-then-context order pin)

## 2. Design per deliverable

### D1 — Analysis provider client (new `translator/analysis/`, 3 files)

- `AnalysisWire.kt` — `AnalysisRunIdentity` (manga scope carried as explicit `absent:v1` when unprovable — never empty, §4.1), `AnalysisEvidenceTexts` (wire `p<N>` / `p<N>_b<M>` identities + `wirePageKeyByStorageKey` translation), validated record types, `AnalysisCoverageKind { COMPLETE, MISSING_ONLY }`.
- `AnalysisRequestBuilder.kt` — protocol `"tachiyomiat-analysis"` schemaVersion 1 (:26,:29); `buildChunkRequest` (:72) emits pages in CORE-then-CONTEXT order and fail-fasts with `IllegalStateException("contributing-set order must be core-then-context (wave-2 F4/gap-3)")` (:86). Manual JSON emission (no reflection), JSON-escaped incl. `\u000C` (Kotlin has no `\f` escape). `existingCanon` carries chapter facts with series/user authority keys emitted as JSON `null` (present-but-absent, never silently omitted).
- `AnalysisResponseValidator.kt` — `classify(rawText, request, corePageKeys, contextPageKeys)` (:113) walks the JsonObject manually so every V-violation is individually reported. V1 unknown ref (:646,:703), V2 duplicate id (:176,:208,:312), V3 missing field (:538), V4 overlong/count (:567 etc., total evidence ≤ 512 :418), V5 enum (:333..:433), V6 non-JSON/truncated (:477,:484), V7 version strict (:131), V8 excerpt-hash recompute — `^(?:e:)?([0-9a-f]{16})$` vs recomputed full `StageFingerprints.sourceExcerptHash(text)` prefix (:657,:662), V9 core-page duty (:691). Refusal checked FIRST via `TranslationResponseFaithfulness.isStructuralRefusal`. Chapter-only authority: `AUTHORITY_KEY_REGEX` `(?i)(series|user)[_-]?(authority|canon|glossary)` (:63) → field DROPPED + reported in `droppedAuthorityKeys` (:152) — never persisted, never auto-promoted. Wire JSON parsed with `ignoreUnknownKeys = true` (wire is ephemeral; T924-SC-06 scopes durable docs only).
- `AnalysisChunkExecutor.kt` — `AnalysisTextTransport` (:29) is the pluggable per-backend seam; `execute()` (:140) runs ≤2 CLASSIFIED attempts, each wrapped `BatchRequestSublimitGate.executeBatch { withTranslationRetry(maxAttempts=3, budget, governor) { postStructuredAnalysis(...) } }` (T924-AP-08 bridge; budget default 8 via `RequestRetryBudget`). Malformed → exactly ONE identical reissue → typed `PROTOCOL`/`PAUSE`. Refusal → typed `REFUSAL`/`TERMINAL`, no reissue. Transport typed failures → `TransportPaused`. MISSING_ONLY = clean parse + `!hasExtractionContent`. `runner()` (:102) adapts to the coordinator's `AnalysisChunkRunner`; `AnalyzerProvenanceFactory` (:287) freezes provider/model/promptVersion/credential signature.

### D2 — Chunk persistence (`pipeline/batch/AnalysisChunkPublication.kt`)

- ONE validated chunk = ONE `publishSidecarPointers` transaction (T924-SC-20): content-addressed `analysis/f-<sha256>.json` sidecar published first, then manifest `analysisChunks` pointer appended atomically. Crash windows leave at most an orphan (retention-swept) — never a dangling pointer.
- Order enforced: `result.chunkOrdinal != manifest.analysisChunks.size` → `Rejected("chunk ordinal out of order: …")` (:59) — list order == ordinal order, so the ST-08 resume scan is an O(size) index read.
- Rejection of any kind (invalid schema, non-VALID status, ordinal gap, stale manifest, sidecar write fault) leaves the PRIOR manifest authoritative; the failed chunk stays unpersisted.
- `contentFingerprint` (:96) = SHA-256 over canonical re-encode with `createdAtEpochMs` zeroed → equal chunks map to the equal (idempotent) name.
- Store addition (flagged): `ChapterArtifactStore.analysisChunkSidecarName` (:824) + `ChapterArtifactLayout.analysisChunkFile` — 4-line additive helper beside the existing WP9 sidecar-name helpers; artifact DTOs untouched.

### D3 — Coordinator extension (`ChapterProfileBatchCoordinator.kt`)

- After the COMPLETE-OCR preflight barrier (corpus fingerprint non-null, :381) the run enters `runAnalysisPhase` (:408):
  1. **ANALYSIS_PLAN** record published (:431) — phase transition only; the corpus is re-derived from the DURABLE checkpoints (`corpusEntriesFromCheckpoints`, :734), fingerprint drift → typed PAUSED (never a stale plan).
  2. **ANALYSIS_CHUNKS** (:561) — `persistedPrefix = manifest.analysisChunks.size` (:545); chunks with `chunkOrdinal < persistedPrefix` are skipped (`continue`, :583) — resume never re-sends validated work and re-runs ZERO OCR (checkpoints reused upstream by ST-06 identity).
  3. Per chunk: runner → `Completed` → `AnalysisChunkPublication.buildResult + publish` → on `Committed` the store manifest is refreshed and counters published; `MISSING_ONLY` increments `COUNTER_CHUNKS_PENDING` (DR-A Option 1 — never blocks the chapter); `Paused`/`Refused` publish a `COUNTER_CHUNKS_FAILURES = 1` record and return a typed PAUSED with the `ProviderFailure` + `retryAfterAtEpochMs`; publication `Rejected` → `PERSISTENCE_REJECTED` (prior manifest authoritative).
- Terminal state: **PAUSED** with `ANALYSIS_STOP_REASON` (:716) — reconcile/freeze are slice B; `decideResume` and `COMPLETE` untouched (wave-2 F1).
- Skip rules (§2.1, §5): empty plan → `COUNTER_SKIPPED_NO_WORK` + `ANALYSIS_NO_WORK_REASON`; runner == null → `COUNTER_SKIPPED_NO_TRANSPORT` + `ANALYSIS_NO_TRANSPORT_REASON` (typed CONFIGURATION gate, never garbage chunks).
- Bug fixed during testing: `AnalysisCorpus` looked chunk page keys (STORAGE keys) up in a wire-key map → `evidenceTextsFor`/`publicationInput` now key by `storagePageKey` and translate wire→storage only for persisted evidence refs (coordinator test caught this).

### D4 — Governor Batch sublimit (`ProviderRequestGovernor.kt`)

- `BatchProviderSublimit` (:707): measured constant `BATCH_REQUESTS_PER_MINUTE = 15` (not a flag), credential-wide model-agnostic key (`model = null`, credentialScope preserved), policy = rolling 60 s window, `minimumSpacingMs = 0` (no forced sleeps), `maxInFlight = 1`, RPM-only v1 (`tokensPerMinute = Int.MAX_VALUE`; DR-C TPM values deferred to measurement).
- `BatchRequestSublimitGate` (:750): nested bucket-2 admission beneath the untouched shared bucket-1 (`SharedProviderRequestGovernor`) — interactive reserve, starvation guard, cooldowns all hold. All-or-nothing: if bucket 1 defers after bucket 2 granted, the outer permit is released BEFORE the pause propagates (`releaseAdmitted`, :561; `try/finally` in `executeBatch`). Interactive requests never enter the gate (structural skip).
- Executor routes all analysis requests through the gate, then `withTranslationRetry` through bucket 1.

### D5 — Real provider/model identity at dispatch freeze (`BatchChapterTranslator.kt` :662-684)

- `providerKey = "<engine>:<model>"` (e.g. `gemini:gemini-2.5-flash`), `credentialId` = first 16 hex of SHA-256 over the credential secret (LM Studio: base URL; otherwise API key) — one-way hash only, never a raw key (T924-FP-04). Achieved via existing `translationPreferences` — no new plumbing; `RunConfigSnapshot.credentialId` already additive with default `""`.

## 3. DR-A handling

- MISSING_ONLY = response parsed with zero V-violations but no extraction content → the independently complete empty-record subset COMMITS (sidecar + pointer), `COUNTER_CHUNKS_PENDING++`, run continues/pauses normally — a missing chunk never blocks the chapter (Option 1).
- AMBIGUOUS_PROTOCOL (any V1..V9, version error, unparseable) → whole response discarded, ONE identical reissue, then typed PROTOCOL PAUSE. No partial retention.
- TERMINAL_REFUSAL → typed REFUSAL/TERMINAL, no reissue, nothing retained.

## 4. Diff summary

Modified (main):
- `app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt` (+~650: analysis phase, corpus rebuild, runner seam, counters/reasons)
- `app/src/main/java/eu/kanade/translation/translator/ProviderRequestGovernor.kt` (+112: `releaseAdmitted`, `BatchProviderSublimit`, `BatchRequestSublimitGate`)
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt` (+24: real provider/model/credential identity)
- `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt` (+4: `analysisChunkSidecarName` — flagged additive)

New (main): `translator/analysis/` (`AnalysisWire.kt`, `AnalysisRequestBuilder.kt`, `AnalysisResponseValidator.kt`, `AnalysisChunkExecutor.kt`), `pipeline/batch/AnalysisChunkPublication.kt`.

Tests: new `translator/analysis/AnalysisRequestOrderTest.kt` (gap-3 pin), `translator/analysis/AnalysisChunkValidationTest.kt` (V1-V9 matrix + taxonomy + executor attempt policy), `translator/ProviderGovernorBatchSublimitTest.kt`, `pipeline/batch/AnalysisChunkPublicationTest.kt`, `pipeline/batch/ChapterAnalysisPhaseCoordinatorTest.kt`; updated `pipeline/batch/OcrPreflightCoordinatorTest.kt` (analysis-phase terminal expectations) and `pipeline/batch/OcrPreflightRejectedMidRunDurabilityTest.kt` (resume reason → `ANALYSIS_NO_TRANSPORT_REASON`).

READ-ONLY surfaces respected: artifact DTOs, `StageFingerprints.kt`, rendering, `SequentialBatchCoordinator.kt`, domain, ui untouched. `ChapterTranslationStore.kt` NOT modified (no facade helper needed).

## 5. Tests and counts

Command: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.translator.*" --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.artifact.*"` → **545 tests, 0 failures** (BUILD SUCCESSFUL). `ChapterTranslatorQueueRestoreTest` (touches the coordinator) also run separately: green.

Coverage pins: gap-3 core-then-context order + byte determinism; V1-V9 matrix (each rule individually asserted); refusal no-retry; exactly-one identical reissue; MISSING_ONLY partial commit (pending counter, chapter unblocked); series/user authority dropped+reported; publication round-trip, ordinal ordering + rejection, invalid-never-persisted, stale-manifest authority; coordinator transitions incl. checkpoint reuse (zero re-OCR) + persisted-prefix skip + typed pause/refusal; sublimit 15-per-window + 16th immediate deferral (no sleeps), model-agnostic shared pool, interactive isolation, permit release on inner pause.

## 6. Deviations

1. **Transport not wired to real providers** — contract §8.2 assigns provider HTTP integration to a provider work package. Slice A defines `AnalysisTextTransport`; coordinator treats runner==null as a typed CONFIGURATION skip (PAUSED, `ANALYSIS_NO_TRANSPORT_REASON`), exactly the §2.1 gate.
2. **No durable corpus-manifest sidecar** — the analysis plan is recomputed from durable checkpoints at every entry (ST-01.4/ST-05 analogy); only the phase transitions persist. Run-record `ocrCorpusFingerprint` is the durable identity; drift → typed PAUSED.
3. **Run-record failure text** — schema-v1 `ChapterRunRecord` has no failure-text field; typed chunk failures surface via `BatchPass1Outcome.failure`/reason + logcat, with `COUNTER_CHUNKS_FAILURES` as the durable marker (capped naturally: one per pass, run pauses).
4. **`ChapterArtifactStore.analysisChunkSidecarName` added** (4 lines) — the store-side name helper needed by the pipeline-level publication object; additive beside WP9 helpers, no DTO or store behavior changed.

## 7. Risks

- Executor defaults to the process-wide `SharedProviderRequestGovernor`; per-test governors are injected in tests. Production wiring of the transport (provider package) must construct `AnalysisChunkExecutor` with the same shared governor + gate.
- Sublimit is RPM-only v1 (DR-C TPM deferred to measurement) — token-level bursts ride bucket 1's TPM as before.
- `MISSING_ONLY` chunk persists an empty record set; slice B reconcile must treat empty VALID chunks as pending, not canon.

## 8. Cuts

None. All five deliverables landed (D1-D5); cut order 5→4 was not needed.
