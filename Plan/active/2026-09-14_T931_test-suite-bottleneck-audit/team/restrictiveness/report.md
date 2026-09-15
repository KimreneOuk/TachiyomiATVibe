# T931 — Restrictiveness audit: tests vs T930 group-commit collision surface

Reviewer report · 2026-09-14 · HEAD · read-only audit (no source modified)
Scope: all of `app/src/test` (277 files, 1,914 `@Test` methods), exhaustive on
`translation/artifact/` (15 files, ~159 tests) and `translation/coexistence/`
(21 files, 43 tests); pattern sweep elsewhere.

---

## 1) Verdict up front

**The Director's premise ("too restrictive, stale legacy") is NOT true of the
collision surface.** The suite is restrictive about T930 only in a thin,
identifiable band: **~14 test methods (0.7%) are true change-detectors** — they
pin exact write-op counts, write ordering, flush counts, or publish op
sequences. Everything else that touches persistence asserts *durability truth*
(state is correct on disk after a flush/commit, or unchanged after a failure),
which is precisely what T930 must preserve.

Concretely:

- **Slice A (flag default OFF) breaks ~0 tests behaviorally.** Every store/artifact
  store is built in tests through constructor/fixture recipes that tolerate
  appended, defaulted parameters. The two real Slice-A risks are *structural*,
  not behavioral: (a) 40 positional `ChapterTranslationStore(null, null[, map])`
  call sites that break compile only if a parameter is inserted mid-signature;
  (b) one reflection bridge that selects `RollingAutoCoordinator`'s constructor
  by exact arity 7 (`D6DrainNotCancelTest.kt:181`) — breaks the moment T930's
  writer registry adds a constructor param, and it fails *loudly with a named
  "RED defect" message that would be a lie*.
- **For full flag-ON T930, ~12–18 test methods need CONVERSION, not deletion** —
  re-pointing write-count/op-order assertions at commit-point equivalents.
- **The load-bearing race/coexistence safety net is ~12% of the suite** (~225 of
  1,914 tests) and must survive untouched. The two most T930-relevant suites
  (`D6DrainNotCancelTest`, `D11PermitFreeCommitTest`) are not obstacles at all —
  they already pin the *target* semantics (drain-to-commit, fail-closed commit).
- **Cull: 0 tests.** One suite has an expired stated purpose
  (`TranslationManagerDeleteResetOrderingTest` — its guard moved to
  `manager/ChapterDataResetController.kt`, which now exists) and is the only
  honest "convert-then-retire" candidate, and only after the reset path adopts
  drain-to-commit.

---

## 2) Collision inventory

Classes: **BC** = behavior-contract (survives T930), **CD** = change-detector
(pins today's mechanics, breaks under flag-ON T930), **AMB** = ambiguous.
"T930 impact" reads as: what happens when the pinned mechanic changes.

### 2a. Exhaustive — `translation/artifact/`

| File:line | What it pins | Class | T930 impact |
|---|---|---|---|
| AtomicChapterDocumentsTest.kt:20-23 | First publish leaves exactly `{primary}` on disk (no tmp/bak residue) | BC | Final-state; survives read-back elision and staging |
| AtomicChapterDocumentsTest.kt:30-39 | Exact 3-rename rotation sequence `tmp→primary, primary→bak, tmp→primary` via `io.renamed shouldBe listOf(...)` | **CD** | The canonical publish-op-sequence pin. Survives read-back elision (elision removes a read, not renames); breaks if publication is staged/deferred through a different path |
| AtomicChapterDocumentsTest.kt:42-56 | Rollback when final rename fails (prior primary restored) | BC | Crash-window contract; T930 must keep it |
| AtomicChapterDocumentsTest.kt:59-66 | Second publish retains previous version as `.bak` | BC | Backup-rotation contract preserved by commit-point design |
| AtomicChapterDocumentsTest.kt:69-78 | `publish(..., validate = { false })` returns false and leaves primary intact | **CD** | Direct collision with read-back elision: the read-back `validate(written)` step (ChapterDocumentIo.kt:240-244) is exactly what may be removed; eliding it makes this publish return true |
| AtomicChapterDocumentsTest.kt:81-88 | Write failure aborts publication without touching primary | BC | Write-path failure contract survives elision |
| AtomicChapterDocumentsTest.kt:91-144 | Corrupt-primary recovery from `.bak` + quarantine, incl. two race injections (destination inserted mid-recovery) | BC | Read-side recovery; off the write elision path |
| AtomicChapterDocumentsTest.kt:147-174 | Missing-primary backup fallback; semantic validation on read; null when neither validates | BC | Read-side only |
| ChapterArtifactStoreTest.kt:106-133 | loadOrMigrate publishes manifest+sidecar; URI path leaves no `.tmp` | BC | Durability truth |
| ChapterArtifactStoreTest.kt:136-436 (17 tests) | Legacy preservation: collision-safe renames, INTENT/PRESERVED/DELETED lifecycle, health gate, race-injected rename destinations | BC | Migration safety; untouched by group commit |
| ChapterArtifactStoreTest.kt:440-449 | Unchanged-identity reopen is a fast path: `manifest shouldBe first` and **no `.bak` appears** (write-suppression pin) | BC (borderline CD) | Group commit only coalesces writes; "no change → no write" still holds under both flag states |
| ChapterArtifactStoreTest.kt:452-505 | Resync refusal, durable-failure retention across reopen | BC | |
| ChapterArtifactStoreTest.kt:517-544 | Corrupt primary recovers from retained backup; quarantine count pinned to 1 | BC | Crash-safety net |
| ChapterArtifactStoreTest.kt:547-571 | Interrupted backup promotion retains backup; primary absent | BC | Crash window B2 |
| ChapterArtifactStoreTest.kt:574-637 | Future-schema guard: manifest left byte-identical, backup preserved | BC | Fail-closed schema gate |
| ChapterArtifactStoreTest.kt:639-702 | recordDurableFailure: NotStored on write/rename failure, durable on happy path | BC | Fenced-seam contract |
| ChapterArtifactStoreTest.kt:705-775 | Retention deletes exactly 6 orphans, keeps reachable + legacy | BC (exact count pin but off T930 surface) | |
| ChapterArtifactStoreTest.kt:778-808 | Reload sweeps orphan temps/candidates, keeps active candidate | BC | |
| ChapterArtifactStoreTest.kt:811-908 | Generation retention bounded; exact encoded identity; backups of reachable docs never deleted | BC | |
| ChapterArtifactStoreTest.kt:912-936 | Glossary version advance + retention bound; empty chapter | BC | |
| ChapterArtifactStoreTest.kt:939-980 | Provisional committed pointer for incomplete legacy pages | BC | |
| ChapterArtifactStoreTest.kt:983-1037 | Promotion reuses candidate snapshot; **`io.writtenNames.count { committedTmp } shouldBe 1` (:1030), `writtenNames.none { candidateTmp }` (:1031), `listedDirectories shouldBe emptyList()` (:1032)** | **CD** (AMB for Slice A) | Pins exact write ops + deferred-sweep of the promote path. Survives flag OFF; becomes wrong the moment promotion's IO choreography is restaged |
| ChapterArtifactStoreTest.kt:1040-1106 | Candidate + retryable-failure publish atomically; committed display untouched | BC | TX-07 invariant |
| ChapterArtifactStoreTest.kt:1109-1135 | Interrupted RUNNING stage → durable retryable failure on restart | BC | Process-death recovery |
| ChapterArtifactStoreTest.kt:1138-1166 | Two concurrent `openCandidate` on same snapshot: exactly 1 Committed + 1 Rejected | **BC — safety net** | CAS serialization; T930 keeps fenced CAS seams |
| ChapterArtifactStoreTest.kt:1169-1196 | Cancel restores pre-candidate textless display | BC | |
| ChapterArtifactStoreTest.kt:1209-1268 | v2 fixture loads clean; write cycle rewrites as v3 with old data byte-intact | BC | Schema evolution gate |
| ChapterArtifactStoreStaleManifestRetryTest.kt:75-127 | Stale `publishActiveRun` retries ONCE, keeps concurrent verify marker; `durable.updatedAtEpochMs shouldBe 3L` (:126, fixed param — deterministic) | **BC — safety net** | One-shot CAS retry semantics; T930 preserves |
| ChapterArtifactStoreStaleManifestRetryTest.kt:263-288 | Same for `checkpointOcr` | **BC — safety net** | |
| ChapterArtifactStoreStaleManifestRetryTest.kt:291-335 | Fresh-state drift rejects with real reason after exactly one retry; stale sidecar never reached disk (:334) | **BC — safety net** | Pins rejection-reason taxonomy (staleManifestRejection prefix contract, ChapterArtifactStore.kt:1639) |
| CheckpointOcrTransactionTest.kt (all 14 tests) | Close/rebase/adopt transactions; B1-B3 fault injection; every rejection leaves prior manifest authoritative, at most orphan sidecar; stale caller recovers via one-shot retry | **BC — safety net (core)** | This is the fenced-CAS-seam spec T930 builds on |
| SidecarCrashPublicationTest.kt:75-90 | Sidecar bytes durable **before** the single manifest publication; **`writtenNames.indexOf(runTmp) shouldBe 0` (:87), `writtenNames.count { manifestTmp } shouldBe 1` (:88)** | **AMB** | The sidecar-before-pointer ordering is a crash contract (BC); the exact write-count arithmetic is a mechanics pin that must be restated if commit points batch publications |
| SidecarCrashPublicationTest.kt:93-155 | Sidecar write/rename failure, manifest write/rename failure → prior manifest authoritative, pointer never dangles, orphans swept by retention | **BC — safety net (core)** | |
| SidecarCrashPublicationTest.kt:158-209 | Generic multi-sidecar transaction; **retry publishes "exactly ONE manifest publication" (`count { manifestTmp } shouldBe 1`, :200)** | **AMB** | Same split as :87-88 |
| SidecarCrashPublicationTest.kt:212-228 | Quarantined pointed sidecar preserved by retention | BC | |
| SidecarCrashPublicationTest.kt:231-247 | Stale snapshot recovers through one-shot retry | BC | |
| ChapterArtifactStoreRetireActiveRunTest.kt:71-85 | retireActiveRun clears pointer, idempotent second call commits unchanged manifest | BC | Idempotence = no spurious rewrite; survives coalescing |
| ChapterArtifactStoreRetireActiveRunTest.kt:88-96 | Stale snapshot rejected, pointer kept | BC | |
| ChapterArtifactStoreRetireActiveRunTest.kt:99-111 | Retired sidecar becomes retention orphan (transaction never deletes) | BC | |
| ChapterArtifactDeletionTest.kt:46-98 | Deletion plan removes exact tree set (manifest/tmp/bak/corrupt/artifacts), fails closed on SAF failure | BC | |
| LegacyArtifactMigrationTest.kt (26), ChapterArtifactLayoutTest.kt (11), ChapterRunRecordSchemaTest.kt (7), ModelIdentityCacheTest.kt (7), ProfileContentFingerprintGoldenTest.kt (5), SemanticFingerprintTest.kt (21), StageFingerprintsTest.kt (6) | Pure mapping / layout-naming / fingerprint / schema round-trip. No publish ops, no write counts | BC | Unaffected by T930 |

### 2b. Exhaustive — `translation/coexistence/`

| File:line | What it pins | Class | T930 impact |
|---|---|---|---|
| D6DrainNotCancelTest.kt:226-278 | Cancelled auto window DRAINS in-flight call to a terminal commit under NonCancellable; ledger consumed; exactly one paid call | **BC — safety net (core)** | This IS T930 item 6 (drain-to-commit stop) for the provider lane; generalizing it must not regress it |
| D6DrainNotCancelTest.kt:285-333 | Grace expiry cancels cleanly, does NOT commit, ledger stays unresolved, no re-issue | **BC — safety net** | Bounded-drain contract |
| D6DrainNotCancelTest.kt:208-219 | Reflection pin: `RollingAutoCoordinator.PROVIDER_DRAIN_GRACE_MS == ATTACH_TIMEOUT_MS` via `getField` | AMB (brittle harness, BC content) | Constant-value pin; breaks only on rename — staleness domain |
| D6DrainNotCancelTest.kt:180-196 | **Reflection bridge selects RollingAutoCoordinator ctor by `parameterTypes.size == 7`**; null → AssertionError naming a fake "RED defect" | **CD (structural)** | Breaks the moment T930's writer registry adds a constructor param — the likeliest T930 touchpoint of this class |
| D7EngineEpochStopRaceTest.kt:284-361 | Stop during in-flight auto page drains to terminal commit; ledger mirror asserts durable truth | **BC — safety net (core)** | Drain-to-commit under engine-epoch stop |
| D7EngineEpochStopRaceTest.kt:258-283 | Drain grace budget ≥ attach chain budget (reflection constant read) | AMB | Same as D6 constant pin |
| D7EngineEpochStopRaceTest.kt:362-601 | Engine close/borrow/grace-expiry race matrix | **BC — safety net** | |
| D9AttemptLedgerTest.kt:219-279 | Attempt entry durable **BEFORE** the paid provider call starts; startup reconcile consumes exactly one; counter persisted in same file | **BC — safety net** | Write-ordering pin, but of a crash-accounting property (fenced seam) that T930 preserves; the "durable before provider call" invariant is exactly what the commit-point contract must keep |
| D9AttemptLedgerTest.kt:286-421 | Three death cycles cap chapter; exact counter values after each cycle; manual attach writes zero entries | **BC — safety net** | |
| D11PermitFreeCommitTest.kt:56-190 | Real store commit parked at COMMIT barrier; second page admitted mid-commit; **fail-closed: released publication must actually commit** (:175-179); precondition: snapshot lags while parked (:126-128) | **BC — safety net (core)** | Pins "parked publication is a real commit that later lands" — the exact shape T930 generalizes to commit points. NOT a persist-before-UI pin: it tolerates UI lagging the parked write either way |
| D1/D2/D3/D5/D6FF/D8/D10, T918, StandardLane, StandardPipeline, NormalMangaIsolation, P5Honest, BatchDispatchResumeWiring (all) | Lease fencing, origin priority, stall watchdog, partial-download honesty, outcome typing; disk assertions are ledger/manifest read-backs after explicit flush or process-death simulation (D10:376-525, BatchDispatchResumeWiringTest:83-176) | **BC — safety net** | One borderline: BatchDispatchResumeWiringTest:92 "re-dispatch republishes nothing" is a write-suppression contract that group commit preserves |
| TranslationCoexistenceHarness.kt (1,254 lines) | Fakes only at sanctioned disk/graphics seams; store commits go through the REAL store (Harness.kt:394-421, 943-985); artifact-authority recipe (Harness.kt:862-902) | Infrastructure — keep | The harness is what makes the net real; T930 flag wiring should be added here once, not per-test |

### 2c. Pattern hits elsewhere (summarized)

| File:line | What it pins | Class | T930 impact |
|---|---|---|---|
| ChapterTranslationStorePersistenceTest.kt:58-71 | **`store.persistCount shouldBe 1` then `2`** — failed flush stays dirty and retried by next flush | **AMB→CD** | Durability contract (don't drop failed persists) expressed through the legacy-persist counter; converts if the legacy flat-file lane is replaced by staged commits at flag flip |
| ChapterTranslationStoreDefunctTest.kt:59-70, 125-128 | `persistCount` frozen after `markDefunct`; exactly 1 persist before defunct | **AMB→CD** | Same counter dependency |
| ChapterTranslationStoreArtifactMigrationTest.kt:294-314 | One durable baseline after explicit `flush()`; only changed page has candidate | BC | flush() = force-flush seam; survives |
| ChapterTranslationStoreArtifactMigrationTest.kt:411-421 | Reopen with unchanged legacy: manifest **bytes identical**, no `.bak` | BC (borderline) | Write-suppression preserved under group commit |
| ChapterTranslationStoreArtifactMigrationTest.kt:357-375, 378-408 | Artifact-only writes exact file set; legacy bytes never rewritten after cutover | BC | |
| TranslationManagerDeleteResetOrderingTest.kt:188-216 | Exact 11-event teardown sequence for deleteTranslation | **CD** (self-declared characterization, :33-41) | Any re-sequencing for drain-to-commit breaks the exact list; passes flag OFF |
| TranslationManagerDeleteResetOrderingTest.kt:220-249 | Reset sequence including **two `storeFlush` events at fixed positions (:243-245)** | **CD** | Pins exact flush count/position — first thing to change if reset adopts drain-to-commit |
| TranslationManagerDeleteResetOrderingTest.kt:252-293 | resetOcrData ordering incl. `storeFlush` position | **CD** | Same |
| TranslationManagerPendingAcknowledgementTest.kt:43-66 | Acknowledgement publishes **in-memory BEFORE the durable commit**; durable write queued on a persistence lane, drained explicitly (:60-62) | **BC — supports T930** | The pending-request store ALREADY implements the staged/emit-then-commit shape; this test is the in-repo precedent for the T930 flag path |
| TranslationManagerPendingAcknowledgementTest.kt:69-126 | Cancel/version-fence while commit in flight; exactly one surviving durable write | **BC — safety net** | Fenced-write semantics |
| ui/P5OutcomeProjectionTest.kt:130-146, 256-273, 340-354, 394-403 | UI truth keys on DURABLE display, never on a stale completed callback; persistence rejection projects "not saved"; queued slots never read as ready ("Queued." label :397) | **BC — safety net for item 5** | Under restricted UI-before-persist these invariants become MORE load-bearing; they are the honest-UI guard T930 must keep |
| ui/P5CopyAndAccessibilityTest.kt, P5VisibilityPrecedenceTest.kt, T918SheetRetryTruthTest.kt | Copy/visibility/typed-truth projection; reflection on method arity (P5CopyAndAccessibilityTest.kt:56) | BC (AMB harness) | |
| pipeline/batch/AnalysisChunkPublicationTest.kt:107-190, EnvelopePlanPublicationTest.kt:60-110, ProfileFreezePublicationTest.kt:82-120 | Sidecar+pointer "in one transaction"; fingerprint mismatch rejected before any byte written; byte-identical republication idempotent | **BC** | Transactional publication contracts; idempotence survives coalescing |
| pipeline/batch/OcrPreflightRejectedMidRunDurabilityTest.kt:208-241 | Mid-run checkpoint rejection records durable retryable failure, run stays restartable | **BC — safety net** | |
| pipeline/batch/BatchWriteGateHealTest.kt:90-142 | Lease identity drift heals; foreign lease never preempted | **BC — safety net** | |
| scheduling/RollingAutoCoordinatorTest.kt (33 tests) | Window/admission/fencing/epoch logic over fake executors; "ready-ahead reflects durable display results" (:363) reads in-memory state | BC | Admission-signal (#5) logic; no disk pins |
| ChapterResetPreflightTest.kt:12-38 | Counts durable artifacts/edits | BC | |
| OcrCheckpointRestartReuseTest.kt:185-426 | Checkpoint reuse across restart via `readManifest` after reopen/flush | BC | |
| TranslationPendingRequestStoreTest.kt:160 | `updatedAtEpochMs shouldBeGreaterThan 50L` (wall-clock lower bound after a 50ms sleep fixture) | AMB (flakiness, staleness domain) | Off T930 surface; loose bound |
| TranslationRequestGenerationFenceTest.kt:126 | Generation strictly increases | BC | |
| ActiveChapterStoreRegistryTest.kt:27-203, ChapterTranslatorTerminalExitsTest.kt:123-168, CleanedImagePublisherTest.kt:17-121, BatchTerminalExitTest, TranslationBatchProgressTracker* (combined **40 positional call sites** in 9 files) | `ChapterTranslationStore(null, null[, map])` positional construction | Structural (compile surface) | Safe iff T930 appends defaulted params; breaks compile if a param is inserted mid-signature |

**Persist-before-UI (item 4) verdict:** no test anywhere pins the current
`publishLocked` internal order (persist → emit, ChapterTranslationStore.kt:1894-1895)
for the chapter store. The only emit-vs-commit ordering pins are (a)
`TranslationManagerPendingAcknowledgementTest.kt:43-66`, which pins emit-BEFORE-commit
and therefore *supports* T930, and (b) `D11PermitFreeCommitTest.kt:126-128`,
which tolerates either order. The restricted UI-before-persist relaxation is
guarded, not blocked, by the P5 truth tests.

**updatedAtEpochMs timing (item 3):** only 3 exact pins in the whole tree
(ChapterArtifactStoreStaleManifestRetryTest.kt:126 — deterministic injected
value; TranslationPendingRequestStoreTest.kt:109,160 — different store, loose
bound). No wall-clock ordering pins on the manifest.

**Manifest rewrite-per-mutation:** no test asserts that a manifest write
happens per mutation. The pins run the other way (write suppression:
ChapterArtifactStoreTest.kt:447-448, ArtifactMigrationTest.kt:419-420,
BatchDispatchResumeWiringTest:92) — all survive coalescing.

---

## 3) Slice-A answer (question C)

**How many tests need CONVERSION for Slice A (flag default OFF): 0 behavioral
conversions; 2 structural hazards to respect while implementing.**

With the flag OFF, T930 must produce byte-for-byte today's IO choreography at
every seam the tests observe (`writtenNames`, `renamed`, `.tmp/.bak` sets,
`persistCount`, manifest bytes). Verified: no test asserts a manifest write per
mutation, no test pins wall-clock manifest timestamps, and no test pins
persist-before-emit for the chapter store — so there is nothing behavioral to
convert for Slice A.

What CAN break "just from the new code existing":

1. **Constructor compile surface — 40 positional call sites in 9 files**
   (`ChapterTranslationStore(null, null[, map])`: ActiveChapterStoreRegistryTest
   (14), TranslationBatchProgressTrackerTest (6), CleanedImagePublisherTest (4),
   TranslationBatchTrackerRegistryTest (4), TranslationBatchProgressTrackerTotalsTest (4),
   ChapterTranslatorTerminalExitsTest (3), BatchTerminalExitTest (2),
   BatchProgressProjectorDurableReconstructionTest (2),
   TranslationBatchProgressReducerTest (1)). Rule for the implementer: **append
   new constructor parameters with defaults; never insert mid-signature.** Then
   this costs zero conversions. (ChapterArtifactStore is always built with
   named args in tests — free.)
2. **`D6DrainNotCancelTest.kt:180-196`** selects the RollingAutoCoordinator
   constructor by exact arity 7 via reflection. If T930's writer registry adds a
   constructor parameter, this bridge returns null and the test fails claiming a
   bogus "§2.3 RED defect". Conversion needed *only if* that constructor
   changes: replace the arity probe with a direct constructor call (the comment
   at :170-175 says the bridge exists only because the grace param was once
   missing — that debt is paid).
3. Lower-probability: `Unsafe.allocateInstance` + `setField`-by-name fixtures
   (TranslationManagerDeleteResetOrderingTest.kt:93-97,295-308;
   TranslationManagerPendingAcknowledgementTest.kt:155-172) tolerate *added*
   fields; they break only on field renames. Harness note for T930: add new
   TranslationManager fields without renaming existing ones, or update ~3
   fixture methods.

For the **later flag-ON slices**, the conversion list is ~12-18 methods:
AtomicChapterDocumentsTest `validation failure...` + `URI-backed...rotation` (2),
SidecarCrashPublicationTest write-count assertions in 2 tests,
ChapterArtifactStoreTest `promotion reuses...` (1),
ChapterTranslationStorePersistenceTest `failed persist...` (1) +
ChapterTranslationStoreDefunctTest (2) [persistCount],
TranslationManagerDeleteResetOrderingTest (3). Each converts by re-expressing
the count/order pin as a commit-point assertion (durable-after-flush +
fail-closed), never by deletion.

---

## 4) Keep / convert / cull + safety-net share

| Category | Tests | Share of 1,914 | Action |
|---|---|---|---|
| **KEEP — untouched** | ~1,890 | ~98.7% | Everything asserting durability truth, crash windows, CAS fencing, retention, leases, UI truth, compute. Includes the entire safety net below. |
| **KEEP — load-bearing race/coexistence safety net** (subset of keep) | ~225 | **~12%** (range 10-15%) | coexistence/ 43 tests + harness; artifact/ transaction & crash suites ~85 (CheckpointOcrTransaction 14, SidecarCrash 8, StaleManifestRetry 3, RetireActiveRun 3, AtomicChapterDocuments 12, ChapterArtifactStoreTest ~40, Deletion 2); adjacent fence/durability: D6/D7/D9/D11-style properties in pipeline/batch (~25: write-gate, mid-run durability, publication transactions), manager durable reconstruction + pending-ack fencing (~10), P5 UI-truth (~38), reader teardown/admission (~15), translator/governor fences (~10). **These MUST survive T930 untouched — they are the spec of the seams T930 re-stages.** |
| **CONVERT** (re-point mechanics pins at commit-point equivalents; keep the invariant) | ~12-18 methods | ~0.8% | AtomicChapterDocumentsTest:25-40, 69-78 · SidecarCrashPublicationTest:87-88, 196-203 · ChapterArtifactStoreTest:1014-1036 · ChapterTranslationStorePersistenceTest:58-71 · ChapterTranslationStoreDefunctTest:56-70, 125-128 · TranslationManagerDeleteResetOrderingTest (3 tests) · D6DrainNotCancelTest:176-197 (arity bridge → direct ctor) |
| **CULL** | 0 mandatory | 0% | Audit rules forbid deletion; nothing on the collision surface is dead. `TranslationManagerDeleteResetOrderingTest`'s stated purpose ("pin before the region moves to ChapterDataResetController.kt", :33-41) is **expired** — the controller exists (app/src/main/java/eu/kanade/translation/manager/ChapterDataResetController.kt:33) — so it is the single honest convert-then-retire candidate, and only after the reset path adopts drain-to-commit. Referral to the staleness team. |

**Bottom line for the Director:** the suite is not overly restrictive against
T930. Under 1% of it pins mechanics that the redesign changes, and those pins
are concentrated in 6 files. The redesign's real obligation — enforceable in
review — is that the ~12% race/coexistence net (coexistence/, artifact/
transaction/crash suites, and the flush-as-commit-point tests) passes with zero
edits; if a T930 slice needs to touch one of those tests to make it pass, that
slice is wrong, not the test.
