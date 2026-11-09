param(
    [string]$Sdk = $env:ANDROID_SDK,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Out = "XingceTimer.apk"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

if (-not $Sdk) { throw "Set ANDROID_SDK (or pass -Sdk C:\path\to\sdk)" }
if (-not $Jdk) { throw "Set JAVA_HOME (or pass -Jdk C:\path\to\jdk17)" }

$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"

$Bt = Join-Path $Sdk "build-tools\36.0.0"
$AndroidJar = Join-Path $Sdk "platforms\android-36\android.jar"
$Aapt2 = Join-Path $Bt "aapt2.exe"
$D8 = Join-Path $Bt "d8.bat"
$ZipAlign = Join-Path $Bt "zipalign.exe"
$ApkSigner = Join-Path $Bt "apksigner.bat"
$Javac = Join-Path $Jdk "bin\javac.exe"
$Jar = Join-Path $Jdk "bin\jar.exe"
$Keytool = Join-Path $Jdk "bin\keytool.exe"

foreach ($tool in @($Aapt2, $D8, $ZipAlign, $ApkSigner, $Javac, $Jar, $Keytool, $AndroidJar)) {
    if (-not (Test-Path $tool)) { throw "Missing build tool: $tool" }
}

Remove-Item -Recurse -Force build -ErrorAction SilentlyContinue
New-Item -ItemType Directory build | Out-Null
New-Item -ItemType Directory build\gen | Out-Null
New-Item -ItemType Directory build\classes | Out-Null
New-Item -ItemType Directory build\dex | Out-Null

Write-Host "==> aapt2 compile"
& $Aapt2 compile --dir res -o build\res.zip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "==> aapt2 link"
& $Aapt2 link -o build\base.apk -I $AndroidJar --manifest AndroidManifest.xml `
    build\res.zip --java build\gen `
    --min-sdk-version 29 --target-sdk-version 34 `
    --version-code 1 --version-name 1.0
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "==> javac"
$sources = @(Get-ChildItem -Recurse -Path src, build\gen -Filter *.java | ForEach-Object { $_.FullName })
& $Javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options `
    -classpath $AndroidJar -d build\classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "==> d8"
$classes = @(Get-ChildItem -Recurse -Path build\classes -Filter *.class | ForEach-Object { $_.FullName })
& $D8 --lib $AndroidJar --min-api 29 --output build\dex $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "==> package"
Copy-Item build\dex\classes.dex build\classes.dex -Force
& $Jar --update --file build\base.apk -C build classes.dex

Write-Host "==> zipalign"
& $ZipAlign -f 4 build\base.apk build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (-not (Test-Path debug.keystore)) {
    Write-Host "==> create debug keystore"
    & $Keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}

Write-Host "==> apksigner"
& $ApkSigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $Out build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $ApkSigner verify --print-certs $Out
Write-Host "APK ready: $Root\$Out"
