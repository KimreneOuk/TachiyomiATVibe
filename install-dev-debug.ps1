$ErrorActionPreference = "Stop"

$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\dev\debug\app-dev-universal-debug.apk"

if (!(Test-Path -LiteralPath $adb)) {
    throw "adb.exe not found at $adb"
}

if (!(Test-Path -LiteralPath $apk)) {
    throw "APK not found at $apk. Run .\gradlew.bat :app:assembleDevDebug first."
}

& $adb devices
& $adb install -r $apk
