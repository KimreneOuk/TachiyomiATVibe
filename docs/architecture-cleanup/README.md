# Architecture cleanup: laptop handoff

**Unfinished checkpoint: Phase 3, Slice S5. The current source has known compile and lifecycle blockers. Do not treat this branch as a release or passing build.**

S1?S4 are independently reviewed and committed through `ff21fd15`. S5 is preserved exactly as requested for transfer; no S5 Gradle commands or tests have run. S6 and Phases 4?8 remain. Implementation agents were told to freeze for this transfer.

## Continue on the other laptop

```sh
git fetch origin
git switch architecture-cleanup
git pull --ff-only origin architecture-cleanup
```

For a fresh checkout:

```sh
git clone --branch architecture-cleanup https://github.com/KimreneOuk/TachiyomiATVibe.git
```

Open this file and the linked current review before resuming. Continue on one laptop at a time to avoid concurrent branch changes. This is a migration checkpoint, not authorization to ignore the pending review or run the rejected launchers.

## Start here

- [Transfer status and continuation constraints](artifacts/laptop-handoff/index.md)
- [Current coordinator code review, including r1 blockers](artifacts/phase-3-implementation/tickets/owner-cutover/review/coordinator/index.md)
- [Final independent re-review received at migration freeze](artifacts/phase-3-implementation/tickets/owner-cutover/review/migration-recheck/index.md)
- [Independent GLM S5 review](artifacts/phase-3-implementation/tickets/owner-cutover/review/index.md)
- [S5 preparation and mechanism decisions](artifacts/phase-3-implementation/tickets/owner-cutover/preparation/index.md)
- [Phase 3 implementation plan](artifacts/phase-3-planning/plan/index.md)
- [Phase 3 implementation tickets](artifacts/phase-3-implementation/index.md)
- [Full architecture cleanup roadmap](artifacts/translation-simplicity-investigation/phases/index.md)
- [Standing execution policy](artifacts/execution-policy/index.md)
- [Validation audit and cost controls](artifacts/phase-3-implementation/validation-audit/index.md)
- [Architecture investigation report](../../TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md)
- [All artifact pages](CATALOG.md)
- [Source/export SHA256 inventory](MANIFEST.json)

## What is included

`artifacts/` is a byte-for-byte snapshot of this epic's locally available artifact tree: all plans, tickets, reviews, `.comments` threads, earlier investigations and attachments. Older plans and approvals are historical; current findings govern. Ticket statuses were preserved, not promoted to completion.

`validation/` preserves the available small S1?S4 scratch evidence, logs, XML, helper scripts and watchdog selftest source (generated `.class` omitted). These are historical results, not S5 validation. See the per-ticket results artifacts for which attempts are accepted, superseded, failed or missing evidence.

The artifact mirror keeps original contents, including historical absolute Windows paths and Traycer rootless links. Use the catalog for GitHub navigation. To restore native Traycer artifact locations, copy the **contents** of this `artifacts/` directory into your destination task's artifact root (the original task id is `699de408-00ac-4fe5-a903-b541c23063cc`). Compare existing destination artifacts before replacing them; newer cloud/local edits must not be overwritten blindly. Git access to these documents does not depend on restoring Traycer state.

## Watchdog and validation caveats

The approved historical helper and init script are in `validation/phase-3-s1/2026-10-15T20-08-45Z/`. The helper was tested on Windows/Git Bash, uses PowerShell process inspection and requires an init-script copy in each fresh evidence directory. Its repo-name process filter assumes `tachiyomiATVibe-inpainting-modes-redesign`; inspect/adapt that identity check on a differently named checkout. Do not run copied old launchers with original absolute paths.

The helper's 300-second test timer starts at test execution; a separate verified setup/compilation watchdog is still required. The latest S5 proposed wrapper-only watchdog and generic Debug tasks were rejected and must not be used. Use exact StandardDebug and DevDebug tasks, focused classes and fresh immutable attempt directories; do not rerun already-green checks without semantic changes.

Android SDK settings (`local.properties`), credentials/profile sessions, model binaries, Gradle caches and built APKs stay local. Reconfigure/provision them on the destination laptop using the repository's existing setup/model documentation. No keys or machine configuration were intentionally included.
