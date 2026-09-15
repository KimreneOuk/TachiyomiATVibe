# T933 — Unified Chapter Context proposal

Base verified: `9c19ad05bd62cc3c222dfa8527fd4407b037ecc6`. Design only; no production or test change is proposed here.

## Recommendation

Adopt **one chapter-context owner with multiple typed producers**, but do not implement it as one mutable map and one global version. Keep the analyst. It is the only current producer of source-grounded gender, entity and scene evidence; the glossary miner is an advisory target-output co-occurrence heuristic (`ChapterGlossaryBuilder.kt:62-104`), and existing prompts explicitly reject prior target pronouns as gender evidence (`TranslationPrompts.kt:131-135`).

The owner exposes one `prepare(ContextRequest)` path for manual, auto and profile-batch prompts. Standard batch retains a no-context adapter so its current behavior remains byte-identical (`BatchChapterTranslator.kt:819-822`). It can still submit accepted page output to the term producer for later contextual consumers.

The rejected part of the draft is “one store, one version stamp, rolling pairs first.” A single stamp would make every rolling update look like glossary maturation and re-run prior pages: the current D5 gate deliberately converts REUSE to RUN when the glossary version increases (`PageWorkPlanner.kt:313-331`). The correct design has one owner but three identities:

| Identity | Meaning | Used for |
|---|---|---|
| `chapterContextRevision` | diagnostic publication order | observability only |
| `requestContextFingerprint` | exact selected, serialized prompt context | page/envelope provenance, retry/resume truth |
| `reuseCompatibility` | existing stage fingerprints, glossary D5 version, frozen-profile identities, plus any future narrow dependency rule | whether paid repair is required |

This preserves the D5 requirements: glossary maturation repairs the early page; an equal fold is cost-flat; a glossary-less chapter remains cost-flat; standard engines retain their decision/reason (`D5GlossaryAwareReuseTest.kt:250`, `:282`, `:319`, `:371`, `:399`).

## One model, typed producers

`ChapterContextService`, owned by the existing chapter-store mutex, has four operations:

1. `replaceCommittedPageContribution(pageKey, generation, sourceFingerprint, compactPairs)`. It replaces, never adds to, the old contribution after a successful terminal commit. PARTIAL, rejected and UI-only output supplies no durable evidence.
2. `publishAnalystEvidence(chunkEvidence, profileIdentity)`. Analysis remains optional and uses its existing bounded chunk/reconcile/freeze flow (`ChapterProfileBatchCoordinator.kt:805-940`, `:999-1118`). “Once per chapter” means one reusable logical analysis for a compatible corpus, not one HTTP request: a chapter is chunked and resume persists its successful prefix.
3. `prepare(ContextRequest) -> PreparedContext`. It selects, allocates and renders immutable evidence using request page/block IDs, known natural range, language, lane capability and frozen-run base.
4. `snapshotForRun`. It pins the analyst base and policies for a profile run. Later manual/auto observations form a live candidate overlay only; they do not mutate a frozen batch run in place.

Each selected record contains a stable ID, kind, source/target language, source evidence references and OCR revisions, page range, provenance, confidence and conflict state. Target-side output is rendered as “previous preferred rendering,” never as factual identity/gender evidence. Conflicts stay explicit; current profile reconciliation likewise preserves unresolved alternatives rather than arbitrarily selecting a winner (`ProfileReconciler.kt:171-202`).

Field-specific precedence is: user lexical choice; compatible source-grounded identity/gender/scene facts; analyst aliases; consistent output-derived lexical hints; recent pairs. Recent pairs guide voice and local discourse only. Scope is respected: a later fact cannot affect an earlier range, and contradictory equal-strength facts are shown as bounded uncertainty, not merged.

## Shared allocator and the 8k contract

Use one allocator for translation, profile envelopes, legacy-shaped envelopes and analysis. It validates the **final rendered provider messages**, including system prompt, chat template, JSON/escaping, headings, identifiers and the actual `max_tokens` value. Admission is:

`final input I + requested output O + reserve 512 <= 8,192`.

For translation, initial policy maximums are final `I <= 4,096`, `O <= 3,584`; for analysis, final `I <= 4,608`, `O <= 3,072`. In both cases the reserve gives exactly 8,192. These replace the current 16,384/8,192 envelope policy (`GlobalEnvelopePlanner.kt:43-51`), 16,384 analysis input (`AnalysisChunkPlanner.kt:41-47`), LM Studio 16,000 (`TranslationContextChunkPlanner.kt:196-201`) and analysis’s 8,192 requested output (`ChapterProfileBatchCoordinator.kt:782`, `:3130`; `AnalysisEngineTransport.kt:38-43`).

The quotas above are total final input, not source-only limits. The existing CL100K counter (`TranslationContextChunkPlanner.kt:143-148`) is useful for planning but cannot prove fit for every model/template. Each provider therefore needs an `InputAccountingContract`: an exact counter for the actual model/template or a certified conservative upper bound. Without one, that provider cannot dispatch under the all-model 8k guarantee. A fixed 512 margin is not a substitute for this contract.

Within context budget the selector keeps whole records in this order: relevant explicit term/entity decision (target 320 tokens), bounded uncertainty/gender safeguards (96), gap-free predecessor pairs (288), then local scene/style (96), with unused capacity flowing forward. Mandatory safety instructions live outside optional context. This deliberately reverses the old rolling-first policy: current single-page logic drops the whole glossary before pairs (`TranslationContextChunkPlanner.kt:104-111`), while profile execution drops scenes, unresolved lines, resolved lines, then pairs (`ProfileEnvelopeExecutor.kt:878-934`). Neither behavior is the unified rule.

For a dense one-page request, remove optional context first, then split whole-page envelopes and rebuild. If the page still cannot fit a complete response reserve, PAUSE without a provider call. Do not shrink output to the 256 floor merely to make arithmetic pass. OCR-gap rescan may fix missing/bad OCR; it cannot fix unchanged valid text that exceeds the context window (`ProfileEnvelopeExecutor.kt:393-409`, `:454-460`). Block-level splitting remains the stated Director decision.

## Storage, crash and resume

Increment 1 is **read-only projection and shared prompt assembly**. It reads current glossary/profile data through the service, writes no new pointer, and makes no T930 dependency. It must retain the existing fold-then-stamp order in `SinglePageHttpRenderPhase.kt:444-459` and preserve the D5 semantic version unmodified.

Increment 2 is **durable unified context**, sequenced after T930 Slice B approval and implementation. Add a versioned `ChapterContextSnapshot` sidecar and a `context` manifest pointer; the current manifest is schema 3 and explicit pointers require a schema bump, rather than an unknown-field assumption (`ChapterArtifactManifest.kt:37`, `:57`, `:63-83`). The snapshot is bounded metadata and references: glossary semantic fingerprint, optional frozen-profile pointer/input identity, user authority reference, committed predecessor contribution digests, scoped typed facts and policy versions. It stores neither bitmaps nor a full transcript.

At a page-terminal promotion, compute the replacement contribution, D5 pending glossary stamp and context delta together under the store mutex. Write immutable sidecars first, then publish the page generation, glossary/context pointers and stamp in the same combined manifest commit. A failure commits none. The request snapshot, captured before D9’s immediately durable paid-call ledger, is never rewritten after the response. In Increment 2 that snapshot is an immutable, bounded request-context sidecar (serialized selected records plus selector/prompt/accounting versions), not only a fingerprint. It is referenced by the run/envelope provenance before dispatch, so retry/resume either reconstructs byte-identical context despite a later manual replacement or fails closed with a typed new-generation reason. Increment 1 retains current resume guarantees and does not claim this new replay guarantee.

This follows all T930 amendments: glossary/legacy pointer writers force-flush and reread durable truth; second writers are registry-gated when flag ON; schema guards remain fresh; staged state is never request-visible; close/stop drain uses the existing bounds; D9 never stages. On process death before manifest publication, staged page/context evidence is dropped; after manifest publication, resume sees the matching committed set. Retention gains explicit context/run/request roots, including frozen active-run references.

The memory contract is explicit but requires a measurement gate before activation: one in-flight request snapshot per owner, one live projection plus one frozen run base; request snapshot <=64 KiB UTF-8, <=64 facts, recent pairs <=32 and <=16 KiB. These are payload limits, not heap limits. The implementation must set and measure a total resident budget covering live projection, frozen base, contribution/ranking index, simultaneously decoded sidecars and temporary selector/reconciliation buffers; excess state is disk-backed or streamed one page/chunk at a time. A 200-page high-distinctness fixture must record peak heap and work against this budget. Exact glossary semantics cannot be both unlimited and constant-memory: current `Stats` maps are uncapped (`ChapterGlossaryBuilder.kt:59-75`) and `build()` scans/sorts all candidates (`:80-104`). For the Phase 1 performance promise, use persisted page contribution records plus an incrementally maintained ranking, or accurately retain streamed recomputation as a slower fallback. Never seed exact counts from the 30-entry map or silently evict history.

## Lane lifecycle

Manual and auto capture a committed `PreparedContext` under the mutex, release it for network work, then on successful terminal commit replace the page contribution and fold/stamp atomically at the commit boundary. Arbitrary manual order only contributes pairs proven to be predecessors for the requested range; it must not invent a batch-style frontier.

Standard batch submits successful outputs to the producer but takes the no-context consumer path. It receives no analyst call and retains existing reuse behavior.

Profile batch captures a frozen analyst base after its existing durable analysis/reconciliation. Each envelope takes an immutable envelope snapshot with gap-free predecessor overlay; its exact fingerprint is stored with the envelope/page provenance. A later manual contribution is available to a future run, not retroactively to an existing frozen run. Resume uses the persisted frozen base and retained envelope snapshots; it does not replay analysis because a timestamp, policy publication or unrelated rolling pair changed.

## Tests and rollout

No existing safety-net assertion changes in Increment 1 or the durable context work. Preserve all coexistence tests, artifact transaction/crash tests, UI truth, manager reconstruction, batch write-gate and mid-run durability tests. In particular D5, D6 drain, D7 epoch, D9 ledger-before-call, D10 partial admission and D11 commit-after-barrier remain unedited.

Add: producer-conflict and scope examples; target-pronoun non-evidence; selected-context digest determinism; page replacement/subtraction; no contribution from PARTIAL/rejected work; final-message accounting for every transport/template; unavailable counter no-send; escaped/CJK/long-ID limits; analysis single-page oversize no-send; crash at every paired context/page commit point; second-writer stale-CAS retry; restart with matching pointer/stamp; frozen-run/manual overlap; retention roots; 200-page byte/work measurements; unchanged semantic republish cost-flat.

Existing non-safety planner/golden expectations may change only for the accepted 8k policy and typed-selection behavior: `TranslationContextChunkPlannerTest` LM Studio cap and glossary-drop expectation; `GlobalEnvelopePlannerGoldenTest`; `AnalysisChunkPlannerGoldenTest`; profile prompt formatting tests. Preserve their page atomicity, deterministic order, evidence-universe and prior-pronoun assertions. T930’s conditional mechanics conversions remain confined to its approved flag-on slice.

Sequence: Phase 0 test hygiene -> Phase 1 glossary fold plus batch-stall attribution -> Phase 1 8k compliance and provider accounting audit -> Increment 1 shared projection/prompt assembly -> T930 Slice A/B only with Director approval -> Increment 2 durable context pointer behind the T930 flag -> soak. Increment 1 is technically independent of T930 and can be scheduled before it; placing it after the 8k gate is a quality/scheduling preference, not a dependency on unapproved durability work. A changed analysis policy/input compatibility legitimately replans analysis; an unrelated rolling or publication change does not. Analyst retention is recommended. T930 start, quota edits, auto-resume and legacy migration deletion are still Director decisions and are not assumed here.
