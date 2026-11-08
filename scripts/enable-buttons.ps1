# Turns the remote-button helper (accessibility service) back on.
# This TCL TV switches accessibility OFF every time Avy TV Home is updated, and the
# toggle in the TV's settings doesn't always turn the master switch back on.
# Order matters: master switch first, then re-register the service so Android binds it.
param([switch]$NoConnect)
. "$PSScriptRoot\common.ps1"
if (-not $NoConnect) { Connect-Tv }

$svc = 'com.avy.tvhome/com.avy.tvhome.KeyService'
Invoke-Adb shell settings put secure accessibility_enabled 1
Start-Sleep 1
Invoke-Adb shell settings delete secure enabled_accessibility_services | Out-Null
Start-Sleep 2
Invoke-Adb shell settings put secure enabled_accessibility_services $svc
Start-Sleep 4

$bound = (Invoke-Adb shell dumpsys accessibility) -match 'Bound services:\{Service\[label=Avy TV Home'
if ($bound) { Write-Host 'Remote-button helper is running.' }
else { Write-Warning 'Helper not running yet. On the TV: Settings > Accessibility > Avy TV Home remote buttons > off, then on.' }
