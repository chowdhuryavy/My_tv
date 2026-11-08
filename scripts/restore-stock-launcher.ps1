# Brings back the original Google TV home screen.
param([switch]$UninstallCustom)
. "$PSScriptRoot\common.ps1"
Connect-Tv

Invoke-Adb shell pm enable com.google.android.apps.tv.launcherx
Invoke-Adb shell pm enable com.google.android.tungsten.setupwraith
Invoke-Adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
if ($UninstallCustom) { Invoke-Adb uninstall com.avy.tvhome }
Invoke-Adb shell input keyevent KEYCODE_HOME
Write-Host 'Stock Google TV launcher restored.'
