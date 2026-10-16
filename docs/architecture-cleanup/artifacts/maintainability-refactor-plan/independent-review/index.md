---
title: "Cold adversarial critique: maintainability refactor plan (recheck)"
kind: review
comments: none
---

# Headline verdict

Following an adversarial recheck of the amended plan artifacts across all five documents ([index](..), [ownership](../ownership), [capabilities](../capabilities), [implementation](../implementation), [validation](../validation)), the technical design is **substantially strengthened and free of blocking mechanism contradiction**s.

The amendments successfully resolve the previously identified contract and synchronization ambiguities:

1. **Registered native finalization:** Native output is published directly into an operation-owned registered slot inside the invocation scope with a `NonCancellable` finalizer before native admission release. Crucially, the plan avoids the unsafe suggestion of recycling every unclaimed bitmap on native exit (which would break concurrent live HTTP overlap); instead, it establishes distinct slot lifecycle states (`producer-owned`, `available-to-the-registered-operation`, `claimed`, `abandoned-awaiting-native-exit`, `disposed`) and an owner completion hook to safely drain abandoned outputs.
2. **First-write store initialization lock table:** Lock tenure and acquisition order for MR-07A are explicitly sequenced (`Registry monitor` ' `Store reservation (releases page mutex)` ' `Document exclusion` ' `ARTIFACT_OPEN_LOCKS acquire once` ' `Store installation under reacquired page mutex`). Recursive acquisition is banned, and asynchronous store initialization remains gated on MR-00 proving reverse-edge elimination.
3. **Publication cancellation resilience:** `CleanedImagePublisher`'s cancellation-to-delete behavior is addressed via a narrow `NonCancellable` commit-decision and internal receipt recording in MR-02, preventing accepted live or candidate files from being prematurely deleted.
4. **Separate-admission fallback constraints:** The two-stage fallback is explicitly disabled unless an `EngineEpoch` borrow spanning OCR-to-inpaint exit and private scratch isolation are empirically proved.
5. **Color estimation consolidation:** Fresh inpaint-time color estimation is recommended for retirement in favor of a single pure pass at the joined render boundary, with a prerequisite caller-parity inventory across all modes.
6. **Fingerprint resolution boundary:** Corrects the target data structure to `LazySourceFingerprints` (blocking Map in `BatchChapterTranslator:1203`) while keeping `BatchExpectedFingerprints` as a pure configuration-evidence data class (`PageWorkPlan.kt:74`) and resolving source hashes at the suspending worker preflight entry.
7. **Manual sequence alignment:** The sequence diagram now explicitly includes lease/source checks, deferred-write drains, and eligibility rechecks prior to provider HTTP entry.
8. **Empirical baseline gates:** Performance triggers (>5% latency, >10% P95) are explicitly defined as empirical investigation gates dependent on MR-00 reference distributions, rather than unverified static claims.

No blocking contradictions remain in the text. Implementation may proceed into Phase MR-00 once authorized.

# Re-evaluated findings & risk classification

| Original Finding | Re-evaluated Risk | Disposition & Status | Remaining Phase Blocker |
| --- | --- | --- | --- |
| **1: Late native result disposal** | Low (Resolved) | Corrected from unverified "permanent leak" assertion. Registered slot inside native invocation + `NonCancellable` exit finalizer + slot state machine + completion hook fully resolves the lifecycle gap without breaking live HTTP provider overlap. | **Gates MR-02 implementation:** Must pass MR-00 prototype race tests covering claim-before-abandon, abandon-before-claim, and lost continuation. |
| **2: Store initialization lock recursion** | Medium (Architectural constraint) | Reclassified from baseline deadlock to an explicit design gate. Sequenced lock table and already-open helper eliminate recursion; token fencing prevents stale disk state from overwriting live memory maps. | **Gates MR-07A implementation:** MR-00 lock inventory must verify zero reverse edges (artifact-open under page mutex) before asynchronous initialization is enabled. |
| **3: Publication cancellation delete** | Low (Resolved) | Reclassified from proven corruption to contract risk. Narrow `NonCancellable` commit-decision and authoritative internal receipt in MR-02 prevent deletion of accepted/referenced images. | **Gates MR-02 subpackage:** Test cancellation racing commit acceptance and lazy hash publication. |
| **4: Separate admission session gap** | Low (Contained fallback) | Separate admission is now disabled by default. Contiguous admission is the baseline. Fallback requires `EngineEpoch` borrow and scratch isolation. | **Blocks fallback activation only:** Contiguous admission remains unblocked. |
| **5: Duplicate color estimation** | Low (Resolved) | Recommends retirement of inpaint-time pass after all-mode consumer parity audit; single pure pass at join/render boundary avoids translation race and redundant work. | **Gates MR-03:** Consumer inventory across manual, resume, Auto, and batch modes. |
| **6: Suspending fingerprint lookup** | Low (Resolved) | Corrected target to `LazySourceFingerprints` Map in `BatchChapterTranslator:1203`. Resolves per page at suspending worker preflight; keeps `PageWorkPlanner` pure. | **Gates MR-06B:** Worker preflight integration tests; preserves contiguous-prefix rules. |
| **7: Sequence diagram barriers** | Low (Resolved) | Sequence diagram in `ownership/index.md` updated to show store/lease verification and deferred-write drain before HTTP dispatch. | None. Fully resolved in spec. |
| **8: Empirical performance baselines** | Low (Resolved) | Clarified as review triggers, not unmeasured allowances. MR-00 baseline noise band collection made an explicit gate. | **Gates MR-00 / MR-08:** Capturing baseline distributions on reference hardware. |

# Verification of cross-document consistency

A cross-document audit confirmed no contradictory residual wording remains:

- **Color estimation:** Both `capabilities/index.md` and `implementation/index.md:MR-03` consistently specify the retirement of the fresh inpaint estimator call subject to a full caller-parity inventory, centralizing execution at the immutable join/render boundary.
- **Fingerprints:** `implementation/index.md:MR-06B` and `validation/index.md` accurately distinguish `LazySourceFingerprints` from the pure `BatchExpectedFingerprints` data class, resolving source evidence per-page at worker entry without whole-chapter barriers.
- **Result slots and native exit:** `ownership/index.md` and `implementation/index.md:MR-02` consistently specify the registered slot lifecycle, native finalizer ordering, and publication receipt handling.

# Verified repository status

- **Baseline commit:** `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`
- **Working tree:** Clean (verified via `git status --porcelain`).
- **Scope compliance:** Planning artifact review only. No production code, build files, tests, or models were modified.
