---
title: "Final full rebaseline after trace completion-boundary fix"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Result

Full unfiltered app unit-test rebaseline at the frozen two-file test-only diff (Gemini trace actual-code/proof review ACCEPTED): ProbeTest 3/3 helper patch + TranslationSchedulerTraceTest +3/−2 (three `schedule_end` completion-boundary waits; file snapshot C986F99E251CFA2D2BC8E991867E2449BE43D9695282B27D6F579C17975E45A2). No further edits before, during, or after these runs. No domain, build, assemble, or Spotless rerun (frozen test-only diff; the standing full formatter inventory from trace-disposition remains valid). No clean, toolchain, model, device, native, provider, commit, or push action.

## Summary — forecast met, both flavors fully green (real Gradle exits)

| Flavor | XML files | Tests | Failures | Errors | Skipped | GRADLE_EXIT / outcome | Wall |
| --- | --- | --- | --- | --- | --- | --- | --- |
| DevDebug | 332 | 2254 | 0 | 0 | 1 | 0 / BUILD SUCCESSFUL in 3m 5s | 3m 5s |
| StandardDebug | 332 | 2254 | 0 | 0 | 1 | 0 / BUILD SUCCESSFUL in 2m 58s | 2m 58s |

2253 pass + 1 benchmark skip per flavor as forecast. The sole skip in each flavor is the documented `eu.kanade.translation.diagnostics.SyntheticJournalReplayBenchTest.synthetic200PageJournalFullReplayBenchmark()`. Actual XML is authoritative and matches the forecast exactly.

# Literal commands, shells, exits

Serial from the repository root (Git Bash), no filters, `--rerun`, each with its own registered command log; full Gradle output redirected to the per-leg scratch log and `GRADLE_EXIT:` marker recorded (the authoritative Gradle exit — wrapper exits are 0 by construction and were not reported as Gradle results).

| Leg | Literal Gradle command | Traycer shell ID | GRADLE_EXIT / outcome |
| --- | --- | --- | --- |
| DevDebug | ./gradlew.bat :app:testDevDebugUnitTest --rerun --console=plain --stacktrace | e26a3aaa-f17c-4d66-9098-04376bb795a2 | 0 / BUILD SUCCESSFUL in 3m 5s |
| StandardDebug | ./gradlew.bat :app:testStandardDebugUnitTest --rerun --console=plain --stacktrace | 2a41ef9b-c351-45db-bc6e-69daf8bb3600 | 0 / BUILD SUCCESSFUL in 2m 58s |

Log completeness / rotation / tee audit: each redirected log is complete — true first lines ("Configuration on demand is an incubating feature." / "Type-safe project accessors is an incubating feature.") through the leg's own final "BUILD SUCCESSFUL" + task summary. No rotation, no prefix gap, no truncation marker. Sizes and hashes:

| Leg | Log bytes | SHA-256 |
| --- | --- | --- |
| dev/log/gradle.log | 16,821 | 5173A60C66C5755C4BF55DB73C872762D65931C95F76E9D58871604A05D8C8F8 |
| standard/log/gradle.log | 16,967 | 7FF438E65C05499D4E4A116699F31A57FE72E9FF503F2C501F68822B00E2737F |

XML provenance: each flavor's 332 task XMLs copied immediately after its own invocation, before the next Gradle call, to pre-created unique dirs `scratch\store-baseline-repair\full-after-trace\{dev,standard}\xml`. Key per-suite XML hashes (dev / standard):

| Suite | DevDebug | StandardDebug |
| --- | --- | --- |
| ChapterTranslationStoreArtifactOpenProbeTest | 3,331 B; B962696190D9B3F7623BCEB896F17A51595FAB44A7B1AF54A530AC64E7389649 | 3,331 B; BAE8C751D76925BC5C4FC3C22463F09426FDA7BEEC497A67C1BA8E5E7CB74688 |
| TranslationSchedulerTraceTest | 889 B; B17B5B441A63DDAE269975BDB7F7624883890FA65E4CC28A993D547E5F2C426F | 889 B; 4287FC30A9858C7BE21A3382207602FD17FBF8E095F9F706C97ECC4D1598161B |
| TranslationPackageBoundaryTest | 896 B; 6C90E01E1ACC796F4A3B4530A4BDD9F168CF8C1E73C97481AC309CE8E4BE4557 | 897 B; 62365C5431E2B0E92E992FE5E188A26D83F32500FFF1D287A44D6E6EFA02023A |

# Comparison vs Phase 2 immutable full XML and original failed Dev ledger

Baseline: `scratch\phase-2-contracts\validation\2026-10-15-999bb9be\{dev,standard}\xml` (332 files, 2254 tests, 4 failures, 0 errors, 1 skip each).

- Suite-identity sets: identical fresh-vs-baseline in both flavors (no missing, no extra).
- Method identities: ZERO mismatches across all 332 suites per flavor (per-suite sorted testcase-name comparison).
- Per-suite count deltas: exactly ONE in each flavor — `ChapterTranslationStoreArtifactOpenProbeTest` (t=14, f=4→0, e=0, s=0): the four baseline-known store cases now PASS in both flavors under the full suite (the accepted helper repair holds).
- Trace suite: no count delta vs baseline (3/0 both flavors in both ledgers), and all 3 method identities identical. The originally-failing Dev case `cancelled manual intent closes exactly once as cancelled()` FAILED in the original full ledger (XML 165EFEDBEC94E1A63923794E44B5EA1B4485C802EC0B619D28F0D4B354CA2476) and PASSES in this fresh dev leg (0.027s) and standard leg — same method identity, no reclassification: the old failure ledger is preserved verbatim and marked historical, not erased (see [original results](../results/index.md)).
- Boundary test `eu.kanade.translation.architecture.TranslationPackageBoundaryTest`: 3/0 in both fresh flavors.
- No unexpected failure appeared in either leg; nothing was retried and no edits were made.

# Frozen workspace state

- HEAD 999bb9be6563f957ee508f739ba2f042bf1c90ee; content diff vs HEAD is exactly two files: ChapterTranslationStoreArtifactOpenProbeTest.kt (+3/−3) and TranslationSchedulerTraceTest.kt (+3/−2).
- Working tree remeasured: 13 porcelain entries = 10 stat-only M restorations + the 2 content diffs + the untracked report.
- Report `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` SHA-256 EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD — unchanged, untracked.

# Limits

- These two green legs prove THIS tree passed THIS full-suite run on THIS machine (JDK 21 vs CI 17 parity still an open platform gate); no proof of universal stability is claimed.
- No domain/build/Spotless rerun (frozen test-only diff; standing format inventory valid); no device/native/store-gate production checks beyond the targeted store probe class which ran green inside both full suites.
- The original full-rebaseline Dev trace failure and StandardDebug green ledgers remain immutable, preserved under `scratch\store-baseline-repair\2026-10-15-fullrebaseline\`, now labeled historical.

# Next

Gemini [accepted the final evidence](../final-review). Parent committed and pushed the exact two-file tree as `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec`; fresh remote verification matched local HEAD. [Closure](../../closure) records the published replacement baseline and Phase 3 entry. Historical pre-commit workspace snapshots above remain unchanged. No child commits/pushes.
