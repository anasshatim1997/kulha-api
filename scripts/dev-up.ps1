# Starts the local stack (PostgreSQL + Mailpit) and waits until PostgreSQL is ready.
# Run from the project root:  .\scripts\dev-up.ps1
# If PowerShell blocks the script:  powershell -ExecutionPolicy Bypass -File .\scripts\dev-up.ps1

$ErrorActionPreference = "Stop"

# 1. Keep WSL alive. Start the keep-alive only if it is not already running.
$keepAlive = Get-CimInstance Win32_Process -Filter "Name='wsl.exe'" |
    Where-Object { $_.CommandLine -like "*sleep infinity*" }
if (-not $keepAlive) {
    Start-Process wsl -ArgumentList "-e", "sleep", "infinity" -WindowStyle Hidden
    Write-Host "WSL keep-alive started."
}

# 2. Start PostgreSQL and Mailpit.
wsl docker compose up -d

# 3. Start the dedicated local Elasticsearch node.
wsl docker compose -f infra/elasticsearch/docker-compose.yml up -d

# 4. Wait for PostgreSQL to be healthy (maximum 90 seconds).
$deadline = (Get-Date).AddSeconds(90)
do {
    $status = (wsl docker inspect -f "{{.State.Health.Status}}" kulha-api-db-1 2>$null)
    if ($status -eq "healthy") { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

if ($status -ne "healthy") {
    Write-Host "PostgreSQL is not healthy (status: $status)." -ForegroundColor Red
    Write-Host "Run: wsl docker compose logs db --tail=40"
    exit 1
}

# 5. Check that the ports answer from Windows, which is where Maven and IntelliJ run.
$port = if ($env:DB_PORT) { $env:DB_PORT } else { 5433 }
if (-not (Test-NetConnection -ComputerName localhost -Port $port -InformationLevel Quiet)) {
    Write-Host "PostgreSQL is healthy in Docker but port $port is not reachable from Windows." -ForegroundColor Red
    exit 1
}

$esDeadline = (Get-Date).AddSeconds(90)
do {
    $esStatus = (wsl docker inspect -f "{{.State.Health.Status}}" kulha-elasticsearch 2>$null)
    if ($esStatus -eq "healthy") { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $esDeadline)

if ($esStatus -ne "healthy") {
    Write-Host "Elasticsearch is not healthy (status: $esStatus)." -ForegroundColor Red
    Write-Host "Run: wsl docker compose -f infra/elasticsearch/docker-compose.yml logs --tail=40"
    exit 1
}

if (-not (Test-NetConnection -ComputerName localhost -Port 9200 -InformationLevel Quiet)) {
    Write-Host "Elasticsearch is healthy in Docker but port 9200 is not reachable from Windows." -ForegroundColor Red
    exit 1
}

Write-Host "Ready: PostgreSQL is healthy on localhost:$port. Elasticsearch: http://localhost:9200. Mailpit UI: http://localhost:8025" -ForegroundColor Green
