# T917 Phase 5 Verification — Reviewer Acceptance (UI truth, conditions A–D)

Reviewer: independent Reviewer role (`docs/roles/reviewer.md`).
Scope: branch `t917/coexistence-v3`, range `checkpoint/t917-p4-done..HEAD`
(11 commits, `e7ea1af..1dd5d85`). Baseline p4 accepted
(`review/phase4-verification.md`). Contract:
`engineering/phase5-product-spec.md` (§2 matrix, §3 truth/D12/D13, §4 minimum
fix, §6.2 commit order). Implementer record:
`engineering/phase5-implementation-notes.md` (11-commit matrix, 8 deviations).

Method: primary-evidence reading of all production diffs in range, the four new
P5 suites, `P5HonestOutcomeTypingTest`, the harness diff, and targeted greps
(Completed productions, RED names, sleeps/Robolectric, native-kill, duration
copy, mapper consumers). No Gradle runs were performed by this review (gate
builds owned elsewhere); the 76-suite/510-test sweep is implementer-attested
XML evidence — classified STRONG INFERENCE, not reviewer-executed.

Evidence labels: VERIFIED (read in code), STRONG INFERENCE (code +
cross-referenced test/doc), ASSUMPTION, UNKNOWN, CONTRADICTION.

---

## 1. Condition A — resume-null/timeout conflation (phase4 §5 Deviation #7)

**VERIFIED closed; drain-before-type ordering preserved; residuals are
wrong-chip-only.**

- **Ordering.** In `runGrantedSinglePageBoundary` the D11 deferred-publication
  drain still runs BEFORE the outcome typing: normal-path drain
  (`TranslationPipeline.kt:592-599`) precedes the null check (`:609-616`). The
  cleaned-image persist, render commit, and flush are therefore durable before
  the boundary inspects the store — the D11 discipline from P4 is intact.
- **Store-terminality inspection.** `onnxResult == null` now resolves the store
  and returns `Completed` only when `isPreparedPageTerminal(page)`
  (`TranslationPipeline.kt:609-616`; predicate at
  `scheduling/PreparedPageBoundary.kt:91-100` — rendered READY/SKIPPED or
  textless; FAILED stages are never terminal). This is exactly the
  `buildTerminalPreparedPage` discipline the AUTO boundary uses, as the P4
  review's required fix pattern mandated.
- **Real timeout still types Failed:** yes — non-terminal page ⇒
  `Failed(pageKey, "native phase timed out")` (`:615`).
- **Resume success still typing Failed:** not on any normal path. The test
  drives the REAL boundary and REAL store: the resume tail's cleaned-image
  publication is a genuine preconditioned `updatePageGuarded` commit, the test
  first pins `renderStatus == READY` in the store, then asserts the typed
  outcome is `Completed` (`P5HonestOutcomeTypingTest` condition A, :84-155,
  named RED at :146). Two theoretical residuals remain, both LOW and
  wrong-chip-only (store truth unaffected):
  1. `resolveActiveStore` returning null at the inspection moment types Failed
     on a genuine success — the same nanosecond-narrow exposure the AUTO
     boundary has; symmetric with the accepted AUTO discipline.
  2. A resume success whose durable page carries a FAILED stage types Failed —
     per the shared predicate, failed pages are never terminal (identical to
     `prepareSinglePage`'s own contract that failures never become prepared
     pages). Consistency with the AUTO boundary is the design requirement; a
     mixed render-READY + translation-FAILED page is failed truth.
- **Re-tap on an already-terminal page whose new native work times out:** the
  native `onTimeout` `markPageTimedOut` guards skip READY stages, the page
  stays terminal, the boundary types `Completed` — the spec §4.1.1
  "legitimate terminal no-op". Consistent old-vs-new. VERIFIED by guard reading.

## 2. Conditions B + C — no path to Completed/success wording

**VERIFIED: the outcome mapping is exhaustive and every non-committed value is
a visible non-success. No success wording survives a rejection.**

- **Exhaustiveness.** `ChunkCompletionOutcome` is sealed with exactly five
  subtypes — Completed, Paused, Failed, Unexpected, PersistenceRejected
  (`BatchCoordinatorInterfaces.kt:149-195`). The boundary's post-finally
  `when` (`TranslationPipeline.kt:656-669`) covers all five; there is no
  `else` and no fall-through.
  - `null` (timer fired) ⇒ `Completed` only if `httpTimeoutLandedDurableResult`
    (store-confirmed terminal at `:632-635`), else
    `Failed(pageKey, REASON_HTTP_RENDER_TIMER_EXPIRED)` — the D8-1 lie is dead.
  - `Paused` ⇒ `Paused(epoch)` (D6 §2.2a behavior preserved).
  - `PersistenceRejected` ⇒ `Rejected(null, REASON_TRANSLATION_NOT_SAVED)` —
    exactly the spec §4.1.3 shape; the stable reason is a pipeline constant
    (`:155-160`) consumed by the mapper to select NOT_SAVED truth.
  - `Failed`/`Unexpected` ⇒ `Failed(pageKey, reason)`.
  - `Completed` ⇒ `Completed` (guarded commit per the store's synchronous
    reject-and-restore contract, P3/P4-verified).
- **Terminal truth outranks the timer, and the placeholder cannot clobber it.**
  On HTTP timeout the boundary inspects terminality FIRST
  (`:632-638`): terminal ⇒ `httpTimeoutLandedDurableResult = true` and
  `markPageTimedOut` is NOT called at all; non-terminal ⇒ the placeholder is
  written with `nativeTimer = false`. A terminal page can never be overwritten
  by the timeout placeholder on this path.
- **Mapper-level backstop (stale-success guard).** `TranslationUiTruth
  .fromDurable` (`ui/TranslationUiTruth.kt:395-412`): a `Completed` the durable
  record cannot confirm maps to `failedRetryable(ERROR)` when the projection
  says FAILED_NO_RESULT, and to NOT_SAVED otherwise — so even a hypothetically
  wrong `Completed` can never render success. Two independent layers.
- **Batch side.** `nonDurableFailure` + safe reason are carried on
  `BatchPaused`/`BatchFinished` events (`TranslationBatchEvent.kt`), persisted
  into the tracker state, and projected onto the snapshot
  (`TranslationBatchProgressTracker.kt:134-149, 155-162, 270-283`). The
  notification copy selects "Translation not saved — retry required"
  (`TranslationNotificationCopy.kt:54-60`), the chapter gate surfaces NOT_SAVED
  (`chapterSurfaceDecision` :333-336), and `terminalPages` never counts the
  rejected page as success. Pinned by the batch test
  (`P5HonestOutcomeTypingTest` :377-435, named REDs at :419/:430).
- **Grep.** All `SinglePageOutcome.Completed` productions in the pipeline are
  the store-confirmed terminal cases above; no other return path exists.
  The fail-closed store, lease release in `finally` (`:649-651`), generation/
  page-version/lease fences, and synchronous publication are unchanged.

## 3. markPageTimedOut skip narrowing (notes deviation 2) — attacked hardest

**VERIFIED safe at the mechanics level; terminal stage states cannot be
corrupted; one LOW corner found (SKIPPED not guarded); store semantics
untouched.**

- **Mechanics are race-safe.** `patchPage` validates generation/pageVersion/
  artifact-version/candidate/fingerprint/lease preconditions under the store
  mutex and applies the lambda to `current?.detachedCopy()` — NOT the live
  record (`ChapterTranslationStore.kt:562-623`). The new `existing.apply { … }`
  therefore mutates a copy: a concurrent terminal commit between the boundary's
  precondition snapshot and the timeout patch rejects the timeout write
  fail-closed, and a rejected patch cannot leak mutation into the live page.
  The boundary additionally pre-inspects terminality on the HTTP path, so a
  landed durable result skips the write entirely (§2 above). VERIFIED.
- **Stage-by-stage guard analysis** (PageStoreWriter.kt:120-168):
  - `ocrStatus != READY → FAILED`: READY preserved; a RUNNING/FAILED ocr is
    failed with the timer-named message. Correct on a timed-out page.
  - `translationStatus != READY && != PARTIAL → FAILED`: READY and PARTIAL
    (the P5 partial-truth flag source) preserved. Correct.
  - `renderStatus != READY → FAILED`: a committed render (READY) preserved —
    the rendered result is never overwritten. Correct.
  - `cleanedImageName` and all artifacts untouched; `retryCount/attemptCount`
    increment (informational page counters, not the D9 ledger).
- **Attack found — LOW, no permanent corruption:** the guards do not protect
  `SKIPPED`. A textless-finalized page
  (`PostOcrStageSemantics.finalizePostOcrStage` sets translation/render
  SKIPPED and keeps `cleanedImageName` when inpaint mask boxes are non-empty)
  that is non-terminal because ANOTHER stage is FAILED (e.g. inpaint FAILED)
  and whose repair attempt hits the native timer gets SKIPPED→FAILED
  overwritten. Consequences are bounded: (a) the typed outcome is IDENTICAL to
  the old code (the old skip also typed Failed, because the page was
  non-terminal via the FAILED stage); (b) the retry recomputes the SKIPPED
  facts (re-OCR finds no text); (c) artifacts persist. Recommended Phase-6
  hardening: add SKIPPED to the guard sets. Not a blocker.
- **The committed RED is real and the fix is strictly more truthful.** Old
  code: `cleanedImageName != null ⇒ return existing` left a mid-pipeline page
  (FAST-inpaint persisted, render not committed) silently RUNNING forever —
  the stranded-TRANSLATING defect. New code converts that into FAILED stages
  carrying the timer-named truth, with artifacts preserved and retry recoverable.
- **Call-site timer naming (D12).** Both batch call sites
  (`BatchLaneWorkers.kt:831, :996`) are `withNativeLane` onTimeout — genuine
  native-lane timers, so the default `nativeTimer = true` name is correct. The
  prepared-path site (`TranslationPipeline.kt:1104`) passes
  `nativeTimer = false` — correct, that block runs the HTTP+render phase. The
  manual HTTP site passes `nativeTimer = false` explicitly. VERIFIED.
- **Leases/quota/ledger/native semantics untouched.** The PageStoreWriter diff
  is confined to the `markPageTimedOut` lambda and its timer flag; no lease,
  quota, D9-ledger (`ChapterAttemptLedger`), or native-path changes anywhere in
  the range (diff read in full). `invalidateGeneration` + preconditioned patch
  behavior is byte-identical to P4. VERIFIED.

## 4. Deviation 3 — D8StallWatchdogTest assertion update

**VERIFIED truthful; no other unmeasured duration remains.**

- The assertion now pins `page.activeError.contains("ONNX/native result
  timer expired")` (`D8StallWatchdogTest.kt:135-136`), replacing
  `contains("100")`. The production copy is `TranslationUiTruth.timeoutCopy` —
  "ONNX/native result timer expired; translation failed." / "HTTP+render
  result timer expired; translation failed." (`TranslationUiTruth.kt:179-184`)
  — which names the timer and omits durations, exactly the D12 §3.2 preferred
  wording. This is a contract-following fix, not test weakening.
- Grep over UI copy, store placeholders, and strings.xml found NO remaining
  unmeasured duration or "timed out after N" user-facing claim; the old
  second-division "after 0s" rendering is gone (mentioned only in a comment).
  The only numbers surfaced are state counters (§6/D13 below). VERIFIED.

## 5. Deviation 5 — visibility gate enforced only at the notification announcer

**Announce/silence claim VERIFIED sound for its own scope; one CONTRADICTION
found in the notes' broader claim (finding P5-1, MEDIUM); no stale-success
surface exists.**

- **The gate itself is correct.** `TranslationForegroundService
  .acknowledgeTerminalOutcome` (`:213-227`) runs before the policy-driven stop
  path, gates through the pure `chapterSurfaceDecision`, double-checks
  `aborted || nonDurableFailure`, and retains a non-ongoing notification with
  the pure copy ("Batch translation cancelled — saved pages kept" /
  "Translation not saved — retry required") plus an explicit Retry action.
  `onDestroy` honors `retainNotification` (`:98-109`). The progress publisher
  renders the pure `TranslationNotificationCopy` record with identical-body
  coalescing (single bounded `Triple`), trusted-only determinate progress, and
  an indeterminate bar for unknown totals (`:174-211`). VERIFIED.
- **Reader/manga/drawer make no announce/silence decisions:** VERIFIED — they
  are passive renderers; no notify/announce code exists there, and no reader
  surface changed in range.
- **No stale success after terminal cancellation:** VERIFIED — the reader chip
  renders durable display truth (displayReady precedence, untouched and
  P4-verified); the notification shows cancelled/not-saved copy or is removed;
  the drawer renders `snapshot.aborted`. No surface can show success for a
  cancelled in-flight page.
- **Minor gap (accepted, noted):** the user-initiated `ACTION_STOP` path
  (`:69-72`) removes the notification without the cancelled acknowledgement.
  The user performed the cancellation themselves and the drawer carries the
  aborted state — self-acknowledgement; acceptable.
- **FINDING P5-1 (MEDIUM — CONTRADICTION in the gate record, not in durable
  truth):** the reader page chip, reader overlay, and rolling-auto status do
  NOT consume the Phase-5 truth layer. `TranslationUiTruth.forManualOutcome`,
  `forAutoSlot`, and `TranslationScheduler.manualOutcomeFor` have ZERO
  production callers (grep VERIFIED); `ReaderTranslationFeedback`,
  `ReaderAutoTranslationUiState`, `AutoTranslationStatus`, and the page holders
  are untouched in range; `Stalled` has no production consumer outside the
  pipeline/watchdog. Consequence: manual typed outcomes (Attached,
  AttachedUnresolved, Paused, Stalled, Rejected/not-saved) and the stall state
  remain invisible on reader surfaces — the pre-P5 condition, which spec
  §0.2.2 and §6.2.8 ("make terminal/pause/stall/rejection visible", "wire the
  pure precedence in reader, manga, drawer, and notification callers") required
  joining to the page-holder path with identity fencing. Deviation 5's
  announce-gate argument is valid for the §3.1 announce/silence budget but does
  not cover this rendering mandate. Worse, notes §3 condition D states
  "page chip / reader overlay / notification / queue / progress / accessibility
  all read from the shared pure mappers" — CONTRADICTION with the code for page
  chip, reader overlay, and auto status. What IS wired and truthful: the
  notification, drawer mini-chips/hero/subtitle, chapter indicator a11y, the
  manga partial dialog, the store placeholders, and the batch snapshot facts —
  the durable "discoverable route" per spec §1.1. The batch-scoped visibility
  budget is met; the manual/auto typed-outcome hop to reader surfaces is not.
  This is a presentation-reach shortfall over an honest, fully-typed plumbing
  layer — the same class as P4's accepted residues, but the gate record must
  say so accurately (gate condition G1/G2 below).

## 6. Normal manga, memory bounds, D13, no native kill

- **Normal manga:** `NormalMangaIsolationTest` untouched in range (diff stat
  empty — VERIFIED). Production changes are inside translation surfaces, the
  translation-only dialog branch of MangaScreen, the translation foreground
  service, and translation pipeline/store internals. No decode/arbitration/
  storage-observation path changes for translation-disabled manga. VERIFIED.
- **Memory / Android 8.0+ / bounded:** new state is bounded — snapshot value
  fields (booleans/ints/nullable reason), `cancelledPageKeys` bounded by the
  registered page set, single-entry notification coalescing `Triple`,
  `manualOutcomes` cap 32 unchanged (`manualOutcomeFor` is a read-only
  accessor, never mutates — VERIFIED), mappers stateless. No new platform
  APIs, no dependencies, no polling loops. VERIFIED.
- **D13:** the only numbers in UI copy are state counters — trusted-total
  fractions ("N of M pages translated", successes exclude failures), terminal
  counts, "N pages available · source total unknown", downloaded/total in the
  decision body. Unknown totals never render a percentage or a determinate bar
  (`UNKNOWN_TOTAL` hero phase + indeterminate progress + notification copy —
  VERIFIED). No ETA/latency/quality claims. VERIFIED.
- **No native kill:** grep over the full range diff for
  cancel/abort/interrupt/kill/destroy on native paths is clean — the hits are
  test coroutine scopes and the batch tracker abort event. The quarantine,
  drain, and NonCancellable semantics are untouched. VERIFIED.

## 7. Test honesty

- **REDs are named:** 28 `T917 P5 RED defect` assertions across the six
  touched test files (grep VERIFIED), each naming the seam/defect
  (conditions A/B/C, Appendix-A mapper, manual-outcome exposure, terminal
  totals, D12 placeholders, visibility budget).
- **RED/GREEN discipline:** all five RED commits (`e7ea1af`, `9100f03`,
  `d968424`, `a25424b`, `2cb3789`) are test-only (harness seam included where
  needed) — verified by `git show --stat`; the commit sequence matches spec
  §6.2 order 1:1. Production lands only in the even commits. VERIFIED.
- **No sleeps/polling/Robolectric:** grep clean across all new suites; waits
  are barrier arrivals, `StateFlow.first {}`, and the harness await bound.
  The condition-B choreography parks the transport at PROVIDER_START and the
  timer is injected (150 ms) — deterministic, no wall-clock waits. VERIFIED.
- **Fakes at sanctioned seams:** the cleaned-publication mock performs REAL
  preconditioned store commits (condition A's resume stub asserts `Accepted`);
  the harness reflection seam converts a missing field into a named RED
  AssertionError (P4 precedent); the real pipeline boundary and real store are
  driven for the typing conditions. VERIFIED.
- **Mapper purity:** `TranslationUiTruth` and `TranslationNotificationCopy`
  import only model/scheduling/pipeline-constant types — no Android, no
  Compose, no resources. JVM-testable as required by spec §5. VERIFIED.
- Sweep evidence (76 suites / 510 / 0) is implementer-attested XML; the
  gate's own full touched-module sweep remains the Main Leader's step.

## 8. Anything else (Q8)

- **LOW — notification catch-all:** `TranslationNotificationCopy.of`'s final
  `else` renders "Translation complete — ready to read" for any non-matched
  state, including `NOT_TRANSLATED`. If a chapter were reset while the service
  still monitored it, a false completion could render (the old code rendered a
  neutral no-progress line). Narrow race; gate the else on `TRANSLATED`
  explicitly. Phase-6 hardening.
- **LOW — chapter-level D9 a11y drift:** `chapterSurfaceDecision` maps every
  chapter PAUSED through `forChapterIndicator(PAUSED)`, whose retryMode is
  hard-coded AUTOMATIC. A D9 INTERRUPTED chapter pause (no retry epoch) should
  read "manual retry required" (spec §2 D9 row, §3.4). The notification copy
  already says "manual retry available" without an epoch and offers no
  automatic-retry wording; only the drawer/indicator a11y drifts. The manual
  D9 truth (exhausted → MANUAL_REQUIRED) is correct in `forManualOutcome`.
- **LOW — partial+failed copy:** `forManualOutcome(Failed)` ignores the
  `partial` flag (only `fromDurable` consults it), so a failed candidate on a
  partial page shows generic failed copy instead of the PARTIAL row's wording.
- **LOW — indicator a11y counts:** `ChapterTranslationIndicator` passes
  `snapshot = null`, so the trusted-count clause of the chapter a11y
  description never renders there.
- **LOW — localization debt:** the pure mapper/notification copy are English
  source literals (the pre-existing notification pattern; the sheet hero uses
  `stringResource`, one new string added). Spec §2 requires localization; the
  pure-copy tradeoff was chosen for testability. Carry as i18n debt.
- **P4 Deviation-2 residue (settled-but-actually-partial legacy directories):**
  recorded in the notes ("legacy-directory counts remain the manifest's
  facts"); the residue itself — self-derived trusted counts for that legacy
  class — remains open and stays on the Phase-6 carry list. Not newly failing.
- **Commit/tag hygiene:** commit order matches §6.2 1:1; no tag created
  (ordering honored — acceptance precedes `checkpoint/t917-p5-done`).

## 9. Verdict rationale

No CRITICAL or HIGH findings. The data-truth core of Phase 5 — the binding
conditions A, B, C from the Phase-4 carry-list — is genuinely closed and
verified at the strongest available level: exhaustive sealed-outcome mapping,
store-terminality arbitration with preserved drain ordering, terminal truth
outranking timers, fail-closed preconditions making the timeout-write race
harmless, and two-layer stale-success suppression. The markPageTimedOut
narrowing survived its attack: detached-copy preconditioned patches protect
terminal states, and the only corner found (SKIPPED guard) is bounded,
outcome-identical to the old code, and self-healing. D12/D13/no-native-kill/
isolation/bounds all hold. Tests are honest, named, and test-first.

Acceptance carries two gate conditions and carries findings forward:

- **G1 (record correction, required before tagging):** amend
  `engineering/phase5-implementation-notes.md` §3 condition D (and deviation 5)
  to state accurately which surfaces consume the shared mappers — notification,
  drawer mini-chips/hero/subtitle, chapter indicator a11y, manga dialog, store
  placeholders do; reader page chip, reader overlay, and rolling-auto status do
  NOT (finding P5-1). The gate record must not claim surface integration that
  the code does not contain.
- **G2 (carry list, required before tagging):** add to the PHASE-LOG Phase-6
  carry list: the reader-surface rendering hop (`forManualOutcome`/
  `manualOutcomeFor` → page-holder feedback with identity fencing, spec
  §0.2.2/§6.2.8/§6.2.10); markPageTimedOut SKIPPED guard; notification
  catch-all gating on TRANSLATED; chapter-level D9 a11y retry mode.
- Findings forward (non-blocking): ACTION_STOP acknowledgement, partial+failed
  copy, indicator a11y counts, i18n of pure copy, D8-2/D11-1/D7-1 (existing).

VERDICT: ACCEPT-WITH-NOTES
