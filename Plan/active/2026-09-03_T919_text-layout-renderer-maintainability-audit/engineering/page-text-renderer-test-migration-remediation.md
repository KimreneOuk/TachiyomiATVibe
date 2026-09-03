# PageTextRenderer removal — test migration review remediation

## Scope

This follow-up resolves the migration review's M2 and M3 findings without
changing production code or beginning `PageTextRenderer` removal.

## M2: assigned disconnected component must produce ink

`disconnectedMaskClipsToAssignedComponentOnly` now asserts positive alpha in
component 0 before proving zero alpha in the unassigned component 1. A dropped
prepared layout can no longer satisfy the test.

## M3: repeated prepared draw gate

`repeatedDrawOfOnePreparedClippedLayoutIsStableWithoutRebinding` binds one
clipped layout once, draws it sixteen additional times through the exact
production test seam, and verifies that every output is byte-identical to the
first frame and has no alpha outside the hard cell/component square.

This is a stable-output/reprepared-state regression gate, not an allocation
measurement. The code-level ownership proof is that
`TranslationOverlayView.bindLayoutsForTest` invokes `prepareLayouts` once,
whereas `drawLayoutsForTest` only iterates `preparedLayouts` and invokes
`drawLayout`; the repeated-draw test never calls bind again. No reliable
application-owned Android allocation counter/probe exists in this test suite,
so this report does **not** claim allocation-free drawing. Platform
`Canvas`/text-rendering allocations remain outside this test's observable
contract and require profiling if an allocation budget needs a numeric gate.

## Verification

- `git diff --check`: passed.
- Connected device observed with `adb devices`:
  `192.168.100.223:34075 device`.
- Attempted compilation through the bundled JBR directly, without `JAVA_HOME`:

  ```powershell
  & 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' `
    -classpath gradle\wrapper\gradle-wrapper.jar `
    org.gradle.wrapper.GradleWrapperMain :app:compileDevDebugAndroidTestKotlin `
    --console=plain --offline --no-daemon
  ```

- Attempted the three focused overlay instrumentation classes with the same
  direct-wrapper method. Both returned execution sessions were polled to
  completion by the tool bridge, but each emitted only Gradle daemon/configuration
  startup output and no task result or exit code. Therefore compilation and
  instrumentation execution remain **unverified**, not passed.

## Residual blocker

Before removal, run the Android test-source compile and focused overlay suite
in an environment that returns a definitive Gradle result. The repeated-draw
test proves output stability and bind/draw separation, but does not substitute
for an Android allocation profiler if a quantified post-bind allocation target
is required.
