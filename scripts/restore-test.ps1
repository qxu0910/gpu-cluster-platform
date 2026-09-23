param([Parameter(Mandatory)][string]$BackupFile)
. "$PSScriptRoot/common.ps1"
Import-PlatformEnvironment
$backupPath = (Resolve-Path -LiteralPath $BackupFile).Path
$name = 'platform_restore_' + (Get-Date -Format 'yyyyMMddHHmmss')
docker compose --env-file "$root/.local/platform.env" -f "$root/deploy/compose.yml" exec -T postgres createdb -U platform $name
Assert-NativeSuccess 'Create isolated restore database'
docker compose --env-file "$root/.local/platform.env" -f "$root/deploy/compose.yml" cp $backupPath postgres:/tmp/restore.dump
Assert-NativeSuccess 'Copy restore archive'
docker compose --env-file "$root/.local/platform.env" -f "$root/deploy/compose.yml" exec -T postgres pg_restore -U platform -d $name --no-owner /tmp/restore.dump
Assert-NativeSuccess 'Restore isolated database'
docker compose --env-file "$root/.local/platform.env" -f "$root/deploy/compose.yml" exec -T postgres psql -U platform -d $name -c 'SELECT version,success FROM flyway_schema_history; SELECT kind,count(*) FROM resource GROUP BY kind;'
Assert-NativeSuccess 'Validate restored schema and records'
Write-Output "Restored into isolated database $name. Business database remains unchanged."
