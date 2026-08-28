# T904 integration verification

Date: 2026-08-27  
Branch: `t904/integration`  
Verified commit: `a53fc502d388e68655db22419f511c16168f036e`

## Assembly

The approved phase work was already present as one linear child-branch chain. No individual phase commits were cherry-picked and no rebase against origin refs was performed. The integration branch was fast-forwarded to `e18ed840eb7ea1b0e3ab2435888f398401fa220e`, then received only the integration fixes recorded below.

Dependency order (oldest first):

1. Contract base: `926ae00def9ac887f34f192f1b84416a1be543c0`
2. Governor: `011e6018e111eb08210cde215481f01f8ca70e4b`
3. Semantic retry: `f1450e9d0f3d28812316b396e82e7b56d0a9c5f7`
4. Pause/durable queue: `b56f56c3a81c958ca6068041e0d551987e2a7844`
5. UI/lifecycle: `e18ed840eb7ea1b0e3ab2435888f398401fa220e`
6. Integration fixes: `a53fc502d388e68655db22419f511c16168f036e`

All phase hashes are ancestors of the verified commit. The primary workspace was not modified or merged.

## Integration fixes

Commit `a53fc502d388e68655db22419f511c16168f036e` changes only:

- `AiModelFetcher`: provider failures now return their already-redacted safe summary; generic exception text is no longer surfaced to the UI; the LM Studio start diagnostic records only a normalized-base hash rather than the raw URL.
- `GoogleTranslator`: every classified non-2xx response now throws its typed provider failure, including terminal HTTP failures, so an authentication/client error cannot fall through JSON parsing and appear to succeed.

These are integration-boundary fixes for the cross-phase diagnostics and retry/persistence contract; no phase implementation was rewritten.

## Required verification

| Check | Result |
|---|---|
| `spotlessCheck` | PASS |
| `:app:compileStandardDebugKotlin` | PASS with explicit Android Studio JBR and Android SDK |
| `:app:testStandardDebugUnitTest` | 1,053 tests; 1 failure, 0 errors, 0 skipped |
| `:domain:testReleaseUnitTest` | 68 tests; 0 failures, 0 errors, 0 skipped |
| `git diff --check` | PASS; clean after commit |

The one app failure is the policy-excluded, deterministic base failure:
`AotReportBubbleFillTest > reportBubbleFill preserves diagonal components without an inset interior()` (`AotReportBubbleFillTest.kt:49`). No other app test failed. The app task therefore exits non-zero solely because that exclusion remains reproducible; the domain task passes.

The post-fix combined `spotlessCheck :app:compileStandardDebugKotlin` run completed successfully. The full app suite completed with `1053 tests completed, 1 failed`, and the domain release suite completed with 68 passing tests. Earlier SDK-location and PATH-Java failures were environment setup failures, not source/test failures.

## Cross-phase checks

- **Governor coverage:** Static tracing found all translation-reachable remote HTTP calls under `ProviderRequestGovernor.executeValue`: `RemotePageTranslationEngine`, `AiModelFetcher` (all model-fetch backends), `DeepLTranslator`, `GeminiTranslator`, `GoogleTranslator`, and `OpenAiCompatibleTranslator`. Contextual and standard Gemini requests, thinking fallback POSTs, model fetchers, and the remote page engine all pass through the shared governor. No translation source path bypasses it. `SharedProviderRequestAdmission` remains only as a no-op compatibility facade; its old broad local-I/O admission lock has no call sites.
- **Finite retry budget:** `RequestRetryBudget(maxAttempts = 8)` is atomically consumed by the shared governor. Semantic retries, transport retries, and Gemini thinking fallback inherit one budget; nested transport contexts avoid double charging the same network attempt. The effective retry ceiling is finite.
- **Pause and resume:** The pause coordinator reconciles a committed natural prefix with a pending tail. A paused batch persists its tail and resumes through idempotent `requeueExisting` without losing the committed prefix; cooldown and duplicate-start guards remain in place.
- **Foreground lifecycle:** Foreground admission is limited to `QUEUE` and `TRANSLATING`. `PAUSED` publishes a non-ongoing paused notification and stops the foreground service.
- **Reader deletion protection:** Auto-delete protects `QUEUE`, `TRANSLATING`, and `PAUSED` chapters, plus chapters with pending translation requests. Protected pending deletions are retained and unprotected deletions continue normally.
- **Artifacts and durable queue:** Atomic document writes validate the temporary file, rotate primary to backup, promote temp to primary, and recover from backup. Durable stage failure and manifest publication use the store's atomic boundary. Unit tests cover these paths.
- **Diagnostics and UI safety:** New diagnostics use event fields, hashes, status/length, and stable summaries; no raw keys, prompts, bodies, or model-fetch URLs are emitted or shown by the changed paths. The Google terminal-error fix ensures typed failures reach the retry/persistence fence.
- **Normal reader behavior:** Reader lifecycle, state projection, deletion protection, and translation tests remain green in the full app suite apart from the known AOT exclusion. The intentional merged-state precedence (`TRANSLATING > ERROR > PAUSED > TRANSLATED`) is preserved.

## Risks and limits

- The deterministic AOT failure remains excluded by the task policy and is the only failing test.
- Repository health at the contract base is **YELLOW** (`99c11a492e8480a1c19a007c9ab83c4525cd6fb3`); the base is `926ae00def9ac887f34f192f1b84416a1be543c0`. No upstream synchronization or origin rebase was attempted.
- Initial preflight could not resolve `java`/`JAVA_HOME` from PATH. Verification succeeded only after explicitly selecting the Android Studio JBR and SDK; this environment limitation should remain visible in handoff records.
- There is no device/instrumentation process-kill or service-restart end-to-end test. Queue and pending-request preferences use AndroidX `preferences.edit {}` (asynchronous apply), so hard-kill durability and SAF/OS crash windows remain residual risks despite atomic artifact-store coverage.
- Provider live endpoints were not exercised; network behavior is covered by static tracing and existing fixture/unit tests.

## Final disposition

The assembled phase chain is internally consistent, the required verification is complete with only the documented AOT exclusion, and the remaining changes are limited to the two integration fixes in `a53fc502d388e68655db22419f511c16168f036e`. This child branch is ready for the separate primary integration decision.
