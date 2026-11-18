$ErrorActionPreference = 'Stop'

$baseCompose = '.\compose.yaml'
$routingCompose = '.\compose.routing.yaml'
$expectedOsrmImage = 'ghcr.io/project-osrm/osrm-backend:v5.27.1'
$composeFiles = @('-f', $baseCompose, '-f', $routingCompose)
$dataDir = Join-Path (Get-Location) '.osrm-data'
$pbfPath = Join-Path $dataDir 'morocco-latest.osm.pbf'
$tempPbfPath = "$pbfPath.download"
$downloadUrl = 'https://download.geofabrik.de/africa/morocco-latest.osm.pbf'

if (-not (Test-Path $baseCompose)) {
    throw 'Run this script from the kulha-api project root, beside compose.yaml.'
}
if (-not (Test-Path $routingCompose)) {
    throw 'compose.routing.yaml is missing. Extract the v4 package beside compose.yaml.'
}

New-Item -ItemType Directory -Path $dataDir -Force | Out-Null

Write-Host 'Validating merged Docker Compose configuration...'
$imageList = & docker compose @composeFiles config --images
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose could not validate the compose files.' }
$imageList | ForEach-Object { Write-Host "  $_" }
$badImages = @($imageList | Where-Object { $_ -match 'osrm-backend' -and $_ -ne $expectedOsrmImage })
if ($badImages.Count -gt 0) { throw "Unexpected OSRM image reference: $($badImages -join ', ')" }

& docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running or the Docker daemon is unavailable.' }

Write-Host 'Pulling the OSRM image...'
& docker pull $expectedOsrmImage
if ($LASTEXITCODE -ne 0) { throw "Could not pull $expectedOsrmImage." }

$existingSize = 0
if (Test-Path $pbfPath) { $existingSize = (Get-Item $pbfPath).Length }
if ($existingSize -lt 180000000) {
    Write-Host 'Downloading the Morocco road extract directly to .osrm-data...'
    Remove-Item $tempPbfPath -Force -ErrorAction SilentlyContinue
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($null -ne $curl) {
        & curl.exe --fail --location --show-error --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 30 --output $tempPbfPath $downloadUrl
        if ($LASTEXITCODE -ne 0) { Remove-Item $tempPbfPath -Force -ErrorAction SilentlyContinue; throw "Morocco download failed (curl exit code $LASTEXITCODE)." }
    } else {
        try {
            Invoke-WebRequest -Uri $downloadUrl -OutFile $tempPbfPath
        } catch {
            Remove-Item $tempPbfPath -Force -ErrorAction SilentlyContinue
            throw "Morocco download failed: $($_.Exception.Message)"
        }
    }
    if (-not (Test-Path $tempPbfPath)) { throw 'Download did not create the expected PBF file.' }
    $downloadSize = (Get-Item $tempPbfPath).Length
    if ($downloadSize -lt 180000000) {
        Remove-Item $tempPbfPath -Force -ErrorAction SilentlyContinue
        throw "Downloaded file is only $downloadSize bytes. Expected a Morocco PBF extract (currently roughly 233 MB)."
    }
    Move-Item -Path $tempPbfPath -Destination $pbfPath -Force
    Write-Host "Morocco road extract downloaded: $([math]::Round($downloadSize / 1MB, 1)) MB" -ForegroundColor Green
} else {
    Write-Host "Using existing Morocco road extract: $([math]::Round($existingSize / 1MB, 1)) MB"
}

Write-Host 'Preparing the Morocco routing graph...'
& docker compose @composeFiles up --no-deps --force-recreate --abort-on-container-exit --exit-code-from osrm-prepare osrm-prepare
if ($LASTEXITCODE -ne 0) {
    & docker compose @composeFiles logs --no-color --tail 150 osrm-prepare
    throw 'OSRM preparation failed. The extraction/partition/customize logs are shown above.'
}

Write-Host 'Starting the routing server...'
& docker compose @composeFiles up -d --no-deps osrm
if ($LASTEXITCODE -ne 0) {
    & docker compose @composeFiles logs --no-color --tail 150 osrm
    throw 'Could not start the local Morocco routing server.'
}

Write-Host 'Checking OSRM at http://127.0.0.1:5000 ...'
$ready = $false
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    try {
        $response = Invoke-RestMethod -Uri 'http://127.0.0.1:5000/nearest/v1/driving/-7.5898,33.5731?number=1' -TimeoutSec 3
        if ($response.code -eq 'Ok') { $ready = $true; break }
    } catch {}
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    & docker compose @composeFiles logs --no-color --tail 150 osrm
    throw 'OSRM did not become ready. See the server logs above.'
}

Write-Host 'Local Morocco road routing is ready at http://127.0.0.1:5000.' -ForegroundColor Green
