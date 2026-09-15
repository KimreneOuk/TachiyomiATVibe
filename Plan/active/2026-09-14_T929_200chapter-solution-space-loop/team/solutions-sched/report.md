# T929 Round 2 — scheduling/pipeline solution catalog (200-chapter baseline)

- Author: Technical Lead (scheduling/pipeline domain). Base `main` @ `9c19ad0`, read-only.
- Inputs: T928 sched slice + Round-1 verify-sched (binding corrections) + Round-1 throughput model.
- All anchors verified firsthand at HEAD. `…/translation/` abbreviated; coordinator = `pipeline/batch/ChapterProfileBatchCoordinator.kt`.
- Binding corrections adopted: attach starvation is a narrow per-page race (leases release after OCR checkpoint, re-acquired per translate); the REAL pathology is corpus-gap PAUSE (`:556-569`) triggered by manual-tap deferral (`BatchLaneWorkers.kt:329-341`) or decode-defer (`:432-447`), with NO in-pass rescan.
- Fact correction to T928: chapter claim serialization is stronger than "one per source" — the active flow emits at most ONE chapter process-wide (`ChapterTranslator.kt:421-425`: group by source, `take(1)`, `first()`), even across different manga.
- Model shorthand (P=15, B=5, S=20): t_ocr=2.10 s/page (flagship). Flagship+cloud AI marginal 64 s/ch = 31.5 OCR + 1.75 fix + 11 analysis (1×(10+1)) + 0.5 freeze + 18 envelopes (3×max(4,5+1)) + 1 finalize; total 3.6 h; ch1 TTFP 49 s; jump-B = 99×64+49 ≈ 1.77 h; C: 47% of chapter time after OCR. LM Studio LAN: 219 s/ch = 31.5 + 1.75 + 76 (75+1) + 0.5 + 108 (3×36) + 1; total 12.1 h; ch1 TTFP 144 s; B ≈ 6.0 h; C: 85%. Standard lane flagship (L_std=1): 64 s/ch, TTFP 34 s.

## Catalog

| ID | Entry | Tag | Scen. | Δ flagship+cloud | Δ LM Studio LAN | Risk |
|---|---|---|---|---|---|---|
| S1 | Standard-lane OCR-barrier removal (F.1) | T928 | A,C | std lane 64→~40 s/ch, TTFP 34→~4 s; ~2.2 h | n/a (std lane) | MED |
| S2 | Decode off the native permit (F.2) | T928 | A | 3.6→~3.4-3.5 h; tap wait −0.25-0.6 s/job | none (provider-bound) | LOW-MED |
| S3 | Native warm-up at session/queue open (F.3) | T928 | D,A | ch1 TTFP 49→34-46 s | 144→129-141 s | LOW |
| S4 | Eventize claim + governor polls (F.6) | T928 | F | −15-50 s/run of poll dead time | same | LOW |
| S5 | Rebuild-free clearQueue (F.8) | T928 | B,D | −I (3-15 s) per clear→start, off-lane | same | LOW |
| S6 | Cross-chapter profile carry-over (same manga) | IMAGINATIVE | A,B,E | 64→53 s/ch; 3.6→3.0 h | 219→143 s/ch; 12.1→8.0 h | HIGH (quality) |
| S7 | Reader-position queue priority + reorder | NOVEL | B,A | B: 1.77 h→~1.5-2 min | B: 6.0 h→~4-6 min | LOW |
| S8 | In-pass corpus-gap rescan | NOVEL | A,C | gap→+2-5 s/page, not a stall | same | LOW-MED |
| S9 | Standard-lane partial-corpus proceed | NOVEL | A,C | eliminates std-lane gap stalls | n/a | MED |
| S10 | Gap-avoidance dwell on manual defer | NOVEL | A | stall ∞→≤~90 s once/chapter | same | MED |
| S11 | Cross-chapter preflight lookahead (waves) | IMAGINATIVE | C,E | 64→~35 s/ch; 3.6→~2.0 h | 219→~186 s/ch; ~10.3 h | HIGH |
| S12 | LAN envelope enlargement (E 3→1-2) | NOVEL* | E | none (native-bound) | 219→147-183 s/ch; 8.2-10.2 h | MED (quality) |
| S13 | Interactive admission hardening | NOVEL | A | tap defer 15 s→≤1 slot | tap defer 15 s→≤1 slot | LOW (policy) |
| S14 | Engine-signature pinning at run scope | NOVEL | B,D,F | −3-15 s per mid-run config flip | same | LOW |
| S15 | Resume-frontier auto re-arm | NOVEL | F | user resume latency→0 (wall ≈20 s) | same | LOW-MED |
| S16 | Native priority/preemption | REJECTED | — | — | — | no-go |
| S17 | Per-workload EngineLane split | REJECTED | — | — | — | no-go |

*S12 shared with the provider/quality domain slice.

## Per-entry detail

### S1 — Standard-lane OCR-barrier removal (T928 F.1; verifier-agreed with risk additions)
- Mechanism: start a page's translate+inpaint as soon as its OCR checkpoint commits instead of gating on whole-chapter preflight (`:378-525` loop precedes `:571-583` standard branch). Re-scope the corpus gate (`:556-569`) to the AI lane only (standard lane has no corpus dependency); keep the AI analysis barrier (genuine context need).
- Anchors: `:378-525`, `:556-569`, `:571-583`, serial tail `:1825-1852`; verifier §5 risks: ST-14 resume ordering (`:288-296` region), finalize drain (`:1623`), wider per-page batch-lease window.
- Impact: standard lane TTFP 34→~4 s (2.1 OCR + ~1 provider + 1 spacing for page 1). Total: page N's provider wait overlaps page N+1's OCR → marginal 64→~40 s/ch → ~2.2 h (saves min(Σprovider 30, OCR 31.5) ≈ 24 s/ch). AI lane unchanged (3.6 h / 12.1 h stand). Scenario C (the "OCR done, translate not" norm) disappears for standard lane.
- Quality: none — same requests, same order, same fences (BatchWriteGate untouched).
- Risk/invariant: MED; does not touch one-native-lane or quota; must redefine "gap" for the standard lane (see S9) and keep COMPLETE-with-gaps impossible.
- Interactions: io — per-page TX cadence unchanged; reader — A/C improved; provider — request order unchanged; requires S8/S9 so pipelined gaps don't stall.

### S2 — Decode off the native permit (T928 F.2)
- Mechanism: `decodePageBitmapForTranslation` runs inside `withNativeLane` (`BatchLaneWorkers.kt:401-431`; inpaint re-decode `:584-599`). Move decode before permit entry; optional 1-page lookahead decode so decode overlaps the previous page's lane work.
- Anchors: `BatchLaneWorkers.kt:401-431, 584-599, 737-747` (reclaim hook is permit-free); memory gates `canStartDecode` stay in front.
- Impact: lane occupancy per page 2.10→~1.85 s flagship (−t_dec 0.25; mid −0.6). Manual wait behind the current batch job shrinks by the same. With lookahead overlap: cloud total 3.6→~3.4-3.5 h; LAN unchanged (native slack already 184 s/ch).
- Quality: none. Risk: LOW-MED; verifier notes manual-decode ∥ batch-native-OCR becomes possible inside the one-bitmap envelope — needs a memory review; keep `releaseDecodedPage` reclaim interaction intact.
- Interactions: memory domain (bitmap envelope, spill), reader A (smaller native-queue tail), io none.

### S3 — Native warm-up at reader-session open / batch queue admission (T928 F.3)
- Mechanism: build the engine session set (and a cheap dummy recognition pass) outside any timed region when a reader session opens or a 200-chapter queue is admitted, memory-gated.
- Anchors: absence verified — sessions lazy on first `analyze()` (`recognition/RoiPageRecognitionEngine.kt:153-265`, no warm-up code at HEAD); init charged to first page inside the 90 s permit (`TranslationPipeline.kt:139`).
- Impact: I∈[3,15] s leaves ch1 TTFP: cloud 49→34-46 s; LAN 144→129-141 s; scenario D cold start: model deploy stays one-time, session init hidden. Total run unchanged.
- Quality: none. Risk: LOW (memory-gated; pressure releases buffers, never sessions).
- Interactions: reader D/A; memory (gate on `hasHeadroomForPrefetch` pattern).

### S4 — Eventize chapter-claim and governor polls (T928 F.6)
- Mechanism: replace the 100 ms claim busy-poll with an event on `inFlightChapterIds` release; add a wakeup-on-release to the governor wait loop.
- Anchors: `ChapterTranslator.kt:93, 461-463`; `translator/ProviderRequestGovernor.kt:303-333` (50 ms poll).
- Impact: ≤200 handoffs×≤100 ms ≈ ≤20 s/run, plus 600-1200 requests×≤50 ms ≈ ≤30-60 s/run poll dead time; F resume unwind −≤100 ms. Cosmetic but free.
- Quality: none. Risk: LOW. Interactions: provider (wakeup-on-release only), io none.

### S5 — Rebuild-free clearQueue (T928 F.8)
- Mechanism: `clearQueue()` → `translator.stop()` (null reason) → `closeEngines()` (`TranslationManager.kt:742-746`, `ChapterTranslator.kt:343-345`), charging the next start a full session rebuild inside the permit (`BatchChapterTranslator.kt:318-320`). Release pools/buffers instead; the signature gate (`pipeline/EngineLane.kt:177-231`) still forces a rebuild when config truly changed.
- Impact: saves I (3-15 s) of lane stall per clear→start cycle; matters for scenario B (clear-and-requeue surgery on a 200-chapter queue) and D.
- Quality: none. Risk: LOW (verify no correctness need for teardown on clear). Interactions: reader B; memory (buffers vs sessions).

### S6 — Cross-chapter profile carry-over for a same-manga run — IMAGINATIVE
- Mechanism: ch N's frozen profile seeds ch N+1: generalize the AI-only frozen-profile reuse probe (`:326-369`; currently requires the NEW chapter's corpus FP to equal the pointer's, i.e. same-chapter re-runs only) to a "lineage" match: carry glossary/style profile across chapters, re-analyze only a bounded delta (new pages) instead of C full chunks.
- Anchors: `:326-369` (reuse skip → straight to envelopes `:362-368`), analysis phase `:585-595`, `translator/contextual/AnalysisChunkPlanner.kt:28-47`.
- Impact: removes C×(L_a+1) per chapter after the first: cloud 64→53 s/ch (3.6→3.0 h); LAN 219→143 s/ch (12.1→8.0 h — the single biggest LAN lever found). ch N+1 TTFP −11 s cloud / −76 s LAN.
- Quality: REAL RISK — per-chapter corpus analysis is the reference quality bar (glossary continuity); 200-chapter drift must be bounded (similarity check → full re-analysis fallback); explicitly a quality-for-speed trade requiring provider-domain co-design.
- Risk/invariant: HIGH; touches ST-10 profile-freeze invariants and profile sidecar identity (io). Speculative: not in T928 or reviews.
- Interactions: provider/quality (owner), io (sidecar schema), reader B/A (faster tails).

### S7 — Reader-position queue priority — NOVEL
- Mechanism: add a reorder operation on `_queueState` (list reorder + `persistQueue()`), auto-invoked when the reader opens a queued chapter (move-to-head, never touching the live TRANSLATING entry — the active preference at `:416-425` already keeps it stable). No reorder logic or UI exists at HEAD (grep).
- Anchors: FIFO append `ChapterTranslator.kt:843-855`; head-of-list selection `:411-426`; persisted ordered ids `ChapterTranslator.kt:171-175` + `TranslationQueueStore.save/load`.
- Impact: B: TTFP(100) = 99 marginals + own → (in-flight tail ≤64 s) + own 49 s ≈ ≤2 min cloud (1.77 h today); ≤219+144 ≈ 4-6 min LAN (6.0 h today). A: the chase chapter stays at the frontier. Reorder is free and crash-safe: per-page checkpoint adoption (`:385-423`) makes any order resumable.
- Quality: none. Risk: LOW; no preemption (live chapter never interrupted); persistence format unchanged.
- Interactions: reader UX owns the trigger; io one SharedPreferences commit per reorder; provider none.

### S8 — In-pass corpus-gap rescan — NOVEL (fixes the Round-1 HIGH counter-finding)
- Mechanism: at the corpus gate (`:529-569`), before returning PAUSED, run ONE bounded rescan pass over gap pages: `reusableCheckpointFingerprint` (`:385-423`) is origin-neutral, so manual-completed pages adopt without re-OCR; still-missing pages (deferred `BatchLaneWorkers.kt:338`, decode-deferred `:432-447`) re-OCR in-pass. Cap passes; PAUSE only on residual gaps.
- Anchors: `:529-569`; deferredPages written `:338`, never consumed (verifier V12); SKIP_ALL adoption precedent `BatchLaneWorkers.kt:360-393`.
- Impact: A/C: one tap during preflight currently stalls the chapter until an explicit re-arm; with rescan it costs +2-5 s per missing page (one extra OCR turn) inside the same run. LAN unchanged (barrier provider-bound). Removes the need for re-queue surgery in most chase cases.
- Quality: none (same OCR path, same checkpoints). Risk: LOW-MED — rescan must stay inside the run's ledger semantics (honest counters, same runId); corpus-completeness gate retained, only retried in-pass.
- Interactions: io (re-published records within run), reader A (tap no longer starves the chapter), S9/S10 compose.

### S9 — Standard-lane partial-corpus proceed — NOVEL
- Mechanism: for `standardLane` only, treat gaps as per-page skips: branch at `:575` with checkpointed pages; gap pages re-enter via S8's rescan. The corpus FP is a run-record input, not a translation input for this lane (T928 F.1 rationale); AI lane keeps the strict gate.
- Anchors: `:556-569` (gate currently precedes the branch — enforced twice for standard lane, verifier V8), `:571-583`.
- Impact: standard lane scenario A: a tap can no longer pause the chapter at all; combined with S1 the chapter never stalls (avoided cost: unbounded user-re-arm latency → 0).
- Quality: none for standard lane (page translations are independent). Risk: MED — resume/finalize semantics assume translate-after-full-preflight (verifier §5-(ii)); record states must make "PARTIAL-corpus COMPLETE" unrepresentable (PAUSED-with-partial-translate only).
- Interactions: io (run-record state space), reader A/C, depends on S1's gate re-scope.

### S10 — Gap-avoidance dwell on manual defer — NOVEL
- Mechanism: when the OCR loop hits a manual-owned page (`ocrDeferred`, `BatchLaneWorkers.kt:339`), dwell at the loop's yield point (`:383`): await that page's terminal (bounded ~≤90 s), then SKIP_ALL-adopt (`:360-393`). The gap never forms; no preemption (batch waits, manual never interrupted).
- Impact: A: worst case per tapped chapter goes from indefinite stall+re-arm to ≤ one single-page cycle (~90 s cap + network) added to the 64 s/219 s marginal. Reader tap latency unchanged.
- Quality: none — manual result reused verbatim; batch writes stay fenced out (`BatchWriteGate`). Risk: MED — dwell must be cancellable (pause/stop) and must not chain across multiple deferrals (cap total dwell/chapter).
- Interactions: reader A (manual unaffected), io none, composes with S8 as belt-and-suspenders.

### S11 — Cross-chapter preflight lookahead ("wave" pipelining) — IMAGINATIVE
- Mechanism: second scheduler slot: one TRANSLATING + one OCR_PREFLIGHTING chapter. Today the active flow emits one chapter (`ChapterTranslator.kt:421-425`); relax to a 2-tuple. Native lane stays concurrency-1 — N+1's OCR fills lane-idle time (lane idle 29.5 s/ch cloud, 184 s/ch LAN during provider waits); optionally prioritize it inside provider windows, amending the OverlapScheduler never-rule "Detector/OCR NEVER run here" (`pipeline/batch/OverlapScheduler.kt:27-45`) — the verifier already flagged this rule for rephrasing under F.1. Legal within no-gos: one-native-lane kept, maxInFlight=1 kept, per-chapter stores/generations are independent, OCR is local (no quota change). Same mechanism legalizes cross-MANGA lookahead (separate stores/mutexes already).
- Impact: per-chapter serial Σ(native+provider) → max(Σnative, Σprovider): cloud 64→~35 s/ch (3.6→~2.0 h); LAN 219→~186 s/ch (12.1→~10.3 h). C: N+1 is usually fully OCR'd when the user opens it. B: unchanged (pair with S7).
- Quality: none (same work, re-timed). Risk: HIGH — chapter-claim machinery (`:461-499`), finalize/teardown ownership, and two concurrent store-record streams (io); one-decoded-bitmap envelope must be preserved (lane serializes OCR vs inpaint, but decode lookahead from S2 adds a second bitmap — must gate).
- Interactions: io (two record streams), reader C, provider (windows unchanged), memory (bitmap envelope).

### S12 — LAN envelope enlargement — NOVEL (shared with provider/quality slice)
- Mechanism: raise `maxContributingPages`/`maxBlocksPerEnvelope` for the LM_STUDIO profile (`translator/contextual/GlobalEnvelopePlanner.kt:45-51`; `ppe=min(8,⌊32/B⌋)`=6 at B=5 → E=3 for P=15). E=2 → 183 s/ch (10.2 h); single envelope → 147 s/ch (8.2 h). Cloud gains nothing (provider 12 s ≪ OCR 31.5 s — native-bound).
- Quality: per-page context dilutes; missing-block retries (1 whole + 2 partial, `translator/retry/AiTranslationRetryController.kt:42-44`) bound recovery but double per-envelope latency on failure. Provider domain owns validation.
- Risk: MED; quota-friendly (fewer, larger requests). Interactions: provider (owner), io (fewer envelope TXs), reader E.

### S13 — Interactive admission hardening during batch — NOVEL
- Mechanism: verifier §4.4: a manual waiter exceeding `maxForegroundWaitMs`=15 s is DEFERRED → reader Paused/retry cycle (`translator/ProviderRequestGovernor.kt:487-503`). Fix within policy: raise the interactive reserve (0.2, `:449-461`) while a reader session is foreground, or defer the aged BACKGROUND waiter instead when an INTERACTIVE waiter ages past a few seconds (queue-order preemption only — an admitted request is never interrupted).
- Impact: A: during a LAN envelope wait (up to 60 s) a manual tap admits on the next slot (~≤1 s + spacing) instead of a 15 s defer + retry. Total run −0 (BACKGROUND absorbs the wait).
- Quality: none (same requests). Risk: LOW technically but quota constants are T926-bound — needs Director sign-off; 30 s starvation guard semantics must survive.
- Interactions: provider (owner), reader A.

### S14 — Engine-signature pinning at batch-run scope — NOVEL
- Mechanism: the signature gate re-reads prefs on every translate path (`pipeline/EngineLane.kt:177-231`); a mid-run flip triggers a full rebuild inside the permit under `engineRebuildMutex` (`BatchChapterTranslator.kt:318-320`), stalling the lane I∈[3,15] s. Pin the signature at batch start; apply config changes at chapter boundaries.
- Impact: worst case (user fiddling mid-run) saves minutes of lane stalls across 200 chapters; protects B/D consistency. Typical run: 0 rebuilds, 0 change.
- Quality: none. Risk: LOW; boundary-applied changes must be surfaced or they look like stale config (reader UX note).
- Interactions: reader (config-change expectations), provider (signature includes key/model/baseUrl).

### S15 — Resume-frontier auto re-arm — NOVEL (F/D)
- Mechanism: `restoreQueue()` rehydrates everything PAUSED and never auto-starts (`ChapterTranslator.kt:184-219`); after a crash at ch137 the user must manually Start. Policy lever: auto re-arm the frontier chapter (highest partial progress) on process restart, behind a user setting. Safe: checkpoint adoption 10-50 ms/page (`:385-423`), zero-work COMPLETE resume (`:2019-2069`), analysis prefix never re-sent.
- Impact: F: user-visible resume latency minutes→0 (wall-clock resume ≈20 s [P 10-40 s] unchanged, per model §4.4). D: unchanged (fresh install has no queue).
- Quality: none. Risk: LOW-MED — auto-start of provider spend on launch is a product decision; consent surface belongs to reader/UX domain.
- Interactions: reader UX (owner of consent), io (queue ids already durable), provider (spend on relaunch).

### S16 — Native priority lane / preemption — REJECTED (negative space)
- Mapped and rejected: manual native-wait depth is ~ONE batch invocation (Round-1 V2 correction), and the `yield()` between pages (`:383`) already lets a queued manual jump the next batch page; preemption is impossible safely — timed-out invocations keep ownership until real exit (`scheduling/NativeRunQuarantine.kt:96-106`), `OrtSession.run` is non-interruptible, close-vs-run is leak-instead-of-SIGSEGV (`RoiPageRecognitionEngine.kt:87-103`). No-go F.4 stands; S2/S10/S13 solve the same reader pain cheaper.

### S17 — Per-workload EngineLane split — REJECTED (negative space)
- Splitting engine sets per workload multiplies native memory (96 MiB/session reserves, `util/TranslationMemoryBudget.kt:33-34`) and adds rebuild churn; the binding serialization is the quarantine mutex, not the engine cache. What is ALREADY legally off the lane: provider HTTP+sublimit waits (`pipeline/batch/ProfileEnvelopeExecutor.kt:563-575`), overlap inpaint riding those windows (`OverlapScheduler.kt:27-35`), checkpoint/run-record writes (IO persistence lane), parallel fingerprint hashing (`pipeline/batch/BatchChapterTranslator.kt:394-405`), and decode (S2). Verdict: keep the single EngineLane; S2/S11 capture the remaining legal off-lane/re-timed work.

## Cross-cutting

- Portfolio by scenario: A/C → S8+S10 (+S1, S9 standard lane, S13); B → S7 (+S5); D → S3; E → S12, S6, S11; F → S15, S4, S14. Biggest cloud lever: S11 (3.6→~2.0 h); biggest LAN levers: S6 (12.1→8.0 h) then S12 (→8.2 h); biggest reader lever: S7 (B from hours to minutes).
- Quality guardrail: only S6 and S12 touch the AI-lane quality bar; both are explicit trades requiring provider-domain measurement before adoption. Everything else is work-reordering with identical requests and fences.
- Sequencing: S8/S10 (small, fix the verified HIGH hazard) → S7 (small, huge B win) → S1/S9 → S2/S3/S5 → S13/S14/S15 (policy) → S11/S6/S12 (structural, need red-team round).
