# T924 plan-completeness and anti-drift audit

Date: 2026-09-05  
Baseline claimed by the package: `adbe643`  
Scope: documentation and source-traceability audit only; no implementation or
runtime tests were performed.

## Verdict

The package is **architecturally coherent and complete enough to authorize a
separate Stage 1 implementation plan**, but it is **not yet a complete,
implementation-ready specification** and does not yet provide a systematic proof
that resulting code matches the Director's vision.

The strongest documents are `chapter-profile-batch-design.md`, which covers the
requested architecture dimensions and explicitly separates verified facts,
recommendations, assumptions and device questions (`:12-75,77-121,578-701`), and
`final-target-migration.md`, which supplies a safe eight-stage migration and names
the mechanisms to retain (`:149-247`). The package also has unusually good
source-line evidence for current behavior. Its remaining weakness is control of
future delivery: there is no single authoritative reading order for the final
design, no requirement-to-code-to-test traceability matrix, no precise stage exit
criteria, and several contracts needed by Stage 1 remain open.

## Evidence classification

- **VERIFIED:** The current-code investigation is source-cited and pinned to HEAD
  `adbe643` (`../README.md:19-28`; `source-reverification.md:3-13`).
- **VERIFIED:** The requested major subjects are present: feasibility and impact
  map (`../design/chapter-profile-batch-design.md:12-75`), durable state machine
  (`:77-121`), artifacts and fingerprints (`:123-229`), tests and measurements
  (`:578-626`), and evidence/open decisions (`:628-701`).
- **VERIFIED:** A staged, feature-flagged migration with retained legacy behavior
  and rollback window exists (`../design/final-target-migration.md:149-208`).
- **STRONG INFERENCE:** A new engineer can understand the intended architecture by
  reading the right files, but the repository does not currently tell that engineer
  unambiguously which files are authoritative and which historical requirements to
  ignore.
- **VERIFIED:** No document defines a complete conformance/acceptance matrix or
  stage-by-stage measurable exit checklist. Test topics exist, but proof obligations
  and pass thresholds do not.

## Findings

### 1. HIGH — likely — design-process defect: the published reading order is stale

`HANDOFF_PROMPT.md` is the only document with an explicit reading order
(`:13-24`), but it directs readers to the historical `chunk-sizing-options.md` and
then declares the old block-cap/min-fill/Fast-small-first direction established
(`:49-70`). The current README says that direction is superseded by the
chapter-profile design and final reconciliation (`README.md:19-28`), while the
final migration explicitly retires token-overflow tuning and the small-first/Fast
idea for contextual AI Batch (`design/final-target-migration.md:233-247`).

This creates a realistic drift path: a future implementer following the only
ordered guide can optimize the obsolete progressive planner before encountering
the replacement architecture.

**Required documentation decision:** Put one authoritative reading order in the
README and mark `HANDOFF_PROMPT.md` historical or update it. Recommended order:

1. README contract/status/open decisions;
2. `profile-preflight-requirements.md` as the product requirement baseline;
3. `chapter-profile-batch-design.md` as the primary architecture;
4. `final-target-migration.md` as delivery order and persisted-layout addendum;
5. T923 investigation and T924 engineering reports as evidence;
6. superseded design records only for decision history.

**Evidence to confirm:** A clean-room reviewer follows only the README and can
identify the active target, excluded paths, unresolved decisions and first delivery
stage without consulting conversation history.

### 2. HIGH — certain — design limitation: there is no requirements traceability matrix

The profile-preflight contract lists the desired behavior in prose
(`design/profile-preflight-requirements.md:6-36`) and demands a testing matrix and
evidence classifications (`:44-49`). The architecture provides a broad test list
(`design/chapter-profile-batch-design.md:578-617`), but requirements have no stable
IDs and tests are not mapped to artifacts, components, implementation stages or
specific test suites. The final migration likewise names work per stage without
mapping each stage back to product requirements (`design/final-target-migration.md:149-208`).

Without this mapping, code review cannot reliably answer whether unknown gender,
future-plot containment, fragmented resume, malformed-output backoff, 15-RPM
sharing, reader priority and sparse migration were all implemented and proved.

**Required planning artifact:** Add a live traceability table with stable IDs and
columns for requirement, authority/source, affected contract/component, delivery
stage, automated test(s), device/provider validation, and status. Each pull request
should cite the IDs it implements and attach evidence.

**Evidence to confirm:** Every MUST/invariant in the requirement contract and README
has at least one owning stage and one verification route; no test exists without a
corresponding requirement or risk.

### 3. HIGH — certain — implementation blocker: foundational contracts are still open

The README leaves the exact profile/analysis schemas, provider relationship,
structural budgets, malformed mixed-response retention, invalidation, OCR checkpoint
transaction, native fairness and persisted layout DTO open (`README.md:30-51`). The
main architecture repeats the exact profile schema and OCR checkpoint transaction as
decisions still required (`design/chapter-profile-batch-design.md:667-683`). Yet the
final migration says discovery is complete enough to begin Stage 1 planning
(`design/final-target-migration.md:286-291`), and Stage 1 itself requires those
versioned contracts and transactions (`:154-160`).

There is no contradiction if the next action is **implementation planning**, but
there would be a defect if coding Stage 1 began by inventing these contracts inside
production changes. The plan is therefore not “fully written in details.”

**Required planning artifacts before production coding:** Exact serialized DTOs and
compatibility rules; transaction pre/postconditions and failure atomicity for OCR
checkpoint/rebase; fingerprint canonicalization; profile authority/conflict enums;
phase transition table; and upgrade/downgrade behavior for unknown schema versions.

**Evidence to confirm:** Pure serialization, deterministic fingerprint and
fault-injected transaction tests can be written from the contracts without reading
their implementation.

### 4. HIGH — certain — verification gap: test topics lack pass/fail oracles and stage gates

The test plan is broad and correctly includes the 200-page case, malformed output,
gender ambiguity, scene scope, fragmented resume, concurrency, process death and
sparse migration (`design/chapter-profile-batch-design.md:580-617`). Device metrics
are also named (`:619-626`). However, the plan does not state:

- the exact expected durable state after each injected interruption;
- maximum retained decoded pages/bitmaps and acceptable peak-memory regression;
- reader latency/starvation limits during OCR;
- structural-validity and retry/backoff pass thresholds per provider/model;
- quota/accounting assertions for shared 15 RPM and TPM;
- quality evaluation protocol or baseline for terminology, gender and semantic
  improvements;
- the required automated test class/module, fixture format, or CI/device lane;
- go/no-go thresholds for enabling each feature flag or removing PROBE.

The design explicitly labels the key quality and performance gains unmeasured
(`design/chapter-profile-batch-design.md:657-665,685-692`), and final migration
rightly defers constants to measurement (`design/final-target-migration.md:249-265`).
That honesty is good, but measurements without predetermined decision rules allow
post-hoc acceptance.

**Required planning artifact:** A stage exit-gate table. Each stage needs invariant
checks, deterministic automated suites, lifecycle fault cases, device/provider
matrix, numeric budget or baseline-comparison rule, evidence location and rollback
condition.

**Evidence to confirm:** A reviewer can reject or accept a stage using recorded
results without subjective interpretation.

### 5. MEDIUM — likely — anti-drift limitation: source citations have no refresh policy

The package pins findings to HEAD `adbe643` (`README.md:19-25`) and carries useful
file:line citations. It does not specify what happens when implementation starts
from a later HEAD, line numbers move, APIs change, or the legacy path evolves during
the staged rollout. `final-target-migration.md` requires each stage to compile and
preserve behavior (`:149-152`) but does not require source re-verification or an
architecture-conformance review.

**Required process:** At stage kickoff, record the implementation base commit and
reverify load-bearing source assumptions. At stage completion, update the impact
map/citations, run the traceability matrix, and require an independent conformance
review. Treat stale line citations as navigation hints after code moves; bind proof
to tests and semantic symbols.

**Evidence to confirm:** Each stage report records base/finish commits, changed
assumptions, traceability status, test evidence and reviewer verdict.

### 6. MEDIUM — likely — scope-control ambiguity around persisted layout

The core requirement contract ends at native/render/commit and does not introduce
persisted layout (`design/profile-preflight-requirements.md:6-10`), while the README
now includes a later persisted `LAYOUT_PREPARE` stage in proposed scope
(`README.md:65-67`). The final migration correctly separates it as a second contract
migration and delays it until the OCR/profile pipeline is stable
(`design/final-target-migration.md:7-11,41-48,195-202`). That is a safe technical
decision, but its product requirement and acceptance rationale are not located in
the active requirements contract.

**Required documentation decision:** Either add persisted layout as a separately
identified accepted requirement with its own success criteria, or move it to a
linked follow-up task. Do not let it silently expand the definition of “T924 done.”

**Evidence to confirm:** The traceability matrix identifies whether layout is part
of T924 completion or a separately authorized milestone.

### 7. MEDIUM — possible — completeness gap: no explicit end-to-end reference scenarios

The architecture explains phases and provides isolated edge tests, but no single
normative scenario follows one chapter through exact artifact transitions, leases,
profile freeze, envelope execution, display promotion, process restart and reader
reuse. Fragmented resume is discussed in prior material, and the state machine is
clear, but implementers still must mentally compose behavior across several
sections.

**Required planning artifact:** Add three executable narrative specifications:
normal 200-page chapter; fragmented/resumed chapter (1,2,4 complete); and concurrent
reader plus malformed provider response. At every phase list durable artifacts,
lease owner, visible display, frontier position, allowable request count and restart
result.

**Evidence to confirm:** Integration tests use the same scenarios and assert the
documented checkpoints.

## What is already sufficiently specified

- The architectural direction and scope boundary: contextual AI Batch changes;
  Manual/Auto and legacy paths remain (`design/chapter-profile-batch-design.md:6-10`).
- The high-level phase sequence and durable resume principle (`:77-121`).
- The retained safety mechanisms and affected components (`:59-75`;
  `design/final-target-migration.md:210-247`).
- The rationale for serial, one-page OCR and deferring inpaint during preflight
  (`design/final-target-migration.md:168-193,281-284`).
- Frozen-profile authority, semantic fingerprint direction, whole-page planning,
  strict response handling, root retry budgeting and gap-free frontier intent.
- The major lifecycle, storage, reader coexistence and provider risks requiring
  tests.

## Systematic implementation-control recommendation

Use five linked artifacts as the control system:

1. **Authoritative README index** — reading order, active/superseded status, scope,
   decisions, stage status and current evidence baseline.
2. **Normative requirements catalog** — stable IDs and unambiguous MUST/SHOULD
   statements, including invariants and exclusions.
3. **Contract specifications** — serialized schemas, state-transition tables,
   transactions, fingerprints, request/response protocols and invalidation matrix.
4. **Traceability and verification matrix** — requirement ID → source assumption →
   component/change → automated test → device/provider result → status.
5. **Stage evidence reports** — base/finish commit, implemented IDs, deviations,
   tests, measurements, reviewer verdict and rollback state.

The feature-flag stages in `final-target-migration.md:149-208` are a sound delivery
skeleton. Adding these artifacts converts that skeleton into a repeatable process
that can prove conformance and catch architectural drift.

## Recommended next action

Do not reopen the selected orchestration direction. Before implementation, perform
one documentation-hardening pass: establish the authoritative README reading order,
assign requirement IDs, resolve the Stage 1 contracts, create the traceability and
stage-gate matrices, and define the three end-to-end reference scenarios. After
that, an implementer can build Stage 1 without silently deciding product semantics,
and a reviewer can determine objectively whether the code matches the vision.
