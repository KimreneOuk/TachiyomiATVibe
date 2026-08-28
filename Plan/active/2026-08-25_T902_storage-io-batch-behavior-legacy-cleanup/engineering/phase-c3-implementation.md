# T902 Phase C3 implementation

## Result

Implemented the later-version, chapter-scoped health gate for preserved legacy
inputs. It is invoked from an opened artifact store, never from a library-wide
startup scan, and is fail-closed: verification metadata is published before
any physical cleanup and exact content identity is re-read immediately before
each deletion.

## Changes

- `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt:1101-1248`
  - Added `verifyLegacyArtifactHealth`. It requires ARTIFACTS authority,
    supported migration metadata, a strictly later app version, no active
    lease/candidate, matching page count/key digest, valid glossary and
    committed page/display pointers, no durable/stage failures, and valid
    cleaned-image references.
  - Publishes `health=VERIFIED` first. It then recomputes SHA-256 plus length
    for the exact preserved source/glossary names and deletes only matching
    identities; mismatches, unreadable files, provider failure, or marker
    publication failure retain recovery data. `PRESERVED→DELETED` is published
    after physical deletion and remains retryable if that marker write fails.
  - Cleanup is limited to the opened chapter's stale summary/empty flat file
    and reachable managed-artifact reconciliation; preserved `.corrupt` data
    and referenced/unknown legacy images are not broadly scanned or removed.
- `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt:2318-2330`
  - Runs the health gate on the serialized artifact open path using the current
    app version and invalidates/replaces the in-memory manifest only after the
    result is returned. ARTIFACTS reopens continue to ignore flat JSON.

## Tests and evidence

- `ChapterArtifactStoreTest.kt` covers later-version healthy verification and
  deletion, same/current version retention, external replacement retention,
  invalid/incomplete graphs, active lease/candidate blocking, and image/pointer
  health checks.
- Cumulative focused artifact/migration/manager run:
  `:app:testStandardDebugUnitTest --tests 'eu.kanade.translation.artifact.*'
  --tests 'eu.kanade.translation.ChapterTranslationStore*'
  --tests 'eu.kanade.translation.TranslationManagerArtifactReadTest'` — PASS,
  149 tests, 0 failures/errors.

## Risks and boundaries

Cleanup is intentionally best-effort and monotonic. A failed identity read or
delete leaves the source and a retryable marker. There is no startup/library
enumeration, no reader/scheduler change, and no deletion based on mtime alone.
