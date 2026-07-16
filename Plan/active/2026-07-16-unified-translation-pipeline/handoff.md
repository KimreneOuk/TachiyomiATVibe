# Handoff - Unified Translation Pipeline Recovery

## Current status

- Investigation and design are complete.
- Checkpoint 0 passed; production implementation starts with the disjoint Agent A
  and Agent B work packages.
- The authoritative task artifacts are this folder's `brief.md`,
  `investigation.md`, `design.md`, `checkpoints.md`, and `tasks.md`.
- The prior flat plan is marked superseded; the temporary `.kilo` draft was
  removed to avoid duplicate authority.

## Decisions confirmed with the user

- Detect/OCR the chapter first; no batch inpaint before the all-OCR barrier.
- Enqueue Pass 1 as each page OCR becomes durable.
- One page-context Pass-1 request per page; split only an oversized page.
- Pass 1 uses page-only context; revision uses glossary and nearby chapter
  source/drafts.
- Current block sorting is preserved and compact panel headers provide a
  token-efficient layout hint; panels are never treated as speakers.
- Valid drafts display immediately and batch completion does not wait for
  semantic revision.
- Semantic revision is a user-triggered, manager-owned chapter action with
  `FLAGGED` and `ALL_TRANSLATED` scopes.
- Revision works over durable manual, auto, batch, partial, and legacy drafts.
- Gemini, OpenRouter, DeepSeek, and LM Studio may act as explicit contextual
  reviewers. Google, DeepL, and ML Kit remain validation/retry-only translators,
  but their drafts may be reviewed by a separately confirmed contextual model.
- Revision uses strict plain-text K/C/U results and no tool calling, speaker IDs,
  chain-of-thought output, silent provider switch, or positional fallback.
- Manga and reader entry points share immutable preflight/start/progress/report
  contracts; backend state and eligibility are authoritative.
- Confirmation shows coverage, scope, reviewer/model, language pair, exclusions,
  and request estimate. The terminal report shows exact outcomes and accepted
  before/after changes.
- Pre-translation continues through reader and app background lifecycle unless
  explicitly cancelled/deleted or critical memory handling intervenes.

## Next safe action

Run Agent A and Agent B in parallel from `tasks.md`. Review and freeze their
store/admission and provider contracts before releasing Agent C or Agent E.

## Validation already performed

- Live code paths, provider interfaces, store patch behavior, lifecycle calls,
  test locations, and commit attribution were inspected.
- `JAVA_HOME` was resolved for repository commands to Android Studio's bundled
  JBR at `C:\Program Files\Android\Android Studio\jbr`.
- `:app:testStandardDebugUnitTest` passed for
  `eu.kanade.translation.batch.*` and `eu.kanade.translation.translator.*`.
- Baseline `git diff --check` passed.

## Risks to retain during implementation

- Provider slowness must not backpressure OCR; queue only small page references.
- Independent translation/inpaint commits must not overwrite or spuriously reject
  one another due only to unrelated page-version changes.
- Re-decoding for inpaint adds I/O/CPU; device measurement is required.
- One request per page increases request count and prompt overhead.
- Splitting the current manual/auto fused native path is high-regression-risk and
  must follow parity tests.
- The current standalone revision path does not exist; the existing revision loop
  is batch-only and must be extracted after pure contracts pass.
- Provider/language metadata is not currently persisted. Legacy preflight needs
  explicit language confirmation and backward-compatible summary defaults.
- The manga translation indicator is currently hidden for non-downloaded
  chapters; partial manual/auto eligibility cannot be keyed only to batch state.
- `ALL_TRANSLATED` may produce many bounded requests, so confirmation and exact
  request accounting are required.
- Current worktree contains unrelated modifications/deletions; never revert or
  include them in this task.

## Prepared execution

- `tasks.md` is the implementation todo list and agent work-package source.
- Checkpoints are dependency gates, not permission to run agents concurrently on
  shared hotspot files.
- The orchestrator owns contract freeze, integration, shared-file conflict
  resolution, checkpoint records, and final validation.

## Checkpoint records

### Checkpoint 0

- Files changed: planning records only; no production/test files.
- Tests/validation run: focused batch and translator JVM tests passed;
  `git diff --check` passed; dirty worktree inspected.
- Unexpected findings: Java was available through Android Studio's bundled JBR,
  despite no global `JAVA_HOME`; numerous unrelated existing deletions and edits
  remain excluded from this task.
- Risks remaining: shared hotspot files must remain orchestrator-serialized.
- Next checkpoint still valid: yes; CP1 and CP2 have disjoint ownership and can
  proceed in parallel before coordinator work begins.

## Completion report requirements

When implementation finishes, report:

- production, test, and documentation files modified;
- tests added or updated;
- automated and device validation actually performed;
- validation not performed and why;
- memory/performance measurements;
- assumptions, residual risks, and follow-up work.

### Checkpoint 1

- Files changed: ChapterTranslationStore.kt, TranslationStageContracts.kt
- Tests/validation run: focused batch and translator JVM tests passed.
- Unexpected findings: None.
- Risks remaining: Ensure orchestration uses the new APIs correctly.
- Next checkpoint still valid: yes.

### Checkpoint 2

- Files changed: Contextual translators and parsing contracts.
- Tests/validation run: focused batch and translator JVM tests passed (with compilation fixes for structured providers).
- Unexpected findings: LmStudioTranslator, DeepSeekTranslator, and OpenRouterTranslator lacked 	ranslateContextualStructured implementation. This was fixed by the orchestrator.
- Risks remaining: Ensure exact K/C/U outputs in pass 2 adapters.
- Next checkpoint still valid: yes, Agents C and E can proceed in parallel.
