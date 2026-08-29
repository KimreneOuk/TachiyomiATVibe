# T909 — Second-Tier Triage: 7 files of 800–1,517 lines (SPLIT vs LEAVE)

Date: 2026-08-29 · Owner: Technical Lead (second-tier triage) · READ-ONLY investigation

Method: declaration outlines via grep, targeted reads of seam regions, cross-reference of
callers/tests, churn proxy `git log --oneline --since=2026-08-01 -- <file> | wc -l`.
Line counts re-measured today (drift vs task README noted below).

## Summary

| File | Lines (actual) | Churn (since 08-01 / total) | Verdict | Constituent splits | Priority |
|---|---|---|---|---|---|
| `recognition/RoiPageRecognitionEngine.kt` | 1,517 | 8 / 33 | **SPLIT** | vertical CJK OCR (~340 L) + block dedup/parent-bubble geometry (~280 L) | **High (wave 1)** |
| `artifact/ChapterArtifactStore.kt` | 1,499 | 9 / 9 (new file) | **SPLIT** | legacy rescue/preservation/health machine (~430 L); optional retention sweep (~120 L) | **High (wave 1) — top pick** |
| `inpainting/AOTInpainting.kt` | 1,430 | 7 / 31 | **SPLIT (defer)** | session bootstrap (~190 L), neural candidate runner (~470 L), classical fill+blend (~410 L) | Medium (wave 2 — needs characterization harness) |
| `rendering/TextLayoutPlanner.kt` | 1,378 | 7 / 15 | **LEAVE** | — (pure stateless planner, one domain, 4 test files pin it) | Low |
| `scheduling/RollingAutoCoordinator.kt` | 1,003 | 3 / 3 (new file) | **LEAVE** | — (one focused scheduler machine, recently extracted, well-tested) | Low |
| `translator/ProviderRequestGovernor.kt` | 828 | 3 / 3 (new file) | **SPLIT (cheap)** | HTTP failure classification block (~190 L) used by 10 consumers | Medium-High (cheap ride-along, wave 1) |
| `ChapterTranslator.kt` | 759 | 11 / 33 | **LEAVE** (quick wins) | optional queue-persistence extraction (~120 L) only if it grows | Low-Medium |

**Totals: 4 SPLIT / 3 LEAVE. Top pick: ChapterArtifactStore legacy-block split** — highest
recent churn of the tier, a crisp ~430-line seam, dedicated migration tests already pin the
extracted machine, and the remainder (live-candidate lifecycle) becomes coherent.

**Churn read (VERIFIED):** ChapterArtifactStore, RollingAutoCoordinator, and
ProviderRequestGovernor are brand-new files (all lifetime commits are within the window);
they are products of recent extraction work. ChapterTranslator has the highest recent churn
(11 commits) but is under 800 lines and lifecycle-coherent. RoiPageRecognitionEngine and
AOTInpainting are long-lived files under active modification.

---

## 1. recognition/RoiPageRecognitionEngine.kt — 1,517 lines — SPLIT

**What it is (VERIFIED):** implements the per-page recognition engine interface
(`analyze` / `inpaint` / `close` / `reclaimPooledMemory` / `forceReleaseNativeBuffers`).
Holds ONNX detector/segmenter/OCR engines + inpainting delegates as lazy state (L42–148),
`initialize()` (L150–263), the monolithic `analyze()` OCR pipeline (L264–572, ~310 lines),
`inpaint()` delegation (L573–653), panel/bubble assignment (L654–790), lifecycle + dedup
helpers (L791–1052), and the multi-line/vertical OCR machinery (L1053–1517).

**Verdict: SPLIT.** The orchestration core is one machine, but two tail blocks are
independent machines in the trenchcoat:

| Constituent | Lines | Est. yield | New file |
|---|---|---|---|
| Vertical/multi-line OCR (recognizeMultiLine, recognizeSingleLine, recognizeDetColumns, recognizeHeuristicColumns, rotateCcw, recognizeVerticalColumnPerChar, detectVerticalGlyphRows, detectVerticalColumns, cropBitmap) | ~1,044–1,386 | ~340–360 | `recognition/VerticalLineOcr.kt` (internal object or top-level functions taking `RoiOcrEngine`) |
| Block dedup + parent-bubble geometry (suppressCrossLabelDuplicates, dedupeTextDetections, removePostOcrDuplicateBlocks, findParentBubble, parentContainmentScore, isTextBoxDuplicate, normalizeOcrText, trimParentBbox, selectParentBubble, RecognizedBlock/RecognizedAnalyzeResult L619–653) | ~920–1,043 + ~1,388–1,517 | ~280 | `recognition/OcrBlockDeduplication.kt` |
| Remainder (state, initialize, analyze pipeline, inpaint delegation, panel assignment, close/reclaim) | what's left | ~900 | stays |

Seam quality (VERIFIED): the vertical-OCR helpers are called only from within this file
(checked main/ and test/ trees); their inputs are `(RoiOcrEngine, Bitmap, flags)` — no
engine state access, pure extraction. `trimParentBbox`/`selectParentBubble` are called from
`analyze()` (L364, L470) with plain data — also stateless.

**Tests pinning it:** `app/src/test/java/eu/kanade/translation/recognition/BoxGeometryTest.kt`
— whose header explicitly documents that this geometry is *copy-pasted in BOTH
OnnxPageTextDetector and RoiPageRecognitionEngine* (VERIFIED). That makes the dedup split a
dedup opportunity too: a shared geometry home shrinks both files. Broader behavior is pinned
indirectly by the batch/phase characterization tests (Phase0BatchTranslationCharacterizationTest et al.).

**Priority: High (wave 1).** Churn 8 commits since 08-01 — this file is being actively
modified while carrying two extractable machines; every future OCR change lands in 1,517 lines.

**Quick wins:** none dead; the OnnxPageTextDetector geometry duplication is the quick win
(shared extraction, shrinks two files without behavior change).

## 2. artifact/ChapterArtifactStore.kt — 1,499 lines — SPLIT (top pick)

**What it is (VERIFIED):** the artifact persistence authority. Live-candidate lifecycle
(persist/promote/cancel/demote/delete/open, L240–916), interrupted-stage recovery + validity
guards (L800–916), retention/GC sweep (L917–1034), manifest parse/backup recovery
(L1013–1034), and a legacy flat-file rescue/preservation/health machine (L1035–1463).

**Verdict: SPLIT.** The legacy machine is a distinct authority that predates the artifact
graph and interacts with it only through `loadOrMigrate` and two reconciliation entry points:

| Constituent | Lines | Est. yield | New file |
|---|---|---|---|
| Legacy rescue/preservation/health (rescueLegacy, reconcileLegacyPreservation, verifyLegacyArtifactHealth, deletePreservedLegacyInput, cleanupOpenedChapterJunk, preserveLegacyInput, preservedResult, artifactGraphIsComplete, identityOf, preservationTargetName, attachGlossaryIfNeeded, futureBackupPresent, refuseFutureDocument) | ~1,035–1,463 | ~430 | `artifact/LegacyArtifactRescue.kt` (internal class over the same layout/paths deps) |
| Retention sweep (reconcileRetention, sweepDirectory, isRetained, retainedImageGenerations, reachablePaths) — optional second cut | ~917–1012 | ~120 | `artifact/ArtifactRetention.kt` |
| Remainder (candidate lifecycle, manifest publish, recovery/validity, loadOrMigrate) | what's left | ~950 (or ~830 with both cuts) | stays |

Seam quality (VERIFIED): external callers of the legacy machine are exactly
`loadOrMigrate` (L133, L151 → rescueLegacy) and `ChapterTranslationStore.kt` L2419/2435
(→ reconcileLegacyPreservation / verifyLegacyArtifactHealth). The block at L1464–1499
(publishManifestInternal, stampChapterKey, latestGlossarySidecarVersion, backupName) belongs
to manifest publishing and stays. Relocation is mechanical; visibility stays `internal`.

**Tests pinning it:** `artifact/ChapterArtifactStoreTest.kt`,
`artifact/LegacyArtifactMigrationTest.kt`, `ChapterTranslationStoreArtifactMigrationTest.kt`
— the legacy machine already has a dedicated migration test file, which travels with the
extraction (VERIFIED).

**Priority: High — wave 1 top pick.** Churn 9/9: the entire history of this file is within
the last month; it is the most actively-built large file in the tier, and it mixes a stable
end-state (artifact graph) with a shrinking legacy obligation. Splitting now freezes the
legacy machine in its own file where it can age and eventually be retired without touching
the live path.

**Quick wins:** no dead code spotted — the legacy machinery is live and load-bearing on
chapter open (VERIFIED at ChapterTranslationStore.kt L2435: health-gated deletion of
recovery sources). Relocate, do not delete. 139 "legacy" marker hits are substantive, not cruft.

## 3. inpainting/AOTInpainting.kt — 1,430 lines — SPLIT, defer to wave 2

**What it is (VERIFIED):** the AOT (amortized ONNX-time) inpainting engine: session
bootstrap for QNN/strict-NNAPI/dynamic providers (L104–291), `inpaintRegions` entry +
per-group strategies (L292–507), neural candidate pipeline with output-guard rejection and
provider fallback (L508–982), the classical `inpaint()` implementation with uniform-output
suspicion check and feather blending (L983–1384), lifecycle close (L1385–1430).

**Verdict: SPLIT (defer).** Three real machines, but this is the riskiest mechanical
extraction in the tier:

| Constituent | Lines | Est. yield | New file |
|---|---|---|---|
| Session bootstrap/providers (initializeQnnSession, initializeStrictNnapiSession, initializeSession, readContract, closeAndDetach*) | ~104–291 + ~878–901 | ~230 | `inpainting/AotSessionProvider.kt` |
| Neural candidate runner (inpaintReportFreeTextNeural, tryStrictFixedCandidate, prepareFixedInput, runPreparedFixedCandidate, tryNeuralCandidate, AotRejectedException) | ~508–877, ~902–974 | ~470 | `inpainting/AotNeuralCandidateRunner.kt` |
| Classical fill + blend (inpaint, isSuspiciousUniformOutput, featherBlend, setAlphaMaskPixels) | ~975–1384 | ~410 | `inpainting/AotClassicalFill.kt` |
| Entry + strategy dispatch + lifecycle | ~25–103, 292–507, 1385–1430 | ~330 | stays |

**Risk evidence (VERIFIED):** no test file imports `AOTInpainting` directly (grep over
app/src/test returns zero hits). Behavior is pinned end-to-end via the inpainting package
tests of already-extracted helpers (AotOutputGuardTest, AotModelContractTest, AotPixelOpsTest,
AotSessionLifecycleTest, StrictNnapiFallbackTest, AotReportBubbleFillTest, AotCorpusGateTest,
BoundaryAwarePipelineTest) plus a large real-image corpus
(`app/src/test/resources/corpus/aot*/...`). Guardrails exist, but a split should follow a
characterization pass, not precede it. This is also the memory-sensitive engine —
reader-stability constraint applies.

**Priority: Medium (wave 2).** Churn 7 since 08-01 is real but the seams here are
state-coupled (scratch pixel buffers, session fields, alpha-mask reuse), unlike the clean
data-in/data-out seams of files 1–2.

**Quick wins:** none — 0 legacy/dead markers (VERIFIED).

## 4. rendering/TextLayoutPlanner.kt — 1,378 lines — LEAVE

**What it is (VERIFIED):** a stateless `object` planning block layout: `plan()` entry
(L158–209), `placeBlock` placement machine (L224–496), displacement/free-space/growth
geometry (L497–915), mask-region/font-equalization helpers (L916–1092), CJK detection and
vertical-text utilities (L1093–1120, L1203–1377), plus public contracts (L19–121) and
`VerticalOrientation` (L1378).

**Verdict: LEAVE.** Size is inherent domain complexity, not accretion: it is pure
(data in → BlockLayout out, no engine state, no Android deps beyond Bitmap-free geometry),
single-domain, and heavily pinned. The CJK vertical block (~280 L) is the only candidate
seam (`rendering/CjkTextGeometry.kt`), but it shares `TextMeasurer`/tokenize with the
placement core, so the yield is low and the coupling real.

**Tests pinning it (VERIFIED):** TextLayoutPlannerTest.kt, TextLayoutPlannerStrokeTest.kt,
PageTextRendererDirectionTest.kt, RenderColorEstimatorLayoutSamplingTest.kt, plus
instrumented PageTextRendererInstrumentedTest. Renderer pixel-identity depends on this math.

**Priority: Low.** Churn 7 since 08-01, but every touch is layout math where regression =
visible mis-render (project constraint: normal manga must not regress). Do not spend
dismantling effort here.

**Quick wins:** the 12 "legacy" hits are comments documenting *ported* math ("mirrors the
legacy tuned math") — documentation value, not dead code. Nothing to delete.

## 5. scheduling/RollingAutoCoordinator.kt — 1,003 lines — LEAVE

**What it is (VERIFIED):** one focused machine: rolling auto-translate window scheduler —
window lifecycle (L144–304), coordination loop + consumption (L305–337, 338–467),
retry/pause bookkeeping (L468–528), reconcile pass with desired-set computation and
generation-based invalidation (L529–796), snapshot publishing (L797–959), small data
classes (L960–1003).

**Verdict: LEAVE.** Recently extracted (all 3 lifetime commits are within the window),
single responsibility, strict mutex/generation discipline that is easier to review in one
file than across several. Sub-regions (snapshot builder, admission marks) are cohesive
private helpers, not independent machines.

**Tests pinning it (VERIFIED):** scheduling/RollingAutoCoordinatorTest.kt +
TranslationManagerAutoArbitrationTest.kt.

**Priority: Low.** Churn 3 since 08-01 — quiet and settled. Revisit only if reconcilePass
(L529–670, ~140 L) keeps growing.

**Quick wins:** none — 0 legacy/dead markers (VERIFIED).

## 6. translator/ProviderRequestGovernor.kt — 828 lines — SPLIT (cheap)

**What it is (VERIFIED):** provider admission governor (buckets, waiters, permits,
cooldowns, L231–635) surrounded by ~210 lines of contract types (L20–230) and a tail
block of HTTP failure classification (L642–827).

**Verdict: SPLIT — one cut, trivially mechanical.**

| Constituent | Lines | Est. yield | New file |
|---|---|---|---|
| Failure classification (RetryAfterParser, parseRetryAfterMillis shim, classifyHttpFailure, classifyHttpFailureWithRetryAfterMillis, classifyProviderFailure, safeAdd) | ~642–827 | ~190 | `translator/ProviderFailureClassification.kt` |
| Remainder (types + governor + SharedProviderRequestGovernor) | what's left | ~640 | stays |

Seam quality (VERIFIED): the classification block is consumed by ~10 files that never touch
the governor — TranslationPipeline, RemotePageTranslationEngine, AiModelFetcher,
AiTranslationRetryController, DeepL/OpenAI-Compatible/Google/Gemini translators,
TranslationRetry. It is shared infrastructure squatting at the bottom of the governor file.
`parseRetryAfterMillis` (L662) is a 2-line shim used by GeminiTranslator — move it with the
block, keep the shim or update the single import.

**Tests pinning it (VERIFIED):** translator/ProviderRequestGovernorTest.kt — pins
RetryAfterParser.parseMillis directly (L69–72), so the moved block stays tested.

**Priority: Medium-High as a cheap ride-along.** Churn is only 3, but cost is ~1 hour of
pure moves with direct test coverage, and it visibly clarifies 10 consumers' import graph.
Bundle with a wave-1 phase; never worth its own phase.

**Quick wins:** nothing dead (the shim is used; keep it).

## 7. ChapterTranslator.kt — 759 lines (README said 807 — drift) — LEAVE

**What it is (VERIFIED):** chapter-level translation driver: durable queue persistence
(persistQueue/restoreQueue/DurableQueueState, L155–273), lifecycle (start/stop/pause/
memory-pressure/requeue, L274–476), queueing (L477–507), the chapter-driving core
translateChapterInternal (L512–683, ~170 L), page loading + queue list management
(L684–759).

**Verdict: LEAVE.** Under 800 lines, and its three regions are facets of one lifecycle
(what is queued, what is running, what drives a chapter to completion). It has the highest
recent churn in the tier (11 commits since 08-01), which argues against disturbing it in
the same window — churn here is feature work landing on a stable shape, not thrash from
mixed responsibilities. If it grows past ~1,000 lines, extract queue persistence
(L155–273, ~120 L → `ChapterQueuePersistence.kt`; pinned by
ChapterTranslatorQueueRestoreTest.kt) as the first cut.

**Tests pinning it (VERIFIED):** ChapterTranslatorQueueRestoreTest,
TranslationManagerAutoArbitrationTest, TranslationManagerArtifactReadTest,
TranslationManagerDownloadFailureRecoveryTest, TranslationManagerReaderTeardownTest,
batch/Phase0BatchTranslationCharacterizationTest.

**Priority: Low-Medium.**

**Quick wins (VERIFIED, trivial):** `translateChapter()` (L508–511) is a pure pass-through
to `translateChapterInternal()` — a vestigial wrapper. Inline/delete it. (README's 807-line
figure vs actual 759 suggests ~48 lines were already trimmed recently — consistent with
active shaping.)

---

## Dead / legacy quick-win inventory (no code moved)

1. **ChapterTranslator.kt L508–511** — `translateChapter` pass-through wrapper. Delete/inline. (VERIFIED)
2. **RoiPageRecognitionEngine ↔ OnnxPageTextDetector geometry duplication** — documented in
   BoxGeometryTest's header; the file-1 dedup extraction doubles as the shared home. (VERIFIED)
3. **ChapterArtifactStore legacy block** — not dead (load-bearing on chapter open), but it is
   a *retirable* obligation; the file-2 split isolates it for eventual deletion. (VERIFIED)
4. Explicitly checked and NOT dead: `panelDetector` in the engine (used in analyze + all three
   release paths), `parseRetryAfterMillis` shim (GeminiTranslator), TextLayoutPlanner "legacy"
   comments (documentation of ported math). (VERIFIED)

## Recommended wave placement (input to dismantling-plan.md)

- **Wave 1:** ChapterArtifactStore legacy split (top pick) → RoiPageRecognitionEngine
  vertical-OCR + dedup split → ProviderRequestGovernor classification ride-along.
  All three have direct tests at the seam or pure data-in/data-out extraction.
- **Wave 2:** AOTInpainting — only after a characterization pass over the aot corpus;
  sequence session-provider cut first (least state-coupled), classical fill second,
  neural runner last.
- **Not in plan:** TextLayoutPlanner, RollingAutoCoordinator, ChapterTranslator (re-triage
  ChapterTranslator only if it exceeds ~1,000 lines).

## Evidence quality notes

- Line ranges are grep-outline-derived and accurate to ±5 lines (block boundaries verified
  by targeted reads at L150–620 (engine), L640–827 (governor), L2410–2440 (store caller)).
- Churn figures are VERIFIED via git log on the exact paths.
- "No test imports AOTInpainting" is VERIFIED by grep over app/src/test and app/src/androidTest.
- ASSUMPTION: estimated yields assume no signature changes; pure moves only, per task constraints.
- UNKNOWN: whether wave ordering must respect the T909 planner's risk budget (deferred to dismantling-plan.md).
