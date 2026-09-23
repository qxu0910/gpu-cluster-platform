. "$PSScriptRoot/common.ps1"
Import-PlatformEnvironment
$backup = 'platform-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.dump'
docker compose --env-file "$root/.local/platform.env" -f "$root/deploy/compose.yml" exec -T postgres pg_dump -U platform -d platform -Fc -f /tmp/platform.dump
Assert-NativeSuccess 'Database backup'
docker compose --env-file "$root/.local/platform.env" -f "$root/deploy/compose.yml" cp postgres:/tmp/platform.dump "$root/.local/$backup"
Assert-NativeSuccess 'Copy backup'
Write-Output "Backup saved to .local/$backup. Preserve ENCRYPTION_KEY separately."
