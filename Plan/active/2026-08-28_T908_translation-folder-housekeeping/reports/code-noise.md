# T908 Code-Noise Audit — `eu/kanade/translation/`

Date: 2026-08-28
Scope: `app/src/main/java/eu/kanade/translation/` and `app/src/test/java/eu/kanade/translation/`
Mode: read-only audit. Branch at audit time: `t907/fix` @ `2085c03`.

## Summary

The translation codebase is **exceptionally clean**. Across 296 Kotlin files /
66,335 lines (167 main files / 44,439 lines; 129 test files / 21,896 lines) the
audit found **zero** stale action markers, zero commented-out code, zero dead
branches, zero debug logging, and zero unused translation resources. The only
real noise is **4 small copy-pasted helper groups** (the largest ~45 duplicated
code lines) and **14 production files above 600 lines**, dominated by
`TranslationPipeline.kt` at 5,415 lines.

| Checklist item | Count | Verdict |
| --- | --- | --- |
| Stale markers (TODO/FIXME/HACK/XXX/WIP/@Deprecated/workaround/temporary) | 7 hits, 0 actionable | Clean |
| Commented-out code blocks | 0 (9 candidates inspected, all prose) | Clean |
| Dead branches / constant gates / unreachable code | 0 | Clean |
| Duplicated code | 4 groups (worst: ~45 lines) | **Only actionable noise** |
| Debug noise (println/Log.d/printStackTrace/System.out/commented logging) | 0 | Clean |
| Files > 600 lines | 14 main + several test | Flagged, design-level |
| Unused translation resources | 0 | Clean |
| Trailing WS / CRLF / BOM | 0 / 0 / 0 | Clean |

Top noise sources, ranked:
1. `TranslationPipeline.kt` size (5,415 lines) — 12% of the module in one file.
2. `getChapterPages` duplicated between `ChapterTranslator` and `TranslationPipeline`.
3. `isDiagnosticsEnabled` verbatim-triplicated across the three OCR engines.
4. Two trivial duplicated helpers (`safeAdd` x2, `identitiesMatch` x2).

## Stale markers

`TODO`, `FIXME`, `HACK`, `XXX`, `WIP`, and `@Deprecated` — **zero occurrences**
in the entire scope (main and test). Remaining keyword hits, all judged benign:

| Location | Marker | Judgment |
| --- | --- | --- |
| `recognition/RoiPageRecognitionEngine.kt:1228` | "validated workaround for vertical CJK (see docs/ocr-engine-notes.md)" | Legitimate documented constraint; introduced 2026-06-27 (`06d2629`), references external docs. Keep. |
| `segmentation/BubbleMaskRle.kt:38` | "one full-page temporary per persisted instance" | Prose describing memory allocation, not a TODO. |
| `runtime/onnx/DeviceCapability.kt:26` + its test | "enum is deprecated upstream" | Documents an upstream ONNX API fact. Keep. |
| `app/src/test/.../AiTranslationRetryControllerTest.kt:47,120`, `ProviderRequestGovernorTest.kt:117` | "temporary network failure" | Test fixture exception messages. Not noise. |

`legacy` appears in ~600 lines across ~20 files, but every inspected use is the
deliberate **legacy artifact-migration feature** (`LegacyArtifactMigration`,
`LegacySourceIdentity`, `ManifestAuthority.LEGACY`, legacy rescue paths) — domain
terminology with tests, not stale scaffolding.

## Commented-out code

Scanner: consecutive `//`-line runs (>=3) where >=60% of lines contain
code-like tokens (braces, `= `, keyword starts, trailing `)`). 9 candidates
found; **all 9 are explanatory prose** (step-by-step math, threshold rationale,
null-safety rationale). Examples verified by reading:

- `app/src/test/java/eu/kanade/translation/webtoon/WebtoonSlidingDetectorTest.kt:39-43` — window-arithmetic walkthrough ("Step = 1400 - 250 = 1150...").
- `app/src/test/java/eu/kanade/translation/inpainting/BubbleMaskBuilderTest.kt:217-219` — clamp math for a 5x5 mask.
- `app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6DetEngine.kt:65-67` — rationale for pooled direct buffer (ORT #16937).

**No disabled code found.** Documentation comments in this codebase are KDoc
(`/** */`) or prose `//`; none contain executable code.

## Dead branches & disabled logic

- `if (false)`, `if (true)`, `&& false` — zero hits.
- Always-constant feature gates (`val ...Enabled = true/false` style) — zero hits.
- Unreachable code after `return`/`throw` (same-indentation heuristic) — zero hits.
- `return@label` escape hatches found (e.g., `TranslationManager.kt:837,854`) are
  ordinary structured-concurrency returns inside `withContext`/`runBlocking`, not hacks.

## Duplicates

No same-named files/classes across packages; no near-duplicate files (size-twin
pairs all diverge substantially on diff; e.g., `PaddleOcrV6DetEngine` 322 lines vs
`PaddleOcrV6SmallEngine` 219 lines). Real duplication found by signature
extraction + body diff:

1. **`getChapterPages(chapterPath: UniFile)` — ~45 identical code lines.**
   `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt:684` vs
   `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt:5370`.
   Code is line-for-line identical; only the inline comments have drifted apart
   (ChapterTranslator's copy gained extra null-safety rationale comments).
   Worst offender; a shared util would eliminate ~45 duplicated lines.
2. **`private fun isDiagnosticsEnabled(): Boolean` — verbatim x3.**
   `ocr/PaddleOcrV6DetEngine.kt:311`, `ocr/PaddleOcrV6SmallEngine.kt:208`,
   `ocr/MangaOcrEngine.kt:481`. Only difference: `catch (_: Throwable)` vs
   `catch (e: Throwable)` (the `e` in MangaOcrEngine is unused — lint nit).
   Same 5-line lazy-cached preferences read; belongs in a shared helper.
3. **`private fun safeAdd(left: Long, right: Long)` — byte-identical x2.**
   `translator/ProviderRequestGovernor.kt:823` and `translator/TranslationRetry.kt:281`
   (both in `translator/`; trivially shareable).
4. **`private fun identitiesMatch(expected, actual)` — identical x2.**
   `artifact/ChapterArtifactDeletion.kt:103` and `artifact/ChapterArtifactStore.kt:1432`;
   a 2-line sha256+length comparison. Tiny but drift-prone for identity semantics.

Not noise: `forceReleaseNativeBuffers()` (x12) and `reclaimPooledMemory()` (x9)
are interface methods of the shared memory-pressure contract
(`util/TranslationSafetyPrimitives.kt`); bodies are class-specific pool clears,
not copy-paste.

## Debug noise

Zero in `app/src/main` translation sources: no `println`, no `Log.*`, no
`printStackTrace()`, no `System.out/err`, no commented-out logging. Production
logging uses the structured `logcat(LogPriority.*)` DSL in 40 files with explicit
priorities — legitimate observability, not noise.

## Oversized files (top 15, all >600 lines in scope)

| Lines | File | Content |
| --- | --- | --- |
| 5,415 | `main/.../TranslationPipeline.kt` | Full page pipeline orchestration (pages → OCR → translate → inpaint → render) incl. a duplicated `getChapterPages` |
| 2,552 | `main/.../ChapterTranslationStore.kt` | Durable chapter store + legacy flat-file rescue/migration |
| 2,083 | `main/.../TranslationManager.kt` | Chapter/queue lifecycle + legacy decode paths |
| 1,517 | `main/.../recognition/RoiPageRecognitionEngine.kt` | Per-page OCR orchestration incl. vertical CJK ordering |
| 1,504 | `main/.../artifact/ChapterArtifactStore.kt` | Artifact persistence authority |
| 1,430 | `main/.../inpainting/AOTInpainting.kt` | AOT inpainting engine (ONNX sessions, pools) |
| 1,391 | `test/.../scheduling/RollingAutoCoordinatorTest.kt` | Scheduler coordinator tests |
| 1,380 | `main/.../rendering/TextLayoutPlanner.kt` | Text layout/line-breaking planning |
| 1,197 | `test/.../artifact/ChapterArtifactStoreTest.kt` | Artifact store tests |
| 1,160 | `main/.../inpainting/SmartBubbleTextCleaner.kt` | Bubble cleaning pipeline |
| 1,014 | `main/.../scheduling/TranslationScheduler.kt` | Scheduling policy |
| 1,010 | `main/.../translator/AiTranslationRetryController.kt` | Retry/pacing controller |
| 1,003 | `main/.../scheduling/RollingAutoCoordinator.kt` | Rolling auto-translate coordination |
| 827 | `main/.../translator/ProviderRequestGovernor.kt` | Provider request governor |
| 807 | `main/.../ChapterTranslator.kt` | Chapter-level translation driver (also holds the duplicated `getChapterPages`) |

`TranslationPipeline.kt` is the standout: ~12% of the module in one file, and a
natural future split candidate — but that is architecture work, not noise removal.

## Unused resources

Bounded sweep of translation-sounding names in `app/src/main/res`:

- Drawables: `ic_translate`, `ic_translate_circle`, `ic_translate_circle_filled`,
  `ic_reader_webtoon_24dp` — all referenced (4/4/2/1 reference sites respectively
  in code/XML).
- Strings/arrays/plurals with `translat|ocr|inpaint|batch` in the name: **zero
  exist**, so zero can be unused.

**Zero-reference translation resources: none.**

## Whitespace / line endings

Trailing-whitespace lines: 0. CRLF files: 0. BOM files: 0. Nothing pervasive;
no itemization needed. (Formatter/enforcement is clearly in place.)

## T905 drift check (report of 2026-08-27)

Verified against the current checkout — **all checkout-level findings are resolved; the workspace has moved forward, not degraded**:

| T905 claim | Status now |
| --- | --- |
| Primary on `optimize_translation_finishing_page` @ `926ae00`, dirty | **Resolved / superseded**: checkout is on `t907/fix` @ `2085c03`; work has advanced through committed branches. |
| 5 tracked deviations (deleted `AGENT.md`, deleted `docs/project_context/{implementing,knowledge_base,planning}.md`, modified `AGENTS.md`) | **Resolved**: `git status` tracked tree is clean; all four files verified present on disk. |
| 45 untracked paths in primary workspace | **Resolved**: exactly 1 untracked path remains — `Plan/active/2026-08-28_T908_translation-folder-housekeeping/README.md`, the current task's own scaffold (expected). |
| 23 registered worktrees | **Essentially unchanged**: 22 worktrees now (one fewer). Worktree-level hygiene was out of this audit's scope; no regression signal. |

## Method

- Scope inventory via `find` + `wc` (296 files, 66,335 lines).
- Stale markers: case-insensitive `grep -E` for `\bTODO\b|\bFIXME\b|\bHACK\b|\bXXX\b|\bWIP\b|@Deprecated|workaround|temporary|legacy|DEPRECATED`; each hit read in context; oldest hit dated via `git log -S`.
- Commented-out code: awk run-detector over all 296 files (runs of >=3 `//` lines, >=60% code-like tokens), all 9 candidates manually inspected.
- Dead branches: `grep` for `if (false|true)`, `&& false`, constant enable-flags; same-indent `return`/`throw` follower heuristic in awk.
- Duplicates: basename `uniq -d`; size-twin pairing (+/-5%) with diff; `fun` signature extraction + `sort | uniq -c` + body `diff -w` for repeat signatures.
- Debug noise: `grep` for `Log.[dveiw](|println(|printStackTrace(|System.(out|err)` and commented-out `// logcat|Log.|println` in main only.
- Whitespace: `grep -E "[ \t]+$"`, `file` CRLF scan, BOM byte scan.
- Resources: name-pattern `find`/`grep` over `app/src/main/res` for translation-sounding names, then reference check across `app/src/main` + `app/src/test`.
- T905: claims re-verified with `git rev-parse`, `git status --porcelain=v2`, `ls`, and `git worktree list`.
- Scratch diff files used only under the system temp directory; the repository was not modified.
