# tools/dev — Developer Tools & Diagnostic Rigs

## Page15MockRig.kt

`Page15MockRig.kt` is a developer visual layout diagnostic rig that executes the production text layout planner (`TextLayoutPlanner.planPage`) against cached detection data (Konoka to Kossori 3 page 15) and generates SVG previews of text layout and adaptive band fitting.

### Why it was moved
Previously located in `app/src/test/java/eu/kanade/translation/rendering/Page15MockRig.kt`, it executed during every standard unit test suite run whenever fixtures were present, producing non-test SVG writes into `Plan/.../fixtures/rig-out/`. Per T931 bottleneck audit and EXECUTION_ORDER v3 task 0.3, it was evicted from the test source set to eliminate non-regression suite overhead and avoid working tree pollution.

### How to use / drop back
To run this diagnostic rig locally:
1. Temporarily copy or link `tools/dev/Page15MockRig.kt` to `app/src/test/java/eu/kanade/translation/rendering/Page15MockRig.kt`.
2. Run the specific test:
   `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.rendering.Page15MockRig"`
3. View generated SVG artifacts in `Plan/active/2026-08-30_T912_text-layout-renderer/engineering/fixtures/rig-out/`.
4. Delete the temporary test file in `app/src/test/...` before committing.
