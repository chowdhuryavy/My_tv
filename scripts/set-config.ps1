# Sends config\config.json (city + subscriptions) to the TV's home screen.
# config\config.json is git-ignored so your dates and city stay private.
param([switch]$NoConnect)
. "$PSScriptRoot\common.ps1"
if (-not $NoConnect) { Connect-Tv }

$cfg = Join-Path $PSScriptRoot '..\config\config.json'
if (-not (Test-Path $cfg)) {
    '{ "city": "", "subscriptions": [] }' | Out-File -Encoding utf8 $cfg
    Write-Host "Created an empty config\config.json. Add your subscriptions (see config.example.json), then run this script again."
}

$json = Get-Content $cfg -Raw -Encoding UTF8
$null = $json | ConvertFrom-Json   # fail early on invalid JSON
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($json | ConvertFrom-Json | ConvertTo-Json -Depth 5 -Compress)))

Invoke-Adb shell am start -n com.avy.tvhome/.HomeActivity --es config_b64 $b64 | Out-Null
Write-Host 'Config sent to the TV.'
