param([string]$MavenCommand = 'mvn', [string]$QueueProject = '')
$ErrorActionPreference = 'Stop'
if (-not (Get-Command $MavenCommand -ErrorAction SilentlyContinue)) { throw 'Maven 3.9+ and JDK 25 must be available; see BUILDING.md.' }
if ($QueueProject) {
    $queuePom = Join-Path (Resolve-Path -LiteralPath $QueueProject).Path 'pom.xml'
    if (-not (Test-Path -LiteralPath $queuePom -PathType Leaf)) { throw 'QueueProject must contain the NordQueue Maven project.' }
    & $MavenCommand -B -ntp -f $queuePom install
    if ($LASTEXITCODE -ne 0) { throw 'NordQueue dependency build failed.' }
}
& $MavenCommand -B -ntp -f (Join-Path $PSScriptRoot 'pom.xml') clean verify
if ($LASTEXITCODE -ne 0) { throw 'NordQueue build or tests failed.' }
$jar = Join-Path $PSScriptRoot 'target/NordQueue-1.1.3.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Expected release JAR missing.' }
Get-FileHash -LiteralPath $jar -Algorithm SHA256
