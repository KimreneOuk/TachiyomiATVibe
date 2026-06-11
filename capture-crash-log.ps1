$ErrorActionPreference = "Stop"

$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$outDir = Join-Path $PSScriptRoot "logs"
$outFile = Join-Path $outDir ("crash-log-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".txt")

if (!(Test-Path -LiteralPath $adb)) {
    throw "adb.exe not found at $adb"
}

if (!(Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

& $adb devices
& $adb logcat -c

"Logcat cleared. Reproduce the crash now, then press Enter here to save the log."
Read-Host | Out-Null

& $adb logcat -d -v time > $outFile
"Saved log to $outFile"
