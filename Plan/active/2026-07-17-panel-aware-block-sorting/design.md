# Design - Panel-Aware Block Sorting (Pass-1 reading-order context)

Status: Design complete; implementation not started.
Date: 2026-07-17
Parent plan: `Plan/active/2026-07-16-unified-translation-pipeline/`
Supersedes: `Plan/active/2026-07-17-deterministic-revision-flagging/` (revision
flagging work — abandoned; the 90%-good pipeline makes downstream flagging not
worth the cost/risk).

## Problem

Pass-1 translation request blocks are ordered by a coordinate-only heuristic
(`TranslationBlockSorter`: top-to-bottom rows, RTL/LTR within row). Panel
detection (`OnnxPanelDetector` + `PanelAssignment`) runs on every page, tags
each block with `panelIndex` (reading-order index for OWNED blocks, ≥80%
containment) and `panelAssignment`, and **persists that data** — but the sorter
**ignores it.** Blocks from different panels interleave by row, breaking the
natural scene/speaker flow the AI needs to translate dialogue coherently.

The user's concrete symptom: manga with ongoing cross-panel conversation (e.g.
male speaking to female across panels) loses context when block order scrambles
panels. The 90%-good pipeline gets worse on exactly the dialogue-heavy chapters
that matter.

## Objective

Make `TranslationBlockSorter.sort` **panel-aware**: group blocks by their
`panelIndex` in reading order, then sort within each panel by the existing
coordinate heuristic. Blocks with no reliable panel (`panelAssignment != OWNED`
or null `panelIndex`) fall into a single trailing group sorted by the existing
coordinate heuristic. No prompt change, no AI cost, no new sidecar. The AI
receives blocks in panel-reading-order so conversational/scene flow is
preserved.

This is a **pure upstream fix**: better Pass-1 input → better Pass-1 output →
fewer revision-worthy errors at the source. It recovers value from detection
data that is already computed and paid for.

## Decisions (locked)

1. **Sort only.** No prompt change, no `P<n>` headers, no speaker hints. (Headers
   are Phase 2, deferred to device-gate validation of the sort's effect.)
2. **No fallback, no legacy support.** Clean break. Blocks without panel data
   (`panelAssignment != OWNED` or null `panelIndex`) go into ONE trailing group
   sorted by the existing coordinate heuristic. No compatibility shim, no
   "behave like before" branch, no migration of old data.
3. **Single insertion point:** extend `TranslationBlockSorter.sort` itself, since
   it is the existing chokepoint already called at both pre-request sites
   (TranslationPipeline.kt:1381 batch path, :2108 single-page path). Both call
   sites and the persisted store order stay consistent automatically because
   the sort result is what gets stored (verified pattern).
4. **Fingerprint-safe** — verified: `stableFingerprint()` hashes field values
   only, no index/position. A pure reorder is fingerprint-neutral.
5. **Overlay/render-safe** — verified: the reader overlay and inpainter are
   coordinate-based (`TextLayoutPlanner.plan` re-sorts by score; overlay draws
   by `block.x/y`). List order has no display impact.

## Component change: `TranslationBlockSorter`

File: `app/src/main/java/eu/kanade/translation/util/TranslationBlockSorter.kt`

Current behavior (lines 7-50): coordinate-only TTB row-grouper, RTL/LTR within
row by `fromLang`. No panel awareness.

New behavior:
```
sort(blocks, fromLang):
  1. Partition blocks into:
     - panelGroups: Map<panelIndex, MutableList<block>>  // only OWNED, non-null panelIndex
     - unassigned: MutableList<block>                     // everything else
  2. For each panelIndex in ASCENDING order:
       append coordinateSort(panelGroup, fromLang) to result
  3. Append coordinateSort(unassigned, fromLang) to result   // single trailing group
  4. Return result
```

`coordinateSort` = the existing row-grouper logic, extracted to a helper so it
can be applied per-group and to the unassigned tail. The grouping is the only
new logic; within-group order is unchanged from today.

### Edge cases (all resolved by the single trailing-group rule + no-fallback)

- **All blocks OWNED, contiguous panels** → panels in ascending index order,
  within-panel by coordinate. Ideal case.
- **Mix of OWNED + SPANNING/FREE_FLOATING/ORPHAN** → OWNED blocks grouped by
  panel first; non-OWNED all in the trailing group, sorted by coordinate.
- **Zero OWNED blocks (no panel data at all)** → panelGroups empty, everything
  in `unassigned` → result == coordinate-only sort == today's behavior. No
  regression, no special branch.
- **Panel detector absent/failed (all `panelAssignment="none"`)** → same as
  above; degrades to coordinate-only.
- **Sparse panelIndex (0, 1, 5 — gaps)** → ascending order handles gaps; no
  assumption of contiguity.
- **Same panelIndex on blocks far apart** (shouldn't happen — OWNED assignment
  is by max-containment panel — but if it did) → coordinate sort within group
  keeps them in sane spatial order.

## What is removed entirely

Per the user's "no fallback, no legacy support" directive and the pivot away
from revision flagging:

1. **The revision-flagging spec** (`2026-07-17-deterministic-revision-flagging/design.md`)
   is abandoned. Not implemented. Its planned changes (drop `[OK]`/`[FLAG]` tag,
   density heuristic, character list, sidecar, manual trigger, `reviewerDecidedAt`
   field) are **all dropped.** The existing AI tag stays as-is.

2. **No new sidecar.** No character list store, no flagging pass, no version
   field. Nothing added to the storage layer.

3. **No prompt change.** `pass1SystemPrompt` is untouched. No `P<n>` headers, no
   speaker hint. The `[OK]`/`[FLAG]` tag instruction and few-shot examples
   remain exactly as today. Zero risk to the 90%-good translation quality from
   prompt edits.

4. **No new admission guards / no fingerprint race.** The sort runs at the same
   point the existing sort runs (pre-request, before the block list is captured
   into `TargetLocation`/`BlockRef` indices and before it is persisted). The
   request-time and commit-time index already see the same sorted list today;
   this change only alters *which* sorted order, not *when* sorting happens. No
   new concurrency surface.

5. **No new tests for the sort beyond extending `TranslationBlockSorterTest`**
   — the sorter is the only behavior change. (The audit noted `PanelAssignment`
   has no tests; adding those is optional foundation work, listed under
   "Optional foundation" below, not required for this change to be safe — the
   sort treats panel data as opaque input and degrades cleanly when absent.)

## What is changed

Exactly one production file:
- `app/src/main/java/eu/kanade/translation/util/TranslationBlockSorter.kt` —
  add panel-aware grouping, extract the existing coordinate sort into a reusable
  per-group helper.

Exactly one test file (extend, not new):
- `app/src/test/java/eu/kanade/translation/util/TranslationBlockSorterTest.kt`
  (or the existing test location) — add cases for panel grouping, mixed
  OWNED/unassigned, all-unassigned (degradation parity), RTL within-panel.

No other files change. No prompt, no store, no sidecar, no UI, no contracts, no
revision path. The change is invisible to everything except the order of lines
in the Pass-1 request.

## Contracts (unchanged)

- `RevisionPlanner.isRevisionTarget`, `ALL_TRANSLATED`/`FLAGGED` scopes, K/C/U
  protocol, `RevisionMerger`/`RevisionCommitter`, `RevisionConfirmation`,
  `stableFingerprint`, `patchBlock`/`mergeTranslationLocked` — all unchanged.
  The sort is fingerprint-neutral (verified) and the request→commit index window
  already operates on the sorted list today.
- `ContextualRequestBuilder.build`, `StreamingChunkPlanner.accept` — unchanged;
  they consume `page.blocks` in list order, which is the sorted order produced
  by the sorter at the existing call sites.

## Validation

Automated:
- `:app:testStandardDebugUnitTest` green (existing 830 + extended sorter tests).
- `git diff --check` clean.

On-device (CP10-style gate, user-run):
- Dialogue-heavy chapter with cross-panel conversation: confirm block order in
  the Pass-1 request follows panel reading order; confirm translation quality on
  the ongoing-conversation case is maintained or improved vs the coordinate-only
  sort. This is the single load-bearing device check — the sort's whole purpose
  is better conversational context.
- Spot-check a chapter with no panel data (detector absent or full-bleed pages):
  confirm it degrades to today's coordinate-only order (no regression).

## Risks

- **Sort quality depends on panel data accuracy.** `PanelAssignment` is
  conservative (OWNED-only, ≥80% containment) and has no unit tests today.
  If assignment mislabels blocks, grouping is wrong → order could be *worse*
  than coordinate-only. Mitigation: the all-unassigned degradation path means
  bad panel data at worst behaves like today (no regression) for the affected
  blocks; the device gate validates real-world grouping. Adding `PanelAssignment`
  tests is listed as optional foundation below.
- **Within-panel coordinate sort may not match true reading order** for unusual
  panel layouts (L-shaped, inset). The existing row-grouper is a heuristic;
  reusing it per-panel inherits its limitations. Accepted — same heuristic as
  today, just scoped to a panel.
- **No prompt risk** — confirmed: nothing in the prompt changes. The 90%-good
  quality is not endangered by a prompt edit because there is no prompt edit.

## Optional foundation (not required for this change, not in scope)

- `PanelAssignmentTest` — the audit found zero tests for `PanelAssignment`.
  Adding them would harden the foundation the sort relies on, but is not
  required for the sort itself to be safe (the sort degrades cleanly on bad
  data). Recommend as a small follow-up if the device gate shows grouping
  problems.
- `P<n>` panel layout headers in the prompt (design.md:153-158 of the parent
  plan) — Phase 2, deferred until device data shows the sort alone is
  insufficient. Not in this spec.

## Out of scope

- Any revision-flagging work (dropped — see "What is removed entirely").
- Prompt changes, panel headers, speaker hints.
- Cross-chapter character/gender continuity.
- New sidecars or store changes.
- The unrelated CP10 device-gate defects (G1-G10 from the earlier gap analysis)
  — separate effort; the CP10 fixes A/B/C/D already shipped and verified.
