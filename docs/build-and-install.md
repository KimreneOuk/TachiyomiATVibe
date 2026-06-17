# Build, Run & Install Toolchain — Quick Reference

A single place documenting how to compile, build the APK, locate the tools,
and install on a device for this repo. Captured after the first build session,
where these were discovered the hard way.

> Paths below are specific to the current dev machine (Windows). If you're on a
> different machine, use the same **commands** but fix the **paths** — the section
> "Locating the tools on a new machine" explains how.

---

## 1. Prerequisites (the two things that were missing)

### JDK / JAVA_HOME

Gradle needs a JDK. The Android Studio bundled JBR works and is what this repo
builds against:

```
JAVA_HOME = C:\Program Files\Android\Android Studio\jbr
```

**Without `JAVA_HOME` set, `gradlew.bat` fails with:**
```
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

Set it per-shell before invoking gradle:
```cmd
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
```
(Or set it as a permanent system environment variable so you don't have to.)

### Android SDK (`local.properties`)

Already committed-as-generated in `local.properties`:
```
sdk.dir=C\:\\Users\\User\\AppData\\Local\\Android\\Sdk
```
If the SDK moves, update this file. `adb` lives inside it (see below).

---

## 2. Compiling (fast feedback, no APK)

This project has **two product flavors**: `standard` and `dev`. The bare task
name `compileDebugKotlin` is **ambiguous** and fails:

```
Cannot locate tasks that match ':app:compileDebugKotlin' as task
'compileDebugKotlin' is ambiguous ... Candidates are: compileDevDebugKotlin,
compileStandardDebugKotlin, ...
```

Always qualify the flavor. Kotlin-only compile (fastest, ~1–2 min incremental):

```cmd
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
```

> Note: on Windows `cmd`, use `gradlew.bat` (NOT `./gradlew` — that's the Unix
> launcher and fails with `'.' is not recognized`). `gradlew.bat` must be on the
> PATH or invoked from the repo root.

---

## 3. Building a debug APK

```cmd
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
gradlew.bat :app:assembleStandardDebug --no-daemon
```

Output APK (the `standard` debug variant):
```
app\build\outputs\apk\standard\debug\app-standard-debug.apk
```

> **Per-ABI split is enabled** in this project, so the build produces one APK
> per ABI rather than a single `app-standard-debug.apk`:
> ```
> app-standard-arm64-v8a-debug.apk      <- 64-bit ARM (most modern phones)
> app-standard-armeabi-v7a-debug.apk    <- 32-bit ARM (older phones)
> app-standard-x86_64-debug.apk         <- emulator / x86_64
> app-standard-x86-debug.apk            <- 32-bit x86 emulator
> app-standard-universal-debug.apk      <- all ABIs (largest)
> ```
> Install the one matching the device. For a modern phone (e.g. the OnePlus
> PKG110 on this machine), use `app-standard-arm64-v8a-debug.apk`. To build a
> single fat APK instead, pass `-Pabi=universal` or disable the splits in the
> gradle config.

(For the `dev` flavor, substitute `dev` for `standard` in both the task and the
output path: `app\build\outputs\apk\dev\debug\app-dev-...apk`.)

A full clean build can take several minutes; incremental builds are much faster.

---

## 4. adb — where it lives

`adb` is NOT on the system PATH by default. It ships inside the Android SDK:

```
C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

Easiest pattern: prepend it to PATH for the current shell, then use bare `adb`:

```cmd
set "PATH=%PATH%;C:\Users\User\AppData\Local\Android\Sdk\platform-tools"
adb devices
```

Or invoke it by full path each time:
```cmd
"C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
```

### Connecting to a phone over Wi-Fi (wireless debugging)

If the phone has **Wireless debugging** enabled (Settings → Developer options →
Wireless debugging, with a port shown), pair once if needed, then connect:

```cmd
set "PATH=%PATH%;C:\Users\User\AppData\Local\Android\Sdk\platform-tools"
adb connect 192.168.100.207:37625
adb devices -l          # confirm it lists as "device"
```

If `adb connect` says `failed to authenticate` or `device offline`, the phone
is showing an "Allow USB debugging?" dialog — accept it on the device. If the
port changed (the wireless-debugging port rotates after a reboot), read the new
port off the phone's Wireless debugging screen and reconnect.

Known-good example from this machine:
```
> adb devices -l
List of devices attached
192.168.100.207:37625  device product:PKG110 model:PKG110 device:OP5D2BL1
```

---

## 5. Installing the APK on the connected device

The package name of the `standard` **debug** build is
`app.kanade.tachiyomi.at.debug` (NOT `eu.kanade.tachiyomi` — the applicationId
differs per flavor/build-type). Install the APK matching the device ABI:

```cmd
set "PATH=%PATH%;C:\Users\User\AppData\Local\Android\Sdk\platform-tools"
adb -s 192.168.100.207:37625 install -r app\build\outputs\apk\standard\debug\app-standard-arm64-v8a-debug.apk
```

- `-s <serial>` targets the specific device when several are attached. Omit it
  if only one device is connected.
- `-r` reinstalls (replaces) the existing app and keeps its data.

If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature
mismatch vs. a Play Store / different build install), uninstall first using the
**debug package name**:
```cmd
adb -s 192.168.100.207:37625 uninstall app.kanade.tachiyomi.at.debug
```
then reinstall. To find the exact package name on any device:
```cmd
adb -s 192.168.100.207:37625 shell pm list packages | findstr /I tachiyomi
```

To launch after installing (note: the launcher Activity class differs per fork,
so the `monkey` invocation — which resolves the launcher activity automatically —
is more reliable than `am start -n <pkg>/<activity>`):
```cmd
adb -s 192.168.100.207:37625 shell monkey -p app.kanade.tachiyomi.at.debug -c android.intent.category.LAUNCHER 1
```
A successful launch prints `Events injected: 1`.

---

## 6. All-in-one: build + install on the Wi-Fi phone

Copy-paste from the repo root:

```cmd
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%PATH%;C:\Users\User\AppData\Local\Android\Sdk\platform-tools"
gradlew.bat :app:assembleStandardDebug --no-daemon && ^
adb connect 192.168.100.207:37625 && ^
adb -s 192.168.100.207:37625 install -r app\build\outputs\apk\standard\debug\app-standard-arm64-v8a-debug.apk && ^
adb -s 192.168.100.207:37625 shell monkey -p app.kanade.tachiyomi.at.debug -c android.intent.category.LAUNCHER 1
```

---

## 7. Locating the tools on a new machine

If the paths above don't exist, find them:

| Tool | How to find it |
|------|----------------|
| JDK / JAVA_HOME | Android Studio → File → Project Structure → SDK Location → "JDK location". Or look for `jbr` under the Android Studio install dir. |
| Android SDK | `local.properties` → `sdk.dir=...`. Or Android Studio → Settings → Languages & Frameworks → Android SDK. |
| adb | Always `<sdk.dir>\platform-tools\adb.exe`. |
| gradlew | Repo root: `gradlew.bat` (Windows), `gradlew` (Unix). |

Confirm each works:
```cmd
"%JAVA_HOME%\bin\java" -version
"<sdk>\platform-tools\adb.exe" version
gradlew.bat --version
```

---

## 8. Reading build output

Always check the **tail** of the log for the real result — the wrapper's exit
code can be misleading when chained with `&`:

```cmd
gradlew.bat :app:compileStandardDebugKotlin --no-daemon > build.log 2>&1
```
Then look for `BUILD SUCCESSFUL` or `BUILD FAILED`, and grep for `e: file` /
`error:` to find Kotlin/compiler errors:
```cmd
findstr /R /C:"e: file" /C:"error:" /C:"BUILD" build.log
```
```
The error format `e: file:///.../File.kt:LINE:COL <message>` is the canonical
Kotlin compiler diagnostic — the line:col points straight at the problem.
```

---

## 9. Common pitfalls (all hit during the first session)

| Symptom | Cause | Fix |
|---------|-------|-----|
| `'.' is not recognized` | ran `./gradlew` on Windows | use `gradlew.bat` |
| `JAVA_HOME is not set` | JDK env var missing | `set "JAVA_HOME=...\Android Studio\jbr"` |
| `compileDebugKotlin` is ambiguous | project has `standard` + `dev` flavors | qualify: `compileStandardDebugKotlin` |
| `'gradlew.bat' is not recognized` (exit 9009) | not on PATH, wrong cwd | run from repo root, or `.\gradlew.bat` |
| `adb` not found | platform-tools not on PATH | `set "PATH=%PATH%;...\Sdk\platform-tools"` |
| `device offline` / `unauthorized` | phone prompt pending / port rotated | accept dialog on phone; re-read port & reconnect |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | signature clash with existing install | `adb uninstall <package>` first |
