# T929 Round 3 — red-team: invariants, cross-domain conflicts, quality erosion

Base `main` @ `9c19ad0`, read-only. Attacks all four Round-2 catalogs against the binding
invariants. Every load-bearing premise was re-verified in code at HEAD, not taken from the
catalogs. Verified first-hand: `NativeRunQuarantine.run` (timed-out invocation keeps lane
ownership until real exit; generation fence, `scheduling/NativeRunQuarantine.kt:62-107`);
`OverlapScheduler` never-rules + one-bitmap envelope (`pipeline/batch/OverlapScheduler.kt:17-45`);
single chapter emission (`ChapterTranslator.kt:416-425` group→take(1)→first; claim
`inFlightChapterIds.add` :455-463); corpus gap PAUSE before the standard-lane branch
(`pipeline/batch/ChapterProfileBatchCoordinator.kt:529-583`); SKIP_ALL adoption of
externally-completed pages (`pipeline/batch/BatchLaneWorkers.kt:360-393`); manual-tap defer
(`:329-341`) and decode-defer FAILED write (`:432-447`); governor per-key buckets +
interactive reserve + 15 s defer (`translator/ProviderRequestGovernor.kt:447-503`), desktop
policy at :670-677 (no `backend=="desktop"` producer — LmStudioTranslator uses `lm_studio`);
credential-wide Batch sublimit maxInFlight=1 (`ProviderRequestGovernor.kt:717-737`);
`pageAuthoritativelyDone` blocks re-translate of READY/edited pages
(`pipeline/batch/ProfileEnvelopeExecutor.kt:972-981`; coordinator :2375);
`hasManualEdits` from `userEditedAt` (`artifact/ChapterArtifactStore.kt:1257`);
stale-manifest CAS compares the DURABLE manifest (`ChapterArtifactStore.kt:1641-1649`);
checkpoint = sidecar+pointer in ONE publication, committed pointer untouched
(`:445-470` TX-01); `ChapterDocumentIo.write` is flush-only, NO fsync;
`renameNoReplace` UNSUPPORTED on URI backends (`artifact/ChapterDocumentIo.kt:159-178`);
queue store clear+rewrite `commit=true`, rehydration never auto-starts
(`TranslationQueueStore.kt:19-64`); `publishLocked` persists BEFORE publishing, rollback
via `restorePageLocked` (`ChapterTranslationStore.kt:1876-1903`); durable
Translated/Failed precedence over auto feedback (`ui/reader/viewer/ReaderTranslationFeedback.kt:95-104`)
and terminal latch `terminalDisplayed`/`highestStageRank` (:185-231); LM Studio context
16_000 (`translator/contextual/TranslationContextChunkPlanner.kt:196-202`);
envelope policy 32/8 "MEASURED-EXPERIMENT… never product constants"
(`translator/contextual/GlobalEnvelopePlanner.kt:35-57`).

## 1. Per-entry verdicts (66 entries)

Verdicts: SAFE = no invariant violated as specified; NEEDS-REWORK = invariant is load-bearing
and the entry as written would stress/violate it until a named condition is met; REJECT = maps
a no-go as a solution (correctly, as negative space).

| ID | Catalog | Verdict | Reason |
|---|---|---|---|
| S1 | sched | SAFE | Re-times provider starts; fences untouched; rider: corpus-gate re-scope must keep COMPLETE-with-gaps unrepresentable; depends on S8/S9 |
| S2 | sched | NEEDS-REWORK | Lookahead decode breaks the verified one-bitmap envelope (OverlapScheduler gate 6.4); manual-decode ∥ batch-OCR = 2 bitmaps; needs cross-origin bitmap budget (see N3) |
| S3 | sched | SAFE | Warm-up outside timed region, memory-gated; pressure releases buffers never sessions |
| S4 | sched | SAFE | Poll→event; no semantics change |
| S5 | sched | SAFE | Engine cache retention only; signature gate still forces rebuild on real change; rider: release on idle for 6 GB memory safety |
| S6 | sched | NEEDS-REWORK | Erodes the reference quality bar (per-chapter corpus analysis); drift bound + full re-analysis fallback + io sidecar schema must land first; merge with provider #8 (one owner) |
| S7 | sched | SAFE | Reorder-only, live TRANSLATING never touched (active preference verified :416-425); checkpoints make any order resumable |
| S8 | sched | SAFE | Rescan stays inside run ledger; corpus gate retained; origin-neutral checkpoint adoption verified |
| S9 | sched | NEEDS-REWORK | Resume/finalize assume translate-after-full-preflight; corpus FP stamped in run records would certify a partial corpus — record state space must be extended first |
| S10 | sched | SAFE | Dwell is bounded, cancellable, batch waits (no preemption); manual result reused verbatim behind BatchWriteGate |
| S11 | sched | NEEDS-REWORK | Amends the verified OverlapScheduler never-rule; claim/finalize ownership + 2 record streams + bitmap envelope (with S2) unresolved; merge with provider #7 |
| S12 | sched | NEEDS-REWORK | Quality trade real (context dilution under token-fit/trim-ladder) and provider #1 mislabels it "neutral"; MEASURED-EXPERIMENT gate requires measurement first |
| S13 | sched | NEEDS-REWORK | Edits T926-bound quota constants; 30 s starvation guard must provably survive; Director sign-off required |
| S14 | sched | SAFE | Boundary-applied config; rider: must surface deferred config or it reads as stale |
| S15 | sched | NEEDS-REWORK | Auto provider spend on launch is a product consent decision; mechanically safe (adoption 10-50 ms/page, zero-work COMPLETE verified) |
| S16 | sched | REJECT | Correctly rejected negative space; preemption impossible safely (quarantine ownership verified) |
| S17 | sched | REJECT | Correctly rejected; memory math (96 MiB/session) holds |
| S1 | io | NEEDS-REWORK | CAS compares the DURABLE manifest (verified :1641-1649) — staged state rejects every intermediate CAS; commit-point set must include OCR checkpoints + promotions or resume re-pays OCR |
| S2 | io | NEEDS-REWORK | Writer detection moves disk→registry but probe/rescue/health-verify writers (DurableChapterStatusResolver, LegacyChapterMigrationSource) must ALL register; keep disk CAS on LI-4 seams |
| S3 | io | SAFE | Candidate+promotion already share one lock; probe cache needs an invalidation trigger on file events |
| S4 | io | SAFE | GC deferral is space-only (content-addressed names); teardown sweep retained |
| S5 | io | SAFE | Crash loses ≤1 page of terms; D5 PENDING-version stamp required or resume loops repair calls |
| S6 | io | SAFE | Lane order = mutex order, bounded queue fails closed; rider: CAS/promotion seams must await synchronously or serialization is recreated |
| S7 | io | SAFE | Local-only; parse-at-publish still catches torn tmp |
| S8 | io | SAFE | Journal seqno IS the CAS; pointer-commit records preserve sidecar-before-pointer by construction; replay is the concentrated risk (must be crash-idempotent) |
| S9 | io | SAFE | Crash window scoped per page; cross-page invariants stay chapter-level; schema v4 migration is the cost |
| S10 | io | NEEDS-REWORK | Substrate replacement retires .bak for the manifest (no-go adjacent); needs checksummed idempotent replay proof + migration before the "strictly stronger" claim is accepted |
| S11 | io | SAFE | Blob-before-pointer by admission ordering; torn blob never pointed to; GC depends on S4 |
| S12 | io | NEEDS-REWORK | App-private DB makes uninstall/clear-data destroy SAF-hosted translations — data-safety regression vs today; export/SAF-hosted DB must be resolved |
| S13 | io | NEEDS-REWORK | Correctness hinges on the flush-point contract (checkpoints + every promotion) and a memory ceiling with spill backpressure on 6 GB devices — unproven as written |
| S14 | io | SAFE | Documents a real gap (no sync call exists — verified); Mechanism A local-only, cheap after S1 |
| S15 | io | SAFE | Bounded loss; explicit re-arm preserved; rider: flush on STOP (see N1) |
| S16 | io | SAFE | Raw path + ATOMIC_MOVE + force is stronger locally; fail-closed fallback to current sequence |
| P1 | provider | NEEDS-REWORK | Constants are "never product constants" (verified); quality label conflicts with sched S12 — trim-ladder/60 s-read impact must be measured before adoption |
| P2 | provider | SAFE | Decision item; code has 16k (verified) — enforcing 8k is a quality+speed double loss; recommend keep-16k or explicit waiver |
| P3 | provider | SAFE | Same model output, earlier commits; rider: sub-envelope commit cadence must be co-designed with io S1/S13 commit points (see conflict X4) |
| P4 | provider | NEEDS-REWORK | Modifies a quota policy (revocable only by Director decision per verify-sched §6); keep reserves for cloud buckets; maxInFlight=1 retained |
| P5 | provider | SAFE | Per-bucket isolation verified in code (keyed buckets + per-credential sublimit); each gate individually respected — not "parallelism beyond quota gates"; rider: widened F-resume window, LAN TPM bursts |
| P6 | provider | SAFE | Bar held per chapter; FP-04 analyzer fingerprinting makes analysis-provider changes safe; rider: switch in blocks (rebuild inside the permit, verified EngineLane path) |
| P7 | provider | NEEDS-REWORK | Same mechanism as sched S11 — one rework, one owner (claim machinery, two stores, bitmap envelope); "no no-go touched" is right but the memory invariant is not |
| P8 | provider | NEEDS-REWORK | Edits freeze semantics (quality bar); drift gate + new provenance kind + merge with sched S6 (single carry-over owner) required |
| P9 | provider | NEEDS-REWORK | Second writer class; pageAuthoritativelyDone forces a new candidate mode; refine vs envelope sublimit contention; NEW finding: draft glossary fold can contaminate profile-freeze inputs (quality-bar erosion beyond "temporary draft") |
| P10 | provider | SAFE | Rides existing MISSING-request machinery + durable failure metadata; strictly closes pages stuck PARTIAL |
| P11 | provider | SAFE | Pure CPU pre-dispatch; frontier-bound (rolling context depends on committed history — correctly capped) |
| P12 | provider | SAFE | Quality-improving, zero extra requests, LM_STUDIO cap untouched; TPM math checks out |
| P13 | provider | SAFE | Explicitly below AI bar but above today's plain standard lane; page atomicity per request must hold |
| P14 | provider | NEEDS-REWORK | Prefetch OCR competes with reader taps on a priority-less native lane (up to 1 invocation, ~120 s cap); pre-freeze prefetch is below-bar (gated post-freeze only); needs B1/E2 arbitration (conflict X1) |
| P15 | provider | SAFE | Typed PAUSE with cheap resume; INTERACTIVE keeps 8 |
| P16 | provider | SAFE | Rides governor BACKGROUND; negligible |
| R1 | reader | SAFE | In-memory identity-fenced truth; M1 silent deaths become terminal Failed; rank 0 placeholder ages benignly |
| R2 | reader | NEEDS-REWORK | Reverses verified persist-first order; rollback-after-publish leaks a transient that then reverts (coalescer flicker); precondition-first is necessary but not sufficient once io S6 makes durability async (conflict X6) |
| R3 | reader | SAFE | Debounce guards no correctness invariant (verifier-agreed); conflation bounds churn |
| R4 | reader | NEEDS-REWORK | Precedence conflict is real (verified :95-104 + latch); intent-identity design must be co-owned with scheduling (who admits refresh intents) before touching the precedence core |
| R5 | reader | SAFE | Mapping unification; must preserve manual Queued intent + terminal-latch semantics |
| R6 | reader | SAFE | Flush-on-overwrite breaks no invariant; rider on not pre-bumping highestStageRank is correct (verified) |
| B1 | reader | SAFE | Same mechanism as sched S7 (adopt one); QUEUE semantics + outstanding filter keep committed display intact |
| B2 | reader | SAFE | Virtualized read-only join; M9 durable-terminal reconstruction mandated; memory bounded by lazy queries |
| B3 | reader | SAFE | Estimate-labeled constants; no pipeline change |
| C1 | reader | NEEDS-REWORK | Conflicts with the verified one-live-schedule-per-chapter claim + single emission (:421-425) and the corpus gate; second-lane admission must be designed with sched; quality trade is at least explicit and final state = bar |
| C2 | reader | SAFE | Read-only; never committed display; codify the no-OCR-as-translation rule in the mapper |
| C3 | reader | SAFE | Pure label; data already computed |
| E1 | reader | SAFE | View-layer only; strengthens no-flicker |
| E2 | reader | NEEDS-REWORK | Same one-active-chapter tension as C1; without preemption it degenerates to a B1 trigger — merge into B1 and resolve steering arbitration (conflict X1) |
| F1 | reader | SAFE | Read-only artifact exposure |
| F2 | reader | SAFE | Attempt-open stays behind the identity fence; peekStore registry-hit-only |
| F3 | reader | SAFE | Additive overlay; never alters committed image |

Tally: 42 SAFE / 22 NEEDS-REWORK / 2 REJECT (S16/S17 — correct negative space).

## 2. Cross-domain conflict matrix (different catalogs)

| # | Pair | Conflict | Resolution (winner / rework) |
|---|---|---|---|
| X1 | sched S11 + provider P7 + reader E2 + provider P14 + reader B1 | FOUR independent queue/admission policies (always-on second slot; cross-chapter overlap; position-triggered pre-start; prefetch; manual reorder) mutate the same single-emission queue head. Uncoordinated, they thrash admission and each widens the manual-tap gap window. | B1/S7's reorder-only mechanism WINS as the single steering primitive; S11/P7 merged into one sched-owned lookahead design; E2 becomes a B1 auto-trigger; P14 prefetch admitted only as BACKGROUND behind the interactive reserve. One arbitration owner: scheduling. |
| X2 | sched S2 + sched S11 + reader taps | Bitmaps stack: S2 lookahead decode + S11 N+1 preflight decode + manual tap decode + OverlapScheduler inpaint re-decode can exceed the verified one-bitmap envelope (gate 6.4). | No entry wins; memory invariant binds. Rework: process-wide decoded-bitmap budget token (N3) is a prerequisite for both S2 and S11. |
| X3 | sched S6 + provider P8 + provider P6 | Two competing carry-over designs (per-chapter lineage vs series scope) plus per-chapter analyzer routing: P6's analyzer change invalidates FP-04 reuse, destroying S6/P8's gains and re-triggering analysis. | Provider P8 WINS as owner (freeze semantics live there); S6 folds in as the drift-bound mechanism; analyzer provider pinned per series/run-block (compose with sched S14 signature pinning). |
| X4 | provider P3 (SSE) + io S1/S13 (group commit / write-behind) | SSE commits "per-page as blocks arrive"; io S1 stages everything to page-terminal commit points. Under S1, streaming's earlier durability is nullified; under S13 mid-envelope blocks sit unflushed, so crash after a streamed commit still loses it. | io commit-point contract WINS; P3 reworked to emit sub-envelope flush requests at block boundaries, adopted only if the io layer accepts them as commit points. |
| X5 | reader R2 + io S6 | R2 publishes before persist; io S6 makes persist asynchronous on a lane. An async lane failure now rolls back AFTER reader publication — a visible stage that reverts (worse than today's verified persist-first/restorePageLocked). | R2 restricted to updates whose durability is eventually-certain, or io S6 exposes synchronous completion at durable-result seams. Durable results keep persist-first (R2's own rule). |
| X6 | sched S9 + resume/reuse machinery | S9 lets standard lane translate on a partial corpus; the corpus FP is then stamped into run records/plan inputs, making "PARTIAL-corpus COMPLETE" representable and poisoning future fingerprint comparisons. | Record-state rework WINS: gap semantics must be explicit in run records (e.g. corpusGaps>0 field already exists — make it gate COMPLETE) before S9. |
| X7 | sched S15 + io S15 | Auto re-arm consumes the persisted queue immediately at launch; io S15's debounce widens the crash window for queue mutations, so the re-armed frontier can be stale (wrong chapter or dropped steer). | io S15 must flush on terminal transitions (STOP, chapter completion, reorder); S15 auto re-arm reads only post-flush state. |
| X8 | provider P9 + reader C1 + T924 claim | Both create second writer classes / second lane for the same chapter against the verified one-live-schedule-per-chapter claim (`inFlightChapterIds`, single emission). | Neither wins alone: one shared scheduling design for "reader-adjacent standard-lane pass + AI refine" with claim renegotiation; P9 additionally needs the new candidate mode (pageAuthoritativelyDone blocks envelope reuse — verified). |
| X9 | provider P5/P7 + scenario F | Bucket-concurrency and cross-chapter overlap each double the phase-partial chapter count; crash at ch137 leaves TWO half-paid chapters and a wider re-pay window. | Acceptable only with per-chapter checkpoint discipline intact (it is); adopt P5 before P7; resume UI (B2) must render two partial chapters truthfully. |
| X10 | sched S12 + provider P1 + provider P2 | Envelope enlargement assumes the 16k window; P2 puts 8k on the Director's desk. Under an enforced 8k, splitForTokenFit halves ppe — MORE requests AND smaller per-page context (12.1→14.2 h per P2's own math). | Decision dependency: settle P2 first; S12/P1 are meaningless (or harmful) under 8k. If 16k stands, S12/P1 merge into one measurement-gated change. |

## 3. Quality-bar erosion audit (corpus analysis → profile → envelopes)

- **Mislabels found (labeled quality-neutral, actually degrading):**
  - **provider P1** ("neutral (same per-page inputs)") contradicts **sched S12** ("per-page
    context dilutes"). Code favors S12: bigger envelopes raise per-request input tokens; the
    trim ladder (`ProfileEnvelopeExecutor` :877-934, scenes→pairs→subset) and token-fit splits
    engage exactly when chapters are dense, dropping context per page. P1 must be re-labeled a
    measured trade.
  - **provider P9 draft-and-refine**: labeled "draft BELOW bar until refined; final = bar", but
    the catalog also folds drafts into the glossary (the manual-fold precedent
    `SinglePageHttpRenderPhase.kt:444-459`). Draft-derived terms then feed the AI profile —
    the reference bar itself is contaminated by sub-bar output, which no catalog admitted.
    Either exclude draft folds from freeze inputs or quantify.
  - **provider P14**: "neutral IF refined under the same chapter profile" — pre-freeze prefetch
    pages are translated WITHOUT the profile (below bar, permanent unless refined by P9's
    machinery). The gate is stated but there is no mechanism to retro-refine prefetch pages;
    without it, those pages are silently below bar forever.
- **Correctly-labeled trades (admissible as specified):** sched S6 / provider P8 (explicit
  HIGH, gated), reader C1 (explicit, AI refresh final), provider P13 (below AI bar but above
  today's standard lane — no AI-lane change), provider P2's 8k option (neutral-to-worse,
  honestly priced), io S5 (crash loses ≤1 page of glossary — small, bounded).
- **Quiet erosion by interaction:** X10 — enforcing the 8k window shrinks envelopes, which
  reduces per-page context even with no catalog changing a quality constant. The 8k "discipline"
  is itself the largest single quality lever on the table and no entry owns it as a quality
  decision (P2 frames it purely as policy/latency).
- **No erosion found:** S1/S8/S10 (same requests, same fences), io S1-S16 (byte-identical
  payloads), P10/P12 (strictly quality-improving), all reader R/B/C/E/F entries except C1.

## 4. New-solutions hunt (correctness/interaction space)

Three levers the merged catalog leaves uncovered. Per loop protocol these are Round-4 targets.

- **N1 — Drain-to-commit-point stop semantics** (io + sched). Mechanism:
  `translator.stop()`/cancel currently cuts mid-page; under io S1/S13 staged/write-behind
  state, a USER stop would lose unpublished stages exactly like a crash. Add a graceful-stop
  mode: finish the in-flight page to its next commit point (OCR checkpoint / page terminal),
  publish the group commit, then release. Turns S1/S13's crash-window claims from "bounded
  loss" into "lossless on user stop". Anchor: `ChapterTranslator.cancelTranslatorJob` /
  clearQueue path (`ChapterTranslator.kt:395-398`), `BatchChapterTranslator` teardown.
- **N2 — Run-scoped writer lease generalization** (io). io S2 coordinates probe/rescue writers
  per store; S11 (sched) makes TWO chapters live concurrently. Generalize to a process-wide,
  run-scoped writer registration keyed (chapterId → writer origin) in
  `ActiveChapterStoreRegistry` (anchor: `ActiveChapterStoreRegistry.kt:23,137`,
  `DurableChapterStatusResolver.kt:207`) so rescue/health-verify/migration never write under a
  live lookahead chapter. Prerequisite for S2-io + S11-sched co-adoption.
- **N3 — Cross-origin decoded-bitmap budget** (sched/memory). The one-bitmap envelope is
  enforced per-scheduler today (OverlapScheduler gate 6.4; `canStartDecode` gates batch decode
  only). With S2 lookahead, S11 second-chapter preflight, and manual taps decoding
  concurrently, a counted process-wide decoded-page budget (acquire/await before
  `decodePageBitmapForTranslation`, manual lanes exempt-with-cap) preserves the 6 GB memory
  invariant. Anchor: `BatchLaneWorkers.kt:401-431` (`withNativeLane` decode), existing
  `canStartDecode` gates.

Beyond these: no further obvious uncovered levers in the correctness/interaction space
(lease semantics, checkpoint adoption, ordered flush, quota gates, and display authority are
each covered by at least one catalog entry plus a reconciliation above) — near-convergence;
the three items above are reconciliation mechanisms rather than a new solution family.

## Bottom line

The catalogs are anchor-honest: 66/66 entries cite real code, and every premise I re-verified
held (including the two self-corrections: LM Studio 16k-not-8k and per-backend quota buckets).
The merged portfolio is adoptable in the sequencing each catalog proposes PROVIDED: (a) the
X1 steering arbitration and X2 bitmap budget land before any lookahead/prefetch entry; (b)
provider P1/P8/P9 quality labels are corrected and measurement-gated; (c) P2 (8k vs 16k) is
decided first; (d) N1-N3 are pulled into Round 4 as prerequisites, not optional extras.
