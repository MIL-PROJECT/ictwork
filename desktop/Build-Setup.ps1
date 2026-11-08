param(
    [Parameter(Mandatory)][string]$BundlePath,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$MakeNsis = "$PSScriptRoot/../output/installer-tools/nsis-3.13/makensis.exe",
    [string]$VisualCppInstaller = "$PSScriptRoot/../output/installer-tools/vc_redist.x64.exe",
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$ReleaseDate = (Get-Date -Format 'yyyy-MM-dd')
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $BundlePath).Path
$compiler = (Resolve-Path -LiteralPath $MakeNsis).Path
$runtime = (Resolve-Path -LiteralPath $VisualCppInstaller).Path
$release = [DateTime]::ParseExact($ReleaseDate, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
$releaseVersion = $release.ToString('yyyy.MM.dd')
$fileVersion = "$($release.Year).$($release.Month).$($release.Day).0"
foreach ($required in 'OMNIS.exe', 'resources/app/main.cjs', 'resources/app/legacy-schema.cjs', 'resources/app/windows-ansi.exe', 'resources/ictwork.war', 'resources/java/bin/java.exe', 'resources/mysql/bin/mysqld.exe') {
    if (!(Test-Path -LiteralPath (Join-Path $source $required) -PathType Leaf)) { throw "Missing payload: $required" }
}
$signature = Get-AuthenticodeSignature -LiteralPath $runtime
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Microsoft Corporation*') {
    throw 'The Visual C++ prerequisite must have a valid Microsoft signature.'
}
$destination = (New-Item -ItemType Directory -Path $OutputDirectory -Force).FullName
$evidence = (New-Item -ItemType Directory -Path (Join-Path $destination 'evidence') -Force).FullName
$dvd = (New-Item -ItemType Directory -Path (Join-Path $destination 'DVD') -Force).FullName
$manifest = @(Get-ChildItem -LiteralPath $source -Recurse -File | Where-Object {
    $_.FullName -ne (Join-Path $source 'debug.log') -and $_.FullName -ne (Join-Path $source 'sidebar.png')
} | Sort-Object FullName | ForEach-Object {
    [pscustomobject]@{ path = $_.FullName.Substring($source.Length + 1); bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'payload-manifest.json') -Encoding UTF8
$uninstall = @($manifest | ForEach-Object { 'Delete "$INSTDIR\' + $_.path + '"' })
$directories = @(Get-ChildItem -LiteralPath $source -Directory -Recurse | Sort-Object { $_.FullName.Length } -Descending)
$uninstall += @($directories | ForEach-Object { 'RMDir "$INSTDIR\' + $_.FullName.Substring($source.Length + 1) + '"' })
$uninstall | Set-Content -LiteralPath (Join-Path $evidence 'uninstall-payload.nsh') -Encoding UTF8
$guide = @'
OMNIS Final — Windows x64 설치 안내 (__RELEASE_DATE__)

1. DVD에 있는 Setup.exe를 실행합니다. 별도 압축 해제는 필요하지 않습니다.
2. 설치 위치를 선택하고 설치합니다. 기본 위치는 현재 사용자 Programs\OMNIS Final입니다.
3. 설치 완료 화면의 OMNIS Final 실행을 선택하거나 바탕화면 바로가기로 실행합니다.
4. 첫 실행은 MySQL 시연 데이터 초기화와 Java 서버 시작 때문에 시간이 걸립니다.
5. 시연 계정: bn1-co1-admin / 암호: Local-Demo-Init!26

Windows 10/11 x64용입니다. Electron, Java 17, MySQL 8.0, 업무 프로그램이 포함됩니다.
필요한 Visual C++ 런타임도 포함되며, 설치가 필요한 PC에서만 관리자 승인을 요청합니다.
설치와 기본 업무 기능에 인터넷, Node.js, 별도 Java/MySQL 설치는 필요하지 않습니다.
AI 질의응답은 이 demo 배포 설정에서 꺼져 있습니다. Ollama와 AI 모델은 포함하지 않습니다.

사용자 데이터: %APPDATA%\OMNIS Desktop Main\service
프로그램을 제거하거나 다시 설치해도 해당 데이터는 자동 삭제하지 않습니다.
이 설치본에는 새 시연 DB를 만드는 자료가 들어 있으며, 원본 PC의 입력 자료는 포함하지 않습니다.
기존 SQLite 기반 OMNIS 1.1.12 데이터와 자동 호환·이관되지 않습니다.

데이터를 따로 옮길 때는 앱 창을 정상적으로 닫아 Java/MySQL 종료를 기다린 다음,
service 폴더 전체를 백업합니다. 대상의 기존 데이터도 먼저 백업한 뒤 복원합니다.
사용 중인 MySQL 데이터 파일을 개별 복사하거나 삭제하지 않습니다.

제거: Windows 설치된 앱의 OMNIS Final 또는 설치 폴더의 Uninstall.exe.
오류 확인: 위 service 폴더의 spring.log, mysql.log.
설치 파일은 전자서명되지 않았습니다. 실행이 차단되면 해당 PC 관리자의 허용 절차를 따릅니다.
'@
$guide = $guide.Replace('__RELEASE_DATE__', $ReleaseDate)
$guide | Set-Content -LiteralPath (Join-Path $evidence 'INSTALL-KO.txt') -Encoding UTF8
$guide | Set-Content -LiteralPath (Join-Path $dvd '설치안내.txt') -Encoding UTF8
$defines = @(
    '/INPUTCHARSET', 'UTF8', '/V3',
    "/DBUNDLE=$source", "/DOUTPUT=$dvd\Setup.exe", "/DICON=$PSScriptRoot\omnis.ico",
    "/DVCREDIST=$runtime", "/DGUIDE=$evidence\INSTALL-KO.txt", "/DREMOVEFILES=$evidence\uninstall-payload.nsh",
    "/DRELEASE_DATE=$ReleaseDate", "/DRELEASE_VERSION=$releaseVersion", "/DFILE_VERSION=$fileVersion",
    "$PSScriptRoot\installer.nsi"
)
& $compiler @defines 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'makensis.log')
if ($LASTEXITCODE -ne 0) { throw "NSIS build failed ($LASTEXITCODE)" }
$setup = Get-Item -LiteralPath (Join-Path $dvd 'Setup.exe')
$dvdBytes = (Get-ChildItem -LiteralPath $dvd -File | Measure-Object Length -Sum).Sum
$result = [ordered]@{
    setup = $setup.FullName
    setupBytes = $setup.Length
    setupMiB = [math]::Round($setup.Length / 1MB, 2)
    sha256 = (Get-FileHash -LiteralPath $setup.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    source = $source
    sourceFiles = $manifest.Count
    sourceBytes = ($manifest | Measure-Object bytes -Sum).Sum
    dvdFolderBytes = $dvdBytes
    fitsConservative700MB = ($dvdBytes -le 700000000)
    fitsSingleLayerDvdR = ($dvdBytes -le 4700000000)
    runtimeBytes = (Get-Item -LiteralPath $runtime).Length
    runtimeSha256 = (Get-FileHash -LiteralPath $runtime -Algorithm SHA256).Hash.ToLowerInvariant()
    sourceCommit = (& git -C $source rev-parse HEAD 2>$null)
    sourceTreeDirty = [bool](& git -C $source status --porcelain 2>$null)
    warSha256 = (Get-FileHash -LiteralPath (Join-Path $source 'resources/ictwork.war') -Algorithm SHA256).Hash.ToLowerInvariant()
    desktopMainSha256 = (Get-FileHash -LiteralPath (Join-Path $source 'resources/app/main.cjs') -Algorithm SHA256).Hash.ToLowerInvariant()
    createdAt = (Get-Date).ToUniversalTime().ToString('o')
    releaseDate = $ReleaseDate
    releaseVersion = $releaseVersion
}
$result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'build-result.json') -Encoding UTF8
($result.sha256 + '  Setup.exe') | Set-Content -LiteralPath (Join-Path $destination 'SHA256.txt') -Encoding ASCII
$result | ConvertTo-Json -Depth 4
