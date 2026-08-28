# T902 Phase C technical design input

Date: 2026-08-25
Audited revision: `63e77ff` (working tree, including Phase B changes)
Scope: legacy rescue, authority cutover, quarantine retention, and Phase C deletion boundaries. No production files were edited for this audit.

## Recommendation

Replace the proposed upgrade-wide migration/deletion job with a per-chapter,
IO-bound, lazy rescue. On the first open of a chapter that still has valid
legacy output, materialize and validate every artifact reference, publish one
final ARTIFACTS-authority manifest, and only then rename the original flat
source to a collision-safe `.migrated` name. Preserve the resolved name in the
manifest through a durable rename state machine. A later app version may
remove the preserved source only after it opens that chapter and records a
successful artifact-health verification. A failed or incomplete rescue keeps
the source and retries on a later chapter open; it never makes a library-wide
startup pass.

The downgrade-safe compatibility representation is the existing
`CommittedBundleMetadata.pageSnapshotFileName` pointer, with
`provisional=true` and `origin=LEGACY` for incomplete pages. No new
`legacySnapshotFileName` field or manifest-schema bump is needed. This is
important because the `63e77ff` reader already rehydrates candidate, then
committed, then legacy-flat snapshots; it will therefore still see a partial
page after the flat source is renamed.

## Evidence from the current tree

The following are verified against the named source and tests.

| Current behavior | Evidence | Phase C implication |
| --- | --- | --- |
| Opening a flat file decodes it, calls `migrateArtifactManifest`, loads/resyncs a manifest, materializes only strict committed snapshots, and reconstructs ARTIFACTS pages from candidate/committed snapshots with a flat-page fallback. | `ChapterTranslationStore.open/openInternal` `1987-2068`; `migrateArtifactManifest` `2091-2168`. | Keep the open trigger, but make it a one-way rescue transaction. The flat fallback cannot remain after the source is renamed. |
| A LEGACY manifest is resynced whenever the legacy identity or glossary changes. | `ChapterArtifactStore.loadOrMigrate` `67-155`; `resyncAndPublish` `1245-1259`; `LegacyArtifactMigration.resyncManifest` `91-112`. | Delete identity-resync behavior. A rescue attempt either reaches ARTIFACTS or remains read-only/retryable. |
| `openCandidate` is the current authority cutover point and publishes the manifest atomically after the generation record. | `ChapterArtifactStore.openCandidate` `549-627`, especially `620-625`; `AtomicChapterDocuments.publish/publishJson` `127-151`. | Reuse the same atomic document primitive, but perform the cutover only after all legacy page/glossary references have been materialized and re-read successfully. |
| Strict legacy pages already have a committed-snapshot helper; incomplete legacy pages have only `legacyVisible` and an in-memory flat-page fallback. | `materializeLegacyCommittedSnapshot` `266-299`; `LegacyArtifactMigration.migratePage` `115-158`; `migrateArtifactManifest` `2152-2159`. | Add a durable compatibility page snapshot for incomplete/retryable pages before renaming the flat source. Otherwise those pages disappear from `livePages` after cutover. This is a strong inference from the current fallback expression. |
| ARTIFACTS writes do not rewrite the flat file, but compatibility-shaped lazy stores and two direct fallbacks can still create one. | `ChapterTranslationStore.ensureArtifactStoreLocked` `1381-1415`; `ChapterTranslationStore.lazy` `2240-2251`; `ChapterTranslator.translateChapterInternal` `386-410`; `TranslationPipeline.translateSinglePageOnnx` `2867-2875`. | Remove the legacy writer/creation escape hatches. New stores must be artifact-only. |
| Legacy glossary bytes are read during migration, while ARTIFACTS glossary writes are versioned and atomic. | `migrateArtifactManifest` `2121-2131`; `ChapterArtifactStore.publishGlossary` `172-187`; `ChapterTranslationStore.persistGlossaryLocked` `1767-1791`. | Keep one migration-time read and artifact publication; remove the post-cutover legacy glossary writer. |
| The current manager routes ARTIFACTS reads through registry-owned stores and flat reads only for LEGACY/no-manifest cases; failed flat decodes are quarantined. | `TranslationManager.resolveDurableChapterStatus` `485-510`; reader reads `559-592`; probe ownership `635-656`; quarantine `674-685`. | Preserve the read-only rescue boundary, invalidate durable status after cutover, and do not cache a transient rescue failure as permanent NOT_TRANSLATED. |
| Retention is already chapter/store scoped for artifact trees and the cleaned-image sweep is bounded and lease-aware. | `ChapterArtifactStore.reconcileRetention` `1134-1147`; `TranslationManager.sweepOrphanedCleanedImages` `922-952`. | Extend the same per-chapter trigger for preserved legacy source/glossary cleanup; never turn it into a startup library scan. |

## Exact retain/change/delete boundary

### Retain as the rescue surface

- `ChapterTranslationStore.open`, `openInternal`, `openArtifact`,
  `legacyIdentityOf`, and `cleanedFileValidationOf`: these are the lazy input
  path for chapters that were actually produced by the legacy method.
- `LegacyArtifactMigration.LegacyChapterSnapshot`, `LegacyPageFacts`,
  `CleanedFileState`, `migrateChapter`, `migratePage`,
  `committedBundleOrNull`, `legacyVisibleOrNull`, geometry validation, and
  durable-failure mapping. They are pure mapping/validation logic and do not
  constitute an ongoing writer.
- `ChapterArtifactStore.materializeLegacyCommittedSnapshot`, extended with a
  `materializeLegacyCompatibilitySnapshot` operation for pages that are
  partial, retryable, cancelled, or otherwise not strict-ready. The latter
  writes an immutable `PageTranslation` snapshot under the managed artifact
  tree and records its pointer in the manifest.
- `AtomicChapterDocuments`, `ChapterDocumentIo`, `readValidated`, backup
  recovery, and the existing artifact manifest/page/glossary publication order.
- `ManifestAuthority.LEGACY` and `legacySource` only to recognize old
  manifests during rescue. No new successful migration may publish a LEGACY
  authority, and no post-cutover operation may resync from flat bytes.
- The manager's legacy decoders and `.corrupt` quarantine only as a bounded,
  read-only retry path when a rescue cannot yet complete. They must never be
  selected for an ARTIFACTS-authority chapter.

### Change for one-way rescue

- Rewrite `ChapterArtifactStore.loadOrMigrate` so an existing LEGACY manifest
  invokes one rescue attempt, not `resyncAndPublish`. A missing/unreadable
  source leaves the existing manifest and source untouched. An ARTIFACTS
  manifest follows the existing recovery path and ignores legacy bytes.
- Replace `resyncAndPublish`, `legacyGlossaryMatches`, and the resync use of
  `LegacyArtifactMigration.resyncManifest`. The pure `migrateChapter` mapper
  remains; identity comparison is used to record provenance, not to keep a
  second authority alive.
- Change `ChapterTranslationStore.migrateArtifactManifest` to build a complete
  rescue manifest in memory, publish/validate all immutable page snapshots and
  the versioned glossary, then publish one final manifest with
  `authority=ARTIFACTS`, `cutoverAtEpochMs`, and the migration marker described
  below. Only after that final manifest is durable may it rename the flat source
  and legacy glossary.
- For every incomplete legacy page, materialize an immutable full
  `PageTranslation` snapshot and put its file name in the existing
  `CommittedBundleMetadata.pageSnapshotFileName`. The record is
  `provisional=true`, `origin=LEGACY`, and retains the migration-derived
  `displayState` (`ORIGINAL_ONLY`/`FAILED_NO_RESULT` as appropriate); a
  provisional committed pointer is a compatibility/recovery pointer, not a
  claim that the page is strict-ready. A valid `legacyVisible` display base
  remains an optional projection detail. Do not add a
  `legacySnapshotFileName` field. The pointer is already included by
  `ChapterArtifactStore.reachablePaths` and by the manifest committed-image
  reference walk, so it remains reachable until a validated successor is
  committed and its bounded predecessor retention expires.
- Make `ChapterTranslationStore.ensureArtifactStoreLocked` and `lazy`
  artifact-only. Remove the `fileCreator` path and the flat branch of
  `persistLocked`; a new store may create the artifact manifest but never an
  `X.json` compatibility file. Replace the direct `createFile` fallbacks in
  `ChapterTranslator.translateChapterInternal` and
  `TranslationPipeline.translateSinglePageOnnx` with an artifact-parent/name
  lazy store or a clean failure. This changes storage acquisition only; it
  must not change stage ordering, reader cadence, provider lanes, or scheduler
  behavior.
- Make `persistGlossaryLocked` artifact-only. Legacy glossary bytes are read
  once during rescue and are never written again. Reader/manual glossary
  update cadence remains exactly as in Phase B.
- Make quarantine naming collision-safe. The current
  `quarantineCorruptTranslationFile` deletes an existing target before rename;
  Phase C must preserve both bytes. Prefer `<name>.corrupt` when absent, then
  `<name>.corrupt.<sha8>` (or a monotonic collision suffix) when occupied, and
  log the chosen name. Retry is idempotent: the source remains if rename
  fails. The same no-overwrite rule should be used by
  `AtomicChapterDocuments.recoverPrimaryFromBackup`.
- Remove `ChapterTranslationStore.tempFileNameFor`; it is not the atomic
  publication primitive and has no production caller.

### Delete now in C1/C4

- `ChapterArtifactStore.beginStage`, `commitStagePayload`, `promoteCandidate`,
  and `markCandidateStageFailed`, plus their test-only coverage, remain dead
  (`ChapterArtifactStore.kt` roughly `637-942`, zero production callers).
- `ChapterTranslationSummaryStore` and its tests/references after confirming
  the production reference set remains empty. Existing `X.summary.json` files
  are stale data, not a new status source.
- The flat JSON page writer branch in `persistLocked`, the legacy glossary
  writer branch, and all new-flat-file creation fallbacks. Keep only the
  legacy *read input* needed for lazy rescue.

## Migration marker and data schema

Add an additive, nullable `legacyMigration` field to
`ChapterArtifactManifest`. Keep the manifest's current schema number: the
compatibility pointer uses only existing fields, and older builds must be able
to read it. The migration record has its own format version and records a
post-cutover rename intent rather than claiming an exact target before SAF
has performed the rename:

```text
LegacyMigrationMetadata {
  formatVersion: 1
  sourceFileName: "X.json"
  sourceIdentity: LegacySourceIdentity            // existing SHA/length/mtime
  sourcePageCount: Int
  sourcePageKeyDigest: String                     // SHA-256(sorted legacy keys)
  migratedByVersionCode: Long                     // generated app version code
  migratedAtEpochMs: Long
  sourcePreservation: INTENT | PRESERVED
  requestedSourceFileName: String                  // deterministic attempt name
  resolvedSourceFileName: String?                  // discovered actual name
  sourcePreservedAtEpochMs: Long?
  sourceRenameAttempts: Int
  glossaryIdentity: LegacySourceIdentity?
  glossaryPreservation: NOT_APPLICABLE | INTENT | PRESERVED
  requestedGlossaryFileName: String?
  resolvedGlossaryFileName: String?
  health: INITIAL_CUTOVER | VERIFIED_WITH_WARNINGS | VERIFIED
  lastVerifiedByVersionCode: Long?
  lastVerifiedAtEpochMs: Long?
}
```

`INITIAL_CUTOVER` means every referenced artifact document was written and
re-read successfully, not that every page is complete. Incomplete pages are
represented as retryable/warning records. `VERIFIED` is reserved for a later
open that validates the whole graph and finds no unresolved source/corruption
warning. `VERIFIED_WITH_WARNINGS` never authorizes source deletion. A missing
record, missing version code, missing health, or a version code not greater
than `migratedByVersionCode` never authorizes deletion.

Legacy migration must leave `expectedPageCountTrusted=false`: a flat file's
page count is the set already produced, not a trustworthy chapter total. The
existing Phase B status rule therefore yields warnings for partial rescue
chapters and never falsely certifies TRANSLATED. A later true batch
pre-registration may set the trusted total.

## Cutover transaction and crash states

The operation is serialized per chapter (the existing migration lock can be
keyed rather than global later). It is a sequence of atomic document writes,
with the manifest being the sole authority switch:

1. Read the flat bytes once, compute `LegacySourceIdentity`, parse the page
   map, calculate the sorted-key digest, and validate every referenced cleaned
   image with the existing bounded probe. A parse-corrupt source is not
   migrated; preserve/quarantine it and return a retryable warning.
2. Build the mapped manifest. Publish the versioned artifact glossary if the
   legacy glossary is valid. For every page publish a committed snapshot when
   strict promotion is proven; otherwise publish a full compatibility
   snapshot and put its pointer in a provisional committed bundle. Re-read
   every snapshot, stage payload, display base, and glossary pointer before
   accepting it. No incomplete page is represented only by `legacyVisible` or
   a legacy-origin candidate.
3. Publish one final manifest through `AtomicChapterDocuments.publishJson`:
   all pointers are present and validated, `authority=ARTIFACTS`,
   `cutoverAtEpochMs` is set, and `legacyMigration` is
   `preservation=INTENT` with source/glossary identities and requested target
   names. This is the authority cutover. Never rename the source before this
   publication.
4. Resolve and rename each preserved legacy input using the intent state
   machine below. A successful rename is followed by an atomic manifest update
   recording the discovered actual name and `preservation=PRESERVED`.
   Rename failures leave the intent and source bytes intact; they do not roll
   back ARTIFACTS authority, and the next chapter open retries the operation.
   The old path is ignored for reads once the manifest is ARTIFACTS, but it is
   never deleted as part of this step.

Crash behavior is deliberately monotonic:

| Crash/failure point | Durable authority and next action |
| --- | --- |
| Before artifact writes | Flat source remains authoritative; no marker/cutover. Retry on next open. |
| After some snapshots/glossary, before final manifest | Flat source remains authoritative. Unreferenced managed files are harmless and are removed by the next artifact retention boundary; retry is idempotent. |
| Final manifest publication fails | `AtomicChapterDocuments` leaves the prior primary/backup authoritative; source remains. Do not rename. |
| Final ARTIFACTS manifest succeeds, rename not attempted or fails | ARTIFACTS is authoritative; source is retained as a safety copy. Reopen verifies the marker and retries rename. Never resync from it. |
| Rename intent is published, but rename has not started or fails | Manifest remains `INTENT`; source remains at the original name, any mismatched target is retained, and the next open re-reads identities and chooses a new suffix without overwriting. |
| Rename succeeds, then process dies before marker update | Manifest remains `INTENT`; reopening checks the requested name and bounded deterministic suffixes for a file matching `sourceIdentity`, adopts the discovered actual name, and publishes `PRESERVED`. If no matching target exists, it retries while retaining the source. |
| Rename succeeds and the marker update commits | Manifest records the resolved actual `.migrated` name; reopening uses artifacts. Later-version health verification may remove the preserved copy. |
| A page is incomplete or its cleaned image is corrupt | Migration can cut over with warning/failure metadata and a durable page snapshot; it never points the reader at a bad image. The legacy JSON/source bytes remain preserved and the page is retryable. |
| SAF permission/revocation during any publication | Keep the previous manifest/source, log, and retry. Never delete the only source copy. |

There is no cross-file atomic transaction in SAF. Safety comes from publishing
the fully verified artifact graph before changing authority, and from making
the post-cutover source rename optional and recoverable.

## Later-version verification and cleanup trigger

Do not scan the library at app startup. Trigger `verifyLegacyArtifactHealth`
only when an individual chapter is opened through
`openExistingChapterTranslationStore`/`withProbeStore` (on the existing IO
path), or when an explicitly selected chapter is repaired. The trigger must
not enumerate other manga/chapter directories.

Verification requires:

- `authority=ARTIFACTS` and a supported `legacyMigration.formatVersion`;
- current version code strictly greater than `migratedByVersionCode`;
- source page count and key digest represented by manifest page records;
- every committed, compatibility, candidate, generation, stage, and glossary
  pointer either validates or is explicitly a warning/failure record;
- committed display bases are present, bounded-probe valid, and still match
  their source identity; active reader leases are not ignored;
- no invalid pointer is hidden by a flat-file fallback.

First atomically publish the marker update (`lastVerified*`, health). Only then
may the per-chapter cleanup remove the file named by
`legacyMigration.resolvedSourceFileName` (and the corresponding resolved
glossary name). If either applicable preservation state is still `INTENT`, its
resolved name is absent, health is missing, the version is not later, or
verification has warnings, retain every source/quarantine copy. A `.corrupt` file with
no matching successful migration marker is retained indefinitely by automatic
cleanup; it is not safe to infer that corrupt bytes are redundant.

Legacy companion images are separate from the preserved JSON policy. Retain
names reachable from committed/previous/compatibility display references,
live state, and active `TranslationStreamRegistry` leases. After the chapter
is opened, the existing bounded `sweepOrphanedCleanedImages` may remove only
unreferenced, non-leased, non-protected files; apply the freshness grace before
the candidate cap. Never delete a referenced legacy image merely because the
JSON was renamed. Corrupt/unreferenced image files may be removed by this
verified per-chapter sweep, while the corrupt source JSON remains preserved.

## Status, cache, and reader implications

- During rescue failure, flat decode is a read-only retry path. The exact cache
  rule is: `durableStatusCache` stores only a non-null state returned after a
  successful durable read (including a durable `ERROR` record); it never stores
  a null result or a recoverable rescue/I/O failure. A no-document result is
  also uncached, so a newly-created artifact is discovered without an
  unrelated invalidation. `resolveDurableChapterStatus` should return a
  `(state, cacheable)` result internally, with `cacheable=false` for failed
  manifest open, failed legacy rescue, permission/revocation, malformed input
  awaiting quarantine retry, and any probe that did not complete the artifact
  graph. This removes the current nullable `DurableStatus` poisoning at
  `TranslationManager.kt:463-482`.
- After final manifest publication, successful rename-marker update, health
  verification, or any artifact reset/candidate transition that can change the
  chapter badge, clear that chapter's durable status entry. Queue emissions,
  active-store registration/close, and the existing reset paths keep their
  conservative whole-map invalidation. Reader/status reads route exclusively
  through registry-owned artifact stores after ARTIFACTS cutover; the legacy
  decoder is selected only for LEGACY-authority chapters or a rescue that has
  not yet cut over. Existing queue/live/durable resolution order remains
  intact.
- `expectedPageCountTrusted=false` for migrated legacy data means complete
  touched pages can be READY_WITH_WARNINGS, not TRANSLATED, until a real batch
  total is supplied. Durable failure records remain ERROR; incomplete/missing
  pages remain retryable warnings.
- Materialized compatibility snapshots are required so
  `getChapterTranslationForReader` can rebuild partial pages after `X.json` is
  renamed. This changes only storage rehydration. Do not change
  `translateSinglePage`, `translateSinglePageOnnx` stage logic, glossary
  update cadence, `RollingAutoCoordinator`, `TranslationScheduler`, provider
  lanes, or reader cancellation/barriers.
- `TranslationManager.findTranslationDocument` may continue to derive the
  sibling manifest path when `X.json` is absent. It must not recreate `X.json`
  for an ARTIFACTS chapter.

## Test design

Extend the existing migration fixtures rather than creating a second storage
model:

1. `LegacyArtifactMigrationTest`: marker format/defaults, sorted key digest,
   strict versus incomplete pages, corrupt cleaned-image mapping, and
   `expectedPageCountTrusted=false`.
2. `ChapterTranslationStoreArtifactMigrationTest`: a complete legacy chapter
   is cut over, has validated committed snapshots, and leaves
   `X.json.migrated`; an incomplete page is rehydrated from its compatibility
   snapshot after the source is renamed; artifact-only reopen never reads the
   renamed source; legacy glossary becomes a versioned artifact sidecar.
3. Fake-IO crash matrix: fail a snapshot write, manifest temp-to-primary
   rename, source rename, and glossary publication independently. Exercise
   candidate open, candidate snapshot failure, cancel, interrupted recovery,
   and commit after the source has been renamed. Assert the old
   source/manifest remain usable, retries do not duplicate or overwrite
   `.migrated`/`.corrupt`, and a final ARTIFACTS manifest never points at an
   unvalidated file.
4. `ChapterArtifactStoreTest`: health verification rejects missing marker,
   same-version verification, missing page/key digest, invalid snapshot, and
   invalid glossary; a later-version successful verification records health
   and permits only the preserved-source cleanup step.
5. `TranslationManagerArtifactReadTest`: fresh manager reads pages/status after
   `.migrated` rename, incomplete rescue is warning/retryable, corrupt JSON is
   preserved/quarantined, and a failed rescue is retried on the next lookup
   without an unrelated cache invalidation. Add collision cases for existing
   `.migrated` and `.corrupt`, a target-created race, and a crash after rename
   before marker update.
6. Storage-acquisition characterization: `ChapterTranslator` and the
   single-page pipeline fallback create an artifact-only store and never an
   `X.json`; existing reader/manual stage and glossary-cadence tests remain
   unchanged. A manager construction test asserts no library-wide directory
   enumeration occurs.
7. Image retention: referenced legacy image and leased image survive;
   unreferenced image is deleted only on the opened chapter's bounded sweep;
   fresh/unknown-timestamp files survive the write-to-commit grace window.

## Contradictions with the current Phase C design

`phase-c-design.md` is directionally correct about deleting dead code and
keeping a rescue path, but these items must be amended before implementation:

- **C2's “one-time on app upgrade” scan conflicts with the Director's explicit
  no-library-wide-startup-scan decision.** Replace it with lazy per-chapter
  rescue; an optional settings action may operate only on a user-selected
  chapter and must call the same path.
- **C2 deletes `X.json`/`X.glossary.json` immediately after a page-count
  check.** Replace deletion with the final-manifest-then-rename protocol and
  later-version health gate. Count alone is insufficient; use source identity,
  page-key digest, pointer validation, and health/version evidence.
- **C3's always-on sweeper is too broad.** It must not delete `.corrupt` or
  `.migrated` data without the marker/version gate, and it must run only for a
  chapter already opened (or an explicitly selected chapter). Artifact `.tmp`
  cleanup can remain in the existing chapter retention boundary.
- **C3's orphan-image rule needs the B5 protections.** “Not in the current
  manifest” is not enough during reader leases or the cleaned-image
  write-to-commit window; retain active references, leases, may-delete guards,
  and the freshness grace.
- **C4's “legacy glossary deletion rides C2” is too early.** Rename/preserve
  it during cutover, verify the artifact glossary pointer on a later version,
  then remove the preserved copy. No legacy glossary writer remains.
- **C1's dead stage API and `tempFileNameFor` deletions remain valid.** The
  current `resyncManifest`/identity-resync behavior is an additional deletion
  required by the no-ongoing-legacy policy.

## Reviewer resolution: C1-C5

This section is the implementation contract for the cold review. It resolves
the five load-bearing ambiguities against the current `63e77ff` types and
rehydration code; it supersedes the earlier `legacySnapshotFileName` proposal.

### C1 — Existing committed pointer is the downgrade-safe representation

Use the existing `PageArtifactRecord.committed.pageSnapshotFileName`, not an
additive page field and not a schema bump. During rescue, every incomplete
legacy page receives a validated immutable `PageTranslation` snapshot and a
`CommittedBundleMetadata` with:

- `origin=LEGACY`, `provisional=true`, and the existing committed snapshot
  file name;
- the migration-derived `displayState` unchanged (`ORIGINAL_ONLY` or
  `FAILED_NO_RESULT` when the page is not strictly ready); and
- `displayBase` set to the validated legacy cleaned image when available, or
  `ORIGINAL_SOURCE` when the cleaned image is missing/corrupt. The snapshot
  must not retain a bad cleaned-image pointer merely because the flat record
  named it.

`legacyVisible` may remain as a compatibility display hint, but it is not the
rehydration authority. Initial rescue records do not need a legacy-origin
candidate: the provisional committed pointer is the fallback while a later
candidate is opened. `provisional` never counts as strict display-ready and
`expectedPageCountTrusted=false` keeps the chapter at warning/retry semantics.

This is visible to the prior reader contract at
`ChapterTranslationStore.kt:2152-2159`: it reads candidate snapshot first,
then `committedPages`, then the flat page. A `63e77ff` downgrade therefore
still returns the partial page from the existing committed pointer even when
`X.json` is absent; it may render the original/partial display exactly as the
serialized `PageTranslation` permits, but it must not silently omit the page.
No unknown field is required for this guarantee.

The fixture is a schema-1 manifest with no `X.json` and no summary, one
partial page snapshot, and a provisional LEGACY committed pointer. Open it
with the pre-Phase-C rehydration helper (the same candidate/committed/flat
resolution used by `63e77ff`) and assert the page key, blocks/statuses, and
warning state are present. Re-run the fixture after opening and cancelling a
new candidate with a null candidate snapshot; the old reader must still see
the committed compatibility snapshot.

### C2 — Compatibility ownership and transition table

The compatibility pointer is owned by `committed` until a validated successor
is published in the manifest. Candidate creation is never a replacement.
`previousCommitted` retains the old pointer after promotion, so reachability
and image protection are monotonic.

| Event | Candidate pointer | Compatibility/committed pointer | Rehydration and status | Reachability/image rule |
| --- | --- | --- | --- | --- |
| Rescue cutover for incomplete page | `null` | `committed` points to provisional LEGACY snapshot | New and old readers load the snapshot; partial/missing total is warning, never TRANSLATED | Committed snapshot and any validated cleaned image are reachable. |
| Candidate open | `null` initially | Provisional `committed` is unchanged | Candidate-running state falls back to committed display; status remains warning/in-flight, not ERROR | Keep compatibility snapshot and image; generation record is reachable. |
| Candidate snapshot write succeeds | Points to validated candidate snapshot | Old committed pointer remains | New reader uses candidate; old reader still has committed fallback | Both snapshots and both image references remain reachable. |
| Candidate snapshot write/pointer publication fails | Unchanged (normally `null`) | Unchanged | Retryable candidate failure; committed compatibility page remains readable | Any unreferenced candidate sidecar is orphan cleanup only; never delete committed image. |
| Candidate cancellation | Cleared by `cancelCandidate` | Unchanged | Rehydrates committed compatibility page; no cancellation becomes ERROR by itself | Candidate/generation can be swept after lease release; committed image remains. |
| Interrupted-stage recovery | Existing candidate pointer is retained only if its snapshot validates; otherwise it is cleared/retried | Unchanged | RUNNING becomes `FAILED_RETRYABLE`; missing expected pages are warnings, not ERROR | Keep committed and any validated candidate files; do not sweep an active lease. |
| Validated commit | Cleared | New committed pointer published; old compatibility moves to `previousCommitted` | Successor becomes the live/display authority; status uses its page plus trusted baseline | Candidate, new committed, old previous, and their images are reachable until bounded retention drops the old predecessor. |
| Commit or manifest publication failure | Prior candidate/manifest remains authoritative | Old compatibility remains | Retryable storage rejection; no new committed pointer is claimed | New sidecars may be orphaned, but old snapshot/image is retained. |
| Explicit reset/demotion | Cleared | `committed` and `previousCommitted` cleared only by the existing user-reset operation | Page becomes ORIGINAL_ONLY by intent; this is not candidate failure | Image deletion waits for leases and the existing freshness/retired-image guards. |
| Cleanup/reachability pass | No pointer changes | Only pointers absent from `committed`, `previousCommitted`, candidate, live state, and leases become unreachable | Status cache is invalidated after reset/promotion as applicable | `reachablePaths` and `referencedCleanedImageNames` protect both committed generations and candidate images; deletion is bounded and lease-aware. |

The implementation must remove the ARTIFACTS `legacyPages[pageKey]` fallback
after cutover. It is safe only before the source rename; afterward a missing
committed/candidate snapshot is a durable warning and a migration-health
failure, not an invitation to read a stale flat authority.

### C3 — Durable collision-safe rename state machine

`ChapterDocumentIo.rename(from, to)` has no reserve-if-absent primitive, so the
manifest must record intent and identity before attempting the rename and the
resolved actual name only after observing the result. The per-chapter
migration lock serializes competing opens; the identity checks make retries
idempotent if a second process or SAF provider races between checks.

1. **Intent.** The final ARTIFACTS manifest publishes
   `legacyMigration.sourcePreservation=INTENT`, the source identity, original
   source name, a deterministic `requestedSourceFileName` (prefer
   `X.json.migrated`, then `X.json.migrated.<sha8>-N`), and the analogous
   optional glossary fields. No target is treated as reserved.
2. **Resolve before rename.** Read the requested target. If it is absent, try
   `rename(source, requested)`. If it exists and its bytes/identity match the
   recorded source, adopt it without another rename. If it exists but differs,
   choose the next deterministic suffix, atomically publish the new requested
   name while retaining `INTENT`, and retry. Never overwrite a target.
3. **Observe the rename result.** A failed `rename` is followed by reads of
   source and candidate targets. Source-present plus target-mismatch selects
   the next suffix. Source-absent plus a matching target adopts that target.
   Source-absent with no matching target leaves `INTENT` and retains every
   available copy for diagnosis; it never marks cleanup-safe.
4. **Commit the actual name.** After a matching target is discovered (whether
   by successful rename or an idempotent pre-existing copy), publish the
   manifest with `resolvedSourceFileName=<actual>`,
   `sourcePreservedAtEpochMs`, and `sourcePreservation=PRESERVED`. The same
   state machine runs independently for the optional legacy glossary, using
   `glossaryPreservation` and its resolved name; one input may therefore be
   preserved while the other remains in `INTENT`.
5. **Crash after rename, before marker update.** On the next chapter open,
   `INTENT` plus `sourceIdentity` causes a bounded lookup of the requested
   name and deterministic suffixes. A matching file is adopted and the marker
   is advanced; a mismatched file is skipped. The system never needs a
   library-wide scan and never records a guessed target as the actual name.

The corrupt-flat quarantine uses the same no-overwrite identity helper:
`<name>.corrupt` when absent, then deterministic hash/suffix names when
occupied; an existing matching quarantine is adopted, an occupied mismatched
target is preserved, and a failed rename leaves the source. Apply that helper
to `recoverPrimaryFromBackup`, replacing its current delete-before-rename
behavior.

### C4 — Mutation admission and existing error mapping

Define one admission result at the store boundary:

```text
MutationAdmission =
  Granted
  | Rejected(code, retryable=true, message)

codes: LEGACY_RESCUE_REQUIRED, LEGACY_RESCUE_FAILED,
       ARTIFACT_PUBLICATION_FAILED, STORE_DEFUNCT, FENCE_REJECTED
```

`ChapterTranslationStore.ensureArtifactAuthorityForMutation()` performs the
serialized lazy rescue when needed and returns this result. Every mutator then
calls `admitMutationLocked()` again under the store mutex before constructing
or installing a new `PageTranslation`; the second check closes the race
between manager open and the first write. `updatePage` changes from `Unit` to
the existing `PatchResult` family (ignored return values remain source
compatible), while `patchPage`, `updatePageGuarded`, and all stage-merge
methods return their existing `Rejected` variants. A failed artifact
publication is also rejected before assigning the locally-built page to
`pages`, so the in-memory map cannot claim a mutation that was not durable.

The manager's internal store-open result is `Ready(store)`, `Absent`, or
`RetryableStorageFailure(code, cause)`. The current nullable resolver remains
an adapter for read-only callers, but it logs the typed failure and does not
register a failed store. Existing production mappings stay:

- `ChapterTranslator.translateChapterInternal` sees no ready store before
  `preRegisterPages` and follows its current storage-failure path at
  `386-410`/`503-515`, setting `Translation.State.ERROR` without mutating a
  page; the retryable code is retained in the log/diagnostic result.
- Batch guarded writes already map `PatchResult.Rejected` to
  `BatchPersistenceRejectedException` and the surrounding batch catch maps it
  to the existing translation ERROR state. Admission rejection occurs before
  stage work and does not change ordering or cancellation.
- The single-page pipeline resolver returns before stage mutation when rescue
  is rejected; existing caller failure/timeout handling remains in force.
  Late `PatchResult.Rejected` is logged by the current path and never applies
  the page update.
- Reader/status reads continue returning an empty/non-translated result on an
  unavailable store, but C5 prevents that transient result from poisoning the
  next retry. No reader/manual cadence or provider/scheduler path is changed.

The required test injects a LEGACY rescue failure, calls the mutation API, and
asserts `Rejected(LEGACY_RESCUE_FAILED, retryable=true)`, unchanged `state`,
unchanged manifest, and no flat-file write. It then restores I/O and confirms
the next admission succeeds and the mutation is durable.

### C5 — Exact durable-status cache rule and invalidations

Replace the current `durableStatusCache[key] = DurableStatus(state)` behavior
with an internal `DurableStatusResolution(state, cacheable)` result. Store an
entry only when `state != null && cacheable`; never store null for absent
documents, failed probes, permission errors, malformed legacy input awaiting
quarantine/retry, or failed LEGACY rescue. A durable `ERROR` backed by a
verified manifest failure record is cacheable; a transient I/O/migration error
is not. This is the chosen rule—no source/manifest token is needed.

Invalidate the chapter entry (or use the existing conservative whole-map
clear) after final ARTIFACTS cutover, a successful rename-marker update,
successful later-version health verification, candidate promotion/demotion,
reset data paths, queue emissions, active-store registration/close, and any
manifest/glossary update that can change the derived badge. A retry that stays
in `INTENT` also clears the entry before probing. Tests must use one manager:
first make rescue/probe fail and assert no cache entry, restore the source/IO,
then assert the second status lookup reopens the chapter and returns its
artifact-derived status/pages. Add successful-cutover and reset invalidation
assertions alongside the existing cache tests.

## Rollback and operational risks

There is no safe rollback from a durable ARTIFACTS authority to a mutable flat
writer: once cut over, artifacts own the chapter. Rollback means retrying
failed artifact writes, reopening a retained `.migrated` copy for diagnosis,
or restoring a prior atomic manifest backup; it does not re-enable legacy
resync. A downgrade to an older app must see `authority=ARTIFACTS` and must
never be allowed to delete the preserved source because it lacks the later
version/health evidence. SAF rename semantics, permission revocation, and
partial directory visibility are the main operational risks; the monotonic
ordering above leaves a recoverable source at every pre-cutover failure point.
