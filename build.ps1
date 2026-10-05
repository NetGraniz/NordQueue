param(
    [string]$ProxyPath = 'Z:\Minecraft Proxy'
)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = Join-Path $projectPath 'src\main\java'
$testSourcePath = Join-Path $projectPath 'src\test\java'
$resourcePath = Join-Path $projectPath 'src\main\resources'
$buildPath = Join-Path $projectPath 'build'
$classesPath = Join-Path $buildPath 'classes'
$testClassesPath = Join-Path $buildPath 'test-classes'
$outputPath = Join-Path $buildPath 'NordQueue-1.1.2.jar'
$velocityJar = Join-Path $ProxyPath 'velocity.jar'
$javaPath = 'C:\Program Files\Java\jdk-25\bin'

if (-not (Test-Path -LiteralPath $velocityJar)) { throw "Velocity jar not found: $velocityJar" }
New-Item -ItemType Directory -Force -Path $classesPath, $testClassesPath | Out-Null
foreach ($target in @($classesPath, $testClassesPath)) {
    $resolved = [IO.Path]::GetFullPath($target)
    $expectedRoot = [IO.Path]::GetFullPath($buildPath).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe build cleanup target: $resolved"
    }
    Get-ChildItem -LiteralPath $resolved -Force | Remove-Item -Recurse -Force
}
$sources = Get-ChildItem -LiteralPath $sourcePath -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& (Join-Path $javaPath 'javac.exe') --release 25 -encoding UTF-8 -classpath $velocityJar -d $classesPath $sources
if ($LASTEXITCODE -ne 0) { throw 'NordQueue compilation failed.' }
$testSources = Get-ChildItem -LiteralPath $testSourcePath -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& (Join-Path $javaPath 'javac.exe') --release 25 -encoding UTF-8 -classpath "$velocityJar;$classesPath" -d $testClassesPath $testSources
if ($LASTEXITCODE -ne 0) { throw 'NordQueue test compilation failed.' }
& (Join-Path $javaPath 'java.exe') -ea -classpath "$velocityJar;$classesPath;$testClassesPath" com.nordfjell.nordqueue.BanProtocolTest
if ($LASTEXITCODE -ne 0) { throw 'NordQueue tests failed.' }
& (Join-Path $javaPath 'java.exe') -ea -classpath "$velocityJar;$classesPath;$testClassesPath" com.nordfjell.nordqueue.QueueStateTest
if ($LASTEXITCODE -ne 0) { throw 'NordQueue state tests failed.' }
& (Join-Path $javaPath 'java.exe') -ea -classpath "$velocityJar;$classesPath;$testClassesPath" com.nordfjell.nordqueue.BanStorageTest
if ($LASTEXITCODE -ne 0) { throw 'NordQueue ban-storage tests failed.' }
Copy-Item -Path (Join-Path $resourcePath '*') -Destination $classesPath -Recurse -Force
if (Test-Path -LiteralPath $outputPath) { Remove-Item -LiteralPath $outputPath -Force }
Push-Location $classesPath
try {
    & (Join-Path $javaPath 'jar.exe') --create --file $outputPath .
    if ($LASTEXITCODE -ne 0) { throw 'NordQueue packaging failed.' }
} finally { Pop-Location }
Write-Output $outputPath
