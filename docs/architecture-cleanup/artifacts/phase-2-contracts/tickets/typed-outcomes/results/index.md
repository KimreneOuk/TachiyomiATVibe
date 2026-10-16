---
title: "P2.2 typed-outcomes — results and evidence"
kind: spec
comments: none
---

# P2.2 implementation results

Ticket `phase-2-contracts/tickets/typed-outcomes/index.md` on `architecture-cleanup` @ `d8252cbe` (P2.1 commit). Typed rejection semantics for `SinglePageOutcome.Rejected` plus one narrow package-boundary test. Sole implementation/Gradle owner; no commits/pushes/subagents/devices/models; reader queue, scheduler behavior, persistence authority, native/bitmap lifecycle and UI state machine untouched; report SHA256 `EFC11B90…88DDD` unchanged.

# Exact change (20 meaningful paths = 18 existing modified + 2 new; plus 1 temporary status entry)

18 existing modified paths: 4 production files, 13 existing test files, 1 `app/build.gradle.kts`. 2 new files. One further path (`engines/vision/ocr/RoiPageRecognitionEngine.kt`) currently shows as modified but is **CRLF-only restoration residue with an empty content diff** — a temporary status entry pending byte-level restoration after the review freeze; it carries no P2.2 change.

| Path | Δ | Content |
| --- | --- | --- |
| `model/SinglePageOutcome.kt` | NEW (70 lines) | `SinglePageOutcome` sealed interface moved verbatim from `pipeline/execution/TranslationExecutor.kt` (same KDoc, same members) except `Rejected`, now `Rejected(owner: PageWriteOrigin?, kind: PageRejectionKind, diagnosticDetail: String? = null)` — mandatory kind, no default category, no string constructor. `PageRejectionKind` = SESSION_DENIED / ALREADY_OWNED / PERSISTENCE_FAILED / OTHER (smallest set over the five actual producers). Keeps the pre-existing `PageWriteOrigin` (persistence.chapter) dependency, nothing else. Reviewer-noted (pending post-review wording fix): the PERSISTENCE_FAILED KDoc "no durable result exists" is imprecise where an older committed display record survives the rejected publication. |
| `pipeline/execution/TranslationExecutor.kt` | 1/34 | sealed interface block removed; `import model.SinglePageOutcome` added; PreparedPage/TranslationStageEvent/TranslationCompletionOutcome contracts untouched |
| `pipeline/TranslationPipeline.kt` | 18/13 | lease denial maps owner-known → ALREADY_OWNED, owner-less → OTHER (decision from `acquisition.owner`, never from `acquisition.reason` text; reason becomes diagnosticDetail); both in-flight membership sites → ALREADY_OWNED; PersistenceRejected → PERSISTENCE_FAILED carrying `outcome.reason` as diagnosticDetail; `REASON_TRANSLATION_NOT_SAVED` constant + KDoc removed |
| `scheduling/TranslationScheduler.kt` | 7/2 | reader-session gate → SESSION_DENIED with the old text as diagnosticDetail |
| `presentation/TranslationUiTruth.kt` | 40/14 | mapper keys off `owner == BATCH` (unchanged precedence) then `kind`: PERSISTENCE_FAILED → existing NOT_SAVED truth (warning/retry preserved); ALREADY_OWNED / SESSION_DENIED / OTHER → three new fixed INFO truths ("…already active." / "…reader session is busy." / "Translation not started."), DETAILS-only, no retry; raw `rejection.reason` interpolation removed; `TranslationPipeline` import removed (was only for the constant); diagnostic detail never reaches labels/accessibility |
| `app/build.gradle.kts` | 4/0 | one `systemProperty("translation.boundary.srcMainJava", project.layout.projectDirectory.dir("src/main/java").asFile.absolutePath)` in the existing `Test.configureEach` block; no other build change |
| `test/architecture/TranslationPackageBoundaryTest.kt` | NEW (264 lines) | see below |
| 1**3 existing test files** — all listed explicitly |  |  |
| — `coexistence/RenderPermitReacquisitionTest.kt` | 1/1 | **import-only** (`model.SinglePageOutcome`, ASCII-sorted) |
| — `scheduling/TranslationSchedulerTraceTest.kt` | 1/1 | **import-only** |
| — `scheduling/TranslationSchedulerManualOutcomeTest.kt` | 1/1 | **import-only** |
| — `scheduling/RollingAutoCoordinatorTraceTest.kt` | 1/1 | **import-only** |
| — `scheduling/RollingAutoCoordinatorTest.kt` | 1/1 | **import-only** |
| — `scheduling/RollingAutoCoordinatorRegistrationRaceTest.kt` | 1/1 | **import-only** |
| — `scheduling/CancelSyncStoreWriteTest.kt` | 1/1 | **import-only** |
| — `scheduling/CancelBatchLeaseSkipTest.kt` | 1/1 | **import-only** |
| — `coexistence/AutoProviderCallDrainsNotCancelsTest.kt` | 2/2 | **mechanical FQN swap** (inline qualified names → `model.SinglePageOutcome`; no import statement in file) |
| — `workflow/TranslationSessionCoordinatorTest.kt` | 2/2 | **behavioral**: FQN swap + assertion retyped to `.kind == PageRejectionKind.SESSION_DENIED` (was `.reason == "reader session rejected: BATCH_ACTIVE"`) |
| — `coexistence/PipelineStallWatchdogTest.kt` | 2/1 | **behavioral**: reflection assertions `getMethod("getReason")` → `getMethod("getKind")` = "ALREADY_OWNED" + `getMethod("getDiagnosticDetail")` = "page already translating" |
| — `coexistence/P5HonestOutcomeTypingTest.kt` | 6/4 | **behavioral**: import swap + `PageRejectionKind` import; `.reason == <stable string>` → `.kind == PERSISTENCE_FAILED`; clue/error wording retyped |
| — `presentation/P5OutcomeProjectionTest.kt` | 66/6 | **behavioral**: constructors retyped; duplicate test retitled to fixed-safe-copy with contentDescription pinned; 2 new tests (see below) |

Tree state: 18 modified tracked files + 2 new untracked files + the untracked original report.

# New semantic tests (P5OutcomeProjectionTest, 18 → 20 tests)

1. `duplicate rejection projects fixed safe copy without diagnostic text` — retyped constructor, fixed label/cd pinned, no retry actions.
2. `same kind with divergent diagnostic detail projects identical truth` — `ALREADY_OWNED` with internal detail vs null detail project byte-identical label/cd/severity/retry/actions; label and cd contain none of the diagnostic text.
3. `session denial and ownerless rejection project their fixed categories` — SESSION_DENIED and OTHER fixed copies; assertions explicitly assert the diagnostic tokens (BATCH_ACTIVE, "unknown") do NOT appear in labels.

Existing tests preserved: BATCH precedence (owner=BATCH → BATCH_SESSION_SWITCH regardless of kind), persistence warning/non-success even alongside a DISPLAY_READY durable record, partial-refresh committed-output precedence — all unchanged and green. Failed.reason semantics untouched; no durable/scheduling representation merged.

# Boundary scanner (TranslationPackageBoundaryTest)

- Shared detector masks comments, string literals, raw strings, and char literals (escape-aware, newlines preserved), then flags (a) `import eu.kanade.translation.pipeline…` ordinary/aliased/wildcard declarations and (b) remaining executable qualified references incl. `::` callable syntax. Production scan walks only `eu/kanade/translation/engines` and `…/presentation` under the system-property source root (never the shell cwd); `engines→persistence/model/domain` edges are legal and not scanned.
- Fixtures in the same file: ordinary/alias/wildcard imports, FQ reference, Java-style qualified reference, callable reference, multi-line file with exactly one violating line; clean cases: line comment, block comment, KDoc link, string literal, raw string, char literal, escaped-quote string. Stated limitation (KDoc + review note): string-template `${…}` expressions are masked with their string, so a reference occurring only inside a template is not detected.
- Planted-violation proof: `import eu.kanade.translation.pipeline.TranslationPipeline` was added to `engines/vision/ocr/RoiPageRecognitionEngine.kt` and removed after detection. Evidence: shell `cb9994dd` — compile first failed on the test file itself (KDoc containing `/**` nest-opening sequences — fixed; documented in the test's KDoc), then ran with `tests=3 failures=2`: production-scan test FAILED naming the plant + fixture line-number assertion failed (real scanner bug, fixed: fixture expected line 5, actual 4; and `resolve("engines")` missed the `eu/kanade/translation` prefix — fixed). RED XML preserved at `planted-red/xml/`. After plant removal: boundary test 3/0 in both flavors.

# Validation

| Run | Shell | Exit | Result |
| --- | --- | --- | --- |
| Pre-baseline attempt (combined selector) | `0b2b01b4` | 1 | **selector failure, no executed tests** (`--tests=A,B,C` is one literal pattern); its XML copies were stale build-dir residue, marked invalid |
| Pre-baseline corrected | `3c03bec7` | 0/0 | 4 classes × both flavors green: 6+18+1+7 = 32/0 per flavor; XML at `pre-baseline-corrected/{dev,std}/xml` (mtimes/classnames/counts verified; provenance in `pre-baseline/PROVENANCE.md`) |
| Planted RED | `315f0484`, `cb9994dd` | 1 | first: test-file compile error (KDoc `/**` nesting — fixed); second: boundary test 3 tests, 2 failures naming the planted file — XML preserved |
| Post GREEN attempt | `7e0cfaf9` | 1/1 | boundary 3/0; P5 20 tests / **2 failures both flavors** — test-file dash characters had been corrupted to U+FFFD by an intermediate encoding accident during import reordering (reported verbatim: `expected:<…???> but was:<…?>`); XML preserved at `post-change/`; all touched files scanned, exactly 2 U+FFFD repaired to U+2014 |
| Post GREEN corrected | `46b0153f` | 0/0 | **37/0 per flavor** fresh compile (boundary 3, honest-typing 6, projection 20, manual 1, coordinator 7) |
| `:app:spotlessCheck` (read-only) | same | 1 | two files flagged: the known pre-existing `LiteRTMangaInpaintingEngine.kt` wrapping debt and `engines/vision/ocr/RoiPageRecognitionEngine.kt` (CRLF working-copy residue from this slice's PowerShell rewrite; initially missed by an over-narrow log extraction, corrected during P2.4 accounting) — zero semantic violations in any touched file; the RoiPage CRLF residue was resolved in this ticket's finalization by the authorized exact-HEAD-bytes restoration Disposition note (P2.4 finalization): this slice's spotless reporting is reclassified as partial-extraction until the final verified run; the complete verified inventory across all Phase-2 runs is consolidated in the chapter-replacement results (final state: LiteRT only, a5aff896). |

Encoding incident disclosure (reviewer-relevant): a PowerShell `Get-Content`/`Set-Content` import-reordering pass momentarily double-encoded non-ASCII text (mojibake) and added BOMs in 14 files it rewrote. Detected via diff review, reversed byte-exactly (cp1252↔UTF-8 round-trip + BOM/`\uFEFF`→`?` line-1 artifact removal), and verified: final `git diff` shows only intended lines; U+FFFD scan over all touched files is clean. **Not** entirely clean, however: the new boundary test file contains **one verified literal NUL byte** (line 84, the look-ahead fallback `else '<NUL>'` in the masking lexer's `next` placeholder — behaviorally inert, the value is only compared against specific delimiters, never emitted), a leftover of the original file Write; an escaped-char fix (`'\u0000'` or `' '`) is **pending after the review freeze**. `NeuralInpaintingCoordinator.kt` also matched a mojibake grep but is pre-existing repo state (untouched, out of scope).

# Limits

- Focused suites only (per ticket); full-suite and parity runs remain the parent's gate.
- The four ledgered ArtifactOpenProbe failures, LiteRT wrapping debt, JDK21-local/JDK17-CI parity, device/JNI measurements: unchanged, still deferred.
- Boundary scanner is narrow by design; template-expression blind spot documented, not hidden.
- Historical review conditions (all addressed in Finalization below): (1) escaped-char replacement for the literal NUL in `TranslationPackageBoundaryTest.kt:84`; (2) PERSISTENCE_FAILED KDoc wording in `model/SinglePageOutcome.kt` ("no durable result exists" → acknowledge a surviving older committed display); (3) byte restoration of `RoiPageRecognitionEngine.kt`'s CRLF-only working-copy state.
- No scheduler/persistence/native/UI-rewrite changes; no Phase 2.3 preparation; no execution of the older Deferred/cache plan.

# Finalization (3 authorized fixes applied post-review)

Independent review verdict: ACCEPTED WITH CONDITIONAL REVISIONS — exactly the 3 known issues, no additional findings (`tickets/typed-outcomes/review/index.md`; note: its "four producers" phrasing means the four enum categories over five constructor sites). Bounded finalization executed, everything else frozen:

1. **NUL escape** — the single literal NUL byte in `TranslationPackageBoundaryTest.kt:84` (lexer look-ahead fallback) replaced with the source escape `'\u0000'` via a binary byte splice (guard-verified quote surroundings, NUL count re-verified 0; six ASCII characters replace one byte, net +5 bytes). The new test file is no longer binary-detected.
2. **KDoc precision** — `SinglePageOutcome.Rejected` and `PageRejectionKind.PERSISTENCE_FAILED` docs amended to "current attempt rejected, an earlier committed display record can remain"; no contract/type change.
3. **RoiPageRecognitionEngine.kt restored to exact HEAD bytes** — via `git show HEAD:<path>` cmd-redirected byte dump (PowerShell-safe, 74,394 bytes); normalized (CRLF→LF) old-vs-HEAD equality verified BEFORE write so no meaningful edit was lost; post-write byte-identity verified; path no longer appears in `git status`.

Post-fix verification: the primary independently scanned all 20 meaningful touched/new paths for literal NUL, UTF-8 BOM and U+FFFD; all clean. RoiPageRecognitionEngine is byte-identical to HEAD. `git status` = exactly the 20-path manifest (18 modified + 2 new), plus the original untracked report; report SHA256 unchanged `EFC11B90…88DDD`. Primary XML check: finalization dev/std each contain five suites, 37 tests, zero failures/errors/skips. These checks support source hygiene and the observed focused outcomes, not general runtime equivalence.

| Run | Shell | Exit | Result |
| --- | --- | --- | --- |
| Finalization (5 focused classes × both flavors, `--rerun` fresh compile) | `3ac9c9d3` | 0/0 | **37/0 per flavor** (boundary 3, honest-typing 6, projection 20, manual 1, coordinator 7); XML at `finalization/{dev,std}/xml` |

No expanded/full-suite or spotless rerun (no failure, no new review finding; read-only spotless standing evidence from `46b0153f` remains valid — fixes touched one byte escape, two KDoc strings, and restored bytes identical to HEAD).

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- Baseline: `scratch/phase-2-contracts/typed-outcomes/pre-baseline-corrected/{dev,std}/xml` (+ `pre-baseline/PROVENANCE.md`)
- RED: `scratch/phase-2-contracts/typed-outcomes/planted-red/xml`
- Failed intermediate: `scratch/phase-2-contracts/typed-outcomes/post-change/{dev,std}/xml`
- Final GREEN: `scratch/phase-2-contracts/typed-outcomes/post-change-green/{dev,std}/xml`
- Finalization GREEN: `scratch/phase-2-contracts/typed-outcomes/finalization/{dev,std}/xml`
- Logs: `commands/…/{0b2b01b4,3c03bec7,315f0484,cb9994dd,7e0cfaf9,46b0153f,3ac9c9d3}/output.log`

# Next

Finalization complete per authorized bounded scope; awaiting parent's final review recheck and commit. No commits/pushes, no P2.3.
