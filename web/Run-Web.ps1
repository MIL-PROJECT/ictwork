param([string]$JavaHome, [ValidateRange(1,65535)][int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$war = Join-Path $PSScriptRoot 'app/OMNIS.war'
$config = Join-Path $PSScriptRoot 'config/application.properties'
if (!(Test-Path -LiteralPath $war -PathType Leaf)) { throw "Missing WAR: $war. Use the generated OMNIS-Web folder." }
if (!(Test-Path -LiteralPath $config -PathType Leaf)) { throw "Copy config/application.properties.example to config/application.properties and configure MySQL first. See README.md." }
if (!$JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($JavaHome) {
    $java = Join-Path $JavaHome 'bin/java.exe'
    if (!(Test-Path -LiteralPath $java -PathType Leaf)) { throw "Java executable not found: $java" }
} else {
    $java = (Get-Command java.exe -ErrorAction Stop).Source
}
$javaArgs = @('-Duser.timezone=Asia/Seoul', '-jar', $war, '--spring.profiles.active=demo', "--server.port=$Port")
$qa = Join-Path $PSScriptRoot 'config/qa-config.yml'
if (Test-Path -LiteralPath $qa -PathType Leaf) { $javaArgs += '--spring.config.additional-location=' + ([Uri]$qa).AbsoluteUri }
Push-Location $PSScriptRoot
try {
    Write-Host "Starting OMNIS WAR. Open http://127.0.0.1:$Port/signin after startup completes. Ctrl+C stops Java."
    & $java @javaArgs
    if ($LASTEXITCODE -ne 0) { throw "Java exited with code $LASTEXITCODE" }
} finally { Pop-Location }
