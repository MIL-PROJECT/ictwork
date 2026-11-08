param(
    [Parameter(Mandatory)][string]$ElectronDist,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$MySqlHome,
    [Parameter(Mandatory)][string]$War,
    [Parameter(Mandatory)][string]$Destination,
    [string]$ResourceEditor,
    [string]$PathHelper = "$PSScriptRoot/../output/installer-tools/native/windows-ansi.exe"
)
$ErrorActionPreference = 'Stop'
$electron = (Resolve-Path -LiteralPath $ElectronDist).Path
$jdk = (Resolve-Path -LiteralPath $JavaHome).Path
$mysql = (Resolve-Path -LiteralPath $MySqlHome).Path
$warFile = (Resolve-Path -LiteralPath $War).Path
if (!(Test-Path -LiteralPath $PathHelper)) { & "$PSScriptRoot/Build-PathHelper.ps1" -OutputDirectory (Split-Path -Parent $PathHelper) }
$helperFile = (Resolve-Path -LiteralPath $PathHelper).Path
foreach ($required in "$electron/electron.exe", "$jdk/bin/java.exe", "$mysql/bin/mysqld.exe", "$mysql/bin/mysqladmin.exe") {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing runtime file: $required" }
}
if (Test-Path -LiteralPath $Destination) { throw "Destination already exists: $Destination" }
$out = (New-Item -ItemType Directory -Path $Destination).FullName
Get-ChildItem -LiteralPath $electron -Force | Where-Object Name -ne 'debug.log' | Copy-Item -Destination $out -Recurse
Rename-Item -LiteralPath "$out/electron.exe" -NewName 'OMNIS.exe'
if ($ResourceEditor) {
    $editor = (Resolve-Path -LiteralPath $ResourceEditor).Path
    & $editor "$out/OMNIS.exe" --set-icon "$PSScriptRoot/omnis.ico" --set-version-string ProductName OMNIS --set-version-string FileDescription OMNIS
    if ($LASTEXITCODE -ne 0) { throw "Could not set Windows executable resources ($LASTEXITCODE)" }
}
$resources = Join-Path $out 'resources'
if (Test-Path -LiteralPath "$resources/app") { throw 'Use an unmodified Electron distribution.' }
New-Item -ItemType Directory -Path "$resources/app" | Out-Null
foreach ($name in 'package.json', 'main.cjs', 'legacy-schema.cjs', 'omnis.ico') {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination "$resources/app/$name"
}
Copy-Item -LiteralPath $helperFile -Destination "$resources/app/windows-ansi.exe"
Copy-Item -LiteralPath $jdk -Destination "$resources/java" -Recurse
Copy-Item -LiteralPath $mysql -Destination "$resources/mysql" -Recurse
Copy-Item -LiteralPath $warFile -Destination "$resources/ictwork.war"
Copy-Item -LiteralPath "$PSScriptRoot/README.md" -Destination "$out/README.md"
Get-FileHash -LiteralPath "$out/OMNIS.exe", "$resources/ictwork.war"
