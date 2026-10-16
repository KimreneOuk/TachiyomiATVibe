---
title: "S5 actual-cutover cold code review (GLM, independent)"
kind: spec
comments: none
---

# Addendum r2 (parent corrections thread follow-up — all source-verified)

**A3 — NEW compile blocker: private wrapper signature mismatch.** The boundary's segment-1 call (Pipeline ~:598) invokes the PRIVATE wrapper `translateSinglePageOnnx` (Pipeline :1459) with `adjustedResume`/`sourceCustody`/`owner`/`attempt` named args; the wrapper still has the OLD signature and forwards NONE of them → does not compile. General instruction from parent for the fix pass: audit EVERY phase/public wrapper caller for unchanged-signature compile failures and dead parameters (the phase function gained params; wrappers/callers were not all updated).

**B4 remedy — CORRECTED.** The enroll borrow is closed in the CALLER's (enroll's) own try/finally; the WORKER lifetime is covered by the owner-registered STORE receipt; the work lambda must NOT close the same borrow (double-close if both). `owner.release()` already awaits receipts FIRST, then zero borrows — that ordering safely covers the no-work/receipt-false case. The receipt must NEVER be completed false by the caller after a successful queue — only the store decides receipt outcomes.

**B9 — corrected to CONDITIONAL danger, not impossibility.** My "owner never owns the SOURCE bitmap" claim is FALSE: alias C===S is possible (an engine may return its input bitmap). The resume-branch finally's `if (owner == null || !owner.owns(bitmap))` recycle is a conditional hazard: when owner does NOT own the bitmap, it recycles WITHOUT `custody.take()` (double-recycle with boundary close); when owner DOES own an aliased S, the slot silently retains an owner-held bitmap. Also: as delivered, this code path is not safe-proof in the working tree (it fails compile via A3/A1) — the complete call chain must be re-verified after the fix pass.

**B7 extension — the chain gap is NOT only the hash worker.** ALL OCR admission/status writes in the Onnx phase (e.g. :330 `updatePageFromCurrentSnapshot` admission/status writes) are still DIRECT store writes outside the authority → the authority's INITIAL precondition is stale before OCR even begins (the first `attempt.write` would false-reject on pageVersion once any direct write bumped it). Gemini instructed: fix ALL S5 writes to the authority chain, not just the enroll/late paths.

Status: combined corrections delivered to Gemini (`15fcdd07`); code editing resumes, NO Gradle; review HOLDS until the revised handoff. Frontmatter kind corrected spec→review this edit.

Reviewed cold against the working tree at base `ff21fd15` (11 modified tracked files + new `SinglePageOverlapBoundaryTest.kt`). Every finding below was verified against actual source this session; handoff claims were not treated as evidence. One combined bounded disposition — no Gradle, no edits by reviewer. Verified-good items listed at the end so Gemini does not churn them.

# P0 — Compile blockers

**A1 — enroll call omits required `store` parameter.** `CleanedPublication.enrollCleanedPersistence` (:251 signature) requires `store: ChapterTranslationStore` with no default; the native-branch call in `TranslationPipeline` (fork site, `owner = owner, attempt = attempt, ...`) passes manga/chapter/source/pageKey/pageTranslation/owner/attempt/deferredPublications but NO `store` → does not compile. (The `persistOnnxCleanedImage` wrapper passes `store = result.store`; the direct fork-site call does not.)

**A2 — invented constructors in the new test.** `SinglePageOverlapBoundaryTest` :326-331 builds `BubbleMaskRle(width, height, counts=intArrayOf(...), boxes=listOf(InpaintMaskBox(x=0,y=0,width=10,height=10)))`. Actual: `BubbleMaskRle(width, height, bounds: List<Int>, runs: List<Int>, score: Float)` with `require(bounds.size == 4 && runs.size % 2 == 0)`; `InpaintMaskBox(x1, y1, x2, y2, label)`. `counts`/`boxes`/`x/y/width/height` do not exist → compile error (and the require() would fail the values anyway).

# P1 — Correctness majors

**B1 — reader page lease LEAK at the native boundary.** The old boundary `finally { releaseReaderPageLease(leaseStore, pageKey, origin) }` was removed in the rewrite; the new boundary finally chain (drain → streamRegistry.clearPage → custody.close → owner.release → endTranslatorUse → reclaim) contains NO lease release. Remaining release sites (:1198, :1394) are AUTO-origin (prepare/prepared-retry) only. Every reader native attempt acquires at :496 and never releases → all later attempts on the page rejected (lease token required / ALREADY_OWNED) until reset. Restore the lease release in the outer boundary finally (per approved plan it is the OUTERMOST release so every failure path still reaches it).

**B2 — one shared mutable `PageTranslation` across both fork branches.** Native branch inpaints against `onnxResult.pageTranslation`, HTTP branch takes `ctx = onnxResult`, `enrollCleanedPersistence` additionally writes fields DIRECTLY on that same object after its store patch, and the retry wrapper mutates it — no branch-private deep copy at the fork (explicit plan requirement; also the direct enroll writes are unsynchronized cross-branch mutation). Fork must hand each branch its own copy and merge through the authority only.

**B3 — prepared path regression: fresh pages are never inpainted nor prepared.** `processSinglePage` no longer forks `inpaintJob` (return type now `PageTranslation`), `prepareSinglePage` passes no custody/owner/attempt to `translateSinglePageOnnx`, and the old `onnxResult.inpaintJob.await()` block was replaced by `val onnxReadyResult = onnxResult`. Result: `cleanedBitmap` is never produced on the prepared path → `persistOnnxCleanedImage` short-circuits (cleaned == null) → durability gate fails → pages with blocks never become prepared. `OnnxPhaseResult.inpaintJob` is now written `null` at :623 with zero producers/consumers — the "handoff" framing is false; remove the field or restore a real producer/consumer. The prepared path needs an explicit decision implemented end-to-end (custody/owner/attempt wiring or an inline admitted inpaint), not a removed await.

**B4 — `owner.release()` hang via leaked borrow count.** `enrollCleanedPersistence` calls `owner.borrow()` BEFORE the suspending `attempt.write` with no try/finally (cancellation there strands the borrow), and `closeBorrow()` runs only inside the enqueued work lambda's finally. On any enqueue-reject path — defunct, generation mismatch, no artifact target, `!writeDrain.isDrainScopeActive()` (`enqueueLazyPersistence` :1302-1307 completes the receipt FALSE but never runs the work) — the borrow is never closed, so the boundary `owner.release()` blocks forever at `openBorrows.first { it == 0 }` (:304). Required: borrow/closeBorrow paired in one try/finally inside enroll around EVERYTHING after the borrow (write, enqueue), and the work-lambda close kept as the second half of that pairing — the count must reach zero on every path, including never-queued.

**B5 — shared T3 budget broken.** `withTimeout(singlePageTimeoutMs)` wraps ONLY the HTTP child; the post-join `renderSinglePageTranslated` (retry segment + render + commit) runs with NO budget (the old code wrapped translate+render in one `withTimeoutOrNull(singlePageTimeoutMs)`). The plan's ONE shared T3 must cover HTTP + post-join render/retry/commit; native child keeps its own nativeTimeoutMs (that part matches native 1 / inpaint 2).

**B6 — C6 soft-null conflated with quarantine timeout.** Native branch: `if (inpaintProduced != null) Success else TimedOut` — `withNativeLane` returns null BOTH on a real timeout AND when the engine's inpaint soft-returns null. A genuine C6 soft failure is disposed as a native timeout (retryCount/attemptCount increments + FAILED marks via the C1/C2 marker). Distinguish: block-internal completion (even null) ⇒ C6 soft-failure disposition; only the withNativeLane timeout callback ⇒ TimedOut (e.g. have the block set a completed-flag the branch reads, or type the lane result).

**B7 — authority chain bypassed inside the enroll work.** Both store patches in the work lambda (hash record and failure mark) call `store.patchPage(expected = store.snapshot(pageKey).toPrecondition(), ...)` — fresh-snapshot, result IGNORED, and the commit arm returns a FABRICATED `PatchResult.Accepted(accepted.snapshot)`. These bump pageVersion outside the authority with no lease passed (public 4-arg overload → lease null) → the authority's precondition goes stale and the NEXT `attempt.write` false-rejects; the lease absorption cannot help because nothing stamps. Route both through `attempt.write` (or pass the lease + honor the result). The fabricated Accepted also masks real publication outcomes from the receipt.

# P2 — Correctness, second tier

**B8 — retry output orphan + unmediated semantic write.** In `renderSinglePageTranslated`'s retry arm, `retryInpaintDownscaled` (admitted wrapper) produces a Bitmap-bearing `PageTranslation` inside the quarantine lane; `owner.replace(...)` happens only after return. On retry timeout the admitted wrapper's fallback `pageTranslation.apply { cleanedBitmap = null; inpaintStatus = FAILED; errorMessage = ... }` mutates the SHARED object (see B2), nulls a produced bitmap WITHOUT recycle (leak), and writes semantic status with no store patch. The produced bitmap must be produced INTO the owner (or custody) inside the lane and recycled exactly once; timeout marking must go through the attempt.

**B9 — resume custody double-recycle hazard.** The resume inline-inpaint branch recycles the decode bitmap in its finally (:584-588) under `if (owner == null || !owner.owns(bitmap))` — the owner NEVER owns the SOURCE bitmap (it owns inpaint output), so the condition is always true and recycles while `sourceCustody` may still hold the reference. Today unreachable-with-custody (resume callers pass custody=null; register only happens when custody != null), but it is one wiring change from a double recycle. The recycle must be `sourceCustody?.take()`-based like the fresh path.

**B10 — AttemptArtifactLease deviations from the accepted proposal.** (a) Retains the WHOLE `PageSnapshot` (store :113-116) — accepted design requires bounded page-bound metadata (generation, livePageVersion, artifactPageVersion, candidateGenerationId, dependencyFingerprint); the snapshot drags the page draft + fingerprint list into the lease. (b) No pageKey/store binding — a miswired lease/page pair is undetectable. (c) The DIRECT branch stamps `lastOwnDrainSnapshot` whenever `artifactAccepted == true` (:3782-3784) — including the short-circuit `else -> true` where NO artifact write happened — certifying an unverified manifest state as own-drain, which the next write would then ABSORB even if a foreign change landed. Stamp only after a real `persistArtifactMutationLocked` success (the lazy-diff stamp site :1409-1412 is correctly post-success; give it the same bounded-record + binding treatment).

**B11 — `isPreparedPageTerminal` dropped the FAILED guard (unrequested semantic change).** Old: any FAILED stage ⇒ never terminal (prepare returns null; retries flow). New: `hasRenderedResult || isTextlessTerminal || (ocrStatus READY && blocks empty)` — e.g. FAILED inpaint + textless page now reads terminal; failure attribution/retry semantics change without an approved decision. Revert to the approved semantics (failed ⇒ not terminal).

# P3 — Contract/policy violations

**C1 — legacy `translateSinglePageHttpRender` retained AND live.** The removal contract was one atomic tree; the delegator (`SinglePageHttpRenderPhase`:983) is still called from `TranslationPipeline`:1364 (AUTO prepared-retry) via the :1481/:1490 wrapper, attempt-less — so that production path still uses fresh-snapshot commits with the old :1005-style fence-nulling while the reader path uses the authority. Two divergent commit policies in production. Move the AUTO retry path onto the split+attempt and delete the delegator.

**C2 — inconsistent failure marking.** HTTP branch `Failed` → `markPageFailed(...)` (non-authority writer) while native branch `Failed` → `attempt.write`. All attempt-scope semantic writes must be authority-mediated.

**C3 — test suite does not prove what it claims.** (i) Test 1 "true concurrent overlap" forks two `async` blocks exchanging strings around CompletableDeferreds — pure coroutine mechanics, zero production code; NO test invokes `translateSinglePageNative`, the coexistence harness, `withNativeLane`, or the HTTP phase, so there is no orchestration/overlap proof of the cutover. (ii) The lease test uses a MEMORY store (artifact early-return true; no manifest), MANUALLY stamps `lease.lastOwnDrainSnapshot`, and — despite its name — tests NO foreign-mutation case and NO rejection assertion; it passes even with the absorption mechanism deleted. (iii) Test 3 has no lifetime try/finally: the receipt is completed manually after cancel; on any earlier assertion failure `owner.release()` never returns and the external `SupervisorJob` scope (never cancelled) leaks — hang until the 60s timeout. Required per standing policy: real harness-driven overlap tests (guarded paths), a DURABLE-store drain-driven lease test (real queue → flush → stamp; foreign-writer rejection case), lifetime finally + NonCancellable receipt completion in finally, `scope.cancel()` in finally.

# P4 — Minor

- CRLF line endings introduced in six edited files (repo stores LF; git warns normalization will rewrite them) — normalize to LF before commit.
- `DeferredPagePublications.drain()` alias added for "nested lifecycle cleanup invocations" — the boundary already calls `drainDeferredPublications`; keep one name.
- `finalizePostOcrStage(..., inpaintAlreadyRan = false)` is now hard-coded at :1228 (and :1033): textless pages WITH mask boxes now get `inpaintStatus = PENDING` (queue-visible transient) where the old code left it unset — confirm intended, else pass the real value.
- No double-decode introduced: retry reuses `ctx.streams`/`ctx.decoded` as before (verified).
- Paid-call ledger (`runLedgerWrapped`/`recordAttemptStart`/`resolveAttempt`) and governor spans preserved; paid-call policy unchanged (verified).

# Verified-good (do not churn)

- `patchPage` public API preserved via internal 5-arg overload; public callers unaffected (lease null ⇒ absorption inert).
- Store absorption block (:1956-1989) implements the corrected proposal: live invariants (generation, live pageVersion) + exact three-field compare against the certified record + same-expression dependency derivation incl. the page-snapshot fallback, no refresh on mismatch, ALL fences (artifact/candidate/dependency/block/lease both arms) evaluated against `effective`.
- Lazy-diff stamp only after persist success; effective-precondition semantics flow through `publishLocked(..., effective, ...)` (lazy queue re-snapshots under the same mutex hold — equivalent-and-current).
- Boundary finally nesting: each release individually nested, streamRegistry clear included, reclaim failure logged not swallowed, order drain → registry → custody → owner → engines → reclaim.
- Custody discipline on the fresh path: register at decode, `take()` inside the segment-2 guarded block, recycle in the block finally only when the owner does not hold it, boundary `close()` as backstop — exactly-once in the exercised paths.
- Governor settle: first-invocation settle retained AND phase-finally settle added (matches approved semantics, not settle-on-response).
- C1/C2 post-join marker via `attempt.markTimeout` with store-truth-outranks-timer guards; C7 loser rejection via closed attempt ("attempt already closed").
- `SourceBitmapCustody` (AtomicReference register/take/close, no boolean flag) matches the parent-instructed slot discipline; unit semantics correct.
- Native branch captures ordinary failure INSIDE the child (`NativeBranchOutcome.Failed`) and rethrows only cancellation; structured `coroutineScope` cancels the sibling on child cancellation.

# Disposition

Combined single-pass fix list: A1+A2 (compile), B1-B7 (majors), B8-B11 (second tier), C1-C3 (contract/tests) — then resubmit for pre-launch inspection; NO Gradle before parent approval, and any eventual runs use the guarded launcher with watchdog and immutable evidence per standing policy. Reviewer holds: no source edits by GLM.
