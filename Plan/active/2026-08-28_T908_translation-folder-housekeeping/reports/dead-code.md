# Dead-code audit: `app/src/main/java/eu/kanade/translation/`

Date: 2026-08-28 · Scope: 167 Kotlin files (task brief said 169; `find` counts 167:
15 root files + artifact 10, batch 10, data 2, detection 4, inpainting 21, model 17,
ocr 12, presentation 0, recognition 5, remote 4, rendering 3, runtime 6 (incl.
`runtime/onnx`), scheduling 9, segmentation 4, translator 36, util 7, webtoon 2).

## Summary

- **415 top-level declarations** extracted (209 class, 77 object, 76 fun, 27 interface,
  25 val, 1 typealias); **each name word-boundary-grepped across all of
  `app/src/main/java` + `app/src/test/java`** (887 files, 142k lines indexed).
- **63 declarations (61 distinct symbols) have zero references outside their declaring
  file.** Breakdown: 37 private (file-scoped by design — not dead-code candidates),
  21 public, 5 internal.
- **Verified dead: 2 symbols** (both `object`s). One more zero-ref candidate
  (`toChunkCompletionOutcome`) was initially mis-extracted and proved **alive** — see caveats.
- **High-confidence dead: 17 public funs** on the biggest classes are never called
  anywhere in the repo (declaration line is the only code occurrence; verified
  per-name with repo-wide greps).
- **Zero fully-dead files.** A transitive liveness fixpoint (seeded from all
  production code outside the package) found every one of the 167 files reachable
  from production code.
- **7 files are TEST-ONLY**: they ship in the APK but their only referrers are unit
  tests (4 of them form one cluster in `remote/`).
- **Headline:** the dead weight is not whole files but (a) 2 orphaned singleton
  objects, (b) ~17 uncalled public API methods left behind by pipeline refactors
  (concentrated in `TranslationManager.kt` and `ChapterTranslationStore.kt`), and
  (c) a test-rooted remote-inference cluster.

Counts per package (zero-external-ref top-level declarations; rows, incl. 2 dup rows
for `safeAdd`/`resolveStableId`):

| Package | zero-ext decls | of which private | notes |
|---|---|---|---|
| root files | 8 | 3 | incl. 1 VERIFIED DEAD (`SharedProviderRequestAdmission`) |
| artifact | 5 | 2 | 3 public types need review |
| batch | 2 | 0 | 1 alive (mis-extracted ext fun), 1 public (`AbortDecision`) |
| model | 9 | 7 | incl. 1 VERIFIED DEAD (`RenderQuality`) |
| runtime/onnx | 3 | 0 | 3 public path-holder classes need review |
| translator | 34 rows / 32 names | 24 | 11 public/internal types need review |
| scheduling, detection, ocr, recognition, remote, rendering, segmentation, util, webtoon, data | 0 | 0 | — (remote/webtoon contribute test-only files, see below) |

## Verified dead

Every symbol below: zero word-boundary hits in any `.kt`/`.java` in `app/src/main`,
`app/src/test`, `app/src/{debug,dev,standard,androidTest}` (including its own file
beyond the declaration); not in `app/src/main/AndroidManifest.xml`; no reflection hits
(only reflection in the tree is `Class.forName("android.os.SystemProperties")` in
`runtime/onnx/DeviceCapability.kt`); no string-literal, Gradle or ProGuard references
(`app/proguard-rules.pro` has no `translation` rules); no JNI/native bindings.

| Symbol | Declaring file | Cluster | Evidence |
|---|---|---|---|
| `RenderQuality` (object, 3 string consts) | `model/PageTranslation.kt:277` | none | 1 occurrence in file (= declaration). Only repo mentions are documentation (`docs/DATA_FLOW.md:258`, `docs/TRANSLATION_MODULE.md:201` describe pages "marked `RenderQuality.SIZE_LIMITED`" — behavior that does not exist in code; the literal `"SIZE_LIMITED"` appears nowhere else). |
| `SharedProviderRequestAdmission` (object) | `TranslationStageContracts.kt:195` | none | 1 occurrence in file (= declaration). Repo hits only under `Plan/` engineering notes. KDoc: transitional no-op shim for legacy callers; after the T904 shared-pacing redesign zero callers remain; `withRequest`/`governor` never invoked. |

## Transitive dead clusters

**None.** Fixpoint over the file-level reference graph (a file is alive if referenced
by production code outside the package, or by a live in-package file): 167/167 files
reachable from production code. The only transitive structure found is the
test-rooted cluster in Test-only usage below (`BackendPageKey.kt → InferenceBackend.kt`
and `RemotePageTranslationEngine.kt → RemotePageTranslationException.kt` chains that
never reach production entry points).

## Suspicious / needs review

### A. Public/internal funs never called anywhere (17) — remove after review

Extracted from the 10 largest files; each name verified with a repo-wide grep
(all source sets): the only non-`Plan/`, non-`docs/` hit is the declaration line
itself; no `override fun <name>` exists anywhere (so none is an interface
implementation); no string/reflection references.

| Fun (public unless noted) | Location |
|---|---|
| `isPageActive` | TranslationManager.kt:475 |
| `getActivePageKeys` | TranslationManager.kt:470 |
| `openChapterTranslationStore` | TranslationManager.kt:1016 |
| `openActiveChapterTranslationStore` | TranslationManager.kt:1270 |
| `observeActiveStore` | TranslationManager.kt:1340 |
| `observeChapterTranslationStatus` | TranslationManager.kt:682 |
| `getBatchTracker` | TranslationManager.kt:1370 |
| `getCompanionImageDirForChapter` | TranslationManager.kt:1993 |
| `deletePageTranslation` | TranslationManager.kt:1615 |
| `translatorStart` (`= translator.start()`) | TranslationManager.kt:492 |
| `patchBlock` | ChapterTranslationStore.kt:651 |
| `mergeTranslation` | ChapterTranslationStore.kt:816 |
| `mergeInpaint` | ChapterTranslationStore.kt:821 |
| `ensureArtifactAuthorityForMutation` | ChapterTranslationStore.kt:331 |
| `getContextualTranslator` | TranslationPipeline.kt:4333 |
| `tryRenderStandalone` | TranslationPipeline.kt:4263 |
| `antiAliasInset` (stub returning 0f) | rendering/TextLayoutPlanner.kt:29 |

Note: several appear by name in `Plan/2026-08-25_T902_storage-io-batch-behavior-legacy-cleanup`
notes ("remains", "pre-existing") — consistent with consciously-retained but currently
uncalled API. Confirm with Product/Technical lead before deletion.

### B. Public/internal top-level types never named outside their own file (23)

Zero external references; used only within the declaring file (in-file occurrence
count in parentheses, declaration included). These are *used* — not verifiably dead —
but their public visibility is unjustified or they are vestigial data models:

| Symbol | File | in-file |
|---|---|---|
| `MutationAdmission` (interface) | ChapterTranslationStore.kt:68 | 34 |
| `TranslationWorkKind` (enum) | TranslationSession.kt:17 | 2 (property type of `TranslationSession.kind`; values never matched outside file) |
| `TranslationBlockPatch` | TranslationStageContracts.kt | 2 |
| `AbortDecision` | batch/BatchOomPolicy.kt:3 | 4 |
| `ChapterArtifactDeletionResult` | artifact/ChapterArtifactDeletion.kt | 4 |
| `ChapterLegacyDeletionCandidate` | artifact/ChapterArtifactDeletion.kt | 6 |
| `ModelIdentity` | artifact/ModelIdentityCache.kt:21 | 5 |
| `ContextualStructuralFailure` | translator/ContextualTranslationBatch.kt | 3 |
| `ContextualStructuralFailureCode` | translator/ContextualTranslationBatch.kt | 19 |
| `ContextualTranslationAccounting` | translator/ContextualTranslationBatch.kt | 3 |
| `GeminiEmptyResponseException` (public exception, never thrown/caught outside file) | translator/GeminiTranslator.kt | 3 |
| `GeminiPayload` (internal) | translator/GeminiTranslator.kt:214 | 3 |
| `OpenAiApiException` (internal) | translator/OpenAiCompatibleTranslator.kt | 2 |
| `ProviderRequestPermit` | translator/ProviderRequestGovernor.kt | 5 |
| `ProviderRequestPriorityContext` | translator/ProviderRequestGovernor.kt | 4 |
| `RequestRetryAttemptContext` (internal) | translator/TranslationRetry.kt | 6 |
| `RequestRetryBudgetContext` (internal) | translator/TranslationRetry.kt | 4 |
| `TranslationValidationResult` (interface) | translator/TranslationBlockValidation.kt | 9 |
| `LayoutFailureException` (public exception) | TranslationPipeline.kt | 2 |
| `ModelPaths` | runtime/onnx/OnnxModelStore.kt | 3 |
| `PaddleOcrV6DetPaths` | runtime/onnx/OnnxModelStore.kt | 3 |
| `PaddleOcrV6SmallPaths` | runtime/onnx/OnnxModelStore.kt | 3 |
| `overlayContentFingerprint` (public val) | model/PageView.kt | 2 |

Mini-clusters worth reviewing together: the Contextual trio, the artifact-deletion
pair, the ProviderRequest pair, the Retry-context pair, the onnx paths trio.

### C. Public/internal funs never called from outside their file but used within it (39)

Best-effort visibility-narrowing candidates (public visibility with file-only
callers). Per file: TranslationPipeline.kt 29 (e.g. `runTranslate`, `tryRender`,
`resumeGate`, `translateChunkAi`, `persistBatchPageWithOomRecovery`),
TranslationManager.kt 6, ChapterTranslationStore.kt 2, inpainting/SmartBubbleTextCleaner.kt 3,
inpainting/AOTInpainting.kt 1 (`clearScratch`), artifact/ChapterArtifactStore.kt 1
(`isCandidateOwned`), rendering/TextLayoutPlanner.kt 0 beyond the dead one above.
Full list retained in audit working data; ask if needed.

Also noted: `forceReleaseNativeBuffers` / `reclaimPooledMemory` (declared on
`inpainting/AOTInpainting.kt` engine API, overridden in ocr/recognition engines)
DO have call sites (`ChapterTranslator.kt:300,321`, `TranslationPipeline.kt:2723,3811,4226`)
— alive, excluded.

### D. Private top-level declarations (35 distinct symbols, 37 rows)

Zero external refs *by design* (Kotlin file-private); all show in-file usage
(≥2 occurrences), so none is verifiably dead by this method. Concentrated in
`translator/AiTranslationRetryController.kt` (helpers `analyzeResponse`,
`classifyFailure`, `freezeEnvelope`, types `FrozenAiEnvelope`, `RequestKind`, ...),
`model/PageTranslationState.kt`, `model/PageDisplayProjection.kt`,
`scheduling/AutoWindowState.kt`. Excluded from findings.

## Test-only usage

These 7 files live in the **main source set** (shipped in the APK) but their only
referrers are unit tests in `app/src/test/java/eu/kanade/translation/`. Not counted
as dead (tests use them), but they are dead weight in release builds unless removal
of the feature is intended:

| File | Referrers (all test-rooted) |
|---|---|
| `remote/RemotePageTranslationEngine.kt` | `remote/RemotePageTranslationEngineTest.kt` only |
| `remote/RemotePageTranslationException.kt` | Engine (main, itself test-only) + test |
| `remote/InferenceBackend.kt` | `remote/BackendPageKey.kt` (main, itself test-only) + test |
| `remote/BackendPageKey.kt` | test only |
| `webtoon/WebtoonSeamStitcher.kt` | `webtoon/WebtoonSeamStitcherTest.kt` only |
| `translator/NumberedLineResponseParser.kt` | `translator/NumberedLineResponseParserTest.kt` only (note: `companion_server/translate/translator.py` mentions it only in comments as the porting origin) |
| `artifact/ModelIdentityCache.kt` | `artifact/ModelIdentityCacheTest.kt` only |

The four `remote/` files form one transitive test-only cluster (see above). Verified
absent from `app/src/{debug,dev,standard,androidTest}`, manifest, reflection, strings.

## Method + caveats

1. **Index**: all lines of `app/src/main/java` + `app/src/test/java` (887 files) dumped
   to a `path<TAB>content` index; tokenized into a word→file inverted index (word-boundary
   semantics equal to `grep -rw`).
2. **Declarations**: awk tokenizer over column-0 (top-level) and indented (member) lines,
   stripping modifiers/annotations/generics; extension-function receivers resolved to the
   final callable name.
3. **Reference counting**: per name, referencing files excluding co-declaring files;
   per-file reference graph; liveness fixpoint for transitive deadness.
4. **False-negative screening for every reported candidate**: repo-wide `grep -rw`
   across the whole workspace (excluding `.git`/`build`/`.gradle`) — covers
   AndroidManifest.xml, string literals, reflection, Gradle files, ProGuard rules,
   resources, and the auxiliary source sets `app/src/{debug,dev,standard,androidTest}`;
   plus an explicit `override fun <name>` sweep for member findings.
5. **Known caveats**:
   - Textual method, no Kotlin type inference/flow analysis. A type consumed only as an
     inferred type or only via a property declared in the same file can appear
     externally-unused while being live (e.g. `TranslationWorkKind` is the declared type
     of `TranslationSession.kind`). Hence "needs review", not dead.
   - Member analysis is name-based: a same-named method elsewhere counts as a reference,
     which can only *under*-count dead code (conservative).
   - One declaration (`ProviderFailureException.toChunkCompletionOutcome`,
     BatchCoordinatorInterfaces.kt:245) was initially flagged zero-ref because its
     fully-qualified receiver mangled the extracted name; repo-wide grep showed real call
     sites (`SequentialBatchCoordinator.kt:182`, `BatchCoordinatorInterfaces.kt:96`) — it is
     **alive** and excluded. A sweep confirmed it was the only multi-dot receiver in the package.
   - KDoc `[links]` can inflate in-file counts; all VERIFIED-DEAD and never-called findings
     were additionally confirmed by reading or targeted grep (declaration-only occurrence).
   - `@Composable`, framework-interface overrides, and DI-wired construction are excluded/
     captured by construction; no translation classes are loaded reflectively by name.
   - Top-level `private` declarations are file-scoped in Kotlin and cannot be referenced
     externally; they were excluded from dead-code candidacy (in-file deadness would need
     per-statement analysis, out of scope).
