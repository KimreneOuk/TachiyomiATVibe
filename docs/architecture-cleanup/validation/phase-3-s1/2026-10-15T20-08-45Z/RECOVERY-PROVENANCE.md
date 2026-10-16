# S1 command-recovery provenance note

## Recovered native compile invocation (completed BEFORE the handoff stall)

- Command: `.\gradlew.bat :app:compileStandardDebugKotlin :app:compileDevDebugKotlin --console=plain`
- Working directory: repo root
- Result: `BUILD SUCCESSFUL in 2m 24s` — `191 actionable tasks: 17 executed, 1 from cache, 173 up-to-date` — actual exit `EXIT=0` captured by the invoking tool before the result handoff stalled.
- Both compile tasks EXECUTED (not up-to-date): `> Task :app:compileDevDebugKotlin`, `> Task :app:compileStandardDebugKotlin` (log lines 263-264, 274).
- Retained full output: `std-compile/compile-both-flavors.log` (written by Tee-Object during the same run).
- Cross-check: parent-copied daemon log (daemon 29780, build cec91df0, started 20:08:56, BUILD SUCCESSFUL in 2m24 at 20:11:14.956, then idle + client-handshake timeout) is CONSISTENT with the retained log. The 30-minute UI appearance was a command-result handoff stall after a successful build, not an active build; no rerun of the compilation was performed.

## Provenance limits

- The compile console evidence lives in the retained log file; the interactive transcript stream itself was lost with the stopped turn. Task coverage is proven by the log's task lines + exit code, not by a live transcript replay.
- The compile ran through a native blocking tool call; subsequent long Gradle invocations (focused tests, format check) run under registered Traycer shells so exit reliably resumes the agent.

## State at recovery

- `git status --porcelain`: 10 pre-existing metadata-only M entries + untracked protected report + exactly 3 new untracked S1 files
  (`app/src/main/java/eu/kanade/translation/pipeline/AttemptAuthority.kt`,
  `app/src/test/java/eu/kanade/translation/pipeline/AttemptAuthorityTest.kt`,
  `app/src/test/java/eu/kanade/translation/pipeline/CleanedOwnerTest.kt`).
- Protected report SHA256 unchanged: `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`.
- No staging/commits/pushes; S1 production call sites remain unwired.
