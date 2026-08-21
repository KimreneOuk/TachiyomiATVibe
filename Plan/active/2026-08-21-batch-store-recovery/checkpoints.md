# Checkpoints

## 2026-08-21

- Force-stopped `app.kanade.tachiyomi.at.debug` on the connected device before changing persistence behavior.
- Made active chapter-store creation single-flight per chapter so reader and batch callers share one store instance.
- Added origin-checked candidate cancellation and wired every rejected/failed batch stage to release its lease and discard staged artifacts.
- Added conservative provider-response recovery for complete, exact, unique ID records when wrapper framing is absent or only the optional context-delta tail is truncated.
- Kotlin compilation passed after the implementation changes.
- Focused store, cleanup, chunking, and stage-semantics tests passed (20 tests); the final provider parser suite passed all 21 tests.
- `spotlessApply` and `git diff --check` completed cleanly.
- The combined `spotlessCheck :domain:testReleaseUnitTest :app:assembleStandardDebug` gate passed in 3m 3s.
- The broad app unit-test gate reached an unrelated existing failure in `RollingAutoCoordinatorTest.cancel immediate rearm waits for cancelled owner` and then hung without log or CPU progress; the stalled Gradle process was stopped after the failure was captured.
- Installed `app-standard-arm64-v8a-debug.apk` on `192.168.100.223:33649` with app data preserved. Installed version is `0.17.1-182`; SHA-256 is `4CAEB3184822845DD762017879A23D3C2A8C0E54E1E251C6653CCCE97EC14320`. The app remains stopped and no translation batch was restarted.
