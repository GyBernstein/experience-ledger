[CmdletBinding()]
param(
    [switch]$DatabaseOnly,
    [int]$DatabasePort = 15432
)
$ErrorActionPreference = 'Stop'
if ($DatabasePort -lt 1 -or $DatabasePort -gt 65535) { throw 'DatabasePort must be 1..65535' }
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    if (-not (Test-Path '.env')) { Copy-Item '.env.example' '.env' }
    $env:LEDGER_LOCAL_DB_PORT = "$DatabasePort"
    $compose = @('compose', '-f', 'docker-compose.yml', '-f', 'docker-compose.local.yml')
    & docker @compose up -d --wait db
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL did not become healthy. Check Docker Desktop and port availability.' }
    # Read Compose's resolved values as data; never evaluate .env as a script.
    $raw = & docker @compose config --format json
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve Compose configuration.' }
    $config = ($raw -join "`n") | ConvertFrom-Json
    $app = $config.services.application.environment
    function Escape-Property([string]$value) {
        $value.Replace('\', '\\').Replace("`r", '\r').Replace("`n", '\n').Replace(' ', '\ ').Replace('=', '\=').Replace(':', '\:').Replace('#', '\#').Replace('!', '\!')
    }
    $values = [ordered]@{
        'spring.datasource.url' = "jdbc:postgresql://127.0.0.1:$DatabasePort/ledger"
        'spring.datasource.username' = 'ledger_app'
        'spring.datasource.password' = [string]$app.DB_PASSWORD
        'spring.flyway.url' = "jdbc:postgresql://127.0.0.1:$DatabasePort/ledger"
        'spring.flyway.user' = 'ledger_owner'
        'spring.flyway.password' = [string]$app.DB_MIGRATION_PASSWORD
        'ledger.security.principals' = [string]$app.LEDGER_PRINCIPALS
        'ledger.worker.spaces' = [string]$app.LEDGER_WORKER_SPACES
    }
    New-Item -ItemType Directory -Force '.local' | Out-Null
    $lines = @('# Generated local credentials. Do not commit or share.')
    foreach ($entry in $values.GetEnumerator()) {
        # ASCII unicode escapes make Java Properties decoding independent of OS encoding.
        $escaped = Escape-Property $entry.Value
        $ascii = -join ($escaped.ToCharArray() | ForEach-Object { if ([int]$_ -gt 127) { '\u{0:x4}' -f [int]$_ } else { [string]$_ } })
        $lines += "$($entry.Key)=$ascii"
    }
    [IO.File]::WriteAllLines((Join-Path $root '.local/application-local.properties'), $lines, [Text.Encoding]::ASCII)
    Write-Host "PostgreSQL is available on 127.0.0.1:$DatabasePort. Local configuration is ready."
    Write-Host 'IDE: activate profile local, and set the working directory to the project root.'
    Write-Host 'After the first successful app startup, run scripts/initialize-local-space.ps1.'
    if (-not $DatabaseOnly) {
        & mvn '-Dspring-boot.run.profiles=local' spring-boot:run
        if ($LASTEXITCODE -ne 0) { throw 'Application failed. Check the first database error and docs/spring-boot-4-upgrade.md.' }
    }
} finally { Pop-Location }
