# T924 Wave 4 — Independent Review (S5 slice A: analysis provider, chunk persistence, Batch 15-RPM sublimit)

Reviewer: independent Reviewer (read-only; authored none of the reviewed diff)
Date: 2026-09-06
Worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`
Commit under review: `66fda24` "feat(translation): T924-S5 slice A analysis provider + chunk persistence + Batch 15-RPM sublimit" (parent `a56f232`); 16 files, +3583/−47.
Controlling contracts: `stage0/contracts-provider-analysis.md` (AP-01..08, §2/§4/§5/§6), `stage0/contracts-state-transactions.md` (ST-07/ST-08, TX-06/TX-07), `stage0/contracts-schemas-fingerprints.md` (SC-19..22, FP-04), `evidence/wave2-review.md` (F1, F4/gap-3), `implementation-sequence.md` §S5 + owed items.
Main-source paths below relative to `app/src/main/java/eu/kanade/translation/`; test paths to `app/src/test/java/eu/kanade/translation/`.

---

## Verdict

## ACCEPT-WITH-FIXES

All five deliverables conform to their contracts at the code level; the two CRITICAL-direction checks passed (V8 recompute is a real recompute; `release(usage=null)` does NOT refund the rolling-window reservation, so the 15-RPM sublimit is not a no-op). Acceptance is conditioned on F-W4-1 landing before the analysis transport is wired (slice B / provider package): the ST-08 resume-prefix skip is keyed on list size alone and can silently mix chunk plans across a corpus change. F-W4-2..5 are scheduled notes, none blocking.

Independent verification run: `:app:compileStandardDebugKotlin` + targeted 4-package `testStandardDebugUnitTest` → BUILD SUCCESSFUL (2m 55s); JUnit XML tally **classes=76 tests=545 failures=0 errors=0 skipped=0** — matches the implementer's reported 545/0 exactly.

---

## 1. Findings

### F-W4-1 — MEDIUM (resume hazard, durable-state poisoning path; fix BEFORE transport wiring)

The ST-08 resume-prefix skip is validated against nothing — `ChapterProfileBatchCoordinator.kt:549-550` reads `persistedPrefix = store.artifactManifest?.analysisChunks?.size ?: 0` and `:588` skips any `chunk.chunkOrdinal < persistedPrefix`. The skip never compares the persisted chunk at ordinal *i* against the re-planned chunk *i* (chunkId, corePageKeys, contributingCorpusFingerprint), and never compares the current corpus fingerprint against the corpus the persisted chunks were derived from.

`analysisChunks` is a chapter-scoped manifest list; runs are run-scoped. Cross-run scenario: run 1 plans and persists N chunks; the chapter source then changes (re-download) → new source digest → new runId (ST-03.1), corpus rebuilt from checkpoints with changed content; the re-planned chunk windows from the change point onward differ from the persisted ones — but the first N chunks of the NEW plan are skipped because the OLD plan persisted N records. Result: a durable chunk list mixing two plans/parameterizations, no typed pause, no rejection. Within-run drift IS typed-paused (`:449-456` "corpus fingerprint drifted between preflight and plan") — the cross-run boundary is the unguarded one. ST-07 resume says "verify corpus fingerprint still matches checkpoint fingerprints; mismatch → rebuild corpus" (done), but nothing invalidates or re-validates the chunk list on that mismatch.

Why MEDIUM and not HIGH today: FF-01 is default-OFF and no production transport exists (runner==null ⇒ the CONFIGURATION gate at `:513-541` — production never persists a chunk in this slice), and slice-B reconcile is not yet consuming. The moment a transport wires, this becomes a silent poison path.

Fix direction: inside the resume loop, for each skipped ordinal read the persisted chunk (pointer at index *i*) and require identity equality with `plan.chunks[i]` (at minimum `chunkId` + `corePageKeys` + `contributingCorpusFingerprint`; optionally record the run-level `ocrCorpusFingerprint` in the chunk record for an O(1) whole-plan check). Mismatch ⇒ typed PAUSED (same discipline as `:449-456`), or clear/rebase the pointer list in one M2 publication. Pin with a test: persist chunk 0, mutate one page's checkpoint fingerprint, re-run, assert typed pause (not silent skip).

### F-W4-2 — LOW (latent wiring trap, extends report §7 risk 1): default gate/governor instances are per-executor

`AnalysisChunkExecutor` (`translator/analysis/AnalysisChunkExecutor.kt:90-92`) defaults `sublimitGate = BatchRequestSublimitGate()` and `governor = SharedProviderRequestGovernor.instance`. `BatchRequestSublimitGate` internally constructs its own `ProviderRequestGovernor` (`ProviderRequestGovernor.kt`, `sublimitGovernor` field). The governor default is shared, but the gate default is per-instance: any production wiring that constructs more than one executor (per run, per chapter, per backend) gets an independent 15-RPM window — exactly the "two pools of 15" DR-C forbids. No production construction site exists yet (transport unwired), so this is latent.

Fix direction: expose a process-wide singleton gate (mirror of `SharedProviderRequestGovernor`), or make the gate a required constructor parameter and state the injection obligation in the provider-package kickoff next to the report's existing same-governor obligation.

### F-W4-3 — LOW (coverage mislabeling, masked): narrative-only response classifies COMPLETE

`AnalysisResponseValidator.ValidatedAnalysisResponse.hasExtractionContent` (`AnalysisResponseValidator.kt:104-106`) counts a non-blank `narrativeSummary` (or scenes) as extraction content. A response with zero terms/entities but a summary therefore classifies `COMPLETE` (`:437-439`) and commits an empty record set with no `COUNTER_CHUNKS_PENDING` marker — indistinguishable at the durable level from genuine full extraction. DR-A `MISSING_ONLY` is "no extraction content"; whether narrative counts is a design choice the wire doc states (`AnalysisWire.kt:114-119`), but the report's risk 3 ("slice B reconcile must treat empty VALID chunks as pending, not canon") only covers the MISSING_ONLY case, not this COMPLETE-labeled empty case.

Fix direction: for chunks whose core pages carry blocks, require ≥1 term/entity/scene for COMPLETE (summary-only ⇒ MISSING_ONLY/pending), or persist the coverage kind on `AnalysisChunkResult` so reconcile can distinguish.

### F-W4-4 — LOW (leniency drift in an "intentionally unforgiving" validator)

Two spots accept more than the contract sketch: (a) `EXCERPT_HASH_REGEX = ^(?:e:)?([0-9a-f]{16})$` (`:50`, `:653-658`) accepts a bare 16-hex excerptHash without the `e:` prefix (T924-AP-05 specifies `e:` + 16 hex); (b) a term with missing `kind` defaults to `"TERM"` (`:187`) instead of V3 — only an invalid value is fatal. Neither can admit an invented anchor (V8 recompute still applies to every ref; kind defaulting is cosmetically lossy only). Record the leniency or tighten; do not let it grow.

### F-W4-5 — NOTE (dead branch, misleading but harmless): relationship V1 resolution

`AnalysisResponseValidator.kt:279` checks `target !in knownIds`, but `knownIds` stores prefixed ids (`"e:e001"`, `"t:…"`, `"s:…"`) while `targetEntityId` is unprefixed — the set-membership test can never pass. Actual resolution happens through `pendingEntityIdWillResolve(target, rawEntities)` (`:279`, `:717-722`), which is correct (entities only, forward references allowed). The valid-response test passes through the fallback path, not the `knownIds` path. Simplify the condition to the working form or fix the prefixing to avoid a future reader "repairing" it wrongly.

---

## 2. Dimension verdicts (judged on code, not report prose)

### A. D1 — analysis provider client (translator/analysis/, 4 files): CONFORMANT

- **V1**: evidence pageKey must resolve into the request (`:645-647`), blockId must belong to that page AND pass the `p12_b4`-under-`p13` prefix guard (`:649-651`), scene range refs (`:695-709`), scene participants (`:326-330`), relationship targets (`:278-282`), record-id pattern `^[tesuc]\d{3,4}$` (`:48`, `:516-528`), chunkId conflict (`:139-147`).
- **V2**: per-kind id set with type-prefixed keys (`:176`, `:208`, `:312`).
- **V3**: required text fields (`:530-542`), gender/pronoun evidence-required (`:233`, `:248`), sourceNames non-empty (`:210-212`), range required (`:313-317`), revelation/question/conflict text (`:368-370`, `:382-384`, `:252-254`).
- **V4**: all T924-AP-06 caps present incl. total evidence refs (`:417-419`) and per-fact evidence (`:630-632`).
- **V5**: every closed enum from AP-04 individually checked (gender/strength/kind/applicability/tone/contentTags/register/hypothesis/confidence).
- **V6**: non-object / no-JSON / truncated / non-array shapes (`:470-487`, `:510-513`); fenced-body stripping matches the strict translation parser's tolerance (`:490-496`).
- **V7**: strict, missing version also fatal, never forward-interpreted (`:130-134`).
- **V8**: RECOMPUTE verified — `StageFingerprints.sourceExcerptHash(request.textByBlockId.getValue(blockId))` compared by prefix against the claimed hash (`:660-664`); the collector persists the full locally recomputed hash (`:463-467`), never the model's string. Test `AnalysisChunkValidationTest` "V8 invented evidence hash is fatal" pins a wrong hash; "V8 paraphrased hash format" pins format rejection.
- **V9**: record-level core-page duty for terms/entities/gender (`:676-693`), applied at `:190`, `:234-236`, `:288`; pinned by test.
- **Refusal FIRST**: `isStructuralRefusal` on the sanitized body before JSON parsing (`:119-122`); executor returns REFUSAL/TERMINAL with zero reissue (test: `transport.calls.size shouldBe 1`).
- **Chapter-only authority**: top-level keys matching `(?i)(series|user)[_-]?(authority|canon|glossary)` are dropped (their values are never parsed into any validated record) and reported in `droppedAuthorityKeys` (`:151-155`, executor logs at WARN `:227-231`); request side emits `userAuthority`/`seriesAuthority` as JSON `null` (present-but-absent, `AnalysisRequestBuilder.kt:127-128`). Never persisted, never promoted. Test pins drop+report.
- **Manual JSON emission**: hand-rolled with escaping for `\ " \n \r \t \b`, `\u000C`, and a `\u%04x` fallback for other controls (`AnalysisRequestBuilder.kt:150-163`); byte-determinism and parse-back asserted by `AnalysisRequestOrderTest`.
- **Exactly-one identical reissue**: ≤2 classified attempts reusing the SAME request object (`AnalysisChunkExecutor.kt:158-241`); test asserts `calls[0] == calls[1]` and `PROTOCOL`+`PAUSE` after the second failure; refusal and transport paths exit without reissue.

### B. D2 — AnalysisChunkPublication: CONFORMANT

One validated chunk = ONE `artifact.publishSidecarPointers` transaction (`AnalysisChunkPublication.kt:67-87`; store method pre-existing at `ChapterArtifactStore.kt:736`) — content-addressed sidecar first, atomic `analysisChunks` pointer append second (SC-20). `validationError()` gate + explicit non-VALID rejection before any I/O (`:50-58`). Ordinal enforced as `chunkOrdinal == manifest.analysisChunks.size` (`:59-64`) ⇒ list order == ordinal order ⇒ resume prefix is an O(size) read. `contentFingerprint` zeroes `createdAtEpochMs` and re-encodes canonically (`:96-103`); idempotence asserted (`AnalysisChunkPublicationTest` timestamp-invariance). Tests cover: round-trip read-back through the pointer idiom, out-of-order rejection with prior manifest intact, INVALID + semantically-broken rejection, stale-manifest rejection with committed list preserved. All drive the real store over a temp dir.

### C. D3 — coordinator analysis phase: CONFORMANT except F-W4-1

- Entered ONLY after the COMPLETE preflight barrier: `corpusFingerprint == null` (incomplete corpus) returns PAUSED before the phase (`ChapterProfileBatchCoordinator.kt:363-376`).
- ANALYSIS_PLAN published as phase transition only (`:424-434`); corpus re-derived from DURABLE checkpoints (`corpusEntriesFromCheckpoints :734-778` reads `manifest.ocrCheckpoints` + `readOcrCheckpoint` + snapshot sidecars); missing checkpoint/snapshot ⇒ typed PAUSED (`:439-448`); fingerprint drift vs preflight ⇒ typed PAUSED (`:449-456`).
- Resume prefix skip `:584-588` — never re-sends persisted chunks; zero re-OCR on resume proven by the process-death test (`resumedWorker.ocrPages shouldBe emptyList()`, `ChapterAnalysisPhaseCoordinatorTest` resume test). See F-W4-1 for the unguarded identity dimension.
- MISSING_ONLY = DR-A Option 1: commits the empty-valid subset, `COUNTER_CHUNKS_PENDING++`, chapter unblocked (`:612-620`; test `missing_only chunk commits its complete subset and never blocks the chapter`).
- Terminal: PAUSED + `ANALYSIS_STOP_REASON` (`:719-726`); paused/refused chunks publish a typed record (`COUNTER_CHUNKS_FAILURES`) and return `ProviderFailure` + `retryAfterAtEpochMs` (`:655-715`). NO COMPLETE publication anywhere in the phase.
- `decideResume` untouched: the coordinator diff contains only KDoc/comment deletions plus the analysis-phase insertion (hunk list verified); `decideResume` (`:1144`) and its OFF+COMPLETE semantics are byte-identical — wave-2 F1 holds.
- Wave-2 F4/gap-3: builder fail-fasts on CORE-after-CONTEXT (`AnalysisRequestBuilder.kt:79-89`, message names wave-2 F4/gap-3), pages emitted in `contributingPageKeys` order via `toRequestPages` (`AnalysisChunkExecutor.kt:302-324`); pinned by `AnalysisRequestOrderTest` (payload order, roles, byte determinism, throw test) — the owed gap-3 item is DISCHARGED.

### D. D4 — governor sublimit: CONFORMANT; the CRITICAL check passes

- `BatchProviderSublimit` (`ProviderRequestGovernor.kt:707-750` region): `BATCH_REQUESTS_PER_MINUTE = 15` measured constant; rolling 60 s window; `minimumSpacingMs = 0` (no forced sleeps — test asserts `clock.delays` empty through admit AND defer); `maxInFlight = 1`; `tokensPerMinute = Int.MAX_VALUE` (RPM-only v1, DR-C deferred).
- Credential-wide model-agnostic key: `batchSublimitKey` drops `model`, keeps backend + credentialScope; test proves mixed-model sharing (8+7 across two models ⇒ 16th defers) and per-credential separation.
- All-or-nothing nesting: `BatchRequestSublimitGate.executeBatch` admits bucket 2, runs the block, `finally { sublimitGovernor.releaseAdmitted(...) }` — the outer permit is released BEFORE an inner defer propagates (the inner defer surfaces as `ProviderRequestPausedException` from `withTranslationRetry`/bucket 1, caught by the `finally` on the way out). Test 4 asserts no permit leak (gate still admits afterwards). `ProviderRequestPausedException : ProviderFailureException` (`:193-204`), so the executor's typed catch handles sublimit defers.
- **CRITICAL — release(usage=null) does NOT refund the window**: `release` (`:569-588`) only sets `reservation.actualTokens = null` (stays at estimate) and decrements `inFlight`; the `Reservation` object remains in `bucket.reservations` and `evaluate` counts `bucket.reservations.size` against `requestsPerMinute` (`:462`) until `prune` ages it out past `windowMs` (`:548-552`). The 15-RPM sublimit is therefore real, and the first test proves it end-to-end: 15 executed+released calls, 16th defers. A released-unexecuted admission burns one slot for ≤60 s — the conservative direction (undercounts capacity, can never over-admit), self-limiting because a sublimit defer is a typed PAUSE, not a spin.
- Interactive isolation: the ONLY `executeBatch` call site in main is `AnalysisChunkExecutor` (BACKGROUND); test 3 saturates bucket 2 and proves an INTERACTIVE request rides bucket 1 with zero waits. Bucket-1 (`SharedProviderRequestGovernor`) behavior untouched — the governor diff is purely additive (no deleted lines).

### E. D5 — real identity freeze: CONFORMANT

`BatchChapterTranslator.kt:656-684` (PROFILE_PIPELINE branch only): `providerKey = "<engine lowercased>:<model>"`; `credentialId = sha256Hex(secret).take(16)` — LM Studio uses the base URL, otherwise the per-engine API key; blank ⇒ `""`. The raw secret exists only as the local `credentialSecret` val consumed by the hash input; greps of the new code paths show no log statement touching it (executor logcat lines use `safeSummary`/ids only). `RunConfigSnapshot.credentialId` pre-exists with default `""` (`artifact/ChapterRunRecord.kt:55`) — the change is purely the population, schema-additive. The legacy/`LEGACY_SEQUENTIAL` branch and the OFF dispatch are untouched (diff touches only the PROFILE_PIPELINE arrow), so Manual/Auto and FF-01-OFF behavior are unaffected. D4 (wave-3 owed item) DISCHARGED.

### F. Read-only surfaces: VERIFIED

`git show --stat 66fda24`: exactly 16 files — 4 modified main (coordinator, governor, translator, store), 5 new main (analysis/×4, publication), 6 tests + 2 test edits. `ChapterArtifactStore.kt` diff is exactly the 4-line `analysisChunkSidecarName` delegate (helper target `ChapterArtifactLayout.analysisChunkFile` pre-exists at `:132`; `publishSidecarPointers` pre-exists at `:736`). Artifact DTOs, `StageFingerprints.kt`, `rendering/*`, `SequentialBatchCoordinator.kt`, `domain/*`, `ui/*`: zero diff.

### G. Never-rules: HOLD

Analysis chunk loop is strictly serial (`for` + `ensureActive` + `yield()`, no `async`/`launch` in the phase); no OCR and no detector calls in the phase (corpus is checkpoint reads); no inpaint call in the phase; no bitmaps across provider calls (evidence is block TEXT read from store sidecars; nothing bitmap-typed enters the runner seam); Manual/Auto untouched (FF-01 accessors and the legacy construction unchanged this commit; all new behavior sits behind the already-flagged PROFILE_PIPELINE branch).

### H. Tests: REAL (no assertion-free tests, no gate-bypassing fakes)

- Sublimit: drives the real `BatchRequestSublimitGate` + real `ProviderRequestGovernor` with a `FakeClock`; the 16th-defer, model-agnostic pool, credential separation, interactive isolation, no-sleep, and permit-release-on-inner-pause behaviors each individually asserted. The fake transport in `AnalysisChunkValidationTest` sits at the correct seam (`AnalysisTextTransport`) with the real gate + real governor around it — it does not bypass the sublimit.
- Validator/executor: full V1–V9 matrix (each rule individually fatal), taxonomy split, reissue identity (`calls[0] == calls[1]`), refusal no-retry, transport pause typing.
- Publication: real store over `@TempDir`; round-trip, ordinal rejection, invalid-never-persisted, stale-manifest authority.
- Coordinator: real store + real preflight lane; transitions, counters, checkpoint-reuse (zero re-OCR), persisted-prefix skip, MISSING_ONLY commit, refusal persistence. Missing: the cross-run corpus-drift-with-persisted-chunks case (F-W4-1).

---

## 3. Deviation rulings (report §6)

| # | Deviation | Ruling | Basis |
|---|---|---|---|
| 1 | Transport not wired; runner==null = typed CONFIGURATION skip | **RATIFIED** | Contract §8.2 assigns provider HTTP to the provider package; §2.1 gate implemented exactly (`:513-541`): `ANALYSIS_CHUNKS` state + `COUNTER_SKIPPED_NO_TRANSPORT` + `ANALYSIS_NO_TRANSPORT_REASON`, never garbage chunks; `OcrPreflightCoordinatorTest`/`OcrPreflightRejectedMidRunDurabilityTest` updated to pin it. |
| 2 | No durable corpus-manifest sidecar; recompute per entry | **RATIFIED** | ST-07 recommends persisting only the phase transition when the corpus is recomputable; equality gate against the preflight fingerprint (`:449-456`) keeps it honest. Residual cross-run chunk-list identity gap is F-W4-1 (chunk list, not corpus manifest). |
| 3 | Failure text via typed counters + outcome fields, not a record field | **RATIFIED** | schema-v1 `ChapterRunRecord` has no failure-text field; an additive one would need SC-14 evaluation. `ProviderFailure` + reason ride `BatchPass1Outcome`; `COUNTER_CHUNKS_FAILURES` is the durable marker and is naturally capped (one per pass, run pauses). |
| 4 | 4-line `ChapterArtifactStore.analysisChunkSidecarName` | **RATIFIED** | Verified: the diff is exactly the 4-line delegate; `ChapterArtifactLayout.analysisChunkFile` and `publishSidecarPointers` pre-exist; no DTO or store behavior changed. |

## 4. Risk rulings (report §7)

| Risk | Ruling |
|---|---|
| Shared governor/gate at production wiring | **RATIFIED and EXTENDED** — F-W4-2: the gate must be shared too, not only the governor; both belong in the provider-package kickoff as acceptance conditions. |
| RPM-only v1 (TPM deferred) | **RATIFIED** — bucket 1 still enforces its TPM window on every transport attempt (`withTranslationRetry` admits per attempt via `governor.executeValue`), so token bursts remain bounded exactly as before; DR-C values are a measurement decision. |
| MISSING_ONLY empty VALID chunks pending-not-canon in slice B | **RATIFIED and EXTENDED** — F-W4-3: the summary-only COMPLETE variant must be covered by the same reconcile rule; persist coverage kind or tighten COMPLETE. |

## 5. Verification run (independent)

- `./gradlew :app:compileStandardDebugKotlin :app:testStandardDebugUnitTest --tests "eu.kanade.translation.translator.*" --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.artifact.*"` with `JAVA_HOME=/c/Program Files/Android/Android Studio/jbr` → **BUILD SUCCESSFUL** (2m 55s, exit 0).
- JUnit XML tally over `app/build/test-results/testStandardDebugUnitTest/*.xml` (regex on the testsuite tag): **classes=76 tests=545 failures=0 errors=0 skipped=0** — matches the implementer's reported 545/0.
- `git status` clean at review time; HEAD = `66fda24`.

## 6. Owed-item status touched by this slice

- gap-3 (core-then-context request-builder pin + schemas-contract §1.3 convention note): pin **DONE** (`AnalysisRequestBuilder` fail-fast + `AnalysisRequestOrderTest`); the schemas-contract §1.3 note itself already exists (`contracts-schemas-fingerprints.md:117`) — item DISCHARGED.
- D4 (real engine/model identity before analysis fingerprinting freezes it): **DONE** this commit (dimension E). Note for slice B: `providerKey` uses `lmstudio` (enum name lowercased) where governor backends use `lm_studio` — fingerprint-internal only today, but align before any code compares the two spellings.
- Wave-2 F1 (decideResume wiring owed to the first COMPLETE-publishing stage): still OWED — correctly untouched here; this slice still publishes zero COMPLETE runs.
