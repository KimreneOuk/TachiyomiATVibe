# T934 — Verification checklist

Legend: [ ] pending · [x] verified by Main Leader with recorded evidence.
Every [x] requires: diff reviewed + targeted test output (names, counts) +
file:line. No assertion weakened anywhere (checked per lane).

## W0 — Baseline

- [ ] W0.1 Dirty diff classified (E-fixes vs prior in-flight work) — note classification here:
- [ ] W0.2 Full suite `:app:testStandardDebugUnitTest` green on dirty tree — result:
- [ ] W0.3 Branch `t934/resume-rebuild-and-parallelism` created at `8010961` + baseline commit — commit:
- [ ] W0.4 Tree clean after baseline (`git status` empty of tracked changes)

## R1 — Lease abort/re-work fix

- [ ] R1.1 Owner-proof heal implemented in BatchWriteGate:
      token-mismatch reject → re-acquire BATCH lease; Denied → typed contention
      yield (existing semantics); Granted AND generation+candidateGenerationId
      match → refresh identity from the GRANTED lease, retry once, max.
      Evidence (file:line):
- [ ] R1.2 Never-heal guards: no heal from bare `store.snapshot` re-arm; no
      heal on absent-lease ("page lease required") rejects. Evidence:
- [ ] R1.3 Flip-source fix: envelope completion cannot release a page whose
      write identity overlap-inpaint still holds. Evidence:
- [ ] R1.4 New test: token-flip mid-write heals via re-acquire (same run
      identity) and write succeeds — class/test name:
- [ ] R1.5 New test: MANUAL/AUTO lease holder → heal denied → typed contention
      exception, NO write — class/test name:
- [ ] R1.6 New test: resumed-run identity (candidateGenerationId mismatch) →
      no heal — class/test name:
- [ ] R1.7 New test: envelope release no longer invalidates live overlap
      identity (the T1→T2 reproduction) — class/test name:
- [ ] R1.8 Existing fences green: BatchWriteGateHealTest + T917 contention/
      coexistence family unedited and passing — test output:
- [ ] R1.9 No net assertion weakened — diff audit note:

## U — Phase truth + simplified UI

- [ ] U.1 Phase + payload in projection derived from run-record states/counters;
      no competing enum; rebuild/restore phases distinguishable — file:line:
- [ ] U.2 Sheet Simple view: hero + ready chip + progress bar (indeterminate in
      rebuild) + ONE status line + one-line failure notice (failures>0) +
      actions; Advanced = stages + page grid + failure groups + queue detail;
      toggle persisted — file:line:
- [ ] U.3 Status-line priority chain implemented: requestState → queuePosition
      → pauseReason → coordinator phase+counters → batchPhase — file:line:
- [ ] U.4 BottomReaderBar consumes the same phase truth (no frozen "Batch X/Y"
      during rebuild) — file:line:
- [ ] U.5 "Resuming…" snapshot-driven; synchronous self-reset removed — file:line:
- [ ] U.6 All NEW copy routed through TranslationUiTruth / string resources;
      truth layer extended, existing truth assertions unedited — file:line:
- [ ] U.7 Truth/projection tests: rebuild→running→finished transitions +
      reader-bar copy mapping — class/test names:
- [ ] U.8 Existing UI-truth + projector tests green — test output:

## R2a — Write-time digests + typed adoption failures

- [ ] R2a.1 Per-page source SHA-256 recorded durably at first admission — file:line:
- [ ] R2a.2 Run start consumes recorded digests; whole-chapter re-hash removed — file:line:
- [ ] R2a.3 Test: second run with unchanged sources performs zero source
      re-hash (hasher call-count assertion) — class/test name:
- [ ] R2a.4 Test: changed source detected at consumption, fails closed (no
      stale reuse) — class/test name:
- [ ] R2a.5 Typed adoption-failure reasons (NO_POINTER, SIDE_CAR_UNREADABLE,
      SHA_MISMATCH, LEASE_DENIED, MERGE_REJECTED, BUNDLE_MISSING) in WARN +
      run-record counters — file:line + test:
- [ ] R2a.6 D5/D6/D9/D10/D11 test files byte-identical (git diff empty) — verified:

## R2c — Consolidation spike

- [ ] R2c.1 Report filed: both options sized (touchpoints, seam risk, test
      impact, migration order) + recommendation — path:

## I — Inpaint decoupling

- [ ] I.1 Gate relaxed: OCR-final page is a candidate regardless of translation
      status; textless/already-inpainted skips preserved — file:line:
- [ ] I.2 "No inpaint during OCR preflight" still enforced — file:line + test:
- [ ] I.3 Bitmap-budget concurrency caps unchanged — file:line:
- [ ] I.4 Test: OCR READY + translation PENDING page gets inpainted — test name:
- [ ] I.5 Test: displayReady still requires translation terminal + cleaned
      image (promotion gate untouched) — test name:
- [ ] I.6 Scheduler test conversions documented, semantics preserved — note:

## Wave gates

- [ ] GATE-W1: full suite green after R1 + U commits — result:
- [ ] GATE-W2: full suite green after R2a + I commits — result:
- [ ] On-device smoke: resume shows "Rebuilding pipeline…" then completes;
      no lease-abort storm in logcat — evidence:
