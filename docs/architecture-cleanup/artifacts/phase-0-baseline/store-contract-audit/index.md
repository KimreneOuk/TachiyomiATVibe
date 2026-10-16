---
title: "Phase 0 — ChapterTranslationStore contract audit (read-only, failures #2–#5)"
kind: spec
comments: none
---

# Scope and method

READ-ONLY static audit of the four failing `ChapterTranslationStoreArtifactOpenProbeTest` methods, grounding the reviewer prerequisite from [failure triage](../build-and-tests/failure-triage/index.md). No Gradle/JUnit runs, no fixture edits, no repo changes; evidence = source at `4de121bf` + the preserved full-suite XML. No "fixture-only" or "production corruption" verdicts are made; the one closure-controlled experiment that could settle #4/#5 was NOT run.

All paths relative to `app/src/main/java/eu/kanade/translation/` unless noted. Test file: `app/src/test/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt` ("ProbeTest").

**Amendment (coordinator qualification, 2026-10-14):** claims of an "only source-consistent explanation" are downgraded to "strongest source-supported": rejection reasons are unobserved, and validation/fixture/reopen paths remain unresolved candidates. The disconnected image-probe seams do not alone eliminate image validation/path hypotheses. Baseline evidence remains preserved; no further runs or source changes authorized. See [Addendum 3 disposition](#addendum-3-disposition) for the reviewer-directed corrections applied to this artifact.

# A. Intended pre-registration / manifest materialization

| Layer | Finding |
| --- | --- |
| Spec guarantee | `ChapterTranslationStore.kt:3446-3470` (KDoc): placeholders are "intentionally memory-only"; expected-page COUNT is captured to the manifest "when an artifact parent is already available"; "Missing pages are never registered as page records; the manifest carries the absence." |
| Current behavior | `preRegisterPages` (`ChapterTranslationStore.kt:3472-3592`): puts a bare `PageTranslation(sourceFileName)` into live `pages` (3517), records `pendingArtifactPageRegistrations += pageKey` (3518, in-memory set, line 385 declaration), then publishes ONLY `expectedPageCount`/`expectedPageCountTrusted`/`partialBatchInfo` via `store.publishManifest(updated)` (3582) — **page entries untouched**. Entries materialize later in `persistArtifactMutationLocked` (3766): `registrationKeys = pendingArtifactPageRegistrations + pageKey` → CAS `publishSidecarPointersWithStaleRetry` writing `PageArtifactRecord(pageKey=it)` for each (3824-3886). The CAS is `ChapterArtifactEngine.publishSidecarPointersWithStaleRetry` (`ChapterArtifactEngine.kt:896-921`) — one fresh-manifest retry (`retryOnStaleManifest`, :2174), then `Rejected(reason)`. |
| Test assumption | ProbeTest:237 expects `manifest.pages.keys == {p1,p2,p3}` after preRegister + one RUNNING `updatePage` + `flush()`. |

**Contract reading (Addendum 3 corrected):** the KDoc sentence "Missing pages are never registered as page records" (3468-3469) sits inside the source-count/download-crosscheck paragraph (3458-3467) and refers to NOT-SUPPLIED pages of the source/download delta — not to the placeholders supplied via `preRegisterPages`. There is therefore NO unavoidable contradiction between the KDoc and mutation-bridge materialization: the stage-timing distinction (placeholders memory-only at pre-registration; entries materializing at later mutation publication) is plausible and consistent with both texts. What the intended materialization timing/semantics ARE still requires a complete contract trace — this audit did not settle it.

# B. RUNNING candidate eligibility

| Layer | Finding |
| --- | --- |
| Spec guarantee | `shouldPersistUpdate` (`ChapterTranslationStore.kt:4683-4709`): persist only durable progress (rendered image, non-empty blocks, cleaned image, stage failure, rendered→non-rendered transition); "Transient placeholder (RUNNING/PENDING/CANCELLED with no content and no failure): keep it in memory only." |
| Current behavior | `publishLazyLocked` (3712-3752): `shouldQueueArtifact = shouldPersistUpdate(previous, updated) \|\| artifactManifest?.pages?.containsKey(pageKey) != true` (3725-3726). A RUNNING-only update on a page ABSENT from the manifest IS queued (second disjunct true), drained by `flushPendingLazyMutationsWithJournalCapture` (1397) → `flushLazyMutationsLocked` (1349-1387) → `persistArtifactMutationLocked` (no re-filter), re-queued on rejection (1371-1376). |
| Test assumption | ProbeTest:237/#3 and ProbeTest:280 (PASSING comparator) both treat a RUNNING update + flush as publishing something observable. The passing test asserts only `artifactStatus()` (= `statusProjector.artifactStatus()`, `ChapterTranslationStore.kt:4637` — a projection, not a manifest read), so it never exercises the materialization question. |

# C. READY candidate promotion / display / reopen evidence

| Layer | Finding |
| --- | --- |
| Spec guarantee | `persistArtifactMutationLocked` KDoc (3760-3765): "Every emission is first written to a candidate snapshot. A display-ready emission writes the immutable committed snapshot and moves the manifest pointer in one final publication." Display readiness = `updated.hasRenderedResult \|\| updated.isTextlessTerminal` (4029); `hasRenderedResult` = `isTranslationDisplayReady` (`model/PageTranslationState.kt:70`). Live-side promotion: `promoteDisplayIfReadyLocked` (4142-4166), gated the same way. |
| Current behavior | Display-ready mutations take the fused `store.promoteLiveCandidateImmediately` (4030-4040); non-ready take `persistLiveCandidate` (4042-4051). Any `TransactionOutcome.Rejected` → WARN log + `reject(reason)` (4055+) → `lastArtifactPublicationRejectionDiagnostic` set (3775-3787; surfaced into typed PatchResults at 2033/2126/2153, else "reason unavailable"). `updatePage` (3015) RETURNS UNIT: the compatibility wrapper consumes the guarded result internally — callers see rejection only via WARN logs and `rejected(...)` diagnostics (3017, 3025-3040), never a return value. Reopen: `openArtifactSuspend` "installs only the state reconstructed from its ACKed journal prefix" (4799) + manifest; display is committed-only via `displaySnapshotLocked` (4172-4188, committedDisplay or live page). |
| Test assumption | ProbeTest:514 expects the second reopen's committed display to carry the cleaned image written in the reopened session. The compatibility wrapper offers no return value to check (updatePage: Unit, 3015), so the test cannot observe rejection directly. |

# D. Manifest-writer handle / job ownership (teardown contract)

| Component | Ownership |
| --- | --- |
| Journal writer | Created via `ensureJournalWriter()` as early as `preRegisterPages` (3486-3495) or mutation paths. Allocation is FILE-FREE until a record is appended: a persistent channel exists only for a segment opened via `FileChapterJournalStorage.openSegment` (`ChapterJournalWriter.kt:296-311`, `FileChannel.open` :303 → `FileSink(channel)` :328); `truncateSegment`'s `RandomAccessFile` (285) closes via `.use`. Opened segment channels close ONLY via `journalWriter?.drainAndClose()` inside `closeAndFlush()` (4717-4725) or `close()` (4730-4737). |
| Manifest document | `ChapterDocumentIo.write` opens `target.openOutputStream().use { … }` per call (`ChapterDocumentIo.kt:208-210`) — per-write streams, closed; renames via `renameNoReplace`/`renameOwned`. No persistent manifest handle found in static reading. |
| Retention/drain jobs | `artifactEngine.cancelRetentionWorkAndJoin()` joined in `closeAndFlush`/`close` finally (4723, 4735); drain job joined with timeout in `StoreWriteDrainCoordinator.closeAndFlush` (`StoreWriteDrainCoordinator.kt:96-133`). |
| Test teardown | ProbeTest:301/#4 ends at kt:315 and ProbeTest:569/#5 ends at kt:593 — NEITHER calls `closeAndFlush()`/`close()`. Whether any journal segment channel was actually OPEN at `@TempDir` close in these tests was NOT observed; the journal-handle contribution is UNPROVEN. |
| Observed lock | Suppressed exceptions in the preserved XML name `Chapter 2.manifest.json` / `Chapter 8.manifest.json` as the undeletable paths (`FileSystemException: … being used by another process`), NOT a journal segment. |

**Gap statement:** the store's documented teardown is `closeAndFlush()`/`close()`; the two tests skip it. No "leak is REAL" claim is made: a journal channel would exist only if a segment had been opened (file-free until first record), and no fixture execution observed a channel allocated-and-unclosed. The manifest lock owner is UNRESOLVED: no in-process manifest holder was found in static reading (per-write streams only), and EXTERNAL-PROCESS speculation (Windows Defender/indexer) is explicitly UNTESTED — named as possible with no exclusion evidence, until a closure-controlled observation names the holder.

# Per-failure synthesis

## #3 `pre-registered pages publish one durable baseline with only the changed candidate` (ProbeTest:237, fails kt:253)

- **Dataflow:** preRegister writes count-only manifest (observed on disk: `expectedPageCount=3, trusted=true` — test lines 246-247 PASS) and keeps {p1,p2,p3} in `pendingArtifactPageRegistrations`. `updatePage(p1 RUNNING)` → queued (manifest lacks p1). `flush()` → `persistArtifactMutationLocked` → registration CAS publishes entries {p1,p2,p3}, then candidate write.
- **Observed:** manifest after flush = count kept, `pages=[]`.
- **Missing evidence:** the runtime rejection reason. A registration-CAS `Rejected` → `return reject(...)` (3887-3893) before any entry write, with the mutation re-queued (1371-1376) and never retried (single flush), is PLAUSIBLE for the count-kept/entries-absent shape — NOT PROVEN; rejection itself is unobserved. `lastArtifactPublicationRejectionDiagnostic`/logcat WARN "artifact page registration failed … reason=…" would name it if rejection occurred.
- **Unexcluded alternatives (per Addendum 3):** earlier paths BEFORE the CAS — engine initialization failure or manifest UNAVAILABLE/null at drain time; queue anomalies (mutation never queued, drain finding nothing); fixture-shape differences vs the passing comparators; promotion-side or reopen-side divergence — all unexcluded by this static audit.
- **Working explanation (plausible, not proven):** the page-registration CAS rejected and the rejection was logged-only. WHAT causes it (stale manifest vs durable-load vs pointer-document precondition) is not determinable statically.
- **Discriminating experiment (needs authorization):** capture `lastArtifactPublicationRejectionDiagnostic`/WARN log after the failing flush (diagnostic-only test edit), and read the manifest bytes before/after `flush()`.

## #2 `lazy store cuts over before first candidate and reopens from artifact pointers` (ProbeTest:514, fails at the ~563 `shouldBe`)

- **Dataflow:** session 1: RUNNING-with-blocks update (`blocks.isNotEmpty` → durable) → candidate published (test line 542 `candidate?.pageSnapshotFileName shouldNotBe null` PASSES) → close. Reopen: state rehydrated RUNNING (line 545 PASSES). Session 2: all-READY + `cleanedImageName` + `CURRENT_INPAINT_REVISION` → `shouldPersistUpdate` true → `promoteLiveCandidateImmediately` fused commit → close → reopen: committed display observed null (kt:563).
- **Missing evidence:** whether session 2's fused promotion Committed or Rejected. `updatePage` returns Unit (3015), so the test has no return value to inspect, and it never reads the manifest between session-2 close and final reopen.
- **Working explanation (plausible, not proven):** a rejected/lost final publication in session 2 (same silent-rejection surface as #3), OR a committed pointer that never reached the document before close. Not decidable statically; the live `committedDisplay` promotion (4142) succeeds in-memory, so a silent durable-side rejection reproduces the observation exactly.
- **Discriminating experiment (needs authorization):** observability via the EXISTING GUARDED API — `updatePageGuarded`/`patchPage` with a correct snapshot/precondition — which returns the typed PatchResult (reason at 2033/2126/2153), or diagnostic log/field evidence. NOTE: the guarded API is an ALTERNATE diagnostic surface, NOT automatically equivalent to the compatibility wrapper — e.g., the wrapper rejects while a page lease is active (WARN, 3025-3027), a condition the guarded path must be driven with the matching snapshot/precondition to compare meaningfully. Complement: read `readManifest("Chapter 2").pages["page.jpg"].committed` immediately after session-2 `closeAndFlush()` (before the final reopen). This splits "rejected at write" from "written but not seen on reopen" (the latter would point at reopen/journal-prefix reconstruction — cf. passing `artifact-authoritative reopen keeps committed display while incomplete candidate resumes`).

## #4 `artifact-only lazy store writes pages without materializing flat compatibility file` (ProbeTest:301) and #5 `lazy store without parent resolves artifact authority through artifactParentResolver` (ProbeTest:569)

- **Dataflow:** both bodies PASS all assertions; both leave the store open (kt:315 / kt:593). `@TempDir` close fails deleting `Chapter {2,8}.manifest.json` with the suppressed `FileSystemException`.
- **Missing evidence:** the handle holder. Whether a journal segment channel was open at `@TempDir` close is UNOBSERVED (channels exist only after `openSegment`; file-free until a record is appended), so the journal-handle contribution is unproven; per-write manifest streams close; no static in-process manifest holder found.
- **Strongest source-supported explanation:** NONE proven. Open candidates: unidentified in-process holder vs external process (named possible until owner observed).
- **Discriminating experiment (needs authorization):** closure-controlled variant of the SAME fixtures with explicit `closeAndFlush()` (and, as a second variant, with the store closed but journal writer held) — observing which change removes the `FileSystemException` identifies the owner class. Pure diagnostic; Phase 0 authorizes no fixture commits.

# Auxiliary finding (relevant to fixture design, no failure attribution)

The probe seam is disconnected from publication: `ChapterTranslationStore.artifactImageProbe` (companion, `ChapterTranslationStore.kt:4780`) has NO read sites in main, and `ChapterArtifactEngine.displayBaseProbe` (`ChapterArtifactEngine.kt:77`) is never invoked elsewhere in the engine. `installPngHeaderProbe()` (ProbeTest:98) therefore may be vestigial for these paths; cleaned-image file locations (`image-fixtures/`, `Chapter 2_images/`) are test-local conventions and nothing in the audited publication paths resolves them. SCOPE OF THIS OBSERVATION (per Addendum 3): UNUSED PROBES DO NOT ELIMINATE image/file eligibility hypotheses — the seams not being consulted by the audited paths shows only that they do not gate THOSE paths; decode/validation or path-dependent behavior exercised elsewhere in the pipeline or in code outside this audit's scope remains possible, so no "wrong image directory" or similar hypothesis is removed.

# Required follow-ups (all gated, none authorized in Phase 0)

1. Runtime rejection-reason capture for #2/#3 (diagnostic test edit or log capture).
2. Post-close manifest read for #2 (split write-rejection vs reopen-loss).
3. Closure-controlled handle-owner experiment for #4/#5.
4. Complete contract trace of intended registration-materialization timing/semantics (memory-only pre-registration stage vs mutation-time materialization, 3824-3886) and reopened-session republish/fence semantics for #2 — static mapping only; runtime discrimination outstanding.

# Addendum 3 disposition

Reviewer: Gemini Antigravity, Addendum 3 (relayed by coordinator, 2026-10-14). Corrections applied to THIS artifact only — no new tests, no Gradle runs, no source changes; baseline evidence preserved.

1. **updatePage returns Unit** (`ChapterTranslationStore.kt:3015`): the "assert updatePage's PatchResult" diagnostic was removed. Replaced with observability via the existing guarded API (`updatePageGuarded`/`patchPage` with a correct snapshot/precondition) or diagnostic log/field evidence. The guarded API is explicitly distinguished from the original compatibility wrapper — an alternate diagnostic surface, NOT automatically equivalent (e.g., wrapper rejects while a page lease is active, WARN 3025-3027).
2. **KDoc "Missing pages" misread corrected**: `ChapterTranslationStore.kt:3468-3469` refers to NOT-SUPPLIED pages of the source/download delta (paragraph 3458-3467), not to supplied placeholders. The alleged unavoidable contradiction and the misread-based contract-ruling follow-up were removed. The stage-timing distinction — placeholders memory-only at pre-registration vs later mutation-time materialization — is recorded as PLAUSIBLE; the intended materialization still requires a complete contract trace.
3. **#3 single-cause claim removed**: registration-CAS rejection is labeled PLAUSIBLE, NOT PROVEN; earlier alternatives are enumerated as unexcluded — initialization failure, manifest UNAVAILABLE/null at drain, queue anomalies, fixture-shape differences, promotion/reopen divergence.
4. **Journal-writer claims corrected**: allocation is file-free until a record is appended; `RandomAccessFile` (285) closes via `.use`; the `openSegment` `FileChannel` (303) is persistent only IF opened. "Leak is REAL" removed — channel-open state at `@TempDir` close in #4/#5 is UNOBSERVED. Manifest lock owner UNRESOLVED; external indexer speculation explicitly UNTESTED, with no exclusion.
5. **Probe finding scoped**: unused probes do not eliminate image/file eligibility hypotheses; the "removes wrong image directory from hypothesis space" phrasing is removed.
6. **Frontmatter**: `status` field removed — a `spec` must not carry a status suggesting gate closure.

**Audit status:** complete ONLY as a static mapping of contract text, current behavior, and test assumptions at `4de121bf`. Runtime discrimination — which enumerated alternative is ACTUAL — remains OUTSTANDING and requires the gated follow-ups above. The early gate stands: baseline contract/source/fixture/cleanup investigation is required BEFORE Phase 3/4 entry. NO phase-complete claim is made.
