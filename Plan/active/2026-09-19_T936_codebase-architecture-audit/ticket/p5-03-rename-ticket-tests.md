# Ticket P5-03: Rename ticket-named test suites to behavioral invariants

**Phase:** 5 (after P5-02) | **Risk:** Low | **Type:** Test file/class renames only

## Current state — 23 files (verified @ `ce714c6`)

Naming rule: strip the delivery code (D#, MilestoneM#, T9##), name the invariant the test protects.
File name AND class name AND (if trivially greppable) the header comment's ticket phrase change;
assertions/tests bodies otherwise untouched.

| Current | New |
|---|---|
| D1OriginPriorityTest | ReaderManualPreemptsAutoLeaseTest |
| D5GlossaryAwareReuseTest | GlossaryAwareZeroPaidReuseTest |
| D6DrainNotCancelTest | AutoProviderCallDrainsNotCancelsTest |
| D6ForegroundFairnessTest | ForegroundProviderWindowFairnessTest |
| D7EngineEpochStopRaceTest | EngineEpochStopRaceTest |
| D8StallWatchdogTest | PipelineStallWatchdogTest |
| D9AttemptLedgerTest | BatchAttemptLedgerDeathCycleTest |
| D10PartialDownloadAdmissionTest | PartialDownloadAdmissionTest |
| D11PermitFreeCommitTest | NextPageAdmittedDuringParkedPublicationTest |
| MilestoneM2KillTheStallsTest | QueueSteeringProviderGovernorTest |
| MilestoneM3OcrPushThroughTest | LazySourceFingerprintPushThroughTest |
| MilestoneM4ParallelWindowsTest | BatchParallelWindowBudgetTest |
| MilestoneM5LanProviderHeadroomTest | ProviderHeadroomTest |
| MilestoneM6DurabilityAtScaleTest | DurabilityAtScaleTest |
| T918CancelledBatchRestartTest | CancelledBatchRestartReuseTest |
| T918SheetRetryTruthTest | TranslationSheetRetryTruthTest |
| T924FeatureFlagsTest | TranslationFeatureFlagsTest |
| T934CompletionOracleTest | TranslationCompletionOracleTest |
| T934ProjectorRebuildTruthTest | ProjectorRebuildTruthTest |
| T934ReaderBarTruthTest | ReaderBarTruthTest |
| T934RebuildTruthTransitionsTest | RebuildTruthTransitionsTest |
| T934SheetAdvancedViewPreferenceTest | TranslationSheetAdvancedViewPreferenceTest |
| T934WriteTimeDigestsTest | WriteTimeDigestsTest |

Adjust a name if the file's actual dominant invariant differs (you read the file; the table is a
strong prior, not a cage) — document any deviation.

## Rules

Renames only: file name, class name, package-visible references in the same package, header comment
ticket phrases. Test bodies/assertions zero diff. If a class name is referenced elsewhere (harnesses,
suites), update those references mechanically.

## Verification

`Get-ChildItem -Recurse app/src/test -Filter *.kt | Where-Object { $_.Name -match '^(D\d|Milestone|T9\d)' }` → empty.
Full both-flavor suites green (same test count as before renames).

## Commit

`test(translation): rename ticket-named suites to behavioral invariants`
