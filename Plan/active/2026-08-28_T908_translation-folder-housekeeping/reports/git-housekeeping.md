# Git Housekeeping Audit — TachiyomiAT

Date: 2026-08-28 (snapshot ~22:20-22:35 local). Read-only audit; no git state was modified.

**IMPORTANT CAVEAT — repo changed mid-audit.** While this audit was running, a concurrent
process committed the previously-uncommitted T907 work as `2085c03` on branch `t907/fix`
and switched the main worktree from `optimize_translation_finishing_page` to `t907/fix`.
Early inventory numbers (taken minutes before) differ slightly; all figures below reflect
the post-commit state unless explicitly noted.

---

## Summary

- **22 worktrees** total: 1 main checkout (KEEP), 1 recently-active integration checkout (KEEP),
  **7 SAFE TO PRUNE** (clean, tip is a commit-level ancestor of the active tip `t907/fix`),
  **6 prune-able with note** (clean, tip rebased but 100% patch-equivalent to content already in
  `t907/fix`), **7 NEEDS DECISION** (5 dirty, 2 with unique unmerged commits).
- **25 local branches**: only `main` is trivially merged into `main`. Local `main` is STALE
  (2026-08-17); the whole active line descends from it. **21 of 24 non-main branches have all
  their content preserved in the active tip `t907/fix`** (14 at commit level, 7 by patch-id
  equivalence after rebase). **2 branches carry unique unmerged commits**:
  `design_ai_org_roles` (5 commits) and `t904/repo-health` (1 docs commit).
- **13 remote branches**: only `origin/feat/reader-translation-flexibility` is merged into
  `origin/main`. `origin/master` is NOT divergent — it is strictly behind `origin/main`
  (0 ahead / 18 behind; fork point = origin/master tip `7e1d94f`). `origin/main` itself is
  severely stale: local `main` is **125 commits ahead** of it, and the active tip is ~65
  commits ahead of local `main` (~190 total).
- **Uncommitted work (task context)**: the 3 modified files + 2 untracked test paths described
  in the task context were **committed mid-audit** as `2085c03` on `t907/fix`. Verified against
  the T907 README contract: it is exactly the in-flight T907 Slice 1 + Slice 2 work. **KEEP.**
- `git stash list`: empty.
- Refs: 28 loose refs, 33 packed-refs entries; minor harmless duplication (both loose and
  packed: `heads/feat/npu-acceleration-and-hardware-discovery` and its remote-tracking ref).
  2 stray Codex checkpoint refs under `refs/codex/turn-diffs/` (agent clutter, decision).
  1 packed-only tag `refs/tags/archive/pre-cleanup/Pre-translation-feature` (keep).
- `*.rej` / `*.orig` scan (excluding `.git`, `build`, `.gradle`, `node_modules`): **none found**.

---

## Worktree inventory (22)

Merge column: containment of the worktree HEAD in the active tip `t907/fix` (`2085c03`).
**Zero worktree HEADs are merged into local `main`** — local `main` (ca716f7, 2026-08-17) is
65+ commits behind the active line, so the strict "merged into main" test classifies nothing;
the active tip `t907/fix` is used as the merge reference, which is the meaningful test here.

| # | Worktree (short) | Branch | HEAD | Date | Dirty | In t907/fix | Class |
|---|---|---|---|---|---|---|---|
| 1 | `<MAIN>` (Documents\...\TachiyomiAT-1.16.8-dev) | t907/fix | 2085c03 | 2026-08-28 | 1 (this report dir) | =tip | **KEEP** (current checkout) |
| 2 | gemini\analyze_batch_translation_flow | analyze_batch_translation_flow | 754ccce | 2026-08-24 | 0 | ancestor | **SAFE TO PRUNE** |
| 3 | gemini\audit_batch_translation_logic | audit_batch_translation_logic | da1e9d6 | 2026-08-21 | 0 | ancestor | **SAFE TO PRUNE** |
| 4 | gemini\audit_translation_pipeline_architecture | audit_translation_pipeline_architecture | 0c0c2b9 | 2026-08-21 | 0 | ancestor | **SAFE TO PRUNE** |
| 5 | gemini\debug_gemini_translation_retry | debug_gemini_translation_retry | 754ccce | 2026-08-24 | **10 modified** | ancestor | **NEEDS DECISION** (dirty) |
| 6 | gemini\debug_translation_pipeline_robustness | debug_translation_pipeline_robustness | 754ccce | 2026-08-24 | **25 (16 mod + 9 staged docs/ai_org)** | ancestor | **NEEDS DECISION** (dirty) |
| 7 | gemini\design_ai_org_roles | design_ai_org_roles | c28e4f7 | 2026-08-25 | 0 | **NO** (5 unique commits) | **NEEDS DECISION** (unique content) |
| 8 | gemini\document_batch_translation_flow | document_batch_translation_flow | 7c78a46 | 2026-08-25 | 0 | ancestor | **SAFE TO PRUNE** |
| 9 | gemini\optimize_translation_finishing_page | (detached 3600b11) | 3600b11 | 2026-08-23 | **1 untracked** Plan/2026-08-24-batch-translation-followups/ | ancestor | **NEEDS DECISION** (untracked plan dir) |
| 10 | gemini\read_agents_documentation | read_agents_documentation | da1e9d6 | 2026-08-21 | 0 | ancestor | **SAFE TO PRUNE** |
| 11 | traycer\feat-batch-translation-reliability | fix/reader-entry-anr | da1e9d6 | 2026-08-21 | **16 (15 mod + 1 untracked Plan dir)** | ancestor | **NEEDS DECISION** (dirty) |
| 12 | traycer\t904-integration | t904/integration | c924bbd | 2026-08-28 | 0 | ancestor | **KEEP** (active today; integration line) |
| 13 | traycer\t904-pause-durable | t904/pause-durable | da56c68 | 2026-08-27 | 0 | NO (patch-equiv 0 unique) | **PRUNE-ABLE** (patch-merged) |
| 14 | traycer\t904-repo-health | t904/repo-health | 99c11a4 | 2026-08-26 | 0 | NO (1 unique docs commit) | **NEEDS DECISION** (unique content) |
| 15 | traycer\t904-review | (detached 56179d7) | 56179d7 | 2026-08-27 | 0 | ancestor | **SAFE TO PRUNE** |
| 16 | traycer\t904-semantic-retry | t904/semantic-retry | 1f62fe0 | 2026-08-26 | 0 | NO (patch-equiv 0 unique) | **PRUNE-ABLE** (patch-merged) |
| 17 | traycer\t904-shared-pacing-governor | t904/shared-pacing-governor | dcd29c8 | 2026-08-26 | 0 | NO (patch-equiv 0 unique) | **PRUNE-ABLE** (patch-merged) |
| 18 | traycer\t904-ui-integration | t904/ui-integration | e18ed84 | 2026-08-27 | 0 | ancestor | **SAFE TO PRUNE** |
| 19 | traycer\t906-fix-a1 | t906/fix-area1 | 1a0db7b | 2026-08-28 | **1 modified** (RollingAutoCoordinatorTest.kt) | NO (committed part patch-equiv) | **NEEDS DECISION** (dirty) |
| 20 | traycer\t906-fix-a2 | t906/fix-area2 | ce75b7b | 2026-08-28 | 0 | NO (patch-equiv 0 unique) | **PRUNE-ABLE** (patch-merged) |
| 21 | traycer\t906-fix-a3 | t906/fix-area3 | 2bd73b3 | 2026-08-28 | 0 | NO (patch-equiv 0 unique) | **PRUNE-ABLE** (patch-merged) |
| 22 | traycer\t906-fix-a4 | t906/fix-area4 | ba24abc | 2026-08-28 | 0 | NO (patch-equiv 0 unique) | **PRUNE-ABLE** (patch-merged) |

Dirty-worktree details (uncommitted content that blocks pruning):

- **debug_gemini_translation_retry** (10 M): Downloader.kt, MangaScreenModel.kt, ChapterTranslator.kt,
  ChapterQueueConflictDetection.kt, Translation.kt, GeminiTranslator.kt, TranslationRetry.kt,
  ChapterQueueConflictDetectionTest.kt, GeminiRequestPayloadTest.kt, TranslationRetryTest.kt.
  Overlaps T907/T904 scope (Downloader + MangaScreenModel); looks like 08-24 debug experiments,
  likely superseded — confirm before discard.
- **debug_translation_pipeline_robustness** (25): 16 modified (MangaScreen.kt, TranslationProgressSheet.kt,
  MangaScreenModel.kt, ReaderTranslationFeedback.kt, ChapterTranslator.kt, TranslationPipeline.kt,
  AotReportBubbleFill.kt, GeminiTranslator.kt, + tests) **plus 9 STAGED additions** under
  `docs/ai_org/` (README, 8 role files, 8 template files — appears to be the AI-org docs set,
  of which some content may only exist here).
- **gemini\optimize_translation_finishing_page** (detached): untracked
  `Plan/active/2026-08-24-batch-translation-followups/`. A folder of the same name exists in the
  main checkout's Plan/active — likely a duplicate; diff before removal.
- **feat-batch-translation-reliability / fix/reader-entry-anr** (16): 15 modified
  (ReaderViewModel.kt, ChapterTranslationStore.kt, ChapterTranslator.kt, TranslationManager.kt,
  TranslationPipeline.kt, TranslationScheduler.kt, AiTranslationRetryController.kt,
  ContextualResponseParser.kt, ContextualTranslationBatch.kt, TranslationPrompts.kt, + 5 tests)
  plus untracked `Plan/active/2026-08-21-translation-cancel-protocol-lifecycle/`. Substantial
  uncommitted translation work — must be triaged, not discarded.
- **t906-fix-a1** (1): modified `app/src/test/java/eu/kanade/translation/scheduling/RollingAutoCoordinatorTest.kt`.

---

## Local branches (25)

`ahead` = commits in branch not in local `main`. `unique` = commits whose *patch* is not in
`t907/fix` (via `git cherry`) — 0 unique means content is fully preserved in the active line
even though commit SHAs differ (rebase/cherry-pick lineage).

| Branch | HEAD | Last activity | Ahead of main | Unique vs t907/fix | Verdict |
|---|---|---|---|---|---|
| main | ca716f7 | 2026-08-17 | 0 | — | KEEP (stale base; 125 ahead of origin/main — push decision) |
| t907/fix (current) | 2085c03 | 2026-08-28 | 65 | — | KEEP (active line) |
| optimize_translation_finishing_page | b65a21f | 2026-08-28 | 64 | 0 | Deletable — ancestor of t907/fix; now checked out NOWHERE (freed when main worktree switched) |
| t904/integration | c924bbd | 2026-08-28 | 56 | 0 | Deletable after worktree removal (KEEP while active) |
| t906/fix-area1 | 1a0db7b | 2026-08-28 | 51 | 0 | Blocked: worktree dirty |
| t906/fix-area2 | ce75b7b | 2026-08-28 | 51 | 0 | Deletable after worktree removal |
| t906/fix-area3 | 2bd73b3 | 2026-08-28 | 51 | 0 | Deletable after worktree removal |
| t906/fix-area4 | ba24abc | 2026-08-28 | 51 | 0 | Deletable after worktree removal |
| t904/ui-integration | e18ed84 | 2026-08-27 | 47 | 0 | Deletable after worktree removal (source of T907 regression, but preserved in lineage) |
| t904/pause-durable | da56c68 | 2026-08-27 | 46 | 0 | Deletable after worktree removal |
| t904/semantic-retry | 1f62fe0 | 2026-08-26 | 45 | 0 | Deletable after worktree removal |
| t904/shared-pacing-governor | dcd29c8 | 2026-08-26 | 44 | 0 | Deletable after worktree removal |
| t904/repo-health | 99c11a4 | 2026-08-26 | 44 | **1** | **KEEP / DECISION** — unique docs commit "docs: record T904 repository health" |
| t904/review | 926ae00 | 2026-08-26 | 43 | 0 | Deletable (no worktree) |
| design_ai_org_roles | c28e4f7 | 2026-08-25 | 41 | **5** | **KEEP / DECISION** — diverged docs line; local is 4 ahead of its origin counterpart (push first) |
| document_batch_translation_flow | 7c78a46 | 2026-08-25 | 40 | 0 | Deletable after worktree removal |
| analyze_batch_translation_flow | 754ccce | 2026-08-24 | 39 | 0 | Deletable after worktree removal |
| debug_gemini_translation_retry | 754ccce | 2026-08-24 | 39 | 0 | Blocked: worktree dirty |
| debug_translation_pipeline_robustness | 754ccce | 2026-08-24 | 39 | 0 | Blocked: worktree dirty |
| fix/reader-entry-anr | da1e9d6 | 2026-08-21 | 36 | 0 | Blocked: worktree dirty |
| audit_batch_translation_logic | da1e9d6 | 2026-08-21 | 36 | 0 | Deletable after worktree removal |
| read_agents_documentation | da1e9d6 | 2026-08-21 | 36 | 0 | Deletable after worktree removal |
| audit_translation_pipeline_architecture | 0c0c2b9 | 2026-08-21 | 35 | 0 | Deletable after worktree removal |
| feat/batch-translation-reliability | e89c2af | 2026-08-21 | 33 | 0 | Deletable (no worktree) |
| feat/npu-acceleration-and-hardware-discovery | 4868cd2 | 2026-08-19 | 25 | 0 | Deletable (no worktree; identical commit exists on origin) |

**Branches fully merged into local `main`: only `main` itself.** Content-merged into the active
tip: 14 branches at commit level + 7 at patch level = 21 of 24. Unique unmerged: 2
(design_ai_org_roles, t904/repo-health).

---

## Remote branches (origin; 12 + HEAD)

`ahead` = commits vs `origin/main` (c548502, 2026-06-19 — very stale).

| Remote branch | HEAD | Date | vs origin/main | In local main? | Verdict |
|---|---|---|---|---|---|
| origin/main | c548502 | 2026-06-19 | — | contained in local main | **STALE — local main is 125 ahead; push decision** |
| origin/master | 7e1d94f | 2026-06-15 | 0 ahead / 18 behind (strictly behind main, NOT divergent) | contained | Deletable candidate (or keep for upstream convention) |
| origin/feat/reader-translation-flexibility | f494e00 | 2026-06-19 | merged (0 ahead) | contained | Deletable (only fully-merged remote branch) |
| origin/Quality-Improvement | 1a6f082 | 2026-07-12 | 38 ahead | **contained** | Deletable after pushing local main |
| origin/codex/checkpoint-text-overlay-inconsistency | 62af4a4 | 2026-07-14 | 77 ahead | **contained** | Deletable after pushing local main |
| origin/fix/translation-race-p0-quality | 0dc8300 | 2026-07-13 | 75 ahead | **contained** | Deletable after pushing local main |
| origin/fix-translation-pipeline | f079702 | 2026-07-16 | 83 ahead | **contained** | Deletable after pushing local main |
| origin/unified-translation-pipeline-cp0-cp10 | 2b6f264 | 2026-07-18 | 109 ahead | **contained** | Deletable after pushing local main |
| origin/audit_extension_loading_lifecycle | b3fd10f | 2026-08-17 | 119 ahead | **contained** (merged into local main per its merge commit) | Deletable after pushing local main |
| origin/feat/npu-acceleration-and-hardware-discovery | 4868cd2 | 2026-08-19 | 150 ahead | same SHA as local branch | Mirror of local; keep or delete both together |
| origin/design_ai_org_roles | 38303c0 | 2026-08-24 | 162 ahead | **NOT contained** | Local branch is 4 commits ahead of it, remote 0 ahead — push local to update, then decide |
| origin/optimize_translation_finishing_page | c28e4f7 | 2026-08-25 | 166 ahead | **NOT contained** (diverged: 5 commits each way vs local b65a21f) | The 5 remote-only commits exist locally as design_ai_org_roles head — no data loss if deleted after design_ai_org_roles is pushed |

**No remote branch holds content that is absent locally**, provided `design_ai_org_roles`
(and ideally `t904/repo-health`) is pushed before any remote cleanup.

### origin/main vs origin/master divergence (task 3)
- `origin/main..origin/master` = **0** (master ahead of main: none)
- `origin/master..origin/main` = **18**
- Fork point = `7e1d94f` ("Add reader translation diagnostics", 2026-06-15) = origin/master tip.
- Conclusion: **master is strictly behind main; there is no divergence.** `origin/HEAD -> origin/main`.

---

## Uncommitted work assessment (task 4)

The task context reported, on branch `optimize_translation_finishing_page`:
- modified: `MangaScreenModel.kt`, `ReaderViewModel.kt`, `TranslationManager.kt`
- untracked: `app/src/test/java/eu/kanade/tachiyomi/ui/manga/`,
  `app/src/test/java/eu/kanade/translation/TranslationManagerDownloadFailureRecoveryTest.kt`

**Finding: this work was committed during this audit** as
`2085c03` — *"fix(translation): auto-start batch downloads and self-clear download-failure state"*
— on a new branch `t907/fix`, now checked out in the main worktree (previous tip b65a21f,
"docs(plan): record T907 batch download autostart recovery task contract", is its parent).

Commit contents (5 files, +335 / −9) match the task-context file list exactly:
- `MangaScreenModel.kt` (+42): T907 Slice 1 — translation-driven chapter downloads pump
  unconditionally (`startDownloads()`) instead of relying on stock `wasEmpty` auto-start.
- `ReaderViewModel.kt` (+3): manual reader entry calls
  `translationManager.clearStaleDownloadFailedRequest(chapter.id)`.
- `TranslationManager.kt` (+17): new `clearStaleDownloadFailedRequest()` — drops only a stale
  `DOWNLOAD_FAILED` pending request at manual/rolling-auto entry; live phases untouched
  (T907 Slice 2, self-clearing failed wedge).
- `EnqueueTranslationDownloadsTest.kt` (+73, new) and
  `TranslationManagerDownloadFailureRecoveryTest.kt` (+209, new): the focused tests the README requires.

Cross-check against `Plan/active/2026-08-28_T907_batch-download-autostart-recovery/README.md`:
- Contract says: work on branch `t907/fix`; Slice 1 = MangaScreenModel batch+retry paths with
  unconditional `startDownloads()`; Slice 2 = TranslationManager self-clearing DOWNLOAD_FAILED
  (requeue reset, manual/auto entry clear); every slice adds focused tests; do not modify stock
  Mihon downloader behavior. **The commit matches the contract file-for-file and slice-for-slice.**

**Classification: in-flight T907 work — KEEP (now committed, pending gates + review per README).**
The only remaining untracked path in the main worktree is
`Plan/active/2026-08-28_T908_translation-folder-housekeeping/` — this audit's own report folder,
expected and fine.

The uncommitted changes in the OTHER worktrees (see worktree table) are separate work and are
NOT part of T907; they are listed under Needs-decision items.

---

## Cleanup command proposal (NOT EXECUTED)

Paths abbreviated below —
`G` = `C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev`,
`T` = `C:/Users/User/.traycer/worktrees/kimreneouk__tachiyomiatvibe`.
Run from the repo root. `git branch -D` (not `-d`) is required because local `main` is stale and
no branch is merged into it; safety comes from the verified containment in `t907/fix` above.

### Phase A — worktree remove: SAFE TO PRUNE (commit-level, 7)
```bash
git worktree remove "G/analyze_batch_translation_flow"
git worktree remove "G/audit_batch_translation_logic"
git worktree remove "G/audit_translation_pipeline_architecture"
git worktree remove "G/document_batch_translation_flow"
git worktree remove "G/read_agents_documentation"
git worktree remove "T/t904-review"
git worktree remove "T/t904-ui-integration"
```

### Phase B — worktree remove: PRUNE-ABLE (patch-level merged into t907/fix, 6)
```bash
git worktree remove "T/t904-pause-durable"
git worktree remove "T/t904-semantic-retry"
git worktree remove "T/t904-shared-pacing-governor"
git worktree remove "T/t906-fix-a2"
git worktree remove "T/t906-fix-a3"
git worktree remove "T/t906-fix-a4"
```

### Phase C — branch -D (only AFTER the corresponding worktree is removed; never t907/fix)
```bash
# No worktree currently:
git branch -D optimize_translation_finishing_page feat/batch-translation-reliability feat/npu-acceleration-and-hardware-discovery t904/review
# After Phase A removals:
git branch -D analyze_batch_translation_flow audit_batch_translation_logic audit_translation_pipeline_architecture document_batch_translation_flow read_agents_documentation t904/ui-integration
# After Phase B removals:
git branch -D t904/pause-durable t904/semantic-retry t904/shared-pacing-governor t906/fix-area2 t906/fix-area3 t906/fix-area4
# Optional, only if also pruning the KEEP worktree t904-integration:
#   git worktree remove "T/t904-integration" && git branch -D t904/integration
```

### Phase D — remote prune (housekeeping of stale remote-tracking refs)
```bash
git remote prune origin --dry-run   # inspect first
git remote prune origin             # removes tracking refs for branches deleted on the server
```

### NOT included — see warning block below (dirty worktrees, unique-content branches, remote deletions, codex refs)

> ### WARNING — NEEDS DECISION (do not prune until resolved)
> 1. **gemini\debug_gemini_translation_retry** — 10 uncommitted modified files overlapping T907/T904
>    scope (Downloader.kt, MangaScreenModel.kt, ...). Likely stale debug experiments from 08-24,
>    superseded by 2085c03 — confirm, then `git -C <wt> checkout -- .` (discard) or commit to a
>    side branch before `git worktree remove --force`.
> 2. **gemini\debug_translation_pipeline_robustness** — 16 modified + 9 STAGED `docs/ai_org/**`
>    additions. Verify the docs/ai_org set exists in the main checkout (it does under
>    `docs/ai_org/` on the active line — diff before discarding), then force-remove.
> 3. **gemini\optimize_translation_finishing_page (detached)** — only holds untracked
>    `Plan/active/2026-08-24-batch-translation-followups/`. A same-named folder exists in the main
>    checkout; diff and delete the copy, then remove worktree.
> 4. **traycer\feat-batch-translation-reliability (fix/reader-entry-anr)** — 15 uncommitted modified
>    translation files + untracked `Plan/active/2026-08-21-translation-cancel-protocol-lifecycle/`.
>    Substantial unmerged work — triage (commit to a side branch or consciously discard) before
>    any removal.
> 5. **traycer\t906-fix-a1** — 1 uncommitted test modification; triage, then remove + delete
>    `t906/fix-area1` (its committed content is already patch-merged into t907/fix).
> 6. **branch design_ai_org_roles** — 5 commits diverged from the active line (docs), local 4
>    ahead of origin. PUSH IT (`git push origin design_ai_org_roles`) before any cleanup; then
>    decide merge vs archive.
> 7. **branch t904/repo-health** — 1 unique docs commit ("docs: record T904 repository health").
>    Push or cherry-pick into the active line before deleting.
> 8. **Local `main` is stale (2026-08-17) and origin/main is staler (2026-06-19, 125 behind).**
>    Recommend: after T907 gates/review, fast-forward/push main from the active line so future
>    "merged into main" checks become meaningful again. Director decision.
> 9. **Remote branch deletions** (all content exists locally): `feat/reader-translation-flexibility`
>    (merged) and `master` (strictly behind main) are safe now; the other 8 only after local main
>    (+ design_ai_org_roles, t904/repo-health) is pushed. All are Director decisions since they
>    affect the shared remote.
> 10. **refs/codex/turn-diffs/checkpoints/**\* — 2 stray Codex-agent checkpoint refs (clutter).
>     Deletable via `git update-ref -d <refname>`; harmless to keep.
> 11. **Branch deletions above are safe only while `t907/fix` (or its merged successor) is
>     preserved.** Do not delete `t907/fix` or reset the main worktree until T907 is merged and
>     pushed.

---

## Other checks (task 6)

- `git stash list`: **empty**.
- Refs duplication: 28 loose ref files + 33 packed-refs lines (31 refs + header/peeled lines).
  Only duplication: `refs/heads/feat/npu-acceleration-and-hardware-discovery` and
  `refs/remotes/origin/feat/npu-acceleration-and-hardware-discovery` exist both loose and packed —
  harmless; `git pack-refs --all` would consolidate (not executed).
  Packed-only: 2 `refs/codex/turn-diffs/checkpoints/*` refs and tag
  `refs/tags/archive/pre-cleanup/Pre-translation-feature`.
- `*.rej` / `*.orig` repo-wide (excluding `.git`, `build`, `.gradle`, `node_modules`): **none**.
