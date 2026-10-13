. "$PSScriptRoot/common.ps1"
$ErrorActionPreference='Stop'
$base='http://127.0.0.1:18082'
$kubeconfig=Join-Path $root '.local/kubeconfig-drain-lab'
$context='kind-gpu-platform-drain'
$target='gpu-platform-drain-worker'
$alternate='gpu-platform-drain-worker2'
$run=[guid]::NewGuid().ToString('N')
$capabilities=Invoke-RestMethod "$base/v1/capabilities"
if(!$capabilities.node_drain) { throw 'Drain is disabled in the isolated lab; run scripts/run-drain-lab.ps1 -EnableDrain first' }

function Call-Api([string]$method,[string]$path,$body,[string]$key='',[hashtable]$extra=@{}) {
    $headers=$extra.Clone()
    if($key) { $headers['Idempotency-Key']=$key }
    $parameters=@{Method=$method;Uri="$base$path";Headers=$headers;TimeoutSec=20}
    if($null -ne $body) { $parameters.Body=ConvertTo-Json $body -Depth 20 -Compress; $parameters.ContentType='application/json' }
    Invoke-RestMethod @parameters
}
function Wait-Status([string]$id,[string]$expected,[string]$errorCode='') {
    $deadline=(Get-Date).AddMinutes(5)
    do {
        $operation=Call-Api GET "/v1/operations/$id" $null
        if($operation.status -eq $expected -and (!$errorCode -or $operation.error.code -eq $errorCode)) { return $operation }
        if($operation.status -eq 'failed' -or ($operation.status -eq 'succeeded' -and $expected -ne 'succeeded')) {
            throw "Operation reached unexpected status=$($operation.status) error=$($operation.error.code)"
        }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    throw "Operation did not reach $expected/${errorCode}: $id status=$($operation.status) error=$($operation.error.code)"
}
function Node-Snapshot {
    $snapshot=Call-Api GET "/v1/nodes/$target" $null
    if($snapshot.stale) { throw 'Node observation is stale' }
    $snapshot.node
}
function Node-Change([string]$action,[string]$key) {
    for($attempt=0;$attempt -lt 30;$attempt++) {
        $node=Node-Snapshot
        if($node.unschedulable) { break }
        Start-Sleep -Seconds 2
    }
    if(!$node.unschedulable) { throw 'Target cordon was not observed' }
    $response=Call-Api POST "/v1/nodes/$target/$action" @{expected_uid=$node.uid;expected_resource_version=$node.resource_version} $key
    Wait-Status $response.operation_id 'succeeded' | Out-Null
}
function Workload-Pods([string]$id) {
    $json=kubectl --kubeconfig $kubeconfig --context $context -n platform-workloads get pods -l "platform.cluster.io/workload=$id" -o json
    Assert-NativeSuccess 'Read workload Pods'
    @((($json -join "`n") | ConvertFrom-Json).items)
}
function Target-Is-Cordoned {
    $node=kubectl --kubeconfig $kubeconfig --context $context get node $target -o json | ConvertFrom-Json
    Assert-NativeSuccess 'Read target Node'
    [bool]$node.spec.unschedulable
}
function Start-Drain([string]$key) {
    $node=Node-Snapshot
    Call-Api POST "/v1/nodes/$target/drain" @{expected_uid=$node.uid;expected_resource_version=$node.resource_version} $key
}

# A standalone Pod in another namespace must block before the target is cordoned.
$unmanagedName="drain-unmanaged-$($run.Substring(0,8))"
$unmanagedPath=Join-Path $root '.local/drain-unmanaged-pod.json'
@{apiVersion='v1';kind='Pod';metadata=@{name=$unmanagedName;namespace='default'};
  spec=@{nodeName=$target;containers=@(@{name='sleep';image='busybox:1.37.0';command=@('sh','-c','sleep 3600')});restartPolicy='Never'}} |
    ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $unmanagedPath -Encoding utf8
kubectl --kubeconfig $kubeconfig --context $context apply -f $unmanagedPath | Out-Null
Assert-NativeSuccess 'Create unmanaged fault Pod'
try {
    $pod=kubectl --kubeconfig $kubeconfig --context $context -n default get pod $unmanagedName -o json | ConvertFrom-Json
    Assert-NativeSuccess 'Observe unmanaged fault Pod'
    if($pod.spec.nodeName -ne $target) { throw 'Unmanaged fault Pod is not on target' }
    $unmanagedOperation=Start-Drain "unmanaged-$run"
    Wait-Status $unmanagedOperation.operation_id 'blocked' 'node_contains_unmanaged_pods' | Out-Null
    if(Target-Is-Cordoned) { throw 'Unmanaged Pod block happened after target cordon' }
} finally {
    kubectl --kubeconfig $kubeconfig --context $context delete -f $unmanagedPath --ignore-not-found --wait=true | Out-Null
    Assert-NativeSuccess 'Delete unmanaged fault Pod'
}
Wait-Status $unmanagedOperation.operation_id 'succeeded' | Out-Null
Node-Change 'uncordon' "uncordon-unmanaged-$run"

# A one-replica PDB must deny Eviction; an executor restart must not lose the operation.
$images=Call-Api GET '/v1/images?limit=100&offset=0' $null
if(!$images.items.Count) { throw 'Run scripts/acceptance-drain-lab.ps1 first to register a real image' }
$image=$images.items[0].id
$state=kubectl --kubeconfig $kubeconfig --context $context get node $alternate -o json | ConvertFrom-Json
Assert-NativeSuccess 'Read alternate node'
if($state.spec.unschedulable) { throw 'Alternate node is already cordoned' }
$alternateCordoned=$false
try {
    kubectl --kubeconfig $kubeconfig --context $context cordon $alternate | Out-Null
    Assert-NativeSuccess 'Cordon alternate for placement'
    $alternateCordoned=$true
    $configuration=@{
        name="drain-pdb-$($run.Substring(0,8))";image_id=$image;cluster_id='drain-kind';type='service';replicas=1
        placement=@{allowed_nodes=@($target,$alternate)}
        resources=@{gpu_per_replica=0;cpu_cores=0.1;memory_gib=0.0625}
        ports=@(@{name='http';port=8080;protocol='TCP'})
        readiness=@{type='http';path='/ready';port=8080}
        termination_grace_seconds=10
    }
    $created=Call-Api POST '/v1/workloads' $configuration "create-pdb-$run"
    Wait-Status $created.operation_id 'succeeded' | Out-Null
    $workloadId=$created.target_id
    $pods=Workload-Pods $workloadId
    if($pods.Count -ne 1 -or $pods[0].spec.nodeName -ne $target) { throw 'PDB fixture did not become Ready on target' }
} finally {
    if($alternateCordoned) {
        kubectl --kubeconfig $kubeconfig --context $context uncordon $alternate | Out-Null
        Assert-NativeSuccess 'Restore alternate scheduling'
    }
}
$oldUid=(Workload-Pods $workloadId)[0].metadata.uid
$pdbName="drain-pdb-$($run.Substring(0,8))"
$pdbPath=Join-Path $root '.local/drain-pdb.json'
@{apiVersion='policy/v1';kind='PodDisruptionBudget';metadata=@{name=$pdbName;namespace='platform-workloads'};
  spec=@{minAvailable=1;selector=@{matchLabels=@{'platform.cluster.io/workload'=$workloadId}}}} |
    ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $pdbPath -Encoding utf8
kubectl --kubeconfig $kubeconfig --context $context apply -f $pdbPath | Out-Null
Assert-NativeSuccess 'Create PDB fault fixture'
for($attempt=0;$attempt -lt 60;$attempt++) {
    $pdb=kubectl --kubeconfig $kubeconfig --context $context -n platform-workloads get pdb $pdbName -o json | ConvertFrom-Json
    Assert-NativeSuccess 'Observe PDB status'
    if($pdb.status.currentHealthy -ge 1 -and $pdb.status.disruptionsAllowed -eq 0) { break }
    Start-Sleep -Seconds 2
}
if($pdb.status.currentHealthy -lt 1 -or $pdb.status.disruptionsAllowed -ne 0) { throw 'PDB did not become active' }
$node=Node-Snapshot
$drainBody=@{expected_uid=$node.uid;expected_resource_version=$node.resource_version}
$pdbOperation=Call-Api POST "/v1/nodes/$target/drain" $drainBody "pdb-$run"
Wait-Status $pdbOperation.operation_id 'blocked' 'eviction_pdb_or_rate_limited' | Out-Null
if(!(Target-Is-Cordoned)) { throw 'PDB test target was not cordoned' }
$replayed=Call-Api POST "/v1/nodes/$target/drain" $drainBody "pdb-$run"
if($replayed.operation_id -ne $pdbOperation.operation_id) { throw 'Idempotent drain retry created a second operation' }
$conflict=$false
try { Call-Api POST "/v1/nodes/$target/drain" $drainBody "pdb-conflict-$run" | Out-Null }
catch { $conflict=([int]$_.Exception.Response.StatusCode -eq 409) }
if(!$conflict) { throw 'Conflicting drain request did not return HTTP 409' }
docker compose --env-file (Join-Path $root '.local/drain-lab.env') -f (Join-Path $root 'deploy/compose-drain-lab.yml') restart worker | Out-Null
Assert-NativeSuccess 'Restart drain lab executor during PDB block'
Wait-Status $pdbOperation.operation_id 'blocked' 'eviction_pdb_or_rate_limited' | Out-Null
kubectl --kubeconfig $kubeconfig --context $context delete -f $pdbPath --wait=true | Out-Null
Assert-NativeSuccess 'Release PDB block'
Wait-Status $pdbOperation.operation_id 'succeeded' | Out-Null
$migrated=$false
for($attempt=0;$attempt -lt 60;$attempt++) {
    $pods=Workload-Pods $workloadId
    $onTarget=@($pods | Where-Object { $_.spec.nodeName -eq $target })
    $readyAlternate=@($pods | Where-Object { $_.spec.nodeName -eq $alternate -and (@($_.status.conditions | Where-Object { $_.type -eq 'Ready' -and $_.status -eq 'True' })).Count -gt 0 })
    if(!$onTarget.Count -and $readyAlternate.Count -eq 1) { $migrated=$true; break }
    Start-Sleep -Seconds 2
}
if(!$migrated -or $readyAlternate[0].metadata.uid -eq $oldUid) { throw 'PDB recovery did not create a Ready replacement Pod' }
Node-Change 'uncordon' "uncordon-pdb-$run"
$workload=Call-Api GET "/v1/workloads/$workloadId" $null
$deleted=Call-Api DELETE "/v1/workloads/$workloadId" $null "delete-pdb-$run" @{'If-Match'='"'+$workload.version+'"'}
Wait-Status $deleted.operation_id 'succeeded' | Out-Null
@{run=$run;completed_at=(Get-Date).ToUniversalTime().ToString('o');result='passed';unmanaged_operation=$unmanagedOperation.operation_id;pdb_operation=$pdbOperation.operation_id;workload=$workloadId;old_pod_uid=$oldUid;new_pod_uid=$readyAlternate[0].metadata.uid} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '.local/drain-fault-acceptance-result.json') -Encoding utf8
Write-Output 'Unmanaged Pod block, PDB block and release, idempotency conflict, and worker restart recovery passed.'
