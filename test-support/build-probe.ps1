param([string]$LocalProxyPath)
$ErrorActionPreference='Stop'
if(-not $LocalProxyPath.StartsWith('C:\Users\artyo\Documents\Codex\nordqueue-test-',[StringComparison]::OrdinalIgnoreCase)){throw 'Local fixture required'}
$support=Split-Path -Parent $MyInvocation.MyCommand.Path
$classes=Join-Path $support 'build\probe-classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$java='C:\Program Files\Java\jdk-25\bin'
$jar=Join-Path (Split-Path -Parent $support) 'build\NordQueue-1.1.2.jar'
& (Join-Path $java 'javac.exe') --release 25 -encoding UTF-8 -classpath ((Join-Path $LocalProxyPath 'velocity.jar')+';'+$jar) -d $classes (Join-Path $support 'probe\QueueTestProbe.java')
if($LASTEXITCODE -ne 0){throw 'Probe compilation failed'}
Copy-Item -LiteralPath (Join-Path $support 'probe\velocity-plugin.json') -Destination $classes -Force
$probeJar=Join-Path $support 'build\NordQueueTestProbe.jar'
& (Join-Path $java 'jar.exe') --create --file $probeJar -C $classes .
if($LASTEXITCODE -ne 0){throw 'Probe packaging failed'}
Copy-Item -LiteralPath $probeJar -Destination (Join-Path $LocalProxyPath 'plugins\NordQueueTestProbe.jar') -Force
