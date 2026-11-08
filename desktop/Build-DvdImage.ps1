param(
    [Parameter(Mandatory)][string]$Directory,
    [Parameter(Mandatory)][string]$Output,
    [ValidatePattern('^[A-Z0-9_]{1,32}$')][string]$Label = 'OMNIS'
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $Directory).Path
$target = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $target) { throw 'Use a new ISO output path.' }
$files = @(Get-ChildItem -LiteralPath $source -File -Recurse)
if (($files | Measure-Object Length -Sum).Sum -gt 4700000000) { throw 'Files exceed a single-layer DVD capacity.' }
if (Get-ChildItem -LiteralPath $source -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'External links cannot be packaged.' }
# Microsoft IMAPI2FS builds a single-session ISO9660 + Joliet data image.
# https://learn.microsoft.com/windows/win32/imapi/burning-a-disc
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
public static class OmnisIsoStream {
    public static void Save(object value, string path) {
        var stream = (IStream)value;
        var count = Marshal.AllocCoTaskMem(4);
        try {
            stream.Seek(0, 0, IntPtr.Zero);
            using (var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
                byte[] buffer = new byte[1024 * 1024];
                for (;;) {
                    stream.Read(buffer, buffer.Length, count);
                    int length = Marshal.ReadInt32(count);
                    if (length == 0) break;
                    file.Write(buffer, 0, length);
                }
                file.Flush(true);
            }
        } finally { Marshal.FreeCoTaskMem(count); }
    }
}
'@
$image = $null
$result = $null
$stream = $null
try {
    $image = New-Object -ComObject IMAPI2FS.MsftFileSystemImage
    $image.FileSystemsToCreate = 3
    $image.VolumeName = $Label
    $image.FreeMediaBlocks = [int][math]::Floor(4700000000 / 2048)
    $image.Root.AddTree($source, $false)
    $result = $image.CreateResultImage()
    $stream = $result.ImageStream
    [OmnisIsoStream]::Save($stream, $target)
    if ((Get-Item -LiteralPath $target).Length -ne [long]$result.TotalBlocks * $result.BlockSize) { throw 'ISO stream size mismatch.' }
    if ((Get-Item -LiteralPath $target).Length -gt 4700000000) { throw 'ISO exceeds DVD capacity.' }
    [pscustomobject]@{ iso=$target; bytes=(Get-Item -LiteralPath $target).Length; sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant(); label=$Label; files=$files.Count } | ConvertTo-Json
} finally {
    foreach ($item in @($stream,$result,$image)) { if ($null -ne $item -and [Runtime.InteropServices.Marshal]::IsComObject($item)) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($item) } }
}
