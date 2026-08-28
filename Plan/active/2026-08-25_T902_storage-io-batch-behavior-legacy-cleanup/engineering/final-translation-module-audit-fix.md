# Final translation-module audit fix

## Scope and result

Implemented the final audit's bounded F-B1 delete/reset ownership fix and the
cheap F-M1 verification short-circuit on top of the cumulative Phase C delta.
No reader, scheduler, provider-lane, prompt, or translation-pipeline behavior
was retuned, and no commit was created.

## F-B1: chapter-authority deletion

`TranslationManager.deleteTranslation` now performs a read-only, IO-dispatched
capture before teardown using `ChapterArtifactDeletionPlan.capture`:

1. It validates the chapter manifest and records the authority, manifest
   siblings, artifact root, supported migration metadata, and SHA-256/length
   identities before the existing cancellation, worker join, store defunct,
   and stream-registry clear sequence.
2. After that unchanged sequence, it deletes the manifest plus owned `.tmp`,
   `.bak`, base `.corrupt`, and discovered `.corrupt.*` siblings. Only after
   every authority sibling is gone does it delete the chapter artifact tree,
   including committed/candidate snapshots and artifact glossary documents.
3. For supported metadata, `INTENT` admits the exact canonical source or
   derived legacy glossary name; `PRESERVED` admits only the exact recorded
   resolved name. Each candidate is re-read immediately before deletion and
   is deleted only when SHA-256 and byte length match. Last-modified time is
   diagnostic metadata only. Missing, mismatched, malformed, unsupported, or
   externally inserted files remain untouched.
4. If manifest-sibling deletion fails, the plan stops before tree deletion and
   reports a retryable failure. If tree deletion fails after authority removal,
   the remaining tree is orphaned but cannot be rehydrated because no authority
   manifest/sibling remains; the failure is logged for later retry. Companion
   images are retired only after authority removal and through the existing
   stream-registry retirement barrier. Durable status cache is cleared after
   teardown.

Chapter-wide `resetChapterOcrData` continues to delegate to
`deleteTranslation`. Reader/per-page `resetOcrData` and `deletePageTranslation`
were not widened: they still call `store.deletePage` and retire only the page's
companion images.

The new `ChapterArtifactDeletionTest` covers raw deletion of the authority tree
and manifest siblings, matching resolved source deletion, mismatched glossary
retention, externally inserted canonical-source retention, and a URI-style
backend deletion failure that retains the authoritative tree for retry.

## F-M1: verified-health fast path

`ChapterTranslationStore.migrateArtifactManifest` still runs preservation
reconciliation first. It now calls `verifyLegacyArtifactHealth` only when the
marker is absent, health is not `VERIFIED`, or source/glossary preservation is
still `INTENT`/`PRESERVED`. A `VERIFIED` marker with no pending preservation
state skips the repeated page/image probes and manifest rewrite. Pending
preservation states remain eligible for retry and are not hidden by the fast
path.

## Verification

- Focused deletion/storage suite: 64 tests passed, 0 failures/errors.
- Final restart-fixture rerun: `ChapterArtifactDeletionTest` 2 tests passed,
  0 failures/errors, including the post-delete authority recapture check.
- Deterministic translation wildcard rerun with `--rerun-tasks`: 964 tests,
  exactly one known independent `AotReportBubbleFillTest` pixel mismatch; the
  earlier reader-teardown ordering failure did not reproduce.
- Full gate:
  `./gradlew.bat spotlessCheck :app:testStandardDebugUnitTest
  :domain:testReleaseUnitTest --no-daemon --console=plain` using the Android
  Studio JBR under Git Bash. Spotless passed; domain release tests passed (68);
  app standard-debug completed 1,024 tests with exactly the same known AOT
  mismatch.
- `git diff --check`: clean.

## Deviations and remaining risks

The implementation uses a separate deletion plan rather than opening a new
store outside the active registry, so deletion cannot create a competing store
or trigger artifact rehydration. A newer/unsupported manifest schema or any
SAF/provider deletion failure fails closed and retains recovery data. External
or identity-mismatched legacy files are deliberately retained; no ownership is
inferred from filename or mtime. Companion-image cleanup remains conditional
on authority removal and stream retirement, so a failed authority deletion may
leave images until a later successful retry.
