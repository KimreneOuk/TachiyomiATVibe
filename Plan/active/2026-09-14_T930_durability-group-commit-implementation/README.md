# T930 — Durability group-commit package: implementation plan (PROPOSAL, awaiting Director approval)

Derived from T928 (audit) + T929 (verified solution map, red-team rework
conditions). Base: `main` @ `9c19ad0`. Scope: the whole-manifest rewrite fix
(io S1/S2/S3/S4/S7 + N1/N2 prerequisites) plus the UI admission-signal changes
that share the publish path (R1, R2-restricted). Scheduler-side items (S7/B1
steering, S8 gap rescan, S3 warm-up) are a separate follow-on package.

## Design principle

The existing ownership machinery — ChapterTranslationStore mutex,
PageStageLeaseTable (one writer origin per page), BatchWriteIdentity fenced
writes, generation records — is UNTOUCHED. Group commit only changes the
write-behind boundary: stage mutations accumulate in an in-memory staged
buffer inside the store (already the single mutex owner) and publish as ONE
combined manifest transaction at defined commit points.

## Slice A — foundation (no behavior change, all paths identical)

1. **Commit-point contract** (codified, tested): CommitPoint = {
   OCR checkpoint CLOSE; page-terminal promotion; chapter phase records;
   chapter COMPLETE; user STOP drain; explicit flush requests from fenced
   CAS seams }. Everything else becomes stageable.
2. **Writer registry (N2)**: process-wide (chapterId → writer origin) in
   ActiveChapterStoreRegistry; ALL writers register — store, probe stores,
   DurableChapterStatusResolver, LegacyChapterMigrationSource, health-verify.
   Disk CAS retained at the LI-4 republish seams (verify-io condition).
3. **Schema-guard cache**: future-schema primary/backup read once per store
   instance; invalidated on migration/upgrade.
4. **Event-driven retention**: per-commit known-orphan deletion; full
   reachability crawl only at open (>8 pages deferred), teardown, user reset.

## Slice B — group commit activation (behind flag, default OFF)

5. **Staged mutations + combined publish** at commit points or 250 ms debounce
   (first wins). Fenced preconditions (checkpoint CAS, promotion compare)
   force-flush BEFORE comparing — the durable manifest remains the comparison
   truth at those seams (red-team X4/X6 condition).
6. **Candidate-promotion merge**: display-ready writes publish committed +
   generation + manifest directly; intermediate candidate publish skipped
   (content-equal). Retryable-candidate case keeps today's two-step.
7. **Read-back elision** on File-backed local storage (parse-validate only);
   SAF keeps the byte-compare.
8. **Drain-to-commit stop (N1)**: translator stop/cancel finishes the in-flight
   page to its next commit point, publishes, then releases. User stop becomes
   lossless; crash semantics unchanged (stage re-runs from last publish,
   identical to today's RUNNING→FAILED_RETRYABLE recovery).

## Slice C — publish-path UI changes (R1 + R2-restricted)

9. **Admission signal (R1)**: manual/auto/batch page admission emits
   in-memory Queued truth (identity-fenced) before any pipeline work.
10. **UI-before-persist, restricted (R2 per X5)**: transient (non-durable)
    stage updates publish StateFlows before staging; durable results keep
    persist-first. No stage that can roll back is ever shown.

## Coexistence impact

- **Manual**: lease/evict semantics unchanged; store mutex held for ~1
  combined publish instead of ~7; first stage visible at admission (R1).
- **Auto**: identical write body (PageWriteOrigin.AUTO); suppression rules
  untouched; eviction-by-manual unchanged.
- **Batch**: checkpoint CAS and run records force-flush at their fences;
  BatchWriteIdentity preconditions compare durable truth exactly as today;
  biggest op reduction lands here (per-page checkpoint + record batching).

## Race / edge-case register (each carries a dedicated test)

| # | Hazard | Mitigation |
|---|---|---|
| 1 | Staged state vs fenced CAS (batch preflight) | force-flush before compare (5) |
| 2 | Probe/rescue/migration second writers | writer registry (2); disk CAS at LI-4 seams |
| 3 | Process death with staged unpublished state | recovery = today's stale-writer path; checkpoints always durable |
| 4 | User stop mid-page (NEW hazard from staging) | drain-to-commit stop (8) |
| 5 | UI stage that later reverts | R2 restricted to eventually-certain transients (10) |
| 6 | Retention deleting a file staged state references | orphan = unreachable from BOTH durable and staged (4) |
| 7 | Manual tap during batch OCR (corpus gap) | UNCHANGED in this package (lease + defer); fixed later by S8 gap rescan |
| 8 | Queue reorder vs live chapter | out of scope here (steering package); reorder-only design already verified |
| 9 | Glossary pending-version vs durable stamp | staged pending-version stamping (D5 rider) |
| 10 | Auto+manual same page | unchanged lease-table arbitration; both origins serialize on the store mutex as today |

## Verification ladder

1. Unit: commit-point contract — inject process death at every staged-buffer
   boundary; assert manifest parses, sidecar-before-pointer, committed display
   never revoked, resume adopts checkpoints without re-paying OCR.
2. Coexistence matrix: extend the T916/T924/T925-derived suites (manual tap
   during batch OCR; auto window during staged publish; probe write during
   staged window; stop-drain mid-envelope; resume after each commit point).
3. Property tests: single-writer, no partial commit, monotonic display.
4. Soak behind flag OFF→ON with TranslationTrace diagnostics; acceptance
   metrics: durable ops/page (~113→~55-65), first-stage-visible latency
   (tap→pill), zero new PAUSE/REJECTED outcomes vs baseline probe.

## Rollout

Slices land in order A→B→C; B and C independently flag-gated and revertible.
No invariant ever depends on a later slice. Expected effect: −44-48% durable
ops/page (blended ~113 baseline; corrected audit range ~105–180), O(P²)→O(P)
manifest bytes, visible admission feedback, lossless user stop within the
drain grace window.

## Amendments (2026-09-14 adversarial review — BINDING on implementation)

Four-agent red-team round (reports: T929 `team/verify-final-*/report.md`).
No attack overturned the core design; the following fix plan-text defects
found by literal-reading attacks. **Items A–C are pre-approval blockers.**

**A. Slice A item 3 (schema-guard cache) — RESCOPED.** Cache applies to the
schema-NORMALIZATION decision only (`normalizeSupportedSchema`,
ChapterArtifactStore.kt:1751-1756). The future-schema guard reads at :130
(loadOrMigrate) and :230 (readManifest — also the CAS reference at :1642)
are NEVER cached: always fresh from disk. The v1 wording implemented
literally deletes a future-schema backup that appears mid-instance
(ChapterArtifactStoreTest.kt:620-636 fails flag-OFF; production equivalent:
an older build deletes a newer build's document — the exact scenario the
guard exists to prevent, ChapterArtifactStore.kt:131-137).

**B. Slice A item 2 (writer registry) — enforce-vs-record stated.**
Observability-only while flag OFF (registration records; excludes nothing —
any exclusion would reorder the LI-4 probe/verify choreography and break the
Slice A gate). Flag-ON exclusion semantics: a second writer (probe store,
health-verify, migration, glossary lane) force-flushes the owning store's
staged buffer BEFORE its own publication and re-reads durable truth.

**C. Flush-time story — NEW race register row #11.** Hazard: second writer
commits during a staged window → the store's later combined flush is built
on a stale base → CAS rejects at a seam that has NO retry today
(persistLiveCandidate :1080, promoteLiveCandidate :1189, cancelLiveCandidate
:1322, openCandidate :1404, recordDurableFailure :790, cancelCandidate :1492
— only :350-355 and :478-487 have the one-shot retry) → new REJECTED/failed
page writes, violating verification-ladder item 4. Mitigation: registry-gated
exclusion (B) as primary; generalizing `retryOnStaleManifest` to all facade
seams as defense-in-depth. One of these must be named; v1 named neither.

**D. Slice A item 4 (retention) — trigger parity codified.** Teardown sweep
= the existing `close()` sweep ONLY (StorePersistenceScheduler.kt:119-128).
`closeAndFlush()`/probe teardown remain sweep-free per T921 — the crawl
there stalled first opens ~15s (:103-117), and there is no flag to revert a
flag-OFF stall. Slice A changes no trigger, only codifies today's.

**E. Slice B item 8 / register #3 — three precision fixes.**
(1) Drain-to-commit is bounded by PROVIDER_DRAIN_GRACE_MS
(RollingAutoCoordinator.kt:1359 = ATTACH_TIMEOUT_MS, pinned by
D6DrainNotCancelTest:210-218). Within grace: finish to the next commit point
and publish. On expiry: cancel cleanly, NOTHING commits, the D9 entry stays
unresolved (D6 :285-333; D7 :430). "User stop becomes lossless" = lossless
within the grace window.
(2) Register #3 restated: process death with staged state = DROP (stage
re-runs from last publish). "Recovery = today's stale-writer path" was wrong
— `recoverInterruptedStages` operates on DURABLE RUNNING stages, which no
longer exist under staging.
(3) D9 attempt-ledger publishes (ChapterAttemptLedger.persistLocked →
publishAttemptLedger) are direct durable writes, NEVER staged — a paid call
must never start without its durable entry (D9AttemptLedgerTest:226-232).

**F. Glossary/legacy pointer lane — NEW register row #12.**
`updateGlossary`/`persistGlossaryLocked` build `manifest.copy(glossary=…)`
from the CACHED manifest and call the non-transactional `publishManifest`
(ChapterArtifactStore.kt:235-237; ChapterGlossaryStore.kt:77-92, :114-125).
Under staging they must force-flush the staged buffer first and build ONLY
from durable truth — otherwise they durable-ize staged page state and can
install manifest pointers at sidecars that do not exist yet (the T924-SC-22
dangling-pointer failure mode).

**G. NEW register rows #13–#16** (each already pinned by a suite):
#13 orderly teardown/eviction during staged window → flush the staged buffer
at close; committed display never revoked. #14 engine-epoch stop during
staged window → the dead epoch's staged work resolves at its stop-drain
commit point (D7). #15 partial-download admission during staged window →
admission preflight force-flushes before comparing chapter truth (D10).
#16 defunct store mid-staging → staged flush dropped/joined within
PERSIST_JOIN_TIMEOUT_MS.

**H. Legacy flat-file lane scope statement.** Staging applies to the
artifact bridge only. Pre-cutover, artifact-compat writes stay synchronous
(persistArtifactMutationLocked runs regardless of authority) and the
flat-file debounce (authority != ARTIFACTS) is untouched — two disciplines
do NOT co-own the artifact lane, and rescue re-reads are open-time.
