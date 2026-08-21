# Batch store and provider recovery

Objective:
Make a sequential batch share one authoritative chapter store with the reader, stop downstream work after rejected persistence, and safely recover provider responses whose translations are complete but optional framing is malformed.

Current symptom or desired behavior:
The 76-page device run advanced native work to page 60 while the durable manifest remained at page 21. Logs repeatedly reported stale manifest snapshots, page-version mismatches, dependency-fingerprint mismatches, and strict provider protocol rejection. Only pages 005–007 had committed translated images.

Scope boundary:
- Active chapter store registration/open races.
- Sequential contextual batch guarded-write and candidate lifecycle behavior.
- Contextual response structural validation/recovery.
- Focused unit/concurrency tests and device debug deployment.
- Do not change standard/local translator behavior or relax identifier/cardinality/content safety.

Acceptance criteria:
- Concurrent store registration for one chapter keeps exactly one authoritative instance.
- A rejected candidate/stage write prevents translation, inpaint, and render from running for that page and closes its candidate to the prior display/original state when ownership permits.
- Failed provider chunks do not leave durable `CANDIDATE_RUNNING` pages.
- Complete, uniquely identified translation records can survive missing/non-semantic framing such as a footer or context delta; missing, duplicate, unknown, rejected, or unsafe content remains a failure.
- Focused regression tests pass and a debug APK is installed for a clean rerun.

Constraints:
- Preserve generation/lease fencing and committed display authority.
- Never promote an unvalidated translation ID or incomplete natural prefix.
- Keep native memory bounded and avoid retrying downstream work after persistence rejection.
- Preserve unrelated worktree changes.

Stop conditions:
- Stop if safe parser recovery would require accepting missing/duplicate/unknown IDs or content validation failures.
- Stop if candidate cleanup cannot be fenced to the active generation/lease without risking committed output.
