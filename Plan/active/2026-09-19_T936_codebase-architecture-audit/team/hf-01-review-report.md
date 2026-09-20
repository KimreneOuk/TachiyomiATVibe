# HF-01 — Independent Hotfix Review Report

Date: 2026-09-20
Reviewer: Independent Reviewer (adversarial verification; fix logic re-derived, call-site
census, XML/testcase-level inspection, archaeology spot-check)
Branch reviewed: `t936/hotfix-manual-translate-key-mismatch` @ `7f34b38`, base `main` @
`9555f13`. 3 commits; scope: resolver fix + diagnostic hardening + 1 new test file + docs.

## Verdict

**FAIL (blocking, one-token remedy — the fix code itself is correct and needs no changes)**

The key-mismatch fix is correct and complete, and the P2-00 diagnostic hardening is sound.
But **the hotfix's primary regression test does not execute**: JUnit silently skips it via
the repo's known T906 non-Unit-inferred-return quirk (XML-proven), so the exact
device-symptom invariant — "reader observation emits non-null under the rebound source
filename" — is unpinned. Merging a device-blocking fix whose regression test is dead is how
the bug returns; the remedy is a one-token change (`runBlocking<Unit>`) plus re-run.

---

## 1. Fix correctness — PASS (code verified independently)

`resolveReaderPageTranslationKey` (ReaderAutoTranslationPageResolver.kt:206) new precedence:

- **(a) both present:** `page.sourceFileName ?: page.translation?.sourceFileName` wins;
  the stale cached `translationStorageKey` is **overwritten** with the canonical name and
  returned — this is the fix: after a download rebind, writer and observer converge on
  `4.jpg`/`6.jpg` and the cache stops propagating the stale online key.
- **(b) sourceFileName null** (genuinely online pages, pre-download): falls through to the
  cached `translationStorageKey` — the pre-download fallback is preserved, so online-page
  behavior is unchanged from main.
- **(c) neither:** `onlinePageTranslationKey(imageUrl, url)` derived and cached — graceful.

The cache write-back in case (a) is what makes the fix robust: any direct reader of
`page.translationStorageKey` also sees the canonical key after the first resolution.

**Call-site consistency — single canonical derivation confirmed.** All reader key paths
funnel through this one helper: `ReaderViewModel.resolvePageKey` (L1792-1793) →
`resolveReaderPageTranslationKey`; `ReaderTranslationController.resolvePageKey` (L162)
delegates to the ViewModel method and every manual dispatch / keep-page / auto-window /
observation site (controller L184, 199, 239, 254, 673, 695, 717, 781, and
`observePageView`) uses it; the resolver's own observation path (L147) uses it directly.
Writer and observer cannot diverge post-fix.

## 2. No collateral key changes — PASS

The fix commit touches exactly three production/test files. The resolver diff contains only
the precedence change above — store pre-registration (`preRegisterPages`), batch paths,
artifact hydration, and `rekeyTranslationForCompletedDownload` are untouched. The report's
trace table (writer path unchanged; `rekey` intentionally returning for partial/mismatched
page sets, hence live-store reliance on the resolved key) is consistent with the code I read.

## 3. Tests — **FAIL (blocking)**

`ReaderPageTranslationKeyTest.kt` contains the two required behavioral tests, and the
assertion strength is appropriate — but **only one of them executes**:

- XML proof: `TEST-…ReaderPageTranslationKeyTest.xml` (from the final full Dev run,
  2026-09-20T14:54:22Z) shows `tests=1 skipped=0` with the single executed case being
  `writer and observer keys remain equal when page index differs from filename()`.
- The skipped test is `manual writer and reader observation share the rebound source
  filename` — declared as an expression body `= kotlinx.coroutines.runBlocking { … }` whose
  inferred return is non-Unit. This is the campaign's known **T906 silent-JUnit-skip quirk**
  (documented in `app/build.gradle.kts` L199-239: "The cure is runBlocking<Unit>"; the file
  is not in `config/runblocking-allowlist.txt`).
- Consequence: ticket task 4(a) — "manual translate publishes under the page's source file
  name AND the reader observation for that page emits non-null" — is **not pinned by any
  executing test**. The arithmetic in the report is consistent with this (2,025 + 2 added −
  1 silently skipped = the claimed 2,026), i.e. the "green" number concealed the skip.

**Remedy (one token):** declare `runBlocking<Unit> { … }` on the first test (or add it to
the allowlist per repo convention), re-run the focused test and one full flavor, and confirm
the class XML shows `tests=2`. No other change is needed; the fix code itself is approved as
written.

## 4. P2-00 diagnostic hardening — PASS

`OnnxRuntimeProvider.environment` now evaluates `getAvailableProviders()` inside
`runCatching` (failure → WARN + stable `query_failed:<ExceptionType>` sentinel) and emits
`[onnx_runtime] compiledProviders=…` at INFO **unconditionally, outside the try** — so a
query or logger failure can no longer remove the P1-05 evidence line (the ticket's
secondary anomaly: no compiledProviders line in the device buffer). The emission is a plain
`logcat` call consistent with the file's other unwrapped logcat usage; the only risky
operation (the provider query) remains caught — it cannot crash init, selects no provider,
and touches no routing. Additive-hardening only, as P2-00's constraints require.

## 5. Evidence — PASS WITH NOTE

- **XML: 2,026 × both flavors, 293 files each, 0 failures / 0 errors** (fresh, 09-20
  21:56 Dev / 21:45 Standard) — matches the report. The +1 net vs Phase 5's 2,025 is +2 new
  tests − 1 silently skipped (see Item 3).
- Focused reader/orchestration/scheduler suites, both-flavor compile, and
  `assembleDevDebug` all reported green; APK asset spot-checks in the report match the
  established clean profile (not re-inspected entry-by-entry — no discrepancy elsewhere).
- Flake ledger: four entries, all the established load-flake family
  (`BatchDispatchResumeWiringTest`, `StandardPipelineCoexistenceTest`,
  `StandardLaneMultiPageCompletionTest`) with isolation clears — consistent with the
  pattern this reviewer independently reproduced in Phases 2-4. One legit non-test failure
  (shared temp-root `AccessDeniedException` in JUnit cleanup) handled via isolated temp root.

## 6. Archaeology — PASS

Spot-checked `efb8dee` (2026-08-16, "rolling auto-translation coordinator and reader stage
feedback"): that commit introduced exactly the pre-fix helper shape
(`translationStorageKey?.let { return it }` before consulting `sourceFileName`, then
`onlinePageTranslationKey`) — i.e. the cache-first precedence predates the campaign
(`7262bf4` contains it), so the overlay stall is pre-existing exposure, not a Phase 1-5
regression. The report's verdict ("campaign exposed it, did not introduce it") is
evidence-backed.

## Conclusion

The fix is correct, minimal, and correctly scoped; the diagnostic hardening is sound; the
archaeology holds. Block the merge on the one-token T906 cure for the dead regression test —
for a device-blocking hotfix the regression pin must actually execute. After that commit and
a focused re-run showing `tests=2` for the class, this is merge-ready without further
review (re-verify XML only).
