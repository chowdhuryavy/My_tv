# Saves the TV's current setup (device info, launchers, apps) into tv-snapshot/.
# No serial numbers, MAC addresses or account data are collected.
. "$PSScriptRoot\common.ps1"
Connect-Tv

$dir = Join-Path $PSScriptRoot '..\tv-snapshot'
New-Item -ItemType Directory -Force $dir | Out-Null

function Prop($k) { (Invoke-Adb shell getprop $k).Trim() }

@"
Snapshot taken: $(Get-Date -Format 'yyyy-MM-dd HH:mm')
Brand:          $(Prop ro.product.brand)
Device:         $(Prop ro.product.device) ($(Prop ro.product.model))
Android:        $(Prop ro.build.version.release) (SDK $(Prop ro.build.version.sdk))
Build:          $(Prop ro.build.display.id)
CPU ABIs:       $(Prop ro.product.cpu.abilist)
$((Invoke-Adb shell wm size) -join "`n")
$((Invoke-Adb shell wm density) -join "`n")
$((Invoke-Adb shell cat /proc/meminfo | Select-Object -First 1))
"@ | Out-File -Encoding utf8 (Join-Path $dir 'device.txt')

$curHome = Invoke-Adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
$all  = Invoke-Adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
(@('# Current HOME activity') + $curHome +@('', '# All HOME activities') + $all) |
    Out-File -Encoding utf8 (Join-Path $dir 'launchers.txt')

(Invoke-Adb shell pm list packages -3) -replace '^package:', '' | Sort-Object |
    Out-File -Encoding utf8 (Join-Path $dir 'apps-installed.txt')

(Invoke-Adb shell pm list packages -d) -replace '^package:', '' | Sort-Object |
    Out-File -Encoding utf8 (Join-Path $dir 'apps-disabled.txt')

Write-Host "Snapshot written to $((Resolve-Path $dir).Path)"
