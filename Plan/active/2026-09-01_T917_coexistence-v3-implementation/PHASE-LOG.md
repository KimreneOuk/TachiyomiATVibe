# T917 Phase Log

Format per PLAN.md §2: each gate records what landed, the checkpoint tag, and test/toolchain status.

## Phase 0 — Baseline & stewardship — DONE (2026-09-01)

- Baseline commit on `main`: `9263b5f` — T914–T916 records, v2.1 superseded banner, v3.0 draft, T917 README/PLAN, doc cross-references.
- Anchor tag: `checkpoint/t916-audit-baseline` (annotated, on `main`).
- Work branch created and active: `t917/coexistence-v3`.
- Toolchain baseline: `./gradlew --version` → Gradle **8.12**, launcher JVM **21.0.10** (JetBrains Runtime via Android Studio).
  - **Environment note for all specialists:** `JAVA_HOME` is not set globally in this shell. Required export before any Gradle invocation:
    `export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
- Module unit-test baseline: **deferred to Phase 1 start** (first Gradle test run of `:app` testDebugUnitTest for the translation packages; result recorded here before any production change).

### Gate
- Tag: `checkpoint/t917-p0-done`
- Exit criteria met: baseline tag ✔ · work branch ✔ · toolchain recorded ✔

## Phase 1 — Harness and failing tests — ACCEPTANCE IN PROGRESS

- Deliverables on branch `t917/coexistence-v3` (uncommitted at this entry): `coexistence/` harness (TranslationCoexistenceHarness / CoexistenceBarrier / FakeEngines), tests D2 (2 cases), D3, NormalMangaIsolationTest; D4 update to `TranslationManagerAutoArbitrationTest`; engineering notes (design + implementation log); review/phase1-verification.md.
- Verification (Main Leader independent + Implementer sweep): 1231 translation tests run; exactly 4 intentional RED, each failing fast with a defect-naming assertion — D2 batch→manual = C-01 (silent return, wait-and-attach missing); D3 = C-02 (skipped-never-rescanned → stranded); D4 = "same-chapter auto re-armed while the chapter batch was still queued (batch-lifetime suppression guard missing)". NormalMangaIsolationTest GREEN; neighbors GREEN; zero unexpected failures reported.
- **D4 contract-change callout (PLAN §5, Reviewer condition 3):** the test formerly named "manager keeps auto window active while the chapter batch is queued" is renamed to "manager suppresses same-chapter auto while the chapter batch is queued" and its assertions INVERTED. This is deliberate: the existing test encoded the behavior audit finding C-03/M-06 identified as the defect (auto re-arms during batch with no batch-lifetime gate in the re-arm path, `TranslationScheduler.kt:158-160`). The adopted D4 Recommendation (draft §6 D4) selects suppression as the contract; Phase 2 will make this test green by adding the batch-active guard. Recorded as a contract change, not a test fiddle.
- Reviewer verdict: ACCEPT-WITH-NOTES (`review/phase1-verification.md`) with binding conditions: (1) fix D2.2/D3 green-path ordering inversion — **DONE** (commit `d2f981b`; RED oracles byte-identical, re-run 3-red/1-green, no timeouts); (2) determinism soak 10 → 100 runs — **DONE** (see below); (3) D4 callout in this log — **DONE** (this entry).

### Determinism soak (Reviewer condition 2)
- Method: 100 consecutive Gradle invocations with per-task `--rerun` (forces real test execution — supersedes the earlier 10-run evidence, which could not prove execution vs up-to-date skips).
- Command: `./gradlew :app:testStandardDebugUnitTest --rerun --tests "eu.kanade.translation.coexistence.*"`; log: `engineering/phase1-determinism-soak.log`.
- Result: **100/100 identical outcome signature** — every run: 4 tests completed, 3 failed (D2 both methods, D3), 1 passed (NormalMangaIsolationTest); wall-clock 34–44 s; zero deviations, zero timeouts.

### Gate — Phase 1
- Tag: `checkpoint/t917-p1-done`
- Exit criteria met: harness merged ✔ · D2/D3/D4 red for the documented C-01/C-02/C-03 reasons ✔ · isolation + baseline green (1231 tests, only intentional reds failing) ✔ · determinism 100/100 ✔ · scope: test code only, no production changes ✔
- Deferred to Phase 2 gate: soak re-run against the GREEN (post-fix) test set to prove determinism of the passing choreography.

## Phase 2 — Intent-loss fixes (D1–D4) — ACCEPTANCE IN PROGRESS

- Part A commits: `d99a93b` (D1 three-origin leases + MANUAL-evicts-AUTO + D1OriginPriorityTest), `dd9f17b` (D4 suppression guard on manager auto-entry methods). Part B commits: `48ddee1` (D2 wait-and-attach: typed SinglePageOutcome, bounded store-observation attach, cancellation-safe), `37c0902` (D3 defer-and-rescan: listener-deferred pages, race-free lease-release waiters, in-pass COMPLETED-path rescan ≤2 attempts with re-acquired batch identity), `58afd34` (log).
- Reviewer: **ACCEPT-WITH-NOTES** (`review/phase2-verification.md`). Deep dive confirmed all four attack scenarios against the commit-fence change are fenced (lease-token re-check under the store mutex is airtight); sweep integrity XML-verified (161 classes / 1232 / 0 failed / 0 skipped).
- **D1 corollary — Director-visible contract record (Reviewer condition 1):** *Lease-over-plan precedence* is adopted as long-term semantics — while a boundary still owns the page lease, run-level plan identity (`candidateGenerationId`, `dependencyFingerprint`, `artifactPageVersion`) is advisory for its commit; the writer fences (generation, pageVersion, lease token, block fingerprints) stay fully armed, and every non-holder remains fail-closed. Reviewer recommendation over the narrower batch-side skip-re-planning alternative; adopted under the standing "Recommendation" decision pattern. The Director may override before Phase 3 (D5 reuse is built on this commit semantics); default stands otherwise.
- Gate condition status: (1) contract record — DONE (this entry); (2) clean-build sweep `:app:clean` + full translation suite → **1232/0, BUILD SUCCESSFUL (6m24s)** ✔ + 100-run determinism soak covering D1/D2/D3/isolation — **DONE, 100/100 BUILD SUCCESSFUL, zero deviations** (`engineering/phase2-determinism-soak.log`; all-green runs may omit Gradle's per-test summary line — task-level BUILD SUCCESSFUL is the binding signature, as established in the Phase 1 soak); (3) backlog — DONE (recorded in PLAN §5).

### Gate — Phase 2
- Tag: `checkpoint/t917-p2-done`
- Exit criteria met: D1/D4/D2/D3 implemented per design ✔ · all 4 former-RED tests GREEN on end-state oracles ✔ · neighbors green, isolation untouched-green ✔ · clean-build sweep 1232/0 ✔ · soak 100/100 ✔ · Reviewer ACCEPT-WITH-NOTES with conditions met ✔
- Phase 3 note received: `engineering/phase3-design.md` (D5 targeted-repair gate with absence=0, D6 20% interactive reserve + bounded NonCancellable drain committing per M-09, D9 `_artifacts/attempts/ledger.json` + 3-strike PAUSED cap, patchPage grace fold-in; two PLAN corrections adopted into PLAN.md — stream path stays BACKGROUND; D5 uses a comparable stamp field, not hash-embedding).

### Phase 3/4 backlog (Reviewer findings, non-blocking)
- LOW: `patchPage` lacks the `record.candidate != null` grace that `publishLocked` has (`ChapterTranslationStore.kt:461-463` vs `:1483-1485`) — align at root with a regression test (batch registration between manual capture and commit, lease held → accepted).
- LOW (note): a manifest-only mutation without a generation bump can fail-closed reject the boundary commit (conservative direction: a retried page, never corruption) — acceptable; revisit if observed in practice.

## Phase 3 — Economic fixes (D5 + grace, D9, D6) — implementation complete, gate in progress

- Part A commits: `e9cee3d` (D5 tests RED — right-reason: `expected:<RUN> but was:<REUSE>`), `241d9c7` (D5 gate+stamp: `PageTranslation.translationGlossaryVersion: Int?`, AI-lane rule `current > (recorded ?: 0)`, `currentGlossaryVersion()` accessor, stamps in BatchResumePlanner + SinglePageHttpRenderPhase post-fold), `58360c8` (patchPage candidate-grace aligned with publishLocked + fail-closed regression test), `7e9d080` (log). Sweep 1238/0.
- Part B commits: `be3c4d5` (D9 tests RED — named-missing-seam assertions), `0eaf7e1` (D9: `store/ChapterAttemptLedger.kt` 64-entry evict-oldest, `recordAttemptStart`/`resolveAttempt`/`applyAttemptCapPause`, `reconcileAttemptLedgersForStartup` bounded to opened-chapter set, wired at batch/manual/ONNX/auto call sites, fail-open writes / fail-closed AUTO-only cap, force clears cap), `8dd55e4` (D6 tests RED — including the real §2.2a swallow defect), `dd3364e` (D6: `interactiveTokenReserveFraction=0.2` range-guarded, background limits shrink only under an INTERACTIVE waiter, no revocation, oversized single request still admissible; `SinglePageOutcome.Paused` typed mapping — pipeline no longer swallows governor deferrals; `PROVIDER_DRAIN_GRACE_MS=90_000` NonCancellable drain committing under full fences), `a07d68d` (log). Sweep 1250/0; isolation untouched-green; determinism 5/5 identical per part.
- Reviewer: **ACCEPT-WITH-NOTES** (`review/phase3-verification.md`). No CRITICAL/HIGH. Attacks verified: D5 absence=0 single-repair + fence-untouched payload stamp + D1-corollary clean; grace fail-direction preserved with every caller re-audited; D9 zero-billing AUTO refusal + cap-never-binds-user + bounded reconcile; D6 reserve math exact (defer estimate pinned), drain timeout INNER (cancellation-class, entry unresolved), §2.2a fix strictly narrowing.
- **D9 contract note (Director-visible, Reviewer finding 1; default = KEEP per Reviewer recommendation, override possible):** D9 counts in-app cancellations (user stop, reader close, drain-grace expiry) as consumed attempts at next startup — not only process death. Three consecutive mid-call cancels without a completion can trip the honest PAUSED cap; explicit force always recovers; refusal never bills. Exact §3.2 semantics (resolve in the in-process CE handlers, still counting true deaths) available as a follow-up if preferred.
- Gate condition status: (1) clean-build sweep `:app:clean` + full translation suite → **1250/0, 0 errors, 0 skipped** ✔ + 100-run determinism soak covering `coexistence.*` + `ChapterTranslationStorePatchPageGraceTest` + `ProviderRequestGovernorReservationTest` — **100/100, zero deviations** (`engineering/phase3-determinism-soak.log`; per-run `exit=0`, ~40–105 s each) ✔; (2) D5 cost wording corrected in phase3-design §1.2 (up to two paid passes per maturation, strictly converging — finding 2) + D9 contract note recorded (this entry) — DONE; (3) backlog — DONE (below).

### Gate — Phase 3
- Tag: `checkpoint/t917-p3-done`
- Exit criteria met: D5/grace/D9/D6 implemented per design ✔ · each suite RED-for-right-reason before its GREEN commit ✔ · sweep 1250/0 clean-build ✔ · soak 100/100 ✔ · isolation untouched-green at every step ✔ · Reviewer ACCEPT-WITH-NOTES with all conditions executed ✔
- Deviation records (Director-visible, phase4-design.md §0 + this log): D9 counts in-app cancellations as consumed attempts (default KEEP, exact-semantics follow-up available); D11 staged adoption (permit-free commit slice in Phase 4, write-behind core deferred behind Phase-6 measurement trigger).

### Phase 4/5/6 backlog (Reviewer findings 3–6, non-blocking)
- LOW (finding 3, D5 accepted drift): stamp claims concurrently folded pairs it did not see — under-repairs until next maturation; direction-safe, accepted by design.
- LOW-MEDIUM (finding 4 → D8/Phase 6): drain grace 90 s can expire while the auto chain is within its own legitimate budgets (ONNX 90 s + HTTP 120 s); expiry counts the attempt and re-runs the page. Phase 6 measured stall data must re-validate the bound.
- LOW (finding 5 → Phase 5 UI-truth): `ChunkCompletionOutcome.PersistenceRejected`/Failed-as-value from the HTTP phase still surface as `SinglePageOutcome.Completed` in `manualOutcomes` (pre-existing; store-visible non-terminal page). Fold into the Phase-5 typed-pause/copy pass.
- NOTE (finding 6): translation-only chapter reset does not clear ledger counters or an INTERRUPTED cap failure; explicit force remains the designed recovery path.

## Phase 4 — Engine lifecycle + honest outcomes + partial downloads (D7, D8, D10, D11) — implementation complete, gate in progress

- Salvage note: the first Phase 4 implementer reached its usage limit mid-D8. D7 was complete and committed at that point (`74d037a` tests, `f49a82f` engine epoch + borrow drain + boundary retry, `a37e3f7` provider drain-grace budget alignment); D8 was finished by the Main Leader from a compile checkpoint.
- D8 commits: `a3dd2d6` (tests RED — named seam/outcome defects), `954ddfd` (WIP compile checkpoint — documented, per checkpoint policy), `99dfb1d` (GREEN: `NativeStallWatchdog` + quarantine occupancy observer, typed `Stalled`/`Failed` outcomes, stall refusal BEFORE lease/native admission, residual same-page rejection BEFORE queueing, timeout never `Completed`, truthful timer text incl. sub-second ms rendering, harness `nativeTimeoutMs` injection fixing a latent 0 ms-timeout bug for ALL Unsafe-built pipelines), `6f76706` (implementation log, RED→GREEN evidence).
- D10/D11 commits: `3ff5d3f` (D10 partial-download admission: pure `BatchAdmissionProbe` + routing, additive-nullable manifest `partialBatchInfo`, `preRegisterPages` trusted-total/trust-demote truth, trigger partition + typed partial-download dialog; RED 5 named failures → GREEN 5/0), `b8c0974` (D11 safe slice §4.4: resume-tail cleaned-image persist + render tail + `store.flush()`/stream-clear deferred out of the native permit via `DeferredPagePublications`, `onTimeout` orphans before `markPageTimedOut`, normal-path drain strictly fail-closed; RED 1 named permit-span defect → GREEN 1/0; report `engineering/phase4-d10-d11-notes.md`, 8 recorded deviations).
- Reviewer: **ACCEPT-WITH-NOTES** (`review/phase4-verification.md`). No CRITICAL/HIGH. Attacks verified: drain snapshot-close cannot close rebuilt engines; exactly-one epoch retry with one D9 entry; quarantine-held orphan ordering race-free (tails run exactly once, never after boundary drain); residual rejection membership window exactly represented; NO native cancel/abort/kill (grep-verified over the full diff); D10 manifest backward-compatible both directions; D11 fail-closed direction correct.
- **D6 bound contract change (recorded per design §6 step 3):** `PROVIDER_DRAIN_GRACE_MS` changed 90 s → **210 s** (= `ATTACH_TIMEOUT_MS`, P3 finding 4: a drained call must never be cut by a bound shorter than its own legitimate budget). `D6DrainNotCancelTest`'s bound assertion updated to pin the new bound. Known cost (documented): worst-case reader-stop JOIN extends while a call is genuinely mid-flight; Phase 6 re-validates.
- Deviation records (Director-visible): D7 "complete" scope read as §1 design set; D8 fold-in of Completed-on-timeout (§0.4 defect) and the new pre-admission gates; D10 offline-count refinement (settled downloads admit unchanged — the literal design would dialog every settled chapter since `Downloader` drops queue entries on completion); D11 staged adoption (safe slice now, write-behind behind Phase-6 measurement).
- Deviation #7 assessment (Reviewer §5, corrected framing): resume paths return null on SUCCESS; D8's honest-timeout flip made resume successes type as `Failed("native phase timed out")`. This is a NEW wrong-outcome case introduced by the flip (pre-D8 the accident produced the right `Completed`), store truth unaffected (drain precedes outcome typing — VERIFIED). **Named Phase 5 condition:** fix via the `buildTerminalPreparedPage` store-terminality inspection pattern; must not be dropped.
- Phase 5 carry-list (from this review): D8-1 — HTTP+render timeout still maps to `Completed` (`withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS)` path; extends P3 finding 5); Deviation #7 fix (above); Deviation-2 residue label (settled-but-actually-partial legacy directories still admit with self-derived counts — record in the Phase 5 UI-truth appendix); N2 — multi-chapter partial dialog shows only the first chapter's counts in its body.
- Phase 6 carry-list: D11-1 — residual permit-held writes (RUNNING/OCR patches, `markPageTimedOut` placeholder) count toward permit-held time in measurements; D7-1 — `ensureTranslatorRebuiltForEpochRetry` is unsynchronized (LOW-MEDIUM: two concurrent borrows racing one close can leak the loser's translator; mutex or close-orphan fix); D8-2 — one-line watchdog token re-check hardening.

### Gate — Phase 4
- Tag: `checkpoint/t917-p4-done`
- Clean-build sweep: `:app:clean` + full unit suite → **1373 tests / 191 suites / 0 failures / 0 errors / 0 skipped**, BUILD SUCCESSFUL (4m47s) ✔
- 100-run determinism soak: `coexistence.*` + `translator.*` + `scheduling.*`, per-run `--rerun` → **100/100 BUILD SUCCESSFUL**, per-run wall-clock 52 s–1 m 05 s (`engineering/phase4-determinism-soak.log`; the log carries a labeled correction of a PASS/FAIL string-comparison bug in the soak script — original lines preserved unedited, captured Gradle lines are the evidence; final-iteration XML 274/0 re-verified) ✔
- Reviewer: ACCEPT-WITH-NOTES; gate conditions executed — G1 phase-log entry (commit `2ea0c03`) ✔, G2 specialist reports committed (same commit) ✔
- Exit criteria met: D7/D8/D10/D11 implemented per design + recorded deviations ✔ · every RED a named assertion, zero choreography timeouts ✔ · clean sweep 1373/0 ✔ · soak 100/100 ✔ · isolation untouched-green ✔ · no native kill grep-verified by Reviewer ✔
