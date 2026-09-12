# T924 Stage 1 — Exit Report

Date: 2026-09-05 · Status: **COMPLETE — all gates green, review ACCEPT-WITH-FIXES with fixes landed**

Branch: `t924/batch-profile-pipeline` (worktree `TachiyomiAT-t924-impl`), base `adbe643`, HEAD `1dfd7f5`. Working tree clean. Not merged to main; no pushes (per REPO_HEALTH rules).

## Commit series (one rollback point per step)

| Commit | Slice | Size |
|---|---|---|
| `732f7ff` | T924-R012 — forced-translation OCR reuse decoupled from inpaint readiness; evidence validated when supplied; non-force path byte-identical | 2 files, +469/−4 |
| `bc94045` | WP1 — seven versioned sidecar DTOs, manifest SCHEMA_VERSION 2→3 (reads v2, writes v3, future guard refuses >3 read-only), seven content-addressed retention-bounded sidecar dirs, generic sidecar-then-pointer publication (`publishSidecarPointers`/`publishActiveRun`), shared `ArtifactDocumentJson` | 16 files, +2027/−6 |
| `5f66174` | Semantic fingerprints — T924-FP-02..FP-08 builders + FP-01 exclusion rule by construction; all pre-existing fingerprint outputs byte-identical; FP-09 gate incl. golden + delimiter-forgery fixtures | 2 files, +1119 |
| `1dfd7f5` | `checkpointOcr` CAS transaction — T924-TX-01..TX-12 incl. TX-02.1 no-grace and TX-03.1 adopt-committed; CLOSE/REBASE/adopt branches; M1 milestone proof; review fixes F-1/F-2/F-3 | 6 files, +1766/−6 |

## Stage gates 1.1–1.7 — all PASS

(Oracle-name mapping per review F-4; substance verified in `s1-review.md`.)

| Gate | Evidence |
|---|---|
| 1.1 pre-change manifest loads under new code | `app/src/test/resources/t924/manifest-v2.json` fixture: loads cleanly, survives write cycle rewritten as v3 with old data intact (ChapterArtifactStoreTest) |
| 1.2 schema bounds + unknown-version behavior | `ChapterRunRecordSchemaTest` (7) + sealed `RunRecordRead`/`OcrCheckpointRead` + strictly-`>` future guard set (grep-verified, no equality checks) |
| 1.3 crash-safe publication | `SidecarCrashPublicationTest` (8) with FakeChapterDocumentIo fault injection; `CheckpointOcrTransactionTest` B-table fault rows (5) |
| 1.4 fingerprint determinism + delimiter resistance | `SemanticFingerprintTest` 21 tests: determinism per builder, FP-01 exclusion proofs, forgery fixtures for FP-02 + (F-3) FP-04/FP-06, golden fixtures (profile content + 200-page corpus), NFC/CR normalization |
| 1.5 user-edit authority | (F-2) `ChapterTranslationStorePersistenceTest`: user-edited committed block survives checkpointOcr CLOSE + adopt with bundle identity and `userEditedAt` intact; `D5GlossaryAwareReuseTest`: glossary-repair reuse authoritative after manifest v3 rewrite cycle |
| 1.6 checkpoint transaction incl. REBASE | `CheckpointOcrTransactionTest` (12): CLOSE single publication, REBASE close+successor fenced, TX-03.1 adopt/drift/candidate-present/no-bundle, identity fencing, crash rows |
| 1.7 M1 milestone — OCR → checkpoint → release ownership → process restart → Manual/Auto/Batch safe reuse | `OcrCheckpointRestartReuseTest` (3 `m1_` tests): double restart, reader-adhoc commit on checkpointed payload, Batch adopt-committed re-checkpoint, drift refusal, lease-less rejection |

## Final verification

- Orchestrator run (post-fixes, pre-commit): `:app:compileStandardDebugKotlin` + targeted suites — **48 classes, 357 tests, 0 failures, 0 errors, 0 skipped**.
- Reviewer independent rerun (pre-fixes): 48 classes, 353 tests, 0 failures; post-fix rerun by fix agent: 357/0.
- Scope audits: no feature flags touched (grep zero hits); `pipeline/**`, `translator/**`, `store/PageStageLeaseTable.kt` untouched; Manual/Auto non-force planning byte-identical (reviewer deletions audit).

## Review

`s1-review.md`: **ACCEPT-WITH-FIXES** → fixes F-1 (facade pageKey pin, `ChapterTranslationStore.kt:1124`), F-2 (gate-1.5 tests), F-3 (forgery fixtures) all landed in `1dfd7f5`/`5f66174`. All 15 implementer deviations ratified (WP1-1..7, FP I-1..I-3, TX D1..D3, gate naming). Director safety rules 1–7 all PASS.

## Deliberately deferred (not gaps)

1. R012 evidence-provider wiring into `SinglePageOnnxPhase` — S3 (preflight-reuse stage); interim is the mandated status/payload pass-through, ruled safe by review.
2. Planner-level checkpoint consumption (TX-10 reader extension) — S3; M1 proves store-level origin-neutral reuse only.
3. FP golden fixtures second-machine re-run — Stage-2 exit audit (single-machine today).
4. Retention policy for CANCELLED generation records (audit vs sweep) — ratified as sweep (= `cancelCandidate` semantics); revisit only if Director wants audit retention.
5. Hex-format locale pinning (`%02x` default-locale) — pre-existing behavior, shared with persisted fingerprints; separate L1 task if wanted.

## Stage-2/3/4 readiness

S1 substrate is complete for: S2 (persisted layout, FF-02 — uses `layoutPlans` pointers + `layoutCompatibilityFingerprint`), S3 (coordinator shell, FF-01 — uses `ChapterRunRecord`/`publishActiveRun`, checkpoint consumption, R012+R013 wiring), S4 (pure planners — `EnvelopePlan`/`AnalysisChunkResult`/`ProfilePointer` DTOs ready). All three can proceed in parallel per `implementation-sequence.md`.
