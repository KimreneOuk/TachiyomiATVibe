---
kind: spec
title: "P1.5 fixture finalization — results and evidence"
comments: none
---

# Fixture-finalization results

Implements `consolidated-validation/fixture-finalization/index.md` per the coordinator's decision: class-local per-test owned-resource registration + `@AfterEach` drain, no reusable extension. Base `b44143f2` + lint-only head `f39cfd2a` (three reviewed lint paths, committed by the coordinator); the fixture diff below is the only uncommitted change in the tree alongside the untracked user report (SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`, unchanged). Sole Gradle owner; no commits/pushes/Phase 2; no production persistence edits; LiteRT formatting explicitly remains recorded baseline debt.

# Exact diff

One file: `app/src/test/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopeDispatchTest.kt` — **+52 / −13** (net +39; 1257→1296 lines).

- Imports +4: `kotlinx.coroutines.NonCancellable`, `kotlinx.coroutines.runBlocking`, `kotlinx.coroutines.withContext`, `org.junit.jupiter.api.AfterEach`.
- Two registration lists: `ownedStores: MutableList<ChapterTranslationStore>`, `ownedArtifactEngines: MutableList<ChapterArtifactEngine>`.
- `@AfterEach fun finalizeOwnedResources(): Unit = runBlocking` — the teardown barrier: stores first (`withContext(NonCancellable) { store.closeAndFlush() }`), then standalone engines (`withContext(NonCancellable) { engine.cancelRetentionWorkAndJoin() }`); **every resource is attempted even if an earlier close fails**; the **first failure is rethrown with later cleanup failures attached via `addSuppressed`**; `NonCancellable` guarantees cancellation cannot silently skip cleanup. `runBlocking` is used solely as the synchronous JUnit teardown boundary for the suspend APIs, per ticket.
- Registrations via `.also`, preserving every body/assertion/reopen timing: `lazyStore()` (1), all seven `ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")` sites (7 → 8 store registrations), `artifactStore()` (1 engine registration, covering all four callers — former lines 322/464/992/1023). The two helper declarations were reformatted only to append `.also`.

# Resource coverage inventory

| Resource kind | Creation sites | Finalization |
| --- | --- | --- |
| `ChapterTranslationStore` (lazy) | `lazyStore()` helper — registered on creation | `closeAndFlush()` (writeDrain + journal drain + engine retention cancel/join) |
| `ChapterTranslationStore` (reopened) | 7 explicit `openArtifact(root(), "Chapter 1.json")` sites (former lines 500, 612, 719, 896, 1005, 1080, 1184) — registered on creation | same |
| Standalone `ChapterArtifactEngine` | `artifactStore()` helper (calls at former lines 322/464/992/1023) — registered on creation; owns its own `retentionScopeJob` | `cancelRetentionWorkAndJoin()` (existing public API, `ChapterArtifactEngine.kt:2283`), after store drains |

# JUnit teardown ordering basis

Citable from the N2 failure stack itself: user `@AfterEach` callbacks execute during `NodeTestTask.executeRecursively`'s after-callback phase, while `@TempDir` deletion runs later in `NodeTestTask.cleanUp` → `TestMethodTestDescriptor.cleanUp` → `JupiterEngineExecutionContext.close` → `AbstractExtensionContext.close` → `NamespacedHierarchicalStore.close` → `TempDirectory$CloseablePath.close` (walkFileTree). The TempDirectory extension registers its `CloseablePath` in the test-method context's store, so deletion is ordered strictly after all AfterEachCallbacks — the drain barrier completes before TempDir walks the tree. Mechanism attribution remains bounded as in the follow-up results: missing finalization was demonstrated; which specific task held or removed the file in the original failure is unproven, and this change makes no runtime-actor claim.

# Verification (shell 082a2522, sequential, fresh compilation via `--rerun`)

| Run | Exit | Outcome |
| --- | --- | --- |
| Narrow `ProfileEnvelopeDispatchTest` DevDebug | 0 | BUILD SUCCESSFUL 1m 13s; **20 tests / 0 failures** (XML preserved) |
| Narrow `ProfileEnvelopeDispatchTest` StandardDebug | 0 | BUILD SUCCESSFUL 49s; 0 failures. **Preservation note (honest):** the in-shell XML copy again hit the missing-parent `cp` pitfall, and the subsequent full Standard run reuses the same task output directory, so the narrow-standard per-method XML was overwritten before it could be copied. The logged outcome (green, exit 0) is the retained evidence; the full run's XML below contains the same class's fresh-compiled Standard outcome. Narrow-dev and full-standard XMLs were recovered afterward from the untouched task output dirs (copy step, not a rerun). |
| ONE full `:app:testStandardDebugUnitTest --rerun`, no filter | 1 (expected) | **329 classes / 2234 tests / 4 failed / 1 skipped**; BUILD FAILED 2m 55s; `FULL_STD_EXIT=1` recorded — known failures retained, nothing retried |

Full-run failures — exactly the four ledgered baseline methods, literal types: `ChapterTranslationStoreArtifactOpenProbeTest` — "lazy store cuts over…" (`java.lang.AssertionError`), "pre-registered pages…" (`io.kotest.assertions.AssertionFailedError`), "artifact-only lazy store…" and "lazy store without parent…" (both `org.junit.platform.commons.JUnitException`). **`ProfileEnvelopeDispatchTest`: 20 tests / 0 failures in the full Standard suite — N2 passed under the drain barrier.** No new failures appeared anywhere.

The original P1.5 full run and the isolated diagnostic remain immutable; this run is a separate justified recheck after a substantive fixture change. Bounds per ticket: a successful run supports the repair but cannot establish universal stability or prove the original actor; the four ledgered ArtifactOpenProbe failures remain tracked, untouched baseline items.

# Prose corrections carried into the record (per coordinator)

- The Phase 0 comparator is the **fresh original full-run XML set (the 670-file ledger comparator: 332 + 332 + 6 class files)** — distinct from the separate **30-file pre-existing stale snapshot**. Earlier wording conflated these.
- The isolated diagnostic pass is a **single N=1 observation**: it shows the class green in isolation, establishes no cause, and does not prove full-suite load is causal.

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- XML: `scratch/phase-1-cleanup/consolidated-validation/fixture-finalization/{narrow-devdebug-raw,full-standard-after-fix-raw}/`
- Log: `commands/…/082a2522-9120-4886-af73-005e9740178e/output.log`
- Immutable comparators: P1.5 `full-suite/` (incl. the failed N2 full-run XML and the isolated diagnostic XML in `follow-up/n2-isolated-standard-raw/`), Phase 0 sets unchanged
- Prose corrections applied to: `artifacts/phase-1-cleanup/consolidated-validation/follow-up/results/index.md`

# Next

Stopping for actual-code Gemini/Antigravity review of the single-file diff. No commits/pushes, no Phase 2, no further runs until disposition.
