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

# 2. Start the services.
wsl docker compose up -d

# 3. Wait for PostgreSQL to be healthy (maximum 90 seconds).
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

# 4. Check that the port answers from Windows, which is where Maven and IntelliJ run.
$port = if ($env:DB_PORT) { $env:DB_PORT } else { 5433 }
if (-not (Test-NetConnection -ComputerName localhost -Port $port -InformationLevel Quiet)) {
    Write-Host "PostgreSQL is healthy in Docker but port $port is not reachable from Windows." -ForegroundColor Red
    exit 1
}

Write-Host "Ready: PostgreSQL is healthy on localhost:$port. Mailpit UI: http://localhost:8025" -ForegroundColor Green
