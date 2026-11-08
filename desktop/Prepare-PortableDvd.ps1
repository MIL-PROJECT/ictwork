param(
    [string]$BundlePath = "$PSScriptRoot/../output/OMNIS-Release-20261109/OMNIS-Windows-x64",
    [string]$RuntimePath = "$PSScriptRoot/../output/installer-tools/vc_redist.x64.exe",
    [string]$ExpectedPayloadManifest = "$PSScriptRoot/../output/OMNIS-Setup-20261109/evidence/payload-manifest.json",
    [string]$OutputDirectory = "$PSScriptRoot/../output/OMNIS-Portable-DVD-20261109"
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $BundlePath).Path
$runtime = (Resolve-Path -LiteralPath $RuntimePath).Path
$expected = @(Get-Content -LiteralPath $ExpectedPayloadManifest -Raw | ConvertFrom-Json)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Destination already exists; use a new folder. Nothing was replaced.' }
$signature = Get-AuthenticodeSignature -LiteralPath $runtime
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch '(^|,\s*)O=Microsoft Corporation(,|$)') { throw 'A valid Microsoft-signed VC++ runtime is required.' }
$original = @(Get-ChildItem -LiteralPath $source -Recurse -File -Force)
if ($original.Count -ne $expected.Count) { throw 'Source file count differs from the verified installer payload.' }
foreach ($entry in $expected) {
    $file = [IO.Path]::GetFullPath((Join-Path $source $entry.path))
    if (!$file.StartsWith($source + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Payload manifest contains a path outside the bundle.' }
    if (!(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item -LiteralPath $file).Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "Source payload differs from verified release: $($entry.path)" }
}
$out = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
Copy-Item -LiteralPath $source -Destination (Join-Path $out 'OMNIS') -Recurse
Copy-Item -LiteralPath $runtime -Destination (Join-Path $out 'vc_redist.x64.exe')
foreach ($guide in Get-ChildItem -LiteralPath "$PSScriptRoot/portable-dvd" -File) {
    if ($guide.Extension -eq '.txt') {
        # BOM makes Korean instructions readable in older Windows Notepad too.
        $text = [IO.File]::ReadAllText($guide.FullName, [Text.Encoding]::UTF8)
        [IO.File]::WriteAllText((Join-Path $out $guide.Name), $text.Replace("`r`n", "`n").Replace("`n", "`r`n"), [Text.UTF8Encoding]::new($true))
    } else { Copy-Item -LiteralPath $guide.FullName -Destination $out }
}
foreach ($entry in $expected) {
    $file = Join-Path "$out/OMNIS" $entry.path
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "Copied payload verification failed: $($entry.path)" }
}
if (@(Get-ChildItem -LiteralPath "$out/OMNIS" -Recurse -File -Force).Count -ne $expected.Count) { throw 'Copied payload count mismatch.' }
$runtimeHash = (Get-FileHash -LiteralPath $runtime -Algorithm SHA256).Hash.ToLowerInvariant()
if ((Get-FileHash -LiteralPath "$out/vc_redist.x64.exe" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $runtimeHash) { throw 'Copied VC++ runtime hash mismatch.' }
$files = @(Get-ChildItem -LiteralPath $out -Recurse -File -Force | Sort-Object FullName | ForEach-Object {
    [ordered]@{ path=$_.FullName.Substring($out.Length+1).Replace('\','/'); bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
[ordered]@{
    format=1; createdAt=[DateTime]::UtcNow.ToString('o'); product='OMNIS'; distribution='Folder-copy DVD';
    payloadFiles=$expected.Count; allPayloadHashesMatch=$true; vcRuntimeSignature='Valid Microsoft Corporation'; vcRuntimeSha256=$runtimeHash;
    sourceProfileModified=$false; physicalDvdBurnTested=$false; otherPhysicalPcTested=$false; files=$files
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$out/DVD-INFO.json" -Encoding UTF8
$files | ForEach-Object { "$($_.sha256)  $($_.path)" } | Set-Content -LiteralPath "$out/SHA256SUMS.txt" -Encoding UTF8
$total = (Get-ChildItem -LiteralPath $out -Recurse -File -Force | Measure-Object Length -Sum).Sum
if ($total -ge 4700000000) { throw 'Contents exceed a single-layer 4.7GB DVD.' }
[ordered]@{ folder=$out; bytes=$total; decimalGB=[Math]::Round($total/1000000000,2); payloadFiles=$expected.Count; hashesVerified=$true; fitsSingleLayerDvd=$true } | ConvertTo-Json
