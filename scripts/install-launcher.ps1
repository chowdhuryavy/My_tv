# Installs Avy TV Home, sends your config, makes it the home screen and
# turns off the stock Google TV launcher (the source of the ads).
# Undo any time with scripts\restore-stock-launcher.ps1
. "$PSScriptRoot\common.ps1"
Connect-Tv

$apk = Join-Path $PSScriptRoot '..\launcher\out\avy-tv-home.apk'
if (-not (Test-Path $apk)) { throw "APK not built yet. Run launcher\build.ps1 first." }

Invoke-Adb install -r $apk
if ($LASTEXITCODE) { throw 'Install failed.' }

& "$PSScriptRoot\set-config.ps1" -NoConnect

# Sanity check: our home screen must actually start before the stock one is turned off.
Invoke-Adb shell am start -W -n com.avy.tvhome/.HomeActivity | Out-Null
Start-Sleep 2
$focus = (Invoke-Adb shell dumpsys window) | Select-String 'mCurrentFocus' | Select-Object -First 1
if ("$focus" -notmatch 'com.avy.tvhome') { throw "Avy TV Home did not come to the front ($focus). Stock launcher left untouched." }

Invoke-Adb shell cmd package set-home-activity com.avy.tvhome/.HomeActivity
Invoke-Adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx

# Press Home and confirm.
Invoke-Adb shell input keyevent KEYCODE_HOME
Start-Sleep 2
Invoke-Adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -Last 1
