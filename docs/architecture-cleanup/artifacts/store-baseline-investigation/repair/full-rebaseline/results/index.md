---
title: "Store fixture repair full rebaseline (both flavors)"
kind: ticket
status: 1
assignee: ""
comments: none
---

# Result

Full unfiltered app unit-test rebaseline after Gemini actual-diff review ACCEPTED the one-file 3/3 helper repair. Code frozen at branch architecture-cleanup, HEAD 999bb9be6563f957ee508f739ba2f042bf1c90ee plus exactly the accepted ProbeTest readManifest() helper patch (3/3, the only content diff). No domain, build, assemble, or Spotless rerun (frozen test-only patch; standing focused-format check remains valid). No clean, toolchain, model, device, native, or provider action. No commit or push; the patch stays in place.

## Summary

| Flavor | XML files | Tests | Failures | Errors | Skipped | Gradle exit | Wall |
| --- | --- | --- | --- | --- | --- | --- | --- |
| DevDebug | 332 | 2254 | 1 | 0 | 1 | 1 (BUILD FAILED in 3m 20s) | 3m 20s |
| StandardDebug | 332 | 2254 | 0 | 0 | 1 | 0 (BUILD SUCCESSFUL in 3m 6s) | 3m 6s |

Expected forecast was 332 classes / 2254 tests / 2253 pass / 1 skip / 0 failures per flavor. Counts match exactly; StandardDebug is fully green. DevDebug has ONE unexpected single failure (retained and reported below for disposition, not repaired, not retried). The four Phase 2 baseline-known ChapterTranslationStoreArtifactOpenProbeTest failures pass in both flavors — the repair holds under the full suite. The boundary test passes 3/0 in both flavors. The single skip per flavor is the documented benchmark case `eu.kanade.translation.diagnostics.SyntheticJournalReplayBenchTest.synthetic200PageJournalFullReplayBenchmark()`.

# Literal commands, shells, exits

Both invocations ran serially from the repository root (Git Bash interpreter), no filters, `--rerun`. Each wrote its full Gradle output to its own scratch log file; the registered command log carries the `GRADLE_EXIT:` marker (Gradle's real exit; the wrapper always exits 0, so the marker is authoritative).

| Flavor | Literal Gradle command | Traycer shell ID | Wrapper exit / GRADLE_EXIT | Log evidence |
| --- | --- | --- | --- | --- |
| DevDebug | ./gradlew.bat :app:testDevDebugUnitTest --rerun --console=plain --stacktrace | b33501a5-60a1-40cb-bfb4-d9710d275716 | 0 / 1 | full/dev/log/gradle.log (35,519 bytes; SHA-256 DCA448E763BF282D059F6E37AC34ABC14CC6373741AFBBE42FD3B7AC26D453D1) |
| StandardDebug | ./gradlew.bat :app:testStandardDebugUnitTest --rerun --console=plain --stacktrace | b24db0ac-d53e-46ac-9931-3f3f06849ab7 | 0 / 0 | full/standard/log/gradle.log (16,966 bytes; SHA-256 5E30C72316DD5AD9E107364125BE3545CC2D4BC015B1713D3880C8B8E9FF732B) |

Both logs are complete this time: each begins at the true start ("Configuration on demand is an incubating feature." / "Type-safe project accessors is an incubating feature.") and ends at its own BUILD outcome line plus task summary; no rotation, no prefix gap, no truncation.

XML provenance: each flavor's task XML was copied immediately after its own invocation, before the next Gradle call, to unique pre-created directories under the scratch root `C:\Users\ADMIN\.traycer\epics\699de408-00ac-4fe5-a903-b541c23063cc\scratch\store-baseline-repair\2026-10-15-fullrebaseline`:

- DevDebug: `full\dev\xml\` (332 TEST-*.xml)
- StandardDebug: `full\standard\xml\` (332 TEST-*.xml)

Key per-suite XML hashes (DevDebug / StandardDebug):

| Suite | DevDebug | StandardDebug |
| --- | --- | --- |
| TranslationSchedulerTraceTest | 11,338 bytes; SHA-256 165EFEDBEC94E1A63923794E44B5EA1B4485C802EC0B619D28F0D4B354CA2476 | 889 bytes; SHA-256 46154EB6A5E01A77307F4120C348804D023CDEEDBC31F403F4CCBC4C30937A35 |
| ChapterTranslationStoreArtifactOpenProbeTest | 3,332 bytes; SHA-256 796809772897432908B2C53A7A2A6EB62C191ED37D596457D0A754E1C94FE293 | 3,332 bytes; SHA-256 10FBFE4205DD9C4353F00D92C44C5029F5B35F61C1C1FA28C6367BA991AB955A |

# Comparison vs immutable Phase 2 full XML

Comparator: `scratch\phase-2-contracts\validation\2026-10-15-999bb9be\{dev,standard}\xml` (each 332 files / 2254 tests / 4 failures / 0 errors / 1 skip). Suite-identity sets are identical fresh-vs-baseline in both flavors (no missing, no extra suites). Per-suite test/error/skip counts are identical everywhere. The ONLY per-suite deltas:

| Flavor | Suite | Baseline (t,f,e,s) | Fresh (t,f,e,s) | Interpretation |
| --- | --- | --- | --- | --- |
| both | eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest | (14, 4, 0, 0) | (14, 0, 0, 0) | All four baseline-known failures now PASS — the accepted helper repair holds under the full suite. |
| dev only | eu.kanade.translation.scheduling.TranslationSchedulerTraceTest | (3, 0, 0, 0) | (3, 1, 0, 0) | UNEXPECTED single failure — retained for disposition (below). Same suite and all 3 methods pass in StandardDebug. |

# Unexpected DevDebug failure (retained, not dispositioned)

- Suite: `eu.kanade.translation.scheduling.TranslationSchedulerTraceTest`
- Method: `cancelled manual intent closes exactly once as cancelled()` (TranslationSchedulerTraceTest.kt:164)
- Failure: `io.kotest.assertions.AssertionFailedError: expected:<1> but was:<0>`
- The other two methods of the suite passed; the identical method passed in StandardDebug (0.002s), in the Phase 2 baseline dev+standard XML, and in all Phase 0/1/P2.6 ledgers.
- The file's working-tree content diff vs HEAD is EMPTY (it is one of the eleven stat-only M restorations; byte-identical to HEAD). Production content is likewise unchanged except the ProbeTest helper, which this suite does not exercise. The assertion shape (a once-only trace count observed as 0) is consistent with coroutine dispatch timing sensitivity under full-suite load, but this is NOT claimed as proven cause and NOT accepted as baseline. Per instruction: retained and reported; no unchanged retry, no repair, no disposition.

# Workspace and protected state

- HEAD 999bb9be6563f957ee508f739ba2f042bf1c90ee; content diff vs HEAD is exactly the accepted one-file 3/3 ProbeTest helper patch.
- Eleven stat-only M restorations remain with empty content diffs; untouched. Report `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` SHA-256 EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD — unchanged, untracked.
- Boundary test `eu.kanade.translation.architecture.TranslationPackageBoundaryTest`: 3 tests / 0 failures in both fresh flavors.
- Known limits: local JDK 21 vs CI 17 parity remains an open platform gate; the targeted store gate ran green inside both full suites (ChapterTranslationStoreArtifactOpenProbeTest 14/0 in each flavor), while generic production native/device safety paths remain untested; no domain rerun (untouched); no Spotless rerun (frozen test-only patch; standing focused-format check valid from the repair results).

# Next

Holding for Gemini evidence review. On acceptance the parent carries commit/push and Phase 3 entry; the single DevDebug trace failure awaits parent disposition (none attempted).

# Historical status update (post-disposition)

The single DevDebug `TranslationSchedulerTraceTest` failure recorded above is HISTORICAL — preserved verbatim above and in `scratch\store-baseline-repair\2026-10-15-fullrebaseline\` (XML 165EFEDB…2476), NOT erased or reclassified. It was diagnosed, dispositioned, and repaired via the accepted completion-boundary waits; the repaired tree passed both full suites in the [final rebaseline](../final-results/index.md).
