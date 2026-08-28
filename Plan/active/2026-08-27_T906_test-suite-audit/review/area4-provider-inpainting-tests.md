# T906 Area 4 — Provider & Inpainting Tests Audit

Auditor: T906 Provider Inpainting Test Auditor (reviewer role).
Target truth: commit `56179d7` on `t904/integration` (read-only worktree `C:\Users\User\.traycer\worktrees\kimreneouk__tachiyomiatvibe\t904-integration`, HEAD verified at `56179d7`, clean).
Director inpainting policy (recorded in T906 README): inpainting/AOT tests need only verify (a) the inpaint stage runs and (b) a cleaned image is produced; geometric/pixel-perfect bubble-fill assertions are not acceptance criteria.

## H1 — AotReportBubbleFillTest: deterministic failure at line 49 — WHO is wrong

**Verdict: the TEST is wrong (STALE). Production is internally consistent with its own documented design. One related production wart (dead variable + misleading comment) is noted separately as LOW.**
Classification: **STALE** (asserts a superseded contract). Likelihood of failure: 100% deterministic. Severity: MEDIUM (red baseline test; misleads triage).

### Evidence trail (all VERIFIED via git history + static trace)

1. **Test as it stands** (`app/src/test/java/eu/kanade/translation/inpainting/AotReportBubbleFillTest.kt`, worktree snapshot = `git show 56179d7:...`):
   - Test 2 `` `reportBubbleFill preserves diagonal components without an inset interior` `` (lines 31-53) places two diagonally adjacent masked pixels at (1,1) [argb(1,2,3)] and (2,2) [argb(4,5,6)] in a 4x4 ring, and asserts both are **unchanged** after the fill (lines 49-50: `pixels[first] shouldBe argb(1, 2, 3)`, `pixels[second] shouldBe argb(4, 5, 6)`).
2. **Production fill decision today** (`app/src/main/java/eu/kanade/translation/inpainting/AotReportBubbleFill.kt` at `56179d7`):
   - Component discovery is **8-connected** (lines 52-63: `dy in -1..1`, `dx in -1..1`), so the two diagonal pixels form **one component**.
   - Both pixels touch background → boundary, distance 0 (lines 75-98). Histogram bucket requires `distances[idx] >= 2` (line 129) → `count == 0`.
   - `count == 0` → **fallback: average of the component** (lines 163-176, comment: "Fallback to average of component if it's too small to erode"). Integer average of (1,2,3) and (4,5,6) = (2,3,4).
   - Dynamic inset: `maxDist = 0` → `effectiveInset = 0` (lines 178-183: `maxDist >= 16 -> 4; maxDist >= 8 -> 2; else -> 0`), so `distances[idx] >= 0` is true for every pixel → **both pixels are overwritten with (2,3,4)** (lines 185-191).
   - Therefore assertion at test line 49 receives argb(2,3,4) vs expected argb(1,2,3) → deterministic FAIL. Matches the seed evidence ("fails deterministically on the untouched base 926ae00").
3. **History — the contract changed under the test:**
   - `git show 5caf856:...AotReportBubbleFill.kt` (old contract): fill decision was `if (distances[idx] >= insetPx)` with fixed `insetPx = 5` — components thinner than 5px were **never filled**. The tests' "unchanged" expectations were correct under this contract.
   - Commit `b4eaa14` ("feat(translation): webtoon long-strip seam stitching and sliding detection"; verified ancestor of both `926ae00` and `56179d7`) introduced `effectiveInset` and replaced the fill condition `distances[idx] >= insetPx` with `distances[idx] >= effectiveInset` (verified via `git diff 6694ab6 b4eaa14 -- <file>`). Since then, thin components (maxDist < 8) get inset 0 and **are filled** via the average fallback. The three "preserves ... without an inset interior" tests (lines 9-79) were **never updated** for this change — `git diff 926ae00 56179d7 -- <test file>` is empty, and the only later test edit (`6694ab6`) merely appended the `fillAndBlend` test (lines 81-110).
   - The tests pass today only by coincidence where a component's average equals its own single color: test 1 (single pixel, line 24) and test 3 (two disconnected single pixels, lines 77-78) are overwritten with their own color, so the assertions hold even though the "no eligible interior" rationale in the comment (lines 22-23) is false. Only the diagonal test (line 49) exposes the behavior change because a 2-pixel average differs from either input.
4. **Why production is NOT wrong:** the average fallback (lines 163-176) exists precisely to fill components "too small to erode"; the dynamic inset (lines 178-183) deliberately fills thin components instead of leaving untouched rings. Test 2's expectation (leave masked stroke pixels untouched) directly contradicts the documented fallback design. Forcing production back to a fixed 5px inset would regress the deliberate `b4eaa14` behavior (unfilled residue in small/thin components, e.g. long-strip seam strokes).

### Recommendation (under the Director inpainting policy)

Tests 1-3 (`AotReportBubbleFillTest.kt:9-79`) assert pixel-geometric invariants that are **not acceptance criteria** under the new policy. The `fillAndBlend` equivalence test (lines 81-110) is a genuine contract check and is **VALID** (it compares the helper against the same legacy sequence it documents, and passes under the current implementation).

- **Recommended action: rewrite tests 1-3 (or delete 1 and 3).** Keep one smoke-style test per the policy: invoke `reportBubbleFill`/`fillAndBlend` on a plausible mask and assert only (a) it runs without throwing and (b) masked pixels changed toward a flat fill (cleaned output) — e.g. assert the component interior becomes a single constant color, not which color. Drop all "pixel must remain exactly X" assertions.
- Do **not** change production geometry to satisfy the test.
- Alternative if zero geometric checks are wanted: delete tests 1-3 outright; the `fillAndBlend` equivalence test plus upstream AOT stage tests suffice.

### Related LOW production finding

- `AotReportBubbleFill.kt:38` — `val insetPx = 5 // Do not overwrite pixels within 5px of the mask boundary` is **dead code** (unused since `b4eaa14`; the name survives only as the `smoothMaskedComponent` parameter). The stale comment actively misleads test authors (it is the source of the "five-pixel boundary inset" comment at `AotReportBubbleFillTest.kt:22-23`). Recommend deleting the variable and comment (Director approval required; AUDIT ONLY).

## H2 — Gemini adapter tests (GeminiRequestPayloadTest.kt)

All three tests verified **VALID** against production at `56179d7` (`app/src/main/java/eu/kanade/translation/translator/GeminiTranslator.kt` via `git show`):

1. `` `Gemini 25 Flash disables thinking by default` `` (test lines 12-26) — matches `thinkingConfig()` branch `normalized.startsWith("gemini-2.5-flash") && mode == DISABLED -> {"thinkingBudget": 0}` (GeminiTranslator.kt:263-264); `hasThinkingConfig = thinkingConfig != null` (line 249). JSON assertion `thinkingBudget` primitive content "0" matches `JsonPrimitive(0)`.
2. `` `unsupported thinking mode omits the field...` `` (test lines 28-42) — gemini-2.5-pro + DISABLED falls to `else -> null` (line 267); `payload(includeThinking)` only adds the key when non-null (line 241). Correct.
3. `` `Gemini response ignores thought parts...` `` (test lines 44-49) — matches `extractGeminiText()` skipping parts with `thought == true` (lines 330-335).

No WRONG/STALE/FLAKY/REDUNDANT findings. Two coverage gaps (not defects):
- **LOW / coverage gap** — the thinking-fallback path (`GeminiTranslator.kt:128-136`: `GeminiApiException` with statusCode 400 + `hasThinkingConfig` → re-post `payloadWithoutThinking`) has **no test**. This is exactly the "thinking-fallback" behavior named in the assignment; it is currently only guarded by production code. Recommend a JVM test on a fake transport (needs seam; `post()` is private and hits OkHttp directly — extraction required before it is testable).
- **LOW / coverage gap** — gemini-3 `thinkingLevel:"low"` branch (lines 261-262) untested.

## H3 — AiModelFetcher tests (AiModelFetcherTest.kt, AiModelFetcherParseTest.kt)

Verified against production `app/src/main/java/eu/kanade/translation/translator/AiModelFetcher.kt` at `56179d7`:

- `normalizeBaseUrl` (prod lines 173-174: `trim().trimEnd('/')`): both tests in AiModelFetcherTest.kt:8-18 **VALID**.
- `parseOpenAiModels` (prod lines 176-181): all 4 tests in AiModelFetcherParseTest.kt:19-45 **VALID**, including the JsonNull-id regression guard (test lines 38-45 vs prod `stringOrNull`/`contentOrNull` lines 209-221 which return null for JsonNull — matches documented org.json `optString` semantics).
- `parseGeminiModels` (prod lines 189-201): all 4 tests in AiModelFetcherParseTest.kt:47-81 **VALID** (prefix strip, generateContent filter, missing-methods skip via `?: continue` line 194, missing array → emptyList).
- **REDUNDANT (LOW)** — `AiModelFetcherParseTest.kt:83-86` `normalizeBaseUrl trims whitespace and trailing slashes` duplicates `AiModelFetcherTest.kt:8-12` (same assertion, different host string; no added coverage). Recommend deleting the duplicate in ParseTest.

No pre-governor/pre-typed-failure assertions found in either file: the tests exercise pure functions only and do not encode the fetch outcome contract.

- **LOW / coverage gap** — the typed-failure mapping in `fetch()` (AiModelFetcher.kt:66-83 `InvalidKey` / `ProviderFailureException` → `e.failure.safeSummary` / generic `Error("Model list request failed")`) and `validateAuth` (lines 234-248) is untested. This is where a pre-typed-failure regression would land (e.g. someone asserting exception messages). Recommend boundary tests via a stubbed `requestGovernor` (field is `internal var`, line 33 — already a seam).

## H4 — Coverage gap: OpenAI-compatible family, DeepL, Google, MLKit have no direct tests

**Observation (MEDIUM, coverage gap — not a WRONG test).** No test file references `OpenAiCompatibleTranslator`, `DeepSeekTranslator`, `OpenRouterTranslator`, `LmStudioTranslator`, `DeepLTranslator`, `GoogleTranslator`, or `MLKitTranslator` (grep over `app/src/test` finds only a comment in OcrArtifactSanitizerTest.kt:10 noting the sanitizer was "previously inlined in DeepSeekTranslator"). The entire OpenAI-compatible request/response/error surface — including the current typed `classifyHttpFailure` handling — has no JVM regression guard. Any T904-typed-failure or governor-priority regression in these adapters would ship silently. Recommend at minimum a payload/error-classification test per adapter family mirroring the Gemini payload tests. (Audit-only; no test was written here.)

## H5 — Translator-support tests: TranslatorComputeClassTest, Checkpoint2IntegrationTest, StrictConfigFromPrefTest

Verified against `56179d7` production:

- `TranslatorComputeClassTest.kt` — all 3 tests **VALID**. Matches `TranslatorComputeClass.forConfiguration` (TranslatorComputeClass.kt:55-63: MLKit → LOCAL_COMPUTE, other standard → REMOTE_IO, AI_MODEL → REMOTE_IO) and `mayOverlapNative` (line 27). The exhaustive `AiTranslatorKind.entries` loop (test lines 34-43) is future-proof against new engine kinds.
- `Checkpoint2IntegrationTest.kt:13-40` (compute-class routing) — **VALID**, same production basis. Note **partial redundancy** (LOW): test lines 19-40 duplicate TranslatorComputeClassTest.kt:14-32 coverage for MLKit/DEEPL/gemini; it adds only the "ai engine by string name" angle, acceptable to keep as a smoke check.
- `Checkpoint2IntegrationTest.kt:44-64` (streaming planner tail flush within budget) — **VALID**. Deterministic JVM math against `TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS = 8_192` / `SAFETY_MARGIN = 512` (TranslationContextChunkPlanner.kt:20-21) and the invariant documented at line 10 of the same file. Not FLAKY: `runTest` with a pure planner, no real time.
- `StrictConfigFromPrefTest.kt` — all 5 tests **VALID** and deliberately pin the strict no-fallback contract: `TextRecognizerLanguage.fromPref` throws `IllegalArgumentException` with "Unknown OCR source language" (TextRecognizerLanguage.kt, message verified) and `TextTranslatorLanguage.fromPref` throws "Unknown translator target language" (TextTranslatorLanguage.kt:207-212). The KDoc (test lines 10-26) correctly explains why the `StandardTranslatorKind.fromPref` throw branch is untestable. No stale pre-strict assertions.

No pre-governor / pre-typed-failure assertions in any of these files.

## H6 — Summary of provider/adapter area so far

| Finding | File:Line | Class | Severity | Action |
| --- | --- | --- | --- | --- |
| Diagonal-component assertion contradicts current dynamic-inset fill | AotReportBubbleFillTest.kt:49-50 | STALE | MEDIUM | rewrite to run+cleaned-output (see H1) |
| Tests 1 & 3 pass only coincidentally; comments assert dead 5px-inset contract | AotReportBubbleFillTest.kt:9-28, 55-79 | STALE (latent) | LOW | rewrite or delete under inpainting policy |
| Dead `insetPx = 5` + misleading comment in production | AotReportBubbleFill.kt:38 | (production wart) | LOW | delete (Director approval) |
| Duplicate `normalizeBaseUrl` test | AiModelFetcherParseTest.kt:83-86 | REDUNDANT | LOW | delete |
| Gemini 400→no-thinking fallback untested | GeminiTranslator.kt:128-136 | coverage gap | LOW | add test after transport seam |
| gemini-3 thinkingLevel branch untested | GeminiTranslator.kt:261-262 | coverage gap | LOW | extend payload test |
| `fetch()` typed-outcome mapping / `validateAuth` untested | AiModelFetcher.kt:66-83, 234-248 | coverage gap | LOW | stub `requestGovernor` (internal var seam) |
| OpenAI-compatible/DeepSeek/OpenRouter/LM Studio/DeepL/Google/MLKit adapters: zero direct tests | (whole family) | coverage gap | MEDIUM | add payload/error-classification tests |

VALID counts so far: GeminiRequestPayloadTest 3/3, AiModelFetcherTest 2/2, AiModelFetcherParseTest 8/9 (1 REDUNDANT), TranslatorComputeClassTest 3/3, Checkpoint2IntegrationTest 3/3 (1 partially redundant), StrictConfigFromPrefTest 5/5.

## H7 — Inpainting/AOT suite inventory (excluding AotReportBubbleFillTest, see H1)

All files audited at `56179d7` under `app/src/test/java/eu/kanade/translation/{inpainting,segmentation,model}`. Production counterparts read via `git show 56179d7:<path>`; exact-value assertions re-derived by hand where they pin thresholds.

| Test file | Verdict | Notes |
| --- | --- | --- |
| AotPixelOpsTest.kt | **VALID** (16 tests) | Pure math; every expectation re-derived against AotPixelOps.kt:8-43 (blend rounding, `maskValue = max(blue, alpha)`, histogramMedian `acc > half` semantics incl. empty→255, avg4 +2 rounding, fixed-512 encode/decode + full 0..255 roundtrip). Keep. |
| AotBoxGeometryTest.kt | **VALID** (14 tests) | All exact geometry expectations re-derived against AotBoxGeometry.kt (localizeBox clamp, paddedUnionBounds, centeredReportCrop center/clamp/sub-512/short-side, findParentBubble smallest-area, overlapsAnyBubble 0.12f boundary). Deterministic; not stage-acceptance tests, unaffected by Director policy. |
| AotPadPathTest.kt | **VALID** (6 tests) | Matches AotPadPath.kt (SIZE=512, centered offset, capacity guards, pooled-buffer "extra capacity untouched" contract, require() rejection bounds). |
| AotOutputGuardTest.kt | **VALID** (9 tests) | Pins real neural-artifact rejections (mid-gray, near-white, near-black ≤24) and non-rejection of textured/dark-variance output; expectations re-derived against AotOutputGuard.kt:83-112 thresholds (variance < 9.0, channelDelta < 8.0, unmasked-white/black carve-outs). These are the "cleaned output is sane" checks the policy wants; keep. |
| AotModelContractTest.kt | **VALID** (4 tests) | Matches AotModelContract.kt (input-name sets incl. Qualcomm `input_image/input_mask`, 1 output, NCHW, fixed-512 vs dynamic spatial rules). |
| AotSessionLifecycleTest.kt | **VALID** (6 tests) | Close order qnn→nnapi→fixed→dynamic, independent failure containment, alias-safe single close (IdentityHashMap). Matches AotSessionLifecycle.kt:21-24. |
| AotFallbackCoordinatorTest.kt | **VALID** (7 tests) | Cascade control flow incl. OOM-skip (`Skipped("primary_oom")`), Exhausted pairing, crop-back guard inspection. Matches AotFallbackCoordinator.kt:35-79. |
| AotCorpusGateTest.kt | **VALID** (5 tests) | Tier-3 gate runs the REAL `AotOutputGuard` over a resource corpus; corpus verified present (42 pages + 4 sub-512 fixtures on disk, matching `EXPECTED_CORPUS_SIZE=42` and sides [300,400,480,511]). Defensive size assertions prevent silent pass-on-empty. This is the closest thing to the Director's "runs + cleaned image" acceptance bar that JVM can host. |
| BoundaryAwarePipelineTest.kt | **VALID** (11 tests) | Flood containment, fallback-to-box, exterior paint, tier classification, stats; the "random" fixture is a fixed arithmetic pattern (deterministic, not RNG). APIs verified in BoundaryAwarePipeline.kt. |
| NnapiCapabilityGateTest.kt | **VALID** (9 tests) | Boundary/reason-exact gate decisions vs NnapiCapabilityGate.decide; includes the "unknown headroom must not invent a rejection" guard. |
| NnapiHealthMonitorTest.kt | **VALID** (4 tests) | Rolling-window mismatch disable, shared-rejection immunity, eviction. |
| StrictNnapiFallbackTest.kt | **VALID** (6 tests) | Strict rerun semantics (NNAPI→XNNPACK→PUSH_PULL), mismatch vs shared-rejection accounting, no-recursion when disabled. |
| PushPullGradientTest.kt | **VALID** (4 tests) | Local ring median + push-pull fill with tolerance assertions; masked-only mutation; empty-mask no-op. |
| BubbleMaskBuilderTest.kt | **VALID** (24 tests) | Re-derived: disk dilation kernel `dx²+dy²≤r²` (r1 = 5 px, r2 = 13 px, corners excluded), buildRectMask padding/clamp/union/skip semantics, chamfer distance normalization (`dist[1] = 1f` exact), featherAlphaField ramp monotonicity, computeNeuralCrop = `box*3 coerced [384,512]` (constants verified at BubbleMaskBuilder.kt:378-384). Telea tests use tolerance bands (±15, Dirichlet range containment) — deterministic, not FLAKY. |
| SmartBubbleTextCleanerTest.kt | **VALID** (17 tests) | Regression guards for documented symptoms (gray rectangle, hard feather step, color bleed, screentone false positives). Assertions are behavioral (spread/monotonicity/tolerance), exact pins (`center shouldBe 60` line 155; midpoint 110 line 487) are deterministic against buildLocalBackground/applyFeatheredFill. **LOW** note: `getWorkingBuffers` test (lines 604-637) uses reflection into a private method + fields — deterministic but refactor-brittle; consider an `internal` seam if it ever breaks. |
| BubbleSegmentationDecoderTest.kt | **VALID** (7 tests) | Dense vs RLE decode parity property tests, mid-row/full-width run closing, threshold drop, NMS suppression; expected bounds re-derived incl. inverse letterbox (model 640² → image 640x320 offset 160). Added by 6694ab6 alongside the RLE work. |
| InpaintMaskSerializationTest.kt | **VALID** (5 tests) | Round-trip durability + `hasCurrentInpaintMask` gate semantics; matches PageTranslationState.kt:67-69 (`blocks.isEmpty() || inpaintMaskBoxes.isNotEmpty()`) and its resume-gate consumers (TranslationPipeline.kt:1634,1672; TranslationLifecyclePolicy.kt:108). |

**Policy compliance (Director inpainting policy):** the geometric-assertion tests flagged for rewrite/delete are confined to **AotReportBubbleFillTest tests 1-3 (H1)**. No other file in the suite pins stage-level pixel geometry as acceptance; the rest are pure-helper unit tests or behavioral regression guards that already match the "runs + cleaned output" spirit.

**Observation (LOW, coverage):** there is **no JVM stage-level test** for `AOTInpainting` itself (the stage that must "run and produce a cleaned image" per the policy) — by design it requires ONNX/Android; the pure-helper extraction plus AotCorpusGateTest is the current substitute. Any new stage acceptance test must be instrumented/on-device.

## H8 — `:domain` module tests

- `tachiyomi.domain.translation.pools.DirectBufferPoolTest` — **VALID** (8 tests). All expectations verified against `domain/src/main/java/tachiyomi/domain/translation/pools/DirectBufferPool.kt` (FIFO reuse, maxPoolSize drop, identity-based in-use tracking via IdentityHashMap, `clear()` frees + resets). The two leak-regression tests (mutation across acquire/release; 100-cycle churn) pin the documented June-2026 OOM fix; the file-level KDoc correctly warns against re-adding zeroing. Deterministic.
- Upstream Mihon tests (`ChapterRecognitionTest`, `MissingChaptersTest`, `LibraryFlagsTest`, `FetchIntervalTest`, `GetApplicationReleaseTest`) — **VALID / out of blast radius**: last touched only by pre-translation-era commits (`git log -- domain/src/test` shows initial-import lineage only); no T903/T904 contract intersects them. No findings.

## H9 — Boundaries, pre-governor sweep, and summary

**Boundary notes (to avoid double-assignment):**
- `AiTranslationRetryControllerTest`, `AiTranslationRetryPlannerSinglePageTest`, `ProviderRequestGovernorTest`, `TranslationRetryTest` (translator package, retry/governor contract) → **Area 1** per README.
- `CleanedImagePublisherTest`, manager/lifecycle/queue tests in `eu.kanade.translation` root → **Areas 2/3**.
- Within my boundary I found **no pre-governor or pre-typed-failure assertions**: no provider-side test asserts untyped `RuntimeException → Completed/Success` flows, raw exception-message equality, or governor-agnostic request timing. The seed pattern (SequentialBatchCoordinatorTest.kt:408-437) has no analogue in area 4.

**Final summary for area 4:**

| Class | Count | Items |
| --- | --- | --- |
| WRONG | 0 | — |
| STALE | 3 (1 hard + 2 latent) | AotReportBubbleFillTest tests 1-3 — diagonal test fails deterministically (test is wrong; production matches its documented dynamic-inset design since b4eaa14) |
| REDUNDANT | 1 | AiModelFetcherParseTest.kt:83-86 duplicate normalizeBaseUrl test |
| FLAKY | 0 | all suites deterministic (no timing, no RNG, no ordering dependence) |
| VALID | ~130 | per-file counts in H5/H7/H8 |

**Recommended actions (priority order):**
1. Rewrite AotReportBubbleFillTest tests 1-3 to run+cleaned-output smoke checks or delete them (policy-compliant fix for the red baseline); delete the dead `insetPx` variable + stale comment at AotReportBubbleFill.kt:38 in the same approved change. (H1)
2. Delete AiModelFetcherParseTest.kt:83-86. (H3)
3. Optional hardening: Gemini 400→no-thinking fallback test, gemini-3 thinkingLevel payload case, `fetch()`/`validateAuth` typed-outcome boundary tests via the existing `internal var requestGovernor` seam, and a minimal OpenAI-compatible-family payload/error-classification test. (H2/H3/H4)

## Unnecessary-test pass (T906 second pass, AUDIT-ONLY, 2026-08-28)

Second pass over area 4 hunting unnecessary tests (no real invariant, duplicate coverage, trivial assertions, dead contract) not already acted on by the fix commit. Scope: translator adapter/support tests, inpainting/AOT/segmentation suite, `:domain` tests. Boundary exclusions unchanged from H9 (retry/governor → area 1; manager/lifecycle/queue → areas 2/3). Files re-examined that pass 1 (H7) had not itemized: MaskGeometryTest, MaskGeometryStressTest, BubbleCleanerMathTest, PageInpaintingPlannerTest, plus a fresh redundancy sweep over the pass-1-VALID files. AotReportBubbleFillTest tests 1-3 and AiModelFetcherParseTest duplicate are excluded (acted on in commit `ba24abc`).

| # | Test | File:Line | Why no value | Recommendation |
| --- | --- | --- | --- | --- |
| U1 | `compute class routing classifies ML Kit as serialized and remote as overlapping` | Checkpoint2IntegrationTest.kt:13-17 | Assertion-identical duplicate of TranslatorComputeClassTest.kt:46-49 (`only REMOTE_IO may overlap native`) — same two enum-property assertions, no fixtures of its own. | delete |
| U2 | `compute class routes the configured standard engines correctly` | Checkpoint2IntegrationTest.kt:19-40 | MLKit/DEEPL cases duplicate TranslatorComputeClassTest.kt:14-32; the AI-by-string case (lines 31-35) is subsumed by the exhaustive `every AI engine is REMOTE_IO` loop (TranslatorComputeClassTest.kt:35-43), which matches on the same `kind.engine.name` and covers every AI kind, not just gemini. Pass 1 (H5) called this "partial redundancy, acceptable"; the second pass finds zero remaining delta. | delete (keep the file's streaming tail-flush test, which is unique coverage) |
| U3 | `large sparse geometry construction and indexed queries stay bounded` | MaskGeometryTest.kt:47-65 | Scale coverage is superseded by MaskGeometryStressTest test 1 (10_000×20 / 20k spans vs 2_000×10, deterministic). The only unique element is the `assertTimeoutPreemptively(3s)` wall-clock gate (line 56) — environment-sensitive on loaded CI hosts, and directly contradicts the stress suite's documented no-wall-clock policy (MaskGeometryStressTest.kt:15-19). It is also the only timing gate in the area-4 app suites (grep-verified). | delete (minimum: drop the timeout wrapper and keep the scale checks) |
| U4 | `exceeding the configured span budget is rejected without partial construction` | MaskGeometryStressTest.kt:60-67 | Duplicate of the maxSpans rejection in MaskGeometryTest.kt:104-117 (`rejects invalid dimensions empty masks and configured limits`); the scale delta (1000 vs 1) is semantically irrelevant because the production check is a bare `spans.size > maxSpans`. The extra `assertTrue(error.message != null)` (line 66) is trivial, and "without partial construction" is not actually observable from outside the throw. | delete |
| U5 | `unionMasks empty inputs return empty` | BubbleCleanerMathTest.kt:78-80 | Trivial: the min-size truncation contract is already pinned by the sibling test (lines 71-75, where `b` is longer and the result is cut to min size); empty×empty follows as min(0,0)=0. The test only proves "does not throw on empty". | delete (LOW) |
| U6 | `pool with maxPoolSize one never allocates more than one buffer under churn` | DirectBufferPoolTest.kt:153-175 | Duplicate of `mutating buffer position between acquire and release does not leak` (lines 131-151): identical identity-reuse invariant. The leak mechanism is deterministic per cycle, so 100 additional cycles add no new coverage. The single-cycle test also carries the mechanism documentation. | delete (or keep as an optional cheap stress guard — no wrong behavior either way) |
| U7 | `buffer is usable for normal put_get round-trips` | DirectBufferPoolTest.kt:46-56 | Near-trivial: put/get on a freshly acquired buffer pins java.nio FloatBuffer semantics, not pool logic. Usability is covered more strongly by `recycled buffer is writable and readable after a release-acquire cycle` (lines 84-101), which adds the recycle angle. | delete (LOW); keep only if a "pool hands back a sane buffer" smoke assert is wanted |

Examined and explicitly KEPT (verified distinct coverage, not listed above): BubbleCleanerMathTest classifyBackground/shouldUseSolidFlatFill branch matrix (each test pins one classifier branch); PageInpaintingPlannerTest in full (planner decisions, persisted-mask precedence, blank-text exclusion — all unique); AotPixelOpsTest (16 branch-distinct math pins); TranslatorComputeClassTest; GeminiRequestPayloadTest; AiModelFetcherTest; the rest of the H7 inventory (pass-1 VALID with re-derived expectations, no duplicates found on name/branch sweep). SmartBubbleTextCleanerTest's reflection-based buffer-reuse test (H7 LOW note) stays keep-with-caveat: brittle but pins a real memory-bound contract.

Net: 7 candidates, all deletions; no rewrites. If all are applied, ~7 low-value tests drop with zero coverage loss.

## Dispositions (T906 Area-4 fix implementer, 2026-08-28)

**Part 1 — fix slice (commit `ba24abc` on `t906/fix-area4`, single commit off `56179d7`):**

- Reviewed the predecessor's 3 modified files against the assignment. (a) AotReportBubbleFillTest tests 1-3 were already rewritten as policy smoke checks (runs + flat fill, never which color; fillAndBlend equivalence test untouched) — but rewritten test 3 asserted nothing about masked pixels, so I completed it: components upgraded from single pixels to 2-pixel connected pairs with differing colors, with per-component flat-fill assertions added (single pixels made flatness trivially true and untestable). (b) Dead `insetPx = 5` + stale five-pixel comment deleted at AotReportBubbleFill.kt (former line 38). (c) Duplicate `normalizeBaseUrl` test deleted from AiModelFetcherParseTest (coverage retained in AiModelFetcherTest).
- Gates all green: `spotlessApply`, `spotlessCheck`, `:app:compileStandardDebugKotlin`, and `:app:testStandardDebugUnitTest --tests eu.kanade.translation.inpainting.AotReportBubbleFillTest --tests eu.kanade.translation.translator.AiModelFetcherParseTest` (4/4 and 8/8, 0 failures; test-results XML verified so the filter ran the suites). Environment note: fresh worktree needed `JAVA_HOME` pointed at the Android Studio JBR and a gitignored `local.properties` (`sdk.dir=C:\...\Android\Sdk`, mirrored from the main checkout, left uncommitted).
- One commit, 3 files, +49/−30: AotReportBubbleFill.kt, AotReportBubbleFillTest.kt, AiModelFetcherParseTest.kt.

**Part 2 — second AUDIT-ONLY unnecessary-test pass:** performed as documented in the section above; 7 delete candidates found, no code or test changes made.
