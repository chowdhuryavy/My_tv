# Turns the remote-button helper (accessibility service) back on.
# This TCL TV switches accessibility OFF every time Avy TV Home is updated, and the
# toggle in the TV's settings doesn't always turn the master switch back on.
# Order matters: master switch first, then re-register the service so Android binds it.
# Right after an install the system is still busy, so this retries a few times.
param([switch]$NoConnect)
. "$PSScriptRoot\common.ps1"
if (-not $NoConnect) { Connect-Tv }

$svc = 'com.avy.tvhome/com.avy.tvhome.KeyService'
for ($try = 1; $try -le 4; $try++) {
    Invoke-Adb shell settings put secure accessibility_enabled 1
    Start-Sleep 1
    Invoke-Adb shell settings delete secure enabled_accessibility_services | Out-Null
    Start-Sleep 2
    Invoke-Adb shell settings put secure enabled_accessibility_services $svc
    Start-Sleep 4
    if ((Invoke-Adb shell dumpsys accessibility) -match 'Bound services:\{Service\[label=Avy TV Home') {
        Write-Host "Remote-button helper is running (attempt $try)."
        return
    }
    Start-Sleep 3
}
Write-Warning 'Helper not running. On the TV: Settings > Accessibility > Avy TV Home remote buttons > off, then on.'
