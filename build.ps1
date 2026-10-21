param(
    [string]$Sdk = "",
    [switch]$Deploy,
    [switch]$Clean
)

# Builds a signed debug APK for Snake 2D without Gradle:
#   aapt2 compile/link  ->  javac  ->  d8  ->  jar (dex into apk)  ->  zipalign  ->  apksigner
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
if (-not $Sdk) { $Sdk = Join-Path $root ".android-sdk" }

$bt = Join-Path $Sdk "build-tools\35.0.0"
$androidJar = Join-Path $Sdk "platforms\android-35\android.jar"
$src = Join-Path $root "app\src\main"
$out = Join-Path $root "build"
$apk = Join-Path $out "snake-2d.apk"

foreach ($p in @($bt, $androidJar, $src)) {
    if (-not (Test-Path $p)) { throw "Missing required path: $p" }
}
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin" } else { "" }
$javac = Join-Path $javaBin "javac.exe"
$jarTool = Join-Path $javaBin "jar.exe"
$keytool = Join-Path $javaBin "keytool.exe"

if ($Clean -and (Test-Path $out)) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out, "$out\classes", "$out\dex" | Out-Null

Write-Host "[1/6] aapt2 compile resources"
& "$bt\aapt2.exe" compile --dir "$src\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }

Write-Host "[2/6] aapt2 link -> base apk"
$unsigned = Join-Path $out "app-unsigned.apk"
& "$bt\aapt2.exe" link -o $unsigned -I $androidJar --manifest "$src\AndroidManifest.xml" `
    --min-sdk-version 24 --target-sdk-version 35 --version-code 1 --version-name "1.0" `
    --auto-add-overlay "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

Write-Host "[3/6] javac"
$javaFiles = @(Get-ChildItem "$src\java" -Recurse -Filter *.java | ForEach-Object FullName)
& $javac -encoding UTF-8 -source 11 -target 11 -classpath $androidJar `
    -d "$out\classes" @javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "[4/6] d8 -> classes.dex"
$classFiles = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName)
& "$bt\d8.bat" --lib $androidJar --min-api 24 --output "$out\dex" @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }

Write-Host "[5/6] add dex + zipalign"
Push-Location "$out\dex"
try {
    & $jarTool uf $unsigned classes.dex
    if ($LASTEXITCODE -ne 0) { throw "jar update failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}
$aligned = Join-Path $out "app-aligned.apk"
& "$bt\zipalign.exe" -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

Write-Host "[6/6] apksigner"
$ks = Join-Path $root "keystore\debug.keystore"
$pass = "android"
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $ks) | Out-Null
    & $keytool -genkeypair -keystore $ks -storepass $pass -keypass $pass `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Snake 2D Debug,O=DSH,C=VN" -noprompt
    if ($LASTEXITCODE -ne 0) { throw "keytool failed ($LASTEXITCODE)" }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass "pass:$pass" --key-pass "pass:$pass" `
    --min-sdk-version 24 --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed ($LASTEXITCODE)" }

& "$bt\zipalign.exe" -c -p 4 $apk
if ($LASTEXITCODE -ne 0) { throw "zipalign verify failed" }

Write-Host ""
Write-Host "APK ready: $apk"
Write-Host ("Size: {0:N0} KB" -f ((Get-Item $apk).Length / 1KB))

if ($Deploy) {
    Write-Host "Installing via adb..."
    adb install -r -d $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install failed ($LASTEXITCODE)" }
    adb shell am force-stop com.dsh.snake
    adb shell am start -n com.dsh.snake/.MainActivity
    Write-Host "Launched com.dsh.snake/.MainActivity"
}
