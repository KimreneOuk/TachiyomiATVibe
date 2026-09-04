# Instrumented device baseline

## Outcome

The instrumented Standard Debug APK built, installed, and launched successfully on `192.168.100.223:34075`. A target-chapter timing baseline could not be collected because the device was locked with its screen off, and the exact chapter was not available through an unambiguous restored reader state. No app data was cleared and no speculative UI navigation or unlock action was attempted.

## Build

Environment was set exactly as requested:

- `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`
- `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`
- Java bin prepended to `PATH`

Command:

`./gradlew.bat :app:assembleStandardDebug`

Result: `BUILD SUCCESSFUL in 1m 41s`; 275 actionable tasks, 16 executed and 259 up-to-date. The build emitted existing D8 Kotlin-metadata rewrite warnings but completed and packaged the APK.

Selected newest ARM64 APK:

- Path: `C:\Users\User\.gemini\antigravity\worktrees\TachiyomiAT-1.16.8-dev\optimize_reader_lazy_loading\app\build\outputs\apk\standard\debug\app-standard-arm64-v8a-debug.apk`
- Size: `457,042,431` bytes
- Last write: `2026-09-03T19:32:53.7587285+07:00`

## Install and launch

ADB reported the target as connected:

- Serial: `192.168.100.223:34075`
- State: `device`
- Product/model/device: `PKG110 / PKG110 / OP5D2BL1`

Install command used `adb -s 192.168.100.223:34075 install -r -d <apk>`. Result:

```text
Performing Incremental Install
Performing Streamed Install
Success
```

Installed package inspection:

- Package: `app.kanade.tachiyomi.at.debug`
- Version code: `20`
- Version name: `0.17.1-391`
- Last update time: `2026-09-03 19:33:51`

Logcat was cleared before launch. Launch command:

`adb -s 192.168.100.223:34075 shell am start -n app.kanade.tachiyomi.at.debug/eu.kanade.tachiyomi.ui.main.MainActivity`

Result: activity start accepted; app PID was `28690`. `dumpsys activity activities` reported `MainActivity` as the resumed and focused activity.

## Reproduction limitation

Read-only device inspection showed:

- keyguard showing: `true`
- keyguard occluded: `false`
- screen state: `SCREEN_STATE_OFF`
- interactive state: `INTERACTIVE_STATE_SLEEP`
- UI hierarchy owned by `com.android.systemui` and displayed the lock screen

The launch therefore did not expose an interactable TachiyomiAT UI or an already-restored `ReaderActivity`. Opening `[Bai Asuka] En'yoku no Hana [Complete]` requires the user to unlock the device and navigate to/open that exact chapter. No timing baseline is claimed until that interaction occurs.

## Log inspection

After launch, app-PID and system buffers contained:

- `[reader_entry]` lines: none
- app `FATAL EXCEPTION`, `AndroidRuntime`, or `ANR in` lines: none
- system/crash-buffer ANR or fatal lines naming `app.kanade.tachiyomi.at.debug`: none

This confirms only that installation and locked-device launch did not crash. It does not validate reader entry latency.

## Required next validation action

With the device unlocked, clear logcat immediately before opening the exact downloaded 260-page chapter, open it from a known restored page, then capture all `[reader_entry]` lines. Repeat at least three times to obtain median and worst values for translation fetch, duplicate directory lookup, SAF listing/filtering, `isFile` cumulative time, page mapping, and `WebtoonViewer.setChapters()`.
