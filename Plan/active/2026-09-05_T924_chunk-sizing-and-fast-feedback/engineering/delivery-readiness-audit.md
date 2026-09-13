# T924 delivery-readiness audit

Date: 2026-09-05  
Baseline: `adbe643`  
Scope: contextual AI Batch Translation; delivery analysis only

## Verdict

The architecture is coherent and the migration direction is sufficiently settled to
start contract design, but the package is **not yet an implementation-ready plan**.
An implementer can understand the intended product and architecture, yet cannot
execute every stage without making consequential design choices that are still only
listed as open decisions. The largest gaps are the exact persisted schemas and state
transitions, the OCR checkpoint transaction contract, mixed-response commit policy,
provider/analyzer capability contract, and stage-specific acceptance thresholds.

The current records answer *what system should exist* and *why*. They do not yet
provide a work-package ledger that maps each change to exact source owners, migration
rules, tests, fixtures, feature flags, rollback conditions, and accepted evidence.
Without that ledger, two competent implementers could produce materially different
storage, retry, invalidation, and completion semantics while each believing they
followed the design.

Implementation should begin only with Stage 0 below. Stages 1-8 should become
authorized separately as their blocking decisions and exit evidence are recorded.

## Canonical reading order

Use this order for every implementer and reviewer. Later documents narrow or
supersede earlier ones.

1. `README.md` — task boundary, Director intent, non-negotiable constraints, current
   status and decision register. Treat “not implementation authorization” literally.
2. Attached final target (`pasted-text.txt`) — product vision and desired completion
   contract. It is the intent source, not a source-code feasibility record.
3. `design/chapter-profile-batch-design.md` — code-backed architecture, state machine,
   data model, coexistence, retry, resume, progress and test inventory.
4. `design/final-target-migration.md` — controlling reconciliation. It overrides the
   prior progressive contextual-AI Batch design, separates layout migration from the
   coordinator cutover, and narrows layout/style claims.
5. `engineering/delivery-readiness-audit.md` — execution order, gates and proof
   requirements. It does not override product invariants.
6. Stage-specific contract/specification and work-package file, once created. This
   must name exact source/test entry points and resolve that stage's decisions before
   coding begins.
7. Relevant source files and existing tests cited by the stage spec. Read shared
   ownership/store contracts before workers or UI consumers.
8. Implementation diff, test results, device/provider evidence, and independent
   conformance review for that stage.

Conflict precedence is: explicit Director decision → final target product invariant
→ `final-target-migration.md` correction → `chapter-profile-batch-design.md` detail →
stage spec. Any unresolved conflict stops the affected work package and updates the
decision register; it does not invite an implementer to choose silently.

## Work-package dependency graph

```text
WP0 decision closure + traceability ledger
  |
  +--> WP1 run/artifact schemas + migrations + semantic fingerprints
  |      |
  |      +--> WP2 atomic OCR checkpoint/rebase
  |      |      |
  |      |      +--> WP4 feature-flagged OCR-preflight coordinator
  |      |
  |      +--> WP3 pure corpus/chunk/profile/envelope/retry planners
  |             |
  |             +--> WP5 typed analysis + profile freeze/resume
  |                    |
  |                    +--> WP6 profile-aware translation + split/backoff
  |                           |
  |                           +--> WP7 translation/inpaint overlap
  |
  +--> WP8 persisted-layout DTO/fingerprint prototype
         |
         +--> WP9 LAYOUT_PREPARE publication + reader hydration/fallback

WP4 + WP5 + WP6 + WP7 + WP9
  --> WP10 lifecycle/device/provider evaluation
  --> WP11 default enablement
  --> WP12 contextual-AI PROBE cleanup after rollback window
```

WP8 may be developed beside coordinator work after WP0, but WP9 must remain a
separate feature and completion-contract migration. WP6 depends on a frozen profile,
not on persisted layout. WP7 depends on stable translation publication and OCR mask
reuse. WP11 depends on evidence from the complete path. WP12 must never precede
default evidence and a defined rollback window.

## Stage gates and exit criteria

### Stage 0 — decision closure and executable specifications

Create a decision record, requirements-to-evidence matrix, schema definitions,
state-transition table, invalidation table, and one work-package file per stage.
Every requirement must have a stable ID and one owner, implementation location,
test/evidence method, and status. Define the contextual-AI feature flags and rollback
behavior.

Exit: all blocking decisions below are accepted; schemas have examples and validation
rules; every durable transition names its transaction preconditions, atomic writes,
postconditions and crash result; every later stage has exact source/test entry points.

### Stage 1 — contracts, artifacts and semantic identity

Implement additive/versioned run, OCR checkpoint, analysis chunk, frozen profile,
envelope-plan and persisted-layout records plus crash-safe manifest pointers. Existing
runtime behavior remains selected. Define canonical serialization before hashing.

Exit: old manifests load; unknown/new versions fail or downgrade predictably; sidecar
publication is crash safe; semantic-equivalent artifacts hash identically; transaction
IDs and filenames do not affect semantic hashes; user-edited and committed display
authority survives every migration/invalidation test.

### Stage 2 — pure planning, validation and retry policy

Implement deterministic OCR corpus construction, analysis chunking, structured
validation, deterministic pre-merge, profile-subset matching, global envelope
planning, response taxonomy and the shared root retry ledger.

Exit: golden fixtures cover empty/textless, sparse, 200-page synthetic, boundary
overlap, conflicting facts, future-fact scoping, oversized single page, reordered IDs,
duplicates, unknown IDs, refusals and missing-only output. Repeated input produces
byte/semantic-equivalent plans. No child can escape its root attempt/depth/leaf caps.
No partial page commits or advances the context frontier.

### Stage 3 — OCR checkpoint and preflight coordinator

Land `checkpointOcr` before enabling preflight. Then add a contextual-AI-only feature
flag that decodes and recognizes one page, persists/checkpoints it, releases bitmap
and lease, yields to interactive work, and continues. Cloud analysis, translation and
inpaint remain absent from this stage.

Exit: injected failure at every checkpoint publication boundary preserves the prior
manifest; Manual/Auto can start a fresh candidate from checkpointed OCR; stale Batch
writes are rejected; prior committed display remains visible; cancellation/process
death loses at most the active page; a real approximately 200-page run records bounded
Java/native/graphics memory, OCR throughput, thermal state and interactive latency.

### Stage 4 — analysis and frozen profile

Add a typed structured provider contract, validated chunk persistence, deterministic
pre-merge, bounded reconciliation, evidence validation and atomic profile freeze. All
analysis and translation traffic shares the Batch/background quota.

Exit: resume reuses valid chunks and restarts at the first invalid/missing artifact;
invalid evidence cannot enter the profile; UNKNOWN and CONFLICTING survive correctly;
range-scoped narrative facts do not leak backward; frozen profile bytes/content hash
cannot mutate; no model fact is promoted to series canon; provider fixtures and at
least one real-provider evaluation pass the accepted schema/reliability threshold.

### Stage 5 — global profile-aware translation

Use narrative-order whole-page envelopes, a relevant frozen-profile subset,
range-safe scene context, gap-free history and current stable OCR blocks. Revalidate
page state immediately before dispatch and deterministically re-plan a changed suffix.
Add structural split/backoff under one root budget.

Exit: page atomicity holds across normal, missing-only, ambiguous and refusal cases;
mixed-response behavior matches the accepted decision; stale profile/source/page
commits fail; envelope-policy-only changes do not invalidate compatible translations;
Manual/Auto completions during Batch are skipped safely; provider tests measure calls,
tokens, accepted blocks, malformed categories, retries, time to profile freeze and
time to first accepted page.

### Stage 6 — translation/inpaint overlap

Schedule only serial local inpaint after profile freeze while the single remote Batch
request is in flight. Re-decode source and reuse the durable mask. Preserve native
admission, leases and candidate/committed publication.

Exit: deterministic scheduler tests prove no inpaint during OCR and no concurrent
native-page ownership; cancellation and failure join cleanly; cleaned-image commits
reject stale inputs; on-device comparison shows peak memory remains within the agreed
budget and records whether overlap improves wall time. If it does not, retain the
simpler serial schedule without changing semantics.

### Stage 7 — persisted LAYOUT_PREPARE

Finalize the immutable source-image-space draw-plan DTO and compatibility fingerprint.
Publish geometry and color/style as separately invalidatable sub-results. Reader Pager
and Webtoon hydrate valid plans and keep asynchronous planning for Manual/Auto, legacy,
missing, invalid or corrupt plans. Only then redefine completion as DISPLAY_READY.

Exit: golden round trips reproduce planner geometry within an accepted tolerance;
font/platform compatibility tests define reuse boundaries; user translation edits
invalidate page layout only; color changes do not reflow; stroke-width changes do;
stale layout/hydration delivery is rejected; process restart and LRU eviction rehydrate
without invoking `TextLayoutPlanner`; Pager/Webtoon visual and bind-latency tests pass.

### Stage 8 — evaluation, default and cleanup

Run the full matrix on retained legacy and new feature paths. Compare against the
baseline and accepted budgets, enable by default only when gates pass, observe a
defined rollback window, and then remove contextual-AI-only progressive OCR/physical
PROBE ownership.

Exit: 200-page stress, process death at every phase, low-memory/thermal, CJK identity
and gender, mixed-theme lexical, malformed-provider, queue/service, Pager/Webtoon and
Manual/Auto regression suites pass; cost/reliability thresholds are accepted; rollback
is demonstrated; shared/legacy PROBE and reader behavior remain intact.

## Evidence and verification matrix

| Vision/invariant | Primary proof | Required evidence | Gate |
|---|---|---|---|
| Full OCR before paid analysis | Coordinator phase test | Provider fake observes zero calls until all pages are READY/TEXTLESS and checkpointed | 3 |
| Bounded memory | Instrumented device run | Peak Java/native/graphics, decode sample, bitmap count, thermal and 200-page trace | 3, 8 |
| Reader priority with Batch progress | Contention/device test | Reader wait distribution plus Batch starvation bound under sustained arrivals | 3, 8 |
| Origin-neutral OCR reuse | Store race/crash tests | Fresh Manual/Auto/Batch candidate succeeds; stale writer fails; display preserved | 1, 3 |
| Frozen reproducible profile | Golden/schema/resume tests | Canonical serialization/hash, evidence validity, immutable freeze, chunk reuse | 2, 4 |
| No future narrative leakage | Range-policy fixtures | Requests contain chapter-wide canon but exclude future range facts | 2, 4, 5 |
| Shared provider quota | Governor integration test | Aggregate analysis+translation trace respects provider and Batch rolling limits while reader requests win priority | 4, 5 |
| Whole-page global planning | Property/golden tests | Ordered membership, no initial page split, all pending blocks exactly once | 2, 5 |
| Bounded malformed recovery | Fault-injection provider | Taxonomy, discard/retain decision, root ledger totals, split-depth/leaf caps | 2, 5 |
| Gap-free context | Frontier integration test | Only fully accepted contiguous pages advance; partial/malformed pages never do | 2, 5 |
| User edits authoritative | Store/invalidation tests | Edits survive re-plan/resume and reject stale translation/layout commits | 1, 5, 7 |
| Translation/inpaint overlap safe | Scheduler/device traces | One provider request, one native page, correct joins, no OCR-phase inpaint | 6 |
| Persisted layout is reusable | Round-trip + reader instrumentation | Planner-call counter remains zero on valid restart/LRU Pager/Webtoon binds | 7 |
| Image-space/layout fidelity | Golden visual tests | Coordinates survive zoom/pan/orientation; accepted pixel/geometry tolerance | 7 |
| Completion means display ready | State/UI integration tests | Completion only after cleaned image, colors and valid durable layout; fallback states remain explicit | 7, 8 |
| Manual/Auto and legacy stability | Regression suite | Current latency path, leases, queue/service, refresh and old artifacts remain functional | Every stage |
| Better cost/reliability | Provider evaluation | Accepted blocks/request, tokens, calls, retries, malformed rate, first-result and final time against baseline | 5, 8 |

Evidence must be stored with device, OS, app commit, provider/model, configuration,
fixture/corpus identity and timestamp. “Tests passed” without those identifiers is not
sufficient for a measured gate.

## Missing decisions

### Blocking before Stage 1 coding

1. Exact versioned schemas and canonical serialization for `ChapterRunRecord`,
   `PageOcrCheckpoint`, `AnalysisChunkResult`, `ChapterTranslationProfile`,
   `EnvelopePlan`, persisted layout geometry, and color/style preparation.
2. Complete durable phase-transition and recovery table, including sidecar garbage
   collection, forward/backward compatibility and corrupt-artifact behavior.
3. Exact `checkpointOcr` compare-and-swap inputs and result: close versus rebase,
   snapshot ownership, candidate lifecycle, lease ordering, and failure reporting.
4. Exact semantic fingerprint field/normalization specifications and invalidation
   matrix, including which profile changes invalidate already completed machine text.
5. Persisted-layout compatibility boundary: font asset/version, typeface/style,
   measurement flags, Android shaping/platform key, geometry encoding and tolerance.
6. Feature-flag granularity, default states, migration behavior and rollback window.

### Blocking before provider stages

7. Analysis/profile request and response schemas, maximum field lengths, evidence
   syntax, analyzer capability requirements, and whether analyzer and translator may
   use different provider/models.
8. Complete-page handling for a mixed malformed response: publish safe pages now or
   retain candidates until root recovery completes. This affects context and cost.
9. Typed provider error contract for analysis, retryability classes and refusal/content
   classification.
10. Authority inputs available in the first release: user canon, series canon, or
    chapter-only profile; how absent authorities fingerprint and display.
11. Small-chapter/no-translatable-work policy and whether provider analysis is skipped.

### Blocking before product default, but tunable during flagged evaluation

12. Provider/model/credential RPM and TPM table and the rolling-window implementation
    of the shared 15-RPM Batch sublimit.
13. Analysis and translation token/output/page/block/structural budgets, overlap,
    profile subset/history budgets, retry count, split depth and leaf caps.
14. Native reader-priority and anti-starvation thresholds, queue capacity, prefetch,
    checkpoint flush policy and artifact size limits.
15. Accepted quantitative gates for memory, thermal behavior, reader latency, provider
    malformed rate, quality, cost, first-result time and final completion time.
16. Small-chapter bypass threshold, layout cache size, layout disk/parse budget and
    compatible Android/font platform matrix.
17. Whether non-contextual AI Batch adopts OCR preflight and the separate settings
    snapshot/add-versus-replace UX decisions. These must not be smuggled into this
    contextual-AI delivery.

## Control mechanism for proving conformance

Maintain a live traceability ledger with columns: requirement ID, exact normative
sentence/source, work package, code owner/path, test/evidence ID, accepted result,
reviewer and status. Require each change to cite its requirement and tests. At every
stage exit, a reviewer checks the diff against the ledger, reruns the stage gate, and
records deviations as explicit decisions. This is the systematic mechanism that turns
the vision into auditable implementation rather than relying on prose familiarity.

The plan becomes fully executable when Stage 0 artifacts exist and blocking decisions
are accepted. Until then, the correct readiness classification is: **architecture
ready; implementation plan incomplete; Stage 0 authorized for specification only**.
