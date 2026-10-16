---
kind: review
title: "S5 r1 independent re-review received at migration freeze"
comments: none
---

# Revisions required ? no S5 validation performed

Recorded by the coordinator from GLM independent review message `25dd1179-f769-44fc-bb07-61964d19f2e5`, received during the user-requested laptop snapshot. Reviewer respected the no-write migration hold; this preserves the substantive message for the other laptop. Parent source review independently confirms the principal blockers. The earlier main review artifact remains historical r0.

## Remaining corrections

1. Fresh OCR cleanup still takes/recycles the source slot before the fork when inlineInpaint=false. Segment 2 then sees no source and skips normal inpainting. Retain source custody for the actual fresh producer; terminate it only on inline/terminal paths or boundary cleanup.
2. CertifiedArtifactProgress uses Int generation and Long? dependency fingerprint instead of actual Long and String?. Fix real production types before compiling.
3. Native async belongs to the outer coroutineScope, outside withTimeout. Timer expiry does not cancel it. Make T3 cancellation reach that child while quarantine still waits for actual native exit and late publication stays suppressed.
4. Render borrow precedes a suspending publish before its try/finally. Cancellation can strand the borrow and hang owner.release. Cover the entire borrow lifetime.
5. Prepared owner/custody cleanup exists only on the successful helper path. Add enclosing nested cleanup for timeout/null/throw/persistence-rejection paths while retaining outermost lease release.
6. Artifact capability binds pageKey but still lacks store identity. Prevent cross-store stamping/absorption without adding a registry.
7. Remaining OOM, drain-failure, prepared failure and prepared native-timeout writes still bypass attempt authority.
8. The lease test's artifactAuthorityStore does not enable lazy persistence; the direct path stamps immediately, so the claimed pre-flush null check and real-lazy coverage are wrong. Use an actual lazy fixture and real queue/drain; use sanctioned foreign mutations and typed rejection checks instead of hardcoded pageVersion=999.
9. The overlap test supplies its own translation-start flag instead of observing actual provider arrival. Parent confirms it now invokes pipeline, but this alone does not prove overlap. Gate actual provider/inpaint arrival and demonstrate rendering waits in both completion orders. Keep all controlled lifetimes in finally before the first launch/assertion; recheck cancellation-test teardown.

## Verified fixes to preserve

Required store parameter and private wrapper forwarding, real mask constructor, reader lease release, separate branch drafts, lexical enrollment borrow finally, owner-registered receipt before NC enrollment, actual authority-mediated hash patch result, typed SoftNull, retry deposit inside admission, bounded scalar metadata, direct stamping only on actual persist, failed-terminal guard, deleted legacy delegator, and many native status writes are corrected. Do not restart or undo these.

## Execution hold

The proposed generic Debug task / wrapper-only Kill / overwritable-log launchers were rejected. No Gradle, source correction, staging or publication was performed by the reviewer. The coordinator will commit this explicitly unfinished checkpoint solely because the user requested transfer; completion and release gates are not waived.
