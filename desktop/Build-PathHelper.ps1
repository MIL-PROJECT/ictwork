param([string]$OutputDirectory = "$PSScriptRoot/../output/installer-tools/native")
$ErrorActionPreference = 'Stop'
$vcRoot = 'C:/Program Files (x86)/Microsoft Visual Studio/2019/BuildTools/VC/Tools/MSVC'
$sdkRoot = 'C:/Program Files (x86)/Windows Kits/10'
$vc = (Get-ChildItem -LiteralPath $vcRoot -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1).FullName
$sdk = (Get-ChildItem -LiteralPath "$sdkRoot/Include" -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1).Name
if (!$vc -or !$sdk) { throw 'MSVC Build Tools and Windows 10 SDK are needed to build the path helper.' }
$out = (New-Item -ItemType Directory -Path $OutputDirectory -Force).FullName
$binary = Join-Path $out 'windows-ansi.exe'
$toolBin = Join-Path $vc 'bin/Hostx64/x64'
$previousPath = $env:PATH
try {
    $env:PATH = "$toolBin;$previousPath"
    & "$toolBin/cl.exe" /nologo /O2 /MT /W4 /WX /utf-8 "/I$vc/include" "/I$sdkRoot/Include/$sdk/ucrt" "/I$sdkRoot/Include/$sdk/shared" "/I$sdkRoot/Include/$sdk/um" "/Fe$binary" "/Fo$out/windows-ansi.obj" "$PSScriptRoot/windows-ansi.c" /link "/LIBPATH:$vc/lib/x64" "/LIBPATH:$sdkRoot/Lib/$sdk/ucrt/x64" "/LIBPATH:$sdkRoot/Lib/$sdk/um/x64" /DYNAMICBASE /NXCOMPAT
    if ($LASTEXITCODE -ne 0) { throw "Path helper build failed ($LASTEXITCODE)" }
} finally { $env:PATH = $previousPath }
Get-FileHash -LiteralPath $binary -Algorithm SHA256
