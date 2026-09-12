# T924 Wave 5 — Independent Review (S5 slice B: profile reconcile + freeze + zero-OCR frozen-profile reuse)

Reviewer: independent Reviewer (read-only; authored none of the reviewed diff)
Date: 2026-09-06
Worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`
Commit under review: `d08bfad` "feat(translation): T924-S5 slice B profile reconcile + freeze (ST-09/ST-10, TX-22) with zero-OCR frozen-profile reuse" (parent `f1cdde6`); 10 files, +2209/−29.
Controlling contracts: `stage0/contracts-state-transactions.md` (ST-04 :85, ST-05/OCR_PLAN skip rule :114, ST-09 :133, ST-10 :144, TX-07 :317, TX-22 :386, ST-30 :417), `stage0/contracts-schemas-fingerprints.md` (§1.4 :134, ProfilePointer :58, SC-03 :278, SC-09 :289, SC-10 :290, FP-04 :325, FP-05 :326, §7.1 :358), `evidence/wave4-review.md` (F-W4-1 prefix identity, F-W4-3 durable coverage).
Main-source paths relative to `app/src/main/java/eu/kanade/translation/`; tests to `app/src/test/java/eu/kanade/translation/`.

---

## Verdict

## ACCEPT-WITH-FIXES

D1–D4 conform to their contracts at the code level. Both CRITICAL-direction checks passed: the ST-05 reuse probe (a) runs BEFORE the OCR loop, (b) revalidates every durable checkpoint against the CURRENT per-page source sha (computed fresh from the chapter streams at batch start, `BatchChapterTranslator.kt:396-403`), and (c) returns before the runner seam is ever touched — the reuse tests prove zero OCR and zero chunk executions through recording fakes at the real seams. The FP-05 golden is genuinely self-verifying and the fixture bytes are encoder-reproduced verbatim. Independent verification run: BUILD SUCCESSFUL (2m 42s); JUnit XML tally **tests=578 failures=0 errors=0 skipped=0** — matches the implementer's reported 578/0 exactly.

Acceptance is conditioned on F-W5-1 landing before the analysis transport is wired (provider package): the reconciler's "an out-of-bound chunk record can never fail the freeze" invariant does not hold for ALIAS strings — `textList` in the slice-A validator performs no per-item length/blank check, so an overlong `titles`/`sourceNames` item persists into the chunk and deterministically kills the freeze on every later resume (typed PERSISTENCE_REJECTED loop). FF-01 OFF + no production transport mask it today, exactly like wave-4 F-W4-1 was masked. F-W5-2 is a LOW same-root note.

---

## 1. Findings

### F-W5-1 — MEDIUM (deterministic resume-hazard freeze-rejection loop; fix BEFORE transport wiring)

The reconciler's bound-safety claim (KDoc `ProfileReconciler.kt:63-65`, commit message "bound-safe demotion … never fail the freeze", report §2 D1) does not hold for alias lists. Chain of evidence:

- Slice-A validator: `AnalysisResponseValidator.kt:555-569` — `textList` returns raw JSON string items with NO length or blank check. `titles` is only size-capped (`:226`), `sourceNames` only non-empty-checked (`:215-217`). (Terms aliases ARE per-item capped at 120 — `:187` — the gap is entity-side only.)
- Chunk DTO: `AnalysisChunkResult.validationError()` (`AnalysisChunkResult.kt:98-143`) has no string-length bounds on entity/term fields — an overlong alias persists VALID.
- Reconciler: `boundedAliases` (`ProfileReconciler.kt:455-458`) filters blank + distinct + take(32) but does NOT length-filter; `boundedOrDemoted` (`:404-420`) demotes only oversized `source`/`target`, never aliases.
- Freeze: `ProfileFact.validationError()` (`ChapterTranslationProfile.kt:157`) rejects any alias > `MAX_NAME_CHARS` (128) → `ProfileFreezePublication.publish` Rejected → coordinator returns PERSISTENCE_REJECTED (`ChapterProfileBatchCoordinator.kt`, freeze-rejected branch).

Resume behavior: the reconcile is a pure function of durable chunks; the same overlong alias re-rejects on every subsequent run. No retry, no typed self-heal, chunk evidence durable but unusable — a permanent pause requiring manual chunk removal. Note also the NFC expansion wrinkle: the validator caps at 120 UTF-16 chars and `normalize()` (NFC, `:463-464`) can expand (e.g. U+0958 → 2 chars), so even a validator-clean 120-char `canonicalSourceName` path is only saved because source/target go through `boundedOrDemoted` — aliases do not.

Why MEDIUM and not HIGH today: FF-01 default OFF, no production transport (runner==null CONFIGURATION gate), so no production chunk is ever persisted in this slice; and the failure mode is fail-safe (typed rejection, prior manifest authoritative, nothing partial enters canon). The moment a transport wires, a single hostile/garbled model response can permanently wedge a chapter's profile stage.

Fix direction: in `boundedAliases`, drop or truncate items exceeding `ProfileFact.MAX_NAME_CHARS` (a dropped alias loses only a variant string, never a fact — note it: `"oversized alias dropped"`), mirroring `boundedOrDemoted`. Optionally also close the source gap: per-item `overlong` check for `sourceNames`/`titles` in the validator (schema-adjacent; coordinate with the AP-*/provider package). Pin with a reconciler test: a chunk whose entity carries a 200-char title reconciles to a valid profile (alias dropped), not a Rejected.

### F-W5-2 — LOW (masked §1.4 semantic breach: scene participant can resolve to a demoted non-entity fact)

`factIdsForIdentities` (`ProfileReconciler.kt:265-271`) is computed over the PRE-demotion `FactDraft.type`; `toFact` (`:422-424`) then demotes an oversized identity fact to `NARRATIVE_STATE`. A scene participant remapped through that map points at a fact whose type is no longer `ENTITY_IDENTITY`. `ChapterTranslationProfile.validationError()` (`:283-291`) only checks id membership (entities+terms), so the profile freezes, but §1.4 says participants "must resolve to entity facts". Requires a >128-char (post-NFC) entity name — pathological, masked, never freezes-invalid. Fix direction: compute the remap map from the POST-demotion facts (`entities.filter { it.type == FactType.ENTITY_IDENTITY }`), or drop participants whose target fact was demoted. Can ride the same commit as F-W5-1.

No other findings. The following adversarial checks were run and PASSED:

- **Reconciler determinism / total orders.** Entity drafts: one per distinct normalized source (map-keyed) plus relationship triples keyed by (type, source, target) — the (type.name, source, target) sort key is therefore already unique, no unstable-sort tie possible. Terms: source unique per group. Unresolved: variants differ in target by construction (variant map keyed by target); conflict-note drafts are unique by normalized note. Scenes: (firstPage, lastPage, chunkOrdinal, sceneIndex) — ordinal+index unique per draft. Linked-map first-seen order feeds only pre-sort grouping; every output list is sorted before id assignment (`:236-282`), so output is a pure function of the chunk list. Purity: no I/O, no clock, no store, no provider type in the file.
- **Conflict retention.** Disagreeing source forms emit one CONFLICTING unresolved fact per target variant (`:182-204`); canon receives nothing for the disputed key (pinned by tests, `ProfileReconcilerTest.kt:215-260`). Nothing averaged; notes stay WEAK/UNRESOLVED.
- **MISSING_ONLY.** `canonChunks = filter { coverage == COMPLETE }` (`:126`); pending counted, contributes no records/scenes/evidence/provenance; provenance falls back only within canon chunks (all-pending case pinned, `:196-208`). The wave-4 F-W4-3 extension (summary-only COMPLETE, zero records) is canon-neutral by construction — zero records contribute nothing.
- **Chapter-only scope.** Every fact is `CANONICAL_CHAPTER_WIDE` + `CHAPTER_ANALYSIS`; both candidate lists hard-coded empty (`:303-304`); pinned by test.
- **Validate gate.** `validationError() != null`, non-VALID status, or `chunkOrdinal != index` rejects; empty input rejects (`:103-124`). The coordinator feeds chunks re-read from the manifest in list order, and wave-4's publication enforces `ordinal == index`, so the gate is real, not decorative.
- **Freeze transaction.** Pre-write gates ordered validation → FP-05 recompute-compare → version monotonic vs the CURRENT manifest pointer (`ProfileFreezePublication.kt:51-68`), all before `publishSidecarPointers` (store `:736-761`: sidecar publish FIRST, one `publishManifestInternal` SECOND — SC-20/TX-22 by construction). Supersede = new content-addressed file + new pointer; prior bytes pinned untouched by test. Rejections never update `store.artifactManifest` (only the Committed branch does).
- **Reuse read.** All five gates present (`:130-161`): well-formed pointer (`ProfilePointer.isWellFormed`, manifest `:412-417`), valid sidecar at supported schema through the shared `readSidecarDocument` (future version → `UnsupportedVersion` preserved, never quarantined — store `:877-880`; corrupt → quarantined + absent), pointer↔DTO content+version+input identity, FP-05 recompute, FP-04 vs the current run. Anything else `NotReusable`. The corrupt-sidecar heal works because the probe's read QUARANTINES the corrupt primary (freeing the content-addressed name) and FP-05 excludes `version`, so the deterministic re-freeze re-occupies the same name — the ST-10 crash-(b) idempotence property.
- **FP-04 construction.** `profileInputFingerprintOf` (coordinator `:1084-1100`) uses the same `policyFingerprint("analysis-policy-v1", overlapPages)` helper and `AnalyzerProvenanceFactory.ANALYSIS_SCHEMA_VERSION/PROMPT_VERSION` constants as the analysis identity, provider/model split from the frozen `providerKey`, `credentialId` as opaque signature (blank → absent-null → `AUTHORITY_ABSENT` inside `StageFingerprints.profileInputFingerprint :281-306`), explicit null user/series authority. Freeze-time basis = the preflight corpus fingerprint (runtime cross-checked against the checkpoint-rebuilt corpus at `:502-509` every run); reuse-time basis = the probe's inline recomputation from the same checkpoint fingerprints revalidated against current source shas — equal by construction for well-formed page lists. A divergence could only cause a MISSED reuse (fail-safe), never a false reuse.
- **ST-04 revalidation.** `reusableCheckpointFingerprint` (`:1391-1402`) requires `checkpoint.sourceIdentity.sha256 == sourceShaByPageKey[pageKey]`, and that map is built at `BatchChapterTranslator.kt:396-403` from `computeSourceFingerprint(streamFn)` executed fresh per batch pass. One changed/missing/unhashable page returns null and kills reuse for the whole run (loop `?: return null`, `:1052`). `UNKNOWN_SOURCE_FINGERPRINT` fallback also fails the equality — correct direction.
- **No COMPLETE, decideResume untouched.** Every terminal in the slice is PAUSED (reuse, frozen, reconcile-rejected) or PERSISTENCE_REJECTED (freeze-rejected); zero COMPLETE publications. Hunk-level check of the coordinator diff: hunks at old lines 21/37/44/63/195/377/403/729/1097/1115/1162/1187 only — `decideResume` (old line 1210) untouched; the 15 deleted lines are exactly the slice-A stop terminal and the `ANALYSIS_STOP_REASON` constant. Wave-2 F1 remains owed, correctly.
- **No-work path.** `plan.chunks.isEmpty()` returns `ANALYSIS_NO_WORK_REASON` PAUSED before the freeze (`:554-577`) — a textless chapter freezes nothing, and the reuse probe short-circuits on `manifest.profile == null` BEFORE any checkpoint reads (`:1040-1042`), so textless runs pay nothing.
- **Read-only surfaces.** `git show d08bfad --stat`: exactly 10 files. `ChapterTranslationProfile.kt`, `ChapterArtifactManifest.kt`, `StageFingerprints.kt`, `AnalysisChunkResult.kt`, rendering/*, `SequentialBatchCoordinator.kt`, domain/*, ui/*, `ChapterTranslationStore.kt`, `BatchChapterTranslator.kt`: zero diff. `ChapterArtifactStore.kt` diff is exactly the 4-line `profileSidecarName` delegate; `ChapterArtifactLayout.profileFile` pre-exists at parent `:135` (delegation to the pre-existing `profiles/` convention confirmed). FF-01 default OFF and the single `profilePipelineDispatchKind` dispatch point are untouched (translator not in the diff).
- **Never-rules.** No provider-call surface anywhere in the three new/changed main files (reconciler is pure; publication is store I/O; the coordinator freeze path is store reads + pure reconcile + store transactions). No OCR/detector/inpaint invocation added; no bitmap types touched. The reuse path returns before `runAnalysisPhase`, where the runner is exclusively constructed (`:580`) — zero provider calls by control flow, proven by the recording fakes. Freeze touches only the profile sidecar, the manifest profile pointer, and run records — no page state, no display (TX-07 by construction).

---

## 2. Dimension verdicts

- **A. ProfileReconciler: CONFORMANT except F-W5-1/F-W5-2** (bound-safety holes in the alias/participant corners; purity, determinism, conflicts, exclusion, scope, gate, remap-drop all pass).
- **B. ProfileFreezePublication: CONFORMANT** (pre-write FP-05, version monotonic, one transaction, sidecar-first, supersede-by-new-file, five-gate reuse read, future-version preservation).
- **C. Coordinator: CONFORMANT** (probe placement/short-circuit/ST-04 revalidation/zero-OCR reuse/typed terminals/re-read durable chunk list/PRE-freeze reconcile pause/record ordering/FP-04 identity/decideResume byte-untouched/no-work unchanged).
- **D. Read-only surfaces: VERIFIED** (store diff exactly the flagged +4-line accessor).
- **E. Never-rules: HOLD.**
- **F. FP-05 golden: CONFORMANT** — self-verifying (`contentFingerprint` field == recomputed == golden literal, `ProfileContentFingerprintGoldenTest.kt:57-62`); canonical bytes proven by `decode → encode == fixture text` (`:44-54`); version-only bump ⇒ SAME (`:64-70`); operational-only ⇒ SAME (`:72-82`); hashed change ⇒ DIFFERENT (`:84-94`). Fixture is a fully populated, schema-valid profile (2 entities / 1 term / 1 scene / 1 unresolved, 64-hex fingerprints).
- **G. Tests: REAL.** Coordinator reuse test proves the skip through recording fakes at the real seams (`resumedWorker.ocrPages shouldBe emptyList()`, `resumedAnalyzer.executedOrdinals shouldBe emptyList()`); FP-04 test proves non-reuse AND that the normal path still runs (checkpoint reuse + prefix skip + re-freeze v2, `ChapterProfileFreezeCoordinatorTest.kt:322-360`); the corrupt-sidecar test is the ST-10 heal (unfrozen → re-freeze → valid bytes readable through the pointer). No assertion-free tests; the publication tests drive the real store over `FakeChapterDocumentIo` fault seams. Two hardening notes, not findings: the heal test does not explicitly pin that the post-heal `pointer.fileName` EQUALS the pre-corruption name (implied by content-addressing); the golden's "cross-constructor stability" is covered via the decode/re-encode identity plus the reconciler's constructor-path byte test rather than a direct constructor-vs-decoded hash comparison.

---

## 3. Deviation rulings (report §5)

| # | Deviation | Ruling | Basis |
|---|---|---|---|
| 1 | Chunk-level evidence attribution | **RATIFIED** | The chunk DTO genuinely has no per-record evidence links (`AnalysisChunkResult` fields verified); §1.4 requires refs on non-WEAK facts, which the chunk-level union satisfies. Per-record attribution is an AP-*/SC-* schema change. |
| 2 | No gender/range facts | **RATIFIED** | Nothing gender/range-scoped is persisted in the chunk record (the persisted subset has no such fields), so there is nothing to reconcile; emitting none is honest rather than lossy. Requires provider-contract work first. |
| 3 | No-work (textless) path unchanged | **RATIFIED** | ST-05 :114 groups the no-work skip with the reuse skip; freezing an empty profile for a textless chapter has no consumer in this slice (FINALIZE unreachable), and the FINALIZE skip-to-phase belongs to the completion stage. Behavior pinned unchanged. |
| 4 | Candidate lists always empty | **RATIFIED** | §6.4 forbids auto-promotion; §7.1 corrections require observing committed translations (Stage 6+). Empty-and-recorded beats populated-and-guessed. |
| 5 | Flagged `ChapterArtifactStore.profileSidecarName` | **RATIFIED** | Verified: exactly 4 added lines (KDoc + 3-line delegate); `ChapterArtifactLayout.profileFile` pre-exists (parent `:135`); no DTO or store behavior changed. Mirrors the wave-4-ratified `analysisChunkSidecarName` precedent. |
| 6 | `rel=` / `termKind=` notes | **RATIFIED** | §1.4 has no relationship-list or term-kind field; note-preservation beats silent dropping (the never-guess discipline applied to retention). Schema-visible only inside note text; no fingerprint ambiguity. |
| 7 | PERSISTENCE_REJECTED freeze-rejection terminal | **RATIFIED** | Consistent with the slice-A chunk-publication semantics: a guarded publication rejection means the store re-read decides; the PRE-publication reconcile rejection correctly stays PAUSED per ST-09. Prior manifest verifiably authoritative (test `rejection leaves the prior manifest authoritative`). |

Residual deviation-adjacent NOTE (not a ruling change): an all-MISSING_ONLY chunk set freezes a VALID EMPTY profile (pinned by `all pending chunks still reconcile to a valid empty canon` + the updated analysis-phase test). Contract-consistent (ST-09 entry is "chunks complete"; §1.4 empty lists are schema-valid; FP-04 identity makes later identical-input runs reuse the same empty canon — self-consistent), but it is a deliberate semantic the completion stage should be aware of when it wires skip-to-FINALIZE.

---

## 4. Verification run (independent)

- `cd TachiyomiAT-t924-impl && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileStandardDebugKotlin :app:testStandardDebugUnitTest --tests "eu.kanade.translation.translator.*" --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.artifact.*"` → **BUILD SUCCESSFUL** (2m 42s, exit 0).
- JUnit XML tally over `app/build/test-results/testStandardDebugUnitTest/*.xml`: **tests=578 failures=0 errors=0 skipped=0** — matches the implementer's reported 578/0 (baseline 548 + 30 new).
- `git status` clean at review time; HEAD = `d08bfad`.

---

## 5. Owed-item status touched by this slice

- Slice-B obligations from the wave-4 ledger (profile reconcile/freeze, coverage-aware reconcile, ST-10/TX-22, R035 skip rule): **LANDED** this commit, subject to F-W5-1 before transport wiring.
- Wave-2 F1 (`decideResume` production wiring): still OWED — correctly untouched (zero COMPLETE publications).
- Provider-package kickoff conditions carried: SHARED governor + gate instances (F-W4-2), `lmstudio` vs `lm_studio` spelling alignment, plus now F-W5-1's alias bound (validator-side cap and/or reconciler-side filter).
