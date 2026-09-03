# Repository health — T919 PageTextRenderer removal

## Status: YELLOW

The requested removal can proceed in this worktree, but it must preserve and
isolate existing untracked T918 work.  Do not clean, stash, reset, switch
branches, or stage by broad path/glob.

## Snapshot — 2026-09-03

- Repository root: `C:/Users/User/Documents/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev/TachiyomiAT-1.16.8-dev`
- HEAD: `b8d724f5d794cb6564fb24dd4cd3bfac3932c4b3` (`checkpoint/t917-release`, local `main`)
- Current branch: `fix/batch-retry-affordance`; HEAD is attached, not detached.
- The branch has no configured upstream.  It cannot currently report normal
  ahead/behind status for its own branch.
- The locally cached `origin/main` is an ancestor of HEAD: `0 behind / 227
  ahead` for `origin/main...HEAD`.  No fetch was performed, so remote changes
  not already represented by the cached ref are unknown.
- No merge, rebase, cherry-pick, revert, or bisect state was found.
- Five registered worktrees exist; this worktree is the checkout of
  `fix/batch-retry-affordance`.  The T912 investigation is isolated in its own
  worktree, so it is not a collision risk.
- No tracked or staged modifications exist.

## Untracked work to preserve

The following untracked paths already exist and must not be staged or modified
unless explicitly part of the relevant commit:

- `Plan/active/2026-09-03_T918_batch-retry-affordance/README.md`
- `app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModelCancelledBatchReconciliationTest.kt`
- `app/src/test/java/eu/kanade/translation/coexistence/T918CancelledBatchRestartTest.kt`
- `app/src/test/java/eu/kanade/translation/ui/T918SheetRetryTruthTest.kt`

T919 planning/audit documents are also untracked.  The only new untracked file
from this safety check is this report.

## Safe implementation procedure

1. Keep the current branch and worktree.  Do not create, move, or check out a
   branch as part of T919.
2. Before each implementation slice, run `git status --short` and retain the
   four T918 paths above unchanged.
3. Make only the planned T919 changes.  Stage each file explicitly with
   `git add -- <exact-path>`; never use `git add .`, `git add -A`, or a broad
   `Plan/active` path.
4. Commit 1 contains only the migrated/added reader-overlay tests (and only
   any T919 test documentation the implementation explicitly owns).  Inspect
   `git diff --cached --check` and `git diff --cached --stat` before committing.
5. Commit 2 contains only the unchanged `ComponentClipCache` extraction,
   `PageTextRenderer` and dedicated-test deletion, test rename/reference
   cleanup, and corresponding owned T919 documentation.  Again inspect the
   staged diff and run the planned verification before committing.
6. After each commit, run `git status --short`, `git show --stat --oneline
   HEAD`, and a targeted `git diff -- <T918-paths>` check.  The T918 files must
   remain untracked and untouched throughout.
7. At completion, confirm two new T919 commits, a clean tracked index, and
   only the expected untracked T918/T919 task artifacts.  Pushing needs a
   separate decision because this branch has no upstream and the remote state
   was not refreshed.

## Conclusion

Proceed on the current worktree with explicit path staging and two isolated
commits.  Repository state is cautionary because the shared checkout contains
untracked T918 work and the branch has no upstream, not because of an active
Git operation or tracked conflict.
