$ErrorActionPreference = "Stop"
$backendRoot = Split-Path -Parent $PSScriptRoot
Set-Location $backendRoot

$envFile = Join-Path $backendRoot ".env"
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^(DB_PORT|DB_NAME|DB_USER)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"'), 'Process')
        }
    }
}

$dbName = if ($env:DB_NAME) { $env:DB_NAME } else { "kulha" }
$dbUser = if ($env:DB_USER) { $env:DB_USER } else { "postgres" }
$dbPort = if ($env:DB_PORT) { $env:DB_PORT } else { "5433" }

if ($dbName -ne "kulha" -and $dbName -notmatch '(?i)(^|[_-])(dev|test|local)([_-]|$)') {
    throw "Refusing to seed database '$dbName'. Use a local kulha/dev/test/local database."
}

$psql = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psql) {
    throw "PostgreSQL psql client was not found on PATH. Install the PostgreSQL command-line tools first."
}

$previousPgOptions = $env:PGOPTIONS
$previousClientEncoding = $env:PGCLIENTENCODING
$env:PGOPTIONS = '-c kulha.allow_map_demo_seed=true'
$env:PGCLIENTENCODING = 'UTF8'
try {
    & $psql.Source -h 127.0.0.1 -p $dbPort -U $dbUser -d $dbName -W -v ON_ERROR_STOP=1 -f (Join-Path $PSScriptRoot 'seed-map-demo.sql')
    if ($LASTEXITCODE -ne 0) { throw "Map demo seed failed with exit code $LASTEXITCODE." }
    & $psql.Source -h 127.0.0.1 -p $dbPort -U $dbUser -d $dbName -W -v ON_ERROR_STOP=1 -f (Join-Path $PSScriptRoot 'verify-map-demo.sql')
    if ($LASTEXITCODE -ne 0) { throw "Map demo verification failed with exit code $LASTEXITCODE." }
} finally {
    if ($null -eq $previousPgOptions) { Remove-Item Env:PGOPTIONS -ErrorAction SilentlyContinue }
    else { $env:PGOPTIONS = $previousPgOptions }
    if ($null -eq $previousClientEncoding) { Remove-Item Env:PGCLIENTENCODING -ErrorAction SilentlyContinue }
    else { $env:PGCLIENTENCODING = $previousClientEncoding }
}
