# T902 Phase C1 implementation

## Result

Implemented the approved additive migration contract and downgrade-visible mapping from base `63e77ff`. No lazy cutover, source/glossary rename, cleanup, mutation admission, or legacy-code deletion was added.

## Changes

- `ChapterArtifactManifest.kt:35,59-115`
  - Added nullable `legacyMigration` metadata without changing manifest schema version 1.
  - Added source/glossary preservation states, migration health, source/glossary identities, requested/resolved names, source page count/key digest, migration/version verification fields, and an `isSupported` guard that fails closed for missing, malformed, or unsupported cleanup metadata.
- `ChapterArtifactManifest.kt:80-146`
  - Hardened `isSupported` with explicit state combinations: source `INTENT` requires an original/requested name and no resolved name/timestamp; `PRESERVED` requires a resolved name and preservation timestamp; `DELETED` additionally requires `VERIFIED` health. Glossary `NONE` requires no names/identity, while `INTENT`, `PRESERVED`, and `DELETED` require the corresponding requested/resolved names and immutable identity; deletion also requires `VERIFIED` health. Verification fields are paired, monotonic, and version-gated; malformed identities and timestamps fail closed.
- `LegacyArtifactMigration.kt:53-64,94-205,478-500`
  - Extended the migration input with source/glossary names, glossary identity, and migrating app version.
  - Materialized metadata on migration and preserved it across resync when a fresh migration does not provide replacement metadata.
  - Added deterministic SHA-256 page-key digesting using sorted UTF-8 length-prefixed keys.
  - Kept strict complete pages on their existing committed mapping. Incomplete, retryable, cancelled, textless, and corrupt/uncertain pages now receive a provisional `origin=LEGACY` committed record using the existing generation/pointer contract; they retain non-ready display states and do not receive a legacy candidate generation.
- `LegacyArtifactMigration.kt:123-143`
  - Resync now carries prior provenance only when a fresh observable `legacySource` exactly matches the prior metadata identity. Missing/unreadable identity clears stale metadata; a fresh mismatched identity may only retain fresh matching metadata, never the prior marker.
- `ChapterArtifactStore.kt:266-315`
  - Extended the existing committed-snapshot materialization boundary to every compatibility record.
  - Before publication, preserves a cleaned-image name only when the committed display base is validated and matches the snapshot; otherwise the immutable snapshot is sanitized to `ORIGINAL_SOURCE`.
- `ChapterTranslationStore.kt:2122-2158`
  - Captures legacy glossary identity/version metadata during migration without changing decode semantics.
  - Materializes all legacy committed records, including incomplete pages, while retaining the existing LEGACY authority path.

## Tests

- `LegacyArtifactMigrationTest.kt:452-698`: metadata schema round-trip, identity/glossary mapping, deterministic digest, table-driven lifecycle/malformed-state validation, and resync coverage for absent, unreadable, mismatched, and matching source identities.
- `ChapterArtifactStoreTest.kt:592-637`: incomplete-page materialization and corrupt-image fallback to an original-source snapshot.
- `ChapterTranslationStoreArtifactMigrationTest.kt:247-291`: schema-1 ARTIFACTS fixture rehydrates page state with `Chapter 1.json` absent and remains warning-state rather than translated.

Validation performed:

- `.\gradlew.bat :app:testStandardDebugUnitTest --tests 'eu.kanade.translation.artifact.LegacyArtifactMigrationTest' --tests 'eu.kanade.translation.artifact.ChapterArtifactStoreTest' --tests 'eu.kanade.translation.ChapterTranslationStoreArtifactMigrationTest' --no-daemon` — PASS (83 tests, 0 failures/errors).
- `.\gradlew.bat spotlessCheck --no-daemon` — PASS.
- `git diff --check` — clean.

## Deviations and risks

- The requested `roles/implementer.md` file was not present in the repository or available `.traycer` role paths; implementation followed `traycer-implement/SKILL.md`, root `AGENTS.md`, the ticket, approved plan/review, and repository preflight.
- The metadata object is nullable and its identity fields are nullable so old schema-1 manifests and incomplete migration attempts decode safely; `isSupported` is the explicit fail-closed boundary. Later C2 must require supported metadata before rename or deletion.
- C1 intentionally leaves authority LEGACY, flat-file resync, quarantine rename, cleanup, and mutation admission unchanged for the next tickets.
