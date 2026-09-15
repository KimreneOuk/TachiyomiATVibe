# Preliminary independent review — T933

Code-based design audit, 2026-09-14. No production or test edits; no tests executed. Reviewed proposed direction and T929 EXECUTION_ORDER v2, not a finished implementation. Paths below are repository-relative. Findings concern proposal hazards unless explicitly called current behavior.

## Recommendation

Proceed with one context owner/document and multiple typed producers, conditional on preserving evidence provenance, immutable run snapshots, scoped semantic reuse, and explicit memory/token accounting. Do not describe translation output as ground truth or one document revision as the universal reuse key. The one-system goal does not require one undifferentiated map, one version number, or one model call.

## Findings

### R1 — Output-derived mappings cannot become factual authority

HIGH; likely if proposed literally; design limitation / proposed regression. VERIFIED: `app/src/main/java/eu/kanade/translation/translator/contextual/ChapterGlossaryBuilder.kt:62–76` associates every source CJK candidate with capitalized target tokens by co-occurrence; `:97–104` accepts recall >=0.8. It does not validate semantic alignment. Its own description calls it advisory (`:4–18`). Repeated model choices establish consistency, not correctness. Names, gender, speaker identities and plot assertions must not acquire factual authority merely because several translations repeat them.

VERIFIED: the existing profile prompt explicitly says never infer gender from a prior translation (`app/src/main/java/eu/kanade/translation/translator/contextual/TranslationPrompts.kt:131–135`), and labels target-side pronouns as translations, never canonical gender evidence (`:208–222`). `app/src/test/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopePromptEnrichmentTest.kt:341–358` pins that marking.

Confirm/refute: a merged context must retain producer/evidence type and preserve that prompt rule; test conflicting source-supported identity versus repeated target pronouns. PROPOSAL: distinguish preferred rendering from evidence-backed identity, with conflicts retained rather than silently overwritten.

### R2 — Producer priority alone is an inadequate merge rule

HIGH; plausible for ambiguous terms; design limitation. VERIFIED: existing reconciliation puts conflicting target variants into unresolved facts rather than selecting a winner (`app/src/main/java/eu/kanade/translation/translator/contextual/ProfileReconciler.kt:171–202`). Existing profile schema distinguishes evidence, range and available-from scope (`app/src/main/java/eu/kanade/translation/artifact/ChapterTranslationProfile.kt:159–178`). A generic analyst-wins or latest-output-wins merge loses those semantics. A rendering choice is not equivalent to a resolved referent.

Confirm/refute: examples must cover same spelling/different entity, source-supported correction, equal-strength contradiction, and a later revelation unavailable to an earlier page. PROPOSAL: separate field-specific evidence precedence from prompt-budget selection; preserve unresolved state and source applicability. Do not average away conflict.

### R3 — Rolling-first allocation is not established as universally better

MEDIUM; common on dense requests; proposed quality limitation. VERIFIED: legacy attachment drops glossary first (`app/src/main/java/eu/kanade/translation/translator/contextual/TranslationContextChunkPlanner.kt:104–111`). Profile enrichment instead drops scenes, unresolved context, resolved lines, then rolling pairs before trimming its subset (`app/src/main/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopeExecutor.kt:889–932`). Unifying to legacy rolling-first would change profile behavior and can evict the only source-supported name/identity while retaining fallible prior outputs.

Confirm/refute: compare prompts for a dense dialogue page with a recurring named entity after a long gap; a budget test must show which required facts survive. PROPOSAL: retain mandatory interpretation rules and relevant supported identity/term entries before optional narrative; reserve a bounded recent-pairs share. Exact weights need quality examples, not intuition presented as proof.

### R4 — One global revision used for reuse risks repeated chapter repair

HIGH; likely if rolling/metadata updates bump it; proposed defect. VERIFIED: reuse currently changes REUSE to RUN when current glossary version exceeds recorded version (`app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt:317–331`). Equal glossary maps do not publish a new version (`app/src/main/java/eu/kanade/translation/store/ChapterGlossaryStore.kt:62–85`). The single-page path deliberately stamps after its own fold (`app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt:451–459`). A publication counter including rolling pairs makes every subsequent translated page stale earlier pages even without terminology maturation.

VERIFIED safety evidence: `app/src/test/java/eu/kanade/translation/coexistence/D5GlossaryAwareReuseTest.kt:250`, `:282–305`, `:319–343`, `:371–385`, `:399–407` pins maturation repair, unchanged zero-paid-call reuse, manifest rewrite survival, absent-glossary grandfathering and unchanged standard-engine decisions. Those assertions are load-bearing under EXECUTION_ORDER's assertion-level rule; renaming a field cannot justify changing their meaning.

Confirm/refute: replay/restart tests where only rolling history, counters or provenance changes must not trigger paid repair; a genuine matured glossary must still repair its stale witness. PROPOSAL: one reuse API may expose separate operational revision and semantic dependency identity. Preserve D5 compatibility explicitly, even if adding finer future dependency tracking.

### R5 — Frozen batch context and online updates need an explicit generation boundary

HIGH; likely on mixed manual/batch work; proposed correctness/resume defect. VERIFIED: profile content is frozen and version is not sole validity (`app/src/main/java/eu/kanade/translation/artifact/ChapterTranslationProfile.kt:235–248`); correction candidates are for a future run (`:259–260`). Executor captures a frozen profile (`app/src/main/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopeExecutor.kt:95–99`) and uses it for prompt construction (`:857–885`). Freeze publication tests require old sidecar untouched on supersession (`app/src/test/java/eu/kanade/translation/pipeline/batch/ProfileFreezePublicationTest.kt:153`) and reject bad fingerprints before writes (`:107`).

STRONG INFERENCE: reading mutable live terms per envelope while retaining the same frozen plan identity creates different semantics after resume/retry and can make earlier/later envelopes disagree under one run stamp. PROPOSAL: online producers may publish new live candidates while the run uses an immutable context projection; promote only at an explicit restart/new generation, or specify and persist an envelope-level snapshot contract. One document owner can represent both without treating the snapshot as another competing system.

Confirm/refute: interrupt between envelope completion and term publication, restart, and assert identical snapshot identity/prompt inputs for retained work. No in-place mutation of the frozen referenced content.

### R6 — One chapter analysis phase does not mean one request

HIGH if one request is required; certain on sufficiently large chapters; proposed capacity defect. VERIFIED: coordinator plans analysis chunks (`app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt:666–679`), resumes after a durable prefix (`:765–807`), executes each remaining chunk (`:809–810`), rereads durable chunks and reconciles (`:990–1042`). This supports one logical chapter analysis composed of multiple bounded calls.

VERIFIED: analysis planner currently allows 16,384 input (`app/src/main/java/eu/kanade/translation/translator/contextual/AnalysisChunkPlanner.kt:47`), and whole-page planning explicitly allows a single oversized chunk (`:148–150`). These are existing cap-work touchpoints, not compliant evidence for the proposed 8192 contract. PROPOSAL: retain optional analyst as a producer with bounded chunked work and durable resume; establish a final dispatch fit check for analysis as well as translation.

Confirm/refute: 200-page baseline must demonstrate multiple calls individually fit, successful prefix not resent, and oversized-single-page policy is honest. A single compact resulting document is compatible with that.

### R7 — Capping published entries does not bound accumulator or reconciliation memory

HIGH for a claimed bounded-memory design; likely as chapter text grows; existing/proposed design limitation. VERIFIED: `ChapterGlossaryBuilder.Stats` has uncapped source and nested rendering maps (`app/src/main/java/eu/kanade/translation/translator/contextual/ChapterGlossaryBuilder.kt:59–75`); MAX_ENTRIES=30 bounds output only (`:24`, `:80–99`). Store corpus extraction allocates all pairs (`app/src/main/java/eu/kanade/translation/store/ChapterGlossaryStore.kt:46–53`). Coordinator holds all durable analysis chunks in a list for reconciliation (`app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt:999–1042`); reconciler applies final caps after collecting and sorting draft facts (`app/src/main/java/eu/kanade/translation/translator/contextual/ProfileReconciler.kt:237–282`).

Confirm/refute: account for retained candidate strings, per-page replacement contributions, evidence refs, pending publications, frozen snapshot, live state, source/target pairs, serialization buffers and temporary sorting lists at the same time. Counts alone do not bound string bytes. PROPOSAL: explicit byte/count caps and persisted exact history or documented approximation strategy; no silent eviction presented as identical mining semantics. Memory figure must be a measured peak or stated bound, not final JSON length alone.

### R8 — Incremental Stats alone does not deliver corpus-independent fold cost

MEDIUM; certain for current build algorithm; CONTRADICTION with an unconditional O(B) reading of T929 gate. VERIFIED: every `Stats.build()` filters all source counts and sorts qualifying candidates (`app/src/main/java/eu/kanade/translation/translator/contextual/ChapterGlossaryBuilder.kt:80–88`), then `bestRendering` sorts each candidate's target counts (`:97–99`). Keeping Stats alive removes full pair re-tokenization but does not remove this corpus-dependent projection. EXECUTION_ORDER `:150` asks for corpus-independent per-page fold cost. That claim requires more than the accumulator described by Phase 1.1.

Confirm/refute: operation-count or varied-corpus benchmark with constant changed-page size and increasing distinct candidate count. PROPOSAL: revise complexity claim honestly or maintain ranking incrementally; per-page replacement/subtraction remains needed. Existing glossary tests (`app/src/test/java/eu/kanade/translation/translator/contextual/ChapterGlossaryBuilderTest.kt:10–60`) exercise functional examples, not complexity.

### R9 — Gap rescan cannot guarantee recovery of genuinely oversized valid OCR

MEDIUM; certain when source text remains unchanged; CONTRADICTION with literal recovery promise in EXECUTION_ORDER `:139`. VERIFIED: envelope planner rejects a page's input/output/block overflow (`app/src/main/java/eu/kanade/translation/translator/contextual/GlobalEnvelopePlanner.kt:195–206`); execution checks single-page source plus context against available tokens (`app/src/main/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopeExecutor.kt:454–460`). Typed pause with zero provider calls is tested (`app/src/test/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopePromptEnrichmentTest.kt:461–496`).

STRONG INFERENCE: rerunning OCR only helps if it fixes spurious text or changes the payload enough to fit; unchanged valid text remains too large. PROPOSAL: separate OCR-gap recovery from token-limit recovery; trim optional context, split envelopes by whole pages, then honest pause. Block-level splitting remains a Director-deferred change. Do not promise rescan as a universal escape or repeatedly pay for an identical failed request.

## Token-proof boundary

VERIFIED through Main Leader's explicit spot-check report: estimator uses CL100K_BASE (`app/src/main/java/eu/kanade/translation/translator/contextual/TranslationContextChunkPlanner.kt:143–148`), not a character heuristic. Fixed planner overhead and safety reserve (`:21–27`) do not prove actual request count for arbitrary model tokenizers and provider templates. A proposal may show exact arithmetic under its declared accounting model; actual-model 8192 compliance needs the final rendered messages/framing and model accounting contract. Tests must distinguish conservative planning estimate from strict final dispatch guarantee.

## Review status

Preliminary only. Final review still needs the concrete unified schema, allocator arithmetic, storage/lifecycle plan and test conversion ledger. No implementation start or T930 approval is implied by this audit.
