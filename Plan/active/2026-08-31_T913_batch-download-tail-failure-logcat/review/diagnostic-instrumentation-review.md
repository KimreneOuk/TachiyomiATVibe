# T913 independent review — batch-download diagnostic instrumentation

## Review disposition

**CHANGES REQUIRED BEFORE THE ONE-SHOT DEVICE CAPTURE.** Commit
`1a9fd886b5b02e00c1dd187ce122608a6411400c` compiles, its selected 19 tests
pass, the fixed `BatchDownloadTrace` tag is correct, and the values emitted by
the current call sites are privacy-safe. The download state machine, retry
count/delays, file cleanup, and request-generation fence were not intentionally
changed.

However, two high-severity observability gaps remain:

1. storage operations that fail by returning `false`/`null`, plus several SAF
   operations before the per-page `try`, do not produce a page/stage failure and
   can even be logged as `success`; and
2. a batch request attached after an already-running ordinary download started
   receives no page/validation trace, while handoff reacquires a different
   generation.

Either gap can defeat the requested diagnosis of a failure on the final one or
two pages. The patch should be tightened and covered by focused failure-path
tests before asking the Director to reproduce once.

## Findings

### F1 — HIGH: non-throwing and pre-`try` storage failures are not attributable to the page/stage

- **Likelihood:** Plausible, especially with SAF/document providers.
- **Nature:** Diagnostic defect over pre-existing product behavior.
- **Claim status:** **CONTRADICTION** to the claim that the exact temporary-file,
  rename, and finalization stage is diagnosable.
- **Evidence:**
  - Temporary-file lookup, deletion, and directory enumeration execute before
    the per-page `try` at
    `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt:739-750`.
    A throwable there bypasses `page_attempt_failed` and
    `page_terminal_failed`; it reaches the chapter catch with an `unknown`
    cause (`Downloader.kt:603-618`).
  - Network temp-file rename ignores the Boolean result and then emits the file
    as successful (`Downloader.kt:852-865`). `UniFile.renameTo` is demonstrably
    a Boolean API; other repository code checks it explicitly
    (`app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt:358-365`).
  - Cache-copy rename also ignores the Boolean result
    (`Downloader.kt:901-911`).
  - A false temp rename leaves a `.tmp` file. Validation excludes `.tmp`, but
    the page has already been marked `READY`, so the resulting trace is only
    `ready=expected`, `on_disk=expected-1`, `error_pages=none`, followed by
    generic `cause=storage` (`Downloader.kt:777-779,1052-1092`). It contains no
    failing page or `rename_temp` failure event.
  - Chapter directory and CBZ final renames also ignore their Boolean results
    (`Downloader.kt:579-581,1111-1118`) while the instrumentation emits
    `result=success` immediately afterward (`Downloader.kt:574-586`). This can
    make the trace actively misleading.
  - `tmpDir.listFiles().orEmpty()` maps a provider `null` result to an on-disk
    count of `0`, not `none` (`Downloader.kt:1057-1075`), contradicting the
    implementation report's stated `none` semantics for inability to
    enumerate.
- **Confirmation/refutation evidence needed:** A focused fake-`UniFile` test
  for (a) `renameTo == false`, (b) `listFiles == null`, and (c) a thrown
  lookup/list operation, asserting the page index, controlled stage, terminal
  outcome, and that no `success` event is emitted. A device trace showing a
  thrown network/write exception does not refute this finding because that is a
  different path.

### F2 — HIGH: a request attached to an already-running download can have no correlated page trace

- **Likelihood:** Situational but realistic; `queueChapters` explicitly accepts
  the `already_queued` case.
- **Nature:** Design limitation in generation correlation.
- **Claim status:** **CONTRADICTION** to end-to-end correlation for every
  translation-driven wait.
- **Evidence:**
  - `downloadChapter` snapshots the pending generation only once, before any
    page work (`Downloader.kt:402-404`), and passes that immutable nullable
    value through every page and validation call (`Downloader.kt:509-520`).
  - `pendingRequestGeneration` reads only the current live request state
    (`app/src/main/java/eu/kanade/translation/TranslationManager.kt:319-322`).
    If an ordinary download started before Batch Translate was tapped, the
    snapshot is `null`, so all page, validation, finalization, and download
    terminal events remain suppressed.
  - The later batch action can still report `queue_result=already_queued`
    (`Downloader.kt:348-390`) and attach its generation at
    `app/src/main/java/eu/kanade/translation/manager/TranslationRequestCoordinator.kt:73-82`.
  - Handoff independently reacquires the then-current generation
    (`Downloader.kt:645-686`). A cancel/re-request or late attach can therefore
    split one physical download between an old/null page generation and a new
    handoff generation. The `handoff ... result=success` event also means only
    that the Unit-returning callback did not throw; the callback may return
    early for a missing, terminal, or stale attachment
    (`TranslationRequestCoordinator.kt:499-523`).
- **Confirmation/refutation evidence needed:** A concurrency test that starts
  a download without a request, attaches a generation while a late page is in
  flight, and then fails that page. The required trace must correlate
  `already_queued`, the failing page/validation, and handoff/request outcome
  under a meaningful generation (or an explicit attach relation).

### F3 — MEDIUM: “ordinary downloads are unaffected” is true for log output, not for work performed

- **Likelihood:** Certain on every ordinary queue/download invocation; impact
  depends on batch and chapter size/provider latency.
- **Nature:** Performance/storage-I/O regression risk, not a state-machine
  change.
- **Claim status:** **CONTRADICTION** in the strict diagnostic-only sense.
- **Evidence:**
  - Queueing now allocates downloaded/queued/enqueued ID sets, records IDs, and
    traverses the entire caller list again before each trace is gated by a
    pending generation (`Downloader.kt:331-390,956-968`). These operations run
    for ordinary downloads even though no line is emitted.
  - Validation builds the complete `errorPages` list for every download,
    despite output being capped to eight only later
    (`Downloader.kt:1052-1055` and
    `app/src/main/java/eu/kanade/translation/diagnostics/BatchDownloadDiagnostics.kt:210-229`).
  - The original code returned immediately when ready count mismatched. The new
    code enumerates the chapter directory best-effort even when no translation
    request exists (`Downloader.kt:1069-1075`). That adds provider I/O on an
    already-failing ordinary download and can delay its terminal transition.
- **Confirmation/refutation evidence needed:** An ordinary-download test with
  no pending request and an instrumented provider proving no extra enumeration
  on ready mismatch, plus allocation/latency measurement for a large queue or
  chapter. Current tests assert neither.

### F4 — MEDIUM: cancellation is behaviorally preserved but the requested lifecycle trace is incomplete

- **Likelihood:** Situational.
- **Nature:** Diagnostic design limitation.
- **Claim status:** **PARTIALLY VERIFIED**.
- **Evidence:**
  - Page/network/handoff cancellation is still rethrown
    (`Downloader.kt:689-699,780-810,877-890`), so cancellation propagation is
    behaviorally preserved.
  - Stop, clear, and removal notifications can be inferred from central
    `request_phase.failure_kind` events through the existing coordinator paths
    (`TranslationRequestCoordinator.kt:237-274,334-390`).
  - No `download_terminal` event is emitted from the cancellation catch paths,
    and `pause()` only cancels and requeues the download without any diagnostic
    event (`Downloader.kt:192-198`). A paused download can therefore resemble a
    queue stall in the filtered trace. A direct request cancellation produces
    `request_cleared`, not a controlled cancel cause
    (`TranslationRequestCoordinator.kt:393-408`).
- **Confirmation/refutation evidence needed:** Tests/capture for pause, stop,
  queue clear, item removal, and request-only cancel, each asserting one
  unambiguous bounded boundary event and no admission.

### F5 — MEDIUM: the tests prove formatting, not the changed downloader event paths

- **Likelihood:** Certain coverage gap.
- **Nature:** Verification limitation.
- **Claim status:** **VERIFIED** as a gap; the reported pass count itself is
  accurate.
- **Evidence:**
  - `BatchDownloadDiagnosticsTest` has only three formatter/token tests
    (`app/src/test/java/eu/kanade/translation/diagnostics/BatchDownloadDiagnosticsTest.kt:9-44`).
    It does not invoke the downloader, retry loop, queue gating, cancellation,
    or Logcat sink/tag.
  - The only handoff-test change is one extra mocked
    `pendingRequestGeneration` call
    (`app/src/test/java/eu/kanade/tachiyomi/data/download/DownloaderHandoffFailureSplitTest.kt:159-164`);
    no diagnostic event is asserted.
  - There is no fake source/output failure test for the required sequence
    `page_attempt_failed -> page_terminal_failed -> validation -> download_terminal`,
    although the technical investigation explicitly called for one.
- **Confirmation/refutation evidence needed:** Add focused tests for thrown and
  non-throwing page/storage failures, four-attempt retry bounds, ordinary-log
  suppression, cancellation, and late generation attachment. Existing green
  tests cannot establish these claims.

### F6 — LOW: current output is privacy-safe, but the diagnostic API does not enforce the report's stronger claim

- **Likelihood:** Current leakage unlikely; future misuse possible.
- **Nature:** API-hardening/design limitation.
- **Claim status:** **VERIFIED** for current call sites; **CONTRADICTION** for
  “the trace API cannot accept titles/messages.”
- **Evidence:**
  - Current calls emit IDs, counts, booleans, enums, fixed result/state strings,
    and `Throwable.simpleName`; no URL, path, title, message, headers, or content
    reaches the tagged records (`BatchDownloadDiagnostics.kt:58-208,232-244`).
  - `finalization`, `downloadTerminal`, and `handoff` nevertheless accept raw
    `String` values (`BatchDownloadDiagnostics.kt:159-208`), and `safeToken`
    accepts any alphanumeric token (`BatchDownloadDiagnostics.kt:55-56,235-236`).
    A one-word title, API token, or message would be accepted unchanged.
  - The privacy test rejects a URL because punctuation violates the regex; it
    does not test an alphanumeric secret or enforce controlled result/state
    types (`BatchDownloadDiagnosticsTest.kt:26-34`).
- **Confirmation/refutation evidence needed:** A compile-time controlled type
  or a test/API boundary proving arbitrary alphanumeric strings cannot be
  emitted. Current call-site review is sufficient for this capture, but not for
  the stronger reusable-API claim.

## Claim audit

| Claim | Assessment | Primary evidence |
| --- | --- | --- |
| Retry count and delays unchanged | **VERIFIED** | Four total attempts and 2/4/8-second delays remain at `Downloader.kt:831-876`; diff against `1a9fd88^` preserves the predicate and delays. |
| Download ordering/state mutations/handoff fence unchanged | **VERIFIED**, with diagnostic overhead caveat | Existing mutations remain in order at `Downloader.kt:517-632`; generation fence remains at `TranslationRequestCoordinator.kt:499-525`. |
| Exact thrown source/network/create/write/detect failures are attributable | **VERIFIED** | Stage is set immediately around the relevant throwing calls at `Downloader.kt:833-864`; URL resolution is recorded at `Downloader.kt:482-506`. |
| Exact final-page/storage path is always attributable | **CONTRADICTION** | F1 and F2. |
| Tagged output is bounded | **VERIFIED** for emitted lines | No chunk/progress logs; failed network attempts are limited by the existing four attempts; error-page output is capped at eight at `BatchDownloadDiagnostics.kt:210-229`. Working allocations are not capped; see F3. |
| Tagged output is privacy-safe | **VERIFIED** for present call sites | Fixed tag and sanitized class names at `BatchDownloadDiagnostics.kt:52-56,232-244`; all inspected calls use controlled values. |
| Ordinary downloads emit no `BatchDownloadTrace` lines | **VERIFIED** | Downloader events require a non-null request generation at `Downloader.kt:403,409-416,956-968`; request events arise only from translation request operations. |
| Ordinary downloads incur no change | **CONTRADICTION** | F3. |
| Request locking/generation behavior is preserved | **VERIFIED** for product state; **PARTIAL** for trace correlation | Coordinator mutations still use `pendingRequestMutationLock` at `TranslationRequestCoordinator.kt:334-429`; F2 describes trace snapshot/reacquisition mismatch. |
| Log tag/filter are correct | **VERIFIED** | Tag is exactly `BatchDownloadTrace` and priority is INFO at `BatchDownloadDiagnostics.kt:52-53,243-245`; app installs an Android logger at VERBOSE in `app/src/main/java/eu/kanade/tachiyomi/App.kt:161-163`. The proposed `BatchDownloadTrace:V *:S` filter includes INFO. |

## Independent verification

At HEAD `1a9fd886b5b02e00c1dd187ce122608a6411400c`:

- **VERIFIED:** `:app:compileStandardDebugKotlin` and the three selected test
  classes completed successfully in an independent combined Gradle run.
- **VERIFIED:** the generated test XML reports 8 + 3 + 8 = **19 tests**, zero
  failures and zero errors.
- **VERIFIED:** `spotlessCheck` fails on the repository baseline with the three
  named leading files and “violations also present in 81 other files” (84
  total). None of the five touched Kotlin files appears in
  `app/build/spotless-clean/spotlessKotlin` after the run.
- **VERIFIED:** `git diff --check` passes and the worktree was clean before this
  review report was created.

These results validate compilation and the claimed selected-test outcome, but
they do not close F1-F5.

## Recommendation to Main Leader

Do not use the current build for the Director's single reproduction yet. First
close F1 and F2 and add the focused tests described in F5. F3 should at minimum
be gated so ordinary downloads retain their prior ready-mismatch fast path.
F4 and F6 are smaller hardening items, but an explicit pause/cancel boundary
would materially reduce ambiguity in a one-shot capture.

After those changes, the proposed PID-filtered command and fixed tag are
appropriate. Use a freshly requested chapter or explicitly prove the
already-running/`already_queued` path before giving the Director the click cue.
