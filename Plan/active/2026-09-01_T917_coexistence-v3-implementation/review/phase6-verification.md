# T917 Phase 6 Verification — Reviewer Acceptance (on-device E2E, fixes F1 + F6)

Reviewer: independent Reviewer role (`docs/roles/reviewer.md`).
Scope: branch `t917/coexistence-v3`, commits `1812cf9..f8232ee` (5 commits:
F1 fix, E2E evidence, F6 investigation + fix, fix-verified evidence, screenshot
commit). Baseline p5 accepted (`review/phase5-verification.md`).
Executed record: `engineering/phase6-ondevice-verification-notes.md` (F1–F8),
`engineering/phase6-multipage-strand-investigation.md` (F6 root cause),
PHASE-LOG Phase 6 section. No tag exists for p6 (ordering honored — this
acceptance precedes `checkpoint/t917-p6-done`/release actions).

## VERDICT: ACCEPT-WITH-NOTES

One binding gate condition (G1) must be recorded Director-visibly before doc
promotion/sign-off: the PLAN §3 Phase 6 / DoD item "execute the §8.3
measurement protocol and record D13 measurements" was **not executed** and its
deferral is **not explicitly recorded** in the PHASE-LOG pending-close list
(finding P6-G1, MEDIUM below). Everything that WAS executed is trustworthy:
both defect fixes survived adversarial attack, the sweep evidence is
XML-verified by this review, the on-device evidence is genuine, and the carry
lists are honest. No CRITICAL or HIGH findings.

Method: full-diff reading of both production commits, the two new/extended
test suites, the harness seams they rely on, the store admission path
(`admitMutationLocked`/`ensureArtifactStoreLocked` call sites),
`PageWorkPlanner.planChapter`, the coordinator standard-lane inline loop,
`TranslationProvider.getMangaDir`, and `TranslationManager
.openOrCreateActiveChapterTranslationStoreImpl`; independent XML recount of
`app/build/test-results/testStandardDebugUnitTest/` (build dir intact — no
re-run needed); logcat greps over both local evidence logs; direct reads of
two committed screenshots. Evidence labels: VERIFIED / STRONG INFERENCE /
ASSUMPTION / UNKNOWN / CONTRADICTION.

---

## 1. F1 fix (`1812cf9`) — lazy store resolves artifact parent via fileCreator

**VERIFIED sound on all three attack vectors; lazy intent preserved.**

- **No side effect on open.** The creator is reachable only from
  `ensureArtifactStoreLocked()`, whose three call sites are all mutation
  paths: `admitMutationLocked` (`ChapterTranslationStore.kt:485`, reached from
  first-mutation admission), `persistArtifactMutationLocked` (`:1600`), and
  the `preRegisterPages` materialization at `:1413` — which is additionally
  gated on `artifactParent != null`, so the creator is last-resort only there.
  No open/read path invokes it: `openOrCreateActiveChapterTranslationStoreImpl`
  (`TranslationManager.kt:1326-1362`) only constructs the lazy store with the
  deferred closure `{ provider.getMangaDir(mangaTitle, source) }`
  (`:1356-1360`). Merely opening a chapter still creates nothing.
- **The creator runs at most once per store instance.** After the first
  successful `ensureArtifactStoreLocked`, `admitMutationLocked` grants at
  `:470-472` (artifactStore non-null) and `persistArtifactMutationLocked`
  re-enters at `:1602` without touching the creator. Bounded, no repeated
  SAF/disk churn under the mutex.
- **`runCatching` honesty: honest.** On creator failure,
  `getOrNull()` → null → `?: return false` → caller emits the honest
  `Rejected(LEGACY_RESCUE_FAILED)` (`:485-490`) — the F1 symptom becomes the
  designed failure path when directory creation genuinely fails. The failure
  is not silent in practice: `getMangaDir` logs ERROR before rethrowing
  (`TranslationProvider.kt:40-47`). The second new test pins exactly this
  rejection (`ChapterTranslationStoreArtifactMigrationTest.kt:751-763`).
  Parity claim VERIFIED: `getMangaDir` is the same create step the pipeline
  fallback uses (`SinglePageOnnxPhase.kt:238-241`). On-device corroboration:
  the entire `紙単行本告知_artifacts/` chain (manifest, 4 generation
  snapshots, committed pages, ledger) was created by this fix in the E2E run.
- **Pre-existing tripwires intact.** Kotlin `?:` short-circuit means the
  creator expression is never evaluated when `artifactParent` is non-null.
  Every pre-existing test pairing a creator with a parent keeps its
  throwing-creator tripwire (`ChapterTranslationStoreArtifactMigrationTest
  .kt:324,340,361`, `TranslationManagerArtifactReadTest.kt:175-176`); all
  other `fileCreator` test usages pass `null`. The first new test additionally
  asserts the negative side effect (no compatibility flat file appears,
  `:749-750`) — the false-TRANSLATED guarantee of the lazy store is pinned,
  not weakened. Sweep green (F1 class 21/21 in XML) confirms no tripwire fired.
- **Memory-only grant untouched:** `admitMutationLocked:477-483` still grants
  pure in-memory stores (all four nulls) before the rescue path; the F6
  negative-control store uses exactly this and stays persistence-free.

## 2. F6 fix (`d289ff0`) — live standard-lane unblock + origin-blind skip twin

**VERIFIED sound on all four attack vectors. The fix strictly narrows the
strand set; the skip twin is necessary, correctly placed, and does not regress
fingerprint discipline.**

### 2a. Origin-blind skip vs stale fingerprints — acceptable, no regression

- The twin (`completedStandardPageAfterPriorGap`, `BatchLaneWorkers.kt`
  ~:1223-1231) fires only for pages planned
  `WAIT_FOR_DEPENDENCY/PRIOR_PAGE_INCOMPLETE` whose **live** translation is
  READY/SKIPPED. For such pages, pre-fix behavior was an *unconditional* skip
  (the static `priorPageBlocksStandardTranslation` disjunct) — so a
  stale-config live-READY page is reused today exactly as before the fix.
  **No regression surface exists.**
- Pre-pass stale state is still governed by plan-time decisions: a page the
  resume planner marks as needing work (stale fingerprint) is the FIRST
  needs-work page (`planChapter`, `PageWorkPlanner.kt:113-136`), so it is
  never PRIOR_PAGE_INCOMPLETE-marked and gets re-translated with all writer
  fences (generation/pageVersion/lease/block fingerprints) armed. Only
  mid-pass commits (this run's batch work — current-config by construction —
  or manual work, D1-authoritative) can be live-READY behind a WAIT marker,
  which is precisely the population the twin must protect. The D2 regression
  the first fix attempt introduced (fingerprint-fenced skip double-paid a
  manually-owned page, paid calls 1→2) was caught by the sweep and resolved
  by exactly this origin-blindness — the D2 suite is green in the final XML.
- Render/layout gating untouched by the diff: the render join still gates on
  live `translationStatus READY/PARTIAL`, render commits remain
  fingerprint-fenced, and `plannedRenderNeedsWork`/plan consumers are
  unmodified. A twin-skipped page with a not-READY render renders from the
  existing (authoritative or current) translation through the fenced writer.

### 2b. No new strand/deadlock mode

- The live check is a lock-free `StateFlow.value` read (`store.state.value`
  in `naturalOrderPredecessorTerminal`, `BatchLaneWorkers.kt:177-185`) — no
  suspension, no lock acquisition, deadlock impossible.
- Textless predecessor: finalization sets translation SKIPPED; SKIPPED is in
  the terminal set → unblocks. VERIFIED in code.
- Predecessor held in another origin's lease (manual mid-flight): the
  successor stays blocked this pass (RUNNING/PENDING is not terminal) and
  resolves through the pre-existing D2/D3 machinery (defer-and-rescan,
  wait-and-attach) or, worst case, the reconciler's honest strand → ERROR —
  strictly narrower than the pre-fix all-successors strand. Nothing new.
- `index <= 0` returns "unblocked": correct for index 0 (no predecessor;
  and in practice page 0 carries the within-page reason, not
  PRIOR_PAGE_INCOMPLETE). Index −1 (pageKey absent from `orderedStreams`)
  conflates to unblocked — fail-open (translate rather than strand). LOW,
  finding F6-1.
- PARTIAL predecessor (partial translation, not READY/SKIPPED) keeps
  successors blocked in-pass → honest strand/ERROR until repair retry.
  Consistent with the ordered-context conservatism documented in the helper's
  docstring; reconciler-visible; recoverable. LOW, finding F6-2.

### 2c. The negative control pins the honesty property, and probes fail for the right reason

- The control DOES exercise the new conjunct specifically: in the control's
  store p0 is ocr READY/translation FAILED, so `planPage` marks p0
  needs-ordered-work and `planChapter` overwrites fresh p1's RUN decision to
  `WAIT_FOR_DEPENDENCY/PRIOR_PAGE_INCOMPLETE`
  (`PageWorkPlanner.kt:125-135` — RUN is not in the exemption set). By
  translate() time p1's own OCR is READY (in-pass OCR; the positive test
  proves the identical choreography reaches p1's transport when p0 is READY).
  Therefore the ONLY remaining skip condition for p1 is
  `!naturalOrderPredecessorTerminal` — if FAILED were ever (wrongly) treated
  as terminal, p1's transport starts and `p1Started shouldBe false` fails.
  The control pins the honest direction of exactly the new code.
- The positive test's RED probe (`NEGATIVE_PROBE_MS = 2_000`,
  `TranslationCoexistenceHarness.kt:153`) converts a timeout into the named
  AssertionError ("static PRIOR_PAGE_INCOMPLETE skip is permanent within a
  pass") with the `TimeoutCancellationException` chained as cause — a
  regression fails with the defect name, never a bare choreography timeout
  (`StandardLaneMultiPageCompletionTest.kt:47-60`). The probe window is
  validated by the positive test itself: full 3-page completion in 1.37 s
  (XML per-test time), far inside 2 s.
- The control's silence assertion is real, not a map-miss artifact:
  `launchBatch` pre-registers `transportStarted` deferreds for every page
  before lanes run (`TranslationCoexistenceHarness.kt:938-942`), so
  `getValue("p1")` returns an un-completed deferred. `transportCallsFor`
  sums across all transport instances (D7 exactly-once, `:1090-1092`), so
  `callsFor == 1` per page pins no-double-pay.

### 2d. Relationship to the "translateOutcome silent-Completed" carry item

- The fix is **not** dependent on the weak default: unblocked pages do real
  provider work; the silent `Completed(setOf(ref.pageKey))` default
  (`BatchLaneWorkers.kt:1163-1166`, consumed by the coordinator inline loop,
  `SequentialBatchCoordinator.kt:368-420`) remains reachable only on genuine
  skips (predecessor non-terminal, own OCR not ready), where the stranded-page
  reconciler still catches the strand honestly — proven live in the pre-fix
  run (outcome=ERROR, three stranded pages, logcat line 50073).
- The secondary hardening (explicit skip outcome + a translation-stage
  `stage_decision` diagnostic) was recommended by the investigation, NOT
  implemented, and is honestly carried in the PHASE-LOG carry list
  ("translateOutcome silent-Completed secondary hardening"). Residual
  consequence (carried, not new): a translation-stage skip is still invisible
  to batch diagnostics (the only batch `stageDecision` emitter is OCR).
  Finding F6-3, LOW/carry — the fix neither worsens nor hides it.

## 3. Evidence verification (reviewer-executed)

- **Sweep XML — VERIFIED.** Recounted
  `app/build/test-results/testStandardDebugUnitTest/`: **198 suites / 1446
  tests / 0 failures / 0 errors / 0 skipped** — matches the post-F6 claim
  exactly. All XML mtimes 2026-09-03 07:05:43, i.e. the sweep ran ~32 s before
  the F6 commit (07:06:15) — fresh, and no clean happened afterward; no
  re-run required. `StandardLaneMultiPageCompletionTest` present: 2 tests,
  0 failures (1.367 s + 3.219 s). `ChapterTranslationStoreArtifactMigration
  Test`: 21/21, 0 failures — matches the F1 claim. `NormalMangaIsolationTest`:
  1/0 — isolation untouched-green in the final state.
  Post-F1 "197/1444/0" is overwritten in the build dir → STRONG INFERENCE
  (arithmetic exactly consistent: +1 suite/+2 tests = 198/1446).
- **Logcat — VERIFIED.** `phase6-f6-fix-logcat.log` (local, 9.6 MB, per the
  repo-wide `*.log` gitignore): post-fix run 07:18–07:22 shows four distinct
  pages with translation 16634 / 8817 / 10474 / 8977 ms (`stage=translation
  ... success=true`, items=12/6/9/5), all four renders non-zero
  (14737/14289/15971/13635 ms), zero "stranded page" events, and
  `batch complete chapter=紙単行本告知 pages=4 outcome=TRANSLATED` (line
  63043). The pre-fix run in the same file matches the investigation
  byte-for-byte: translation/render 0 ms on pages 2–4, three stranded-page
  warnings, `pages=4 outcome=ERROR` (line 50073). `phase6-e2e-logcat.log`
  is present locally with exactly the claimed 2199 lines.
- **Screenshots — VERIFIED (spot-check ×2).** `p6-f6-complete.png`: sheet at
  100%, "Page 4 of 4", "4 of 4 pages ready to read", all four pipeline stages
  4/4 · 100%, page overview 1–4 green, "Read now (4 ready)".
  `p6-f6-reader-p3.png`: reader page 3/4 with English overlays "THANK YOU
  VERY MUCH", "BUY IT ~", "CULTURE 3 RELEASE!", "TALENT" — exactly the four
  the notes quote. All referenced `p6-f6-*`/`p6-r-*` screenshots are
  committed (`f8232ee`).
- **Commit/record match — VERIFIED.** PHASE-LOG Phase 6 names `1812cf9` and
  `d289ff0`; `git log` shows exactly those plus three docs/evidence commits
  (`877c640`, `8a92634`, `f8232ee`). Diff stats confine production changes to
  `ChapterTranslationStore.kt` (+16/−4), `TranslationManager.kt` (+5),
  `BatchLaneWorkers.kt` (+39/−1) — full diffs read: no lease/scheduler/
  native-path changes, no native kill, AI lane untouched (`!isAi` conjuncts
  only), normal-manga surfaces untouched.

## 4. Contracts

- **D13 — VERIFIED honored.** The only `[TARGET]` mention in the Phase-6
  documents is "no `[TARGET]` promoted" (PHASE-LOG:168). All numbers in the
  notes and log are evidence timings/counts from a single verification
  device, not UI/SLO claims.
- **Test honesty — VERIFIED with one noted deviation.** No Robolectric, no
  `Thread.sleep`, no `delay(` in either new/extended suite (grep clean);
  waits are `withTimeout` bounded deferreds and the pre-existing
  NEGATIVE_PROBE pattern. Fakes only at sanctioned seams: the harness real
  graph + FakeTransportTranslator (P1 seam) + real stores (`storeOverride`
  pre-existing; the negative control uses the memory-only real store).
  Deviation (F1-2, LOW): unlike P1–P5's separate RED/GREEN commits, both P6
  fixes landed as single commits containing test+fix, so RED-first is
  attested (notes, commit messages, named probe) but not provable from
  history. Mitigated by live on-device reproduction of both defects —
  stronger evidence than a JVM RED.
- **Carry lists — VERIFIED honored and extended.** Every Phase-5 carry item
  appears in the Phase-6 carry list (markPageTimedOut SKIPPED guard; F5
  catch-all gating; chapter-level D9 a11y; partial+failed copy; ACTION_STOP
  ack; indicator a11y counts; i18n; D7-1/D8-2/D11-1; Deviation-2 label; D6
  210 s re-validation; D11 write-behind) plus honest additions: F8 sheet
  Retry affordance, translateOutcome silent-Completed secondary hardening,
  mixed-ABI ONNX packaging decision (F7). F5 was confirmed live twice and
  correctly recorded as the existing carry item, not a new deviation; F8
  recorded as a new UX-gap carry item; F2/F3/F4 recorded as pre-existing/
  not-T917 without code changes.

## 5. Findings

- **P6-G1 (MEDIUM — record/gate gap, not a defect): the §8.3 measurement
  protocol was not executed, and its deferral is not explicitly recorded.**
  PLAN §3 Phase 6 and the DoD ("D13 measurements recorded") require the
  protocol (device tiers, fixed corpus, warm/cold, p50/p95/p99) before any
  number returns to user-facing/SLO status (draft §8.3). The executed phase
  was reframed as on-device E2E verification; the PHASE-LOG honestly states
  D13 non-promotion, but its pending-close list (promote draft → acceptance →
  sign-off → merge → tag) omits the unexecuted protocol, and the D6 210 s
  drain-bound re-validation and the D11 write-behind decision — both defined
  as waiting on Phase-6 measured stall data — therefore have no measurement
  basis yet (correctly carried, but unclosable without the protocol).
  Required before release: record the protocol's status Director-visibly in
  the PHASE-LOG pending-close list, and either execute §8.3 or obtain an
  explicit Director descope (leaving all numbers `[TARGET]`). Evidence:
  PLAN.md:81-84,112; draft §8.3; PHASE-LOG:167-171 (zero mentions of
  protocol/percentile/device-tier in the log — grep VERIFIED).
- **F6-1 (LOW):** `naturalOrderPredecessorTerminal` conflates "no
  predecessor" (index 0) with "pageKey not in orderedStreams" (index −1);
  both return unblocked. Fail-open direction (translate, not strand);
  unreachable in current wiring (refs come from the same page set). One-line
  guard or comment at `BatchLaneWorkers.kt:178-180`.
- **F6-2 (LOW):** a PARTIAL translationStatus predecessor keeps successors
  blocked in-pass → honest reconciler strand/ERROR until repair retry.
  Ordered-context conservatism, visible and recoverable; acceptable.
- **F6-3 (LOW, carried):** translation-stage skips remain
  diagnostics-invisible and the silent-Completed default remains reachable on
  genuine skips; masked by the honest reconciler. Already on the carry list.
- **F1-1 (LOW):** `runCatching` around `fileCreator` would swallow a
  CancellationException-class throw if the seam ever grows suspending or
  cancellation-aware callers. Today the lambda is non-suspending with no
  suspension point in scope — no exposure; hardening note only.
- **F1-2 (LOW):** single-commit RED for both fixes (attested, not
  commit-provable) — see §4 Test honesty.
- **P6-2 (LOW, housekeeping):** ~40 unreferenced session PNGs sit untracked
  in the engineering folder (browse/extension/nav shots). Clean up or ignore
  before merge to main. Logcat evidence files are local-only under the
  repo-wide `*.log` ignore — consistent with all prior phases' soak logs;
  committed screenshots carry the durable record.

## 6. Verdict rationale and gates remaining before release

No CRITICAL or HIGH findings. F1 survived its attack completely: the creator
is mutation-only, last-resort, bounded, honest on failure, tripwire-safe, and
was proven end-to-end on device (the durable chain it created is the E2E
evidence). F6 survived all four assigned attacks: origin-blindness is the
correct D1-compliant reading with no regression surface (pre-fix behavior for
live-READY pages was identical), no new strand or deadlock mode exists, the
negative control pins the honesty direction of exactly the new conjunct, and
both probes fail with named assertions inside a window the positive test
validates. The sweep, logcat, and screenshot evidence all check out at
primary-evidence level. D13 discipline, test honesty, seam discipline, and
carry-list fidelity are intact.

Acceptance carries one binding gate condition:

- **G1 (required before Director sign-off/doc promotion):** amend the
  PHASE-LOG Phase 6 pending-close list to state explicitly that the §8.3
  measurement protocol (PLAN §3 Phase 6 / DoD "D13 measurements recorded")
  has not been executed, and put the D6 210 s re-validation + D11
  write-behind decision explicitly behind it; the Director then either
  schedules the protocol or explicitly descopes it (numbers stay `[TARGET]`).
  Without this record, sign-off would silently close a DoD item.

Gates remaining after this acceptance (out of my scope, in order):
G1 record amendment → Director decision on §8.3 (execute vs descope) →
Director sign-off → draft promoted to canonical → merge `t917/coexistence-v3`
to `main` → tag `checkpoint/t917-release`. The standing carry list (§4 above)
travels into post-release backlog and does not block release.

VERDICT: ACCEPT-WITH-NOTES
