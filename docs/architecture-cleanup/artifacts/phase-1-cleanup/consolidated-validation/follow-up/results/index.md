---
kind: spec
title: "P1.5 follow-up — lint fixes, N2 trace + diagnostic, build-ledger corrections"
comments: none
---

# P1.5 follow-up results

Bounded follow-up under `consolidated-validation/follow-up` on top of P1.5 delivery (base `b44143f2`). Allowed edits were exactly three lint items; no LiteRT formatting, no broad `spotlessApply`, no fixture/store changes, no commits/pushes/subagents, one Gradle owner. Original Phase 0 evidence: the **fresh original full-run XML set (the 670-file ledger comparator, 332+332+6 classes)** — distinct from the separate **30-file pre-existing stale snapshot** — plus the full-suite ledger, user report, and all P1.5 evidence, all untouched (report SHA256 re-verified `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`; final `git status` = the 3 lint-modified files + the untracked report).

# 1. Build-ledger corrections (P1.5 results doc mis-attribution)

Facts from the retained `4ac69adc` log: its head starts 2026-10-14T17:25:36.005 mid-invocation — 34,880 lines of D8/R8 metadata warnings within ~0.8 s — already inside the invocation that runs `mergeStandardDebugGlobalSynthetics`, `processStandardDebugJavaRes`, `mergeStandardDebugJavaResource`, `mergeProjectDexStandardDebug`, `packageStandardDebug`, `createStandardDebugApkListingFileRedirect`, then `> Task :app:assembleStandardDebug` (line 34890), ending **BUILD SUCCESSFUL in 4m 11s / 273 actionable tasks: 16 executed, 1 from cache, 256 up-to-date** (lines 34899–34901, 17:28:34.686).

- **Corrected attribution: the 4m 11s build is `:app:assembleStandardDebug`, not DevDebug.** The 16 executed tasks were real Standard-dex/package work; assembleDevDebug had completed earlier (~17:24:23) and its entire output predates the retained-log head cap — **assembleDevDebug's completion evidence is genuinely absent from the retained log**.
- Provenance restored by the explicitly authorized separate cached run (shell `8da8a714`): `./gradlew.bat :app:assembleDevDebug --console=plain` → **BUILD SUCCESSFUL in 35s**, `DEV_ASSEMBLE_EXIT=0`, logged at 17:52:06. P1.5's "assembles green" conclusion stands; its attribution text was wrong and is superseded by this section.

# 2. Exact lint diff (3 files, all authorized)

`git diff --numstat`: `0 1` CollisionRelaxation.kt; `0 1` BatchParallelWindowBudgetTest.kt; `2 2` ChapterTranslatorQueueRestoreTest.kt.

1. `app/src/main/java/eu/kanade/translation/engines/rendering/CollisionRelaxation.kt` — removed the now-unused `import eu.kanade.translation.model.TranslationBlockView` (its usages were removed by the P1.2 rendering-island slice).
2. `app/src/test/java/eu/kanade/translation/coexistence/BatchParallelWindowBudgetTest.kt` — removed the stray blank line before the final `}` (left by the P1.3b mirror deletion).
3. `app/src/test/java/eu/kanade/translation/workflow/ChapterTranslatorQueueRestoreTest.kt` — moved `import tachiyomi.domain.chapter.model.Chapter` and `import tachiyomi.domain.manga.model.Manga` above the `java.*` imports (custom-imports-before-Java ordering; the P1.3b additions were placed after `java.io.File`/`java.security.MessageDigest`).

`LiteRTMangaInpaintingEngine.kt` was NOT touched, per scope.

# 3. Narrow rechecks (shell 72d14d19, sequential, --rerun fresh compile)

| Check | DevDebug | StandardDebug |
| --- | --- | --- |
| `TextLayoutPlannerTest` (render-relevant suite — CollisionRelaxation consumes `TextLayoutPlanner.GrownBox`; no dedicated CollisionRelaxationTest exists) | 34 tests / 0 failures | 34 / 0 |
| `BatchParallelWindowBudgetTest` | 2 / 0 | 2 / 0 |
| `ChapterTranslatorQueueRestoreTest` | 10 / 0 | 10 / 0 |

Exits `NARROW_DEV_EXIT=0` (32s), `NARROW_STD_EXIT=0` (30s). XML preserved at `follow-up/narrow-{devdebug,standarddebug}-raw/` (mtimes 17:54:34 / 17:55:05).

`:app:spotlessCheck` after the edits → `spotlessKotlinCheck` still fails with **exactly one file**: `LiteRTMangaInpaintingEngine.kt` (same two pre-existing wrapping hunks @160/@357, verbatim at baseline `4de121bf`, file outside Phase 1's diff). **All three cleanup-introduced violations are resolved**; the remaining failure is the pre-existing baseline item explicitly excluded from this scope. `SPOTLESS_EXIT=1` recorded as the honest outcome; resolving it would need either the coordinator's separate approval for that one file's formatting or acceptance of the pre-existing violation as baseline debt.

# 4. N2 read-only trace (from preserved P1.5-standard XML, no rerun for the trace)

Literal nested structure of the full-suite failure (`TEST-…ProfileEnvelopeDispatchTest.xml`, testcase `N2 normal AI batch makes zero analysis transport calls()`):

- Outer: `org.junit.platform.commons.JUnitException` — "Failed to close extension context" from `JupiterEngineExecutionContext.close` during `TestMethodTestDescriptor.cleanUp` — i.e. the test body had already passed; the failure is extension-context teardown.
- `Caused by: java.io.IOException` — TempDir `C:\Users\ADMIN\AppData\Local\Temp\junit-14912880492396424710` could not be deleted; culprit path **`Chapter 1_artifacts\runs\f-e70376629d496ff8ca2337fcb101a341935d3c3d47784d886196bfefd86d4c3f.json`** (content-addressed run-record sidecar: `runs/f-<sha256(contentFingerprint)>.json`, `ChapterArtifactLayout.kt:57-60`, `ChapterArtifactManifest.kt:238`).
- Suppressed #1: `java.nio.file.AccessDeniedException` on that exact file (first walker pass).
- Suppressed #2: `java.nio.file.NoSuchFileException` on the **same path** (TempDirectory's `resetPermissionsAndTryToDeleteAgain` retry found the entry gone — the file's state changed between the two passes).

**Causal wording (per coordinator correction, deliberately bounded):** `AccessDeniedException` on Windows `Files.delete` is consistent with an open handle, restrictive attributes, or an external scanner — it does **not** prove a still-open handle. AccessDenied-then-NoSuchFile demonstrates only that the entry's state changed between walker passes; it does **not** uniquely identify retention work. What is established: every fixture store in `ProfileEnvelopeDispatchTest` is left **undrained** (no `closeAndFlush()`/`close()` anywhere in the class — `lazyStore()` plus seven `ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")` sites at lines 500, 612, 719, 896, 1005, 1080, 1184), while the store owns async persistence children that touch the artifact tree (`writeDrain` children and `journalWriter.drainAndClose` drained only by `closeAndFlush`, `ChapterTranslationStore.kt:4717-4725`; the engine's `retentionScopeJob` fire-and-forget sweeps reclaim `runs/` sidecars via `reconcileArtifactRetentionAsync` → `ChapterArtifactEngine.cancelRetentionWorkAndJoin`, `:2283`). **Undrained store work racing TempDir cleanup is the plausible mechanism — not demonstrated attribution.** The failure family is identical to the two ledgered `ChapterTranslationStoreArtifactOpenProbeTest` failures (`JUnitException: Failed to close extension context`), and that class shows the same pattern: drained methods around its `closeAndFlush()` calls, failing lazy-store methods without.

# 5. Authorized isolated diagnostic (ONE, standard, recorded not relied on)

`./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.pipeline.batch.ProfileEnvelopeDispatchTest" --rerun --console=plain` (shell `8da8a714`) → **BUILD SUCCESSFUL in 50s, 20 tests / 0 failures / 0 skipped, exit 0**; XML preserved at `follow-up/n2-isolated-standard-raw/`. Recorded as diagnostic only: **the isolated pass does not fix the new full-suite failure observation**, and it is a single N=1 observation — it shows the class green in isolation but establishes no cause and does not prove that full-suite load is causal. For completeness, the class's observed history: P0 dev-full fail / std-full pass / isolated pass; P1.5 dev-full pass / std-full fail. No retry, no deletion, no suppression, no sleeps.

# 6. Fixture correction proposal (NOT applied — awaiting approval)

Mechanism-neutral barrier that removes the established undrained-store overlap, without exclusion, TempDir suppression, or sleeps:

1. **Registration**: track every store instance created per test — `lazyStore()` registers on creation; introduce a small `resumedStore()` helper (create + register) and replace the seven direct `ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")` call sites (lines 500, 612, 719, 896, 1005, 1080, 1184) with it. Registration list is per-test-instance state (JUnit default `PER_METHOD` lifecycle; the instance-field `@TempDir mangaDir` confirms per-method instances).
2. **Drain**: `@AfterEach fun drainRegisteredStores()` iterating registered stores and calling `store.closeAndFlush()` — the full barrier (`writeDrain.closeAndFlush { journalWriter.drainAndClose() }` + `finally { artifactEngine.cancelRetentionWorkAndJoin() }`). No exception-swallowing: a drain failure should surface as a test failure rather than re-create the race. This mirrors the try/finally `closeAndFlush()` pattern already adopted by the X6 fixture (P1.3b) and partially present in `ChapterTranslationStoreArtifactOpenProbeTest`, centralized per class.
3. **JUnit lifecycle basis for the ordering guarantee**, citable from the N2 stack frames themselves: user `@AfterEach` callbacks execute during `NodeTestTask.executeRecursively`'s after-callback phase, while `@TempDir` deletion runs later in `NodeTestTask.cleanUp` → `TestMethodTestDescriptor.cleanUp` → `JupiterEngineExecutionContext.close` → `AbstractExtensionContext.close` → `NamespacedHierarchicalStore.close` → `TempDirectory$CloseablePath.close` (walkFileTree). The TempDirectory extension registers its `CloseablePath` in the test-method context's store, so deletion is ordered strictly **after** all AfterEachCallbacks — an `@AfterEach` drain is guaranteed to complete before TempDir walks the tree.
4. **Alternative unexcluded holder** (if an extension idiom is preferred): a `StoreDrainExtension : AfterEachCallback` that keeps registered stores in `ExtensionContext.Store` (namespaced key) and drains them in `afterEach(context)`. Same ordering guarantee (AfterEachCallback phase precedes context close/store close), no exclusion, no suppression, and reusable across fixture classes if the coordinator later wants one idiom for both `ProfileEnvelopeDispatchTest` and the undrained `ArtifactOpenProbeTest` lazy-store methods (the latter remains ledgered-baseline and outside this ticket's allowed edits).

Decision requested: approve (a) the @AfterEach registration+drain, or (b) the extension holder — plus whether the `LiteRTMangaInpaintingEngine.kt` pre-existing formatting may be fixed in a separate explicitly-scoped change.

# 7. Parent verdict status

The P1.5 summary's "consolidation passes as no new unexplained regression" phrasing is **corrected to PENDING**: consolidation outcome awaits N2 disposition (full-suite standard-run teardown failure, isolated pass, class untouched) and the fixture-barrier decision above. The four ledgered ArtifactOpenProbe failures remain literal-identical to baseline; the timestamp repair stands; all other P1.5 accounting is unchanged.

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- This follow-up: `scratch/phase-1-cleanup/consolidated-validation/follow-up/{n2-isolated-standard-raw,narrow-devdebug-raw,narrow-standarddebug-raw}/`
- Logs: `commands/…/{8da8a714-eedd-45a6-acb7-bc1f085a92b0,72d14d19-3d31-4496-968e-4690db773397}/output.log`
- P1.5 evidence + baseline comparators: unchanged at `scratch/phase-1-cleanup/consolidated-validation/full-suite/` and `scratch/phase-0-baseline/`

# Next

Stopping for Gemini/Antigravity review of the actual lint diff, logs, and this account. No fixture edits, no LiteRT formatting, no commits/pushes until the barrier decision and N2 disposition.
