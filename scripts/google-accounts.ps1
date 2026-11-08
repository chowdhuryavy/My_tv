# Temporarily turns Google's TV launcher back on so Settings > Accounts & Profiles works
# (its content comes from that launcher), then switches back to Avy TV Home.
#
#   scripts\google-accounts.ps1          on -> opens Settings -> waits for Enter -> off
#   scripts\google-accounts.ps1 -On      just turn it on (Home button goes to Google TV)
#   scripts\google-accounts.ps1 -Off     just switch back to Avy TV Home
param([switch]$On, [switch]$Off)
. "$PSScriptRoot\common.ps1"
Connect-Tv

$launcher = 'com.google.android.apps.tv.launcherx'

function Enable-Google {
    Invoke-Adb shell pm enable $launcher | Out-Null
    Start-Sleep 2
    Invoke-Adb shell am start -a android.settings.SETTINGS | Out-Null
    Write-Host 'Google TV launcher is ON. On the TV: Settings > Accounts & Profiles now works.'
}

function Disable-Google {
    Invoke-Adb shell pm disable-user --user 0 $launcher | Out-Null
    Start-Sleep 2
    Invoke-Adb shell cmd package set-home-activity com.avy.tvhome/.HomeActivity | Out-Null
    Invoke-Adb shell input keyevent KEYCODE_HOME
    $homeApp = (Invoke-Adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -Last 1)
    Write-Host "Back to Avy TV Home ($homeApp)."
}

if ($On) { Enable-Google; return }
if ($Off) { Disable-Google; return }

Enable-Google
Read-Host 'When you are finished with Accounts & Profiles on the TV, press Enter here to switch back to Avy TV Home'
Disable-Google
