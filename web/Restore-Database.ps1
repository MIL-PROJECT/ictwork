param(
    [string]$MySql = 'mysql.exe',
    [ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$DbHost = '127.0.0.1',
    [ValidateRange(1,65535)][int]$Port = 3306,
    [ValidatePattern('^[A-Za-z0-9_]+$')][string]$Database = 'omnis',
    [ValidatePattern('^[A-Za-z0-9_]+$')][string]$User = 'omnis',
    [Security.SecureString]$Password
)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath "$PSScriptRoot/database/manifest.json" -Raw | ConvertFrom-Json
$sqlFile = Join-Path $PSScriptRoot 'database/final-db.sql'
if ((Get-FileHash -LiteralPath $sqlFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.sql.sha256) { throw 'SQL SHA-256 mismatch; nothing imported.' }
$secure = $Password
if (!$secure) { $secure = Read-Host "MySQL password for $User (not the OMNIS login password)" -AsSecureString }
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $clientPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
$clientArgs = "--no-defaults --protocol=TCP --host=$DbHost --port=$Port --user=$User --database=$Database --default-character-set=utf8mb4 --batch --skip-column-names"
function Invoke-Client([string]$Text, [string]$InputFile) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $MySql
    $info.Arguments = $clientArgs
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['MYSQL_PWD'] = $clientPassword
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    $reader = $null
    try {
        if (!$process.Start()) { throw 'Could not start MySQL client.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if ($InputFile) {
            $reader = [IO.File]::OpenRead($InputFile)
            if (!$reader.CopyToAsync($process.StandardInput.BaseStream).Wait(120000)) { $process.Kill(); throw 'SQL input timed out; preserve this target database before retrying.' }
        } else {
            $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
            $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        }
        $process.StandardInput.Close()
        if (!$process.WaitForExit(120000)) { $process.Kill(); throw 'MySQL command timed out; preserve this DB and investigate before retrying.' }
        if ($process.ExitCode -ne 0) { throw "MySQL failed: $($stderr.GetAwaiter().GetResult())" }
        return $stdout.GetAwaiter().GetResult().Trim()
    } finally { if ($reader) { $reader.Dispose() }; $process.Dispose() }
}
try {
    $count = Invoke-Client -Text "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database';"
    if ($count -ne '0') { throw 'Target database is not empty. Refusing to overwrite existing data. Choose a new empty database.' }
    Write-Host 'Importing the preserved OMNIS dataset into the empty target database...'
    $null = Invoke-Client -InputFile $sqlFile
    foreach ($entry in $manifest.tables.PSObject.Properties) {
        if ($entry.Name -notmatch '^[A-Za-z0-9_]+$') { throw 'Invalid table name in manifest.' }
        $actual = Invoke-Client -Text ('SELECT COUNT(*) FROM `' + $entry.Name + '`;')
        if ($actual -ne [string]$entry.Value.count) { throw "Imported row count mismatch: $($entry.Name)" }
    }
    Write-Host "PASS: $($manifest.tables.PSObject.Properties.Name.Count) table counts verified. Existing OMNIS login passwords are preserved."
} finally { $clientPassword = $null; $secure.Dispose() }
