---
title: "Phase 3 Slice S4 Final Review: Retain Uncertain Cleaned Publications (Revision r1)"
kind: review
comments: none
---

# Phase 3 Slice S4 Final Review: Retain Uncertain Cleaned Publications (Revision r1)

**Verdict: APPROVED**

This is the independent cold-read review, static analysis, and verification audit of Slice S4 ([tickets/publication-policy](..)), evaluating the final implementation for retaining uncertain cleaned publications and enforcing never-accepted-live-name provenance across `CleanedImagePublisher.kt`, `ChapterTranslationStore.kt`, `CleanedPublication.kt`, and `CleanedImagePublisherTest.kt`.

All findings from Revision r0 have been verified as resolved in code, verified by dual-flavor unit test executions (`StandardDebug` and `DevDebug`), and confirmed clean against project code formatting rules.

## 1. Pinned Source Hashes (Revision r1 Final)

The delivered implementation matches the frozen source manifest evaluated by the authorized test gates:

| File | Size (bytes) | SHA-256 Hash | Status |
| --- | --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/persistence/chapter/CleanedImagePublisher.kt` | 8,639 | `5E164DE8E50D25011A86E19AE996954BB44EAA39639BBAA6290C671ED5163A76` | Verified / Approved |
| `app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt` | 255,151 | `63F53E2A2B4CBD1DE8458206DAB4D3EF9A004EFCCF71B6F89F9342D02C31DC13` | Verified / Approved |
| `app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt` | 26,036 | `E8AE77C6474BE1272012EE79C08B74AECB1E6A9FDDB8064ABA2BE3A6FB35A5BE` | Verified / Approved |
| `app/src/test/java/eu/kanade/translation/persistence/chapter/CleanedImagePublisherTest.kt` | 14,708 | `4B32AD89648DAA4F10400B3D53E46134C600EFFBFA35F457CD39C7AE28E4A0A6` | Verified / Approved |

## 2. Verification of Blockers & Findings from r0

### Resolution of Finding F1 (Test Suite Overhaul & Clean Compilation)

- **Previous Defect**: Duplicate test method name and unresolved reference to `pagesSnapshotForTest`.
- **Verified Fix**:
  - `CleanedImagePublisherTest.kt` was rewritten into 11 unique, self-contained tests without invented APIs or ghost references.
  - All 11 tests execute purely against direct production APIs (`CleanedImagePublisher`, `ChapterTranslationStore`).
  - No sleeps, no flaky timing dependencies, and no long-held lock leaks.

### Resolution of Finding F2 (Never-Accepted Provenance vs. Point-in-Time Authorization)

- **Previous Defect**: The store's `authorizeCleanedImageDeletion` only verified point-in-time unreferenced status under `mutex`. If a lazy path accepted a live name that was subsequently cleared or reset mid-flight, a rejected hash patch would authoritatively delete a name that had been live and displayed.
- **Verified Fix**:
  - **Caller Seam Policy (`mayDeleteRejected: suspend (String) -> Boolean = { false }`)**:
    - The seam defaults fail-closed to `false` for any unknown or uncertain provenance.
    - **In-Phase Publication (`CleanedPublication.kt:212`)**: Passes `mayDeleteRejected = { name -> store.authorizeCleanedImageDeletion(pageKey, name) }`. This is safe because the in-phase pipeline writes a fresh, private, versioned file whose only possible point of acceptance is this single commit attempt. If the commit is rejected, the caller possesses absolute knowledge that the name was never accepted live. Combining this with the store's mutex-held `!isReferenced` check satisfies the safety contract.
    - **Lazy Publication Receipt Worker (`CleanedPublication.kt:423`)**: Passes `mayDeleteRejected = { _ -> false }`. Because the lazy receipt worker exists only after an earlier phase already accepted a live name, the rejection of the subsequent hash-patch commit does not prove that the name was never accepted. The seam unconditionally fails closed, retaining the file as uncollected debt even when the store's point-in-time check would have authorized deletion.
  - **Deterministic Proof Test**:
    - `prior-live-then-cleared rejected name is retained even though the store would authorize it` explicitly models this hazard: seeds `new.jpg` into live state, clears the pointer to `null`, verifies that `store.authorizeCleanedImageDeletion("p1", "new.jpg") == true`, and asserts that the lazy publisher fails closed, retaining the image.
  - **Store Contract & KDoc Honesty**:
    - `ChapterTranslationStore.authorizeCleanedImageDeletion` KDoc explicitly documents that `mutex` protects only the decision, the physical deletion occurs outside the lock after return, and deletion is safe only for callers with independent never-accepted provenance.

### Resolution of Finding F3 (Throw/Cancel Retain Matrix & Diagnostics)

- Commit throws and cancellations (`CancellationException` and general `Throwable`) unconditionally retain `newName` and return `unpublishedCleaned = false` (or rethrow the cancellation).
- No blind registration into `retiredCleanedImages` occurs for uncertain files.
- Uncollected retained debt is logged at `ERROR` level (`"TachiyomiAT cleaned publication rejected-name deletion not authorized: ... uncollectedDebt=true"`), fulfilling the architectural ledger requirements.

## 3. Execution & Test Evidence Audit

Test evidence was audited from registered monitored shells under `C:/Users/ADMIN/AppData/Local/Temp/opencode/phase-3-s4`:

### Dual-Flavor Unit Test Execution

1. **Gate 1: StandardDebug (`:app:testStandardDebugUnitTest`)**
  - **Shell**: `3e53459e-a14f-4c03-98cf-b002bbccfdf1` (run id `f4b8616c-0190-4547-b8e8-354bd998bc88`, directory `s4-std`, duration 52s, exit 0).
  - **XML Results**:
    - `CleanedImagePublisherTest`: 11 tests, 0 failures, 0 errors, 0 skipped.
    - `ChapterTranslationStoreLazyPersistenceTest`: 16 tests, 0 failures, 0 errors, 0 skipped.
  - **Total**: 27/0 green.
2. **Gate 2: DevDebug (`:app:testDevDebugUnitTest`)**
  - **Shell**: `ac2ad4d5-3b47-45f2-a364-5f911008d930` (run id `f20bd145-6590-4471-9652-cd1cb7c7bf40`, directory `s4-devdebug`, duration 46s, exit 0).
  - **XML Results**:
    - `CleanedImagePublisherTest`: 11 tests, 0 failures, 0 errors, 0 skipped.
    - `ChapterTranslationStoreLazyPersistenceTest`: 16 tests, 0 failures, 0 errors, 0 skipped.
  - **Total**: 27/0 green.
3. **Flavors Verified**: Both required debug flavors (`StandardDebug` and `DevDebug`) executed and passed without test omission or substitution of release variants.

### Code Formatting Audit

- **Task**: `:app:spotlessKotlinCheck` via shell `1151a709-9171-4a36-90bf-c01f36be1c07` (directory `format-s4`).
- **Log Inspection**: Complete inspection of `spotless-check.log` confirmed zero formatting violations across all four S4 files (`CleanedImagePublisher.kt`, `ChapterTranslationStore.kt`, `CleanedPublication.kt`, `CleanedImagePublisherTest.kt`). The sole reported violation is pre-existing debt in `LiteRTMangaInpaintingEngine.kt`.

## 4. Bounded Proofs & Architectural Tradeoffs

The implementation and verification boundaries are strictly bounded as follows:

1. **Outside-Lock Delete Proof**:
  - The test `rejected never-registered name is store-authorized and deleted outside the store lock` verifies event sequence `[write, commit, authorize, delete]`.
  - The proof that deletion runs outside the store lock is two-part: the event order proves physical delete executes strictly after `authorizeCleanedImageDeletion` returns, and source inspection confirms `authorizeCleanedImageDeletion` executes `mutex.withLock { ... }`, thereby releasing the lock before returning. The event log alone does not assert lock acquisition state.
2. **Deterministic Same-Name Modeling**:
  - The test for prior-live-then-cleared retention configures fake file writes to simulate identical filename resolution. This serves as a deterministic test fixture for the policy seam, not a formal proof of how production filesystems or hash-based naming engines collide.
3. **Unbounded Retained Debt vs. Storage Sweeps**:
  - By design, retaining files on commit throw, cancellation, or uncertain lazy hash-patch rejection creates unbounded uncollected debt on disk. This trade-off intentionally prioritizes safety against use-after-free over storage reclamation. Reclaiming these orphan files is left to future explicit sweep mechanisms outside the scope of S4.
4. **Host-Only Evidence**:
  - All validation was conducted on the JVM host unit test harness. Real device filesystem latency, flash I/O concurrency, and Android lifecycle sweeps remain unproven until device testing gates (D-gates).

## 5. Review Disposition & Next Steps

**Status: APPROVED**

- Slice S4 implementation satisfies all requirements of the architectural plan and resolves all pre-validation critique findings.
- **Slice S5 Hold**: Slice S5 ([tickets/owner-cutover](../../owner-cutover)) remains on hold until the parent auditor completes acceptance and commits Slice S4 to git.
