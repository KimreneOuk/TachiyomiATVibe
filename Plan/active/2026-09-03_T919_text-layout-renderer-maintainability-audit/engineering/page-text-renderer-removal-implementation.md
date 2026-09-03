# PageTextRenderer removal — implementation

## Scope completed

The unused `PageTextRenderer` and its dedicated Android instrumentation suite
were removed. The production `ComponentClipCache` was extracted unchanged into
its own rendering file so `TranslationOverlayView` retains the same bounded,
identity-checked cache contract. The pure direction suite is now named for
`TextLayoutPlanner`, its actual owner.

Only stale source, test, and documentation references were updated. Historical
T919 reports were deliberately retained unchanged.

## Preservation notes

- No planner, overlay drawing, threshold, or layout logic changed.
- `ComponentClipCache` retains its package, `internal` visibility, generic API,
  cap-before-create checks, geometry identity check, counters, and packed key.
- The removal relies on the existing test-migration commits `f4d8a0b` and
  `0365c62`; their migrated tests exercise the live `TranslationOverlayView`.

## Verification

- `rg -n -i "PageTextRenderer" app/src/main app/src/test app/src/androidTest docs`
  returned no matches (exit 1: no matches), confirming zero live source, test,
  and documentation references.
- `git diff --check` and cached-diff checks were run before commit.
- Gradle compile and Android instrumentation gates were intentionally not run
  for this slice at the Main Leader's direction. The prior Gradle bridge result
  remains unresolved; no compile or instrumentation success is claimed.

## Remaining risk

Run the documented narrow Kotlin compile, JVM cache/planner tests, and focused
overlay instrumentation classes in an environment that returns definite Gradle
results. The deletion remains readily reversible as one isolated commit.
