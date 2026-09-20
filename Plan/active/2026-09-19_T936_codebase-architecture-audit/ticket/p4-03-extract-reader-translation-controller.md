# Ticket P4-03: Extract ReaderTranslationController from ReaderViewModel

**Phase:** 4 (after P4-01/02) | **Risk:** Medium | **Type:** Behavior-preserving extraction

## Current state

`ReaderViewModel.kt` = 2,945 lines. Translation concerns interleaved with reader navigation/viewer
logic: session admission calls + switch flow (added P3), manual page translate dispatch, auto window
updates, retry/re-arm, translation toggle, store observation bridges, persisted-layout source install,
reader teardown coordination (~600+ lines per audit; re-verify).

## Changes

1. New `ui/reader/ReaderTranslationController` (or `translation/reader/` if you prefer cohesion with
   the translation module — pick one, justify in report): owns the translation-side state machine of
   the reader — session coordinator interaction, manual translate request path, auto window updates,
   retry/re-arm, toggle-off, teardown delegation to ReaderTeardownCoordinator, translation state
   flows exposure.
2. `ReaderViewModel` delegates translation actions through the controller's single interface and
   collects its flows. Reader navigation/viewer logic stays in the ViewModel untouched.
3. Pure moves: controller methods call the SAME manager/scheduler/coordinator seams with the same
   arguments in the same order. The ViewModel's public state surfaces (used by ReaderActivity/
   viewer composables) keep identical names/types so UI code does not change.

## Constraints

- The P3 session gate calls introduced in ReaderViewModel route through the controller unchanged.
- No changes to TranslationManager/TranslationScheduler/TranslationSessionCoordinator signatures.
- ZERO test modifications (reader-side tests pin behavior; a diff = behavior change: STOP).

## Verification

Full both-flavor suites green; `assembleDevDebug` green. ReaderViewModel < ~2,200 lines;
controller is the single translation entry surface for the reader.

## Commit(s)

`refactor(reader): extract ReaderTranslationController from ReaderViewModel`
