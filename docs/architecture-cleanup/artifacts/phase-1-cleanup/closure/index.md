---
title: "Phase 1 closure — reviewed cleanup and remaining gates"
kind: spec
comments: none
---

# Closure decision

Phase 1 host cleanup is complete following Gemini 3.8 Flash/high/Antigravity's final independent acceptance. Seven reviewed commits are published to `origin/architecture-cleanup`; local and remote agree at `dab5c124`. The audited starting revision remains `4de121bf`. Phase 2 has not started.

The final fixture repair registers every store and standalone artifact engine owned by ProfileEnvelopeDispatchTest and drains them after each test, before temporary-directory cleanup. All assertions and reopen order remain intact. Missing finalization was established; the exact actor in the earlier N2 failure remains unproven. The original failed run and isolated diagnostic are preserved, rather than replaced by later successful observations.

# Final accounting

| Scope | Baseline | Final | Net reduction |
| --- | --- | --- | --- |
| Translation main Kotlin/Java sources | 290 | 284 | 6 |
| Translation ordinary unit-test Kotlin files | 318 | 315 | 3 |
| Translation testDebug files | 1 | 1 | 0 |
| Translation androidTest files | 6 | 6 | 0 |

The authoritative baseline-to-final Git diff spans 23 paths: 641 insertions and 4,763 deletions, a net reduction of 4,122 lines. Git lists 10 deleted paths, one added test path and 12 modified paths. One deletion/addition pair represents the replacement of AdaptiveKnobControllerTest with the retained DevicePagePermitGateTest; semantic retirement is six production files and three unit-test files, with no new production files. The file count drops by nine overall.

# Verification and limits

Both debug assembles succeeded. The consolidated app suites discover 329 classes and 2,234 tests per flavor; domain passes 66/66. After the substantive fixture repair, the one full StandardDebug recheck preserves 329 XML files, 2,234 executions, four failures, zero errors and one skip. ProfileEnvelopeDispatchTest passes all 20 methods, including N2. Narrow DevDebug passes 20/20. Narrow StandardDebug logged success; its per-method XML was overwritten before preservation, so the subsequent complete StandardDebug XML supplies retained evidence for that class. No extra run was performed merely to recreate that narrower XML.

Four ChapterTranslationStoreArtifactOpenProbeTest methods remain failures with baseline types/messages: two assertion mismatches and two TempDir cleanup failures. These remain required investigation gates before Phase 3/4 storage-dependent changes; closure does not assert production persistence correctness. The earlier protocol coroutine timeout was not repaired or proven eliminated by this fixture change.

All three cleanup-introduced lint issues are fixed. App spotlessCheck still fails solely on the unchanged LiteRTMangaInpaintingEngine wrapping debt observed at baseline; OCR/LiteRT code was left untouched. Local JDK 21 versus CI JDK 17, release/CI parity, device performance distributions and physical JNI cancellation evidence remain outstanding. Phone work is deferred by the user, not treated as verified.

The user's untracked architecture report remains outside commits, with SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` unchanged. The tracked working tree is clean.

# Evidence and next phase

- [Consolidated review](../consolidated-validation/review)
- [Fixture implementation evidence](../consolidated-validation/fixture-finalization/results)
- [Fixture final review](../consolidated-validation/fixture-finalization/review)
- [Original Phase 0 evidence](../../phase-0-baseline)

The next planned slice is Phase 2: typed rejection outcomes, package dependency corrections/enforcement, and scoped suspending APIs/fingerprint preparation. Its exact tickets and OCR/LiteRT seam ownership must be reconciled with the simplicity-first plan before implementation. Phase 1 closure authorizes no new model execution changes or persistence redesign.
