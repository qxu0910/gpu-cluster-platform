. "$PSScriptRoot/acceptance-lib.ps1"
$env:KUBECONFIG="$root/.local/kubeconfig-worker-host"
if(!(Test-Path $env:KUBECONFIG)) { throw 'Run scripts/configure-local-worker-identity.ps1 first' }
function Wait-NodeState([string]$name,[bool]$expected) {
    $deadline=(Get-Date).AddSeconds(120)
    do {
        $snapshot=Call-Api GET "/v1/nodes/$name" $null
        if(!$snapshot.stale -and $snapshot.node.unschedulable -eq $expected) { return $snapshot.node }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    throw "Node $name did not converge to unschedulable=$expected"
}
$list=Call-Api GET '/v1/nodes?limit=100' $null
if($list.stale -or $list.items.Count -eq 0) { throw 'Fresh node observation required' }
$node=$list.items | Select-Object -First 1
$initial=[bool]$node.unschedulable
$target=!$initial
$action=if($target){'cordon'}else{'uncordon'}
$restoreAction=if($initial){'cordon'}else{'uncordon'}
$run=[guid]::NewGuid().ToString('N')
try {
    $request=@{expected_uid=$node.uid;expected_resource_version=$node.resource_version}
    $accepted=Call-Api POST "/v1/nodes/$($node.name)/$action" $request "node-$run"
    $replay=Call-Api POST "/v1/nodes/$($node.name)/$action" $request "node-$run"
    if($replay.operation_id -ne $accepted.operation_id) { throw 'Node idempotency replay created a second operation' }
    Wait-Operation $accepted.operation_id | Out-Null
    Wait-NodeState $node.name $target | Out-Null
    $actual=kubectl --context kind-gpu-platform get node $node.name -o json | ConvertFrom-Json
    Assert-NativeSuccess 'Read actual node after maintenance'
    if([bool]$actual.spec.unschedulable -ne $target) { throw 'Kubernetes node state differs from operation result' }
} finally {
    $actual=kubectl --context kind-gpu-platform get node $node.name -o json | ConvertFrom-Json
    Assert-NativeSuccess 'Read actual node before restoration'
    if([bool]$actual.spec.unschedulable -ne $initial) {
        try {
            $fresh=Wait-NodeState $node.name $target
            $restore=@{expected_uid=$fresh.uid;expected_resource_version=$fresh.resource_version}
            $reset=Call-Api POST "/v1/nodes/$($node.name)/$restoreAction" $restore "restore-$run"
            Wait-Operation $reset.operation_id | Out-Null
            Wait-NodeState $node.name $initial | Out-Null
        } catch {
            kubectl --context kind-gpu-platform $restoreAction $node.name | Out-Null
            Assert-NativeSuccess 'Emergency restore through the same restricted service account'
            throw
        }
    }
}
@{result='passed';node=$node.name;initial_unschedulable=$initial;checks=@('real_node_change','idempotency_replay','state_observation','restoration');completed_at=(Get-Date).ToUniversalTime().ToString('o')} |
    ConvertTo-Json -Depth 5 | Set-Content "$root/.local/node-maintenance-result.json"
Write-Output 'Real node cordon/uncordon acceptance passed and initial state restored.'
