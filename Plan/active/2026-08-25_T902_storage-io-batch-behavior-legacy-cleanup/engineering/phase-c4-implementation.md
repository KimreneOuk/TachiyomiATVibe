# T902 Phase C4 implementation

## Result

Closed the new-mutation storage boundary around artifact authority. Mutation
admission is serialized with the chapter store mutex; rescue/publication failure
returns a typed retryable rejection before a new page is retained in the page
map. New translation paths acquire artifact-parent/name stores and no longer
create or rewrite flat page, glossary, or summary documents.

## Changes

- `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt:66-75,319-358`
  - Added `MutationAdmission` and `ensureArtifactAuthorityForMutation()` /
    `admitMutationLocked()`. Defunct stores, failed rescue/publication, and
    missing authority are rejected; pure in-memory reducer stores remain
    memory-only. A fresh artifact-backed lazy store bootstraps an empty
    ARTIFACTS manifest without invoking the compatibility `fileCreator` seam.
- `ChapterTranslationStore.kt:479-918,1068-1281,1329-1414`
  - Applied admission to page/stage mutations, registration, deletion,
    rekey/queue cleanup, and glossary mutation. Candidate publication failures
    restore the prior in-memory page and return the existing rejected result
    families; durable status remains retryable rather than caching a failed
    rescue.
- `ChapterTranslationStore.kt:1546-1604,1957-2060`
  - `ensureArtifactStoreLocked`, `persistLocked`, and glossary persistence are
    artifact-only. The legacy flat file/glossary are read only by migration and
    recovery; the nullable source-compatible `fileCreator` parameter remains
    but is never invoked.
- `ChapterTranslator.kt:374-412` and `TranslationPipeline.kt:2846-2882`
  - Removed direct flat-file creation fallbacks. Missing artifact storage now
    follows the existing clean storage-failure path.
- `TranslationManager.kt:590-669,858-873` keeps registry-owned artifact opens
  and existing pipeline error mapping; no reader/manual cadence or scheduler
  behavior was changed.

## Tests and evidence

- Fresh artifact lazy-store, no-flat-file, artifact-only glossary, admission,
  retry, reader, and in-flight status fixtures are in the translation storage
  tests and pass as part of the 149-test focused run (0 failures/errors).
- Kotlin production and unit-test compilation passed before the final focused
  rerun. The translation wildcard slice completed 962 tests with only the
  known `AotReportBubbleFillTest` pixel mismatch.

## Risks and tradeoffs

The compatibility `fileCreator` API is retained only to avoid source churn in
older test/helpers; production never invokes it and no flat writer remains in
the new mutation path. A storage backend that cannot establish artifact
authority fails closed and can retry after I/O is restored. Reader/manual
single-page cadence and stage ordering remain outside this boundary.
