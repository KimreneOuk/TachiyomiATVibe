# HF-04 — Independent Review Report (lazy preflight durability barrier)

Date: 2026-09-22
Reviewer: Independent Reviewer (adversarial verification; barrier placement checked against
every manifest-reading acceptance boundary, deadlock analysis by call-graph, fingerprint
stability traced through the lazy preference logic, testcase-level XML inspection)
Branch reviewed: `t936/hotfix-batch-rejection` @ `683c784`, base `main` @ `d6ebc87`. 3
commits; 17 files, +855/−20 (includes 8 committed flake/isolation evidence XMLs).

## Verdict

**PASS WITH NOTES**

The device failure is correctly diagnosed (lazy-queue ordering at the preflight durability
boundary — not an unpadded-key bug; the STOP-gate honestly falsified the padded-key theory
without touching key derivation), the barrier is placed at the rejecting boundary with
deadlock-freedom argued from the call graph, identity is stable after one drain and pinned
by a repeated-flush test, and error-origin truth is deterministic in both projections.
Notes are minor (substring classification, checkpoint-reuse probe read, one untested
end-to-end leg).

---

## 1. Barrier correctness — PASS (boundary census)

The fix: `PreflightWorker.checkpointPage` flushes the store BEFORE reading the
manifest-backed checkpoint identity — the exact boundary that rejected `001.jpg` on device.
The report claims keys were exact end-to-end and the STOP-gate (do not change key
derivation if keys match) was honored: the trace table shows `001.jpg` preserved verbatim
through enumeration, pre-registration, checkpoint, and candidate writes, and **no
key-normalization change exists in the diff** (verified: the fix touches only flush
ordering, identity preference, and error categorization).

**Boundary census (manifest reads feeding acceptance decisions):**

| Boundary | Status |
|---|---|
| Per-page preflight checkpoint (the device failure site) | **Fixed** — flush-before-snapshot. |
| Checkpoint-reuse probes (`checkpointReuse`) | Reviewed, benign: a stale read finds no pointer → falls through to run OCR (wasted work, not a rejection); the page's own checkpoint then flushes before its durable write. See Note 2. |
| Batch session end | Barrier pre-exists (HF-03): `flushAllActiveStores` → per-store `flush()` drains lazy tasks + mutations before close. Bonus: the reset/delete paths (`ChapterDataResetController` flush calls) now also drain lazy queues before destructive operations. |
| Envelope-plan / OCR_PREFLIGHT manifest reads | Ordered after per-page checkpoints (each of which flushed), and artifact-engine manifest writes (OCR_PREFLIGHT publication) remain synchronous — not lazy-queued. |
| Preflight `priorRecord` read (run start) | Runs before any page work — nothing is queued yet on a fresh run; on re-dispatch a stale read degrades to a fresh-run treatment, not a rejection. |

The barrier is not a one-site patch: every boundary that can reject on a stale manifest
either flushes, is ordered after a flushing boundary, or degrades to wasted work rather
than rejection.

## 2. Deadlock freedom — PASS

`store.flush()` acquires the persistence scheduler's serialization (backed by the store
mutex) only from contexts that do NOT hold the store mutex:

- The new call site is in the preflight worker's own flow — the worker holds page leases,
  never the store mutex; the immediately following `store.checkpointOcr(...)` already had
  the same precondition (it takes the mutex itself). The diff comment states this
  explicitly.
- Call-graph: flush tasks (`enqueueLazyPersistence` work lambdas — CleanedPublication's
  JPEG write + guarded page patches) execute OUTSIDE the store mutex inside `flush()`
  (only `takeLazyPersistenceTask` and `flushDirtyLocked` take it), so a task that needs the
  store mutex cannot self-deadlock against `flush()`; and **no flush task calls back into
  `checkpointPage`** (the only enqueuers are CleanedPublication and the page-mutation
  queue — verified by call-site census).
- The other external `flush()` callers (PreparedPageBoundary from the HF-03 hotfix,
  ChapterDataResetController) were already verified outside store locks in prior reviews.
- **Test exercising checkpoint-during-active-flush:** `OcrPreflightCoordinatorTest.lazy
  preflight drains exact zero padded registration before checkpoint` drives the real
  coordinator over a lazy-enabled store with a queued registration — the checkpoint runs
  while the lazy queue is non-empty (the flush happens inside `checkpointPage`, mid-run)
  and completes without deadlock, asserting the manifest and checkpoint afterwards.

## 3. Fingerprint stability — PASS

The lazy-only identity preference (`snapshot.candidateGenerationId ?: ref…`, fingerprint
from the snapshot only when the manifest actually carries a candidate) reads identity AFTER
the drain, so:

- The post-drain manifest identity is content+generation-derived and deterministic —
  repeated submissions read the same values (no ref↔snapshot flip-flop: once the manifest
  has a candidate, the snapshot branch wins consistently on every subsequent read).
- The device cascade (candidate writes rejecting with "dependency fingerprint changed"
  against a stale pre-drain identity) cannot recur: candidate writes validate against the
  identity the checkpoint itself just established from the drained manifest.
- Pinned by `ChapterTranslationStoreLazyPersistenceTest.zero padded page keeps one candidate
  dependency across repeated lazy flushes` — two submissions + two flushes assert the SAME
  `generationId` and `dependencyFingerprint` (`= runTest`, alive).
- Synchronous stores keep the exact prior expectation semantics (old diff lines preserved
  verbatim in the non-lazy branches), and `OcrPreflightRejectedMidRunDurabilityTest` (2/2
  green) confirms the deliberate stale-reference rejection contract still holds there.

## 4. Error-origin truth — PASS

`DurableFailureMetadata.toUiPauseReason()`: PROTOCOL-category failures get distinct copy —
"Page manifest mismatch:", "Page fingerprint mismatch:", "Page checkpoint rejected:", else
"Page consistency check failed:"; non-PROTOCOL (provider) failures keep their own message,
so provider-outage copy now appears only for genuine provider admission/breaker failures.

- Both projections categorized identically: the store's `progressSnapshot` and the
  manager's `withDurablePause` use the same PROTOCOL-first deterministic ordering
  (`compareBy(protocol-first, stage ordinal, pageKey)`) — deterministic selection, no
  reason churn.
- The category wiring is real, not cosmetic: `PreflightFailureKind.CHECKPOINT_REJECTED` →
  `FailureCategory.PROTOCOL` (ChapterProfileBatchCoordinator:1910), and
  `ProviderFailureKind.PROTOCOL` → `FailureCategory.PROTOCOL` in BatchWriteGate — the
  device's checkpoint and candidate-write rejections both land in the PROTOCOL bucket.
- Pinned by the reworked `TranslationManagerPausedAffordanceTest`: an OCR-stage PROTOCOL
  failure with message `page missing: pageKey=001.jpg` renders exactly
  `"Page manifest mismatch: page missing: pageKey=001.jpg"` — not provider-outage wording —
  while the no-failure case still projects untouched.

## 5. Tests — PASS

- 2 new tests + 1 strengthened/renamed test; all `runTest`/block bodies (alive — verified
  executing in XML: LazyPersistence 7/7, OcrPreflightCoordinator 5/5, PausedAffordance 5/5,
  OcrPreflightRejectedMidRunDurability 2/2).
- Padded end-to-end: `OcrPreflightCoordinatorTest` drives registration → OCR → checkpoint →
  artifact manifest/checkpoint reads with `001.jpg` exact (asserts exact key sets — no
  `1.jpg` anywhere). Fingerprint stability: the repeated-lazy-flush test. Error-origin: the
  PausedAffordance copy assertions. See Note 3 for the one leg not directly asserted.
- The PausedAffordance rename deliberately broadened pause-truth semantics (protocol
  failures from every stage now surface; previously translation-stage-only) — the old
  "non-translation stage → untouched" assertion was removed as part of the intended
  change, with the no-failure case retained. Documented so it is not mistaken for
  weakening.

## 6. Scope — PASS

Diff touches exactly: `PreflightWorker` (barrier + identity preference),
`ArtifactContracts` (UI pause-reason mapping), store + manager projections (failure
selection + copy), and the three test files. No key-derivation change (STOP-gate honored),
no session/gating files, no HF-01/02/03-hotfix surfaces, no hardware/asset changes.

## Evidence — PASS

- **XML: 2,047 tests × both flavors, 295 files each, 0 failures / 0 errors / 0 skipped**
  (fresh, 09-22 09:58 Dev / 10:04 Standard). Arithmetic reconciles: 2,045 (HF-03 hotfix) +
  2 new tests = 2,047 ✓.
- Committed flake evidence: 2 failing-run XMLs + 6 isolation XMLs (3/3 each) for the two
  known load-flake signatures; final full runs green on both flavors.
- APK clean (2,042 entries; best_int8 0; OCR docs 0; manga109 1; inference.onnx 2;
  aot-512/aot present). Working tree clean; `app/google-services.json` absent.

## Notes (non-blocking)

1. **Substring-based classification:** `toUiPauseReason()` matches "page missing" /
   "fingerprint" / "checkpoint" against the failure message text. The matched literals are
   engine-owned constants (`ChapterArtifactEngine`), so this is stable today, but a future
   literal edit would silently degrade to the generic (still-correct) "Page consistency
   check failed" branch. Consider typed reason kinds in a later pass.
2. **Checkpoint-reuse probes read the durable manifest without a barrier** — benign today
   (stale read → wasted re-OCR, never a rejection; each page's own checkpoint flushes
   before its durable write), but it is a second reader of manifest state that relies on
   the per-page barrier indirectly. If a future change makes reuse-miss costly, revisit.
3. **Tail reconciliation leg:** the coordinator regression test pauses at the single-page
   corpus boundary, so registration→checkpoint→artifact is asserted end-to-end but the
   padded-key tail-reconciliation leg is covered only indirectly (via the canonical-layout
   retention contract tested elsewhere). Acceptable; noted for completeness.
4. Report accuracy: verified — counts, signatures, suite results, and the STOP-gate
   narrative all match the evidence on disk.

## Conclusion

The third store-lifecycle fix in this arc is the cleanest: the stop-gate was honored (keys
were proven exact, so nothing was "fixed" that wasn't broken), the barrier targets the
actual rejecting boundary with deadlock-freedom argued from the real call graph, identity
stability is pinned across repeated flushes, and the UI can no longer blame the provider
for a store consistency rejection. Merge-ready from this reviewer's standpoint.
