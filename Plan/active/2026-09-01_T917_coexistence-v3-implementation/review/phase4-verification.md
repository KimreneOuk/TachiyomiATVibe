# T917 Phase 4 Verification — Reviewer Acceptance (D7, D8, D10, D11)

Reviewer: independent Reviewer role (`docs/roles/reviewer.md`).
Scope: branch `t917/coexistence-v3`, commits `74d037a..b8c0974` (Phase 4). Baseline
`checkpoint/t917-p3-done` previously accepted (`review/phase3-verification.md`).
Method: primary-evidence reading of all production diffs in range, the four new test
suites, the harness diff, and the design/evidence docs. No Gradle runs were performed
by this review (gate builds owned elsewhere); all green/red claims below that I could
not re-execute are classified accordingly.

Evidence labels: VERIFIED (read in code), STRONG INFERENCE (code + cross-referenced
test/doc), ASSUMPTION, UNKNOWN.

---

## 1. D7 conformance — epoch + borrow drain + exactly-one retry

**VERIFIED conformant to phase4-design §1.**

- **Epoch only on closeEngines.** `EngineLane.engineEpoch` is incremented in exactly
  one place: `closeEngines()` (`pipeline/EngineLane.kt:307`). Repo-wide grep confirms
  `currentEngineEpoch()` consumers are only the borrow site and the retry gate in
  `SinglePageHttpRenderPhase` (:201, :320, :324). Rebuilds (`ensureEnginesBuiltFor`)
  do not bump it — matches §1.2.
- **Borrow-observable drain with bounded grace.** `beginTranslatorUse`/
  `endTranslatorUse` (AtomicInteger + one CompletableDeferred, event-driven — no
  polling) wrap the single capture site `translateSinglePageHttpRender`
  (`SinglePageHttpRenderPhase.kt:200-202`, release in the outer `finally` :664). No
  throw path exists between begin and the `try` (:203-339 are declarations only), so
  the release cannot leak. `closeEngines()` fast-path closes when idle
  (`translatorUseCount == 0`), else launches a one-shot `withTimeout(drainGraceMs)`
  drain on the injected `drainScope` (= `nativeRunScope`); the stop caller never
  joins (non-blocking) — matches §1.2 step 3. Grace expiry closes anyway (:335-341).
- **Exactly-one boundary retry.** `runTranslate` retries only when
  `!epochRetryUsed && epoch moved`, inside the SAME `runLedgerWrapped` call — one D9
  entry, `resolveAttempt` on the final outcome; a second mismatch rethrows into the
  typed-failure handling; the close path never touches the ledger (:313-338) —
  matches §1.3. `translateOnce` re-reads the glossary snapshot and rolling pairs per
  round (:275, :288), satisfying §1.2's "re-read the glossary" requirement. The retry
  consumes no extra lease/generation rights; all store fences stay armed (only
  `activeTranslator` is re-captured).
- **Grace vs chain budget.** §1.6 resolved as designed: `PROVIDER_DRAIN_GRACE_MS =
  TranslationPipeline.ATTACH_TIMEOUT_MS` (90 s + 120 s = 210 s
  `RollingAutoCoordinator.kt:1085`) so a drained call is never cut by a bound shorter
  than its own legitimate budget; the ENGINE drain grace is deliberately SHORT
  (`ENGINE_DRAIN_GRACE_MS = 5_000`, documented as a decision at EngineLane.kt:60-70).
  P3 finding 4's bound is now pinned by `D7EngineEpochStopRaceTest."provider drain
  grace budget is at least the attach chain budget"`.
- **High-risk guard (§1.5 row 1) implemented:** the close snapshots the exact engine
  references at entry and closes THOSE (`recognitionToClose`/`translatorToClose`,
  EngineLane.kt:312-318, used by `closeEnginesNow`), so a late drain can never close
  rebuilt engines; `enginesClosed` stays the rebuild authority.

**Residual close-kills-work races — examined:**

1. Grace expiry still closes under an in-flight borrow — by design; the epoch guard
   recovers exactly once (test (c) proves the retry lands on a rebuilt instance and
   commits). Residual, bounded, recovered — accepted per design.
2. The snapshot-close can double-close an instance the epoch retry already closed
   (`ensureTranslatorRebuiltForEpochRetry` closes `retired` again) — both sites
   swallow close exceptions; benign.
3. **Finding D7-1 (LOW-MEDIUM, new, narrow):** `ensureTranslatorRebuiltForEpochRetry`
   (EngineLane.kt:378-394) is unsynchronized. Two concurrent HTTP-phase borrows
   (the designed ONNX-B/HTTP-A overlap) can both fail on the same close, both
   rebuild, and both assign `textTranslator` — the loser's freshly built translator
   is never closed (possible executor/pool leak; audit H-09 shape). Requires two
   borrows racing one close AND both calls failing — rare, bounded to one instance
   per event, stop-race only. Options: mutex the targeted rebuild, or have the loser
   close its orphan before re-capturing. Not a blocker.
4. `TranslationPipeline.close()` → `engines.closeEngines()` (pre-existing wrapper)
   also flows through the new drain — strictly safer; the §1.1 "one production
   caller" inventory line was imprecise but the change set covers it.

Tests: all five design-mandated behaviors (§1.4 a–e) exist and assert the right
things — named AssertionError defects, close-order event-log evidence, paid-call
bounds (1..2 / exactly 2), `cancelledCalls == 0`, ledger-resolution assertions,
negative probes for the idle-lane contract (d). `NormalMangaIsolationTest` untouched
in range (VERIFIED via `git diff --stat`).

---

## 2. D8 conformance — occupancy watchdog + honest outcomes, no native kill

**VERIFIED conformant to phase4-design §2 within its recorded scope.**

- **Stall typed before lease/native admission:** `translateSinglePage` checks
  `nativeStall.value` as its FIRST statement and returns `Stalled` before
  `resolveActiveStore`/lease/native work (`TranslationPipeline.kt:370-374`).
- **Residual same-page rejection BEFORE queueing:** `runGrantedSinglePageBoundary`
  checks `inFlightPageKeys.contains(pageKey)` before `withNativeLane` and returns
  `Rejected(null, "page already translating")` (:494-504); the check sits inside the
  lease-releasing `try/finally` (:604-607), so the lease is released on that path.
  Membership truth claim VERIFIED: the key is removed only inside the parked block's
  `finally` (:537), and the quarantine holds its admission lock until real exit
  (`NativeRunQuarantine.awaitExitAndLogLate` inside `withLock`, NativeRunQuarantine.kt:67,
  :96-98), so the residual window is exactly represented.
- **Timeout never Completed:** `onnxResult ?: return SinglePageOutcome.Failed(pageKey,
  "native phase timed out")` (TranslationPipeline.kt:583) replaces the old `Completed`
  lie; graph test (b) pins it. The drain runs BEFORE the null check, so the residual's
  deferred tails are visible to outcome handling.
- **Truthful timer:** `markPageTimedOut(timeoutMs)` renders whole seconds or ms and
  every pipeline call site passes the timer that actually fired (`nativeTimeoutMs`,
  `SINGLE_PAGE_TIMEOUT_MS`); default kept conservative (PageStoreWriter.kt:105-131).
  The old "120 s" for a 90 s timer defect is gone (design §0.4).
- **Watchdog:** one-shot `delay(threshold)` per occupancy, token-guarded emission,
  replace-don't-accumulate, release-clears; observer fired inside the quarantine's
  admission lock (serialized: occupied/release strictly alternate); `NATIVE_STALL_THRESHOLD_MS
  == ONNX_PHASE_TIMEOUT_MS`; bounded StateFlow; phase tag `NATIVE_LANE` present.
  Lane-scoped: the HTTP tail never holds the lane, so a legitimate long chain cannot
  read as stalled (matches §2.2 and the P3-finding-4 distinction). Pure tests use
  `runTest` (virtual clock).
- **NO native kill — VERIFIED by grep over the full Phase 4 diff:** no added
  `.cancel(`/`abort`/`interrupt`/`kill`/`destroy` on native paths; the quarantine's
  NonCancellable `awaitExitAndLogLate` is unchanged; matches the README "Out" list.

Findings:

- **Finding D8-1 (MEDIUM, pre-existing, adjacent, NOT in the recorded scope):** the
  HTTP+render tail timeout still maps to `Completed`: `withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS)`
  → `markPageTimedOut(...)` → `httpOutcome == null` → falls through the `Paused` check
  → `return SinglePageOutcome.Completed` (TranslationPipeline.kt:576-612). Same §7 lie
  class D8 just fixed for the native timer, one level up, on lines this phase touched.
  Design §2.2 scoped the fix to the native null (`:440-469`), so this is not a
  deviation — but it is unrecorded anywhere. Fold into the Phase 5 typing pass
  (extends the existing P3 finding-5 backlog item: PersistenceRejected → Completed).
- **Finding D8-2 (LOW, new, nanosecond window):** the watchdog timer can publish a
  stale stall state if `onLaneReleased` lands between the timer's `occupiedToken`
  read and the `_state.value =` write (NativeStallWatchdog.kt:66-76) — nothing then
  clears it until the NEXT native admission. Consequence: taps typed `Stalled` while
  the lane is idle, self-healing at next admission; likelihood extremely low. One-line
  hardening (re-check token after constructing the state) suffices whenever it is next
  touched.
- Scope note (documented in the log §2.5, accepted): `translateSinglePageFromStream`
  / the AUTO prepared path gain no Stalled pre-admission gate and the AUTO in-block
  duplicate backstop now throws `NativePageAlreadyInFlightException` → `markPageFailed`
  in `prepareSinglePage`. Per the lane-serialization argument (the quarantine admits
  one block at a time and the key is only set inside a block), that backstop is
  unreachable in practice — nominal change only.

---

## 3. D10 conformance vs §3 and its five deviations

**VERIFIED conformant, with one accepted truth-gap residue.**

- Probe is pure and has exactly the three outcomes with the design's semantics;
  empty-list degrades to `UnknownCount` (§3.5 risk row honored). Routing maps
  FINISH→WaitForDownload, TRANSLATE→AdmitSubset, null→AskUser, Complete→AdmitBatch.
- Manifest `partialBatchInfo` is additive-nullable with default null; manifest JSON
  parses with `ignoreUnknownKeys = true` (`ChapterDocumentIo.kt:183`) — old manifests
  deserialize unchanged; old readers tolerate new manifests (backward-compatible both
  directions, D5 precedent). VERIFIED.
- `preRegisterPages(pageKeys, probedSourcePageCount, sourceCountKnown)`: trusted total
  = source total when known (`maxOf(found, total)`), trust DEMOTED when the total is
  unknown, missing = max(0, total − found), cleared on a later missing==0 cross-check,
  legacy runs preserve the prior value; missing pages never become page records or
  ledger entries. VERIFIED at ChapterTranslationStore.kt:1372-1462 and by tests
  (b), (c), (f).
- Trigger routing probes only candidates with a live queue entry; complete chapters
  of a group admit immediately; partials route through the typed dialog; FINISH reuses
  the existing fenced `queueTranslationAfterDownloadIfCurrent` + fresh download
  generations. The `Translation` probe fields ride the live queued object (no `copy()`
  in the queue→preRegister path — VERIFIED by grep), so they cannot be silently lost.
  Legacy callers keep byte-identical behavior via defaults.

Deviations:

1. **2-arg pure probe (chapterId resolution at the trigger):** accepted — preserves
   the design's own testability requirement; no behavioral difference.
2. **Settled downloads admit unchanged (no Download object → not UnknownCount→dialog):**
   **accepted as the closest safe behavior, with a recorded residue.** The literal
   design is un-shippable: `Downloader` removes the queue entry on completion
   (Downloader.kt:292-293, implementer's citation), so no-entry ≈ settled — dialoging
   every settled chapter would make the feature unusable, and there is no offline
   source of a page count for a settled chapter (no `Download`, `DownloadCache` keeps
   no counts, and a fetchPageList in admission is explicitly out). The truth gap that
   remains: a settled-but-actually-partial directory (historically cancelled or
   error-stopped download that left partial files) still admits with the self-derived
   trusted found-count — the M-08 lie persists for exactly that legacy class. This is
   a labeled, narrow residue, not an opened gap: the live mid-download class (the one
   M-08 actually described) is now dialoged. Phase 5/6 should record it in the
   UI-truth appendix (label or a completed-flag) rather than re-litigate admission.
3. **Page-commit-level assertions instead of terminal done/stranded counts:** accepted
   — the ARTIFACTS display-promotion rejection on fixtures is the documented T909 §4
   gap (pre-existing, out of scope); the page-level store assertions fully pin
   admission truth.
4. **2-page fixtures:** accepted (harness lane-serialization limitation; sufficient
   for subset semantics).
5. **Commit collapse to two commits:** accepted; the design's §6 sequence was a
   sequencing preference, not a contract.

---

## 4. D11 conformance vs §4.4 safe slice

**VERIFIED conformant to the accepted safe slice; the ordering story is sound.**

- **Deferred vs proven-outside inventory (Q4):**
  - Deferred: `resumeInpaintAndRender`'s cleaned-image persist + render tail (one
    lambda, SinglePageOnnxPhase.kt:655-691), the render-only-resume tail (:277-294),
    and the `finally` store.flush + stream clear (:521-543, enqueued after the tails —
    enqueue order preserved). VERIFIED.
  - Proven outside already: fresh-path `persistOnnxCleanedImage` (runs after
    `withNativeLane` returns, TranslationPipeline.kt:559) — the permit then covers
    only native compute + small bookkeeping.
  - **Scope nuance (Finding D11-1, LOW, by-design):** not EVERY permit-held store
    write is deferred — RUNNING-status patches (:610, :562), the resume inpaint-failure
    persist (:640), OCR-start/failure patches (:479ff), and `markPageTimedOut`'s
    placeholder (runs inside `onTimeout` under the lane) remain inline. The design's
    safe slice named precisely the heavy publications (§4.4(1) cited :619-637 and the
    :505 flush); the residual writes are small/short and the slice matches the design
    text. Read D11's claim as "heavy publication moved off the permit", not "the permit
    is storage-free"; Phase 6 measurement should treat the residual writes as part of
    permit-held time.
- **onTimeout orphan ordering (race-free — VERIFIED):** the quarantine's `run` holds
  the admission lock across `awaitExitAndLogLate` (NonCancellable), so
  `withNativeLane` cannot return before the residual block truly exits;
  `orphaned = true` is set first in the `onTimeout` callback
  (TranslationPipeline.kt:517-520). Therefore: tails enqueued before the timeout are
  in the queue and drained by the boundary AFTER the residual exited; tails "enqueued"
  after orphaning run inline and complete before the residual exits — nothing is
  dropped and no tail can run after the boundary's drain. The only theoretical
  imperfection is a nanosecond interleaving where the volatile flag flips between two
  non-suspending enqueues, letting a later inline tail (flush) run before an earlier
  queued tail (persist) is drained — both still run exactly once, each publish chain
  performs its own write+flush, and the final durable state is identical. Not worth
  code; worth this sentence.
- **Fail-closed preserved:** normal-path drain exceptions → `markPageFailed` + rethrow
  (TranslationPipeline.kt:568-582, :884-897); the D11 test additionally proves the
  parked publication still commits after release. CancellationException is rethrown
  without misfailing the page. VERIFIED.
- **Deviation #8 (best-effort drain on the exception path):** ACCEPTED. On that path
  the block already wrote the typed page failure (`markPageFailed` before rethrow);
  making the drain strictly fail-closed there could only mask the original failure
  with a persistence error. The work is still attempted (`runCatching` + logged), and
  the normal path — the only path where a drain failure would otherwise be silent —
  is strictly fail-closed. Correct failure-mode direction.
- **Deviation #6 (safe slice only; write-behind deferred to Phase 6):** this is the
  Director-visible staged adoption already recorded at the P3 gate (PHASE-LOG Phase 3
  deviations) and specified in design §4.5 — the deferral is legitimate and the slice
  is exactly §4.4(1)+(2)+(3). Store fences, D9 ledger, and the synchronous
  `ARTIFACT_PUBLICATION_FAILED` contract are untouched (no store-protocol commits in
  range — VERIFIED via the ChapterTranslationStore diff being D10-only).
- **Deviation #7 is assessed in §5 below.**

Test: the single D11 test is well-aimed — real boundary over the real quarantine, the
park placed at a delegating-mock seam whose default answer performs a REAL store
commit (sanctioned seam; behavior-preserving wiring documented), the choreography
timeout converted into a named RED assertion with rich diagnostics (permitHolder
snapshot, outcomes, store state), plus a fail-closed pin that the moved publication
still lands.

---

## 5. Deviation #7 — resume-null / timeout conflation (Q5)

**VERIFIED real; severity MEDIUM; store truth genuinely unaffected; Phase 5 is an
acceptable fix phase, with a caveat on the "pre-existing" framing.**

- Fact pattern: the resume paths of `translateSinglePageOnnx` return `null` on SUCCESS
  (render-only resume :295, inpaint+render resume :446, resume-skip :263). D8 changed
  the boundary mapping of a null native result from `Completed` to
  `Failed(pageKey, "native phase timed out")` (TranslationPipeline.kt:583). A
  successful resume now types `manualOutcomes` as a native timeout failure while the
  store holds READY/terminal truth.
- Store truth unaffected: VERIFIED — the boundary drains the deferred tails BEFORE the
  null check (:568-583), so the cleaned image, render commit, and flush all land
  before the outcome is typed. The D11 test itself exercises this exact path and
  passes on store-state assertions. No durability or money impact.
- Severity: MEDIUM, not HIGH — the wrong value lives only in the typed
  `SinglePageOutcome` (chip/surface projection), in re-tap/resume scenarios. But it is
  a §7-class lie on a SUCCESS path, and the current UI can already project
  `manualOutcomes`, so it is not purely a Phase-5-internal concern.
- Framing correction: "pre-existing, not introduced" (notes Deviation #7) is only half
  true — the null-on-success shape is pre-existing, but pre-D8 the accident produced
  the RIGHT outcome (`Completed`) for resumes; D8's honest-timeout fix flipped the
  mapping and made resume successes read as timeouts. The phase could not fix it
  without either re-introducing the timeout lie or typing the resume paths — a
  legitimate deferral, but it is a NEW wrong-outcome case, and should be said so in
  the Phase 5 work order.
- Required fix: Phase 5 outcome typing is acceptable (it owns exactly this surface),
  with a named condition so it cannot be dropped. The cheap mechanical fix already has
  an in-repo pattern: inspect `store.state.value[pageKey]` terminality before mapping
  null → Failed (the `buildTerminalPreparedPage` discipline the AUTO boundary already
  uses, TranslationPipeline.kt:920-943).

---

## 6. Regression risk, normal manga, memory bounds (Q6)

- **Normal manga (no translation):** no code path changes — D7/D8 act only inside
  active translation work; D10 only at the explicit manga-screen translate trigger;
  D11 only reorders permit scope within an active page. `NormalMangaIsolationTest`
  untouched in range (VERIFIED) and green per the implementer's sweep evidence.
- **Pre-existing auto/manual paths:** scheduler/lease/attach machinery unchanged;
  `translateChaptersIfCurrent`/`queueChapter` gained default parameters (source
  compatible); the AUTO prepared path change is a nominal backstop (unreachable, §2
  above). `PROVIDER_DRAIN_GRACE_MS` 90 s → 210 s extends only the worst-case
  reader-stop JOIN while a call is genuinely mid-flight, on IO dispatchers — the
  designed, documented cost (§1.6); Phase 6 re-validates.
- **Memory / Android 8.0+ / bounded memory:** one AtomicLong + one AtomicInteger + one
  CompletableDeferred per close (D7); one StateFlow + one timer job per occupancy
  (D8); one per-page `DeferredPagePublications` queue, drained and discarded per
  boundary (D11); one nullable manifest field + two fields on the live queued
  `Translation` (D10); probe map bounded by selection size. No polling loops, no new
  platform APIs, no new dependencies. The 5 s engine drain bounds how long engines
  survive Stop (bounded-memory release outranks the rare retry — documented decision).

## 7. Test honesty (Q7)

- No `Thread.sleep`, no polling, no Robolectric anywhere in the added tests (grep
  VERIFIED, including the whole test diff). Waits are barrier arrivals, StateFlow
  `first {}`, or bounded negative probes (`NEGATIVE_PROBE_MS = 2 s`) whose expiry is
  converted into defect-naming assertions — the D11 RED conversion is exemplary.
- REDs are named assertions: D7's five failures, D8's named seam/outcome defects,
  D10's five named failures, D11's named permit-span defect with diagnostics. The
  harness's reflection-based seam injection converts a missing seam into a named
  AssertionError rather than a compile or timeout failure — honest at RED.
- Fakes stay at sanctioned seams: FakeChapterDocumentIo, graphics shims, fake
  transport/recognition with shared cross-instance evidence, and the delegating
  cleaned-publication mock (real store commit beneath the park). The D8 residual
  re-tap drives the REAL pipeline boundary to bypass the scheduler's anti-blink dedup
  — correct level for the contract under test.
- The `nativeTimeoutMs` harness fix (§2.3.1 of the log) is a genuine latent-bug repair
  with a correct mechanism explanation (Unsafe.allocateInstance skips ctor defaults).

## 8. Anything else (Q8)

- **Gate condition G1:** the D6 `DrainNotCancelTest` bound change is a CONTRACT-CHANGE
  test edit; the design (§6 step 3) requires it recorded in PHASE-LOG.md (D4
  precedent). It is recorded in the test KDoc and implicitly in the log, but
  PHASE-LOG.md has no Phase 4 entry at all yet. Before `checkpoint/t917-p4-done`:
  add the Phase 4 gate entry recording (i) the D6 bound contract change, (ii) the
  design-note §0/§4.5 deviations (D7 "complete" scope, D8 Completed-on-timeout
  fold-in, D10 offline-count refinement, D11 staged adoption), (iii) the phase-4
  findings from this report that are being carried (D8-1, Deviation #7/D7-fix, D11-1,
  Deviation-2 residue).
- **Gate condition G2:** `engineering/phase4-d10-d11-notes.md` is UNTRACKED in git.
  Prior-phase practice commits specialist reports; PLAN §2 requires a committed
  turn-end state. Commit it before/at the tag.
- **Note N1:** `checkpoint/t917-p4-done` correctly does not exist yet (Reviewer
  acceptance precedes the tag — ordering honored). The phase gate's own full-sweep +
  100-run soak remains the Main Leader's gate step; this review does not substitute
  for it. The 954ddfd WIP compile-checkpoint commit and the out-of-phase 2cf906d (p5
  product spec) interleave are within the letter of the checkpoint policy but should
  not become a habit inside a tagged phase range.
- **Note N2 (LOW):** the multi-chapter partial dialog renders only the first
  chapter's counts in its body; the dialog covers the group. Copy is Phase 5's —
  tracked there.

## 9. Verdict rationale

No CRITICAL or HIGH findings. All four decisions conform to their design sections and
their recorded deviations are genuine, documented, and correctly aimed; the two
MEDIUM findings are honest-outcome typing gaps (one pre-existing-adjacent and
unrecorded, one newly flipped by the D8 fix and explicitly deferred), both with
truthful durable state and no cost/durability impact. The concurrency mechanisms I
attacked — drain snapshotting, quarantine-awaited orphan ordering, lease release on
the residual rejection, epoch exactly-once — all held. Acceptance carries the gate
conditions G1–G2 and the Phase 5 carry-list (D8-1, Deviation #7 fix, D11-1 hardening,
Deviation-2 residue label).

VERDICT: ACCEPT-WITH-NOTES
