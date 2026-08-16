# Rolling auto-translation implementation

Objective:
Implement the approved five-ticket rolling auto-translation pipeline and truthful live reader feedback.

Current symptom or desired behavior:
Auto prefetch is off by one, sequentially waits for full-page completion, can underfill after rapid navigation, and exposes ambiguous chapter-wide/full-page processing feedback.

Scope boundary:
Replace auto scheduling and reader feedback while preserving batch translation, translation quality, one native inference lane, manual translation, durable artifacts, and the Android 8+/6 GB device floor.

Acceptance criteria:
- `N` means exactly the next `N` available pages.
- Remote translation overlaps the next page's native preparation only after the cleaned image is durable.
- ML Kit remains serialized with native work.
- Rapid navigation converges without duplicate work or queue holes.
- The UI distinguishes queued, actual stages, ready, and deferral reasons without fabricated percentages.
- Pager and webtoon keep the original page readable and expose truthful ready-ahead state.
- Focused tests and repository validation pass, or environmental blockers are recorded precisely.

Constraints:
- Preserve bounded memory and a single native page lane.
- Do not retain decoded bitmaps across the prepared-page boundary.
- Preserve unrelated worktree changes.
- Live code and tests override planning assumptions.

Stop conditions:
- A live-code discovery conflicts with a settled product behavior.
- Completion requires a materially different architecture or new user authority.
- Required validation is blocked by missing JDK/Android SDK, in which case record the exact blocker and continue all safe static validation.

