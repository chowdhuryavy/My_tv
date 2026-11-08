# Shared helpers: locate adb and the TV.
param()

if (-not $env:TV_ADDR) { $env:TV_ADDR = '192.168.1.124:5555' }
$script:TV = $env:TV_ADDR

$script:ADB = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $script:ADB) {
    $candidates = @(
        "$env:USERPROFILE\Downloads\platform-tools-latest-windows\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "C:\platform-tools\adb.exe"
    )
    $script:ADB = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $script:ADB) { throw "adb not found. Install Android platform-tools or put adb on PATH." }

function Invoke-Adb { & $script:ADB -s $script:TV @args }

function Connect-Tv {
    $out = & $script:ADB connect $script:TV
    Write-Host $out
    $state = (& $script:ADB -s $script:TV get-state 2>$null)
    if ($state -ne 'device') { throw "TV $script:TV is not ready (state: '$state'). Accept the USB debugging prompt on the TV and retry." }
}
