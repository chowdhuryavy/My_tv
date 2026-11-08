# Puts your profile picture in the top-right avatar of Avy TV Home.
# Android doesn't let other apps read your Google profile photo, so copy it here once.
# Usage:  scripts\set-avatar.ps1 C:\path\to\photo.jpg      (jpg, png or webp)
#         scripts\set-avatar.ps1 -Remove                   (go back to the initial letter)
param([string]$Path, [switch]$Remove)
. "$PSScriptRoot\common.ps1"
Connect-Tv

$dir = '/sdcard/Android/data/com.avy.tvhome/files'
Invoke-Adb shell mkdir -p $dir
Invoke-Adb shell rm -f "$dir/avatar.jpg" "$dir/avatar.png" "$dir/avatar.webp"

if (-not $Remove) {
    if (-not $Path -or -not (Test-Path $Path)) { throw 'Give the path to a photo, e.g. scripts\set-avatar.ps1 C:\Users\me\Pictures\me.jpg' }
    $ext = [IO.Path]::GetExtension($Path).TrimStart('.').ToLower()
    if ($ext -eq 'jpeg') { $ext = 'jpg' }
    if ($ext -notin 'jpg', 'png', 'webp') { throw 'Use a .jpg, .png or .webp image.' }
    Invoke-Adb push $Path "$dir/avatar.$ext"
}

# Bring the home screen to the front so it reloads the avatar.
Invoke-Adb shell input keyevent KEYCODE_HOME
Write-Host $(if ($Remove) { 'Avatar photo removed.' } else { 'Avatar photo set.' })
