---
title: "Phase 3 Slice S5 Pre-Implementation Review: Owner-Cutover Checkpoint Critique (Revision r1)"
kind: review
comments: none
---

# Phase 3 Slice S5 Pre-Implementation Review: Owner-Cutover Checkpoint Critique (Revision r1)

**Verdict: REVISIONS REQUIRED / ADAPTED BEFORE CODE**

This is the independent pre-implementation source audit and review of the Slice S5 preparation proposal ([owner-cutover/preparation/index.md](../index.md)), evaluated against the approved Phase 3 Plan (§§4–17), current codebase interfaces at HEAD `ff21fd15`, and the validation audit rules.

---

## 1. Baseline Pin & Environment State

- **Base Commit**: `ff21fd15cfd7b5d359095ccfc26710452ecc0378` (`fix: retain cleaned images when publication outcome is uncertain`).
- **Slices Status**: S1 (`operation-types`), S2 (`writer-enrollment`), S3 (`timeout-adapter`), and S4 (`publication-policy`) are fully approved and committed locally.
- **Current Workspace State**: Zero source file edits, zero staged changes, zero Gradle commands executed for S5.
- **Role Assignment**: Per directive, Gemini 3.8 Flash (Antigravity) acts as the **Implementer**, with GLM 5.3 Flash/max acting as **Reviewer**. This review checkpoint provides the strict implementation-source orientation and architectural alignment before atomic code authoring begins.

---

## 2. Key Review Findings & Required Corrections

### Finding K1 (Source Bitmap S Custody Across Segment 1 Timeout & Discard)
- **GLM r1 Defect**: The r1 draft asserted that "the boundary receives S inside the OCR-only result and holds it from there." This fails to account for quarantine timeout or abnormal termination in Segment 1 (`withNativeLane`): if Segment 1 times out or throws, `withNativeLane` returns `null` or throws, and the return value of the lambda is completely discarded by the quarantine runner!
- **Required Correction**:
  - Custody of `S` (`decoded.bitmap`) must be registered into an operation-visible holder (scoped to the boundary) *immediately upon decode* inside the native lane, before `withNativeLane` returns.
  - On Segment 1 timeout or failure, the boundary's outer `finally` (which runs strictly after `withNativeLane` real exit is joined) inspects the custody holder and recycles `S` if not transferred.
  - On Segment 1 success, `S` is passed to Segment 2. If inpaint produces `C === S` (alias), `owner.produce(C, CleanedProvenance.INPAINT)` takes ownership, and Segment 2's transfer-aware `finally` recycles `S` only if `!owner.owns(S)`.
  - If Segment 2 never runs (e.g., textless page, OCR failure), the boundary outer `finally` recycles `S` via the custody holder.
  - This eliminates any resource orphan risk without relying on producer-delivered boolean flags.

### Finding K2 (Resume Path Custody & Semantic Attempt-Fencing)
- **GLM r1 Defect**: r1 claimed inline resume paths need no owner change. However, in `SinglePageOnnxPhase.kt:540–582`, inline inpaint produces `C` (which can alias `S`), while line 576 blindly executes `bitmap.recycle()` in `finally`. Furthermore, `renderResumedPage` (lines 641–668) executes `updatePageFromCurrentSnapshot` without attempt fences.
- **Required Correction**:
  - The inline resume path must register `C` into the boundary's `CleanedOwner` (`owner.produce(C, CleanedProvenance.INPAINT)`) before admission returns.
  - The resume `finally` must be transfer-aware: `if (!owner.owns(bitmap)) bitmap.recycle()`.
  - All semantic stage mutations in `renderResumedPage` (READY, RUNNING, error) must route through `attempt.write` to honor closed/cancelled attempt fences and foreign user edits. Physical persistence tails (`persistPageWithOomRecovery`) remain in `finally`.

### Finding K3 (Rejection of GLM Option B: No Blind Fresh Snapshot Patch)
- **GLM r1 Defect**: GLM proposed Option (B), allowing the final post-join render commit to bypass `attempt.write` and execute `store.patchPage` directly with `store.snapshot(pageKey).toPrecondition()`.
- **Verdict**: **STRICTLY REJECTED**. Blindly fetching `store.snapshot(pageKey).toPrecondition()` destroys foreign-writer protection, user-edit fences, and lease checks.
- **Required Correction**:
  - All semantic commits (including the final render commit) must route through `AttemptAuthority`.
  - To handle version drift caused by *our own coalesced lazy persistence receipt* or flush under our own lease, `AttemptAuthority` must verify that the drift was caused strictly by our own known receipt progression:
    1. Lease owner remains our origin (`store.pageLeaseOwner(pageKey) == origin`).
    2. No foreign user edit occurred (`snapshot.userEditedAt` matches initial precondition).
    3. Source fingerprint is unchanged (`snapshot.sourceFingerprint` matches).
    4. Generation is unchanged (`snapshot.generation` matches).
    5. Cleaned image name matches our own attempt's publication.
  - Only when these conditions are met under `attempt.mutex` may the authority advance its internal precondition to absorb own-receipt progression. Any foreign mutation or user edit remains a typed rejection.

### Finding K4 (HTTP Monolith Split & Lifetime Scoping Without DTOs)
- **Correction**:
  - `SinglePageHttpRenderPhase.kt` is split at the line 662 seam into `translateSinglePageHttpOnly` (pre-join) and `renderSinglePageTranslated` (post-join).
  - Lifetime of shared resources is managed at the boundary:
    - Translator borrow: `engines.beginTranslatorUse()` before `translateSinglePageHttpOnly`; `engines.endTranslatorUse()` in the boundary outer `finally`.
    - `governorSpan` opened at entry, settled on first request or in boundary `finally`.
    - `retryBudget` shared across HTTP attempts.
    - Streams registry cleared and engine pool memory reclaimed in boundary outer `finally`.
  - No new DTO framework or container class is introduced; parameters are passed explicitly or via a lightweight private execution context.

### Finding K5 (Retry Admission Wrap Scope)
- **Correction**:
  - In `renderSinglePageTranslated`, when `cleanedBitmap == null`, the reader single-page call to `retryInpaintDownscaled` is wrapped in `withNativeLane(timeoutMs = nativeTimeoutMs, ...)` (Segment 3).
  - The shared delegate in `TranslationPipeline.kt:1343–1352` and the batch translator at line 1242 remain UNADMITTED (as batch operations already run within the batch lane).

### Finding K6 (Changed File Manifest Corrections)
- **Correction**:
  - `CleanedOwner` is defined in `pipeline/AttemptAuthority.kt`; no separate `CleanedOwner.kt` exists.
  - `model/PageTranslationView.kt`: `BubbleMaskRle.immutableCopy()` visibility promoted from `private` to `internal`.
  - `pipeline/execution/PreparedPageBoundary.kt`: flush barrier retained; recycle becomes owner-gated release; OCR persist rides receipt path.
  - `pipeline/CleanedPublication.kt`: enrollment-authority adaptation (boundary scope: `registerReceipt` -> `withContext(NonCancellable) { enqueueLazyPersistence }` -> tail enqueue).
  - `pipeline/TranslationPipeline.kt`: boundary fork/join, authority+owner creation, single T3 budget, retry admitted wrapper, prepared await removal, C1-C7 coordination.
  - `pipeline/SinglePageOnnxPhase.kt`: OCR-only `processSinglePage` (remove `Pair`, `inpaintJob`, detached scope, early estimator), resume owner & transfer-aware finally, attempt-fenced `renderResumedPage`.
  - `pipeline/SinglePageHttpRenderPhase.kt`: split at `:662` seam; eliminate `inpaintJob` await/cancel branches; owner-gated recycles; single authoritative estimator.
  - Coexistence test harness (`CoexistenceBarrier.kt`, `TranslationCoexistenceHarness.kt`) updated with inpaint gate.
  - New test class `SinglePageOverlapBoundaryTest.kt` for full S5 proof matrix.

---

## 3. Review Disposition

The corrected architecture resolves all gaps identified in GLM's draft. The preparation specification (`owner-cutover/preparation/index.md`) is updated to Revision r2 reflecting these binding rules.

Implementation of the atomic cutover in code will proceed once this checkpoint is logged and acknowledged.