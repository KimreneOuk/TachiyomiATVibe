# Text-layout offload for the translation overlay (T920 Phase 3 Step 3.1)

**Date**: 2026-09-03
**Branch**: `optimize_reader_lazy_loading` (worktree `optimize_reader_lazy_loading`)
**Method**: Code + JVM tests + build only. No device interaction (Director ban honored).

## Result

`TranslationOverlayView.bind` no longer performs any planner or layout work on the
calling (Main) thread on a cache miss. The synchronous `TextLayoutPlanner.plan(...)`
+ `prepareLayouts(...)` pair (previously at `TranslationOverlayView.kt:80-82`, the last
unbounded Main-thread CPU item at reader entry) is now scheduled on a dedicated
single-thread planner executor, and the prepared layouts are delivered back to Main
through a generation guard. A strict bounded LRU cache (12 entries, shared across all
overlay instances, keyed by blocks content + pageWidth + pageHeight) makes identical
page rebinds apply synchronously with zero planner work; identical rebinds remain the
existing cheap early-return. The planner itself was verified stateless (all mutable
state is function-local in the 3,794-line `TextLayoutPlanner`), so it needed no
semantic changes; only the measurer's mutable `Paint` required confinement, solved by
giving the background planner its own `Paint` copy and a single-thread executor.

## Commits

| Hash | Subject |
|---|---|
| `8380944` | perf(reader): add bounded LRU cache and background coordinator for overlay text layout |
| `953cdae` | perf(reader): offload translation overlay planning off the binding thread |
| `562b4aa` | test(reader): pin overlay layout cache and bind-time planning offload |

(`953cdae` is the amended form of the original wiring commit; it includes the
`logcat` import fix required for it to compile.)

## Production changes

1. `app/src/main/java/eu/kanade/translation/rendering/ReaderTextLayoutCache.kt` (new)
   - `TextLayoutCacheKey(blocks, pageWidth, pageHeight)`: content-based key. Blocks
     compare by live value equality, so a block mutated after an entry was stored can
     only cause a false MISS (re-plan), never a stale hit — fail-closed.
   - `ReaderTextLayoutCache<T>`: strict bounded LRU (access-ordered
     `LinkedHashMap`, evicts the moment `maxEntries` would be exceeded). Main-thread
     confined by design; no locks.
2. `app/src/main/java/eu/kanade/translation/rendering/TextLayoutCoordinator.kt` (new)
   - JVM-pure coordinator (no Android types) owning bind identity + generation:
     - identical rebind → `Unchanged` (cheap early-return preserved);
     - cache hit → `Ready(prepared)` applied synchronously, zero planner calls;
     - miss → `Planning`: `plan` runs on the background executor; result is dropped
       before planning if already superseded, and the Main-thread apply re-checks the
       generation, so a recycled / re-bound / detached view can never apply a stale
       result;
     - empty/invalid input → `Cleared` (safe empty draw state), with repeated clears
       kept as `Unchanged` no-ops;
     - planner exceptions and rejected-executor submissions degrade to the empty
       state (logged via `logcat`), never crash bind.
3. `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt`
   - `bind()` delegates to the coordinator; on `Ready` it applies synchronously; on
     `Planning`/`Cleared` it drops stale layouts and draws the safe empty case until
     the delivery arrives; `Unchanged` only invalidates when the SSIV instance changed.
   - Background planning measurer with its own `Paint` copy of `fill` (identical font
     and flags, so measurements — and therefore layout results — are byte-identical
     to the old inline path); confined to a single-thread, below-normal-priority
     daemon executor named `TranslationOverlayLayoutPlanner` because `Paint.textSize`
     mutation is not safe for concurrent use.
   - `prepareLayouts` renamed to `buildPreparedLayouts` and made pure
     (returns the list); now runs on the planner thread — `Path` construction is not
     looper-bound and the paths are only read by the Main-thread draw path.
   - Shared 12-entry cache in the companion object (strict global memory bound
     regardless of holder count; sized to cover the warm windows: attach 2/evict 5
     pager, attach 4/evict 10 webtoon, plus scroll-back).
   - `onDetachedFromWindow` bumps the generation (`cancelPending`) so a detached
     view never receives an apply. `bindLayoutsForTest` / `drawLayoutsForTest` /
     `clear()` seams and the entire draw path are untouched.

Callers (all Main-thread, verified): `ReaderPageImageView.setTranslationBlocks`,
`onImageLoaded`, `onImageLoadError`, `recycle` — no changes required there.

## Tests added (JVM, no device)

- `app/src/test/java/eu/kanade/translation/rendering/ReaderTextLayoutCacheTest.kt` —
  10 tests: miss, hit, equal-content-in-fresh-list hit, strict eviction bound, LRU
  recency refresh, key discrimination on width / height / block content / block count,
  clear.
- `app/src/test/java/eu/kanade/translation/rendering/TextLayoutCoordinatorTest.kt` —
  11 tests, driven through manual queue executors that simulate the exact production
  thread ordering:
  - **the required proof that `bind` performs zero planning work on the calling
    thread** (planner-hook counter stays 0 after bind until the background queue is
    drained, and application is deferred to the main queue);
  - synchronous cache-hit apply with no additional planner calls;
  - cheap identical-rebind short-circuit (0 planner calls even mid-flight);
  - superseded binds skipped before planning and stale deliveries never applied;
  - clear and `cancelPending` cancel in-flight results;
  - planner failure and rejected submission degrade without crashing or applying;
  - empty/invalid-dimension binds clear synchronously; bind-after-clear re-hits.

## Verification

| Command | Outcome |
|---|---|
| `./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.rendering.ReaderTextLayoutCacheTest" --tests "eu.kanade.translation.rendering.TextLayoutCoordinatorTest"` | BUILD SUCCESSFUL — 21/21 pass |
| `./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"` | BUILD SUCCESSFUL — 1,356 tests across 189 classes, 0 failures |
| `./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.*"` (incl. `ReaderTranslationOverlayBindingTest` and other overlay-behavior JVM tests) | BUILD SUCCESSFUL, 0 failures |
| `./gradlew.bat :app:assembleStandardDebug` (JAVA_HOME=Android Studio JBR, ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk) | BUILD SUCCESSFUL — APK produced |
| `./gradlew.bat :app:spotlessKotlin` | All touched files format-stable (checksum-verified across runs) |

Overlay draw-migration gates from commits `0365c62` / `f4d8a0b` are instrumented
(androidTest) classes and exercise only `bindLayoutsForTest` / `drawLayoutsForTest` /
`clear()` — all preserved verbatim in behavior. They require a device, which this
session's Director ban prohibits running; their seams were kept intact so they remain
valid. Spotless note: `:app:spotlessApply` fails module-wide on a PRE-EXISTING lint
error in `src/test/java/eu/kanade/translation/rendering/Page15MockRig.kt:L73`
(`standard:property-naming`), a file this slice never touched; my files were
formatted with the format step and verified stable.

## Intentional boundary and remaining risk

- **Stale-visual policy**: the task text suggested "keeping prior visuals until
  ready". I deliberately drop prior layouts when the incoming content DIFFERS (the
  `Planning` path) because keeping them would draw the previous page's text over a
  different page for 1-2 frames — strictly worse than the safe empty case the task
  also requires. Prior visuals are only ever kept where they are provably the same
  content (the `Unchanged` identical-rebind path), which is also where flicker could
  occur. On a first-page miss the overlay now appears a few frames later instead of
  blocking entry — the intended trade.
- **Single-thread planner**: the planner is stateless and could run on
  `Dispatchers.Default`, but the measurer's `Paint` mutation forced single-thread
  confinement. This caps overlay planning throughput at one page at a time; fine for
  reader-entry bursts (2-4 pages) since superseded work is skipped before planning.
- **Cache key cost**: keys hash the block list contents per bind, the same order of
  cost as the `blocks == blocks` equality the early-return already performed before
  this change; measured behavior unchanged.
- **Not covered here**: pre-planning the warm window from
  `ReaderViewModel.updateTranslationWorkingSet` (T920 Step 3.2's cache-warming half)
  was intentionally not added — this slice only removes the Main-thread stall at
  bind. Device timing validation of the entry path remains deferred until the
  Director re-authorizes device access.

---

## Review conditions resolution

Independent review (`review/text-layout-offload-review.md`, CONDITIONALLY APPROVE)
raised two MEDIUM conditions. Both are closed on `optimize_reader_lazy_loading`:

### Condition 1 (R1, MEDIUM defect): stale-`Unchanged` blank-overlay hole after `cancelPending`

**Closed in commit `fe7b3a6` — fix(reader): replan identical rebind after cancelled
overlay planning.**

- `TextLayoutCoordinator.cancelPending()` now clears `boundKey` in addition to
  bumping `generation` (`TextLayoutCoordinator.kt`, `cancelPending`). The defect
  sequence — bind(A) miss → planning in flight → detach cancels the delivery →
  identical rebind of A returned `Unchanged` against the stale bound key and never
  re-planned → blank overlay — is no longer reachable: after the cancel, `boundKey`
  is null, so the identical rebind takes the miss path and re-plans, or takes a
  synchronous `Ready` cache hit when a previous delivery for the same content
  already landed and was cached.
- Pinned by two new tests in `TextLayoutCoordinatorTest`:
  - `identical rebind after cancelPending re-plans instead of staying blank` —
    drives the exact defect sequence and asserts `Planning` (not `Unchanged`) with
    a second planner call and the text applied after drain. Fails on the pre-fix
    coordinator (returns `Unchanged`, 1 plan call, nothing applied).
  - `cancelPending after a successful apply still rebinds identically via the
    cache` — blast-radius guard: cancel after a successful (cached) apply still
    rebinds as a synchronous `Ready` hit, not a wasted re-plan and not a no-op.
- Same-commit housekeeping on the touched lines, per review notes R3/R4: the
  planner-thread `generation` read is now `@Volatile` (formalizes the benign race;
  behavior unchanged — the authoritative check remains the Main-side re-check), and
  the unused `isCleared` property was removed (dead code whose meaning the fix
  would have made misleading).

### Condition 2 (R2, MEDIUM test gap): Main-thread delivery drop branch untested

**Closed in commit `0611603` — test(reader): pin main-thread delivery drop and
post-cancel replan.**

- New test `delivery arriving after cancelPending is discarded on the main thread`
  drives the apply-side drop specifically: bind(A) → `background.runAll()` (plan
  completes and queues its delivery) → **only then** `cancelPending()` →
  `main.runAll()`. Because cancellation happens after planning, the
  background-side pre-plan skip cannot save it; only the final stale-apply re-check
  inside the Main delivery can. Asserts nothing is applied AND the dropped delivery
  leaves the cache untouched.
- **Mutation-verified as required**: with the last-line re-check
  (`if (bindGeneration != generation) return@execute`) temporarily removed, this
  test FAILED (applied non-empty, cache polluted) — and only the two new tests
  failed; all other tests stayed green. The guard was then restored and the full
  focused suite passed 24/24.
- Renamed the pre-existing `cancelPending drops an in-flight result without
  changing bound state` to `...drops an in-flight result`, since the fix
  intentionally changes bound state.

### Re-verification after the conditions (all BUILD SUCCESSFUL, no device used)

| Command | Outcome |
|---|---|
| `./gradlew.bat :app:testStandardDebugUnitTest --tests "...ReaderTextLayoutCacheTest" --tests "...TextLayoutCoordinatorTest"` | 24/24 pass (10 cache + 14 coordinator) |
| `./gradlew.bat :app:testStandardDebugUnitTest --tests "...ReaderTranslationOverlayBindingTest" --tests "eu.kanade.tachiyomi.ui.reader.*" --tests "eu.kanade.translation.*"` | `ReaderTranslationOverlayBindingTest` 3/3; translation package total 1,359 tests / 0 failures |
| Overlay migration gate tests (`TranslationOverlayView{,Lifecycle,Rendering}InstrumentedTest`, commits `0365c62`/`f4d8a0b`) | Cannot be RUN without a device (Director ban unchanged). Compiled instead: `./gradlew.bat :app:assembleStandardDebugAndroidTest` BUILD SUCCESSFUL — the gate classes compile against the changed seams; they use only `bindLayoutsForTest`/`drawLayoutsForTest`/`clear()`, untouched by both fix commits |
| `./gradlew.bat :app:assembleStandardDebug` | BUILD SUCCESSFUL |
| Spotless | Only `TextLayoutCoordinator.kt` and `TextLayoutCoordinatorTest.kt` touched (all other files spotless reformatted were reverted; pre-existing `Page15MockRig.kt:L73` lint failure unrelated and untouched) |

Commits closing the conditions: `fe7b3a6` (fix), `0611603` (tests). Final HEAD:
`0611603`; working tree clean except this Plan folder.
