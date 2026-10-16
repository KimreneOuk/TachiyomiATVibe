---
title: "P1.1 — delete orphan helper seams and co-locate resume policy"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Exact slice

Completed and independently accepted by Gemini 3.8 Flash/high through the previously successful Antigravity session `871251fc-68c9-446b-bc64-8c15de349940`. [Review](review) found no blocking code issues. Coordinator committed exactly the six reviewed paths locally as `b452499c` (`refactor(translation): remove orphan helpers and co-locate resume policy`). No remote push yet; original report remains untracked and unchanged. Earlier infrastructure and uncommitted checkpoints below are historical.

Assigned implementation owner: GLM 5.3 Flash/max/OpenCode agent `3b81dcb4-fa80-48c4-9e9b-20b49c3a10d0`, sole Gradle owner for this slice. Coordinator handles artifacts; independent Gemini review follows actual completed changes.

On `architecture-cleanup`, recheck repository-wide references including non-source/keep rules before changing these symbols:

| Path | Action |
| --- | --- |
| app/src/main/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecovery.kt | Delete test-only orphan helper; no production callers |
| app/src/test/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecoveryTest.kt | Delete only tests of that retired helper; no flakiness claim |
| app/src/main/java/eu/kanade/translation/pipeline/batch/recovery/BatchResumePolicy.kt | Move unchanged pure policy into BatchResumeGateDecider.kt as private file-level object; delete standalone file |
| app/src/main/java/eu/kanade/translation/pipeline/batch/recovery/BatchResumeGateDecider.kt | Co-locate policy/imports, preserving all decisions and tests |
| app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt | Remove unused private markBatchTranslationFailed and imports exclusively used by it |
| app/src/main/java/eu/kanade/translation/engines/translator/analysis/AnalysisChunkExecutor.kt | Remove unused isStructuralAnalysisRefusal and batchSublimitKeyFor wrappers/stale test-seam KDoc, retain direct live helper calls |

Expected first-slice file delta: two production deletions, one test deletion, no additions. Under requested translation scopes, 290→288 main source files and 318→317 ordinary unit test sources if rechecked inventory agrees.

# Validation and limits

Current review recovery: three fresh Antigravity startup attempts across two profiles failed before review. User initially selected an OpenCode fallback, which rejected the provider-prefixed Gemini ID; then explicitly corrected the requirement to Gemini through Antigravity. GitHub Copilot/OpenCode attempt is archived and stopped. Coordinator reactivated last-known successful Antigravity reviewer `871251fc-68c9-446b-bc64-8c15de349940` for actual P1.1 code review. No verdict yet; P1.2 held, delivered code/tests/evidence unchanged.

Bounded read-only infrastructure inspection found host-log initialization/session model-discovery timeouts (90-second bounds), installed Antigravity runtime pack 1.3.0, and no active Antigravity/native-helper process at the inspection time. These observations do not establish a root cause. No authentication/configuration files, app installs or user processes were modified. Standalone runtime usage probes were ended; no manual helper remains owned by the coordinator.

Review infrastructure note: Antigravity reviewer `3c3570d3-cfc1-4ec1-a620-364ece044abd` failed startup twice and delivered no verdict; it is archived. Coordinator dispatched fresh reviewer `01bcaf09-4d28-4082-978e-646152035244`, still Gemini 3.8 Flash/high/Antigravity, using the other authenticated profile. These are review-startup failures, not compiler/test failures or acceptance; later slices remain held pending actual review.

Implementation checkpoint: slice delivered; 42 affected DevDebug tests passed before and after, with edited main/test compilation. Policy visibility stays `internal`, preserving two direct assertions in StandardPipelineCoexistenceTest that the original manifest missed. No behavior or test rewrite was required. Independent reviewer `3c3570d3-cfc1-4ec1-a620-364ece044abd` (Gemini 3.8 Flash/high/Antigravity) is reviewing the actual six-file diff. Ticket remains in progress until verdict and any fixes.

Read scoped build/test requirements and execute affected JVM tests before/after where needed: BatchResumeGateDeciderTest, BatchResumeAndDisplayContractTest, relevant analysis faithfulness/sublimit tests and pipeline compilation. Formatting checks scoped to changed files; no mass formatter edits. Preserve the user's report SHA and original baseline XML; never fix unrelated failures in this slice.

No production algorithm, scheduler, native guard, quarantine, store/persistence or provider contract changes. No commits/pushes during implementation; coordinator integrates reviewed slices. Record actual outcomes in a child spec and request independent review before proceeding to rendering-island deletion.
