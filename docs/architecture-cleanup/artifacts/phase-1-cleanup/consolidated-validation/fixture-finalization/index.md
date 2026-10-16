---
title: "P1.5 fixture finalization — drain envelope-test owned resources"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Decision and scope

Completed: actual single-file test-only diff accepted by Gemini/Antigravity; committed as `dab5c124` and pushed to origin/architecture-cleanup. Full StandardDebug recheck has only four tracked ArtifactOpenProbe baseline failures; the envelope class passes 20/20. [Results](results) and [final review](review) preserve limits and the missing narrow Standard XML disclosure.

The three introduced lint fixes are verified locally and await independent review. Pre-existing LiteRT formatting remains recorded baseline debt, outside Phase 1; no LiteRT formatting change is authorized.

Repair the demonstrated lifecycle omission in `app/src/test/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopeDispatchTest.kt`. Missing finalization is established; attribution of the observed N2 TempDir failure to a particular task or descriptor remains unproven. The isolated pass does not erase the failed full-suite observation.

Use class-local per-test ownership and `@AfterEach` finalization, rather than creating a reusable extension. Register every lazy and reopened ChapterTranslationStore when created. Inventory and finalize standalone ChapterArtifactEngine instances created by artifactStore() as well, using their existing retention cancellation/join API. Preserve all test bodies, assertions, process-reopen ordering, and production implementations; finalization runs after the body, including failed bodies. No new files, dependencies, sleeps, retries, exclusions, or TempDir suppression.

The synchronous JUnit callback may use runBlocking solely as the test teardown boundary for suspending closeAndFlush. Drain all registered resources even if one fails; preserve the first failure and suppress subsequent cleanup failures, then rethrow. Cancellation must not silently skip cleanup. Keep this small and local; no new framework or production lifecycle seam.

# Verification and evidence

GLM remains sole Gradle owner. Run the 20-method class in both flavors with fresh compilation, preserving separate XML. Then run the full StandardDebug suite once after the substantive fixture change, preserving all outcomes, including known failures; do not retry to obtain green. The original full run and isolated diagnostic remain immutable. A successful run supports the repair but cannot establish universal stability or prove the original actor.

Record exact diff, resource allocation coverage, JUnit teardown ordering basis, checks and limits in results/index.md. Stop for actual-code Gemini/Antigravity review. No commits, pushes, Phase 2, production persistence edits, device/provider/toolchain operations, or unrelated baseline-failure fixes.
