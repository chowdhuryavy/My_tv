# OPTIONAL. Sends settings from config\config.json (currently just "city") to the TV.
# Subscriptions are managed on the TV itself and are never overwritten by this script.
# config\config.json is git-ignored so your city stays private.
param([switch]$NoConnect)
. "$PSScriptRoot\common.ps1"
if (-not $NoConnect) { Connect-Tv }

$cfg = Join-Path $PSScriptRoot '..\config\config.json'
if (-not (Test-Path $cfg)) {
    '{ "city": "" }' | Out-File -Encoding utf8 $cfg
    Write-Host 'Created config\config.json. Set "city" (or leave empty for auto-detect) and run again.'
}

$obj = Get-Content $cfg -Raw -Encoding UTF8 | ConvertFrom-Json
$send = [ordered]@{}
foreach ($p in $obj.PSObject.Properties) { if ($p.Name -ne 'subscriptions') { $send[$p.Name] = $p.Value } }
$json = $send | ConvertTo-Json -Depth 5 -Compress
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))

Invoke-Adb shell am start -n com.avy.tvhome/.HomeActivity --es config_b64 $b64 | Out-Null
Write-Host "Sent to the TV: $json"
