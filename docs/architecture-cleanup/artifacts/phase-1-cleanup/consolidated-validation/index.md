---
title: "P1.5 — consolidated host validation and exact cleanup accounting"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Baseline and objective

Completed and independently accepted after [bounded lint/provenance follow-up](follow-up) and [fixture finalization](fixture-finalization). Final publication is `dab5c124` on local/remote architecture-cleanup. [Phase 1 closure](../closure) records authoritative final counts, known failures and deferred gates; the original validation objectives and initial run remain historical evidence.

Validate reviewed Phase1 commits on architecture-cleanup at b44143f2 against the immutable Phase0 baseline4de121bf. Scope closes the cleanup slice, not the whole rebuild. Five local reviewed commits exist, remote branch still at baseline. Child performs validation/evidence capture; coordinator handles eventual publication. Original Phase0 XML/logs and untracked user report remain unchanged.

# Required checks

1. Record exact HEAD, branch/upstream, tracked cleanliness, user report SHA256 and current toolchain. No install/JDK/SDK/model/environment changes; localJDK21 vs CI17 remains a limitation, not a proven cause or waived release check.
2. Run :app:assembleDevDebug and :app:assembleStandardDebug with normal cached builds; record full commands/logs/outcomes. Changed main/test sources already compiled fresh per slice; no pointless clean/model-fetch operation.
3. Run full :app:testDevDebugUnitTest and :app:testStandardDebugUnitTest individually/sequentially with fresh task execution (--rerun), no --tests filter, then :domain:testDebugUnitTest. Capture XML separately immediately after each task even if it fails; never let fail-fast skip a flavor or retry away a result. All remaining baseline failing methods must remain visible.
4. Run :app:spotlessCheck as read-only check and record findings. No broad formatting edits. If findings affect changed lines, identify exact file/rule and whether present at baseline; unrelated untouched lint must be reported separately. Return meaningful fix requests rather than modifying sources in this ticket.
5. Compare preserved full-suite ledger by literal class/method/failure type/message. One baseline timestamp assertion is repaired; four ArtifactOpenProbe methods and ProfileEnvelopeDispatch remain untouched. Distinguish same signature from a new failure and isolated-pass hypothesis; do not claim all-green, proven stability or resolved storage/native ownership.
6. Reconcile full source counts/diff manifest including rename/replacement: main290→284, ordinary translation unit318→315, testDebug1 and androidTest6 unchanged. Git reports ten deleted paths/one added test path because the partially retained test suite is represented as replacement; semantic retirement is six main files plus three unit files, and one mixed unit suite rename with zero count impact. No newly introduced production files.

# Expected accounting, not fabricated outcomes

Current cumulative diff4de121bf→b44143f2:22paths,+589/−4748lines,4,159net removed. Predicted full app test delta:2261−4orphan−22render+1timestamp+7production replacements−2mirrors−7adaptive=2234 executions perflavor; expected XML class count332−3retired classes=329. Domain66 tests untouched. Use actual discovered XML as authoritative if dynamic test discovery differs; explain any difference rather than forcing forecast. Test-source files include fixtures and cannot be equated to executed classes/tests.

# Preservation gates and limits

No source/test/config modifications, commits/pushes, broad retries, helper agents, device/paid-provider/native calls, model operations or toolchain installs. Stale harness comment naming retired adapter is recorded for later docs cleanup. Report logs/XML/results/manifest at results/index.md and stop for independent Gemini/Antigravity evidence review. Device performance/JNI cancellation/release parity remains explicitly deferred. Runtime store-contract/handle-owner discrimination remains required before Phase3/4 changes. Consolidation can pass as no new unexplained regression while known baseline failures remain; state that plainly.
