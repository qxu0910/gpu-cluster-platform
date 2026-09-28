. "$PSScriptRoot/acceptance-lib.ps1"
$env:KUBECONFIG="$root/.local/kubeconfig"
function Wait-NodeSnapshot([bool]$stale) {
    $deadline=(Get-Date).AddSeconds(100)
    do {
        $snapshot=Call-Api GET '/v1/nodes?limit=100' $null
        if($snapshot.items.Count -gt 0 -and $snapshot.stale -eq $stale) { return $snapshot }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    throw "Node snapshot did not converge to stale=$stale"
}
$healthy=Wait-NodeSnapshot $false
$actual=kubectl --context kind-gpu-platform get nodes -o json | ConvertFrom-Json
Assert-NativeSuccess 'Read real Kubernetes nodes'
if($healthy.total -ne $actual.items.Count) { throw 'Node count differs from Kubernetes' }
foreach($node in $actual.items) {
    $observed=$healthy.items | Where-Object name -eq $node.metadata.name
    if(!$observed -or $observed.ready -ne ($node.status.conditions | Where-Object type -eq 'Ready').status) { throw 'Node readiness differs from Kubernetes' }
    $detail=Call-Api GET "/v1/nodes/$($node.metadata.name)" $null
    if($detail.node.name -ne $node.metadata.name) { throw 'Node detail mismatch' }
}
$compose=@('compose','--env-file',"$root/.local/platform.env",'-f',"$root/deploy/compose.yml")
$worker=docker @compose ps -q worker
Assert-NativeSuccess 'Find project worker'
if(!$worker) { throw 'Project worker is not running' }
docker network disconnect kind $worker
Assert-NativeSuccess 'Disconnect project worker'
try {
    $unavailable=Wait-NodeSnapshot $true
    if($unavailable.items | Where-Object ready -ne 'Unknown') { throw 'Disconnected nodes retained a healthy status' }
    if(!$unavailable.error_code) { throw 'Dependency failure was not identified' }
} finally {
    docker network connect kind $worker
    Assert-NativeSuccess 'Restore project worker connection'
}
$recovered=Wait-NodeSnapshot $false
@{completed_at=(Get-Date).ToUniversalTime().ToString('o');result='passed';node_count=$recovered.total;checks=@('live_node_list','live_node_detail','dependency_loss_unknown','reconnection_recovery')} | ConvertTo-Json -Depth 5 | Set-Content "$root/.local/node-acceptance-result.json"
Write-Output 'Real node observation and disconnect recovery acceptance passed.'
