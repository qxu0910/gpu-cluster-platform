. "$PSScriptRoot/common.ps1"
$ErrorActionPreference='Stop'
$record=Join-Path $root '.local/drain-acceptance-result.json'
if(!(Test-Path -LiteralPath $record)) { throw 'No successful drain acceptance record to clean up' }
$run=Get-Content -LiteralPath $record | ConvertFrom-Json
$base='http://127.0.0.1:18082'
function Wait-Operation([string]$id) {
    $deadline=(Get-Date).AddMinutes(5)
    do {
        $operation=Invoke-RestMethod "$base/v1/operations/$id"
        if($operation.status -eq 'succeeded') { return }
        if($operation.status -eq 'failed') { throw "Cleanup operation failed: $($operation.error.code)" }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    throw "Cleanup operation did not converge: $id"
}
$node=Invoke-RestMethod "$base/v1/nodes/$($run.target)"
if($node.stale) { throw 'Node observation is stale; inspect before cleanup' }
if($node.node.unschedulable) {
    $body=@{expected_uid=$node.node.uid;expected_resource_version=$node.node.resource_version} | ConvertTo-Json -Compress
    $operation=Invoke-RestMethod -Method Post -Uri "$base/v1/nodes/$($run.target)/uncordon" -ContentType 'application/json' -Headers @{'Idempotency-Key'="uncordon-$($run.run)"} -Body $body
    Wait-Operation $operation.operation_id
}
$workload=Invoke-RestMethod "$base/v1/workloads/$($run.workload)"
$operation=Invoke-RestMethod -Method Delete -Uri "$base/v1/workloads/$($run.workload)" -Headers @{'Idempotency-Key'="delete-$($run.run)";'If-Match'='"'+$workload.version+'"'}
Wait-Operation $operation.operation_id
$remaining=kubectl --kubeconfig (Join-Path $root '.local/kubeconfig-drain-lab') --context kind-gpu-platform-drain -n platform-workloads get pods -l "platform.cluster.io/workload=$($run.workload)" -o json | ConvertFrom-Json
Assert-NativeSuccess 'Check drain fixture deletion'
if($remaining.items.Count -ne 0) { throw 'Fixture Pods remain after deletion' }
$nodeState=kubectl --kubeconfig (Join-Path $root '.local/kubeconfig-drain-lab') --context kind-gpu-platform-drain get node $run.target -o json | ConvertFrom-Json
Assert-NativeSuccess 'Check target scheduling restoration'
if($nodeState.spec.unschedulable) { throw 'Target node remains cordoned' }
Write-Output "Acceptance workload deleted and $($run.target) scheduling restored; evidence retained in ignored .local/."
