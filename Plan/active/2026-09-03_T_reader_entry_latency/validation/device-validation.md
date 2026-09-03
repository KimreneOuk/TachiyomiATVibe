# Device validation — reader entry latency (target device 192.168.100.223:34075)

## Outcome

**BLOCKED — no validation performed.** At validation start the device had already re-locked with a secure keyguard, exactly the condition the task protocol designates as a hard stop. `dumpsys trust` reported `deviceLocked=1` for user 0 (TrustManagerService only reports this when the keyguard is showing AND secure), `KeyguardServiceDelegate` reported `showing=true` / `mIsShowing=true` / `mKeyguardOccluded=false`, window focus was `NotificationShade` (lock screen owns the UI), and the display was off (`state=OFF`, `mWakefulness=Dozing`, `mScreenState=OFF`). Display brightness history shows the screen transitioned to OFF at approximately 19:16:04 device time; at observation time (20:04 device time) it was still off and locked. The Main Leader handoff stated the device was "currently awake and unlocked", but that state was stale by the time the session began. Per protocol, no wake, swipe, gesture, or credential action was attempted. **All acceptance criteria remain untested.**

## Evidence (raw, read-only observations only)

`adb devices`:
```text
192.168.100.223:34075	device
```

`dumpsys trust` (user 0 is the relevant line):
```text
 User "Owner" (id=0, flags=0x4c13) (current): trustState=UNTRUSTED, trustManaged=1, deviceLocked=1, isActiveUnlockRunning=0, strongAuthRequired=0x0
 User "system_clone" (id=10, flags=0x20000410): trustState=UNTRUSTED, trustManaged=0, deviceLocked=0, isActiveUnlockRunning=0, strongAuthRequired=0x1
```

`dumpsys window policy` (keyguard section):
```text
    mKeyguardOccluded=false mKeyguardOccludedChanged=false mPendingKeyguardOccluded=false
    KeyguardServiceDelegate
      showing=true
      deviceHasKeyguard=true
      KeyguardStateMonitor
        mIsShowing=true
```

Window focus and power:
```text
  mCurrentFocus=Window{84d8d2e u0 NotificationShade}
  mFocusedApp=ActivityRecord{34740842 u0 app.kanade.tachiyomi.at.debug/eu.kanade.tachiyomi.ui.main.MainActivity t32789}
  mWakefulness=Dozing
```

Display state (`dumpsys display`, abridged): `state=OFF`, `mScreenState=OFF`; brightness history shows the final transition at `09-03 19:16:04.199` with `state=OFF, stateReason=UNKNOWN, policy=DOZE`.

Device clock at observation: `Thu Sep 3 20:04:01 +07 2026` — i.e. the screen had been off for roughly 48 minutes when validation was attempted.

Root cause of the re-lock (read-only): `settings get system screen_off_timeout` = `120000` (2 minutes) and `settings get global stay_on_while_plugged_in` = `0`. Any unlock lapses back to secure keyguard within ~2 minutes if not kept awake.

## Build & install

Not performed. The installed package is still the stale instrumentation-only build, confirming no install attempt was made:
```text
    versionName=0.17.1-391
    lastUpdateTime=2026-09-03 19:33:51
```
(The new APK containing commits `8a54b60` + `f0d9f46` remains uninstalled at `C:\Users\User\.gemini\antigravity\worktrees\TachiyomiAT-1.16.8-dev\optimize_reader_lazy_loading\app\build\outputs\apk\standard\debug\app-standard-arm64-v8a-debug.apk`.)

## Per-entry [reader_entry] tables / First-frame evidence / Interaction checks / Crashes-ANR

None collected. Zero `[reader_entry]` lines exist for this session because the reader was never opened. No `dumpsys gfxinfo` reset was performed. No UI navigation, taps, swipes, rotation, or backgrounding was attempted. No app mutations of any kind occurred during this session (all commands were read-only `dumpsys`/`settings get`/`date`/`ip route`).

## Acceptance-criteria verdict

| Criterion (task README) | Verdict | Evidence |
|---|---|---|
| Reader entry does not wait for metadata/hydration of all 260 pages | **UNTESTED** | Device locked before session start; reader never opened |
| Active page prioritized; small eager forward window | **UNTESTED** | Not exercised |
| Remaining pages resolve correctly while scrolling | **UNTESTED** | Not exercised |
| Timing evidence identifies post-translation costs on target device | **UNTESTED** | No `[reader_entry]` capture possible |
| No crash / reader lifecycle failure in device logs under interaction | **UNTESTED** | No interaction performed |

## Deviations & limitations

- Protocol step 1 (verify unlocked) failed; per the task's explicit stop rule ("If at ANY point the device re-locks (keyguard secure), STOP and report"), the run was aborted before any mutating step.
- Nothing to restore: `screen_off_timeout` was never modified (still original `120000`). No install, no data clear, no UI input occurred.
- The prior baseline report (`instrumented-baseline.md`) hit the same lock condition; this is the second consecutive blocked attempt.

## Required next action

The Director must physically unlock the device (human entry of PIN/biometric — automation must not attempt this). Immediately after unlock, the validator should, as its first mutating action, set `screen_off_timeout` to 600000 (original value is `120000`) so the session cannot lapse again, then proceed with the planned protocol. Optionally the Director may temporarily enable "stay awake while charging/plugged" (`stay_on_while_plugged_in`) as a belt-and-braces measure. Validation should be re-dispatched the moment the unlock is confirmed, since the current timeout gives roughly a 2-minute window.
