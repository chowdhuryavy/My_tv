# Builds launcher\out\avy-tv-home.apk without Gradle or Android Studio.
# Needs: JDK 17, and from the Android SDK: platforms\android-31 + build-tools 35.0.1.
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot

# --- locate tools
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    $jdk = Get-ChildItem 'C:\Program Files\Microsoft\jdk-17*', 'C:\Program Files\Eclipse Adoptium\jdk-17*', 'C:\Program Files\Java\jdk-17*' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jdk) { throw 'JDK 17 not found. Install it: winget install Microsoft.OpenJDK.17' }
    $env:JAVA_HOME = $jdk.FullName
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$sdk = @($env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\sdk-lite", "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path "$_\platforms") } | Select-Object -First 1
if (-not $sdk) { throw 'Android SDK not found (need platforms\android-31 and build-tools).' }
$androidJar = Get-ChildItem "$sdk\platforms" -Recurse -Filter android.jar | Select-Object -First 1 -ExpandProperty FullName
$bt = Get-ChildItem "$sdk\build-tools" -Recurse -Filter aapt2.exe | Select-Object -First 1 -ExpandProperty DirectoryName

# --- clean
$out = Join-Path $here 'out'
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

# --- compile Java
$sources = @(Get-ChildItem "$here\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 8 -nowarn -classpath $androidJar -d "$out\classes" @sources
if ($LASTEXITCODE) { throw 'javac failed' }

# --- dex
$classes = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& "$bt\d8.bat" --release --min-api 26 --lib $androidJar --output "$out\dex" @classes
if ($LASTEXITCODE) { throw 'd8 failed' }

# --- package manifest, add dex
& "$bt\aapt2.exe" link -o "$out\unsigned.apk" -I $androidJar --manifest "$here\AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 31
if ($LASTEXITCODE) { throw 'aapt2 link failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') | Out-Null
$zip.Dispose()

# --- align + sign (local keystore, never committed)
& "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }
$ks = Join-Path $here 'signing.keystore'
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias tvhome `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Avy TV Home' | Out-Null
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$out\avy-tv-home.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }

Write-Host "Built $out\avy-tv-home.apk ($([math]::Round((Get-Item "$out\avy-tv-home.apk").Length / 1KB)) KB)"
