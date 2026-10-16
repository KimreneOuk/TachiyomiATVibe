---
title: "Close the test manifest reader and rebaseline"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Concrete repair from accepted investigation

The user authorized execution on 15 October 2026 by instructing us to proceed until Phase 3. Complete this prerequisite and prepare Phase 3 entry; no native/page ownership implementation is included in this test-only slice. The completed [investigation](..) supports one test-only repair: scope readManifest()'s File.inputStream with use in app/src/test/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt. Preserve all fourteen test bodies/assertions/store lifecycle and production code. Saved helper-only patch is retained under scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-helper-only/source/helper-only.patch.

Reapply that exact minimal helper change, verify focused class14/0 bothflavors, independently review actual diff, then run full app variants to establish the replacement baseline before dependent Phase3 work. Preserve every failed run and investigate unexpected outcomes instead of retrying unchanged. Existing LiteRT formatting, JDK17 parity and device/native evidence remain separate. No new tests, source files, production store fixes or test-assertion weakening are needed. The implementation preference is GPT6Luna/max/fast; review remains Gemini3.8Flash/high via Antigravity.

When this follow-up is authorized and completed/reviewed, commit and push the repair to origin/architecture-cleanup under the user's standing branch publication instruction, verify remote HEAD and record the new baseline. The investigation itself left no repository change to publish.

# Execution order

Starting published HEAD: 999bb9be. Apply the accepted helper-only patch; preserve every test body, assertion and store lifecycle call. The original14/4 baseline and diagnostic14/0 evidence are already retained; do not rerun the unchanged failing baseline. Fresh focused14-test checks in both flavors and actual single-file diff go to Gemini. Freeze code during review. After focused acceptance, execute fresh full app DevDebug and StandardDebug suites with separately preserved XML/logs, compare against Phase2 332classes/2254tests, review final evidence, commit/push the accepted one-file repair and verify remote HEAD. No domain/model/device/toolchain changes or extra broad test runs unless a new failure justifies them.

Then prepare the Phase3 entry packet: repaired baseline, remaining physical-native/device/JDK/formatting gaps, current sequential-page ownership scope and unsettled resource-boundary decisions. Do not silently select an old detached-holder or disk-only handoff design, or begin a native refactor as part of this fix.

# Current implementer selection

Latest user instruction switches implementation back to GLM5.3Flash/max via OpenCode (zai-coding-plan:glm-5.3-flash). Same agent/workspace/task; configuration applies next turn without interrupting active Gradle. Gemini3.8Flash/high via Antigravity remains reviewer. This supersedes the Luna preference for subsequent implementation turns.

# Actual-code acceptance and full rebaseline

Gemini/Antigravity accepted the exact one-file +3/-3 reader-use change in [review](review), with all14 original method identities and test bodies unchanged, focused14/0 bothflavors and only baseline LiteRT formatting debt. GLM5.3Flash/max now owns the two full app suite runs; a reviewer message naming Luna is historical wording, not the current implementer selection. No domain rerun, assembly rerun or new formatting run is needed for the frozen test-only patch absent a new finding. Full results require evidence review before parent commit/push and Phase3 entry closure.

# Final disposition

Completed and published at `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec` after focused code review and clean full application rebaseline. The unexpected trace wait defect was separately diagnosed and minimally repaired; final integration scope is two test files. [Publication and replacement baseline](closure) supersedes the historical pending steps above. Phase 3 entry is ready; implementation has not started.
