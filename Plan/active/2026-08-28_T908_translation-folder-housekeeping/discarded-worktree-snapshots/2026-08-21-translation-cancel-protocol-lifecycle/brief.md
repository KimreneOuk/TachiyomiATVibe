# Translation cancellation, provider recovery, and reader lifecycle repair

Objective:
Make batch cancellation final and restart-safe, make malformed/truncated provider responses recover without poisoning whole chunks, prevent reader Auto from attaching to stale batch ownership, and make background/foreground transitions non-blocking and ordered.

Current symptom or desired behavior:
- Cancelling a batch can leave native work, leases, RUNNING stages, and trackers alive.
- A quick restart can have its leases released by the previous run.
- Strict provider parsing turns omissions/truncation into terminal whole-chunk failures.
- Reader Auto may strand pages after batch cancellation.
- Reader `onPause()` performs blocking persistence and clears streams before worker teardown joins; foreground resume can race that teardown.

Scope boundary:
- Batch queue/run cancellation and page-lease ownership.
- Contextual prompt/parser/retry recovery.
- Reader Auto admission after batch ownership and reader lifecycle teardown.
- Focused regression tests for the above.
- No translation-quality model changes, UI redesign, or unrelated reader behavior changes.

Acceptance criteria:
- Cancelling a queued chapter does not interrupt a different running chapter.
- Cancelling a running chapter joins its run before restart/next admission and clears its transient state/tracker.
- An older run cannot release a newer run's leases.
- Provider omissions use an explicit contract and recoverable framing never reads delta records as translations.
- Remote-provider structural failures receive bounded chunk splitting/retry.
- Auto never marks a page RUNNING unless it actually owns the page lease/work.
- Reader background teardown performs no blocking persistence on the main thread; foreground dispatch is sequenced after teardown.
- Focused tests and the repository completion gate pass.

Constraints:
- Preserve completed artifacts and batch queue process ownership.
- Keep native execution serialized and memory bounded for 6 GB devices.
- Preserve unrelated worktree changes.

Stop conditions:
- Product behavior must change beyond the acceptance criteria.
- A required API boundary cannot be changed without broad architecture migration.

