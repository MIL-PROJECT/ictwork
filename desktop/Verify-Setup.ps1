param(
    [string]$Distribution = "$PSScriptRoot/../output/OMNIS-Final-Setup-20261107",
    [string]$TestRun = 'validated'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Distribution).Path
$buildEvidence = Join-Path $root 'evidence'
$evidence = (New-Item -ItemType Directory -Path (Join-Path $buildEvidence $TestRun) -Force).FullName
$installed = Join-Path $evidence '설치 시험 OMNIS'
if (Test-Path -LiteralPath $installed) { throw "Use a fresh install test directory: $installed" }
$setup = Join-Path $root 'DVD/Setup.exe'
$process = Start-Process -FilePath $setup -ArgumentList @('/S', '/TESTMODE', "/D=$installed") -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Setup failed: $($process.ExitCode)" }
$manifest = Get-Content -LiteralPath (Join-Path $buildEvidence 'payload-manifest.json') -Raw | ConvertFrom-Json
$mismatches = @($manifest | Where-Object {
    $file = Join-Path $installed $_.path
    !(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $_.sha256
})
if ($mismatches.Count) { throw "Installed payload hash mismatches: $($mismatches.path -join ', ')" }
foreach ($name in 'Uninstall.exe', 'INSTALL-KO.txt', 'omnis-final-install.ini') {
    if (!(Test-Path -LiteralPath (Join-Path $installed $name))) { throw "Missing installer output: $name" }
}
[pscustomobject]@{ exitCode = $process.ExitCode; installed = $installed; filesVerified = $manifest.Count; mismatches = $mismatches.Count } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'installation-result.json') -Encoding UTF8
Write-Output "PASS silent Setup.exe installation and $($manifest.Count) payload SHA-256 checks"
$previousProfile = $env:OMNIS_SETUP_TEST_PROFILE
try {
    $env:OMNIS_SETUP_TEST_PROFILE = '한글 사용자 프로필'
    & node "$PSScriptRoot/setup-smoke.cjs" $installed $evidence
    if ($LASTEXITCODE -ne 0) { throw "Installed app smoke failed ($LASTEXITCODE)" }
} finally { $env:OMNIS_SETUP_TEST_PROFILE = $previousProfile }
# The real uninstaller must preserve files the user added and all userData.
$profile = (Get-Content -LiteralPath (Join-Path $evidence 'installed-smoke-result.json') -Raw | ConvertFrom-Json).profile
$dataManifest = @(Get-ChildItem -LiteralPath $profile -File -Recurse | ForEach-Object {
    [pscustomobject]@{ path = $_.FullName; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
})
# Reinstall the same build while the isolated app is stopped.
$reinstall = Start-Process -FilePath $setup -ArgumentList @('/S', '/TESTMODE', "/D=$installed") -WindowStyle Hidden -Wait -PassThru
if ($reinstall.ExitCode -ne 0) { throw "Reinstall failed ($($reinstall.ExitCode))" }
$changedOnReinstall = @($dataManifest | Where-Object { !(Test-Path -LiteralPath $_.path) -or (Get-FileHash -LiteralPath $_.path -Algorithm SHA256).Hash -ne $_.sha256 })
if ($changedOnReinstall.Count) { throw 'Reinstall changed user data' }
Write-Output 'PASS reinstall preserves isolated user data'
$keep = Join-Path $installed 'USER-FILE-KEEP.txt'
'Synthetic uninstall-preservation check.' | Set-Content -LiteralPath $keep
$uninstaller = Start-Process -FilePath (Join-Path $installed 'Uninstall.exe') -ArgumentList '/S' -WindowStyle Hidden -PassThru -Wait
if ($uninstaller.ExitCode -ne 0) { throw "Uninstall failed ($($uninstaller.ExitCode))" }
$deadline = [DateTime]::UtcNow.AddSeconds(30)
while ((Test-Path -LiteralPath (Join-Path $installed 'OMNIS.exe')) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 500 }
if (Test-Path -LiteralPath (Join-Path $installed 'OMNIS.exe')) { throw 'Uninstaller did not remove OMNIS.exe' }
$remainingPayload = @($manifest | Where-Object { Test-Path -LiteralPath (Join-Path $installed $_.path) })
if ($remainingPayload.Count) { throw "Installed payload left after uninstall: $($remainingPayload.path -join ', ')" }
if (!(Test-Path -LiteralPath $keep)) { throw 'Uninstaller removed a user-added file' }
if (!(Test-Path -LiteralPath (Join-Path $profile 'service/mysql-data/ictwork'))) { throw 'Uninstaller removed userData' }
$changedData = @($dataManifest | Where-Object { !(Test-Path -LiteralPath $_.path) -or (Get-FileHash -LiteralPath $_.path -Algorithm SHA256).Hash -ne $_.sha256 })
if ($changedData.Count) { throw "User profile changed during uninstall: $($changedData.path -join ', ')" }
[pscustomobject]@{ exitCode = $uninstaller.ExitCode; removedProgram = $true; removedPayloadFiles = $manifest.Count; preservedUserAddedFile = $true; preservedData = $true; preservedProfileFiles = $dataManifest.Count } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'uninstallation-result.json') -Encoding UTF8
Write-Output 'PASS uninstall removes the program and preserves user-added files and isolated userData'
