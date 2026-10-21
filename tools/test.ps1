param(
    [string]$Sdk = ""
)

# Runs the plain-Java unit tests (no JUnit) of the snake algorithms:
#   app/src/test/java/**  ->  javac  ->  java com.dsh.snake.PathPilotTest
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $Sdk) { $Sdk = Join-Path $root ".android-sdk" }

$javaHome = Join-Path $Sdk "jdk17"
$javac = Join-Path $javaHome "bin\javac.exe"
$java = Join-Path $javaHome "bin\java.exe"
if (-not (Test-Path $javac)) {
    throw "Missing JDK: $javac (run build.ps1 once to download it, or pass -Sdk)"
}

$main = Join-Path $root "app\src\main\java"
$test = Join-Path $root "app\src\test\java"
$out = Join-Path $root "build\test-classes"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$files = @(
    (Join-Path $main "com\dsh\snake\PathPilot.java"),
    (Join-Path $main "com\dsh\snake\AutoPilot.java")
) + @(Get-ChildItem (Join-Path $test "com\dsh\snake") -Filter *.java | ForEach-Object FullName)

& $javac -encoding UTF-8 -source 11 -target 11 -d $out @files
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

& $java -cp $out com.dsh.snake.PathPilotTest
$code = $LASTEXITCODE
if ($code -ne 0) { throw "tests failed ($code)" }
Write-Host "All unit tests passed."
