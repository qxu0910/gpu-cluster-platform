. "$PSScriptRoot/common.ps1"
Import-PlatformEnvironment
$maven = Join-Path $root '.tools/apache-maven-3.9.11/bin/mvn.cmd'
if (!(Test-Path $maven)) { $maven = 'mvn' }
Push-Location $root
try { & $maven -B -DskipTests package; Assert-NativeSuccess 'Build application' } finally { Pop-Location }
docker compose --env-file "$root/.local/platform.env" -f "$root/deploy/compose.yml" --profile app up -d --build
Assert-NativeSuccess 'Start platform'
$ready=$false
for($attempt=0;$attempt -lt 120;$attempt++) {
    try { $health=Invoke-RestMethod 'http://localhost:18080/actuator/health' -TimeoutSec 10; if($health.status -eq 'UP') { $ready=$true; break } } catch { }
    Start-Sleep -Seconds 1
}
if(!$ready) { throw 'Management API did not become healthy within 120 checks' }
