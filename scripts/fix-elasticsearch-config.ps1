$ErrorActionPreference = 'Stop'

$apiRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $apiRoot '.env'
$examplePath = Join-Path $apiRoot '.env.example'

if (-not (Test-Path $envPath)) {
    if (-not (Test-Path $examplePath)) {
        throw "Neither .env nor .env.example exists in $apiRoot"
    }
    Copy-Item $examplePath $envPath
    Write-Host 'Created .env from .env.example.' -ForegroundColor Yellow
}

$settings = [ordered]@{
    'ELASTICSEARCH_OFFERS_INDEX' = 'kulha-offers-v3'
    'ELASTICSEARCH_CONNECT_TIMEOUT_MS' = '2000'
    'ELASTICSEARCH_READ_TIMEOUT_MS' = '10000'
    'ELASTICSEARCH_REFRESH_AFTER_WRITE' = 'false'
}

$lines = [System.Collections.Generic.List[string]]::new()
foreach ($line in [System.IO.File]::ReadAllLines($envPath)) {
    $lines.Add($line)
}

foreach ($entry in $settings.GetEnumerator()) {
    $pattern = '^\s*' + [regex]::Escape($entry.Key) + '\s*='
    $firstMatch = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $pattern) {
            if ($firstMatch -lt 0) {
                $firstMatch = $i
                $lines[$i] = "$($entry.Key)=$($entry.Value)"
            } else {
                $lines.RemoveAt($i)
                $i--
            }
        }
    }
    if ($firstMatch -lt 0) {
        $lines.Add("$($entry.Key)=$($entry.Value)")
    }
}

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllLines($envPath, $lines, $utf8NoBom)

Write-Host 'Elasticsearch configuration updated:' -ForegroundColor Green
Write-Host '  ELASTICSEARCH_OFFERS_INDEX=kulha-offers-v3'
Write-Host '  ELASTICSEARCH_CONNECT_TIMEOUT_MS=2000'
Write-Host '  ELASTICSEARCH_READ_TIMEOUT_MS=10000'
Write-Host '  ELASTICSEARCH_REFRESH_AFTER_WRITE=false'
Write-Host ''
Write-Host 'Restart the API after running this script. The old v2 index is not deleted.' -ForegroundColor Yellow
