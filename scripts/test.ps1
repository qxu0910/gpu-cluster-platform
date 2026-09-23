param([switch]$Database)
. "$PSScriptRoot/common.ps1"
Import-PlatformEnvironment
if ($Database) {
    $env:RUN_DATABASE_TESTS = 'true'
    $env:DATABASE_URL = 'jdbc:postgresql://localhost:55432/platform_test'
    docker compose --env-file "$root/.local/platform.env" -f "$root/deploy/compose.yml" exec -T postgres psql -U platform -d postgres -tc "SELECT 1 FROM pg_database WHERE datname='platform_test'" | Out-Null
    Assert-NativeSuccess 'Database connection'
    $exists = docker compose --env-file "$root/.local/platform.env" -f "$root/deploy/compose.yml" exec -T postgres psql -U platform -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='platform_test'"
    if ($exists -ne '1') {
        docker compose --env-file "$root/.local/platform.env" -f "$root/deploy/compose.yml" exec -T postgres createdb -U platform platform_test
        Assert-NativeSuccess 'Create isolated test database'
    }
}
$maven = Join-Path $root '.tools/apache-maven-3.9.11/bin/mvn.cmd'
if (!(Test-Path $maven)) { $maven = 'mvn' }
Push-Location $root
try { & $maven -B verify; Assert-NativeSuccess 'Maven verification' } finally { Pop-Location }
