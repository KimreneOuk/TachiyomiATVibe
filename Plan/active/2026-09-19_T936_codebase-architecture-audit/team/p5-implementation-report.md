# T936 Phase 5 implementation report

Branch: `t936/phase5-packages-hygiene`  
Date: 2026-09-20

Phase 5 completed in ticket order. The implementation commits are:

| Ticket | Commit | Result |
|---|---|---|
| P5-01 | `87244fe` | Merge `translation.recognition` into `translation.ocr` |
| P5-02 (storage) | `a9aeddb` | Move storage root files |
| P5-02 (orchestration) | `3b185d3` | Move orchestration root files |
| P5-02 (pipeline) | `de76bac` | Move pipeline root files |
| P5-03 | `331de00` | Rename 23 ticket-named test suites |
| P5-04 | `46a46fd` | Strip ticket tags and phase-history essays from comments |

## P5-01 — recognition → ocr

Moved the nine production recognition implementation files and four recognition tests into
`eu.kanade.translation.ocr`, then updated package/import references and the architecture note.
The old package reference grep is clean:

```text
git grep -n 'translation\.recognition' -- app/src
RECOGNITION_REFS=0
```

No ProGuard rule contained a stale `translation.recognition` FQCN; the remaining FQCNs resolve
under `translation.ocr`.

## P5-02 — structure root translation files

Moved the 17 root files as pure package/import moves, split into the ticket's three commits:

- Storage (5): `ActiveChapterStoreRegistry`, `ChapterTranslationStore`,
  `CleanedImagePublisher`, `TranslationPendingRequestStore`, and `TranslationQueueStore`.
- Orchestration (5): `ChapterResetPreflight`, `ChapterTranslator`, `ReaderEntryTrace`,
  `TranslationManager`, and `TranslationSession`.
- Pipeline (7): `MemoryPressurePolicy`, `PageTranslationKey`, `PostOcrStageSemantics`,
  `TranslationMemoryPressureForwarder`, `TranslationPipeline`, `TranslationStageContracts`,
  and `WriterOrigin`.

The package root now contains zero Kotlin files:

```text
app/src/main/java/eu/kanade/translation/*.kt: 0
```

## P5-03 — behavioral test names

Renamed all 23 files/classes from the ticket table, preserving each test body and assertion.
The new names describe the invariant rather than the delivery ticket (for example,
`D1OriginPriorityTest` → `ReaderManualPreemptsAutoLeaseTest` and
`T934WriteTimeDigestsTest` → `WriteTimeDigestsTest`). A body/assertion comparison found no
behavioral edits; only file/class/header naming references changed. The old-name filename grep
is empty:

```text
OLD_TICKET_TEST_FILES=0
```

The full test gate below also preserved the Phase 4 count: 2,025 test cases per flavor.

## P5-04 — comment hygiene

The ticket's README count (1,611 references) was stale for this checkout. The exact requested
baseline scan over `app/src` reported the following lexical matches before editing:

| Pattern class | Before |
|---|---:|
| `T9\d\d` | 1,837 |
| `D\d{1,2}` | 558 |
| `ST-\d\d` | 148 |
| `TX-\d\d` | 115 |
| `FF-\d\d` | 50 |
| `LI-\d` | 82 |
| `R1\.\d` | 12 |
| Total matches | 2,802 |
| Lines containing matches | 2,256 |

Removed the ticket tags while retaining the technical contracts, and deleted/reworked pure
phase-history header essays (including the former coordinator and batch-worker chronicles).
The review follow-up found three missed ticket prefixes in comments: the `T934` stranded-page
note in `TranslationBatchProgressTrackerTest.kt:101`, plus the `D8` and `D11` seam notes in
`NextPageAdmittedDuringParkedPublicationTest.kt:59` and `:77`. This follow-up rewords all three
while retaining their technical invariants. One intentional ticket-shaped comment residue
remains as a fixture path in `StageFingerprints.kt:481`:
`t924/golden/envelope-plan-small.json`. The corrected comment-residue ledger is therefore:
**3 reworded + 1 retained fixture-path exception**; no other ticket-tag comment residues remain.

The broad lexical recount still reports 488 residual matches (`T9=326`, `D=244`, `ST=7`,
`TX=0`, `FF=2`, `LI=1`, `R1=0`). These are false positives in executable code, string/log
content, test display names, variables, and fixture paths; they were not comment tags and were
left unchanged under the code-zero-diff and UI-string rules. The tag-free comment result is the
acceptance signal for this comment-only ticket.

The lexical code-diff audit over the 301 changed source files reported:

```text
FILES_CHECKED=301
CODE_DIFF_FILES=0
```

`git diff --check` is clean.

## Verification

All commands were run from the repository root with
`JAVA_HOME=C:\\Program Files\\Android\\Android Studio\\jbr`. Dev tasks temporarily copied
`app/src/standard/google-services.json` to `app/google-services.json`; every wrapper removed it
afterward (`GOOGLE_SERVICES_PRESENT=False`).

1. Compile both flavors:

   ```text
   ./gradlew :app:compileDevDebugUnitTestKotlin :app:compileStandardDebugUnitTestKotlin
   BUILD SUCCESSFUL in 6m 55s
   ```

2. Full serialized unit suites:

   ```text
   ./gradlew :app:testDevDebugUnitTest :app:testStandardDebugUnitTest --no-parallel --max-workers=1
   BUILD SUCCESSFUL in 7m 7s
   ```

   Result XML counts:

   | Flavor | Test cases | Failures | Skipped |
   |---|---:|---:|---:|
   | Dev Debug | 2,025 | 0 | 0 |
   | Standard Debug | 2,025 | 0 | 0 |

   No flakes or retries were required.

3. Assemble Dev APK:

   ```text
   ./gradlew :app:assembleDevDebug
   BUILD SUCCESSFUL in 2m 35s
   ```

4. APK inspection of all five files under `app/build/outputs/apk/dev/debug/`:

   | Check | Result per ABI APK |
   |---|---:|
   | `best_int8.onnx` entries | 0 |
   | `.md`, `.yml`, `.gitattributes` under `assets/models/ocr/` | 0 |
   | `assets/models/segmentation/manga109_bubble_int8.onnx` | 1 |
   | OCR `inference.onnx` entries | 2 |

   The two OCR entries are the detector and recognizer paths. The live segmentation asset is
   present in every ABI APK.

5. Acceptance checks:

   ```text
   ROOT_KT_COUNT=0
   RECOGNITION_REFS=0
   OLD_TICKET_TEST_FILES=0
   GOOGLE_SERVICES_PRESENT=False
   git diff --check: exit 0
   ```

The only worktree entry not belonging to this phase is the pre-existing modification to
`Plan/active/2026-09-19_T936_codebase-architecture-audit/README.md`; it was not staged or changed
by the phase commits.
