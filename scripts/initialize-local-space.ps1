[CmdletBinding()]
param([Guid]$SpaceId = '11111111-1111-1111-1111-111111111111', [string]$SpaceName = 'Demo')
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    # Flyway creates exp_space when the application starts. Run this afterwards.
    Get-Content -Raw 'scripts/create-space.sql' | docker compose exec -T db psql -U postgres -d ledger -v ON_ERROR_STOP=1 -v "space_id=$SpaceId" -v "space_name=$SpaceName"
    if ($LASTEXITCODE -ne 0) { throw 'Space initialization failed. Start the application once to execute Flyway first.' }
    Write-Host 'Space is ready. You can now log in to the frontend.'
} finally { Pop-Location }
