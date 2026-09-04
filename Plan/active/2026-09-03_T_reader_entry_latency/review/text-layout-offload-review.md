# Review: background text-layout offload for the translation overlay

**Reviewer**: independent Reviewer (per `docs/roles/reviewer.md`)
**Date**: 2026-09-03
**Scope**: commits `8380944`, `953cdae`, `562b4aa` on `optimize_reader_lazy_loading`
(worktree `C:\Users\User\.gemini\antigravity\worktrees\TachiyomiAT-1.16.8-dev\optimize_reader_lazy_loading`)
**Method**: direct diff inspection, code tracing, JVM test run, assemble. No device/adb interaction
(Director ban honored).

## Verdict

**CONDITIONALLY APPROVE.**

The core invariant — `TranslationOverlayView.bind` performs zero `TextLayoutPlanner.plan` /
`buildPreparedLayouts` work on the calling thread on a cache miss — is **VERIFIED** in the actual
code path, the planner/measurer confinement is sound, the cache bound is real, the drawing contract
and existing test gates are preserved, and my own runs are green (1,417 tests / 0 failures;
`assembleStandardDebug` BUILD SUCCESSFUL).

Conditional on two small follow-ups before or at the next merge of this stack:

1. **[MEDIUM] Fix the stale-`Unchanged` blank-overlay hole** in `TextLayoutCoordinator.cancelPending`.
2. **[MEDIUM test gap] Add a test for the Main-thread delivery guard's drop branch** (currently untested).

---

## 1. Bind path: zero planning work on the calling thread

**VERIFIED.** Traced against the pre-change code (`git show 5584e0a:...TranslationOverlayView.kt`,
old lines 80-82 called `plan` + `prepareLayouts` inline in `bind`).

Current path (`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt`):

- `bind()` (L113-135) delegates to `layoutCoordinator.bind(...)`. The only places
  `TextLayoutPlanner.plan` and `buildPreparedLayouts` are invoked from view code is inside the
  `plan` lambda (L79-82), which is executed ONLY inside
  `backgroundExecutor.execute { ... }` (`TextLayoutCoordinator.kt:87-99`). On a cache miss the
  view receives `Planning` and does `preparedLayouts = emptyList(); invalidate()` (L124-127).
  No planner or Path work on the calling thread. Confirmed.
- **Identical-rebind early-return**: preserved, at coordinator level — `TextLayoutCacheKey`
  equality against `boundKey` (`TextLayoutCoordinator.kt:81-82`) returns `Unchanged` with 0 planner
  calls; the view only invalidates when the SSIV instance changed (L128-133). Same cost class as
  the old `this.blocks == blocks` comparison.
- **Empty/invalid-dim binds**: `blocks.isEmpty() || width<=0 || height<=0` clears synchronously
  (`TextLayoutCoordinator.kt:75-79`, view L124-127), zero planner calls. Repeated clear-shaped
  binds are `Unchanged` no-ops — identical to the old early-return for the initial
  `(null, empty, 0, 0)` state.
- Cache hit: `Ready(prepared)` applied synchronously (L120-123) with zero planner calls; hit
  lookup (`LinkedHashMap.get`) is the only work.

The plan call itself keeps the exact old arguments (`plan(blocks, w.toFloat(), h.toFloat(), 1,
false, measurer)`) — planner semantics unchanged.

## 2. Thread safety

### Planner statelessness — VERIFIED
`object TextLayoutPlanner` (`TextLayoutPlanner.kt:540`) contains only `const val` / immutable
`val` members (incl. `SHIFT_*` ints); all mutables seen (`rescueCache`, `ordered`, `placed`,
mask-group registries at L271-279) are function-local or belong to per-call instances. The
measurer is an injected parameter. Reentrant and safe on a background thread.

### Measurer `Paint` confinement — VERIFIED
`planningMeasurer.planningPaint` (`TranslationOverlayView.kt:58-69`) is a per-view `Paint(fill)`
copy used only inside the `plan` lambda, which runs only on `Companion.planningExecutor` — a
`newSingleThreadExecutor` daemon thread (`TranslationOverlayView.kt:356-363`). All planning is
serialized on that one thread across ALL overlay instances, so no concurrent `Paint.textSize`
mutation. The Main-thread draw path continues to mutate only `fill`/`stroke`, which are never
touched by the planning measurer. Measurement parity: the copy preserves typeface + flags;
`textSize` is set before every `measureText`/`fontMetrics` use in both old and new paths, and
draw-time `fill` mutations (color/align) do not affect measurement → **STRONG INFERENCE** that
layout results are identical to the inline path (byte-level confirmation requires the device
bitmap gates — see confirm/refute below).

### Generation guard — VERIFIED, with one real hole and one benign race
- **Superseded-before-plan**: skip at task start (`TextLayoutCoordinator.kt:89`); tested.
- **Stale-before-apply**: re-check inside the Main delivery (L95-96). Since every
  bind/clear/cancel bumps `generation` on Main and the check runs on Main, a recycled/re-bound/
  detached view can never apply a stale result, and a result can never be applied twice
  (each plan produces exactly one delivery; cache-hit applies are synchronous and separate).
  **However, the drop branch of this Main-side guard is NOT covered by any test** (see finding R2).
- **[R3, LOW, benign]** The background task reads `generation` cross-thread without
  synchronization (non-volatile `Long`, written on Main). Worst case is a stale read → a
  superseded plan still runs (wasted work), then is dropped by the Main-side guard. No stale-apply
  is reachable through this race. Acceptable; `@Volatile` would formalize it.
- **[R1, MEDIUM, latent defect] Stale-`Unchanged` after `cancelPending`**:
  `cancelPending()` (`TextLayoutCoordinator.kt:110-114`) bumps `generation` but leaves `boundKey`
  set. A subsequent bind with content equal to `boundKey` returns `Unchanged` (L81-82) and never
  re-plans. Sequence: bind(A) → miss → `Planning` (view dropped its layouts to empty) →
  `onDetachedFromWindow` (`TranslationOverlayView.kt:187-194`) cancels the in-flight delivery →
  identical rebind of A → `Unchanged` → **overlay stays blank indefinitely** for that binding
  (until content/dimensions change or `clear()` resets `boundKey`).
  Likelihood in production is LOW because the common rebind-after-detach path is protected:
  `ReaderPageImageView.recycle()` (`ReaderPageImageView.kt:842-864`) calls
  `translationOverlay?.clear()` which resets `boundKey` before a recycled holder can be re-bound.
  Remaining exposure is detach-without-recycle rebinds (e.g. `onImageLoaded` firing after a
  detach during decode, viewer rebuilds). Impact when hit: translated page shows no overlay text
  until the next state change. Fix direction: `cancelPending` should also clear `boundKey`
  (or mark the next identical bind as stale).
- All production `bind`/`clear` callers are Main-thread: `ReaderPageImageView` L148/L167/L305,
  reached from view callbacks and holder code on
  `Dispatchers.Main.immediate` (`WebtoonPageHolder.kt:115`, `PagerPageHolder.kt:105`). The
  coordinator's Main-confinement contract holds. Cache `get` (bind) and `put` (delivery callback)
  are both Main-only, so the unsynchronized `LinkedHashMap` is sound.
- Cross-thread reads of `TranslationBlock` var fields by the planner while pipeline threads mutate
  them (e.g. `ChapterTranslationStore.kt:985,1079`, `RenderColorEstimator.kt:310`) widen a
  PRE-EXISTING exposure (the old inline plan raced the same writers, just for a shorter window).
  Consequence stays fail-closed: mutation changes the key's hashCode → cache miss → re-plan; a
  torn read can only cause an unnecessary re-plan, never a stale hit (barring a hash collision +
  same-instance equals, which requires the exact mutated instance — negligible). No structural
  list mutation at plan time was found (state emissions replace lists).

## 3. Cache correctness

**VERIFIED.**

- **Bound is real**: `ReaderTextLayoutCache.put` evicts via
  `while (entries.size > maxEntries)` over an access-ordered `LinkedHashMap`
  (`ReaderTextLayoutCache.kt:47-56`) — the size can never exceed 12 (`MAX_CACHED_PAGE_LAYOUTS`,
  `TranslationOverlayView.kt:341`). Shared in the companion, so the bound is global regardless of
  holder count. Pinned by `eviction keeps the entry bound strict` (maxEntries=2) and the
  LRU-recency test.
- **Key discrimination**: `TextLayoutCacheKey` = (blocks, pageWidth, pageHeight) with data-class
  equals; `TranslationBlock` is a data class (`PageTranslation.kt:294-359`) so blocks compare by
  current field content — fresh-but-equal lists hit, any content/count/dimension change misses.
  All covered by tests (width, height, content, count).
- **No stale hits from mutable blocks**: post-store mutation of a block changes the key's live
  hashCode → lookup misses → re-plan (fail-closed). Residual theoretical hole needs hash collision
  + same-instance equality (see §2) — negligible.
- **No leaks**: cache values are `List<PreparedOverlayLayout>` = `BlockLayout` (geometry + strings
  + a `TranslationBlock` ref, `TextLayoutPlanner.kt:120-160`) + `Path` — no views, bitmaps,
  contexts. Ceiling ~12 pages of layout data (well under 1 MB).
- **In-flight growth**: there is no in-flight set; the single-thread executor queue holds small
  closures. Superseded tasks return in O(1) before calling the planner, so a rapid-scroll backlog
  drains immediately; queue memory is bounded by bind rate and is transient.
- **Minor**: two overlay instances binding equal content concurrently can both plan (each has its
  own coordinator; neither's delivery has landed before the other's bind) — duplicate work,
  serialized, bounded. LOW.
- **Minor**: on `RejectedExecutionException` the bind degrades to a permanently-empty overlay for
  that bind (view shows empty until a rebind). Unreachable in practice (app-lifetime executor
  never rejects); LOW.

## 4. Drawing contract and existing gates

**VERIFIED statically; device run prohibited.**

- The draw path (`onDraw` → `drawLayout` / `drawPositionedLayout` / `drawVerticalLayout`) is
  byte-for-byte untouched by `953cdae` (diff inspection). `prepareLayouts` was renamed to
  `buildPreparedLayouts` and made pure (returns the list instead of assigning); its body is
  unchanged, so prepared output for identical inputs is identical. `bindLayoutsForTest` now
  assigns its return — same end state as before.
- **Planner never-drop contract**: `buildPreparedLayouts` retains the degraded-clip fallbacks
  (component path → cell → legacy clip, `TranslationOverlayView.kt:145-175`); unchanged.
- **Stale-visual policy deviation is intentional and safe**: on `Planning` the view drops prior
  layouts immediately rather than drawing the previous page's text over a different page; prior
  visuals are kept only on the provably-identical `Unchanged` path. This matches T920's
  never-show-foreign-text requirement; the trade (first bind appears a few frames later instead
  of blocking) is the intended Phase 3 behavior and is documented by the implementer.
- **Gates from `0365c62` / `f4d8a0b`** are androidTest classes
  (`app/src/androidTest/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView
  {InstrumentedTest,LifecycleInstrumentedTest,RenderingInstrumentedTest}.kt`). Verified they use
  ONLY `bindLayoutsForTest` / `drawLayoutsForTest` / `clear()` — none call production `bind()` —
  so their semantics are preserved and they remain valid. They require a device; per the Director
  ban I did NOT run them (they are not `connectedAndroidTest`-runnable in this session). Static
  seam-compatibility is the evidence of record.

## 5. Test quality (commit `562b4aa`)

- **Would the tests fail on a synchronous implementation?** YES for the core property:
  `bind on a miss performs NO planning work on the calling thread`
  (`TextLayoutCoordinatorTest.kt:97-113`) asserts `planCalls == 0` before the background queue
  runs — a synchronous coordinator fails immediately. The eviction-bound and key-discrimination
  cache tests likewise fail on an unbounded/content-blind map. The manual dual-queue harness
  faithfully simulates production ordering (caller → planner → main).
- **[R2, MEDIUM test gap] The Main-side delivery drop branch is untested.** Every stale/cancel
  test (`stale planning result...`, `clear cancels...`, `cancelPending drops...`) exercises only
  the background-side pre-plan skip; no test reaches
  `mainExecutor.execute { if (bindGeneration != generation) return }` with a mismatched
  generation. Removing that re-check would keep all 21 new tests green while opening the last
  line of defense against stale applies. Refute-by-test: `bind(A); background.runAll();
  cancelPending(); main.runAll(); assert applied empty`.
- **View wiring is unpinned by JVM tests**: no JVM test references `TranslationOverlayView`
  (repo-wide grep), so a future regression that reintroduces inline planning in the view's
  `bind` would not fail any automated test — only review/r'^$', inspection catches it. Acceptable
  for a JVM-only slice, worth noting for the planned device validation.
- New tests are deterministic, use no sleeps/threads, and pin behavior (not implementation) at
  the coordinator level.

## 6. Regression sweep

- `TextLayoutPlanner.plan(` has exactly ONE production caller: the coordinator lambda
  (`TranslationOverlayView.kt:80`). No other Main-thread planner call sites exist
  (`PageWorkPlanner.planPage` is an unrelated batch planner). After this change, nothing plans on
  Main.
- All `TranslationOverlayView.bind` callers route through `ReaderPageImageView` (L148 onImageLoaded,
  L167 onImageLoadError, L305 setTranslationBlocks), all Main-thread (see §2). No Compose or
  background callers found repo-wide.
- No other component races the coordinator: the executor, cache, and `mainHandler` are
  companion-private to the overlay.

## 7. My verification runs

Working dir: worktree root. `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`,
`ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`. No device/adb used.

| Command | Result |
|---|---|
| `./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --tests "eu.kanade.tachiyomi.ui.reader.*"` | BUILD SUCCESSFUL — **1,417 tests / 189 classes / 0 failures / 0 errors / 0 skipped** (aggregated from `app/build/test-results/testStandardDebugUnitTest/TEST-*.xml`). Includes new `ReaderTextLayoutCacheTest` 10/10, `TextLayoutCoordinatorTest` 11/11, `ReaderTranslationOverlayBindingTest` green |
| `./gradlew.bat :app:assembleStandardDebug` | BUILD SUCCESSFUL (36s), APKs in `app/build/outputs/apk/standard/debug/` |

## Invariant audit

| Invariant | Status | Evidence |
|---|---|---|
| Thread safety | PASS (LOW notes) | Planner stateless (only const/immutable object members); measurer Paint confined to single daemon thread; all bind/cache access Main-confined (holders on `Dispatchers.Main.immediate`); generation cross-thread read benign — final guard on Main (R3 LOW) |
| Cache bounds | PASS | Strict eviction loop, 12-entry global cap, lightweight values (no bitmaps/views); pinned by tests |
| Drawing output parity | PASS (static) | Draw path untouched; `buildPreparedLayouts` == old `prepareLayouts` + return; gates use preserved seams; device confirmation pending (ban) |
| Main-thread cost | PASS | bind = key hashCode/equals + map get + invalidate; planner never on caller thread; early-return cost unchanged |
| Android 8 compat | PASS | LinkedHashMap(accessOrder), Executors, Handler, Paint copy — all ancient APIs; no new API-level deps |
| Normal-manga non-regression | PASS | Empty-block binds are identical no-ops; overlay/cache only engage for translated binds; pager path unchanged |
| Stale/duplicate apply | PASS with R1 | Generation guard prevents stale/duplicate applies on Main; R1 = latent blank-overlay hole after cancelPending+identical rebind (MEDIUM) |

## Confirm / refute steps requiring device (currently banned)

1. **Byte-identical rendering**: run the three overlay instrumented gate classes
   (`0365c62`/`f4d8a0b`) on device once unbanned; they exercise the exact production prepared-draw
   path via the preserved seams. Optionally golden-bitmap compare a page rendered at `5584e0a`
   vs `562b4aa`.
2. **R1 blank-overlay hole on device**: hard to repro deterministically; the JVM coordinator test
   sketched in §5/R1 pins it immediately without a device.
3. **Entry-jank improvement**: re-run the reader-entry timing instrumentation (commit `8a54b60`
   logs) on the 260-page chapter when device access resumes; expect the overlay-plan component of
   first-frame Main-thread time to drop to ~0 (cache miss defers it to the planner thread).

## Findings summary

| ID | Severity | Likelihood | Type | Where |
|---|---|---|---|---|
| R1 | MEDIUM | LOW | Defect (latent) | `TextLayoutCoordinator.kt:110-114` + `:81-82` — cancelPending leaves stale boundKey → identical rebind never re-plans after a cancelled in-flight plan; overlay stays blank |
| R2 | MEDIUM | — | Test gap | `TextLayoutCoordinatorTest.kt` — Main-side delivery drop branch never exercised |
| R3 | LOW | LOW | Design limitation | `TextLayoutCoordinator.kt:89` — unsynchronized cross-thread `generation` read; wasted plan at worst |
| R4 | LOW | — | Dead code | `TextLayoutCoordinator.kt:65` — `isCleared` unused |
| R5 | LOW | LOW | Design limitation | Duplicate planning when two views bind equal content concurrently; rejected-execution leaves empty overlay for that bind |

R1 and R2 are the conditions; both are small, local, and testable on the JVM.

---

## Condition resolution verification (re-review of `fe7b3a6` + `0611603`, 2026-09-03)

**Verdict for the whole slice: APPROVE.** Both MEDIUM conditions are closed by direct diff
inspection and my own re-runs. No device interaction (ban honored).

### R1 (blank overlay after cancelPending) — CLOSED by `fe7b3a6`

`cancelPending()` now does `generation++; boundKey = null`
(`TextLayoutCoordinator.kt:108-117`). Traced against the original hole sequence
(review §2, R1):

- bind(A) miss → Planning (view shows empty) → detach → cancelPending → identical rebind:
  `boundKey` is now null, so the key-equality short-circuit cannot fire → re-plan
  (`Planning`). Hole closed.
- Rebind-after-cancel → cache-hit path: if a PREVIOUS delivery had already landed and cached
  the key, the rebind is a synchronous `Ready` hit with zero planner work — pinned by the new
  test `cancelPending after a successful apply still rebinds identically via the cache`
  (`planCalls` stays 1).
- No new race: `boundKey` remains written only on Main (bind/clear/cancelPending; all
  production callers Main-confirmed in the original review). `@Volatile` on `generation`
  formalizes the planner-thread read (original R3, LOW) without semantic change — the
  authoritative check is still the Main-side apply re-check.
- LRU bound untouched; the new "dropped delivery must not populate the cache" assertion
  strengthens the invariant that only generation-current results enter the cache (no phantom
  entries from dropped deliveries).
- Blast radius: the only behavior change after a cancel is that the next identical bind
  re-plans or cache-hits instead of lying `Unchanged`; genuine double-binds (no cancel) still
  short-circuit. The renamed legacy test (`cancelPending drops an in-flight result`) honestly
  drops the now-false "without changing bound state" clause.
- Same commit also removes the dead `isCleared` property (original R4).

### R2 (untested Main-thread apply-side drop branch) — CLOSED by `0611603`

`delivery arriving after cancelPending is discarded on the main thread` arranges
bind → `background.runAll()` (plan completes, delivery QUEUED) → `cancelPending` →
`main.runAll()`. Because planning already ran, the background-side pre-plan skip cannot fire;
only the apply-side `if (bindGeneration != generation) return@execute` can drop the result.
Mutation sanity check: with that re-check removed, the delivery would run and trip BOTH
assertions (`applied.size` would be 1; `cache.get(K_A)` would return "A" — the cache-must-stay-
empty assertion is a second, independent tripwire). The reported mutation failure is credible
and the test genuinely targets the apply-side branch.

Supporting pins: `identical rebind after cancelPending re-plans instead of staying blank`
fails on pre-`fe7b3a6` code (would return `Unchanged`, not `Planning`, `planCalls` 1 not 2) —
it pins the R1 fix itself.

### Re-runs (this session, worktree root, JAVA_HOME=Android Studio JBR)

| Command | Result |
|---|---|
| `./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.rendering.TextLayoutCoordinatorTest" --tests "eu.kanade.translation.rendering.ReaderTextLayoutCacheTest" --tests "eu.kanade.tachiyomi.ui.reader.viewer.ReaderTranslationOverlayBindingTest"` | BUILD SUCCESSFUL — Coordinator 14/14 (11 + 3 new), Cache 10/10, Binding 3/3; all three new cancel-pending tests present in the XML results |

R3 (`@Volatile`) and R4 (dead `isCleared`) were resolved as free riders in `fe7b3a6`. R5
(duplicate planning across views, rejected-execution empty state) remains LOW/accepted, as
before. Device-gated confirmations (byte-identical rendering via the `0365c62`/`f4d8a0b` gates,
entry-timing evidence) remain deferred until the Director re-authorizes device access — they do
not block approval since the seams are statically verified intact.
