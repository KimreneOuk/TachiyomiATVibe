# Current investigation contract — chapter-profile Batch architecture

2026-09-05. Design/investigation only, no implementation. Supersedes progressive
OCR/chunk tuning as the direction being evaluated, not as an approved shipped design.

Director proposes local validation/hash -> full bounded detection/OCR preflight
-> hierarchical structured chapter analysis -> reconcile/freeze versioned Chapter
Translation Profile -> global whole-page envelope planning -> sequential provider
translation -> validation -> native/render/commit. Stress target: 200 pages.
Manual/Auto retain latency-oriented reader behavior.

Evaluate against current HEAD, not earlier reports. Preserve Android 8+, bounded
memory, page atomicity, one Batch provider envelope in flight, shared leases,
candidate/committed display, per-page refresh, durable reuse, source/config fences,
foreground-service behavior, gap-free rolling context and token ceilings.
Progressive OCR PROBE is now explicitly eligible for replacement in the new Batch
path; preserve old-path semantics and logical planning order where still used.

Profile must distinguish canonical terminology/entities/aliases, supported gender
and pronouns (including unknown/conflicting), evidence and confidence, scene-local
tone/semantic context, and range-scoped narrative. No guessing gender from weak
cues; no chapter-wide lexical replacement for explicit scenes. Later canonical
identity facts may inform earlier dialogue, but future narrative must remain scoped.
Summaries complement structured extraction, never replace it.

Analysis uses bounded token/structure chunks with evaluated overlap, persisted
validated candidates and hierarchical conflict reconciliation, not one huge raw
OCR or concatenated-candidate request. Distinguish explicit user canon, established
series canon, frozen chapter profile, rolling source/translation history, and local
inference. No silent profile mutation during translation; corrections separate.

Requests combine relevant profile subset, scene scope, gap-free rolling history
with source provenance, current stable page/block IDs. Plan using hard input/output
budgets and structural/page/block ceilings. Validate exact ownership/ID coverage;
consider bounded whole-page split/backoff for malformed output without false
context advancement. Static conservative policies preferred before adaptation.

Investigate existing persistence, native ownership/backends/inpaint overlap,
leases across phase boundaries, force OCR reuse, sparse URL-to-filename migration,
config/profile invalidation, interruption at every phase, reader work during
preflight/freeze, shared analysis+translation governor with intended15-RPM norm,
API cost and preparation progress. Do not silently broaden implementation scope.

Deliver source-cited feasibility/impact map, state machine, data model, memory/
concurrency design, analysis/reconciliation and request contracts, envelope and
failure policies, resume/coexistence rules, 200-page cost scenarios, full testing
matrix and open decisions. Explicitly separate VERIFIED, RECOMMENDATION,
ASSUMPTION/INFERENCE and DEVICE/RUNTIME QUESTIONS. Main Leader integrates
specialist reports into design/chapter-profile-batch-design.md and updates README.
