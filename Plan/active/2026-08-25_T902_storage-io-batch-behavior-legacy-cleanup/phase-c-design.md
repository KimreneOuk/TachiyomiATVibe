# Phase C — Legacy Deletion with Safe Migration Helper

## Principle (Director-confirmed direction)

Delete anything not needed, not wired, or stale — but never strand a user
who already has batch artifacts. Sequence: upgrade -> verify -> sweep.

## User continuity tiers (verified in T901/T902)

1. ARTIFACTS-authority chapters: resume directly (fingerprint reuse).
2. LEGACY-manifest chapters: auto-upgrade on first store open
   (LegacyArtifactMigration.migrateChapter).
3. Flat-file-only chapters: Phase A flat decode fallback; migrated into
   artifacts on first touch.

## C-items

### C1. Dead code deletion
- Stage-transaction APIs in ChapterArtifactStore (beginStage /
  commitStagePayload / promoteCandidate / markCandidateStageFailed) +
  their tests. ~300 lines, zero production callers.
- tempFileNameFor (dead atomic-write helper).
- ChapterTranslationSummaryStore class + all remaining references
  (Phase B removed callers; Phase C deletes the class and sweeps stale
  X.summary.json files).

### C2. Migration helper ("clean up translation storage")
One-time on app upgrade + manual entry in Settings -> Storage.
Per chapter:
  a. Open store (triggers tier-2/3 upgrade to ARTIFACTS).
  b. Verify artifact rehydration: page count >= legacy file page count.
  c. On success: delete that chapter's redundant X.json + X.glossary.json.
  d. On failure: skip + log; keep legacy files (never strand).
Runs on IO, cancellable, reports freed space.

### C3. Always-on garbage sweeper (cheap, provable junk only)
- 0-byte / length<=2 flat files (post-cutover noise).
- Stale X.summary.json (dead concept after Phase B redirect).
- Orphaned X_images/ files backing no committed display bundle (B5 overlap).
- Orphaned .tmp artifacts in the managed tree.
- KEEP .corrupt quarantine files (only copy of user data).

### C4. Glossary final state
- Versioned atomic artifact-tree glossary is the only path; legacy
  X.glossary.json deletion rides C2 verification.

## Boundaries

- Do not delete any file that has not passed C2 verification or C3
  provable-junk rules.
- No behavioral change to resume/reuse (tiers 1-3 above stay intact).
