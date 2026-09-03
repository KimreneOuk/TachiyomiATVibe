# Final review — `43e7b6b` PageTextRenderer deletion

## Verdict

**Source-level removal is correct and narrowly scoped.** Commit `43e7b6b`
removes the unused renderer and its dedicated Android test, extracts the cache
unchanged, renames the pure direction test to its real owner, and removes all
live source/test/documentation references. No executable overlay, planner,
DTO, threshold, or segmentation behavior changed in this deletion commit.

This is **not a verified build/device pass**. Kotlin/Gradle compilation and
focused Android instrumentation remain unresolved and must not be reported as
passing.

## Verified deletion invariants

| Invariant | Result | Primary evidence |
| --- | --- | --- |
| Cache extraction preserves behavior | VERIFIED | The 55-line body of `ComponentClipCache` in the parent renderer (`43e7b6b^:app/src/main/java/eu/kanade/translation/rendering/PageTextRenderer.kt:324-378`) exactly equals the body of `app/src/main/java/eu/kanade/translation/rendering/ComponentClipCache.kt` after its required package/import header. The retained class keeps generic/internal API, packed key, instance identity check, and cap-before-create checks (`ComponentClipCache.kt:15-59`). |
| Live overlay still uses that cache and same clip order | VERIFIED | Overlay imports/creates it at `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt:16,82-96`; its component → cell → legacy clip sequence is unchanged at `:185-191`, with the existing 64-component / 100,000-span limits at `:262-263`. |
| Cache contract tests remain | VERIFIED | The six focused cache JVM cases remain in `app/src/test/java/eu/kanade/translation/rendering/ComponentClipCacheTest.kt:28-120`, including identity mismatch, invalid id, component cap, and span cap. |
| Renderer and only dedicated renderer test are deleted | VERIFIED | Commit status records deletion of `app/src/main/java/eu/kanade/translation/rendering/PageTextRenderer.kt` and `app/src/androidTest/java/eu/kanade/translation/rendering/PageTextRendererInstrumentedTest.kt`; no other test file is deleted. Current filesystem has neither file. |
| Pure direction test preserved under correct ownership | VERIFIED | Git reports a 98% rename from `PageTextRendererDirectionTest.kt` to `TextLayoutPlannerDirectionTest.kt`; the class is renamed and still directly tests `TextLayoutPlanner` (`app/src/test/java/eu/kanade/translation/rendering/TextLayoutPlannerDirectionTest.kt:6-79`). |
| Migration-review safeguards retained | VERIFIED at source level | Remediation makes disconnected assigned ink explicit (`TranslationOverlayViewInstrumentedTest.kt:99-121`) and adds repeated prepared-draw stability/clip coverage (`TranslationOverlayViewLifecycleInstrumentedTest.kt:35-73`). The remediation correctly states that this is not an allocation measurement (`engineering/page-text-renderer-test-migration-remediation.md:11-20`). |
| No live references remain | VERIFIED | `rg -n -i "PageTextRenderer" app/src/main app/src/test app/src/androidTest docs` exits 1 with no output. The live comment updates are documentation-only, e.g. `TranslationOverlayView.kt:219-226`, `RenderColorEstimator.kt:32-36`, and `TextLayoutPlanner.kt:515-523`. |
| No production behavior altered in this commit | VERIFIED | The production diff comprises the unchanged cache extraction, renderer deletion, and comment-only edits to `TranslationOverlayView`, `PageTranslationHelper`, `RenderColorEstimator`, and `TextLayoutPlanner`; `git diff --word-diff=porcelain 43e7b6b^ 43e7b6b -- app/src/main/java` shows no executable modification outside the extracted cache. |
| No T918 content in deletion change or staged/modified state | VERIFIED | `git diff --name-only 43e7b6b^ 43e7b6b`, `git diff --cached --name-only`, and tracked `git diff --name-only` contain no T918 path. The worktree does contain untracked `Plan/active/2026-09-03_T918_batch-retry-affordance/` content, but it is neither staged nor modified by this commit. |
| Diff integrity | VERIFIED | `git diff --check 43e7b6b^ 43e7b6b` is clean. |

## Findings

### F1 — Required Gradle and Android verification remains unresolved

- **Severity:** HIGH
- **Likelihood:** medium
- **Classification:** verification gap
- **Evidence status:** VERIFIED
- **Primary evidence:** the deletion implementation report explicitly says that
  Kotlin compile and Android instrumentation were not run and claims no success
  (`Plan/active/2026-09-03_T919_text-layout-renderer-maintainability-audit/engineering/page-text-renderer-removal-implementation.md:18-26`). The remediation report likewise records wrapper/daemon startup without task result or exit code (`engineering/page-text-renderer-test-migration-remediation.md:23-39`). Independent review has no configured `JAVA_HOME`/`java`, so the standard Gradle wrapper command terminates before Gradle configuration.
- **Impact:** source comparison strongly supports a safe deletion, but it cannot
  prove Kotlin source-set compilation, Android resource/test compilation, or
  device raster behavior.
- **Confirm/refute:** run the removal plan's `:app:compileDevDebugKotlin`,
  `:app:compileDevDebugAndroidTestKotlin`, focused cache/planner JVM tests, and
  the four focused overlay/color instrumentation classes on API 26+ in an
  environment that returns definite exits.

### F2 — Repeated-draw testing is a stability gate, not proof of a quantified allocation budget

- **Severity:** MEDIUM
- **Likelihood:** medium
- **Classification:** design limitation / residual coverage gap
- **Evidence status:** VERIFIED
- **Primary evidence:** the new lifecycle test binds once then compares sixteen
  repeated source-space draws (`TranslationOverlayViewLifecycleInstrumentedTest.kt:35-54`), while the retained renderer prepares paths at bind and draws later
  (`TranslationOverlayView.kt:82-111,130-145,175-245`). The remediation report
  expressly limits its claim to stable output/bind-draw separation and says it
  does not measure allocation (`engineering/page-text-renderer-test-migration-remediation.md:11-20`).
- **Impact:** deletion did not introduce an allocation change, but the former
  `PageTextRenderer.draw()` post-bind allocation constraint has no quantified
  successor gate for the live overlay. This is not a reason to restore the
  unused adapter; it remains profiling work if that numeric guarantee is
  required.
- **Confirm/refute:** record an Android profiler/allocation result for repeated
  overlay frames, distinguishing platform Canvas/text allocations from
  application-owned work.

## Review conclusion

The commit meets the planned removal/source-cleanup contract and is reversible
as an isolated deletion/cache-move change. The untracked T918 task material was
left untouched. Merge/acceptance should carry F1 as an explicit unresolved
verification gate; do not state or imply that Gradle or Android tests passed.
