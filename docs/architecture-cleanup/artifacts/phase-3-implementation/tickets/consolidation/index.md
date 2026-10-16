---
title: "S6 - consolidated host and device disposition"
kind: ticket
status: 1
assignee: ""
comments: none
---

# Governing contract

[Execution](../..) and the [approved plan](../../../phase-3-planning/plan) govern this ticket. Follow the plan's mechanism, failure matrix and physical gates. Implementation and independent review use separate assigned GLM5.3Flash/max OpenCode agents; record actual runtime identity in each review. The [current execution policy](../../../execution-policy) overrides older role assignments. The coordinator owns full-suite gates; only one Gradle invocation may run machine-wide.

# Current execution status

App unit gates green: full Standard02 (2,309 cases), corrected focused Standard class1, and full Dev02 (2,309 cases). Each full suite has zero failures/errors and one existing benchmark skip. Production bytes are identical; only the render-permit fixture differs between full Standard02/source02 and final source03. Focused checks passed both flavors; scoped formatting changed no bytes. Paired APK assemblies are running. Remaining host gates: assemblies, lint, full format inventory, domain supplement and final evidence review. No commit/push or Phase 3 completion claim.

Device absent: D1 abandonment disposal, D2 retry serialization, D3 actual receipt/native-exit join and SAF stalls, D4 end-to-end marker interleavings remain unpassed. Host evidence does not establish real Bitmap/JNI behavior.

# Dependencies

S5 source review and focused dual-flavor gate accepted. Cumulative batch regression and final host acceptance are consolidated here, avoiding a separate batch-only run immediately before the full suite.

# Scope

Validate actual cumulative Phase 3 diff; full applicable assemblies and full app unit tests both flavors, relevant domain/boundary suites (fork/join overlap suites included), full Spotless inventory, source/test accounting, report integrity and independent evidence review. Do not add unrelated formatting/algorithm fixes.

# Verification and delivery

Retain literal task commands/exits, complete logs/rotation provenance, fresh XML immediately after each task, identity/count deltas and failure disposition. No unexplained new failure or retry-away. Record D1–D4 performed evidence or explicit outstanding gate; host green alone cannot close full Phase 3. Reviewed commits require applicable checks and Spotless. Current AGENTS.md forbids pushing.

Preserve existing user files, baseline failure evidence and protected report. Use byte-safe edits and fresh immutable attempt directories. Record results and exact meaningful manifest under this ticket. Any delegated evidence review reports to the coordinator with expectReply true. The coordinator handles staging and commits after review; no agent may push.

# Prepared source and test accounting

This is static preparation while S5's focused DevDebug gate runs, not S6 execution or acceptance. Base: `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec` (reviewed prerequisite closure).

| Inventory | Cumulative Phase 3 tree |
| --- | --- |
| Production Kotlin | 13 affected files: one added (`AttemptAuthority.kt`), 12 modified, none retired |
| Test Kotlin | 10 affected files: four added, six modified, none retired |
| Build metadata | One modified app build script; optional fixed timestamp for validation, ordinary build default preserved |
| Domain production / OCR-inpaint algorithms | No changed files |

Test declarations increased by 55: lazy-persistence 10→16 (+6), publisher 6→11 (+5), attempt authority +7, cleaned owner +12, timeout adapter +4, overlap boundary +21. The prior full app count was 2,254, so each full app flavor is expected to report **2,309**, including its existing deliberate benchmark skip. Actual fresh XML and method identities remain authoritative; no test was removed or quarantined by this phase.

The next formatter uses the existing Spotless extension with 13 exact changed Kotlin/KTS targets and an empty XML target. Full-format inventory still runs without that scope override during S6; unrelated baseline formatting is excluded from implementation changes. This avoids 13 separate IDE-hook Gradle launches or formatting unrelated files.

# Consolidation started

Independent review confirmed the executed S5 focus gate: Standard03 and Dev01 each report 69 tests with zero failures/errors/skips against the immutable tested 13-file source archive. No physical evidence is implied.

Formatting evidence: `s5-format-01` failed in init setup (37.16 seconds, actual/guard 1) because the init also ran for included `buildSrc`; no formatter source mutation occurred. The guarded `s5-format-02` succeeded (287.73 seconds, actual/guard 0, no breach/error). The late extension target override did not constrain the already-configured formatter task: scope inspection caught LiteRT whitespace and one blank line in `DeferredPagePublications`. LiteRT was restored byte-exact to its pre-run HEAD; only the related Phase 3 blank-line cleanup was retained. The six changed original targets contain formatting/unused-import removal plus the explicit two-line cleanup comment; coordinator read the entire non-whitespace diff. No algorithm, test assertion, fence, resource policy or API changed. No focused green rerun is needed for those mechanical edits.

Full app gates run one exact Test task per invocation, StandardDebug before DevDebug, no selectors. Each has 1,200-second setup and 900-second test-execution watchdogs, `--max-workers=2`, prescribed Kotlin setting and fixed validation timestamp. Both expected counts are 2,309; the existing synthetic benchmark remains deliberately skipped, with no new quarantine. This planned consolidation justifies the broader suites; it does not authorize repeated successful runs without a new reason. Full format/lint/build and domain checks follow separately under the same single-Gradle rule.

## First full-attempt source identity

These 14 dirty Kotlin/KTS files formed the first S6 full-attempt freeze (`s6-source-01.json` in the host validation root); the corrected 15-file freeze appears below. Committed Phase 3 files remain at HEAD `1c7df290`; no commit or push has occurred.

```json
{
  "app/build.gradle.kts": "8263256a8bb545273055414ec381697bfb5d3e59c6b24094bd2edf4ede742b71",
  "app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt": "22deed84d331c0048d6225b63a0249775ec3df704b1443cd6c6882b45f9f4b14",
  "app/src/main/java/eu/kanade/translation/pipeline/AttemptAuthority.kt": "fcd75d14df30c4d1a596320d7fdd1dd6523017fc2983990c3c5695084ae5bbd8",
  "app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt": "971e8dce98747eabc3ec5eae0b66682c9e9f2f01c8fa7187f6d2101740e94a76",
  "app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt": "67ccac5b793946d85a8816a196641f13cf6e0c29d0d965b4015953ecaab2e0ba",
  "app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt": "774613ce46e6600abec02f270dfb5e6b79d21f290bed7eb94e51cfc6d7e5e9fa",
  "app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt": "3bdfe6557f60eaab6b69399f18a65ad4873b27d5ed10ac79d4c91a6e268dd0c7",
  "app/src/main/java/eu/kanade/translation/pipeline/execution/PreparedPageBoundary.kt": "23d7fac5e0b49e70cd4643dc933706eda5cd9dab6622ae8f7e520b8b3ce8eab1",
  "app/src/test/java/eu/kanade/translation/coexistence/FakeEngines.kt": "47cdd9244e3b06c4b0f1b646db2d8f5a2a8b329b4bb493e0f8c4259562533bf0",
  "app/src/test/java/eu/kanade/translation/coexistence/TranslationCoexistenceHarness.kt": "58144b400d93eb901b62d538d76b2390072d6e3e2a0788617680126f189ddef2",
  "app/src/test/java/eu/kanade/translation/pipeline/SinglePageOverlapBoundaryTest.kt": "9c3817b79a72754c94cc57e1c05fce4544d56a0e7f4684a8f80a75be3046cabd",
  "app/src/test/java/eu/kanade/translation/pipeline/execution/PreparedPageRuntimeBoundaryTest.kt": "8b3ad23a8352f4d81cabf4ea9c17fad7d22f27570e109301fa586ae83f2087b5",
  "app/src/main/java/eu/kanade/translation/model/PageTranslationOwnership.kt": "e079b3e9499e12b7c1f9d5ef32aa03dc2b5d2450cb367ed45db07fc38f812bd9",
  "app/src/main/java/eu/kanade/translation/pipeline/DeferredPagePublications.kt": "a9952fc595b5aac92d8ef59b9809cb4c328ff9658f234f82c71c8080dc184482"
}
```

# First full StandardDebug result — correction required

`s6-full-standard-01`: exact `:app:testStandardDebugUnitTest`, no selectors, actual/guard exit 1, 896.53 seconds, no watchdog breach or monitor error. Fresh XML: **336 files, 2,309 cases, 2,303 passed, four failures, zero errors, two skips**. All 14 source hashes matched the prelaunch freeze; tested bytes are preserved in `s6-tested-source-01.zip`.

| Failure | Observed assertion / precondition |
| --- | --- |
| EngineEpochStopRaceTest — large grace | Translator closed while the provider gate still held its borrow |
| NextPageAdmittedDuringParkedPublicationTest | p0 never reached the mocked COMMIT barrier; timeout at the setup arrival, before p1 admission was exercised |
| P5HonestOutcomeTypingTest — condition A | Durable render READY resume was returned as `Failed(reason=native phase timed out)` |
| P5CopyAndAccessibilityTest — native timer | Expected native-timeout placeholder was not observed while the decode gate was held |

The deliberate synthetic benchmark skip remains. The shipped-dictionary asset policy test also skipped on this host; its prerequisite is being checked, not counted as passed or newly quarantined. Declared=2,309, new quarantine=0, XML=2,309.

The verified worker exited normally before a bounded diagnostic request could attach; identity revalidation refused the absent process. No dump or termination occurred, and no hang/timeout is claimed. Daemon and worker identities were captured by the running observer. DevDebug full validation remains held. Diagnose and correct the four failures together; use their affected classes on StandardDebug before returning to the final full pair. No repeated full suite without a correction or new discriminating reason.

# Four-failure correction pass (GLM implementation, 16 October 2026)

Causes proven against `s6-full-standard-01` XML + current source; one consolidated pass; no assertion weakened, no timer inflated, no fixture-precondition bypass.

| Failure | Proven cause | Correction |
| --- | --- | --- |
| EngineEpochStopRaceTest 1.4.1b | `beginTranslatorUse` existed only in the manual boundary (`runGrantedSinglePageBoundary`); the auto translate/render half (`translatePreparedPage`) never held the borrow, so `closeEngines` saw count 0 and closed the translator under the parked provider call (the passing 1.4.1a took the epoch-retry path, masking it). | `translatePreparedPage` now takes `beginTranslatorUse` after lease admission and ends it in a nested `finally` before `releaseReaderPageLease`. Large-grace close defers behind the borrow; drain-not-cancel, paid-call bounds and rebuilt-instance guarantees unchanged. |
| NextPageAdmittedDuringParkedPublicationTest | Raw timeout at the COMMIT arrival: the test stubbed `persistCleanedBitmap` (ten-arg), but the manual route publishes via `enrollCleanedPersistence` — the harness `installManualPublishShim` contract — so the obsolete seam was never invoked and the publication never parked. | Fixture-only retarget to the current seam: stub `enrollCleanedPersistence`, park at COMMIT BEFORE the attempt-fenced store write (real fenced commit preserved), same receipt/`nativeStageDone` contract as the shim. p1 native admission stays outside the parked publication; all existing assertions retained. |
| P5HonestOutcomeTypingTest condition A | `resumeInpaintAndRender(...); return null` is the resume SUCCESS shape: the drained tail committed OCR/inpaint/render READY, but `isPreparedPageTerminal` requires display shape (persisted block translations, absent on a resume fixture and not the native lane's product), so the manual null-check typed a successful resume as `Failed(native phase timed out)`. | The manual null-check now also honors the committed resume shape (OCR READY + render READY + inpaint not FAILED + translation READY/PARTIAL) before typing a timeout. Genuine timeouts still fail: the mid-flight marker writes OCR FAILED, which the shape excludes; stale/nonterminal/failed pages remain failed/rejected. No completion inferred from null alone. |
| P5CopyAndAccessibilityTest native timer | The native-lane timer marker wrote only `errorMessage`; the reader projection (and the test's mid-park signal) reads `translationError`, so no placeholder appeared while the decode was parked. | The manual boundary's native `onTimeout` marker now also writes the attempt-fenced `translationError` naming the ONNX/native timer, still guarded by terminality and still before the residual invocation's physical exit. No recycle/cancel of physical ownership; HTTP-timer typing untouched. |

Delta: `TranslationPipeline.kt` (3 production hunks) + `NextPageAdmittedDuringParkedPublicationTest.kt` (fixture seam retarget only). Test declarations unchanged: declared 2,309, quarantine 0. Affected-class StandardDebug rerun then final full Standard/Dev per coordinator. New SHA256: Pipeline `86513edc83bf4acee4c0b224fabff470b44a34a7855b071c11dc518772107522`, NextPage test `3ae67552e8d8dcb103a701638598adb4f8174ff709526ed75f9b1ce318aeffcd`; all other `s6-source-01.json` files byte-identical.


### Affected-class validation candidate

Root verified the Pipeline delta against the preserved executed-source ZIP; the other 13 frozen files remain unchanged. The publication fixture is the fifteenth frozen file. Independent review accepted without blocking findings (actual Antigravity/Gemini runtime, recorded in consolidated review §9).

Next check: `:app:testStandardDebugUnitTest` filtered to EngineEpochStopRaceTest (6), NextPageAdmittedDuringParkedPublicationTest (1), P5HonestOutcomeTypingTest (6), and P5CopyAndAccessibilityTest (15): declared=28, quarantined=0, XML=28. Full DevDebug remains held until corrected full StandardDebug passes.

[Dictionary prerequisite](validation-prerequisites): restored from the documented SHA256; StandardDebug execution passed, DevDebug pending.


### Affected-class result

`s6-affected-standard-01`: actual/guard exit 0, 723.62 seconds, no watchdog breach or monitor error. Fresh `xml/` only: four classes, 28 tests, zero failures/errors/skips; preserved `prior-results/` excluded. All 15 source hashes matched the reviewed freeze. Daemon and Test-worker identities captured. All four original failures passed this affected-class check; full-suite acceptance remains pending.

After execution, root replaced the native timer comment with one accurate line; no code change. Scoped `:app:spotlessApply` completed against the two corrected task inputs: `s6-format-01`, actual/guard exit 0, 89.27 seconds, no breach/monitor error, no formatter changes and no new changed paths. Full Standard02 passed; full Dev01 failed one render-permit fixture timeout, recorded below.


### Corrected full-suite source identity

`s6-source-02.json` and prelaunch ZIP preserve these 15 files for full Standard02 and subsequent DevDebug. The Pipeline delta from the 28-test freeze is comment-only. Declared full count remains 2,309; no new quarantine.

```json
{
  "app/build.gradle.kts": "8263256a8bb545273055414ec381697bfb5d3e59c6b24094bd2edf4ede742b71",
  "app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt": "22deed84d331c0048d6225b63a0249775ec3df704b1443cd6c6882b45f9f4b14",
  "app/src/main/java/eu/kanade/translation/pipeline/AttemptAuthority.kt": "fcd75d14df30c4d1a596320d7fdd1dd6523017fc2983990c3c5695084ae5bbd8",
  "app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt": "971e8dce98747eabc3ec5eae0b66682c9e9f2f01c8fa7187f6d2101740e94a76",
  "app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt": "67ccac5b793946d85a8816a196641f13cf6e0c29d0d965b4015953ecaab2e0ba",
  "app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt": "774613ce46e6600abec02f270dfb5e6b79d21f290bed7eb94e51cfc6d7e5e9fa",
  "app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt": "295a18eee567637c528e4bfe7aa181a9fc515285b9c7621ab2872f1afa069a4d",
  "app/src/main/java/eu/kanade/translation/pipeline/execution/PreparedPageBoundary.kt": "23d7fac5e0b49e70cd4643dc933706eda5cd9dab6622ae8f7e520b8b3ce8eab1",
  "app/src/test/java/eu/kanade/translation/coexistence/FakeEngines.kt": "47cdd9244e3b06c4b0f1b646db2d8f5a2a8b329b4bb493e0f8c4259562533bf0",
  "app/src/test/java/eu/kanade/translation/coexistence/TranslationCoexistenceHarness.kt": "58144b400d93eb901b62d538d76b2390072d6e3e2a0788617680126f189ddef2",
  "app/src/test/java/eu/kanade/translation/pipeline/SinglePageOverlapBoundaryTest.kt": "9c3817b79a72754c94cc57e1c05fce4544d56a0e7f4684a8f80a75be3046cabd",
  "app/src/test/java/eu/kanade/translation/pipeline/execution/PreparedPageRuntimeBoundaryTest.kt": "8b3ad23a8352f4d81cabf4ea9c17fad7d22f27570e109301fa586ae83f2087b5",
  "app/src/main/java/eu/kanade/translation/model/PageTranslationOwnership.kt": "e079b3e9499e12b7c1f9d5ef32aa03dc2b5d2450cb367ed45db07fc38f812bd9",
  "app/src/main/java/eu/kanade/translation/pipeline/DeferredPagePublications.kt": "a9952fc595b5aac92d8ef59b9809cb4c328ff9658f234f82c71c8080dc184482",
  "app/src/test/java/eu/kanade/translation/coexistence/NextPageAdmittedDuringParkedPublicationTest.kt": "3ae67552e8d8dcb103a701638598adb4f8174ff709526ed75f9b1ce318aeffcd"
}
```


### Corrected full StandardDebug result

`s6-full-standard-02`: exact `:app:testStandardDebugUnitTest`, actual/guard exit 0, 668.72 seconds, no watchdog breach or monitor error. Fresh `xml/`: 336 classes, declared=2,309, XML=2,309, 2,308 passed, zero failures/errors, one existing synthetic benchmark skip, no new quarantine. Dictionary policy class: 7 tests, zero failures/errors/skips; restored asset check executed. Daemon and Test-worker identities captured. All 15 source hashes matched `s6-source-02.json`; `s6-tested-source-02.zip` preserves the executed bytes.

Full `:app:testDevDebugUnitTest` (`s6-full-dev-01`) is running against the same freeze. Assemble, lint, full format inventory, domain supplement, and physical gates remain open. No complete Phase 3 claim.


### Remaining non-test gate batching

Run both explicit assembly tasks in one guarded invocation, then both explicit lint tasks in another (`--continue`, 1,800-second setup ceiling each). This reuses Gradle configuration without repeating successful tests. The first task labels the invocation tags; the actual command/log includes both tasks and per-task disposition must be recorded. Full StandardDebug and DevDebug Test tasks remain separate invocations. Full format inventory and domain supplement remain separate.


### Full Dev01 result and render-permit fixture ordering

`s6-full-dev-01`: actual/guard exit 1, 1,210.97 seconds, no watchdog breach or monitor error. Fresh XML: 336 classes, 2,309 cases, 2,307 passed, one failure, zero errors, one existing synthetic benchmark skip. All 15 hashes still matched source02; dictionary class executed without skips. The sole failure is `RenderPermitReacquisitionTest.kt:126-127`, waiting for render RUNNING.

The test acquires its render blocker at provider arrival, which may precede concurrent native inpainting admission. Holding that permit prevents inpainting and therefore the join/render transition being awaited. Standard02 happened to pass this ordering. Root added only a bounded store-state wait for inpaint READY before taking the blocker while the provider stays parked. This reuses existing flow/stage seams; production code, all assertions, both provider gates, timeout durations and test declarations are unchanged. No blind retry or quarantine.

New fixture SHA256: `110310d8b510102ae674bdcd049ff5a10739a3273887c725fa8208409b575a01`; source03 freezes the previous 15 unchanged files plus this sixteenth file. Independent GLM/OpenCode/max review §10 cleared the choreography and coverage disposition. Focused Dev and Standard class1 both passed; one-file scoped formatting passed without source mutation (90.25 seconds, actual/guard 0, no breach/monitor error). Corrected full Dev02 passed against unchanged source03 (16 files). Retain Standard02 full evidence for unchanged production and 2,308 other cases; distinguish the corrected focused Standard case rather than claiming source03 already ran full StandardDebug. Coverage disposition is included in independent review. Assemblies/lint remain unlaunched.


### Render-permit focused results

`s6-render-permit-dev-01`: actual/guard exit 0, 310.30 seconds, no watchdog breach or monitor error. Fresh XML: declared=1, XML=1, zero failures/errors/skips; all 16 source03 hashes unchanged. Production compilation reused. `s6-render-permit-standard-01` then passed: actual/guard exit 0, 272.02 seconds, fresh XML=1 with zero failures/errors/skips; all 16 hashes unchanged. Both daemon/worker identity observers completed successfully. A bounded source audit found only this fixture combining `BarrierPoint.PROVIDER_START`, `DevicePermitPriority`, and permit acquisition; no second same-pattern fixture was found.


### Corrected full DevDebug result

`s6-full-dev-02`: exact `:app:testDevDebugUnitTest`, actual/guard exit 0, 317.61 seconds, no watchdog breach or monitor error. Fresh XML: 336 classes, declared=2,309, XML=2,309, 2,308 passed, zero failures/errors, one existing benchmark skip. Render-permit case passed in 2.338 seconds; dictionary policy class passed 7/7. All 16 source03 hashes unchanged, exactly those 16 application files dirty and no untracked app Kotlin. `s6-tested-source-03.zip` preserves the executed source; daemon/worker identities captured.

Unit coverage: full Standard02 on source02 plus corrected focused Standard class1 on source03; full Dev02 on source03. Production identical across these versions; reviewer §10 accepted this coverage disposition. No claim that source03 had a full Standard run. No additional full Standard replay.

Current cumulative Phase 3 source inventory versus `fbbd19d1`: production Kotlin 13 (one added, 12 modified); test Kotlin 12 (four added, eight modified), none retired. The two additional S6 fixture modifications changed no test declarations; net +55 remains 2,309 cases.
