param([int]$Port = 18081)

$ErrorActionPreference = 'Stop'
$static = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../src/main/resources/static')).Path
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { throw 'Python is required to serve the standalone sample page.' }
if ($Port -lt 1 -or $Port -gt 65535) { throw 'Port must be between 1 and 65535.' }

Write-Host "Sample console: http://127.0.0.1:$Port/?mode=sample"
Write-Host 'Only static files are served. Press Ctrl+C to stop.'
& $python.Source -m http.server $Port --bind 127.0.0.1 --directory $static
