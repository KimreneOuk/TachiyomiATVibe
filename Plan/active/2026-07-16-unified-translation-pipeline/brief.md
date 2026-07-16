# Brief - Unified Translation Pipeline Recovery

Status: Planning complete; implementation not started.

## Objective

Replace the divergent chapter pre-translation pipeline with shared page-stage
behavior used by manual, auto, and pre-translation, while preserving a faster
chapter schedule: detect/OCR every page first, start one page-context Pass-1
translation as each OCR result becomes durable, and defer all inpainting until
the chapter OCR barrier. Add a separate, manager-owned semantic review action
that a user may run against any durable manual, auto, or batch draft, including
partially translated and legacy chapters.

## Current symptom or desired behavior

Current pre-translation can finish OCR and inpainting without committing any AI
translation, presents coordinator acceptance as provider completion, and can be
cancelled when the reader opens. Native work is currently ordered as
`OCR(page N) -> inpaint(page N)` instead of completing the chapter OCR sweep
before inpainting.

Desired behavior:

```text
OCR page N -> persist OCR/mask -> release bitmap -> queue page Pass 1
OCR page N+1 ... while one remote provider lane translates prior pages
ALL EXPECTED OCR TERMINAL
serial inpaint sweep while remote translation may continue
render a page when its draft and cleaned image are ready
ALL PASS 1 TERMINAL
authoritative batch reconciliation and summary

optional user action:
preflight -> explicit contextual reviewer -> strict K/C/U results
-> text-only overlay refresh -> visible revision report
```

## Scope boundary

In scope:

- Android manual, auto, and manga-screen chapter translation scheduling.
- OCR, translation, inpaint, render, revision, progress, cancellation, resume,
  memory-pressure, and reader-observation seams needed for one shared workflow.
- Focused chapter-row and reader-sheet actions, immutable UI/backend revision
  contracts, preflight confirmation, progress, and last-run result visibility.
- Gemini, OpenRouter, DeepSeek, LM Studio, Google, DeepL, and ML Kit adapters.
- Focused plain-JVM tests, current architecture documentation, and device gates.

Out of scope:

- Companion server code.
- OCR model-quality changes, inpainting algorithm changes, speaker inference,
  chain-of-thought output, or broad prompt experimentation.
- UI redesign beyond the focused revision action/sheets, manual block editor,
  glossary editor, or persisted page-JSON migration.
- External brainstorming artifacts under the separate translation-quality task.

## Acceptance criteria

- Every expected chapter page reaches terminal OCR state before the first batch
  inpaint starts.
- A remote provider request can start after its page OCR commit and before the
  chapter OCR barrier; provider slowness cannot block the OCR sweep.
- Normal Pass 1 performs one ordered page-context request per page. Only an
  oversized page is split, and the page is terminal only after all subrequests
  are accounted for.
- Pass-1 input preserves current block sorting and adds compact `P<n>` headers
  only for reliable panel groups; panel membership is never called a speaker.
- Queued translation work contains no `Bitmap`, native handle, page stream, or
  native permit. Peak native/bitmap concurrency remains one.
- Manual, auto, and batch use the same OCR, Pass-1 merge, inpaint publication,
  render commit, validation/retry, and self-reporting operations.
- All four contextual AI providers return structured Pass-1 results. Google,
  DeepL, and ML Kit explicitly support validation/retry only and never act as a
  semantic reviewer.
- Gemini, OpenRouter, DeepSeek, and LM Studio all satisfy the same strict K/C/U
  review-adapter contract before standalone revision is enabled.
- A user can preflight and start `FLAGGED` or `ALL_TRANSLATED` review for any
  chapter with durable source/draft pairs, independent of how those drafts were
  produced or whether the whole chapter is translated.
- Semantic review uses an explicitly displayed contextual AI reviewer and plain
  structured text completion, never tool calling or a silent provider switch.
- Strict review output accounts for every target exactly once as keep (`K`),
  correct (`C`), or unresolved (`U`); invalid/missing/stale output retains the
  draft and cannot report success.
- Batch completion does not wait for optional semantic review. Accepted review
  patches refresh the overlay without decoding or rewriting the cleaned image.
- The chapter-row translation menu and reader translation sheet expose the same
  manager action. Confirmation shows scope, reviewer/model, language pair,
  translated-page coverage, target counts, exclusions, and estimated requests.
- Progress and a bounded last-run report show kept, corrected, unresolved,
  rejected/stale, over-budget, and user-edited outcomes, including before/after
  text for accepted corrections.
- Reader entry, display-disable, chapter switching, pause/finish, app
  backgrounding, and screen-off do not cancel a manager-owned chapter batch or
  standalone revision.
- Cancellation, deletion, timeout, critical memory, and newer generations reject
  late writes without recreating files or overwriting newer state.
- Progress reflects real provider starts/completions and exact terminal counts.
- Focused tests, full `:app:testStandardDebugUnitTest`, and `git diff --check`
  pass; low-memory device gates are recorded before completion.

## Constraints

- Target devices have at least 6 GB RAM; the 6 GB / 20-30% heap case is the
  design point.
- One native admission at a time through `NativeRunQuarantine` and one provider
  request at a time.
- Strict no-fallback: no source-text rendering, positional response guesses,
  silent engine downgrade, or false success.
- `ChapterTranslationStore` remains the state owner. Concurrent stage commits
  must merge atomically and reject stale generations, changed target blocks,
  user edits, and defunct stores.
- Existing page JSON and chapter-summary compatibility are preserved.
- New chapter metadata/report fields or sidecars use defaults and atomic writes;
  legacy chapters require explicit language confirmation when metadata is absent.
- Reader-visible state remains original until cleaned-image, translation, and
  render readiness all hold.
- Do not modify unrelated dirty, deleted, or untracked files.

## Stop conditions

- Stop before editing production code if baseline focused tests cannot run after
  configuring `JAVA_HOME`, or if current failures are unrelated to this task.
- Stop if the OCR-only stage cannot persist every inpaint prerequisite required
  by a later re-decode without retaining live native state.
- Stop if stage-specific atomic merges cannot preserve edit-wins and generation
  invalidation while translation and inpaint run concurrently.
- Stop if measured peak bitmap/native concurrency exceeds the current single-page
  bound or if the OCR queue retains page image bytes.
- Stop and revise the design if provider cost/request limits make one request per
  page unacceptable; do not silently restore multi-page streaming.
- Stop if a cold/partial chapter cannot derive review eligibility from durable
  text state without decoding images or retaining Android/native objects.
- Stop if review preflight and start cannot be revalidated atomically, or if a
  second review/provider request can bypass the one-provider-lane invariant.
- Stop if strict K/C/U parsing cannot account for every requested ID without
  positional fallback or treating omissions as `K`.

## Source of truth

The verified investigation is in `investigation.md`; the selected approach is in
`design.md`; implementation gates are in `checkpoints.md`; prepared work packages
are in `tasks.md`.
