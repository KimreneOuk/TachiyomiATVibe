# T917 Phase 5 — Implementation Notes (UI truth)

Implementer record for `phase5-product-spec.md` (§6.2 commit order, §5 test plan).
Branch `t917/coexistence-v3`, base `checkpoint/t917-p4-done`. No tag created —
tagging waits on Reviewer acceptance (§6.2.11).

## 1. Commit matrix (spec §6.2 order)

| # | Commit | Content | RED evidence |
|---|--------|---------|--------------|
| 1 | `e7ea1af` | persistence outcome RED tests | 5 named REDs (conditions A, B/C probe, batch snapshot warning); 31/31 neighbors green |
| 2 | `b9804f0` | preserve typed non-success persistence outcomes | A/B/C GREEN; no `Completed` on timeout/rejection/value failure |
| 3 | `9100f03` | outcome projection RED tests | 19 named REDs (Appendix A matrix, identity/stale-success, retry epochs) |
| 4 | `855d4e4` | expose bounded UI-truth projections | 19/0 + new `TranslationUiTruth` mapper + `TranslationScheduler.manualOutcomeFor` (read-only, cap 32) |
| 5 | `d968424` | terminal progress RED tests | 5 named REDs (terminal-only totals, trusted vs unknown, cancelled/partial counts, no fake 0/0) |
| 6 | `4941bb8` | make batch totals terminal and honest | 5/0; `cancelledPages`/`expectedPageCountTrusted` snapshot fields, `BatchHeroPhase.UNKNOWN_TOTAL` |
| 7 | `a25424b` | copy and accessibility RED tests | 13 named REDs, incl. two live D12 graph defects (native + HTTP+render placeholders) |
| 8 | `dfdb79f` | render unified copy and accessibility | 13/0; timer-named placeholders, pure notification copy, N2 multi-chapter body, a11y labels |
| 9 | `2cb3789` | visibility precedence red tests | 12 named REDs (self-healing silence vs visible outcomes, stale-state precedence) |
| 10 | `20a51b3` | enforce visibility budget and stale-state precedence | 12/0; `chapterSurfaceDecision` gate + notification caller enforcement |
| 11 | this commit | phase gate record | full sweep below |

Every RED failure message names its defect (grep `T917 P5 RED defect` in the
committed test files / recorded XML). Zero choreography timeouts: all waits are
barrier arrivals or `StateFlow.first {}` with the harness await bound.

## 2. Final regression sweep (XML-verified, not the Gradle banner)

`--tests eu.kanade.translation.{coexistence,translator,scheduling,ui,model,pipeline}.*`
`--tests eu.kanade.presentation.*` with `--rerun`:

**76 suites / 510 tests / 0 failures / 0 errors / 0 skipped.**

Includes `NormalMangaIsolationTest` (untouched green), all D1–D11 coexistence
suites, `P5HonestOutcomeTypingTest` (conditions A/B/C), the four new P5 suites
(`P5OutcomeProjectionTest` 19, `P5TerminalProgressTest` 5,
`P5CopyAndAccessibilityTest` 13, `P5VisibilityPrecedenceTest` 12), and the
updated `D8StallWatchdogTest`.

## 3. Binding named conditions — status

- **A (resume-null-on-success conflation):** the boundary inspects store
  terminality via the `buildTerminalPreparedPage`/`isPreparedPageTerminal`
  discipline before typing; a successful resume (durable render tail) types
  `Completed`, a real native timeout types `Failed`. Pinned in
  `P5HonestOutcomeTypingTest`.
- **B (D8-1):** an HTTP+render result-timer timeout with no landed durable
  result types `Failed` naming the HTTP+render timer; it never falls through
  to `Completed`.
- **C (P3 finding 5):** `PersistenceRejected` / failed-as-value surface as
  `Failed`-family outcomes and as the "Translation not saved — retry required"
  truth (snapshot `nonDurableFailure`, mapper `NOT_SAVED`, notification copy,
  visibility gate); no success wording anywhere on rejection.
- **D (state→surface→copy matrix):** page chip / reader overlay / notification
  / queue / progress / accessibility all read from the shared pure mappers
  (`TranslationUiTruth`, `TranslationNotificationCopy`); stalled surfacing,
  D9 manual-retry copy, D10 trusted-vs-unknown totals
  (`UNKNOWN_TOTAL` hero, "N pages available · source total unknown"
  notification), N2 multi-chapter dialog body, and the Deviation-2 residue
  framing (partial truth carried by `partial` page flag + `PARTIAL` chip
  truth; legacy-directory counts remain the manifest's facts) are pinned.

  > **G1 AMENDMENT (Main Leader, post-review — review/phase5-verification.md
  > finding P5-1 / condition G1):** the bullet above overclaimed as originally
  > written. Accurate original state: notification, drawer
  > mini-chips/hero/subtitle, chapter indicator a11y, the manga partial dialog,
  > the store placeholders, and the batch snapshot facts consumed the mappers;
  > the **reader page chip, reader overlay, and rolling-auto status did NOT**
  > (`forManualOutcome`/`forAutoSlot`/`manualOutcomeFor` had zero production
  > callers). Completed after review by commits `337ddc8` (RED 12 named) /
  > `388699d` (GREEN 15/0) — see §6 "Reader-surface hop (P5-1 completion)".
  > With those commits, condition D's surface claim is true as stated.

## 4. Deviations and judgment calls (recorded, none silent)

1. **Queue-position copy already compliant.** "Queued (Nth of M) — waiting for
   earlier batches" already satisfies the §2 matrix row and is pinned by
   `TranslationQueuePositionAndPhasesTest`; no new RED was manufactured for it.
2. **`markPageTimedOut` conservative skip narrowed (commit 8).** The committed
   RED exposed that for the most common production shape — a page whose
   FAST-inpaint lane already persisted a cleaned image before the HTTP+render
   timer fired — the placeholder write was skipped entirely, leaving the page
   silently `RUNNING` with no error (stranded-TRANSLATING defect). The skip
   now applies only to full no-progress pages; mid-pipeline pages keep their
   durable artifacts and get every still-open stage marked `FAILED` with the
   timer-named message. Store semantics (leases/quota/ledger/native-path)
   untouched. `markPageFailed`'s broader skip was left as-is (no committed RED
   pins it) — candidate Phase-6 hardening.
3. **`D8StallWatchdogTest` assertion updated.** It pinned the old
   `contains("100")` duration copy; D12 (spec §3.2) explicitly omits
   unmeasured durations and names the timer. The assertion now pins
   `"ONNX/native result timer expired"`. This is the named defect fix, not a
   test weakening.
4. **Commit-7 choreography note.** The HTTP-copy RED initially failed with a
   null placeholder for the reason in (2), not a copy mismatch — verified via
   a scratch diagnostic (deleted) that ran the choreography with and without a
   test-side `PROVIDER_START` arrival wait; both orders deterministically
   produce the timer-named placeholder once the writer is fixed. The committed
   test needed no edit.
5. **Visibility-gate caller surface.** §6.2.10 names reader, manga, drawer,
   and notification callers. The gate is wired at the announcer
   (`TranslationForegroundService`: identical-body coalescing + terminal
   cancelled/not-saved acknowledgement before the removal path). Reader,
   manga, and drawer are state-driven renderers of the same durable snapshots
   through the shared truth mappers — they make no independent
   announce/silence decisions, so no contradictory surface exists there;
   the closest-safe-behavior reading is recorded here.
6. **Trust default.** `expectedPageCountTrusted` defaults `false`; trust must
   be earned from the artifact manifest or a registered batch set. Two
   pre-existing fixtures were updated to declare trust explicitly.
7. **`TranslationUiTruth` exposure shape.** `manualOutcomeFor` is a read-only,
   identity-keyed accessor over the scheduler's existing bounded map (cap 32,
   unchanged) — no new scheduler state.
8. **Partial-download decision plumbing.** `partialDownloadBody` takes
   `Array<PartialDecision>`; `MangaScreen` maps each group chapter's
   `BatchAdmissionDecision` (`Partial` → counts, anything else → unknown
   total), fixing N2 for the whole group.

## 5. D13 statement

No performance numbers were measured in this phase. All capacity/latency
figures remain the spec's `[TARGET]` claims; the only numbers surfaced in UI
are page/state counters (trusted-total fractions, terminal counts), as
specified. Phase-6 measurement items carry forward from the phase-4 log
(permit-held-time cost of drain/placeholder writes, D11 write-behind, D7-1,
D8-2).

## 6. Reader-surface hop (P5-1 completion)

Closes review finding P5-1 and gate condition G2's reader-hop item (spec
§0.2.2, §6.2.8, §6.2.10). Commits `337ddc8` (RED) and `388699d` (GREEN) on
`t917/coexistence-v3`.

What was wired (all copy/precedence stays in the pure mappers; the reader only
projects):

- **Identity-fenced join** — `ReaderTranslationFeedback
  .readerManualOutcomeFeedback(chapterId, pageKey, attemptActive, lookup,
  nativeStall, durable)`: looks the typed outcome up with the holder's OWN
  (chapterId, pageKey) via `ReaderViewModel.manualSinglePageOutcome` →
  `TranslationScheduler.manualOutcomeFor`, so an outcome for a foreign page or
  chapter can never be returned; the D8 `TranslationPipeline.nativeStall` flow
  is fenced by the same pageKey comparison and only fills the stalled page
  when no scheduler outcome exists. The join wraps
  `TranslationUiTruth.forManualOutcome` verbatim into the new
  `ReaderPageFeedbackState.ManualTruth` carrier. `Completed` returns null
  (existing rendered state stands); a live durable attempt always owns the
  chip (Ticket-04 precedence kept; a previous intent's truth cannot paint over
  live stages). The D9 `exhausted` fact rides with the page's
  `hasExhaustedRetries`.
- **Page holders (pager + webtoon)** — `syncTranslationFeedback` feeds the
  manual truth into the existing `selectReaderPageFeedback` as the
  durable-position candidate: a newer terminal auto slot still outranks a
  typed truth (spec §1.2 rule 6). The native stall flow re-syncs the chip
  (pager: holder collector; webtoon: bind-fenced `stallJob`), so a stall is
  visible without a durable store emission. One bounded nullable field per
  holder; no new maps.
- **Coalescer** — `ManualTruth` bypasses stage ranking; a non-progress truth
  closes the attempt against stale stage callbacks, while a newer typed
  outcome supersedes a displayed terminal truth (the map only holds the latest
  outcome per identity). Rendering: mapper label verbatim; only
  progress-severity truth (attached) shows the running scrim; terminal truths
  use a polite live region; the auto ready-ahead suffix never rides a truth
  pill. Terminal truths persist (like Failed), satisfying the discoverability
  requirement when the transient pill would otherwise expire.
- **Rolling-auto status** — `ReaderAutoTranslationSlot.truth` is filled ONLY
  by `TranslationUiTruth.forAutoSlot` inside `projectReaderAutoTranslationUiState`;
  `AutoTranslationStatus` slot labels render the mapper copy, keeping
  "Auto failed — will retry when available." and "Auto failed — action
  required." distinct. The aggregate summary keeps its localized
  counts/reason precedence per spec §0.2.4/§2.1.

RED/GREEN evidence (`:app:testStandardDebugUnitTest` XML only, no
sleeps/polling/Robolectric):

- RED `337ddc8`: `ReaderManualOutcomeTruthTest` —
  `TEST-...ReaderManualOutcomeTruthTest.xml` tests=15 failures=12 errors=0
  (12 defect-named joins fail against the null-returning seam / sentinel slot
  truth; 3 boundary tests already truthful).
- GREEN `388699d`: same suite tests=15 failures=0 errors=0.
- Required sweep: 48 suites / 338 tests / 0 failures / 0 errors
  (coexistence, translator, scheduling, ui, presentation).
- Extra reader sweep: 7 suites / 59 tests / 0 failures / 0 errors
  (`eu.kanade.tachiyomi.ui.reader.*`).

Deviations:

1. **Slot-truth default sentinel.** `ReaderAutoTranslationSlot.truth` keeps a
   `forAutoSlot(Queued)` default so direct constructions (existing tests) stay
   valid; the production projection always supplies the mapped truth.
2. **Outcome-refresh latency.** The scheduler offers no completion event, so
   the chip refreshes on existing triggers (store flow, auto snapshot flow,
   stall flow, holder rebind/seed). Quiet paths (e.g. a governor pause with no
   further emissions) render on the next trigger; scope left unchanged to
   avoid touching scheduler semantics.
3. **`partial` flag not supplied.** `PageTranslation` carries no partial fact,
   so the join passes the mapper's `partial=false` default; durable-partial
   chip wording remains the mapper's `fromDurable` path when applicable.
