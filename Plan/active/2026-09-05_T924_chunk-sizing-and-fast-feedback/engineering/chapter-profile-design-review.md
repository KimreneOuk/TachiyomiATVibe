# Chapter-profile Batch design review

Date: 2026-09-05 · reviewed against HEAD `adbe643` · architecture only

## Final verdict after re-review

The revised design resolves the five review findings at the architecture-contract
level and is **ready for staged implementation planning**. It now requires an
atomic OCR checkpoint/rebase before lease release, failure-class-based partial
retention, one bounded root split budget, semantic content fingerprints with
user-edit preservation, and native reader-priority admission. Exact store APIs,
scheduler thresholds, and provider limits remain implementation/design-detail
decisions and measurement gates, not contradictions in the architecture.

One LOW editorial ambiguity remains: malformed-response policy item 4 says to
preserve independently complete pages without repeating that this applies only
to the `MISSING_ONLY` class (`design/chapter-profile-batch-design.md:407-422`).
Item 2 clearly makes `AMBIGUOUS_PROTOCOL` discard all parent values, so item 4
should be read—and ideally worded—as “subject to item 2.” This does not block
staged planning.

Resolution check:

- Finding 1 resolved by the mandatory atomic checkpoint/rebase transaction
  (`design/chapter-profile-batch-design.md:252-261,473-478`).
- Finding 2 resolved by the `MISSING_ONLY` / `AMBIGUOUS_PROTOCOL` /
  `TERMINAL_REFUSAL/CONTENT` taxonomy (`:403-417`).
- Finding 3 resolved by one shared 8-attempt root budget, depth 3, four leaves,
  and the explicit commit-level page-atomicity definition (`:418-437`).
- Finding 4 resolved by canonical OCR corpus, profile-input, profile-content,
  and separate request provenance fingerprints (`:190-208`).
- Finding 5 resolved by one-page Batch admission, interactive checks/yielding,
  and a bounded anti-starvation rule (`:237-243`).

## Findings

### 1. HIGH — likely — design defect: lease release is not an OCR phase commit

**CONTRADICTION:** The design says to persist OCR, release the page lease, and
later reacquire a fresh lease/write identity
(`design/chapter-profile-batch-design.md:202-232`). In the current store, OCR is
persisted through the live page candidate; `mergeOcr` publishes the updated page
through the candidate bridge (`ChapterTranslationStore.kt:826-928`). Releasing a
lease only removes the lease record and wakes waiters; it neither promotes nor
rebases the candidate (`store/PageStageLeaseTable.kt:139-149`). Candidate
promotion is a complete-page/display transaction, while an origin change cancels
the old candidate and opens a different one
(`ChapterTranslationStore.kt:1712-1749`;
`artifact/ChapterArtifactStore.kt:448-565,750-780`).

Consequences include 200 active BATCH candidates after preflight, ownership
transfer races when Manual/Auto writes a page, and unclear teardown semantics.
The current batch identity is intentionally invalid after release/reacquire:
guarded writes require the exact lease token, page version, candidate generation,
dependency fingerprint, and artifact version
(`pipeline/batch/BatchWriteGate.kt:85-112`;
`ChapterTranslationStore.kt:630-664`).

**Required design decision:** define an atomic, origin-neutral OCR checkpoint or
an explicit candidate rebase/transfer transaction before releasing the OCR lease.
The operation must either durably preserve the OCR/mask and return a fresh
snapshot, or fail while the Batch still owns the lease. Merely removing
`batchWriteIdentities[pageKey]` and releasing is insufficient.

**Evidence to confirm/refute:** an artifact-store test that OCR-merges under a
BATCH lease, checkpoints/releases, lets MANUAL acquire and fail/cancel, then
reacquires BATCH after process reconstruction and proves the exact OCR blocks,
mask, fingerprints, and committed display all survive.

### 2. HIGH — likely — design limitation: mixed-response retention is not safe for every structural failure

**VERIFIED:** The parser accepts syntactically valid known IDs while separately
recording malformed lines, unknown IDs, duplicates, and missing IDs
(`translator/contextual/ContextualResponseParser.kt:104-188`). The retry
accumulator retains accepted values even when the response has a global protocol
issue (`translator/retry/AiTranslationRetryController.kt:186-215,651-738`). The
current pipeline nevertheless exposes only a complete natural page prefix for
durable promotion/context (`AiTranslationRetryController.kt:767-797,853-879`;
`pipeline/batch/BatchLaneWorkers.kt:478-503`).

The proposed policy broadens this to retain any independently complete page and
split only the remainder (`design/chapter-profile-batch-design.md:374-390`). A
page with all expected IDs is not necessarily trustworthy when the same response
contains unknown IDs, conflicting duplicates, or malformed framing: those errors
can indicate shifted page/block association even though some IDs parse.

**Required design decision:** classify retention by failure type. A conservative
initial rule is to retain a whole page only from a response whose accepted IDs are
unambiguous and whose only defect is missing required IDs elsewhere. Discard or
revalidate parent-attempt values when there are unknown IDs, conflicting
duplicates, protocol-version errors, or malformed framing. Later complete pages
must remain candidates and must not advance `BatchContextFrontier` across an
earlier gap.

**Evidence to confirm/refute:** adversarial parser/controller tests where valid
IDs coexist with shifted text, unknown IDs, duplicate conflicts, truncation, and
malformed lines; prove no ambiguous parent value commits or enters rolling
history.

### 3. HIGH — possible — design defect: split/backoff lacks a bounded root attempt contract

**VERIFIED:** Today one frozen envelope owns one shared `RequestRetryBudget`, and
the retry controller performs bounded whole-envelope and missing-ID requests
(`translator/retry/AiTranslationRetryController.kt:219-246,264-276,432-509`). A
new child call normally creates a new budget unless the caller explicitly passes
the parent budget (`:230-245`).

The design says children share a bounded attempt tree but does not define maximum
tree depth, total child nodes/requests, or whether the budget spans parent plus
all descendants (`design/chapter-profile-batch-design.md:381-388`). Independent
budgets can multiply requests after repeated bisection and defeat the 15-RPM/cost
goal.

There is also an invariant ambiguity: global planning “never splits a page,” yet
the proposed single-page fallback retains the existing missing-block request,
which sends a subset of that page's IDs (`AiTranslationRetryController.kt:130-152,
455-462`). This is compatible only if page atomicity means **membership and
commit**, not that every retry request contains the whole page.

**Required design decision:** specify one root attempt ledger/budget for the
original envelope and all descendants, plus maximum split depth and terminal
single-page behavior. Explicitly define page atomicity across initial envelopes,
retry requests, candidates, and commits.

**Evidence to confirm/refute:** exhaustive failure-tree tests showing the maximum
provider calls for an N-page envelope and proving that a page is committed once,
under one profile/context snapshot, even when missing-ID repair is used.

### 4. HIGH — likely — design limitation: profile identity is unstable if based on artifact pointers

**STRONG INFERENCE:** The design fingerprints a profile from ordered “OCR artifact
IDs” and proposes adding a stable OCR artifact identity
(`design/chapter-profile-batch-design.md:122-129,186-193`). Current candidate
generation IDs and page versions are ownership/transaction identities: they
change when a candidate is opened, cancelled, promoted, or replaced
(`artifact/ChapterArtifactStore.kt:668-747,750-780`). They are therefore unsuitable
as semantic OCR corpus identity. Reader takeover during preflight could invalidate
an unchanged corpus, while pointer reuse alone cannot prove unchanged OCR text.

Current translation reuse has one page-level `translationFingerprint` plus a
separate mutable glossary-version gate (`model/PageTranslation.kt:53-76`;
`model/PageWorkPlanner.kt:290-309`). It has no current field that distinguishes a
frozen profile content hash from envelope policy or selectively invalidates
machine output while preserving user-edited blocks.

**Required design decision:** fingerprint canonical OCR content from ordered page
identity, source fingerprint, OCR/detection configuration, stable block identity,
source text, and geometry/mask revision—not candidate pointers. Define separately:
profile input fingerprint, profile content fingerprint, translation request
fingerprint, and run/envelope policy fingerprint. State explicitly that profile
changes invalidate only affected machine translations; user-edited block values
and the last committed display remain authoritative.

**Evidence to confirm/refute:** deterministic fingerprint tests across candidate
open/cancel/promote/reacquire with identical OCR, and invalidation tests for source,
OCR config, analyzer prompt/model, user/series canon, profile content, envelope
policy, machine translation, and user-edited blocks.

### 5. MEDIUM — likely — design limitation: provider priority does not guarantee reader responsiveness during OCR sprint

**VERIFIED:** Native work is globally serialized by `NativeRunQuarantine`'s
single mutex, including timed-out work until its real exit
(`scheduling/NativeRunQuarantine.kt:43-67,84-106`). Detector and per-ROI OCR are
also held under the recognition engine's native guard
(`recognition/RoiPageRecognitionEngine.kt:267-296`). A reader request for a
BATCH-owned page attaches rather than preempting it
(`TranslationPipeline.kt:458-490,773-787`). The cloud governor's INTERACTIVE
priority does not prioritize this native mutex.

Serial one-page OCR is memory-safe in shape, but a continuous 200-page sprint does
not by itself guarantee useful Manual/Auto latency. The current design lists a
fairness/yield policy only as a runtime question
(`design/chapter-profile-batch-design.md:613-616`), while claiming Manual/Auto
remain usable (`:420-435`).

**Required design decision:** make reader responsiveness a scheduler contract:
admit at most one Batch native page at a time, check cancellation/interactive
demand between pages, and define bounded yielding or reader-first admission.
Keep inpainting out of preflight; it adds another pass through the same serialized
native resources and is not analysis input.

**Evidence to confirm/refute:** device tests on supported 6-GB hardware measuring
reader tap/auto latency during a 200-page OCR sprint, including a long/timed-out
native call, cancellation, thermal throttling, and repeated reader arrivals.

## Required design edits before approval

1. Add the OCR checkpoint/rebase transaction and its crash/ownership semantics.
2. Add a structural-failure taxonomy that controls whether parent results may be retained.
3. Define the root split-tree request budget and the precise page-atomicity rule.
4. Replace pointer-based OCR identity with canonical content fingerprints and a selective invalidation matrix.
5. Promote native reader fairness from a measurement note to an admission requirement, leaving its threshold tunable by device testing.
