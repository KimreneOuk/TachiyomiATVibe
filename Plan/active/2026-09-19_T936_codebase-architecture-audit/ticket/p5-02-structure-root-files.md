# Ticket P5-02: Structure the 17 root translation files into domain packages

**Phase:** 5 (after P5-01) | **Risk:** Medium (wide import churn) | **Type:** Package move

## Current state (17 files at `eu.kanade.translation` root)

Big four: ChapterTranslationStore (2,865), TranslationManager (1,776), TranslationPipeline (1,322),
ChapterTranslator (958). Plus: ActiveChapterStoreRegistry (267), TranslationStageContracts (254),
TranslationPendingRequestStore (152), ReaderEntryTrace (119), CleanedImagePublisher (93),
TranslationQueueStore (71), MemoryPressurePolicy (68), TranslationSession (32), WriterOrigin (27),
ChapterResetPreflight (22), PostOcrStageSemantics (20), TranslationMemoryPressureForwarder (5),
PageTranslationKey (5).

## Target structure (Director principle 4; `orchestration/` already exists)

- `storage/`: ChapterTranslationStore, ActiveChapterStoreRegistry, TranslationQueueStore,
  TranslationPendingRequestStore, CleanedImagePublisher
- `orchestration/`: TranslationManager, ChapterTranslator, TranslationSession, ChapterResetPreflight,
  ReaderEntryTrace
- `pipeline/`: TranslationPipeline, TranslationStageContracts, PostOcrStageSemantics, WriterOrigin,
  MemoryPressurePolicy, TranslationMemoryPressureForwarder, PageTranslationKey

Deviations allowed with one-line justification in the report (e.g., a file more cohesive elsewhere).
Do NOT create `model/` for the tiny files — overkill; they land with their consumers above.

## Rules

1. Pure moves: package declaration + imports only; zero content edits (a file may NOT be edited
   beyond its package line in the same commit that moves it).
2. Update `app/proguard-rules.pro` / gradle FQCN references if any (grep first, state result).
3. One commit per target package (3 commits).
4. Test imports update; assertions untouched.

## Verification

Root package contains only `package eu.kanade.translation` files that are genuinely cross-cutting
(target: zero .kt files remain at root — if you believe one must stay, justify in report).
Full both-flavor suites green; `assembleDevDebug` green.

## Commit(s)

`refactor(translation): move <domain> files into <package> (n/3)`
