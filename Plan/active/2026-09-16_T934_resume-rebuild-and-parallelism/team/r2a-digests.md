# T934 R2a — Write-time digests + typed adoption failures (lane report)

Branch: `t934/resume-rebuild-and-parallelism` (base 5330001). Lane: R2a.
Main files touched (3, all allowlisted) + 1 new test file. **Zero edits to any
existing test file; zero edits to D5/D6/D9/D10/D11 (git confirms).**

Files changed:

- `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactManifest.kt`
- `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt`
  (checkpointOcr publication only — retention/monitor untouched)
- `app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt`
- `app/src/test/java/eu/kanade/translation/pipeline/batch/T934WriteTimeDigestsTest.kt` (NEW)

`ChapterTranslationStore.kt` was NOT needed: the existing `checkpointOcr`
facade already carries `sourceSha256: String?` (:1125), so no store seam was
required.

---

## R2a.1 — Digest recorded durably at FIRST ADMISSION

**Digest-home decision: new additive manifest field
`ChapterArtifactManifest.sourceShaByPageKey: Map<String, String>`**
(`ChapterArtifactManifest.kt:78-92`), stamped inside `checkpointOcr`'s single
atomic `manifest.copy(...)` transaction (`ChapterArtifactStore.kt:728-738`)
— the same transaction that moves the checkpoint pointer. All checkpoint
branches (CLOSE, REBASE, adopt) flow through this one publication site, so
the digest lands at first admission, never at run end. Only a well-formed
lowercase-64-hex sha is stamped; anything else (placeholder / hash failure)
leaves the record untouched.

Rejected alternatives:

1. **Checkpoint-sidecar-only**: requires a sidecar read to answer "what was
   this page's source?" and misses never-OCR'd pages entirely.
2. **`PageArtifactRecord.source.sha256`**: stamped only by translation-side
   publications, and `StageFingerprints.committedBundle` derives fingerprints
   from `PageArtifactRecord.source` — stamping there risks changing committed
   bundle fingerprints (a regression vector), outside this lane's risk budget.
3. **`ChapterRunRecord` map**: per-run, chicken-and-egg with RUN_SNAPSHOT,
   and the record's fields are fingerprint-constrained.

## R2a.2 — Run start consumes RECORDED digests; no whole-chapter re-read

`ChapterProfileBatchCoordinator.kt`:

- `:327-329` `recordedSourceShaByPageKey` — one lazy run-start read of
  `store.artifactManifest?.sourceShaByPageKey`, stable for the whole run.
- `:341-345` `admissionSourceSha` — the page's ADMISSION identity. A
  well-formed fresh dispatch observation wins (see identity note below);
  the RECORDED digest heals an absent or non-hex observation (the shell's
  `UNKNOWN_SOURCE_FINGERPRINT` hash-failure placeholder — which previously
  poisoned reuse identity forever and forced re-OCR).
- `:355-359` `effectiveSourcePairs` — replaces `orderedSourcePairs` as the
  SINGLE `orderedSourceDigest` input at all 7 record-minting sites
  (`:392, :942, :1304, :1645, :1986, :2040, :2182`). `orderedSourceDigest`
  itself (`:3904`) and the runId minted from it are unchanged, so
  `runId`/ST-14 gate identity semantics are preserved — now without
  depending on re-read page bytes when a recorded digest exists.
- `:3372-3398` `checkpointPage` passes `sourceSha256 = admissionSha`, so
  every re-admission keeps the durable record current in the same
  transaction (R2a.1).

## Lazy verification at consumption — what is checked, what was deliberately NOT

Kept (the existing content-identity check, now typed — "keep that"):

- `:3400-3428` `checkpointReuse` — the ST-06 reuse gate. A reusable
  checkpoint must prove `checkpoint.sourceIdentity.sha256 ==
  admissionSourceSha(pageKey)`; a mismatch is typed `SHA_MISMATCH` and the
  page re-runs (fail closed). This is the consumption-point verification:
  the checkpoint is consumed here, and its identity is checked against the
  admission identity (fresh observation preferred, recorded digest fallback).

Deliberately NOT added (and briefly drafted, then removed): a decoded-
bundle-vs-checkpoint sha comparison in `adoptCheckpointSnapshot`, and a
decoded-sha-vs-admission check in `checkpointPage`. Reason: `mergeOcrLocked`
(`ChapterTranslationStore.kt:978-1076`) does NOT copy `sourceFingerprint`
from OCR results onto the live page, so OCR page-snapshot bundles routinely
carry null or stale provenance from a PRIOR admission window. Both checks
would false-positive on that stale provenance (e.g. a reader-translated
page plus a replaced file would fail a legitimate re-OCR), violating the
deliverable's "ONLY where cheap/needed per the existing content-identity
checks". The typed reuse gate above is the genuine-mismatch fail-closed
point.

## R2a.5 — Typed adoption failures

`ChapterProfileBatchCoordinator.kt`:

- `:185-203` `internal enum CheckpointAdoptionFailure(counterKey)`:
  `NO_POINTER("ocrAdoptNoPointer")`, `SIDE_CAR_UNREADABLE("ocrAdoptSideCarUnreadable")`,
  `SHA_MISMATCH("ocrAdoptShaMismatch")`, `LEASE_DENIED("ocrAdoptLeaseDenied")`,
  `MERGE_REJECTED("ocrAdoptMergeRejected")`, `BUNDLE_MISSING("ocrAdoptBundleMissing")`.
- `:205-222` `CheckpointReuse` / `CheckpointAdoption` sealed interfaces
  (typed outcomes; `Failed` carries the reason + verbatim detail).
- `:2833-2888` `adoptCheckpointSnapshot` returns typed outcomes:
  NO_POINTER (`:2848/:2850`), SIDE_CAR_UNREADABLE (`:2853`), BUNDLE_MISSING
  (`:2856`), LEASE_DENIED (`:2861`), MERGE_REJECTED with the merge's verbatim
  reason (`:2882`).
- `:417-431` per-run tally + `recordAdoptionFailure`: WARN
  `"TachiyomiAT t924 preflight checkpoint adoption failed pageHash=…
  reason=<TYPED>[ detail=…] — re-OCRing"` (page keys stay hashed).
- Counters (`:433-451`): fixed keys first (TOTAL/DONE/REUSED/FLAG), then
  `ocrPagesAdoptFailed` (sum, companion `:3715`) and one bounded
  `ocrAdopt*` key per observed reason — emitted ONLY when nonzero, so
  steady-state record counter maps stay byte-identical; appended last so the
  `publishRecord` over-bound trim (`takeLast(32)`, `:3478-3487`) drops these
  first, never the phase-critical `ocrPages*` keys.
- Both consumption walks are typed: primary `:523-586`, S8 gap-rescan
  `:730-759`. `NO_POINTER` is the quiet never-checkpointed answer (normal
  fresh OCR); every other reason counts + warns. Previously this cliff was
  one untyped WARN (`~:455` pre-change) with no counter — the re-OCR cliff
  was invisible per cause.
- Envelope-resume call site `:2733-2735`: `Failed -> null` preserves the
  original `if (adopted?.page == null) → typed CorpusDrift pause` semantics
  byte-identically (no NPE on a null adopted page).
- `:3051` `frozenProfileReuse` consumes `CheckpointReuse.Reusable`.

## R2a.3 + R2a.4 — Tests (`T934WriteTimeDigestsTest.kt`, NEW, 4 tests)

Harness: real `ChapterTranslationStore` over `FakeUniFile`/`@TempDir`
(`OcrPreflightCoordinatorTest` M1 idioms); the fake `NativeLaneWorker`'s
`runOcrStage` IS the decode seam — every invocation re-hashes the source
bytes and is counted in `hashCalls` (the hasher call-count seam).

1. `first admission records every page source sha durably at checkpoint
   time` — R2a.1: `manifest.sourceShaByPageKey ==` the dispatch pairs after
   pass 1; same-store resume: zero OCR, zero hash calls.
2. `second run with unchanged sources performs zero source re-hash from
   recorded digests` — R2a.3: ALL dispatch pairs withheld as the non-hex
   hash-failure placeholder; resumed run performs ZERO source re-hashes
   (`hashCalls == 0`, `ocrPages` empty), runId AND `orderedSourceDigest`
   equal to the first run, `ocrPagesTotal`-style REUSED counter = 3, no
   adoption counters on the healthy run. (First run pins 3 hashes — one per
   page, once.)
3. `changed source detected at consumption fails closed with typed sha
   mismatch` — R2a.4: replaced page bytes under the same keys; the stale
   checkpoints are NOT reused (both pages re-run, REUSED = 0), and the
   record carries `ocrPagesAdoptFailed = 2` + `ocrAdoptShaMismatch = 2`;
   re-admission stamps the NEW digests at write time.
4. `lost checkpoint sidecar fails closed with typed reason and re-OCRs` —
   SIDE_CAR_UNREADABLE pin: checkpoint sidecars deleted from disk while the
   manifest pointers survive; the dangling pointer cannot prove source
   equality, both pages re-run (2 hashes), record carries
   `ocrPagesAdoptFailed = 2` + `ocrAdoptSideCarUnreadable = 2`, REUSED = 0,
   and the same digests are re-stamped (identity preserved).

## Schema / additive-field notes

- New field `sourceShaByPageKey` defaults to `emptyMap()`.
  `ArtifactDocumentJson` has `ignoreUnknownKeys = true` (D5 precedent), so
  the field is tolerated in BOTH decode directions: a rolled-back build that
  strips it ignores the unknown key (loses only the optimization — the
  fallback re-observes at dispatch); the new build reads old manifests via
  the default. **No schema bump is owed** — stripping the map is harmless by
  construction. Bounded: one entry per manifest page record (keyed by
  pageKey), values 64 hex chars.
- `ChapterRunRecord` unchanged — no new record fields; only bounded
  nonzero-only counter keys (32-key budget preserved; trim order protects
  the fixed keys).

## Identity-preservation rationale (ST-14 stays fail-closed)

Deriving `orderedSourceDigest` purely from RECORDED digests would mask a
changed source at the ST-14 resume gate (digest unchanged → a stale
FINALIZE/COMPLETE resume would be waved through). Hence
`admissionSourceSha` prefers a well-formed fresh dispatch observation over
the recorded digest; the recorded digest is only a healing fallback for
absent/placeholder observations. The reuse gate likewise compares against
the admission identity, NOT against the recorded digest — comparing against
the record would be trivially true (the record was stamped from the same
checkpoint), i.e. a vacuous check.

## Honest scope note

The shell (`BatchChapterTranslator`, NOT in this lane's allowlist) constructs
the coordinator with `orderedSourcePairs` from `LazySourceFingerprints`,
whose `get()` per page forces a hash of every page at construction per
dispatch. That construction-time pull cannot be removed within this lane's
allowlist. The coordinator-level wins delivered here: run-start identity no
longer depends on those observations being usable (recorded-digest
consumption), a dispatch hash failure no longer poisons reuse identity
forever or breaks runId/ST-14 continuity, and every adoption cliff is typed
and counted. Removing the remaining construction-time hash pull needs a
shell-lane change (pass the lazy map, not the materialized list).

## Existing-test edits

**NONE.** No existing test file was modified. `git status --short` shows
exactly the 3 allowlisted main files modified + the 1 new test file
untracked; D5/D6/D9/D10/D11 files are byte-identical to base 5330001.

## Known limitations

- Only `SHA_MISMATCH` and `SIDE_CAR_UNREADABLE` are individually pinned by
  tests; `NO_POINTER` (quiet path), `LEASE_DENIED`, `MERGE_REJECTED`,
  `BUNDLE_MISSING` share the same counter/WARN mechanism (each is a one-line
  `return` in `adoptCheckpointSnapshot`/`checkpointReuse`) but are not
  individually exercised — they need exotic fault injection (lease races,
  torn merges) not worth faking at this layer.
- The typed gates run under the coordinator's single-threaded preflight
  walk; the manifest CAS in `checkpointOcr` still guards against any racing
  writer.

## Build/test status

NOT run by this lane (forbidden: no Gradle invocation). Owed to Main Leader:
`:app:testStandardDebugUnitTest`, focus
`T934WriteTimeDigestsTest`, `OcrPreflightCoordinatorTest`,
`Stage7FinalizeResumeCoordinatorTest`, `ChapterAnalysisPhaseCoordinatorTest`,
`OcrPreflightQueueRestoreTest`, `StandardPipelineCoordinatorTest`,
`ChapterArtifactStoreStaleManifestRetryTest`, plus the D-file suites
(byte-identical sources, expected green).
