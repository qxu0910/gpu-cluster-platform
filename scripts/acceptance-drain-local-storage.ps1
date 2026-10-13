. "$PSScriptRoot/common.ps1"
$ErrorActionPreference='Stop'
$base='http://127.0.0.1:18082'
$kubeconfig=Join-Path $root '.local/kubeconfig-drain-lab'
$context='kind-gpu-platform-drain'
$target='gpu-platform-drain-worker'
$alternate='gpu-platform-drain-worker2'
$run=[guid]::NewGuid().ToString('N')
$capabilities=Invoke-RestMethod "$base/v1/capabilities"
if(!$capabilities.node_drain) { throw 'Drain is disabled; run scripts/run-drain-lab.ps1 -EnableDrain first' }

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
            throw "Unexpected operation status=$($operation.status) error=$($operation.error.code)"
        }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    throw "Operation $id did not reach $expected/$errorCode; status=$($operation.status) error=$($operation.error.code)"
}
function Node-Snapshot {
    $snapshot=Call-Api GET "/v1/nodes/$target" $null
    if($snapshot.stale) { throw 'Target observation is stale' }
    $snapshot.node
}
function Node-Cordoned {
    $node=kubectl --kubeconfig $kubeconfig --context $context get node $target -o json | ConvertFrom-Json
    Assert-NativeSuccess 'Read target node'
    [bool]$node.spec.unschedulable
}
function Pods([string]$id) {
    $json=kubectl --kubeconfig $kubeconfig --context $context -n platform-workloads get pods -l "platform.cluster.io/workload=$id" -o json
    Assert-NativeSuccess 'Read workload Pods'
    @((($json -join "`n") | ConvertFrom-Json).items)
}
function Wait-Target-Volume([string]$id,[string]$kind) {
    for($attempt=0;$attempt -lt 90;$attempt++) {
        $pods=Pods $id
        $ready=@($pods | Where-Object {
            $_.spec.nodeName -eq $target -and !$_.metadata.deletionTimestamp -and
            (@($_.status.conditions | Where-Object { $_.type -eq 'Ready' -and $_.status -eq 'True' })).Count -gt 0 -and
            (@($_.spec.volumes | Where-Object { $null -ne $_.$kind })).Count -gt 0 -and
            (@($_.spec.containers[0].volumeMounts | Where-Object { $_.name -eq 'scratch' -and $_.mountPath -eq '/scratch' })).Count -gt 0
        })
        if($ready.Count -eq 1 -and $pods.Count -eq 1) { return $ready[0] }
        Start-Sleep -Seconds 2
    }
    throw "$kind Pod did not become exclusively Ready on target"
}
function Patch-Volume([string]$id,$volume) {
    $patchPath=Join-Path $root '.local/drain-local-storage-patch.json'
    if($null -eq $volume) {
        $patch=@(
            @{op='remove';path='/spec/template/spec/containers/0/volumeMounts'},
            @{op='remove';path='/spec/template/spec/volumes'}
        )
    } else {
        $patch=@(
            @{op='add';path='/spec/template/spec/volumes';value=@($volume)},
            @{op='add';path='/spec/template/spec/containers/0/volumeMounts';value=@(@{name='scratch';mountPath='/scratch'})}
        )
    }
    ConvertTo-Json -InputObject $patch -Depth 10 | Set-Content -LiteralPath $patchPath -Encoding utf8
    kubectl --kubeconfig $kubeconfig --context $context -n platform-workloads patch deployment $id --type=json --patch-file $patchPath | Out-Null
    Assert-NativeSuccess 'Patch Deployment local volume'
}
function Wait-Replacement([string]$id,[string]$oldUid) {
    for($attempt=0;$attempt -lt 90;$attempt++) {
        $pods=Pods $id
        $onTarget=@($pods | Where-Object { $_.spec.nodeName -eq $target })
        $ready=@($pods | Where-Object {
            $_.spec.nodeName -eq $alternate -and $_.metadata.uid -ne $oldUid -and
            (@($_.status.conditions | Where-Object { $_.type -eq 'Ready' -and $_.status -eq 'True' })).Count -gt 0
        })
        if(!$onTarget.Count -and $ready.Count -eq 1) { return $ready[0] }
        Start-Sleep -Seconds 2
    }
    throw 'Ready replacement Pod was not observed on alternate worker'
}
function Run-Case([string]$kind,$volume,[string]$image) {
    if(Node-Cordoned) { throw 'Target is cordoned before test' }
    $alternateState=kubectl --kubeconfig $kubeconfig --context $context get node $alternate -o json | ConvertFrom-Json
    Assert-NativeSuccess 'Read alternate node'
    if($alternateState.spec.unschedulable) { throw 'Alternate is cordoned before test' }
    $suffix="$($kind.ToLowerInvariant())-$($run.Substring(0,8))"
    $alternateCordoned=$false
    try {
        kubectl --kubeconfig $kubeconfig --context $context cordon $alternate | Out-Null
        Assert-NativeSuccess 'Temporarily cordon alternate'
        $alternateCordoned=$true
        $configuration=@{
            name="drain-local-$suffix";image_id=$image;cluster_id='drain-kind';type='service';replicas=1
            placement=@{allowed_nodes=@($target,$alternate)}
            resources=@{gpu_per_replica=0;cpu_cores=0.1;memory_gib=0.0625}
            ports=@(@{name='http';port=8080;protocol='TCP'})
            readiness=@{type='http';path='/ready';port=8080};termination_grace_seconds=10
        }
        $created=Call-Api POST '/v1/workloads' $configuration "create-$suffix"
        Wait-Status $created.operation_id 'succeeded' | Out-Null
        $id=$created.target_id
        Patch-Volume $id $volume
        $pod=Wait-Target-Volume $id $kind
        $podName=$pod.metadata.name
        kubectl --kubeconfig $kubeconfig --context $context -n platform-workloads exec $podName -- touch /scratch/drain-proof
        Assert-NativeSuccess 'Write through local volume mount'
    } finally {
        if($alternateCordoned) {
            kubectl --kubeconfig $kubeconfig --context $context uncordon $alternate | Out-Null
            Assert-NativeSuccess 'Restore alternate scheduling'
        }
    }
    $node=Node-Snapshot
    $drain=Call-Api POST "/v1/nodes/$target/drain" @{expected_uid=$node.uid;expected_resource_version=$node.resource_version} "drain-$suffix"
    Wait-Status $drain.operation_id 'blocked' 'node_contains_local_storage' | Out-Null
    if(Node-Cordoned) { throw "$kind was blocked after target cordon" }
    $stillPresent=@(Pods $id | Where-Object { $_.metadata.uid -eq $pod.metadata.uid })
    if($stillPresent.Count -ne 1) { throw "$kind Pod disappeared while drain was blocked" }
    Patch-Volume $id $null
    Wait-Status $drain.operation_id 'succeeded' | Out-Null
    $replacement=Wait-Replacement $id $pod.metadata.uid
    if(!(Node-Cordoned)) { throw 'Target was not cordoned after recovery' }
    for($attempt=0;$attempt -lt 30;$attempt++) {
        $node=Node-Snapshot
        if($node.unschedulable) { break }
        Start-Sleep -Seconds 2
    }
    if(!$node.unschedulable) { throw 'Platform did not observe target cordon' }
    $uncordon=Call-Api POST "/v1/nodes/$target/uncordon" @{expected_uid=$node.uid;expected_resource_version=$node.resource_version} "uncordon-$suffix"
    Wait-Status $uncordon.operation_id 'succeeded' | Out-Null
    $workload=Call-Api GET "/v1/workloads/$id" $null
    $deleted=Call-Api DELETE "/v1/workloads/$id" $null "delete-$suffix" @{'If-Match'='"'+$workload.version+'"'}
    Wait-Status $deleted.operation_id 'succeeded' | Out-Null
    Write-Host "$kind blocked drain before cordon, then recovered and migrated to alternate"
    @{kind=$kind;workload=$id;drain_operation=$drain.operation_id;old_pod_uid=$pod.metadata.uid;new_pod_uid=$replacement.metadata.uid}
}

$images=Call-Api GET '/v1/images?limit=100&offset=0' $null
if(!$images.items.Count) { throw 'Run scripts/acceptance-drain-lab.ps1 first to register a real image' }
$image=$images.items[0].id
$results=@()
$results+=Run-Case 'emptyDir' @{name='scratch';emptyDir=@{}} $image
$results+=Run-Case 'hostPath' @{name='scratch';hostPath=@{path='/tmp';type='Directory'}} $image
@{run=$run;completed_at=(Get-Date).ToUniversalTime().ToString('o');result='passed';cases=$results} |
    ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $root '.local/drain-local-storage-acceptance-result.json') -Encoding utf8
Write-Output 'Real emptyDir and hostPath drain protection passed; workloads deleted and target uncordoned.'
