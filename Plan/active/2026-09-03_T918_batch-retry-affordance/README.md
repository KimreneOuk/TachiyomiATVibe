# Task T918 — Batch retry affordance (field defect from upgraded phone)

## Objective

A field report on the upgraded build (0.17.1-374): a batch cancelled mid-run
leaves the chapter with a stale TRANSLATING/QUEUE status and an aborted
snapshot ("Batch aborted: Batch cancelled"). Every UI affordance then dead-ends:

- Chapter indicator tap → progress drawer → aborted banner, no Retry button
  (recorded Phase-6 carry item F8).
- Indicator long-press → CANCEL, which is a no-op once the queue entry is gone.
- The only restart (selection-bar Translate) is undiscoverable.

Goal: a visible, working retry path and honest state after a cancelled batch.

## Scope

1. **Sheet retry button**: terminal/aborted progress sheet gets a Retry action
   wired to `translateChapter` (resume gate reuses completed pages).
2. **State reconciliation**: when a batch job is cancelled with no queue entry
   (the `ChapterTranslator` CancellationException branch), the persisted
   translation status must land in a state whose indicator re-offers START
   (NOT_TRANSLATED) — same as the explicit-cancel branch already does.
3. **Tests**: sheet-level (retry visible only when terminal + callback present)
   and model-level (cancel leaves chapter restartable; restart reuses completed
   pages). Full sweep green.

Out of scope: TranslatingIndicator dead dropdown-menu code (note only), pause
semantics redesign, indicator routing table rewrite, i18n translations of new
strings (English base only; existing carry item).

## Constraints (binding)

- Branch `fix/batch-retry-affordance` off `main`; specific-path commits; never push.
- No Robolectric; no sleeps/polling in tests; fakes only at sanctioned seams.
- Do not modify `TextLayoutPlanner.kt`.
- `--rerun` mandatory on Gradle test runs; verify from XML files, never the banner.
- Reader stability + normal manga must not regress; Android 8.0+; bounded memory.
- P5 truth rules for any new copy/a11y (truth-named, no optimism).

## Entry points (discovered)

- `ChapterTranslator.kt:722-729` — cancellation catch: aborts tracker with
  "Batch cancelled" only when the queue entry is gone; leaves persisted status stale.
- `MangaScreenModel.kt:900-968` — action routing; the Undo retry path
  (`translateChapter`, line 956-961) is the wiring template for Retry.
- `MangaScreenModel.kt:569+` — `observeTranslationProgress`; `:691-710` — item
  state mapping (`queuedTranslation`/`translationRequest` gates).
- `ChapterTranslationIndicator.kt:61-74` — tap routing table (START only from
  NOT_TRANSLATED); `:227-303` TranslatingIndicator; `:369+` ErrorIndicator
  (long-press = START is the existing retry pattern).
- `TranslationProgressSheet.kt:164-177` — aborted banner block.
- Test patterns: `TranslationProgressSheetSubtitleTest`, `P5CopyAndAccessibilityTest`,
  `CancelSyncStoreWriteTest`, `TranslationCoexistenceHarness`.

## Reports

- `engineering/t918-implementation-log.md` (implementer)
- Review + verification appended on acceptance.
