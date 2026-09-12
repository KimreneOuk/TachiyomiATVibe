# T924 Stage 1 — review-fix implementation report (F-1/F-2/F-3)

Date: 2026-09-05 · Implementer role (`docs/roles/implementer.md`) · Scope: the three ACCEPT-WITH-FIXES findings from `s1-review.md`, nothing more.

Worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline` (HEAD `bc94045` + pre-existing uncommitted Stage-1 Phase-2 work preserved untouched). Nothing committed or staged, per instruction.

## F-1 — facade content-fingerprint pageKey pinned to the transaction pageKey

File: `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`

- `:1065` — checkpoint construction now passes the transaction `pageKey`: `ocrContentFingerprint = pageOcrContentFingerprint(pageKey, live, naturalPageIndex, sourceOrientation)`.
- `:1106-1129` — the private `pageOcrContentFingerprint` helper gained a `pageKey: String` first parameter; the builder call now uses `pageKey = pageKey` (`:1124`) instead of `page.sourceFileName.orEmpty()`. KDoc records the TX-03.1 canonicalizer-pin rationale (both sides of the adopt drift comparison share one pageKey source, matching `ChapterArtifactStore.committedOcrContentFingerprint`, `ChapterArtifactStore.kt:615-635`).

Behavior-preserving today (`sourceFileName == pageKey` at every live-page construction); removes the future spurious `"committed OCR content drift"` rejection mode. Production diff: 1 logic line + parameter/KDoc. No other production file touched.

## F-2 — gate 1.5 user-edit authority evidence

### 2a. `app/src/test/java/eu/kanade/translation/ChapterTranslationStorePersistenceTest.kt`

New test `user edited committed block survives checkpointOcr close and adopt with bundle identity intact` (`:145`):

- Harness mirrors the m1 idiom (`OcrCheckpointRestartReuseTest`): `ChapterTranslationStore.lazy` over a `FakeUniFile` TempDir, `preRegisterPages`, real PNG fixture + `ChapterTranslationStore.artifactImageProbe` header probe (idiom borrowed from `ChapterTranslationStoreArtifactMigrationTest`, production probe restored `@AfterEach`).
- Creates a display-ready committed bundle via the real guarded writer (`updatePageGuarded`) whose block carries `userEditedAt = 1_757_050_000_000` and translation `"user override"`.
- CLOSE form: BATCH lease → `mergeOcr` → `checkpointOcr` (CLOSE) — asserts the manifest `committed` pointer equals the pre-checkpoint pointer and the committed snapshot still carries the user edit.
- Adopt (TX-03.1) form: simulated restart via `ChapterTranslationStore.openArtifact`, then `checkpointOcr` with `expectedCandidateGenerationId = null` — asserts `Committed`, committed pointer identity, `candidate == null`, the checkpoint pointer installed, and `userEditedAt`/override text intact. The adopt-side drift comparison accepts the user-edited bundle because FP-02 excludes user edits.
- Existing test `failed persist remains dirty for a later retry` unchanged (`block()` helper gained defaulted `translation`/`userEditedAt` parameters; defaults preserve the old shape).

### 2b. `app/src/test/java/eu/kanade/translation/coexistence/D5GlossaryAwareReuseTest.kt`

- New test `glossary repair reuse stays authoritative after a manifest v3 rewrite cycle` (`:319`): scenario (b) repair pass (RUN on p0 via `stampBatchProvenance` + guarded commit, glossary version stays 1), then a literal manifest v3 rewrite cycle — `publishManifest` republication through the production primitive, reload through a fresh `ChapterArtifactStore` over the same document set, fresh `ChapterTranslationStore` over the reloaded manifest — and asserts glossary version 1 preserved, zero RUN decisions, p0 `REUSE`, p2 `TERMINAL_COMPLETE`.
- Fixture-only change: `artifactBackedStore` now keeps the `AtomicChapterDocuments` + `ChapterArtifactLayout` handles (`d5Documents`/`d5Layout`, `:93-104`) so the test can reload the durable bytes; store construction and all existing tests unchanged.
- New import: `io.kotest.matchers.nulls.shouldNotBeNull`.

## F-3 — SC-08 delimiter-forgery fixtures

File: `app/src/test/java/eu/kanade/translation/artifact/SemanticFingerprintTest.kt` (style mirrors `page ocr content resists delimiter forgery`, `:347`).

- `profile input resists delimiter forgery` (`:475`) — `profileInputFingerprint`: naive concatenation of `"en" + "-US"` collides with the fused `"en-US"` tag (asserted distinct); a delimiter-bearing field must not fuse the language pair (`"en" + "en-US"` vs `"enen" + "-US"` collide naively, asserted distinct).
- `translation provenance resists delimiter forgery` (`:597`) — `translationProvenanceFingerprint`: single-block id forgery of a two-block stream (`"p1_b1|1:p1_b2|"`) asserted distinct; contributing-page boundary forgery (`[b1,b2]+[b3]` vs `[b1]+[b2,b3]`, identical flattened block stream) asserted distinct.

## Verification

Environment: worktree `TachiyomiAT-t924-impl`, `JAVA_HOME=/c/Program Files/Android/Android Studio/jbr`.

1. `./gradlew :app:compileStandardDebugKotlin` — BUILD SUCCESSFUL in 40s (176 tasks).
2. `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.artifact.*" --tests "eu.kanade.translation.model.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.ChapterTranslationStore*" --tests "eu.kanade.translation.OcrCheckpointRestartReuseTest"` — BUILD SUCCESSFUL in 1m 28s. JUnit XML aggregate over `app/build/test-results/testStandardDebugUnitTest/`: **48 classes, 357 tests, 0 failures, 0 errors, 0 skipped** (353 pre-review + 4 new: 1 persistence, 1 D5, 2 fingerprint).

## Anomalies / notes

- First run had 1 failure in the new persistence test: my fixture committed the block with the default `"target"` translation while the assertion expected the `"user override"` the test stands for — fixture bug in the new test only, fixed by making the committed page block carry the override; no production code involved.
- `ChapterTranslationStore.kt` diff is 200 insertions relative to `bc94045` — that includes the pre-existing uncommitted Stage-1 Phase-2 work, which was preserved; my F-1 change is the `pageKey` parameter + call-site line + KDoc only.
- Only files touched in the impl worktree: `ChapterTranslationStore.kt` (F-1), `ChapterTranslationStorePersistenceTest.kt` (F-2), `D5GlossaryAwareReuseTest.kt` (F-2), `SemanticFingerprintTest.kt` (F-3). Nothing committed/staged; MAIN worktree untouched except this report.
