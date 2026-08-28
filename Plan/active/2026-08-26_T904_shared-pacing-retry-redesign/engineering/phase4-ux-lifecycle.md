# T904 Phase 4 — UX and lifecycle integration

## Delivered

Commit `e18ed84` (`feat(translation): unify batch UX and reader lifecycle`) adds the Phase 4 projection and lifecycle slice on top of the ordered Phase 1–3 commits.

- Added a durable, numeric-chapter pending-request tracker with immediate `STARTING`, `PREPARING`, `WAITING_FOR_DOWNLOAD`, and download-failure states. Manga rows and the reader can acknowledge a tap before download, archive, OCR, or provider work begins; pending requests remain cancellable and retryable.
- Made `TranslationManager.observeBatchProgress` the shared projection for manga, reader, and foreground notification consumers. Live queue state takes precedence over stale download-cache state, and queue/paused metadata is projected into the progress snapshot.
- Rendered pending and paused states in manga indicators/progress sheets, reader settings/toolbar, and the notification. Paused views include a safe reason, next eligible retry time when available, and a cooldown-aware retry action through Phase 3 `requeueExisting`.
- Kept reader collectors alive across enter/exit, subscribed before opening a translation store so no-store pending requests are still visible, and retained active/paused batch stores during reader teardown.
- Protected queued, translating, paused, and pending chapters from reader convenience auto-delete; protected pending-deletion entries are reinserted for a later eligible cleanup.
- Kept foreground-service active predicates restricted to `QUEUE`/`TRANSLATING`; paused work is represented by a non-ongoing reminder and does not advertise active work.

## Verification

- `./gradlew.bat :app:compileStandardDebugKotlin --no-daemon --console=plain --max-workers=2` — passed after the final reader collector adjustment.
- `./gradlew.bat :app:testStandardDebugUnitTest --no-daemon --console=plain --max-workers=2 --tests 'eu.kanade.translation.model.TranslationUiProjectionTest' --tests 'eu.kanade.translation.TranslationManagerReaderTeardownTest' --tests 'eu.kanade.tachiyomi.data.translation.BatchTranslationForegroundPolicyTest'` — passed.
- `./gradlew.bat spotlessApply --no-daemon --console=plain --max-workers=2` — passed; the final focused suite was run after formatting.
- Required gate `spotlessCheck :app:testStandardDebugUnitTest :domain:testReleaseUnitTest` was run. Formatting and domain tests completed successfully/from cache. The app task reported 1053 tests with exactly one failure: the pre-existing, explicitly excluded `AotReportBubbleFillTest > reportBubbleFill preserves diagonal components without an inset interior()` at `AotReportBubbleFillTest.kt:49`.
- An earlier combined focused run exposed a timing-only reader teardown latch failure; the isolated teardown test was rerun successfully, and the final focused suite passed.

## Decisions and deviations

- Pending intent is persisted in a small SharedPreferences store keyed only by chapter ID; manga/source objects are deliberately not serialized. The downloader handoff supplies the current manga/chapter after the files are ready.
- `WAITING_FOR_DOWNLOAD` remains a request phase rather than a new `Translation.State`, preserving the Phase 3 enum values and the `QUEUE`/`TRANSLATING` foreground contract.
- No governor, semantic-retry, coordinator, or durable pause contract was changed. UI retry calls the existing cooldown-aware requeue adapter.
- Coverage is focused pure projection/lifecycle/policy coverage rather than Compose or device notification instrumentation; the latter remains a runtime validation gap.

## Remaining risks

- One existing progress notification ID is shared by paused chapters, so simultaneous paused chapters are represented by the latest reminder rather than separate notifications.
- SharedPreferences writes use the platform asynchronous apply path. A process kill in the tiny write window could lose the acknowledgement, although normal restart and downloader restoration paths are covered by the durable map.
- The notification/UI displays provider-safe summaries from the durable failure record; legacy migrated failure text is not independently scrubbed in this slice.
- Device-level notification permission behavior and Compose gesture rendering were not exercised in the unit-test environment.
