param(
    [Parameter(Mandatory)][string]$Destination,
    [string]$War = "$PSScriptRoot/../src/backend/build/libs/ictwork-0.0.1.war",
    [string]$Dataset = "$PSScriptRoot/../output/OMNIS-Release-20261109/bootstrap"
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath "$PSScriptRoot/..").Path
$warFile = (Resolve-Path -LiteralPath $War).Path
$datasetFolder = (Resolve-Path -LiteralPath $Dataset).Path
$manifest = Get-Content -LiteralPath "$datasetFolder/manifest.json" -Raw | ConvertFrom-Json
foreach ($pair in @(@($warFile, $manifest.warSha256), @("$datasetFolder/final-db.sql", $manifest.sql.sha256), @("$datasetFolder/qa-config.yml", $manifest.qaConfig.sha256))) {
    if ((Get-FileHash -LiteralPath $pair[0] -Algorithm SHA256).Hash.ToLowerInvariant() -ne $pair[1]) { throw "Dataset/WAR hash mismatch: $($pair[0])" }
}
if (Test-Path -LiteralPath $Destination) { throw 'Use a new destination; existing files are never replaced.' }
$out = (New-Item -ItemType Directory -Path $Destination).FullName
foreach ($folder in 'app','database','config','source') { New-Item -ItemType Directory -Path "$out/$folder" | Out-Null }
Copy-Item -LiteralPath $warFile -Destination "$out/app/OMNIS.war"
foreach ($file in 'final-db.sql','manifest.json') { Copy-Item -LiteralPath "$datasetFolder/$file" -Destination "$out/database/$file" }
Copy-Item -LiteralPath "$datasetFolder/qa-config.yml" -Destination "$out/config/qa-config.yml"
Copy-Item -LiteralPath "$PSScriptRoot/application.properties.example" -Destination "$out/config/application.properties.example"
Copy-Item -LiteralPath "$PSScriptRoot/create-database.example.sql" -Destination "$out/database/create-database.example.sql"
foreach ($file in 'Run-Web.ps1','Run-Web.cmd','Run-Web.sh','Restore-Database.ps1','README.md') { Copy-Item -LiteralPath "$PSScriptRoot/$file" -Destination "$out/$file" }
$sourceZip = Join-Path $out 'OMNIS-source.zip'
& git -C $root archive --format=zip "--output=$sourceZip" HEAD
if ($LASTEXITCODE -ne 0) { throw 'git archive failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($sourceZip, "$out/source")
# Include these distribution helpers while testing an uncommitted packaging change.
New-Item -ItemType Directory -Path "$out/source/web" -Force | Out-Null
Get-ChildItem -LiteralPath $PSScriptRoot -File | Copy-Item -Destination "$out/source/web" -Force
$commit = (& git -C $root rev-parse HEAD).Trim()
$dirty = [bool](& git -C $root status --porcelain)
$files = @(Get-ChildItem -LiteralPath $out -Recurse -File | ForEach-Object {
    [ordered]@{ path=$_.FullName.Substring($out.Length+1).Replace('\','/'); bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
[ordered]@{ format=1; createdAt=[DateTime]::UtcNow.ToString('o'); sourceCommit=$commit; sourceTreeDirty=$dirty; warSha256=$manifest.warSha256; datasetSqlSha256=$manifest.sql.sha256; javaRequired=17; mysqlRequired='8.0'; files=$files } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$out/package-manifest.json" -Encoding UTF8
$files | ForEach-Object { "$($_.sha256)  $($_.path)" } | Set-Content -LiteralPath "$out/SHA256SUMS.txt" -Encoding ASCII
Write-Output "Web distribution ready: $out"
