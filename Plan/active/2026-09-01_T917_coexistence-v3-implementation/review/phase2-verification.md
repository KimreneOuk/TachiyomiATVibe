# T917 Phase 2 — Reviewer Acceptance Verification

**VERDICT: ACCEPT-WITH-NOTES** — D1–D4 implemented to spec, evidence assertion-based and credible; the deviation-3 lease-over-plan commit refresh is fenced correctly and I recommend adopting it as the long-term rule (one Director-visible contract note + gate soak conditions below).

---

## 1. Deep dive — deviation 3 (commit 37c0902, `SinglePageHttpRenderPhase`)

### 1.1 What the change actually does (VERIFIED)

At the final `patchPage` (SinglePageHttpRenderPhase.kt:538-542), when the boundary still owns the
lease (`store.pageLeaseOwner(pageKey) == origin`, :528) and the store generation moved since the
captured precondition (:530), the precondition is re-derived from a fresh snapshot
(`snapshot(pageKey).toPrecondition()`, ChapterTranslationStore.kt:1865-1873) keeping
generation/pageVersion/leaseToken/blockFingerprints and nulling `candidateGenerationId`,
`dependencyFingerprint`, `artifactPageVersion` (SinglePageHttpRenderPhase.kt:531-535). `patchPage`
skips null-expected checks (ChapterTranslationStore.kt:455-463), so only the three plan-identity
fields are waived; the pageVersion (:453), blockFingerprints (:464), generation (:452) and lease
token (:467) fences remain fully armed.

### 1.2 Attack (a) — upstream artifact invalidation under a held lease

Cannot happen for real upstream work. Every stage write requires a BATCH lease
(BatchLaneWorkers.kt:757-771 mints identity only on Granted; `guardedBatchUpdate` →
`updatePageGuarded` rejects on lease-token mismatch, ChapterTranslationStore.kt:467, 1025) and the
BATCH lease is Denied while MANUAL/AUTO holds (PageStageLeaseTable.kt:93-98). The only manifest
mutation the batch start path performs for a held page is candidate-less registration of keys not
yet in the manifest (ChapterTranslationStore.kt:1442-1463, `PageArtifactRecord(pageKey)` with null
candidate, :1449). Verdict: under a held lease the three dropped fields can never witness a real
competing upstream write — they can only witness chapter-wide bookkeeping. The waiver is therefore
sound **for the lease-holder path only**.

### 1.3 Attack (b) — stale commit racing a new owner (TOCTOU)

Airtight. The ownership check (:528) is advisory; the binding check is
`expected.leaseToken != pageLeases[pageKey]?.token` under the store mutex
(ChapterTranslationStore.kt:467-468). The fresh precondition carries the token captured at snapshot
time (snapshotLocked :1857); any handback + re-acquire between the check and `patchPage` mints a new
token (`++nextLeaseToken`, PageStageLeaseTable.kt:117), so the late commit is rejected
("page lease token changed"). If ownership was lost before the check, no refresh happens and the
ORIGINAL precondition's stale token/generation rejects. Same-origin re-entry preserving the token
(:99-114, asserted in ChapterTranslationStorePhase3Test.kt:111) is the same continuous writer, not a
new one. No corruption path found.

### 1.4 Attack (c) — manual survives a source-image mutation?

No false survival. (1) Mutation before the boundary starts: the manual decodes the CURRENT bytes and
stamps its own provenance from its own decode + current config
(SinglePageHttpRenderPhase.kt:181-188; decode fingerprint SinglePageOnnxPhase.kt:830) — consistent
by construction. (2) Mutation while parked (after decode): the commit carries the old
`sourceFingerprint`, and the reuse layer re-checks it independently:
BatchChapterTranslator.kt:304-309 re-hashes sources at batch start and
PageWorkPlanner.kt:345 (`evidence.sourceFingerprint == evidence.expectedSourceFingerprint`) forces
rework. The commit-time candidate fingerprint is plan-identity, not the staleness guard; the manual
path's upstream consistency is enforced by its own decode/stage-fingerprint chain and by the planner.

### 1.5 Attack (d) — do other paths still exercise the dropped precondition?

Yes. Direct `patchPage` callers are untouched: PageStoreWriter.kt:119 and CleanedPublication.kt:144
carry full preconditions; all guarded stage patches keep the three fields
(ChapterTranslationStore.kt:967-1001, checks :1471-1491 with the stronger `record.candidate != null`
grace). Store-fence tests still bite: ChapterTranslationStorePhase3Test.kt:95-134 (late write
rejected on released lease), :137-158 (stage-merge rejection) — both exercise the token fence via
direct calls, unchanged by the deviation. No test asserted the boundary's plan-identity rejection;
nothing was weakened on other paths.

### 1.6 Judgment — lease-over-plan precedence vs narrower alternative

**Recommendation: keep lease-over-plan precedence (as implemented).** Code-level reasons:
1. The narrower alternative (batch engine setup skips candidate re-planning/registration for
   lease-held pages) would not have prevented the observed false rejection class in general: the
   rejection arose from chapter-wide manifest bookkeeping (registration :1442-1463; also re-key
   pageVersion bumps :1823-1846) that legitimately must cover all pages, colliding with a
   precondition captured mid-flight. Exempting pages from chapter-wide durable bookkeeping based on
   a transient lease adds lease-dependent branching to the manifest layer — a larger, riskier
   semantic surface (expectedPageCount, later stage publications) than waiving three advisory
   fields for the one exclusive writer.
2. D1 already establishes the exclusive-writer principle (PageStageLeaseTable.kt:82-89); extending
   it to "run-level plan identity is advisory for the live lease holder" is one coherent rule,
   confined to the single-page boundary commit (the only writer whose precondition can predate a
   batch start).
3. Real competing writes remain fenced (§1.2–1.4); fail-closed behavior is preserved for every
   non-holder.
Root-cause note (LOW, defect-in-precondition-asymmetry, not introduced here): `patchPage`'s
dependencyFingerprint check (:461-463) lacks the `record.candidate != null` grace that
`publishLocked` has (:1483-1485), so a candidate-less registered record falsely rejects non-null
expected fingerprints. The refresh sidesteps it; aligning the check (Phase 3/4 backlog, with a
regression test: batch registration between manual capture and commit, lease held → accepted) would
remove the asymmetry at its root.
This is a contract decision (D1 corollary); surface to Director with the recommendation above.

---

## 2. Standard acceptance checklist

| Item | Status | Evidence |
|---|---|---|
| A Scope | VERIFIED | `git diff checkpoint/t917-p1-done..HEAD --stat`: 25 files, all in pipeline/scheduler/manager/batch/store-lease + tests + Plan logs; no gradle/toml/dependency diffs (checked explicitly); HEAD = 58afd34 (docs-only) |
| B D1 origins | VERIFIED | Matrix + MANUAL-evicts-AUTO: PageStageLeaseTable.kt:90-98 (fresh token :117); origin-checked release/cancel :143,158,178-181; D1OriginPriorityTest.kt:45-121 asserts eviction token, fail-closed rejected write, AUTO release/cancel cannot remove MANUAL lease. Two-vocabulary: stamp via `toArtifactOrigin().name` (SinglePageHttpRenderPhase.kt:162), batch stamps BATCH.name (BatchResumePlanner.kt:73); grep for raw "MANUAL"/"AUTO" stamps: only a KDoc mention |
| C D2 attach | VERIFIED | `attachToOwnerTerminal` TranslationPipeline.kt:509-551 — observation only (`store.state.first{terminal}` :526-529), zero native/provider/render work; ATTACH_TIMEOUT_MS = ONNX+120s (:130); cancellation returns AttachedUnresolved without touching the page (:531-541); scheduler skips `markPageCancelled` outcome-gated on attach family (TranslationScheduler.kt:648-658); `manualOutcomes` capped map :101-105, cleared :813,:866,:921 |
| D D3 rescan | VERIFIED | SequentialBatchCoordinator.kt:557-582 — COMPLETED path only (`stoppingOutcome == null`), RESCAN_MAX_ATTEMPTS=2 (:696), ordered (:565), handback-gated (:568-573); deferral recorded + event only on Denied (BatchLaneWorkers.kt:766-767); externally-completed gate forces SKIP_ALL, no repeated provider call (:788-795); stop paths (:584+) never rescan; SequentialBatchCoordinatorTest unchanged and green in sweep XMLs |
| E D4 suppression | VERIFIED | Manager guards: requestAutoWindow TranslationManager.kt:1364-1370, updateAutoWindow :1401-1406, reconcileAutoWindow admissionGuard :1425; PAUSED included deliberately :649-658; recovery-after-drain asserted in TranslationManagerAutoArbitrationTest.kt:176-189 |
| F Tests measure contracts | VERIFIED | D2: lease owner, manualWaited join-probe, `callsFor(p0)==1`, renderStatus READY (D2ManualBatchInterleavingTest.kt:63-105); D3: strandedPages empty, TRANSLATED, tracker 2/2, calls 1/1 (D3ReaderOwnedPageAcrossBatchTest.kt:123-146); D4/D1 as above — all end-state oracles, no log-based assertions. Test sources untouched in part B (`git show 48ddee1 37c0902 --stat`: production + harness only) |
| G Harness repairs | VERIFIED | CoexistenceBarrier taken-gate (claimed gate stays listed, release unparks parked arrival; already-completed gates skipped) and RENDER keyed by page key via `removeSuffix(".cleaned.jpg")` (48ddee1 diff) — test-infra only, latent-bug class as predicted; documented in commit + log B4.2 |
| H Determinism | STRONG INFERENCE | 5/5 forced `--rerun` of the coexistence package recorded (log B3); adequate for the phase gate given harness uses real components + barriers (no sleeps). REQUIREMENT: the planned 100-run soak at the gate must include D2/D3/D1 + NormalMangaIsolationTest before promotion |
| I Sweep integrity | VERIFIED | Aggregated from `app/build/test-results/testStandardDebugUnitTest/*.xml`: 161 classes, 1232 tests, 0 failures, 0 errors, 0 skipped — matches log exactly; XMLs written 05:11 vs code commit 37c0902 at 04:55 (only docs commit after); no @Disabled/@Ignore in touched test dirs |

## 3. Findings

1. **MEDIUM / design-limitation (accepted, recommend adoption)** — deviation 3 waives
   plan-identity preconditions for the live lease holder (§1). Correctly fenced; no competing-write
   path found. Long-term semantics decision documented in §1.6; goes to Director as a D1 corollary.
2. **LOW / defect (pre-existing)** — `patchPage` vs `publishLocked` candidate-grace asymmetry
   (ChapterTranslationStore.kt:461-463 vs :1483-1485) can falsely reject non-null expected
   fingerprints against a candidate-less registered record; the refresh masks it on the boundary
   path only. Root-cause alignment suggested for Phase 3/4 backlog.
3. **LOW / expected** — refresh only fires when generation moved (:530); a manifest-only mutation
   without a generation bump could still fail-closed reject the boundary commit (pre-existing,
   conservative direction: a retried page, never corruption).
4. **NOTE / expected** — cancellation is swallowed at the attach wait and mapped to
   AttachedUnresolved (TranslationPipeline.kt:531-541); safe because no suspension follows, but any
   future code added after the catch must stay non-suspending (comment already guards this).

## 4. Conditions for `checkpoint/t917-p2-done`

1. Record in PHASE-LOG/README that lease-over-plan precedence (deviation 3) is the adopted D1
   corollary, flagged for Director visibility; if the Director prefers batch-side skip-re-planning
   instead, that decision must land before Phase 3 (D5 reuse builds on this commit semantics).
2. Gate sweep must repeat the 1232/0 full run from a clean build and the 100-run determinism soak
   must cover D1/D2/D3 + NormalMangaIsolationTest (per §H requirement).
3. (Non-blocking) carry findings 2 and 3 into the Phase 3/4 backlog.

No blocking defects. Accept with the conditions above.
