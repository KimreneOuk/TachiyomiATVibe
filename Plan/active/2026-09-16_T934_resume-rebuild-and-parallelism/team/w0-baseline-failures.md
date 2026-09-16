# W0 baseline — known failures at baseline commit (2026-09-16)

Suite: `:app:testStandardDebugUnitTest` → 1979 completed, 26 failed, 65 skipped.
State: prior session's in-flight work (analysis transport rework, ProfileReconciler
→ GlossarySynthesizer split, provider transport gates, envelope dispatch and
scheduler changes) left mid-flight. Yesterday's device APK was built from this
exact tree; only the E-fix test classes were verified green at the time.

## Failure list (grouped)

- TranslationManagerArtifactReadTest — artifact authority status and reader reads survive a fresh manager
- ChapterCommitPointContractTest — commit points enum contains exactly the 6 required boundaries  ← contract-count test; check whether in-flight work deliberately added a 7th boundary (M6 targeted fsync / group commit) → convert with justification, else fix production
- D7EngineEpochStopRaceTest — engine close with a large grace waits for the translator borrow to end
- NormalMangaIsolationTest — translation-disabled chapter next to an active batch never enters arbitration observation or decode
- StandardLaneMultiPageCompletionTest — fresh standard batch translates every page of a multi-page chapter
- StandardPipelineCoexistenceTest — flagged standard lane runs the real shell end-to-end with full OCR before translate
- T918CancelledBatchRestartTest — cancelled batch restarts from completed work with only the remainder re-run
- ChapterAnalysisPhaseCoordinatorTest — resume reuses checkpoints and never re-sends the persisted chunk prefix; cross-run corpus change pauses typed instead of skipping a stale chunk prefix; missing_only chunk commits its complete subset and never blocks the chapter (3)
- ChapterProfileFreezeCoordinatorTest — full pass freezes the profile and pauses
- OcrPreflightRejectedMidRunDurabilityTest — repeated checkpoint rejections respect the consecutive unresolved attempt cap
- ProfileEnvelopeDispatchTest — tx21 user-edited block skipped; tx21 drift suffix re-plan; process death resume never re-translates/re-OCRs; unchanged resume reuses published envelope plan (ST-11); ambiguous protocol outcome discards whole envelope; terminal transport failure still commits independently complete page (6)
- Stage7FinalizeCoordinatorTest — drained run finalizes and publishes COMPLETE exactly once with overlap inpaint drained
- Stage7FinalizeResumeCoordinatorTest — LI-2 COMPLETE lacking display evidence superseded; ST-14 F-2 FINALIZE record resumes idempotent drain; durable COMPLETE re-dispatches as finished with zero work (3)
- StandardPipelineCoordinatorTest — standard run order/textless/zero AI pointers; re-dispatch interrupted same run id; re-dispatch completed zero work (3)
- P5OutcomeProjectionTest — scheduler exposes the last bounded manual outcome read-only

Hypothesis (to verify, not assume): coexistence/standard-lane failures share one
root cause in the in-flight lane/scheduler changes; profile-envelope and
stage-7 failures cluster in the analysis/envelope rework.
