# T902 Phase C2 implementation

## Result

Implemented the approved lazy, one-way legacy rescue and authority cutover on
top of the uncommitted C1 delta. A chapter is now rescued on open under a
per-chapter lock, its complete artifact graph is published and re-read, and
only then is the manifest switched to `ARTIFACTS`. After that switch, flat
JSON is never a page/status fallback. Legacy source and glossary preservation
is retryable and identity-checked; failed preservation leaves the source
untouched. No C3 verification/deletion, C4 mutation admission/writer removal,
or C5 dead-code deletion was implemented.

## Changes

- `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt:1988-2200`
  - Normalized preserved `.migrated` names when reopening a source and added a
    per-chapter lock keyed by the parent file path/URI and translation name.
  - Reads legacy JSON and glossary bytes once, records source/glossary
    identities and build version, and sends the complete snapshot to the
    artifact rescue path.
  - Rehydrates committed/candidate snapshots from the registry-owned artifact
    store. Flat pages are used only while authority is `LEGACY`; an
    `ARTIFACTS` chapter has no flat fallback. Artifact opens retry preservation
    marker recovery without resyncing legacy bytes.

- `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt:70-151,262-305,1262-1501`
  - Replaced identity resync with a one-way rescue transaction. It publishes a
    `LEGACY` staging manifest, materializes every page's committed snapshot
    (including C1 provisional downgrade-visible pages), publishes/re-reads the
    glossary and each page pointer, validates the exact page-key graph, and
    publishes one final `ARTIFACTS` manifest.
  - Keeps the provisional committed pointer through candidate open, failure,
    cancellation, and recovery; candidate promotion remains the point at
    which a validated successor can replace it.
  - Added deterministic, bounded source/glossary preservation candidates.
    Existing matching targets are adopted by identity; mismatches are skipped;
    rename results are re-read so a crash/provider failure after rename is
    recovered on the next open. Unsupported C1 metadata fails closed and never
    drives preservation.

- `app/src/main/java/eu/kanade/translation/artifact/ChapterDocumentIo.kt:21-104,168-241`
  - Added the `RenameResult` destination-admission primitive. The filesystem
    implementation uses `Files.move` without `REPLACE_EXISTING`, so an existing
    destination is reported as `DESTINATION_EXISTS` rather than overwritten;
    URI/SAF backends without a raw filesystem path report `UNSUPPORTED` and
    leave both names unchanged. Atomic publication, backup recovery, and
    quarantine all use this primitive; there is no unchecked `renameTo` or
    timestamp fallback.
  - Backup recovery quarantines a corrupt primary under deterministic bounded
    `.corrupt`/digest/counter names, retains the source on collision,
    unsupported admission, or exhaustion, and restores the backup only after
    no-replace admission confirms the primary is absent.

- `app/src/main/java/eu/kanade/translation/TranslationManager.kt:464-513,541-700`
  - Durable status caches only successful non-null states. Probe/open and
    registry transitions clear the cache, while null/recoverable rescue
    results remain retryable in the same manager.
  - Reader and direct artifact reads use `ActiveChapterStoreRegistry` stores;
    artifact status/page reads do not consult flat JSON. Corrupt flat reads use
    the same no-replace, bounded quarantine primitive and retain the corrupt
    source when the backend cannot admit a destination safely.

## Review-fix design (C2 F1/F2)

- Preservation ownership is content identity only: SHA-256 (case-insensitive)
  plus byte length must match before a pre-existing or post-rename target is
  adopted. `lastModifiedMs` remains diagnostic metadata and is deliberately
  excluded from adoption, so a provider changing mtime during rename cannot
  lose a valid source/glossary copy.
- Every externally owned source/glossary/quarantine move goes through
  `ChapterDocumentIo.renameNoReplace`. A raw filesystem-backed `UniFile`
  receives an OS no-replace move; a SAF/URI backend that cannot provide that
  guarantee returns `UNSUPPORTED`, preserving the source and making rescue or
  quarantine retryable. The store-owned temp/primary/backup publication path
  is deliberately separate: `ChapterDocumentIo.renameOwned` uses the existing
  atomic swap semantics on both raw paths and URI-backed SAF. An externally
  inserted destination is never deleted or overwritten.
- Candidate names are deterministic and bounded (`.corrupt`, digest sibling,
  then `.1` through `.16`). Collision exhaustion returns `null`/`INTENT` with
  the source retained; it does not fall back to an unchecked or random rename.
  Atomic backup recovery likewise keeps both the corrupt primary and backup if
  quarantine or restore admission cannot be made safely.

## Tests

Added or extended fixtures in:

- `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactStoreTest.kt`
  - source and glossary target collisions, matching-target adoption after
    rename-before-marker, mtime-changing rename adoption, deterministic
    destination races/exhaustion, unsupported-backend source retention,
    pre-cutover snapshot publication failure, incomplete provisional committed
    snapshots, and corrupt-manifest quarantine collision; existing candidate
    cancel/failure/recovery and artifact restart coverage.
- `app/src/test/java/eu/kanade/translation/ChapterTranslationStoreArtifactMigrationTest.kt`
  - post-cutover restart rehydration with the original flat source moved to
    `.migrated`, no flat fallback, and provisional partial-page visibility.
- `app/src/test/java/eu/kanade/translation/TranslationManagerArtifactReadTest.kt`
  - fresh-manager artifact reads/status, in-flight warning status, legacy flat
    compatibility, corrupt quarantine collision, and same-manager retry after
    a transient missing input.
- `app/src/test/java/eu/kanade/translation/artifact/FakeChapterDocumentIo.kt`
  - no-replace rename, mtime-changing rename, and deterministic destination
    race-injection seams.
- `app/src/test/java/eu/kanade/translation/artifact/AtomicChapterDocumentsTest.kt`
  - backup recovery and corrupt-primary quarantine preserve both originals when
    every destination admission races or the bounded candidate set is full.
- `app/src/test/java/eu/kanade/translation/TranslationManagerArtifactReadTest.kt`
  - corrupt-flat quarantine retains the source under deterministic exhaustion.

Validation performed:

- `.\gradlew.bat :app:testStandardDebugUnitTest --no-daemon --console=plain --tests 'eu.kanade.translation.ChapterTranslationStoreArtifactMigrationTest' --tests 'eu.kanade.translation.artifact.ChapterArtifactStoreTest' --tests 'eu.kanade.translation.TranslationManagerArtifactReadTest'` — PASS (64 tests, 0 failures/errors).
- `.\gradlew.bat :app:testStandardDebugUnitTest --no-daemon --console=plain --tests 'eu.kanade.translation.*'` — 963 tests completed; the only failure was the known pre-existing `AotReportBubbleFillTest` pixel mismatch.
- `.\gradlew.bat spotlessCheck --no-daemon --console=plain` — PASS.
- `git diff --check` — clean.

The 64-test/963-test entries above are the pre-review baseline; the review-fix
rerun below includes the new F1/F2 fixtures.

Review-fix rerun:

- `:app:testStandardDebugUnitTest` with the four focused C2 classes — PASS
  (80 tests, 0 failures/errors).
- `:app:testStandardDebugUnitTest --tests 'eu.kanade.translation.*'` — 971
  tests completed; the only failure was the known pre-existing
  `AotReportBubbleFillTest` pixel mismatch.
- `spotlessCheck` and `git diff --check` — PASS/clean after the review-fix
  report update.

## Deviations and risks

- Preservation is intentionally post-cutover and best-effort: a permission,
  collision, or provider rename failure leaves `INTENT` and the original
  bytes in place for the next chapter open. C3 owns later verification and
  deletion eligibility.
- A raw filesystem-backed migration can enforce no-replace admission. SAF/URI
  providers without that primitive intentionally defer rescue/quarantine and
  retain the original bytes; this is a safe operational tradeoff for retry on
  a later open, at the cost of some chapters remaining LEGACY until a
  supported backend is available.
- Deterministic race fixtures cover source, glossary, corrupt-flat quarantine,
  and atomic-backup recovery. They verify external destinations and original
  sources remain byte-for-byte intact after candidate exhaustion.
- Existing legacy writer paths, mutation admission, dead resync helpers, and
  reader/manual pipeline cadence remained unchanged until the subsequent C4/C5
  boundary work recorded below.
- The broad translation slice retains the independent Aot pixel mismatch;
  focused C2 tests and compilation passed.

## F3 SAF publication fix

The C2 review fix restores the ownership boundary without weakening F1/F2:

- `ChapterDocumentIo.kt:17-57,114-153` exposes `RenameResult`/
  `renameNoReplace` for names that may be owned by an external actor, and a
  separate `renameOwned` operation for the publisher's own temp, primary, and
  backup names. `AtomicChapterDocuments.publish` and owned backup rollback use
  `renameOwned`, so URI-backed `UniFile` providers retain the established
  temp→validate→backup→primary crash protocol.
- `ChapterDocumentIo.kt:247-299` keeps corrupt-flat quarantine and displaced
  backup admission on no-replace. Unsupported SAF admission, race-inserted
  destinations, and bounded candidate exhaustion retain the original bytes and
  return failure/retry; no raw-path-only fallback or unchecked overwrite was
  added.

F3 fixtures cover URI/fake-URI manifest, snapshot, and glossary publication,
owned backup rotation/rollback, and external destination races. They run with
the cumulative artifact focused suite below.
