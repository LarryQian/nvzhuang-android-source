# Android APK build script (aapt2 + d8 + apksigner, no Gradle / HBuilderX).
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -AssetsDir ..\app\dist\build\h5 -VersionName 1.0.0

param(
    [string]$AssetsDir   = "",
    [string]$OutApk      = "",
    [string]$VersionName = "1.0.0",
    [int]$VersionCode    = 100,
    [string]$MirrorDir   = "",
    [string]$GrsaiKey    = ""
)

# keep going on native stderr noise; every tool exit code is checked explicitly
$ErrorActionPreference = "Continue"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot    = Split-Path -Parent $ProjectRoot

if ([string]::IsNullOrWhiteSpace($AssetsDir)) {
    $AssetsDir = Join-Path $RepoRoot "app\dist\build\h5"
}
$AssetsDir = (Resolve-Path $AssetsDir).Path

if ([string]::IsNullOrWhiteSpace($MirrorDir)) {
    $MirrorDir = Join-Path $ProjectRoot "mirror"
}
$MirrorDir = if (Test-Path $MirrorDir) { (Resolve-Path $MirrorDir).Path } else { "" }

if ([string]::IsNullOrWhiteSpace($OutApk)) {
    $OutApk = Join-Path $RepoRoot ("release\nvzhuang-android-" + $VersionName + ".apk")
}

$Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$BuildTools = (Get-ChildItem (Join-Path $Sdk "build-tools") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$Platform   = (Get-ChildItem (Join-Path $Sdk "platforms") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$AndroidJar = Join-Path $Platform "android.jar"

$Aapt2     = Join-Path $BuildTools "aapt2.exe"
$Zipalign  = Join-Path $BuildTools "zipalign.exe"
$Apksigner = Join-Path $BuildTools "apksigner.bat"
$D8        = Join-Path $BuildTools "d8.bat"

foreach ($tool in @($Aapt2, $Zipalign, $Apksigner, $D8, $AndroidJar)) {
    if (-not (Test-Path $tool)) { throw ("missing build tool: " + $tool) }
}

$Work        = Join-Path $ProjectRoot "build"
$ClassesDir  = Join-Path $Work "classes"
$DexDir      = Join-Path $Work "dex"
$GenDir      = Join-Path $Work "gen"
$CompiledRes = Join-Path $Work "res.zip"
$BaseApk     = Join-Path $Work "base-unsigned.apk"
$WithDexApk  = Join-Path $Work "with-dex.apk"
$AlignedApk  = Join-Path $Work "aligned.apk"
$Keystore    = Join-Path $ProjectRoot "keystore\nvzhuang.jks"
$KsPass      = "nvzhuang"
$KsAlias     = "nvzhuang"

Write-Host "== clean work dir ==" -ForegroundColor Cyan
if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
New-Item -ItemType Directory -Force -Path $ClassesDir, $DexDir | Out-Null
Write-Host ("assets: " + $AssetsDir)

Write-Host "== aapt2 compile ==" -ForegroundColor Cyan
# 内置 GRSAI 令牌以资源形式注入（空字符串 = 只能靠设置中心里自己填）
$grsaiRes = Join-Path $ProjectRoot "res\values\grsai.xml"
$escapedKey = ($GrsaiKey -replace '&', '&amp;' -replace '<', '&lt;' -replace '>', '&gt;')
$xml = "<?xml version=`"1.0`" encoding=`"utf-8`"?>`r`n<resources>`r`n    <string name=`"grsai_key`" translatable=`"false`">$escapedKey</string>`r`n</resources>`r`n"
[System.IO.File]::WriteAllText($grsaiRes, $xml, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("builtin grsai key: " + $(if ($GrsaiKey) { $GrsaiKey.Substring(0, [Math]::Min(7, $GrsaiKey.Length)) + "..." } else { "(empty)" }))
& $Aapt2 compile --dir (Join-Path $ProjectRoot "res") -o $CompiledRes
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== aapt2 link ==" -ForegroundColor Cyan
# NOTE: assets are NOT passed via -A here. aapt2 on Windows writes asset entry names
# with backslashes ("assets\\foo.js"), which the Android AssetManager cannot resolve.
# They are injected below with forward slashes instead.
& $Aapt2 link `
    -o $BaseApk `
    -I $AndroidJar `
    --manifest (Join-Path $ProjectRoot "AndroidManifest.xml") `
    --version-code $VersionCode `
    --version-name $VersionName `
    --replace-version `
    --java $GenDir `
    --auto-add-overlay `
    $CompiledRes
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== javac ==" -ForegroundColor Cyan
$JavaFiles = @()
$JavaFiles += @(Get-ChildItem (Join-Path $ProjectRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$JavaFiles += @(Get-ChildItem $GenDir -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
& javac -source 8 -target 8 -encoding UTF-8 -nowarn `
    -classpath $AndroidJar `
    -bootclasspath $AndroidJar `
    -d $ClassesDir $JavaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== d8 ==" -ForegroundColor Cyan
$ClassFiles = @(Get-ChildItem $ClassesDir -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& $D8 --lib $AndroidJar --min-api 21 --release --output $DexDir $ClassFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== inject classes.dex + assets ==" -ForegroundColor Cyan
Copy-Item $BaseApk $WithDexApk -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($WithDexApk, 'Update')
$old = $zip.GetEntry("classes.dex")
if ($old) { $old.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Join-Path $DexDir "classes.dex"), "classes.dex",
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

$assetRoots = @()
$assetRoots += (Resolve-Path $AssetsDir).Path.TrimEnd('\', '/')
if (-not [string]::IsNullOrWhiteSpace($MirrorDir)) {
    $assetRoots += (Resolve-Path $MirrorDir).Path.TrimEnd('\', '/')
}

$injected = 0
foreach ($root in $assetRoots) {
    foreach ($file in @(Get-ChildItem $root -Recurse -File)) {
        $rel = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
        $entryName = "assets/" + $rel
        $existing = $zip.GetEntry($entryName)
        if ($existing) { $existing.Delete() }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $file.FullName, $entryName,
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        $injected++
    }
}
$zip.Dispose()
Write-Host ("assets injected: " + $injected + " files (bundle + local image mirror)")

Write-Host "== zipalign ==" -ForegroundColor Cyan
& $Zipalign -f -p 4 $WithDexApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (-not (Test-Path $Keystore)) {
    Write-Host "== create keystore ==" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path (Split-Path $Keystore) | Out-Null
    & keytool -genkeypair -v -keystore $Keystore -alias $KsAlias `
        -keyalg RSA -keysize 2048 -validity 36500 `
        -storepass $KsPass -keypass $KsPass `
        -dname "CN=nvzhuang, OU=android, O=paipiange, L=Beijing, ST=Beijing, C=CN" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "== apksigner sign ==" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path (Split-Path $OutApk) | Out-Null
if (Test-Path $OutApk) { Remove-Item -Force $OutApk }
& $Apksigner sign --ks $Keystore --ks-key-alias $KsAlias `
    --ks-pass ("pass:" + $KsPass) --key-pass ("pass:" + $KsPass) `
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --out $OutApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& $Apksigner verify --print-certs $OutApk
if ($LASTEXITCODE -ne 0) { throw "apk verify failed" }

$size = (Get-Item $OutApk).Length / 1MB
Write-Host ("== done: " + $OutApk + "  (" + [string]::Format("{0:N2}", $size) + " MB) ==") -ForegroundColor Green
Write-Output $OutApk
