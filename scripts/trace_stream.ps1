param(
    [string]$Package = "app.kanade.tachiyomi.vibe.debug",
    [string]$Device = "192.168.100.223:46123",
    [string]$OutFile = ""
)

if (-not $OutFile) {
    $OutFile = "trace_capture_$(Get-Date -Format 'yyyyMMdd_HHmmss').log"
}

# Locate adb executable: check PATH first, then standard Android SDK platform-tools location
$adbBin = "adb"
if (-not (Get-Command $adbBin -ErrorAction SilentlyContinue)) {
    $fallbackAdb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $fallbackAdb) {
        $adbBin = $fallbackAdb
    }
}

if ($Device -and $Device -match ':\d+$') {
    & $adbBin connect $Device | Out-Null
}

$deviceArgs = @()
if ($Device -and $Device.Trim() -ne "") {
    $deviceArgs = @("-s", $Device)
}

$targetDesc = if ($Device) { "$Package on $Device" } else { "$Package on default device" }
Write-Host "[*] Finding UID for $targetDesc..." -ForegroundColor Cyan

$pkgPattern = "package:" + [regex]::Escape($Package) + "(\s|$)"
$rawUid = & $adbBin @deviceArgs shell "pm list packages -U" | Select-String $pkgPattern

if (-not $rawUid) {
    Write-Error "Package '$Package' not found on device!"
    exit 1
}

$firstMatch = if ($rawUid -is [System.Array]) { $rawUid[0].Line } else { $rawUid.Line }
if (-not $firstMatch) { $firstMatch = $rawUid.ToString() }
$uid = ($firstMatch -replace '.*uid:(\d+).*', '$1').Trim()
Write-Host "[+] UID resolved: $uid. Starting noise-free streaming to $OutFile..." -ForegroundColor Green

# Ensure capture file exists with initial header so it's not empty
"[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')] [INIT] Trace capture started for $Package (UID: $uid) on $Device" | Out-File -FilePath $OutFile -Encoding utf8

$filter = "MediaProvider|DatabaseUtils|ModernMediaScanner|OplusThumbnailUtils|ColorOS|ViewRootImpl"

& $adbBin @deviceArgs logcat -c
& $adbBin @deviceArgs logcat -b main -b crash --uid=$uid -v threadtime | ForEach-Object {
    $line = $_
    if ($line -notmatch $filter) {
        if ($line -match "budgetMet=false") {
            Write-Host $line -ForegroundColor Red
        } elseif ($line -match "budgetMet=true") {
            Write-Host $line -ForegroundColor Green
        } elseif ($line -match "TachiyomiAT") {
            Write-Host $line -ForegroundColor Yellow
        } else {
            Write-Host $line
        }
        $line | Out-File -FilePath $OutFile -Append -Encoding utf8
    }
}
