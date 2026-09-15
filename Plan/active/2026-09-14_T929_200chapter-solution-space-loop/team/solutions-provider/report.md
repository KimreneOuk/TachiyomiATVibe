# T929 Round 2 — provider/quality solution catalog (200-chapter baseline)

- Mapper: Technical Lead (provider lane). Base `main` @ `9c19ad0`, code-over-docs.
- All paths relative to `app/src/main/java/eu/kanade/translation/`. Baseline + formulas from
  `team/model/report.md` (P=15 default: AI lane = `C+E` = 1+3 = 4 requests/chapter, 800 total;
  standard lane = 15/chapter, 3,000 total; cycle = `max(4s, L+1s)` per AI request; cloud 3.6 h vs
  LM Studio LAN 12.1 h total).
- Code-verified facts anchoring this catalog: governor buckets are keyed per
  `ProviderRequestKey(backend, model, credentialScope)` (translator/ProviderRequestGovernor.kt:22-41,
  buckets map :281) — **quota isolation is per-backend, not process-wide**; `desktop` fast-path policy
  (1000 RPM, ∞ TPM, 0 spacing) exists at :670-677 with **zero production callers** (grep). LM_STUDIO
  context in code is **16,000**, not the directed 8k (translator/contextual/TranslationContextChunkPlanner.kt:196-202).
  All provider responses are **blocking, non-streaming** (`response.body?.string()`,
  translator/providers/OpenAiCompatibleTranslator.kt:144-149; 60 s connect/read/write timeouts :47-51).

## Catalog

| # | Solution | Class | 200-ch wall-clock (cloud / LAN) | Quality vs AI-lane bar | No-go touched |
|---|---|---|---|---|---|
| 1 | Envelope-size scaling (32→48 blocks ⇒ ppe 6→8) | request-count | neutral / 12.1→8.2 h | neutral (same per-page inputs) | none (policy constants) |
| 2 | LM window policy: keep 16k vs enforce 8k | decision | 3.6 h / 12.1 h vs 14.2 h | 8k ⇒ smaller envelopes, more split pauses | T926 directive conflict |
| 3 | SSE streaming for LAN providers | **NOVEL** | removes 60 s-timeout pause cycles | neutral; earlier per-page commits | none |
| 4 | Activate `desktop` quota fast-path for local backends | **NOVEL** | −50 min standard-lane LAN; small AI | neutral | quota policy (revocable by decision) |
| 5 | Per-backend bucket concurrency (analysis ∥ envelopes) | **NOVEL** | −37 min / −37 min | neutral | none (each bucket ≤ its own gate) |
| 6 | Per-chapter provider routing (hybrid cloud+LAN) | **IMAGINATIVE** | e.g. 100+100 split = 7.9 h LAN | neutral (bar held per chapter) | none; engine-rebuild thrash risk |
| 7 | Cross-chapter OCR ∥ translate overlap | **NOVEL** | 3.6→~2.4 h / 12.1→~10.7 h | neutral | none (both lanes stay ×1) |
| 8 | Series-scoped profile carry-over (kill per-chapter analysis) | **NOVEL** | −37 min / −4.2 h | risk: cross-chapter drift (gated) | none (schema fields exist, empty) |
| 9 | Draft-and-refine (fast draft, background refine) | **IMAGINATIVE** | draft TTFP ~30 s/ch; refine = today's cost | draft BELOW bar until refined; final = bar | committed-display rules upheld |
| 10 | QA-flagged targeted re-translate (PARTIAL→READY) | quality | +~6 min cloud | **better** (completes pages) | none |
| 11 | Pre-build envelope payloads during gate waits | pacing | −tens of seconds | neutral | none |
| 12 | Glossary/rolling-context budget raise (1.5k→3k tok) | quality | neutral (TPM headroom OK) | **better** continuity | none |
| 13 | Standard-lane contextual batching (1 req/page → chunk) | request-count | 3,000→~600 requests | better than plain standard; below AI bar | none |
| 14 | Speculative prefetch of unread chapters in idle sublimit | **IMAGINATIVE** | shifts TTFP, not totals | neutral | native-lane contention risk |
| 15 | Retry-budget tightening (8→5 attempts) | quota economy | −~30 % pathological-run cost | neutral (typed pauses, cheap resume) | none |
| 16 | Provider connection pre-warm / pool keep-alive | pacing | ~1 s per process | neutral | none |

## Per-entry detail

**1. Envelope-size scaling.** (1) Raise `EnvelopePlannerPolicy.maxBlocksPerEnvelope` 32→48 so
pages/envelope = min(8, ⌊48/5⌋)=8. (2) translator/contextual/GlobalEnvelopePlanner.kt:43-57 (constants are
"MEASURED-EXPERIMENT… never product constants" :38-42); budget re-check :331-347. (3) E: 3→2 ⇒ LAN chapter
= 2·36+76 = 148 s vs 184 ⇒ 8.2 h vs 12.1 h (−32 %). Cloud: neutral (native-bound, model §4.1).
(4) Neutral: identical per-page source + profile-subset context, fewer rolling-context resets. (5) Risk:
larger output per request vs 60 s blocking read (entry 3 interacts); oversized-PAUSE logic unchanged.
(6) Envelope-plan sidecar republished (EnvelopePlanPublication.kt:43-80); fingerprint inputs include policy
(GlobalEnvelopePlanner.kt:355-369) — plan-policy-only change must not invalidate committed translations
(invalidation matrix row 7, planner doc :40-42).

**2. LM Studio 16k vs 8k.** (1) Director decision: code has 16,000 (`constraintsFor`,
TranslationContextChunkPlanner.kt:196-202; selected via translator type SinglePageHttpRenderPhase.kt:247-252,
BatchChapterTranslator.kt:361-366, coordinator `providerChunkProfile()` :2455-2460 keys on
`frozenConfig.providerKey` prefix `lmstudio:`). (2) as cited. (3) Strict 8k ⇒ execution-time split
(`splitForTokenFit`, pipeline/batch/ProfileEnvelopeExecutor.kt:435-484) halves ppe to ~3 ⇒ E=5 ⇒
5·36+76 = 256 s/ch ⇒ 14.2 h (+2.1 h); analysis chunks still fit 8k (16 pages ≈ 2.6k source tok + ~3k
instructions). (4) Neutral-to-worse: more whole-page splits and trim-ladder entries (executor :877-934).
(5) No structural no-go; policy conflict is the point. (6) Larger envelopes (16k) widen inpaint overlap
windows (OverlapScheduler) — quality-inpaint spill shrinks (model §5.5).

**3. NOVEL — SSE streaming for LAN.** (1) `stream:true` + incremental parse; commit per-page as its blocks
arrive. (2) Today blocking: OpenAiCompatibleTranslator.kt:144-149 (`body?.string()`), 60 s timeouts :47-51;
LmStudioTranslator.kt:53-82 builds plain JSON. T926 RCA attributes LM Studio failures to exactly this
(60 s read timeout on blocking non-streaming request). (3) Each 60 s timeout wastes a full envelope attempt
(budget 8, retry/re-admission ≈ +4 s pacing): at even 5 % failure on 600 LAN envelopes ≈ 30 wasted min;
streaming converts them to completions and lets frontier/inpaint windows open mid-response. (4) Neutral:
same model output; earlier commits improve perceived quality-of-service, not fidelity. (5) Risk: parser must
handle partial BATCH_V1 protocol — reuse the existing ambiguous/MISSING_ONLY classification
(executor :600-680) on stream truncation; retry budget semantics unchanged. (6) Durability: commit path
TX-20 unchanged per page; reader sees pages land sooner (scenario C).

**4. NOVEL — `desktop` quota fast-path.** (1) Key local/LAN backends as `backend=="desktop"` (or extend
`defaultPolicy` to `lm_studio`) to drop cloud-style spacing/TPM. (2) ProviderRequestGovernor.kt:670-677
(policy exists, no callers — verified by grep); LmStudioTranslator.kt:31 (`providerBackend="lm_studio"`),
credentialScope = base-URL hash :33 (each LAN host = own bucket). (3) Standard-lane LAN: 15·(L+1)→15·L ⇒
−15 s/ch ⇒ −50 min/200 ch; AI lane: cycle is `max(4, L+…)` — the 1 s spacing saving is ~1 s/request ⇒
−3-5 min; main value is removing misapplied governance (T926 RCA finding) plus future fast local models
(L=2 s: sublimit floor 4 s still binds unless batch is also exempted — a separate, explicit policy change).
(4) Neutral. (5) Quota safety is a POLICY no-go, explicitly "revocable by decision" (verify-sched §6);
unmetered LAN endpoints are the safe case. (6) Reader reserve (20 %, :449-461) becomes moot per bucket —
fine for a private LAN host; keep reserves for cloud buckets.

**5. NOVEL — per-backend bucket concurrency.** (1) The governor serializes per KEY, not process-wide:
one in-flight `lm_studio` envelope + one in-flight `gemini` analysis are already legal (each bucket's own
`maxInFlight=1`). Wire the coordinator to run analysis (cloud) concurrently with the previous chapter's
envelopes (LAN). (2) ProviderRequestGovernor.kt:22-41, :281, :465 (`inFlightReady` is per-bucket);
sublimit is per-credential too (:717-722). Today single-threaded phases make this moot (executor :181;
analysis loop ChapterProfileBatchCoordinator.kt:805-936). (3) Saves the smaller lane per chapter:
analysis on cloud ≈ 11 s hidden under LAN envelopes ⇒ −37 min/200 ch; symmetric on cloud (analysis under
OCR of next chapter via entry 7). (4) Neutral: both providers still see ≤ their own 15-RPM sublimit.
(5) **No no-go touched** — "parallelism beyond quota gates" is untouched; each gate is respected. Risk:
TPM bursts on the shared home network for LAN; reader INTERACTIVE waiters only shrink their own bucket.
(6) Cross-chapter coordination (entry 7) is the natural host; crash/resume: two phase-partial chapters at
once widens the F-resume window.

**6. IMAGINATIVE — per-chapter provider routing.** (1) Route chapters to providers by budget/queue
position (chapters 1-100 cloud while quota lasts, 101-200 LAN). (2) Provider is fixed per run today
(`translationEngineCategory` + `textTranslator`, BatchChapterTranslator.kt:360-366); translator-signature
change triggers a full engine rebuild inside the native permit (EngineLane.kt:410-444) — switching pays
`I ∈ [3,15] s` [P] per switch unless amortized over chapter blocks. (3) 100 cloud + 100 LAN = 1.8 h +
6.05 h = 7.9 h (vs 12.1 h LAN-only). FP-04 keys the analyzer provider into the profile input fingerprint
(coordinator :2566-2582), so per-chapter analysis-provider changes are safe; envelope provider per chapter
is fingerprint-free. (4) Neutral: bar held per chapter. (5) Rebuild thrash (native-lane occupancy) if
switched per-chapter — switch in blocks; profile-reuse gate compares FP-04, so changing analyzer invalidates
reuse (expected, per-chapter). (6) Scenario B: put the chapter the reader jumped to on the FAST provider.

**7. NOVEL — cross-chapter OCR ∥ translate overlap.** (1) Run chapter N+1's OCR preflight on the native
lane while chapter N's envelopes drain on the provider lane. (2) Today one active chapter per source
(ChapterTranslator.kt:399-446) and strict phase order (coordinator :378-525 → :571-595); the native lane is
idle during provider waits (only inpaint rides them, OverlapScheduler.kt:27-65) and the provider lane idle
during OCR. (3) Cloud: 64 s/ch serial ≈ 31.5 OCR + 24 provider + 8.5 overhead → overlapped ≈ max(31.5, 24)
+ overhead ≈ 42 s/ch ⇒ ~2.3 h (−36 %). LAN: provider-bound 184 s ≫ 31.5 ⇒ ~10.8 h (−11 %).
(4) Neutral: envelope order and rolling-context within a chapter unchanged. (5) Both singletons stay ×1 —
no native or provider parallelism no-go; risk is memory (two live chapter stores + held bitmaps) and the
manual-tap corpus-gap race doubling (verify-sched §2). (6) Sched-domain co-design; reader responsiveness
improves (native lane busy while provider waits → tap waits stay ~1 invocation).

**8. NOVEL — series-scoped profile carry-over.** (1) Promote stable facts (names/terms) once per series;
chapters 2-200 reuse them, skipping analysis (`C: 1→0`). (2) The schema fields exist and are deliberately
EMPTY: ProfileReconciler.kt:38-42, :90-92 ("Nothing auto-promotes to series scope… corrections belong to a
later run"); profile reuse gate requires same-corpus FP-04 (coordinator :2519-2557) — a series layer would
sit beside, not inside, FP-04. (3) Kills the 76 s LAN / ~11 s cloud analysis leg: LAN 184+76 → ~148 s/ch ⇒
12.1→9.6 h (−21 %); cloud −37 min; also removes the analysis+freeze barrier from ch.1 TTFP
(model §5.4: −112 s LAN). (4) RISK to quality: character evolution/renames across 200 chapters. Gate on
evidence strength + keep per-chapter unresolved facts; envelope prompts already merge profile-subset +
rolling context, so stale canon is bounded by the trim ladder (executor :877-934). (5) No no-go; it edits
freeze semantics — new provenance kind needed (AnalyzerProvenance). (6) Durability: series store becomes a
new sidecar class (io slice input); scenario F: series facts must survive partial-chapter resumes (they do —
frozen before envelopes).

**9. IMAGINATIVE — draft-and-refine.** (1) Immediate draft translation for reading (standard-lane cloud
fast, ~2 s/page) + background refine pass under the AI profile lane. (2) Legal on the display side:
committed result stays visible while a differing candidate runs — `REFRESHING_WITH_COMMITTED_RESULT`,
model/PageDisplayProjection.kt:89-94; no committed revocation. But the AI lane never re-translates READY
pages (`pageAuthoritativelyDone`, ProfileEnvelopeExecutor.kt:972-981) — refine needs a NEW candidate mode,
not reuse of the envelope loop. (3) Scenario A TTFP: ~30 s/chapter draft vs 64-219 s full AI; refine runs
at today's 800-request cost during reading gaps; draft adds 3,000 cheap standard requests. (4) EXPLICIT
TRADE: draft pages are BELOW the AI-lane bar until refined (name/terms may drift before the chapter
glossary/profile mature); final state = bar. Must surface draft provenance
(`translationOrigin` exists, SinglePageHttpRenderPhase.kt:175). (5) No no-go (never revokes committed
display; promotion atomic). Risk: double durable writes (~2× page commits — io slice), refine storm
competes for the 15-RPM sublimit with the next chapter's envelopes. (6) Reader UX is the whole point
(scenario A/C); glossary fold from manual pages already exists (:444-459) — drafts fold too, feeding the
refine pass.

**10. QA-flagged targeted re-translate.** (1) A cheap QA sweep flags PARTIAL/echo/blank pages and issues
targeted missing-block requests (machinery already exists: `RequestKind.MISSING`, AiTranslationRetryController.kt:455-463,
cap 2 per envelope; durable FAILED_RETRYABLE metadata, coordinator :2468-2504). (2) as cited. (3) ~3 % of
3,000 pages × 1 request = 90 requests ⇒ +6 min cloud at 4 s pacing. (4) **Better than bar-adjacent status quo**:
pages stuck PARTIAL (missing blocks rejected as echo/blank, controller :691-696) become READY; a later
run currently needs a full chapter re-queue to repair (verify-sched §2 converse). (5) None; rides existing
sublimit + budget. (6) Interacts with resume (repair state must be durable — it is, via failure metadata).

**11. Pre-build envelope payloads during gate waits.** (1) Assemble envelope i+1's chunk
(`buildEnvelopeChunk`: ProfileSubsetMatcher + jtokkit estimation, executor :835-966) while envelope i is
in flight or blocked at the sublimit — the build is currently serial in the one-in-flight lane
(verify-sched §4.7). (2) executor :550 (`prepared = buildEnvelopeChunk(...)` inside the dispatch path).
(3) ~50-200 ms × 600 envelopes ⇒ tens of seconds per 200 ch; zero on cloud (native-bound anyway).
(4) Neutral. (5) None — pure CPU before dispatch, no extra requests. (6) Must not read ahead of the
frontier: context depends on committed rolling history (executor :784), so only the NEXT envelope's
planner-time portion is pre-buildable; execution-time recompute (D5) stays.

**12. Glossary/rolling-context budget raise.** (1) DEFAULT `MAX_ROLLING_CONTEXT_TOKENS` 1,500→3,000 and
pairs cap 32→48. (2) TranslationContextChunkPlanner.kt:39-42; trim ladder consumer executor :901-934;
glossary persisted per chapter via ChapterGlossaryStore.kt:55-99, folded after every manual page
(SinglePageHttpRenderPhase.kt:444-459), versioned reuse gate PageWorkPlanner.kt:113. (3) Cost-neutral:
+1.5k tok × 4 req/ch ≈ 6k tok/ch ⇒ 1.2 M tok over 200 ch, ≈20 k tok/min worst-case < 60 k TPM even under
the 20 % reserve. Zero extra requests. (4) **Better**: fewer dropped pairs ⇒ better pronoun/name continuity
— the trim ladder currently drops scenes→pairs→subset exactly when chapters are dense. (5) None; LM_STUDIO
cap (1,024) untouched to preserve the 8k discipline. (6) Longer prompts on LAN raise L slightly [P +1-3 s].

**13. Standard-lane contextual batching.** (1) Batch standard-lane pages through the existing chunk
planner (TranslationContextChunkPlanner.plan :62-77) instead of 1 request/page. (2) Standard loop:
pipeline/batch/BatchLaneWorkers.kt:933-956 (`textTranslator.translatePage` per page, in order),
shared bucket only (60 RPM, 1 s). (3) 3,000→~600 requests; latency-bound chapter 15·(L+1) → ~3·(6L+1):
L=1 s: 30→21 s/ch; the big win is metered per-request APIs (−80 % request spend). (4) Better than plain
standard (glossary/rolling pairs ride along — plain per-page has neither); still below the AI-lane bar
(no corpus profile). (5) None for cloud backends with size limits; page atomicity must hold per request.
(6) Renders per page still join via BatchRenderJoin; inpaint windows widen (fewer, longer waits).

**14. IMAGINATIVE — speculative prefetch in idle sublimit.** (1) Use leftover 15-RPM batch allowance
(cloud uses ~4 req/min at 64 s/ch ⇒ ~11 idle) to translate AHEAD of reader position; combined with queue
reordering (model §5.2) this is scenario B's real fix. (2) Sublimit cap :710; FIFO queue ChapterTranslator.kt:399-446;
prefetch lanes must stay BACKGROUND — the 20 % interactive reserve (:449-461) is untouched by construction.
(3) Shifts TTFP(jump to ch.100) from 99 marginals toward "already translated" — up to −1.77 h (cloud);
totals unchanged. (4) Neutral IF refined under the same chapter profile; speculative pages translated
before analysis would lack the profile — below bar; only prefetch post-freeze pages. (5) The scarce
resource is the NATIVE lane (OCR must precede translation), which has NO priority mechanism — prefetch
OCR can delay a reader tap by up to one invocation (≤120 s cap); the `yield()` courtesies
(executor :183, coordinator :383) are the only mitigation. (6) Reader-strategy slice co-design; memory:
translated text commits are cheap and durable (scenario F safe).

**15. Retry-budget tightening.** (1) Root budget 8 attempts (TranslationRetry.kt:72) × semantic policy
(1 whole + 2 missing, AiTranslationRetryController.kt:41-51; analysis 2 classified × 3 transport,
AnalysisChunkExecutor.kt:168-185) ⇒ up to 8 HTTP attempts per envelope, each re-admitted through the
sublimit (verify-sched §4.3). Tighten to 5 for BACKGROUND; keep 8 for INTERACTIVE. (2) as cited.
(3) Pathological envelope worst case: 8×(60 s timeout + 4 s pacing) ≈ 9 min → 5× ≈ 5.5 min (−30 %).
(4) Neutral: exhaustion is a typed PAUSE with cheap resume (ST-08 analysis prefix, coordinator :765-807;
TX-20 committed pages never re-paid). (5) None. (6) More frequent pauses under flaky LAN — surface as the
existing retry-at message, not a failure.

**16. Provider connection pre-warm.** (1) Warm DNS+TCP+TLS to the provider host at batch start (one HEAD
or model-list call). (2) Cold OkHttp pool per translator (OpenAiCompatibleTranslator.kt:47-51; pool evicted
only on close :256-259; AiModelFetcher already lists models — reuse its path). Cost today ≈ 0.1-1 s once
per process (pool persists across chapters). (3) Negligible totals; removes a first-envelope jitter from
ch.1 TTFP (scenario D cold start). (4) Neutral. (5) None; the warm-up request must ride the governor at
BACKGROUND. (6)scenario D benefit only.

## Cross-entry notes for the red-team round

- Biggest LAN levers: **8 → 9.6 h (entry 8), 8.2 h (entry 1), 7.9 h (entry 6)**; combined with entry 7 ≈ 6-7 h
  plausible without touching any no-go. Cloud totals are native-bound: only entry 7 materially moves 3.6 h.
- Entries 4/5 hinge on the per-backend bucket fact (governor :22-41,:281) — please red-team whether
  "one in-flight per bucket" plus two buckets constitutes "provider parallelism beyond quota gates" (my
  reading: no — each gate is individually respected; the no-go text targets exceeding a gate).
- Entry 9's refine pass and entry 14 both create SECOND writer classes for pages — both must route through
  the existing lease/CAS ladder, and both widen scenario-F resume surface.
- Entry 8 is the only entry that changes freeze semantics; everything else preserves profile-per-chapter.
