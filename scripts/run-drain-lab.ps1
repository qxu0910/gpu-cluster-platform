param([switch]$EnableDrain)
. "$PSScriptRoot/common.ps1"
& "$PSScriptRoot/init-drain-lab-env.ps1"
foreach($file in @('kubeconfig-drain-lab-worker','truststore.p12','harbor.crt')) {
    if(!(Test-Path -LiteralPath (Join-Path $root ".local/$file"))) { throw "Missing .local/$file" }
}
$maven=Join-Path $root '.tools/apache-maven-3.9.11/bin/mvn.cmd'
if(!(Test-Path -LiteralPath $maven)) { $maven='mvn' }
Push-Location $root
try { & $maven -B -DskipTests package; Assert-NativeSuccess 'Build application' } finally { Pop-Location }
$env:NODE_DRAIN_ENABLED=if($EnableDrain) { 'true' } else { 'false' }
$compose=Join-Path $root 'deploy/compose-drain-lab.yml'
$environment=Join-Path $root '.local/drain-lab.env'
docker compose --env-file $environment -f $compose up -d --build
Assert-NativeSuccess 'Start isolated drain lab services'
$ready=$false
for($attempt=0;$attempt -lt 120;$attempt++) {
    try {
        $health=Invoke-RestMethod 'http://127.0.0.1:18082/actuator/health' -TimeoutSec 5
        if($health.status -eq 'UP') { $ready=$true; break }
    } catch { }
    Start-Sleep -Seconds 1
}
if(!$ready) { throw 'Drain lab management API did not become healthy within 120 checks' }
Write-Output "Isolated drain lab API is healthy on 127.0.0.1:18082; drain experiment enabled=$($EnableDrain.IsPresent)."
