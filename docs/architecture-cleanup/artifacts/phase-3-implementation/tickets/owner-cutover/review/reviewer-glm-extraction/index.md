---
title: "S5 mechanical extraction review (GLM 5.3 Flash max; §7 re-check by Gemini 3.8 Flash) — CLEARED for red-04/05/06/07/08"
kind: review
comments: none
---

# S5 Mechanical Extraction Review (post red-03 MethodTooLargeException)

- **Reviewer**: GLM 5.3 Flash, reasoning max, harness OpenCode (`zai-coding-plan:glm-5.3-flash`, ambient, full_access), agent `d6e13131-3e86-41f3-a912-85a77442b41c` — same reviewer identity, independent, zero source authorship. Implementer `e8d65346`.
- **Context**: red-03 (`s5-red-03`, actual 1 / guard 1 / 521.98s, no breach, no XML/test-worker) compiled clean — the §4 P0 fix held in practice — then failed at JVM codegen: `MethodTooLargeException` on `TranslationPipeline.runGrantedSinglePageBoundary` (evidence: `validation/s5-red-03/command.log`). Implementer applied a bounded mechanical extraction; this review covers that delta plus red-04 scripts.
- **Verdict**: **PRELAUNCH CLEARED for red-04** (bounded: meaningful red evidence only; no green approval, no behavior patch, D1–D4 unpassed).

## 1. Hash inventory

`TranslationPipeline.kt` = `cd40f37852cabc4afeb4a12021ec53aaf2967181e38b2cee6fe8ba31f8e059f3` — exact match to coordinator handoff. Other 9 inventoried files re-hashed byte-identical to the previously verified values, including `PreparedPageBoundary` `9907d22a…`. Single-file delta confirmed.

## 2. Extraction structure

Two new private members of the same class; `runGrantedSinglePageBoundary` (line 519) keeps its signature, fork/join skeleton, and teardown:

1. `runNativeInpaintBranch` (`TranslationPipeline.kt:831-917`): the async native body moved verbatim — `withNativeLane` args (timeout/chapter/onTimeout orphaning+WARN/FOREGROUND), lane body (`sourceCustody.take()` null-guard with span CANCELLED + `return@withNativeLane SoftNull`, CLEANING event, inpaint with span-end finally, produce/SoftNull), enrollment gate `laneResult != null && owner.isAvailable` with identical `enrollCleanedPersistence` args, and the Throwable catch (rethrow `CancellationException`, ERROR log verbatim, typed outcome).
2. `resolveNativeCompletion` (`TranslationPipeline.kt:920-989`): the post-join arms — TimedOut → `markTimeout(nativeTimer = true)` with identical field logic and `Failed("native phase timed out")`; Failed → `attempt.write("native inpaint failure")` with identical fields; http passthrough (`httpOutcome !is Completed → return httpOutcome`); render arm → `renderSinglePageTranslated`. Former `return@withTimeout` values now function returns.

**Wiring preserved** (verified at call sites):

- `async` still starts inside the shared `withTimeout(singlePageTimeoutMs)` (line 691); `await()` still precedes the resolver (734); the resolver is invoked inside the same `withTimeout` lambda (736-748) — so the render arm remains under the shared deadline, same as before extraction.
- The HTTP-branch catch still uses `return@withTimeout TranslationCompletionOutcome.Failed(httpError)` in place (731) — not moved, not duplicated. Paid-call policy unchanged: one HTTP translate call, started once, before the native await.
- Outer `try`/`TimeoutCancellationException` catch (750-775) and the LIFO teardown `finally` chain (785-815: drain → clearPage → custody close → owner release → endTranslatorUse → reclaim) are untouched; the helpers hold no cleanup responsibility, so no parent/cancellation shift. `markTimeout` remains `NonCancellable` inside `AttemptAuthority` — its invocation context is unchanged (still within the `withTimeout` lambda).
- Textless early-exit gap (674-682) byte-identical — red gap 1 intact. CleanedPublication/PreparedPageBoundary untouched — red gaps 2/3 intact. Behavior patch still held.

**Authorized delta inside the moved block** (`886-890`): the previously empty `source.recycle()` catch now logs `logcat(LogPriority.ERROR, e) { "TachiyomiAT segment 2 source bitmap recycle failed: pageKey=$pageKey" }` — original cause preserved, recycle order unchanged, no rethrow (terminal policy kept). In-class receiver so the repo wrapper resolves. This matches the authorized scope exactly.

**Result carrier**: `NativeBranchOutcome` is a `private sealed interface` local to the class (823-828) with exactly Success/SoftNull/TimedOut/Failed(throwable) — a minimal mechanical carrier, not a broad abstraction; `Produced→Success`, `SoftNull→SoftNull`, `null→TimedOut` preserves the old null-branch semantics. Comments are two 1-line KDocs, code-explanation only.

## 3. Reuse/necessity check (user rule)

No pre-existing seam covered this: the moved blocks are the T3-internal native branch and its post-join resolution — phase classes (`SinglePageHttpRenderPhase`, `CleanedPublication`, `SinglePageOnnxPhase`) expose different responsibilities; reusing them would have required new cross-phase plumbing, not reuse. Extraction into same-class private helpers with explicit parameters (all closure variables passed explicitly, including `store`) is the minimal necessary form. No new files.

## 4. Size-reduction assessment (bounded, no compile guarantee)

The two heaviest lambda-laden blocks (async branch body + post-join arms, ~155 source lines) left the boundary function. Residual body keeps admission, segment-1 native OCR arm, OCR publish, fork/join skeleton, timeout catch, mapping, teardown. Plausibly below the 64KB bytecode limit, but unverifiable without an actual run. **Bounded residual-risk note, no speculative refactor requested**: if red-04 re-hits `MethodTooLargeException`, the next mechanical candidate is the segment-1 native OCR region (~546-665), same extraction pattern — decision belongs to coordinator/implementer after evidence.

## 5. Red-04 launcher/observer

`planned-s5-red-04.sh` vs `planned-s5-red-03.sh`: diff is exactly the `--evidence …/s5-red-04` line. `observe-s5-red-04.py` vs `observe-s5-red-03.py`: diff is exactly the evidence path. Same single `:app:testStandardDebugUnitTest`, same three verbatim textless selectors, same flags/deadlines, guard delegation unchanged. `s5-red-04` evidence dir does not pre-exist (`Test-Path` = False). Overwrite refusal intact.

## 6. Disposition

**PRELAUNCH CLEARED.** Coordinator may execute `planned-s5-red-04.sh` with `observe-s5-red-04.py` under the unchanged guard. Expected outcome: the 3 targeted textless methods fail for their designated lifecycle causes with valid failure XML — a meaningful red baseline. A repeat `MethodTooLargeException` is a bounded residual risk (§4) and would itself be valid evidence for the next mechanical step. Physical device absent; D1–D4 unpassed.

## 7. Red-04 Typecheck Finding & Red-05 Fix Re-check — P0 Resolved, CLEARED for red-05

- **Re-review**: Antigravity **Gemini 3.8 Flash**, reviewer agent `d6e13131-3e86-41f3-a912-85a77442b41c`, transcript row `faac2b52-a568-4ec0-82d6-9e504364efa3` — the turn's display name mislabeled it as GLM; coordinator runtime check corrected the attribution (§7.6). Sections 1–6 remain the genuine GLM 5.3 Flash max / OpenCode extraction review, unchanged. Both turns independent, zero source authorship. Implementer `e8d65346`.
- **Red-04 Outcome**: Execution failed in Kotlin typecheck before codegen or test runner execution (`Pipeline:832 unresolved reference TranslationRunTrace`, cascading to `859 beginStage`). Actual run 226.61s, actual 1 / guard 1 / no XML / worker active.
- **Reviewer Qualification Correction**: The initial §2 extraction review verified semantic equivalence and AST movement, but missed checking actual symbol resolution of newly explicit helper parameter types against declarations. In the unextracted method, `traceRun = TranslationTrace.currentRun()` inferred its return type `TranslationRunTrace?`. The mechanical extraction wrote the parameter as `traceRun: TranslationTrace.TranslationRunTrace?` without an import. Because `TranslationRunTrace` is declared top-level in `diagnostics/TranslationTrace.kt:869` (package `eu.kanade.translation.diagnostics`) and is not nested within `object TranslationTrace`, Kotlin member-qualification syntax failed. Recheck protocol is updated to include explicit symbol/name resolution against declarations for every new type annotation.

### 7.1 Fix Verification & 10-File Hash Inventory

Implementer delivered the exact 2-line fix:

1. `TranslationPipeline.kt:11`: added top-level `import eu.kanade.translation.diagnostics.TranslationRunTrace`.
2. `TranslationPipeline.kt:833`: simplified parameter annotation to bare `traceRun: TranslationRunTrace?,`.

**Hash check**:

- `TranslationPipeline.kt` = `8caf18bdbcec4f1f69d43e1123025318db609ea19a89c433d647b96918aeb4af` — exact match to coordinator handoff.
- Other 9 inventoried files re-hashed: all byte-identical to previously verified values:
  - `PreparedPageBoundary.kt`: `9907d22a089d2c9a9b7e04ae638f198b17ac37fb50fa3a0cb791bed9c526c023`
  - `AttemptAuthority.kt`: `fcd75d14df30c4d1a596320d7fdd1dd6523017fc2983990c3c5695084ae5bbd8`
  - `SinglePageHttpRenderPhase.kt`: `656a89934af803e31b1ac7115e676fd7087d51b4350b5ea77527fdc8caed99ea`
  - `SinglePageOnnxPhase.kt`: `9b451c3b7917098924d63c13b3a83f3aebdbfec3779171cd6f6edb01554989ee`
  - `ChapterTranslationStore.kt`: `b14f458baf4522cb0caf41effba0af15fad165bb14844a166a9294aa3d4e573b`
  - `CleanedPublication.kt`: `971e8dce98747eabc3ec5eae0b66682c9e9f2f01c8fa7187f6d2101740e94a76`
  - `FakeEngines.kt`: `0f78263c6f90e3ab01ced8f4cb677b26ce8da1cf7dde05e0c8087769048276d4`
  - `TranslationCoexistenceHarness.kt`: `58144b400d93eb901b62d538d76b2390072d6e3e2a0788617680126f189ddef2`
  - `SinglePageOverlapBoundaryTest.kt`: `51d844d4e458fa789b3e44c268f6fc369768df039d607189999b5041591dbbe3`

### 7.2 Source & Name Resolution Verification

- `TranslationRunTrace` resolves to top-level class `eu.kanade.translation.diagnostics.TranslationRunTrace` via line 11 import.
- Line 859 `traceRun?.beginStage(TranslationTraceStage.INPAINT, lane = TranslationTraceLane.NATIVE, provider = TranslationTraceProvider.CPU)` resolves against `TranslationRunTrace.beginStage(...)` (`TranslationTrace.kt:909`).
- Line 866 `inpaintSpan?.end(TranslationTraceOutcome.CANCELLED)` resolves against `TranslationStageSpan.end(...)` (`TranslationTrace.kt:980`).
- Line 694 caller `runNativeInpaintBranch(traceRun = traceRun, ...)` passes local `traceRun` (`TranslationTrace.currentRun()`), matching the parameter signature.
- All other types across `runNativeInpaintBranch` and `resolveNativeCompletion` re-verified: `NativeBranchOutcome` (class-local sealed interface), `OnnxPhaseResult`, `TranslationCompletionOutcome`, `AttemptAuthority`, `CleanedOwner`, `PageWriteOrigin`, `StageStatus`, `PageStoreWriter` all resolve legally via package membership or explicit imports.

### 7.3 Method-Size & Behavior-Gap Status

- **Method bytecode size**: Remains **unproven**. Because red-04 failed during typecheck prior to JVM bytecode generation, whether `runGrantedSinglePageBoundary` is safely below the 64KB method limit will only be proven on an actual compile/test run.
- **Three behavior gaps held intact**:
  - Gap 1 (reader mask-only): `TranslationPipeline.kt:674-682` textless early exit returns `Completed` on empty blocks before segment 2.
  - Gap 2 (prepared mask-only): `CleanedPublication.kt` hash unchanged (`971e8dce…`).
  - Gap 3 (unmasked textless prepared): `PreparedPageBoundary.kt` hash unchanged (`9907d22a…`).
  - `SinglePageOverlapBoundaryTest.kt` hash unchanged (`51d844d4…`).
  - Zero behavior patches applied; test harness frozen.

### 7.4 Red-05 Launcher & Observer Clearance

- `planned-s5-red-05.sh` vs `planned-s5-red-04.sh`: diff is strictly the `--evidence …/s5-red-05` path substitution. Same single `:app:testStandardDebugUnitTest`, same 3 textless selectors verbatim, `--max-workers=2 -Dkotlin.daemon.jvmargs=-Xmx3g --console=plain`, 1200s/300s setup/test timeouts, guard delegation intact.
- `observe-s5-red-05.py` vs `observe-s5-red-04.py`: diff is strictly the `s5-red-05` evidence path.
- Freshness: `s5-red-05` directory confirmed absent (`Test-Path` = False). Overwrite refusal intact.

### 7.5 Disposition

**PRELAUNCH CLEARED for red-05.**
Coordinator may execute `planned-s5-red-05.sh` with `observe-s5-red-05.py` under the unchanged guard. Expected outcome: clean compile through codegen (proving or disproving the method-size fix), followed by the 3 targeted textless methods failing for their designated lifecycle causes with valid failure XML. Physical device absent; D1–D4 unpassed.

### 7.6 Provenance correction — attribution only (2026-10-16)

Coordinator runtime check established that the §7 red-05 re-check/clearance turn executed on Antigravity Gemini 3.8 Flash (transcript row `faac2b52-a568-4ec0-82d6-9e504364efa3`) although its display name showed GLM; the clearance reply to the coordinator (`responseId 7a0ac900-c0a2-4254-8df1-7b3f237fa065`) was dispatched from that Gemini turn. The reviewer harness was then restored to OpenCode `zai-coding-plan:glm-5.3-flash`/max for subsequent turns. This correction changes provenance only: no review finding, hash, script check, or disposition text was altered, and Sections 1–6 (extraction review, red-04 clearance) remain the genuine GLM turn's work. Red-05 then launched on the identical reviewed source (`8caf18bd…`) and scripts; no re-review was required.

## 8. Red-05 Codegen Finding & Second Extraction Review — CLEARED for red-06

- **Delta provenance**: the second extraction delta was authored by the coordinator (root) during the twice-stuck takeover; implementer `e8d65346` holding. Review independent — this turn GLM 5.3 Flash max, OpenCode harness (`zai-coding-plan:glm-5.3-flash`, ambient, full_access), agent `d6e13131-3e86-41f3-a912-85a77442b41c`; reviewer authored no code and ran no builds.
- **Red-05 outcome**: the §7 typefix passed typecheck; JVM codegen failed again — `MethodTooLargeException` on `TranslationPipeline.runGrantedSinglePageBoundary` (evidence `validation/s5-red-05/command.log`; actual 1 / guard 1 / 355.97s, no breach, no XML/test-worker). First extraction alone insufficient for the 64KB method limit.
- **Delta**: `TranslationPipeline.kt` `8caf18bd…` → `8084e6e6b861bf8cdd1f3b8baf721a00b28f7696216944ff693fdd99afd93e18`. Outer nested-finally cleanup chain moved verbatim into new `private suspend fun releaseSinglePageResources(manga, chapter, source, pageKey, deferredPublications, sourceCustody, owner)` (791-829); outer finally now a single direct call (787). No other change.

### 8.1 Independent inverse-extraction proof (byte-exact)

Reviewer reproduced the inverse transform independently on raw UTF-8 bytes, in-memory only (no source mutation): delete the helper block, inline its body at the original call site with +4 spaces indentation. Reconstructed SHA256 = `8caf18bdbcec4f1f69d43e1123025318db609ea19a89c433d647b96918aeb4af` — exact match to the §7-verified red-05 state. The delta is therefore proven to be the function extraction plus the single call only: zero other text, behavior, comment, or trace change. (First local proof attempt with `Path.read_text()` Windows-default decoding failed on hash; raw-bytes decode used instead — same pitfall coordinator reported.)

### 8.2 Semantics verification

- **Chain order preserved verbatim** (byte-exact inverse proves): drain → clearPage → `sourceCustody.close()` → `owner.release()` → `engines.endTranslatorUse()` → `recognitionEngine.reclaimPooledMemory()` with catch-WARN `logcat(LogPriority.WARN, e)`. Nested try/finally structure intact; no rethrow change; terminal policy kept.
- **No new wrappers**: no `withContext(NonCancellable)`, no new Job/context/deadline/exception wrapper (byte-exact inverse proves none added). Function boundary only — same coroutine, same suspension points, the finally still executes on every method exit, so cancellation and failure propagation are unchanged.
- Helper contains no early returns; `suspend` is legal (invoked from the suspend boundary method's finally).
- **Name resolution**: all 7 parameter types (`Manga`, `Chapter`, `HttpSource`, `String`, `DeferredPagePublications`, `SourceBitmapCustody`, `CleanedOwner`) are the same types already declared on `runGrantedSinglePageBoundary`'s own parameters — the call passes exactly those 7 in-scope values. Red-05 passed typecheck before codegen, so the file's names resolve in practice. No trace changes (byte-exact inverse).
- **Rationale sound**: Kotlin inlines finally bodies at each method exit path; the boundary has many exceptional exits, duplicating the ~27-line nested chain per path. Extraction leaves one invoke per exit and moves the chain body outside the 64KB-limited method. Plausibly decisive reduction — actual bytecode size still unproven until red-06.

### 8.3 Reuse check (user rule)

Existing same-class helpers cover single steps only: `drainDeferredPublications` (drain step, 309), `releaseReaderPageLease`/`releaseBatchPageLease` (reader leases, 1029/1039), inline `recognitionEngine.reclaimPooledMemory()`. No existing helper covers the full operation-owned chain; no new file, interface, or comment added. Same-class private helper with explicit parameters is the minimal necessary form.

### 8.4 Red-06 launcher & freshness

- `planned-s5-red-06.sh` vs `planned-s5-red-05.sh`: diff is strictly the `--evidence …/s5-red-06` path. `observe-s5-red-06.py` vs `observe-s5-red-05.py`: diff is strictly the evidence path. Same single `:app:testStandardDebugUnitTest`, same 3 textless selectors, same flags/deadlines, guard delegation unchanged.
- Freshness: `s5-red-06` directory confirmed absent. Overwrite refusal intact. Textless gaps and frozen test (`51d844d4…`) byte-identical per the 10-file hash check.

### 8.5 Disposition

**PRELAUNCH CLEARED for red-06.** Coordinator may execute `planned-s5-red-06.sh` with `observe-s5-red-06.py` under the unchanged guard. Expected outcome: clean codegen (method-size fix proven or disproven) followed by the 3 targeted textless methods failing for their designated lifecycle causes with valid failure XML. Residual bounded risk: if `MethodTooLargeException` recurs, §4's next mechanical candidate (segment-1 native OCR region inside the boundary method) stands. Physical device absent; D1–D4 unpassed.

### 8.6 Method-size fix PROVEN (red-06 codegen, tests pending)

Red-06 verified post-launch: `:app:compileStandardDebugKotlin` passed codegen — no `MethodTooLargeException` — and advanced to `compileStandardDebugJavaWithJavac`. Emitted class parsed (immutable evidence `validation/s5-red-06/bytecode-sizes.json`, class sha256 `b29f6730d14e963f806daefe9ecb0d53236250b89810231c7e28d89218486ff4`, source `8084e6e6…` = §8-reviewed state, reviewer re-hashed source unchanged): `runGrantedSinglePageBoundary` **8673** code bytes, `releaseSinglePageResources` 7652, `runNativeInpaintBranch` 965, `resolveNativeCompletion` 1011 — versus JVM max 65535. The §8.2 rationale held in practice; §4 residual MethodTooLarge risk discharged. The §8.5 bounded-clearance caveat ("bytecode success unproven") is hereby closed. Test execution (designated textless reds + valid XML) still pending; reviewer awaits actual XML before assessing reds. No performance claims drawn from this run (brief other-task Gradle overlap occurred; inventory retained, foreign daemon since gone).

## 9. Red-06 Test-Compile Finding & Fixture Retarget Review — CLEARED for red-07

- **Provenance**: fixture retarget authored by implementer `e8d65346`; reuse target identified by root (coordinator). Review independent — GLM 5.3 Flash max, OpenCode harness (`zai-coding-plan:glm-5.3-flash`, ambient, full_access), agent `d6e13131-3e86-41f3-a912-85a77442b41c`; reviewer authored no code, ran no builds.
- **Red-06 outcome**: main compile **passed** — production `8084e6e6…` compiled clean, §8.6 method sizes stand. Test source-set compile then failed: `SinglePageOverlapBoundaryTest.kt:558` passed `first.state.value` (`Map<String, PageTranslationView>`, `ChapterTranslationStore.kt:446`) to `initialRecoveredPages: Map<String, PublishedPageTranslation>?` (`ChapterTranslationStore.kt:291`). First-ever test-set compile: all prior reds died before testCompile, so the frozen fixture had never typechecked. Actual 1 / guard 1 / 559.66s, no XML.
- **Delta**: test file `51d844d4…` → `b1f2f030d48289ddbfca96b49e6af62cc4c4baa56a10032c1117884c50a244de`; line 558 now `initialRecoveredPages = first.capturePublishedPagesForCompaction()`. All other 9 hashes unchanged (`Pipeline 8084e6e6…`, `Boundary 9907d22a…`).

### 9.1 One-line inverse proof (byte-exact)

Reviewer reproduced independently on raw UTF-8 bytes, in-memory: restore `first.state.value` at line 558 → reconstructed SHA256 = `51d844d4e458fa789b3e44c268f6fc369768df039d607189999b5041591dbbe3` exactly (the frozen pre-fixture state). The helper name occurs exactly once in the file. Delta proven to be that single line only — zero other text; assertions, counts (incl. expected-20), and red designations byte-unchanged.

### 9.2 Declaration & semantics checks (actual types)

- `capturePublishedPagesForCompaction()` (`ChapterTranslationStore.kt:741-742`): `internal suspend fun … : PersistentMap<String, PublishedPageTranslation> = mutex.withLock { pages }` — immutable snapshot under store mutex, as documented (735-740).
- Target param `initialRecoveredPages: Map<String, PublishedPageTranslation>?` (`:291`) — `PersistentMap` is a `Map` subtype; non-null fits nullable; **no cast**.
- Test method runs in `runTest` (line 546) — suspend call legal. Same-module `internal` visible from the app unit-test source set (Kotlin friend-module standard).
- Scalar-identity premise preserved: seeding pages does not touch `artifactFileName`/`artifactStore`/`initialArtifactManifest`, so the "identical scalar identity" scenario is unchanged. Published map is the correct recovery-seed source (`state.value` view map was the wrong element type — the fixture never compiled, so no behavioral regression is possible).

### 9.3 Reuse & scripts

- Reuse rule satisfied: existing internal store helper reused; no new helper, file, cast, or assertion.
- `planned-s5-red-07.sh` vs 06 and `observe-s5-red-07.py` vs 06: diffs strictly the `s5-red-06` → `s5-red-07` evidence path. `s5-red-07` absent; guard unchanged. Three textless behavior gaps held (production byte-identical).

### 9.4 Disposition

**PRELAUNCH CLEARED for red-07.** Coordinator may execute `planned-s5-red-07.sh` with `observe-s5-red-07.py` under the unchanged guard. Expected outcome: testCompile passes; the 3 designated textless methods fail for their lifecycle causes with valid failure XML — the first meaningful red baseline. D1–D4 unpassed.

## 10. Red-07 VerifyError Finding & Prepared Boundary Extraction Review — CLEARED for red-08

- **Provenance**: extraction authored by implementer `e8d65346` (first handoff `6f1d319…` rejected by root for missing `return`; corrected to `return try { … }`); reuse necessity assessed by root. Review independent — GLM 5.3 Flash max, OpenCode harness (`zai-coding-plan:glm-5.3-flash`, ambient, full_access), agent `d6e13131-3e86-41f3-a912-85a77442b41c`; reviewer authored no code, ran no builds.
- **Red-07 outcome**: testCompile passed; 3 XML failures, errors 0, skipped 0 — but all `java.lang.VerifyError: Bad local variable type` in `TranslationPipeline.prepareSinglePage` before any lifecycle assertion. Not designated red proof. Actual 1 / guard 1 / 561.36s, no breach. Worker-identity proof closed (worker 24860 exact tags; daemon 19788 / client 29600). Reviewer verified XML summary only (bounded output; no VerifyError attribute dump).
- **Compiler root cause (bounded inference)**: root's javap (`validation/s5-red-07/pipeline-javap.txt`) — `@4858 aload_13` followed by `SpillingKt.nullOutSpilledVariable`; local-variable table slot 13 = inner `store`, slot 14 = `AttemptAuthority`, slot 9 = `leaseStore` — store state spilled out-of-scope on the outer release-lease path across the many duplicated finally regions (the boundary has 4 early returns + throws, each duplicating the inner drain→custody→release chain and outer lease finally). Nullable-`let` attribution remains inference; not proven as exact cause. Helper separation is the same duplication-collapse family as §8; actual verifier acceptance unproven until red-08 runs.
- **Delta**: `TranslationPipeline.kt` `8084e6e6…` → `7b3764aa230610d5a3cf8e1ca89c0bbbf2fc177ab6deb03ce7735e4d1fb753d0`. Override `prepareSinglePage` (1097-1134) keeps signature, `leaseStore` resolve, comments, and acquire gate verbatim, then `return try { runGrantedPreparedPageBoundary(manga = …, …, stageListener = …) } finally { releaseReaderPageLease(leaseStore, pageKey, PageWriteOrigin.AUTO) }` — explicit return, 7 named args, outer lease finally verbatim. New `private suspend fun runGrantedPreparedPageBoundary` (1136-1323, 7 same-typed params, returns `PreparedPage?`) holds the entire old granted body + inner cleanup chain (first 5 lines re-indented to 8 spaces, remainder at old indent).

### 10.1 Independent inverse proof (byte-exact)

Reviewer reproduced the inverse independently on raw UTF-8 bytes, in-memory (no source mutation), following root's recipe: strip the helper wrapper, re-indent the first 5 helper-body lines (comment + 3 resource vals + inner `try {`) back +4 spaces, re-inline into the override replacing the `return try { call } finally { … }` block, restoring the original outer lease finally. Reconstructed SHA256 = `8084e6e6b861bf8cdd1f3b8baf721a00b28f7696216944ff693fdd99afd93e18` — exact match to the §8/§9-verified red-06/07 state, 1818 lines. Delta proven to be the wrapper extraction + explicit return only: zero other text, semantics, casts, flags, comments, or trace change. (Reviewer's first attempt accidentally omitted the file prefix and was discarded; corrected run reproduced clean.)

### 10.2 Semantics, name/type, and constraint checks

- **Lease timing unchanged**: acquire gate precedes the try; `releaseReaderPageLease` runs in the override finally after the helper returns or throws — byte-exact inverse proves identical ordering to the pre-extraction method.
- **Return/exception/cancellation semantics unchanged**: the old in-body returns (terminal, null, publish) are now helper returns propagated through the override; finally still executes on every exit; no `withContext(NonCancellable)`, no context/deadline wrapper, no verifier flags, no compiler-version change, no casts (byte-exact inverse).
- **Name/type legality**: helper name occurs exactly twice (def + call), no collision with `runGrantedSinglePageBoundary`; 7 param types identical to the override's own already-resolving types; `PreparedPage?` return matches; `suspend` legal (called from suspend override). No `leaseStore` param — helper resolves its own `store` exactly as the old body did; `leaseStore` stays override-local for gate + release, matching old scoping.
- **Explicit return fix verified**: `return try { … }` — the earlier block-body-without-return defect (`6f1d319…`) is corrected.
- **Reuse**: same-class private extraction is the same minimal pattern as §8; no existing seam covers the prepared boundary body; no new file or new comment (the 1-line comment is moved old text). Three textless gaps intact (production/test deltas none; test stays `b1f2f030…`).

### 10.3 Red-08 launcher & freshness

- `planned-s5-red-08.sh` vs 07 and `observe-s5-red-08.py` vs 07: diffs strictly the `s5-red-07` → `s5-red-08` evidence path. `s5-red-08` absent; guard unchanged.
- All other 9 inventory hashes unchanged (`Pipeline` aside: `PreparedPageBoundary 9907d22a…`, test `b1f2f030…`, etc.).

### 10.4 Disposition

**PRELAUNCH CLEARED for red-08** (bounded: VerifyError resolution unproven until the actual run — slot-spill attribution is inference, the extraction is the plausible mechanical fix). Coordinator may execute `planned-s5-red-08.sh` with `observe-s5-red-08.py` under the unchanged guard. Expected outcome: verifier accepts `prepareSinglePage`; the 3 designated textless methods fail for their lifecycle causes with valid failure XML — the meaningful red baseline. If `VerifyError` recurs, next diagnostic is a fresh javap of the emitted method. D1–D4 unpassed.

## 11. Red-08 Triage — NO Genuine Designated Red; Fixture Pre-Registration Trap

- **Red-08 facts**: actual 1 / guard 1 / 528.27s, no breach, no monitor error. XML: 3 failures / 3 errors 0 / skipped 0. `prepareSinglePage` now verifies (VerifyError resolved in practice — §10 bounded caveat closed). Failure causes differ from designated predictions.
- **Observed causes**: reader assert (test:226) expected `Completed`, got `Failed(pageKey=p0, reason=native phase timed out)`; prepared masked (test:231) expected `READY`, got `PENDING`; prepared unmasked (test:231) expected `SKIPPED`, got `PENDING`.
- **Exact cause chain (verified in actual code, reviewer-independent)**:
  1. `assertTextless` (test:202-203) builds `artifactAuthorityStore(listOf("p0"))` with default `preRegisterInStore = true` (Harness:896, PENDING seed at 915/263-264) and passes it as `storeOverride` — the pipeline sees a pre-registered fresh PENDING page for "p0".
  2. The trap is self-documented (Harness:243-249): pre-registered PENDING entries make the single-page planner project WAIT_FOR_DEPENDENCY for every stage (DETECTION plans RUN and blocks them); the manual path would resume-skip before any barrier.
  3. `PageWorkPlanner.plan(page, force=false)` (PageWorkPlanner.kt:30-81 → planPage stage evidence) returns all-false for the PENDING page → `SinglePageOnnxPhase.kt:302-307` early `return null` before any engine/native work.
  4. Reader path: `translateSinglePageOnnx` null hits the `runGrantedSinglePageBoundary` segment-1 `onnxResult == null` guard (TranslationPipeline.kt:652-665): page non-terminal PENDING → `markTimeout(nativeTimer = true)` → direct `return SinglePageOutcome.Failed(pageKey, "native phase timed out")` at 665 — a null masquerading as a timeout; no real timeout occurred. (Route correction: this exits segment 1 directly and never reaches `runNativeInpaintBranch`:923 / `resolveNativeCompletion`:946.)
  5. Prepared path: `onnxResult == null` → `buildTerminalPreparedPage` returns null for the non-terminal PENDING page (Pipeline:1229-1231) → `reference` null, page stays PENDING → test:231 asserts fail.
- **Verdict**: the three failures are fixture-environmental, not designated lifecycle reds. All target guards (reader textless early-exit, masked-textless clean, unmasked-textless worker bypass) were never exercised — the pipeline bailed at resume planning before engines. **No meaningful red baseline established by red-08.**
- **Fixture correction direction (implementer-assigned, pending frozen review)**: use the existing `preRegisterInStore = false` seams in both `artifactAuthorityStore` and `create` where relevant (EMPTY store matches production fresh-chapter state; `plan(null)` then returns all-run flags, PageWorkPlanner.kt:36-43), plus intended-reach assertions before dependent failures. Fixture-only; no production change, no semantic patch, no Gradle. Reviewer notes: with an empty store the manual/prepared path must create the page record itself — the harness comment ("the manual path creates it") and OnnxPhase:317 `(it ?: PageTranslation())` support this; correction review must verify record creation on the prepared variant explicitly.
