---
title: "S5 consolidated review - Gemini 3.8 Flash / Antigravity"
kind: review
comments: none
---

# S5 Consolidated Substantive Review

- **Actual reviewer runtime**: Gemini 3.8 Flash / Antigravity, agent `d6e13131-3e86-41f3-a912-85a77442b41c`, ambient, `full_access`; zero source authorship. The GLM display label was stale. Coordinator verified the actual configuration and restored OpenCode `zai-coding-plan:glm-5.3-flash` / max for future turns. This metadata correction does not rerun or reattribute the review.
- **Implementer**: `e8d65346`, root audit integration `24062d6a`.
- **Verdict**: **PRELAUNCH CLEARED for Focused-69 Standard & Dev runs**. All 12 frozen source files verified; genuine red-10 failure root causes validated and corrected; terminal handoff, ownership custody, lazy debt gating, and attempt fencing confirmed correct. Zero source edits made during this review.

---

## 1. Frozen 12-File Inventory Verification

All 12 files verified byte-identical against `validation/s5-consolidated-source-02.json`:

| Target Path | SHA-256 Digest | Status |
| :--- | :--- | :--- |
| `app/build.gradle.kts` | `8263256a8bb545273055414ec381697bfb5d3e59c6b24094bd2edf4ede742b71` | MATCH |
| `app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt` | `b14f458baf4522cb0caf41effba0af15fad165bb14844a166a9294aa3d4e573b` | MATCH |
| `app/src/main/java/eu/kanade/translation/pipeline/AttemptAuthority.kt` | `fcd75d14df30c4d1a596320d7fdd1dd6523017fc2983990c3c5695084ae5bbd8` | MATCH |
| `app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt` | `971e8dce98747eabc3ec5eae0b66682c9e9f2f01c8fa7187f6d2101740e94a76` | MATCH |
| `app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt` | `656a89934af803e31b1ac7115e676fd7087d51b4350b5ea77527fdc8caed99ea` | MATCH |
| `app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt` | `853fdb2f0facec08928450d02c9044f79dc5e1555efa47d35db7e9ae3d3b037a` | MATCH |
| `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt` | `85c8549a2ca9cfbee71c5940185c4a217ab1c2b2d4be65af84799563d73458d7` | MATCH |
| `app/src/main/java/eu/kanade/translation/pipeline/execution/PreparedPageBoundary.kt` | `d6c589e9defd01715c802ea17c09c8053265ed3de7bad993bbb323c27f55364c` | MATCH |
| `app/src/test/java/eu/kanade/translation/coexistence/FakeEngines.kt` | `47cdd9244e3b06c4b0f1b646db2d8f5a2a8b329b4bb493e0f8c4259562533bf0` | MATCH |
| `app/src/test/java/eu/kanade/translation/coexistence/TranslationCoexistenceHarness.kt` | `58144b400d93eb901b62d538d76b2390072d6e3e2a0788617680126f189ddef2` | MATCH |
| `app/src/test/java/eu/kanade/translation/pipeline/SinglePageOverlapBoundaryTest.kt` | `39f532806fcd0aae23158197fb108a4b2a84486c73a35cbad50562956cea1a07` | MATCH |
| `app/src/test/java/eu/kanade/translation/pipeline/execution/PreparedPageRuntimeBoundaryTest.kt` | `8b3ad23a8352f4d81cabf4ea9c17fad7d22f27570e109301fa586ae83f2087b5` | MATCH |

---

## 2. Red-10 Evidence Audit

Evidence examined from `validation/s5-red-10`:
- **Guard and Process Execution**: `result.json` confirms `actual_exit = 1`, `guard_exit = 1`, `breach = null`, `elapsed_seconds = 271.89s`. No harness breach, no watchdog timeout.
- **Compiler/Incremental Cache**: `app/build.gradle.kts` property `-PvalidationBuildTime=2026-10-16T06:46:17Z` confirmed main Kotlin/Java compilation UP-TO-DATE without unnecessary task cache-busting.
- **XML Evidence**: `TEST-eu.kanade.translation.pipeline.SinglePageOverlapBoundaryTest.xml` shows tests=3, failures=3, errors=0, skipped=0.
  1. `masked textless prepared page cleans without provider call and reaches durable terminal()`: failed at `SinglePageOverlapBoundaryTest.kt:232` (`AssertionError: Expected value to not be null, but was null`).
  2. `masked textless reader page cleans without provider call and reaches durable terminal()`: failed at `SinglePageOverlapBoundaryTest.kt:240` (`AssertionFailedError: expected:<READY> but was:<PENDING>`).
  3. `unmasked textless prepared page skips inpaint and reaches durable terminal()`: failed at `SinglePageOverlapBoundaryTest.kt:232` (`AssertionError: Expected value to not be null, but was null`).

All three failures were authentic reflections of missing production behaviors before the S5 patch:
- Inpainting was previously conditioned only on `page.blocks.isNotEmpty()`, leaving masked textless pages stuck at `inpaintStatus = PENDING`.
- Prepared boundary handoff previously lacked proper terminal classification and unmasked worker bypass, returning null instead of the terminal reference.

---

## 3. Substantive Implementation Audit

### 3.1 Prepared Nonterminal Handoff & Overbroad Gating Fix
- **Root Catch Verified**: Implementer draft had overbroad `.takeIf { it.isTerminal }` on `publishPreparedPageFromOcr`, which broke normal text-bearing pages (rendering them null rather than handing off for HTTP translation/rendering).
- **Corrected Formulation**: `.takeIf { !noEraseTextless || it.isTerminal }` in `TranslationPipeline.prepareSinglePage`.
  - Normal pages with text (`!noEraseTextless == true`): handoff cleanly as non-terminal prepared references to downstream HTTP phases.
  - Strict no-erase textless pages (`noEraseTextless == true`): must be `it.isTerminal == true`, preventing incomplete textless pages from masquerading as ready.

### 3.2 Terminal Predicate Narrowing & Lazy Debt Gating
- `PreparedPageBoundary.kt:isPreparedPageTerminal`:
  - Failed pages (`ocrStatus == FAILED || inpaintStatus == FAILED || translationStatus == FAILED || renderStatus == FAILED`) are strictly non-terminal (`return false`).
  - Terminal condition: `page.hasRenderedResult || (page.isTextlessTerminal && (page.inpaintMaskBoxes.isEmpty() || page.inpaintStatus == StageStatus.READY))`.
  - Masked textless explicitly requires completed inpainting (`inpaintStatus == READY`).
- Lazy Persistence Debt Gating:
  - `publishPreparedPageFromOcr` queries `store.mutex.withLock { store.isLazyGenerationCurrent(snapshot.generation) && !store.hasPendingLazyPersistenceLocked() }`.
  - A prepared page cannot report `isTerminal = true` if pending lazy writes remain uncommitted in the pipeline queue.

### 3.3 Masks-Only Native Cleaning & SoftNull Attempt Fencing
- `SinglePageOnnxPhase.kt:processSinglePage`:
  - Inline inpaint condition updated to `(pageTranslation.blocks.isNotEmpty() || pageTranslation.inpaintMaskBoxes.isNotEmpty())`. Masked textless pages execute inpainting inline without requiring dummy text blocks.
  - `finalizePostOcrStage` leaves `inpaintStatus = PENDING` for masked textless, while setting translation/render to `SKIPPED`.
- SoftNull Handling in `TranslationPipeline.kt:resolveNativeCompletion`:
  - When `nativeResult is NativeBranchOutcome.SoftNull && nativeDraft.blocks.isEmpty()`: marks `inpaintStatus = FAILED`, `inpaintError = "Masked textless inpainting produced no cleaned image"`, attempt-fenced via `attempt.write`.
  - Returns `TranslationCompletionOutcome.Failed(message)` with zero HTTP/provider calls.

### 3.4 Zero Paid Calls & HTTP Outside Permit
- `SinglePageHttpRenderPhase.kt:translateSinglePageHttpOnly`:
  - Translation admission and execution guarded by `if (pageTranslation.blocks.isNotEmpty())`.
  - Textless pages (masked and unmasked) bypass HTTP translation entirely: 0 provider calls, 0 token consumption.
- `renderSinglePageTranslated`:
  - Rendering canvas pass guarded by `if (pageTranslation.blocks.isNotEmpty() && ...)`.
  - Device render permit acquired outside native ONNX lane.

### 3.5 Ownership Custody & Store Identity
- `CleanedOwner`: Single-slot ownership, receipt-gated asynchronous release, borrow counting. Double-production and slot collisions fail fast.
- `AttemptAuthority`: Mutation fenced against current page precondition; attempts cannot overwrite foreign winners.
- `ChapterTranslationStore.kt`: Artifact lease explicitly checks `belongsTo(this, pageKey)`, preventing capability transfer between different stores with equal scalar identities.
- Extracted `mergeOcrFields` helper deduplicates merge logic between fenced and attempt-based writes.

### 3.6 Test Fixture Parity
- `PreparedPageRuntimeBoundaryTest.kt`: Migrated legacy test fixtures from `inpaintStatus = TEXTLESS` to canonical `finalizePostOcrStage(textless, inpaintAlreadyRan = false)`.
- `FakeEngines.kt`: `FakeRecognitionEngine.inpaintOutcome` now cleanly distinguishes between explicit null override return and default stub bitmap fallback.

---

## 4. Focused Launchers & Validation Scripts

Launchers inspected:
- `planned-s5-focused-standard-01.sh`
- `planned-s5-focused-dev-01.sh`
- Observers: `observe-s5-focused-standard-01.py`, `observe-s5-focused-dev-01.py`

Checks performed:
- Bash syntax (`bash -n`) passes with exit 0.
- Python syntax (`py_compile`) passes with exit 0.
- Line endings and selector joins verified (actual shell newlines, 6 test class selectors matching 69 tests).
- Guard parameters: setup timeout 1200s, test timeout 300s, max workers 2, stable validation build time `-PvalidationBuildTime=2026-10-16T06:46:17Z`.
- Test counts: 21 + 7 + 12 + 16 + 9 + 4 = 69 total tests across the 6 selected classes.

---

## 5. Actionable Findings & Recommendations

- **No Blocking Defects Found**: The 12 consolidated source files are structurally, semantically, and syntactically sound.
- **Severity**: None (Clear to Proceed).
- **Corrective Direction**: No further source edits needed prior to test execution.
- **Next Step**: Proceed directly to running focused test suites:
  1. `planned-s5-focused-standard-01.sh` (69 tests)
  2. `planned-s5-focused-dev-01.sh` (69 tests)

---

## 6. Post-FocusedStandard01 Corrections — Actual Runtime (GLM 5.3 Flash / OpenCode)

- **Runtime attribution for this section only**: OpenCode `zai-coding-plan:glm-5.3-flash` / max, ambient, `full_access`, agent `d6e13131-3e86-41f3-a912-85a77442b41c`, verified via `traycer_get_self` before review. Gemini attribution above (title, runtime bullet, §1–§5) preserved unchanged.
- **Evidence reviewed**: `validation/s5-focused-standard-01` (XML: 69 tests, 6 failures, 0 errors, 0 skipped; actual 1 / guard 1; 1076.39s; no breach) + freeze `validation/s5-post-focused-fixes-01.json` (13 files).
- **Freeze verification**: 13/13 hashes match. Delta vs cleared 12-tree (`s5-consolidated-source-02.json`) confined to exactly 3 production files — `ChapterTranslationStore.kt` `b14f458b…`→`7cf3f84b…`, `TranslationPipeline.kt` `85c8549a…`→`e56308f6…`, and `PageTranslationOwnership.kt` (new 13th inventory entry; pre-fix state byte-equal to HEAD blob `33dc6e2b…`, current delta exactly 1 line). Other 10 files byte-identical to cleared tree. Both test files untouched — no assertion weakening; 69 count unchanged; no new helpers/APIs.
- **Fix 1 — `mergeOcrFields` (Store:5177-5181)**: narrow guard `result.ocrStatus == READY && result.blocks.isEmpty() && result.translationStatus == SKIPPED` → `updated.translationStatus = SKIPPED`. OCR merge previously dropped the canonical textless SKIPPED. Non-empty blocks never touch `translationStatus` — concurrent HTTP status preserved. Single caller: OCR patch path (Store:2546).
- **Fix 2 — 5 `Failed` sites (Pipeline:734, 777, 975, 988, 1001)**: all now named `anchorPageKey = pageKey, reason = …` (httpError / `REASON_HTTP_RENDER_TIMER_EXPIRED` / "native phase timed out" / SoftNull message / nativeError). Ctor (`TranslationExecutor:181-187`) declares `anchorPageKey: String? = null` first — prior positional `Failed(message)` bound the message into `anchorPageKey` and left the default reason, so failure cause was lost and the wrong page fenced terminal. `terminalPageKeys` derivation unchanged.
- **Fix 3 — `detachedCopy` (PageTranslationOwnership:16)**: adds `detached.setErrorMessageRaw(errorMessage)`. `errorMessageValue` is a private non-constructor var, so data-class `copy()` dropped it. Existing internal raw setter (`PageTranslation:116-118`) carries the scalar without the routing setter's stage-error reroute/clear side effects. Reuse satisfied; no new helper.
- **XML↔fix contract mapping**: 4 textless durable-terminal failures ↔ Fix 1; soft-null `SinglePageOutcome.Failed` reason data-class diff ↔ Fix 2 (expected string "Masked textless inpainting produced no cleaned image" matches Pipeline:979 exactly); "authority accepts one terminal marker and rejects the next" expecting "native timeout" but reading null ↔ Fix 3 via the `mergeOcrFields` `detachedCopy` path.
- **Bounded caveat**: `detachedCopy` now carries `errorMessage` for all `PageTranslation.detachedCopy()` callers; block fingerprinting/equality unaffected (block-level fingerprints only). Covered by existing focused runtime-boundary coverage on rerun — no new mirror test added.
- **Disposition**: no blocking findings; reviewer made zero source edits. Root owns FocusedStandard-69 rerun; Dev-69 only after green.

---

## 7. Post-Standard02 Single-Condition Fix — GLM 5.3 Flash / OpenCode

- **Runtime**: get_self verified OpenCode `zai-coding-plan:glm-5.3-flash` / max before review. Bounded scope per user efficiency rule: 2-line delta only, settled sections not reopened.
- **Evidence**: `validation/s5-focused-standard-02` — XML 69 tests / 1 failure / 0 errors / 0 skipped; actual 1 / guard 1; 844.91s; no breach. Sole failure: `expected:<TEXTLESS_COMPLETE> but was:<CANDIDATE_RUNNING>` (overlap test). Freeze `validation/s5-post-focused-fixes-02.json` 13/13 exact; only Store changed `7cf3f84b…`→`9b74f64d…`, other 12 byte-identical to §6 state.
- **Delta**: Store:4776-77 only — comment + `if (updated.hasRenderedResult || updated.isTextlessTerminal) return true`. Independent inverse proof reproduced byte-exact (revert 2 lines rebuilds `7cf3f84b…`; old text recovered from HEAD). No new helper: `isTextlessTerminal` is existing model property (PageTranslationState:177-182), import pre-existing (Store:24).
- **Root cause verified**: textless canonical terminal has no rendered image/blocks/cleaned name/failure → old `shouldPersistUpdate` returned false at 4790 → lazy gate (Store:3790, trap documented 3785-89) suppressed the durable mutation once manifest knew the page → manifest stuck `CANDIDATE_RUNNING`. Fix makes canonical textless terminal durable.
- **Safety checks**: stranded-sweep placeholder (CANCELLED + errorMessage) lacks SKIPPED → `isTextlessTerminal` false → still in-memory only; FAILED pages still persist via fix-4 branch (4784). Bridge:4095 already used identical predicate (`immediatelyPromotable`). Only 2 callers of `shouldPersistUpdate` (3749 sync, 3790 lazy). No test/fence/receipt weakening — test hashes unchanged.
- **Disposition**: no blocking findings; zero source edits by reviewer. Root owns Standard03 same-69 final gate; Dev-69 only after green.

---

## 8. S5 Executed-Evidence Final Disposition — GLM 5.3 Flash / OpenCode

- **Runtime**: get_self verified OpenCode `zai-coding-plan:glm-5.3-flash` / max. Bounded scope: executed evidence only; source already reviewed §§1–7.
- **Standard03** (`validation/s5-focused-standard-03`): `actual_exit=0`, `guard_exit=0`, 514.56s, no breach / monitor error. XML: 6 classes, 16+7+12+9+21+4 = 69 tests, 0 failures / 0 errors / 0 skipped. Task identity: `:app:testStandardDebugUnitTest` (command.log). Daemon + Test-worker identities captured (`live-owned-identities.json`).
- **Dev01** (`validation/s5-focused-dev-01`): `actual_exit=0`, `guard_exit=0`, 925.0s, no breach / monitor error. XML: same 6 classes, 69 tests, 0/0/0. Task identity: `:app:testDevDebugUnitTest`. Identities captured.
- **Tested-source proof**: `validation/s5-tested-source-01.zip` — 13/13 entries byte-exact against `s5-post-focused-fixes-02.json` (independently re-hashed from archive). Current disk may mechanically differ after root's upcoming `:app:spotlessApply`; executed-source proof rests on this ZIP + freeze, not the post-format tree.
- **Verdict**: **S5 focus gate GREEN**. Source reviewed and hash-frozen; both focused Debug suites fully green with correct task identities and clean guard/monitor records.
- **Limitations**: D1–D4 unavailable/unpassed — no physical-device validation; no full Phase-3 completion or device approval claimed. Full existing batch suite consolidated into S6 (no separate batch green required). Formatter tree not yet executed — no formatting-run claims made.
- **Next (root-owned)**: scoped `:app:spotlessApply` on the 13 Kotlin/KTS files (comment trim only), then S6 full suites / batch coverage and full lint/build/format inventory. Reviewer made zero source/Gradle edits.


---

## 9. S6 Consolidated Four-Failure Candidate Review — Antigravity / Gemini 3.8 Flash

- **Runtime attribution for this section**: Antigravity `gemini-3.8-flash` / high, ambient, `full_access`, agent `d6e13131-3e86-41f3-a912-85a77442b41c`, branch `architecture-cleanup`, verified via `traycer_get_self` before review. Prior attributions (§1–§5 Gemini prelaunch, §§6–§8 GLM 5.3 Flash / OpenCode) preserved intact.
- **Baseline evidence**: `validation/s6-full-standard-01` — XML 2309 tests, 4 failures, 0 errors, 2 skipped across entire suite.
- **Freeze & delta verification**:
  - Baseline archive: `validation/s6-tested-source-01.zip` (14 files).
  - Candidate freeze: `validation/s6-four-failure-source-01.json` (15 files) and `s6-four-failure-source-01.zip` verified 15/15 byte-exact against candidate disk state.
  - 13 source files identical to `s6-tested-source-01.zip`.
  - Delta strictly confined to:
    1. `TranslationPipeline.kt`: `3bdfe655…` to `86513edc83bf4acee4c0b224fabff470b44a34a7855b071c11dc518772107522` (3 localized fixes, 29 lines changed).
    2. `NextPageAdmittedDuringParkedPublicationTest.kt`: new 15th inventory entry `3ae67552e8d8dcb103a701638598adb4f8174ff709526ed75f9b1ce318aeffcd` (fixture retargeting).
- **Substantive Fix Analysis (4 failures to 4 fixes)**:
  1. **Prepared translator borrow physical exit/teardown (`TranslationPipeline.kt:1435, 1592-1596`)**:
     - `engines.beginTranslatorUse()` invoked immediately after reader page lease acquisition (`ROLLING_AUTO_TRANSLATION`), wrapped in outer `try` with nested `finally { try { engines.endTranslatorUse() } finally { releaseReaderPageLease(...) } }`.
     - Non-suspending atomic borrow counter, safe nested `finally` guarantees lease release on any exit/error.
     - Extends physical borrow visibility over provider translation/rendering so engine close drain waits for active prepared page translation. Resolves `EngineEpochStopRaceTest`.
  2. **Retargeted enroll fixture real fenced write/receipt/barrier (`NextPageAdmittedDuringParkedPublicationTest.kt:74-108`)**:
     - Migrated legacy `persistCleanedBitmap` mock to production `enrollCleanedPersistence`.
     - Arrives at `CoexistenceBarrier.BarrierPoint.COMMIT` before attempt write.
     - Performs real fenced write via `attempt.write("publish cleaned image (D11 stub)")`, registers receipt via `arg<CleanedOwner>(6).registerReceipt(CompletableDeferred(accepted))`, and signals `nativeStageDone[pageKey]`.
     - Retains real attempt-fenced store write and barrier contract; eliminates 60s timeout. Resolves `NextPageAdmittedDuringParkedPublicationTest`.
  3. **Resume-null committed success shape (`TranslationPipeline.kt:653-664`)**:
     - `onnxResult == null` now evaluates `resumeRenderCommitted`: `ocrStatus == READY && renderStatus == READY && inpaintStatus != FAILED && (translationStatus == READY || PARTIAL)`.
     - *Root inspection note audit*:
       - `inpaintStatus != FAILED` check: verified that in valid production paths `renderStatus` cannot reach `READY` while inpainting is `PENDING`, `RUNNING`, or `CANCELLED` (inpaint must precede render and succeed or be SKIPPED).
       - Store vs attempt currency: `attempt.write("resumed render ready...")` commits to store during `resumeInpaintAndRender`, followed immediately by `drainDeferredPublications(deferredPublications)`, so `store.state.value[pageKey]` is authoritative and current under the auto lease.
       - Overload/view semantics: test fixture `P5HonestOutcomeTypingTest` defines test-local `fun hasRenderedResult() = renderStatus == READY`, whereas model property `hasRenderedResult` delegates to `isTranslationDisplayReady`, which requires non-blank translated text (`blocks.any { it.translation.isNotBlank() }`). Since `FakeCoexistence.textBlock` supplies blank translation, property evaluated to false while test method evaluated to true. `resumeRenderCommitted` catches the committed terminal render and prevents false timeout.
       - *Non-blocking note*: tightening `inpaintStatus != FAILED` to `inpaintStatus in setOf(READY, SKIPPED)` is structurally cleaner, but current formulation is functionally safe.
       - Resolves `P5HonestOutcomeTypingTest`.
  4. **Attempt-fenced native `translationError` mid-quarantine (`TranslationPipeline.kt:574`)**:
     - `withNativeLane.onTimeout` now sets `translationError = PageStoreWriter.timeoutFailureMessage(nativeTimer = true)` inside `attempt.markTimeout` alongside `errorMessage` when `!isPreparedPageTerminal(this)`.
     - Fenced under active attempt; provides deterministic mid-park error placeholder for reader projection before residual invocation exit. Resolves `P5CopyAndAccessibilityTest`.
- **Selector scope**: 28 tests across 4 classes (`EngineEpochStopRaceTest`: 6, `NextPageAdmittedDuringParkedPublicationTest`: 1, `P5HonestOutcomeTypingTest`: 6, `P5CopyAndAccessibilityTest`: 15). Launcher `planned-s6-affected-standard-01.sh` inspected and validated.
- **Actionable Verdict**: **NO BLOCKING FINDINGS. StandardDebug clearance granted for the 28 affected tests.**
- **Limitations**: D1–D4 device testing unpassed / unavailable; no device execution or hardware claims; reviewer made zero source or Gradle edits.
- **Next step**: Root runs `planned-s6-affected-standard-01.sh` (28 tests) under guard to prove the 4 targeted fixes green on StandardDebug.

## Section 10 — S6 Render-Permit Fixture Correction Review (GLM, bounded)

- **Reviewer runtime (verified via get_self)**: OpenCode \zai-coding-plan:glm-5.3-flash\ / max / ambient / full_access — actual GLM run. Prior §9 remains attributed to its actual Gemini/Antigravity turn.
- **Scope**: bounded review of root-authored 4-line fixture correction to \RenderPermitReacquisitionTest.kt\ after \s6-full-dev-01\ sole failure (wait for render RUNNING timed out, system-out showed only provider signalStart). No production review loop.
- **Freeze**: \alidation/s6-source-03.json\ + \s6-source-03.zip\ verified 16/16 byte-exact against disk. \RenderPermitReacquisitionTest.kt\ = \110310d8b510102ae674bdcd049ff5a10739a3273887c725fa8208409b575a01\ (new 16th entry); all 15 overlapping entries byte-identical to \s6-source-02.json\ — production untouched by this correction.
- **Delta proof (mechanical)**: proof \s6-render-permit-fix-01/\ before.kt (170 lines) + the 4-line insert at lines 118-121 (1 comment + \withTimeout(AWAIT_TIMEOUT_MS) { store.state.first { it["p0"]?.inpaintStatus == READY } }\) = after.kt (174 lines); after == disk byte-equal; inverse reconstruction of before exact. No other text changed: both provider gates, all assertions, timeout durations, single test declaration preserved.
- **Diagnosis verified in actual source**:
  - Fork/join: \TranslationPipeline.kt:703-746\ — segment-2 native inpaint (\unNativeInpaintBranch\) runs \sync\ concurrent with the provider HTTP call; PROVIDER_START parks the provider while the native branch proceeds.
  - Device permit consumers: native lane acquire inside \withNativeLane\ (\TranslationPipeline.kt:286\); render re-acquisition at \SinglePageHttpRenderPhase.kt:110\ (the boundary under test).
  - Old race: test acquired the permit at PROVIDER_START arrival, before segment-2 could acquire; held blocker prevented inpaint/permit admission, join could not finish, render RUNNING never published — test timed out waiting. Diagnosis sound.
  - New ordering: test awaits \store.state\ inpaint READY while the provider remains parked, then acquires the blocker permit — render is then the only remaining permit consumer, so the post-provider render boundary is exercised deterministically.
  - Publication reachability: \nrollCleanedPersistence\ (\CleanedPublication.kt:261-286\) writes \inpaintStatus = StageStatus.READY\ to the live store synchronously via \ttempt.write("publish cleaned image live")\ before the lazy persistence deferral; enrollment runs after \withNativeLane\ returns (\TranslationPipeline.kt:919-930\), so READY is observable while the provider is parked.
- **False-proof risk**: none found. \preRegisterInStore = false\; on this path only enrollment writes inpaint READY, attempt-fenced — READY genuinely means segment-2 inpaint produced and published.
- **Reuse rule**: exact awaiting idiom already exists (\CancelledBatchRestartReuseTest.kt:92-96\; same test file lines 130-132 for renderStatus); \kotlinx.coroutines.flow.first\ import pre-existing; no new helper/API/production/timer/test-count change. Comment 1 line, code-only.
- **Validation disposition**: approved. Smallest-class (1 case) DevDebug then StandardDebug run of the corrected test; repeat failed fullDev02 once corrected. Reuse of green fullStandard02 as proof for unchanged production and the 2308 unchanged test cases plus a focused corrected Standard case is sound — production is byte-identical between the fullStandard02 source and source-03. Record exact evidence versions; do not claim the source-03 tree itself had a fullStandard run.
- **Actionable Verdict**: **NO BLOCKING FINDINGS. Fixture correction cleared for proposed validation sequence.**
- **Limitations**: D1-D4 device testing unpassed / unavailable; no device or hardware claims; reviewer made zero source or Gradle edits and created no agents.

## Section 11 — S6 Unit-Gate Executed-Evidence Disposition (GLM, bounded)

- **Reviewer runtime (verified via get_self)**: OpenCode \zai-coding-plan:glm-5.3-flash\ / max / ambient / full_access — actual GLM run.
- **Evidence reviewed (fresh \xml/\ only, \prior-results/\ excluded)**:
  - \s6-full-standard-02\: actual 0 / guard 0 / 668.72s, no breach, no monitor error; fresh XML 336 files, 2309 tests / 0 failures / 0 errors / 1 skip; task \:app:testStandardDebugUnitTest\.
  - \s6-render-permit-dev-01\: actual 0 / 310.30s; fresh XML 1 test, 0/0/0; task \:app:testDevDebugUnitTest --tests RenderPermitReacquisitionTest\ (smallest class, corrected fixture).
  - \s6-render-permit-standard-01\: actual 0 / 272.02s; fresh XML 1 test, 0/0/0; task \:app:testStandardDebugUnitTest\ same selector.
  - \s6-format-02\: actual 0 / guard 0 / 90.25s; task \:app:spotlessApply\ with scoped \s6-scoped-format-02.init.gradle\.
  - \s6-full-dev-02\: actual 0 / guard 0 / 317.61s, no breach, no monitor error; fresh XML 336 files, 2309 / 0 / 0 / 1; \RenderPermitReacquisitionTest\ passed 2.338s; task \:app:testDevDebugUnitTest\.
- **Source versions**: disk verified 16/16 byte-exact against \s6-source-03.json\ post-format; \s6-tested-source-03.zip\ byte-equal to prelaunch \s6-source-03.zip\; \s6-tested-source-02.zip\ preserved byte-equal to \s6-source-02.zip\. All runs share pinned \-PvalidationBuildTime=2026-10-16T06:46:17Z\; daemon markers and identity observers captured (client/daemon roles recorded).
- **Git inventory (vs baseline \bbd19d\, app only)**: production 1 added (\AttemptAuthority.kt\) + 12 modified = 13; tests 4 added + 8 modified = 12; 0 untracked app files; no other statuses. No new quarantine (0 failures/errors across all fresh runs).
- **Coverage disposition applied (per §10)**: fullStandard02 (source-02 tree) accepted as production + 2308-unchanged-test proof; corrected RenderPermit proven on source-03 via focused DevDebug + StandardDebug runs; fullDev02 (source-03) green is the repeat of failed fullDev01. Production is byte-identical between source-02 and source-03 (only the fixture changed), so the composition is sound. The source-03 tree itself has NOT had a fullStandard run — recorded, not claimed.
- **Skip identity**: sole skip in Standard02/Dev02 is \SyntheticJournalReplayBenchTest\ (existing benchmark skip). Observation: fullStandard01 also skipped \PaddleOcrDynamicPageBatchPolicyTest\ (skip=2); that skip no longer occurs — a disappearance, not a new gate.
- **Actionable Verdict**: **S6 UNIT GATE GREEN.** Executed evidence internally consistent; exact coverage = fullStandard02(source-02) + focused corrected Standard/Dev(source-03) + fullDev02(source-03).
- **Material limitations**: D1–D4 physical device gates unpassed/unavailable — no device or hardware claims; net+55 test-count delta vs \bbd19d\ accepted from coordinator (not independently recomputed against a baseline run); remaining S6 gates — assembly, lint, full format, domain, physical — separate and open. Reviewer made zero source/Gradle edits, ran no builds, created no agents.
