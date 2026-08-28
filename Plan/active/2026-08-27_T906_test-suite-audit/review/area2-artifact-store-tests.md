# T906 Area 2 — Artifact / store / durability tests audit

Auditor: T906 Artifact Store Test Auditor (reviewer role).
Audit target: commit `56179d7` on `t904/integration` (worktree
`C:\Users\User\.traycer\worktrees\kimreneouk__tachiyomiatvibe\t904-integration`;
verified `git diff 56179d7 HEAD` is empty for all in-scope paths, so on-disk
content == 56179d7 snapshot for this area).

## Scope

Tests under audit (12 files, ~144 test methods):

- `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactStoreTest.kt` (43)
- `app/src/test/java/eu/kanade/translation/artifact/AtomicChapterDocumentsTest.kt` (12)
- `app/src/test/java/eu/kanade/translation/artifact/LegacyArtifactMigrationTest.kt` (26)
- `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactLayoutTest.kt` (10)
- `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactDeletionTest.kt` (2)
- `app/src/test/java/eu/kanade/translation/artifact/StageFingerprintsTest.kt` (6)
- `app/src/test/java/eu/kanade/translation/artifact/ModelIdentityCacheTest.kt` (7)
- `app/src/test/java/eu/kanade/translation/ChapterTranslationStorePersistenceTest.kt` (1)
- `app/src/test/java/eu/kanade/translation/ChapterTranslationStoreDefunctTest.kt` (6)
- `app/src/test/java/eu/kanade/translation/ChapterTranslationStorePhase3Test.kt` (8)
- `app/src/test/java/eu/kanade/translation/ChapterTranslationStoreRekeyTest.kt` (4)
- `app/src/test/java/eu/kanade/translation/ChapterTranslationStoreArtifactMigrationTest.kt` (19)

Production references (all read at 56179d7): `artifact/ChapterArtifactStore.kt`,
`artifact/ChapterDocumentIo.kt` (incl. `AtomicChapterDocuments`),
`artifact/ChapterArtifactManifest.kt`, `artifact/ChapterArtifactLayout.kt`,
`artifact/LegacyArtifactMigration.kt`, `artifact/StageFingerprints.kt`,
`artifact/ModelIdentityCache.kt`, `artifact/ChapterArtifactDeletion.kt`,
`ChapterTranslationStore.kt`, `model/PageTranslationState.kt`,
`scheduling/TranslationStreamRegistry.kt`.

## Verdict

**0 WRONG · 0 STALE assertions · 0 REDUNDANT · 0 FLAKY · 2 LOW (stale naming / dead setup).
142 of 144 tests VALID.**

This area is in markedly better shape than the seed evidence suggested. Every
test I traced asserts the current contract at 56179d7, including the
post-T904 behaviors: one-way legacy rescue (no resync), atomic
temp/validate/backup/rename publication with recovery at every crash window,
schema-v2 future-document refusal, interrupted-RUNNING-stage recovery to
retryable, clear-on-promotion of durable failures, and typed outcomes
(`Rejected`/`NotStored`) instead of exception-to-success mapping. No
`RuntimeException -> Completed`-style pattern exists in this area.

## Findings

### FINDING 1 — LOW · STALE (naming only; assertions correct)

- **Where:** `ChapterTranslationStoreArtifactMigrationTest.kt:251`
  (`schema one artifact fixture rehydrates with no legacy flat file`)
- **What it asserts today:** builds a `ChapterArtifactManifest(...)` using
  current defaults (lines 256–275) and asserts rehydration of a PARTIAL page
  from the committed snapshot plus `READY_WITH_WARNINGS` status.
- **Problem:** with `ChapterArtifactManifest.SCHEMA_VERSION = 2`
  (ChapterArtifactManifest.kt:49) and the manifest constructed from defaults,
  the fixture is a **schema-2** document. The "schema one" name promises
  backward-compatibility coverage (v1 document missing the additive v2
  fields: `authority`, `expectedPageCount`, `activeCandidateGenerationIds`,
  `legacyMigration`, …) that the test no longer provides. Because
  `kotlinx.serialization` defaults make v1 bytes decode identically, the
  coverage loss is latent, not active — the assertions pass and are correct.
- **Current contract:** schema-2 additive defaults must keep pre-v2 documents
  readable (ChapterArtifactManifest.kt:16–47 defaults; decoder
  `ignoreUnknownKeys = true`, ChapterDocumentIo.kt:182–185).
- **Evidence class:** VERIFIED (constructor defaults traced; no
  `schemaVersion = 1` argument exists in the fixture).
- **Recommendation:** rewrite — rename the test and/or strip
  `schemaVersion` and additive fields from the encoded fixture JSON so the
  v1→v2 additive-default read path is actually pinned.

### FINDING 2 — LOW · STALE (name + dead setup; assertions correct)

- **Where:** `ChapterArtifactStoreTest.kt:495–507`
  (`failed resync publish keeps the prior manifest authoritative`)
- **What it asserts today:** after a first ARTIFACTS-authoritative load, a
  second load with a changed legacy identity (`v2`) under
  `io.failWrites = true` returns the prior manifest, `resyncedFromLegacy
  == false`, and the durable manifest is unchanged.
- **Problem:** the identity resync it names was removed by the one-way
  rescue contract — `resyncedFromLegacy` is a "retained compatibility
  field" that is always false, and ARTIFACTS-authoritative loads never
  consult legacy bytes (ChapterArtifactStore.kt:45–51, 112–127). On that
  reopen path no publication is attempted at all, so the `io.failWrites =
  true` toggle (line 500) is dead setup. The assertions that remain pin the
  (correct) no-resync contract and still hold.
- **Evidence class:** VERIFIED (loadOrMigrate ARTIFACTS branch performs
  reconcileRetention + interrupted-stage recovery only; no publish call is
  reachable with `failWrites = true`).
- **Recommendation:** rename to describe the no-resync-under-reopen
  contract and drop the dead `failWrites` toggle (or repurpose the test to
  exercise the LEGACY-staging publish-failure path, which
  `snapshot publication failure keeps legacy authority and source bytes`
  at line 426 already covers for the page-snapshot stage).

## Classification summary (per README scheme)

| Class | Count | Notes |
|---|---|---|
| WRONG | 0 | — |
| STALE | 0 assertions / 2 LOW naming-setup residues | Findings 1, 2 |
| REDUNDANT | 0 | Near-pairs checked and rejected: `AtomicChapterDocumentsTest` (document-layer recovery) vs `ChapterArtifactStoreTest:519/531` (store-layer manifest recovery) cover different layers; `LegacyArtifactMigrationTest:428` (pure mapping) vs `ChapterTranslationStoreArtifactMigrationTest:422` (full open path with real probe) likewise. |
| FLAKY | 0 | See flakiness review below. |
| VALID | 142 | Summary count only, per README. |

## Seed-evidence checks (assignment-specific)

1. **Pre-atomicity behavior** — none. `AtomicChapterDocumentsTest` pins the
   full temp/validate/rotate/rename sequence including rollback after
   promotion failure (lines 43–56), backup recovery with quarantine
   (91–122), and fail-closed quarantine exhaustion (125–144), all matching
   `AtomicChapterDocuments.publish/readValidated/recoverPrimaryFromBackup`
   (ChapterDocumentIo.kt:189–263). Store-level interrupted-promotion
   recovery is pinned read-only at ChapterArtifactStoreTest.kt:549–573,
   matching the backup-preservation branch (ChapterArtifactStore.kt:94–106).
2. **Pre-schema-v2 behavior** — no test asserts `schemaVersion == 1` or any
   v1-only field shape. Future-schema refusal (schema 99) is pinned
   read-only for primary, backup-with-primary, and backup-without-primary
   (ChapterArtifactStoreTest.kt:576–638), matching
   ChapterArtifactStore.kt:74–90 and publishManifestInternal guards
   (1469–1487). Only residue is Finding 1's stale fixture name.
3. **Tests that can never fail** — none found. The closest candidate,
   `migration is deterministic for identical input`
   (LegacyArtifactMigrationTest.kt:392–399), is a genuine determinism pin:
   migration embeds `migratedAtEpochMs` only from the snapshot (fixed 42L),
   and would fail if anyone introduced wall-clock stamping.
   `ChapterTranslationStorePersistenceTest:24–37` looks tautological but is
   not: it pins the real dirty-retry contract — memory-only stores
   "fail" `persistLocked` by design (ChapterTranslationStore.kt:2133–2144),
   `flushDirtyLocked` keeps `dirty` on failure (2152–2160), so the second
   `persistCount` increment only happens if the failure path retained the
   dirty flag.
4. **File-system / timing flakiness** — examined every FS/thread touch:
   - `ModelIdentityCacheTest:42–50` byte-drift does **not** depend on
     `lastModified` granularity: the stamp is
     `versionMarker:length:lastModified` (ModelIdentityCache.kt:87–88) and
     "model-bytes-detector" (20 B) vs "model-bytes-changed" (19 B) already
     differ in length. Deterministic. VERIFIED.
   - `ChapterArtifactStoreTest:1140–1168` concurrent candidate opens:
     `ChapterArtifactStore` methods are `@Synchronized`
     (ChapterArtifactStore.kt:69, 623), so exactly one
     Committed/one Rejected regardless of thread order; 5 s latches are
     generous. Deterministic. VERIFIED.
   - `ChapterTranslationStoreArtifactMigrationTest:624–670` (held-stream
     retention): `held.close()` runs pending deletes synchronously on the
     caller thread (TranslationStreamRegistry.kt:302–315), so
     `previous.exists() shouldBe false` cannot race; the 60 s grace job
     expires against a removed record (317–323). Deterministic. VERIFIED.
   - Store tests use memory-only stores or explicit `flush()`/
     `closeAndFlush()` (mutex-serialized, synchronous). The debounced
     `persistScope` job (250 ms, ChapterTranslationStore.kt:2192–2204) is
     only armed for legacy-authority durability
     (`isDurable && authority != ARTIFACTS`, line 1420–1422); in these
     tests it either never arms (memory-only early return) or its work is
     idempotent relative to the synchronous flush. No assertion races it.
     VERIFIED (STRONG INFERENCE for the legacy-path interleaving, which is
     monotonic toward the asserted state).
   - `@TempDir`/real-FS tests (`ChapterTranslationStoreArtifactMigrationTest`,
     `ModelIdentityCacheTest`) do real `Files.move`/delete via FakeUniFile;
     nothing depends on mtime (identity matching ignores it by design,
     ChapterArtifactStore.kt:1431–1434).
5. **Assignment-specific store behaviors** — all pinned current:
   - *Atomicity*: candidate snapshot published before committed pointer;
     crash windows leave orphans, never dangling pointers
     (ChapterArtifactStore.kt:407–537; tested at 985–1039, 1042–1108).
   - *Recovery*: interrupted RUNNING → FAILED_RETRYABLE + LEGACY_UNKNOWN
     durable failure with non-null retry eligibility (store test 1111–1137
     matches recoverInterruptedStages, ChapterArtifactStore.kt:800–846);
     backup deleted only after successful recovery or clean load
     (ChapterArtifactStore.kt:116–126).
   - *Clear-on-promotion*: promotion drops the TRANSLATION durable failure
     (ChapterArtifactStore.kt:530), asserted at store test 1106; cancel
     restores `priorDisplayState` (test 1171–1198 vs cancelCandidate
     739–744).
   - *Schema-v2 additive defaults*: see Finding 1 — behavior correct,
     v1-bytes fixture coverage is the only gap.

## Evidence classification

All production-behavior claims above: VERIFIED against `git show
56179d7:<path>`-equivalent on-disk content (diff-checked empty). The two
LOW findings are naming/setup observations, not behavioral defects; no
test in this area needs deletion, relaxation, or stabilization.

## Dispositions (Director-approved fix round)

Branch `t906/fix-area2`, worktree
`C:\Users\User\.traycer\worktrees\kimreneouk__tachiyomiatvibe\t906-fix-a2`,
single commit `ce75b7b` ("test: rename stale artifact-store test names to
match asserted contracts"), parented on `56179d7`. Two files touched,
assertions unchanged in both.

1. **Finding 2 fix** — `ChapterArtifactStoreTest.kt:495`
   - Renamed `failed resync publish keeps the prior manifest authoritative`
     → `reopen with a changed legacy identity returns the prior manifest
     unchanged` (the identity-resync it named no longer exists; the
     assertions pin the no-resync reopen contract).
   - Removed the dead `io.failWrites = true` / `io.failWrites = false`
     setup/teardown: on the ARTIFACTS-authoritative reopen path no
     publication is attempted, so the toggle had no reachable effect.
   - Kept all three assertions verbatim (`second.manifest shouldBe
     first.manifest`, `resyncedFromLegacy shouldBe false`,
     `readManifest() shouldBe first.manifest`).
2. **Finding 1 fix** — `ChapterTranslationStoreArtifactMigrationTest.kt:251`
   - Renamed `schema one artifact fixture rehydrates with no legacy flat
     file` → `committed-only artifact fixture rehydrates with no legacy
     flat file` (naming what is actually asserted: rehydration of a
     PARTIAL page from a committed-snapshot-only ARTIFACTS manifest with
     no legacy flat file).
   - Added a two-line comment stating the fixture is encoded with plain
     `Json` (defaulted fields, including `schemaVersion`, omitted), so it
     pins the additive-defaults decode path.
   - History check that informed the fix (evidence, not scope expansion):
     `authority` was added at `cdee028` and `expectedPageCount` at
     `63e77ff` while `SCHEMA_VERSION` was still 1; the 1→2 bump
     (`b56f56c`) added no fields. There is therefore no v1-only manifest
     shape whose coverage was lost — a faithful v1 fixture would be
     byte-equivalent modulo the version constant. Renaming (not
     reconstructing a "v1" document) is the honest fix; fabricating a
     `schemaVersion: 1` + `authority: ARTIFACTS` document no version ever
     wrote would have been misleading.
   - All assertions unchanged.
3. **Gates (all green in the fix worktree):**
   `spotlessApply` → clean; `spotlessCheck` → BUILD SUCCESSFUL;
   `:app:compileStandardDebugKotlin` → BUILD SUCCESSFUL;
   focused `:app:testStandardDebugUnitTest --tests
   eu.kanade.translation.artifact.ChapterArtifactStoreTest --tests
   eu.kanade.translation.ChapterTranslationStoreArtifactMigrationTest`
   → 43 + 19 tests, 0 failures, 0 errors
   (`app/build/test-results/testStandardDebugUnitTest/*.xml`).
   (`local.properties` was created in the worktree for SDK discovery;
   gitignored, not committed.)

## Unnecessary-test pass (second review, AUDIT ONLY)

Re-reviewed all 12 area files hunting for tests asserting no real
invariant, duplicating coverage, pinning trivia, or targeting contracts
that no longer exist. One clear duplicate found; the rest of the
borderline candidates examined are documented with keep recommendations.

### F1 — REDUNDANT · recommend DELETE (or fold as a data variant)

- **Where:** `ChapterTranslationStoreRekeyTest.kt:56-64`
  (`CBZ entry names are valid targets`)
- **Why it adds no value:** it executes the identical code path as
  `URL keys move to downloaded filenames and update source names`
  (lines 17-27) with only different string constants. `rekeyPages`
  (ChapterTranslationStore.kt:1197-1256) contains no name-shape
  branching — no CBZ-specific handling exists anywhere: it compares
  sizes, membership, and `indexOf` mappings over arbitrary strings. The
  dash/dot key shapes exercise no additional branch, sanitizer, or
  layout code (the test's store has no artifact store, so no
  `pageSegment` hashing is reached either).
- **Distinguishing check performed:** confirmed via the production
  source that neither key validation nor extension handling exists in
  the rekey path; both tests produce structurally identical assertions
  (`rekeyPages` return value, state keys, source names).
- **Recommendation:** DELETE; optionally append the CBZ key pair as a
  second data case inside the line-17 test if key-shape regression
  comfort is desired. (Not acted on — audit only.)

### Borderline candidates examined — all KEEP

- `ChapterTranslationStoreRekeyTest.kt:30-40` / `:43-53` (count
  mismatch, idempotent no-op): each pins a distinct guard branch of
  `rekeyPages` (`size !=` early-out; all-keys-already-downloaded
  early-out). Real branch coverage, keep.
- `ChapterArtifactStoreTest.kt:495` (renamed) vs `:452`
  (changed-identity no-resync): partial topical overlap. The renamed
  test's unique invariant is whole-manifest equality of the load result
  and the durable document (`second.manifest == first.manifest`), i.e.
  reopen does not mutate anything; `:452` pins field-level resync
  semantics (identity retention, `hasManualEdits`). Keep both.
- `ChapterArtifactStoreTest.kt:222-246` / `:383-408` (glossary mtime
  adoption, glossary race exhaustion): the mechanism
  (`preserveLegacyInput`) is shared with the source-preservation twins,
  but each exercises the distinct glossary call-site branch
  (ChapterArtifactStore.kt:1154-1167) whose failure mode (e.g. wrong
  name derivation, wrong exhaustion state) only these catch. Keep.
- `ChapterArtifactStoreTest.kt:641-661` / `:664-683` (NotStored on
  write failure vs rename failure): distinct failure windows inside
  `AtomicChapterDocuments.publish` with different recovery paths
  (temp-only failure vs backup-restore after promotion failure). Keep.
- `LegacyArtifactMigrationTest.kt:428-438` / `:441-451`
  (CORRUPT_BYTES / DIMENSION_MISMATCH mapping): completes the
  `CleanedFileState` mapping table alongside the MISSING (line 274) and
  EMPTY (line 402) rows; the open-layer pair in
  `ChapterTranslationStoreArtifactMigrationTest.kt:422/436` covers the
  probe wiring into those states, a different layer. Keep.
- `ChapterTranslationStoreDefunctTest.kt:46-51`
  (`markDefunct flips isDefunct`): closest-to-trivial test in the area
  (a flag flip), but it documents the guard's entry contract, and the
  flag's behavioral consequences are covered by the four mutator no-op
  tests. Cost is nil; keep.
- `ChapterArtifactLayoutTest.kt:96-106`
  (`managed directories cover every managed subtree`): pins an exact
  list constant, but the constant is load-bearing — a silently dropped
  root would orphan that subtree from retention sweeping forever. Keep
  (the exact-order assertion is stricter than necessary but harmless).
- `StageFingerprintsTest.kt:110-117`
  (`committed bundle fingerprint covers base and stage identities`):
  kept, with a coverage note — the name overstates what is asserted.
  Only determinism and translation-fingerprint sensitivity are tested;
  sensitivity to source identity, display-base kind/fileName, and layout
  fingerprint is untested (`committedBundle`, StageFingerprints.kt:126-138,
  folds all of them). Not unnecessary — the assertions it makes are real
  — but a future fix round could strengthen it to match its name. (Not
  acted on — audit only.)
- `AtomicChapterDocumentsTest.kt:59-66` (`second publish retains the
  previous version as backup`) vs `:26-40` (URI-style rotation): both
  assert `.bak` creation after a second publish, but on opposite
  backend admission modes (`renameNoReplace` available vs
  `UNSUPPORTED`/owned-rename-only). Keep.
- `ModelIdentityCacheTest.kt:32-39` (`repeated reads reuse the cached
  identity`): `first shouldBe second` alone would pass even without
  caching (recomputation is deterministic); the real invariant is
  `cachedRoleCount() == 1`. Kept — the count assertion makes it a
  genuine cache-hit pin. Keep.
- `ChapterTranslationStorePersistenceTest.kt:24-37`: single test pinning
  the dirty-retry contract; not tautological (second increment only
  occurs if the failure path retained `dirty`). Keep.

No unnecessary test was deleted in this pass; no production or test code
was modified. Fix-round commit remains `ce75b7b` on `t906/fix-area2`.
