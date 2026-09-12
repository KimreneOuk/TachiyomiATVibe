# T924 WP9 — Slice C report: persisted layout publication + reader hydration behind FF-02

Worktree: `TachiyomiAT-t924-impl` · branch `t924/batch-profile-pipeline` · base HEAD `ee858f9`
Status: **code final, slice-green** (543/545 tests in the focused set pass; the 2 failures are foreign-owned, see §8). No git add/commit performed (per assignment).

---

## 1. Deliverable map (contract anchors, file:line)

All paths relative to `app/src/main/java/`.

### FF-02a(1) — batch-side publication (LAYOUT_PREPARE + COLOR render body split)

| Contract item | Anchor |
|---|---|
| FF-02 flag (default OFF) | `eu/kanade/translation/domain/TranslationPreferences.kt` :255 `translationBatchPersistedLayout(): Preference<Boolean>`; consumed via `.get()` at `rendering/PersistedLayoutRuntime.kt:52` |
| Runtime flag seam (guarded Injekt read; test override) | `rendering/PersistedLayoutRuntime.kt:50` `flagEnabled()`, override `:30-44`, `resetForTest()` :74 |
| Gate: render success path dispatch | `pipeline/batch/BatchRenderJoin.kt:311` — only on `StagePatchResult.Accepted` (`renderPersisted`); legacy byte-path untouched when flag OFF |
| Publication entry | `pipeline/batch/BatchRenderJoin.kt:368` `publishPersistedLayoutIfEnabled` (flag gate; `CancellationException` rethrown; any `Throwable` → WARN + skip, render outcome never mutated) |
| Publication body | `pipeline/batch/BatchRenderJoin.kt:385` `publishPersistedLayout` — TX-23 fences :396-447, plan+color assembly, `publishSidecarPointers` call :500, `Committed → store.artifactManifest = outcome.manifest`, `Rejected →` WARN non-fatal |
| Plan/color assembly (pure) | `rendering/LayoutPlanPublication.kt:73` `prepare(...)` — planner → `projectToDrawPlan` → `validationError` guard → canonical JSON (`ArtifactDocumentJson`, T924-SC-06) → `ColorStylePreparation` → content fingerprints via `StageFingerprints.envelopePlanContentFingerprint` (T924-SC-10 byte core) |
| Compat fingerprint (FP-07, reader-side recomputation) | `rendering/LayoutPlanPublication.kt:155` `compatibilityFingerprint(...)`; engine version :33/:165 |
| Store sidecar plumbing (additive) | `artifact/ChapterArtifactStore.kt` — `SidecarRead` :66 (top-level), `layoutPlanSidecarName`/`colorPreparationSidecarName` :813/:817 (content-addressed), `jsonSidecarPublication` :826, `readSidecarDocument` :844 (mirrors `readOcrCheckpoint` idioms) |
| Manifest pointers | `artifact/ChapterArtifactManifest.kt:73/75` `layoutPlans` / `colorPreparations: Map<String, SidecarPointer>` (v3); page `layout: StageArtifactRecord` carries the compat fingerprint |
| Production font digest | loader installed idempotently at `pipeline/batch/BatchRenderJoin.kt:571` `installFontDigestLoader` (`resources.openRawResource(R.font.animeace)` → `DrawPlanFingerprint.fontAssetSha256`, computed once, cached in `PersistedLayoutRuntime`); reader-side install at `ui/reader/viewer/TranslationOverlayView.kt:82` |

### FF-02a(2) — reader hydration

| Contract item | Anchor |
|---|---|
| Hydrator | `rendering/PersistedLayoutHydrator.kt:72` `hydrate(...)`, ordered typed outcomes |
| Outcome type (never partial) | `:198` `sealed interface HydratedLayout { Resolved / Lossy / Incompatible / UnsupportedVersion / Absent }` |
| Reader/overlay wiring | `TextLayoutCoordinator.kt:65` ctor `hydrate` lambda; background body :104 `hydrate?.invoke(...) ?: plan(...)` — planner counter stays 0 on hydrated hits; generation stale defense unchanged. Overlay installs bridge + font loader at `TranslationOverlayView.kt:82,108-110` |
| Process-wide bridge (deviation D2) | `rendering/PersistedLayoutHydrator.kt:234` `PersistedLayoutReaderBridge` (`install` :245, guarded `hydrate` :253) |
| Cache contract | `rendering/ReaderTextLayoutCache.kt` KDoc: holds HYDRATED/prepared draw objects only, never DTO bytes (gate 7.6 note) |

### Compatibility matrix (FP-07 rows — every mismatch names its reason, replan-never-mis-draw)

`PersistedLayoutHydrator.hydrate`, in order:
1. `:96` font asset digest changed
2. `:106` layout planner version changed (matrix row 11)
3. `:109` stroke policy version changed (row 9, geometry-affecting)
4. `:112` stroke color policy version changed
5. `:116` platform shaping key changed
6. `:121` source page dimensions changed (`toRawBits` exact)
7. `:126` bind dimensions disagree with source dimensions
8. `:129` decode sample size changed
9. `:133` stored compatibility fingerprint changed (gate 7-5b)
10. `:142` translation content changed since publication (gate 7-3 user-edit authority — NEW this slice; per-block `block.translation != planBlock.chosenText` → `Incompatible`)
11. `:157` F5 loss contract: rehydrate count mismatch → `Lossy(resolvedCount, expectedCount, reason)`, never silent partial
12. SSIV pan/zoom transforms are **not** fingerprints — repeated hydration is stable (`DrawPlanCompatibilityTest` last case)

Unknown version → `UnsupportedVersion(schemaVersion)` preserved, never quarantined/overwritten (T924-SC-13); corrupt → store quarantines + `Absent`; missing → `Absent`. All route to the mandatory async planner fallback (FF-02b) — including Manual/Auto and legacy no-pointer data.

## 2. TX-23 CAS precondition set (as enforced in `publishPersistedLayout`)

Before assembling/publishing, against the post-render `postSnapshot`:
1. Manifest authority == `ARTIFACTS`
2. Artifact page-version fence: durable manifest's page record version vs snapshot (`artifactPageVersion` mismatch → skip)
3. Candidate generation fence (`candidateGenerationId` still current)
4. Dependency fingerprint fence (`dependencyFingerprint` unchanged)
5. OCR block identity: `page.ocrBlockFingerprints() != postSnapshot.page.ocrBlockFingerprints()` → skip
6. Publication itself is one `publishSidecarPointers` transaction (sidecars first, ONE atomic manifest promotion) whose whole-manifest CAS rejects any concurrent movement (`staleManifestRejection`) — fail-closed
7. Cleaned-image/color identity + layout policy/planner/font versions enter the **content-addressed sidecar names** (`layoutPlanSidecarName(pageKey, contentFingerprint)` = SHA-256 over canonical plan JSON incl. fontIdentity, planner/stroke versions) and the **compat fingerprint** stored on the plan/`layout` record — a re-render at different inputs cannot be mistaken for the old artifact
8. `Committed` outcome updates the façade manifest reference; `Rejected` is a WARN + no-op (next render republishes)

## 3. Hydration-loss contract (review F5)

`LayoutDrawPlanProjection.rehydrate` skips inputs it cannot resolve (missing mask geometry etc.); `hydrate` then compares `layouts.size` vs `plan.blocks.size`. Mismatch ⇒ `HydratedLayout.Lossy(plan, resolvedCount, expectedCount, reason)` — the coordinator treats every non-null-only outcome that isn't a usable list as fallback input, so a lossy plan can never draw partially. Fully-resolved hydration is `Resolved(layouts, plan)` only when every plan block resolved AND the user-edit check passed.

## 4. Invalidation wiring summary

- Batch: stage CAS rejects changed inputs before publication; content-addressed file names prevent stale-pointer collisions.
- Reader: 12 typed checks above; any `Incompatible`/`Lossy`/`Absent`/`UnsupportedVersion`/bridge-null/throwing-source ⇒ synchronous fallback to the async `TextLayoutPlanner` (gate 7.7); bind-generation defense drops stale deliveries (gate 7-4).
- FF-02 OFF ⇒ `publishPersistedLayoutIfEnabled` returns before any store mutation and `hydrate` is never installed by the overlay ⇒ byte-for-byte legacy behavior.

## 5. Gate-oracle tests (JVM), all green

| Oracle file | Tests | Covers |
|---|---|---|
| `rendering/DrawPlanDtoRoundTripTest.kt` | 7 | Full store round trip through `publishSidecarPointers` (exactly the BatchRenderJoin transaction) → byte-stable canonical plan (T924-SC-06) → hydration reproduces planner geometry bit-for-bit (`toRawBits`); unresolvable inputs = typed Lossy (F5); user-edited translation invalidates, OCR identity untouched (gate 7-3); unknown version preserved (SC-13); corrupt quarantined + Absent; masked blocks rehydrate component geometry (`planGeometryId`) |
| `rendering/DrawPlanCompatibilityTest.kt` | 14 | Baseline Resolved; named-reason matrix (digest, font/paint identity, planner version row 11, stroke policy row 9, stroke color policy, platform key, page dims, sample size, bind dims, unpinned digest); stored compat fp mismatch (7-5b); color-only change keeps geometry compatible + color prep separately invalidatable (row 10); repeated hydration stable (SSIV absent) |
| `rendering/PersistedLayoutHydrationTest.kt` | 10 | Hydrated bind / LRU eviction+rebind / process-restart rebind all with planner invocation counter 0 (gate 7.6 JVM part); fallback green ×4 (no bridge source, bridge null, throwing source, every typed outcome) incl. FF-02-off parity (gate 7.7); cached rebind = synchronous cache hit; bind-generation stale defense (gate 7-4); runtime seams (flag override, digest pinning/cache) |

Focused run: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.rendering.*" --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.artifact.*"`
Result: **545 completed, 543 passed, 2 failed (foreign, §8)** — all 31 WP9 gate-oracle tests green; all 148 `artifact.*` tests green (my additive store changes break nothing, incl. `ChapterArtifactStoreTest` 45, `CheckpointOcrTransactionTest` 12, `SidecarCrashPublicationTest` 8); all `rendering.*` suites green (planner untouched: `TextLayoutPlannerTest` 31 etc.).

## 6. On-device rows OWED (gate 7.x device legs, JVM cannot exercise)

1. Gate 7.x: on-screen stroke/AA parity — hydrated draw vs live-planned draw pixel comparison (0.5px tolerance) on a real device, Pager + Webtoon.
2. Gate 7.6 holder legs: real Pager/Webtoon holders bind a hydrated plan across activity recreate / LRU pressure with planner counter instrumentation.
3. Gate 7.8 (explicitly NOT mine) — listed here only as a cross-reference for the Stage-7 exit owner.
4. Bridge production install site: `TranslationOverlayView.init` installs the font digest loader, but the `PersistedLayoutReaderBridge.install(...)` call resolving the chapter manifest pointer needs the holder/ReaderViewModel land (Stage-7 wiring; JVM tests exercise the bridge seam directly).
5. `platformShapingKey` on-device exercise: confirm two different SDK buckets reject each other's plans (unit test pins the mechanism with a synthetic key).

## 7. Diff summary (owned files)

Main sources:
- NEW `rendering/PersistedLayoutRuntime.kt` (80), `rendering/LayoutPlanPublication.kt` (195), `rendering/PersistedLayoutHydrator.kt` (245), `rendering/ProductionTextMeasurer.kt` (41)
- `pipeline/batch/BatchRenderJoin.kt` +260 (publication helpers; success-path dispatch only)
- `artifact/ChapterArtifactStore.kt` +89 (strictly additive: `SidecarRead`, sidecar-name helpers, `jsonSidecarPublication`, `readSidecarDocument`)
- `rendering/TextLayoutCoordinator.kt` +21 (opt-in `hydrate` ctor param + fallback), `rendering/ReaderTextLayoutCache.kt` +7 (KDoc), `ui/reader/viewer/TranslationOverlayView.kt` +31 (wiring)
- `rendering/TextLayoutPlanner.kt`: **ZERO diff** (verified). `ui/reader/viewer/ReaderPageImageView.kt`: **ZERO diff**.

Tests: NEW `rendering/DrawPlanDtoRoundTripTest.kt`, `rendering/DrawPlanCompatibilityTest.kt`, `rendering/PersistedLayoutHydrationTest.kt`.

## 8. Deviations & risks

- **D1 — `wave2-review.md` not found** in the task tree; F5/font/platformShapingKey obligations were taken from the assignment text. Risk: none functional; note for the exit-checklist owner.
- **D2 — bridge seam without production install site**: reader hydration resolves the chapter pointer through `PersistedLayoutReaderBridge`, but the production `install(...)` call needs holder/ViewModel context I do not own. Until Stage-7 wires it, `hydrate` resolves to null ⇒ planner fallback ⇒ FF-02 behaves as OFF for reading even when ON for publishing. This is fail-safe (never wrong draw), and the flag defaults OFF anyway.
- **D3 — ChapterArtifactStore additions beyond a "generic reader"**: content-addressed name helpers + `jsonSidecarPublication` (AtomicChapterDocuments is private; pipeline cannot construct sidecars otherwise). Additive only; all 148 artifact tests green.
- **R1 — `Injekt.get<TranslationPreferences>()` in JVM tests**: guarded `runCatching`, safe OFF when DI is empty (pinned by `PersistedLayoutHydrationTest` seams test).
- **R2 — publication failure modes are WARN+skip**: a rejected publish never fails a rendered page; next render republishes. Cost: possible republication churn, never staleness.

## 9. Foreign-owned failures in the focused run (NOT mine — for attribution only)

`pipeline/batch/OcrPreflightRejectedMidRunDurabilityTest` (2 failures: "Expected value to not be null" at :230/:296) — an **untracked new test file** of the parallel WP8 agent exercising their in-flight `BatchChapterTranslator.kt`/`ChapterProfileBatchCoordinator.kt` edits (both `M` by them; I never touched those files). Retried after 90s: still failing (their churn, expected to settle with their slice). Also seen once and then passing on retry: `coexistence/StandardLaneMultiPageCompletionTest` (transient). My owned suites: 0 failures.

## 10. Deferred (genuinely owed, listed per coordinator guidance)

- On-device rows in §6 (device legs of gates 7.x/7.6 + bridge install wiring + platformShapingKey device exercise).
- `PersistedLayoutReaderBridge` production install call-site (Stage-7).
