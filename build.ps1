param([string]$MavenCommand = 'mvn')
$ErrorActionPreference = 'Stop'
if (-not (Get-Command $MavenCommand -ErrorAction SilentlyContinue)) { throw 'Maven 3.9+ and JDK 25 must be available; see BUILDING.md.' }
& $MavenCommand -B -ntp -f (Join-Path $PSScriptRoot 'pom.xml') clean verify
if ($LASTEXITCODE -ne 0) { throw 'NordQueue build failed.' }
$jar = Join-Path $PSScriptRoot 'target/NordQueue-2.0.0.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Expected release JAR missing.' }
Get-FileHash -LiteralPath $jar -Algorithm SHA256
