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
function Wait-Operation([string]$id) {
    $deadline=(Get-Date).AddMinutes(5)
    do {
        $operation=Call-Api GET "/v1/operations/$id" $null
        if($operation.status -eq 'succeeded') { return $operation }
        if($operation.status -eq 'failed') { throw "Operation failed: $($operation.error.code)" }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    throw "Operation did not converge: $id status=$($operation.status) stage=$($operation.stage)"
}
function Publish-Image {
    $upload=Call-Api POST '/v1/image-uploads' @{repository='drain-smoke';tag='v1'} "upload-$run"
    Wait-Operation $upload.operation_id | Out-Null
    $session=Call-Api GET "/v1/image-uploads/$($upload.target_id)" $null
    $credential=Call-Api POST "/v1/image-uploads/$($upload.target_id)/credentials" $null
    $auth=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($credential.username):$($credential.password)"))
    $authPath=Join-Path $root '.local/drain-push-auth.json'
    $archive=Join-Path $root '.local/drain-fixture.tar'
    @{auths=@{$credential.registry=@{auth=$auth}}} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $authPath -Encoding utf8
    try {
        docker build --build-arg APP_VERSION=v1 -t platform-drain-fixture:v1 (Join-Path $root 'tests/fixtures') | Out-Host
        Assert-NativeSuccess 'Build drain fixture'
        docker save -o $archive platform-drain-fixture:v1
        Assert-NativeSuccess 'Export drain fixture'
        docker run --rm --network kind --add-host harbor.platform.test:host-gateway -v "${archive}:/input/fixture.tar:ro" -v "${authPath}:/auth/auth.json:ro" -v "${root}/.local/harbor.crt:/certs/ca.crt:ro" quay.io/skopeo/stable:v1.20.0 copy --dest-authfile /auth/auth.json --dest-cert-dir /certs --format v2s2 docker-archive:/input/fixture.tar "docker://$($session.target)" | Out-Host
        Assert-NativeSuccess 'Push drain fixture to Harbor'
    } finally { Remove-Item -LiteralPath $authPath -ErrorAction SilentlyContinue }
    $complete=Call-Api POST "/v1/image-uploads/$($upload.target_id)/complete" @{} "complete-$run"
    Wait-Operation $complete.operation_id | Out-Null
    (Call-Api GET "/v1/image-uploads/$($upload.target_id)" $null).image_id
}
function Workload-Pods([string]$id) {
    $json=kubectl --kubeconfig $kubeconfig --context $context -n platform-workloads get pods -l "platform.cluster.io/workload=$id" -o json
    Assert-NativeSuccess 'Read drain lab workload Pods'
    @((($json -join "`n") | ConvertFrom-Json).items)
}

$image=Publish-Image
$alternativeState=kubectl --kubeconfig $kubeconfig --context $context get node $alternate -o json | ConvertFrom-Json
Assert-NativeSuccess 'Read alternate node'
if($alternativeState.spec.unschedulable) { throw 'Alternate node is already cordoned; inspect before continuing' }
$targetState=kubectl --kubeconfig $kubeconfig --context $context get node $target -o json | ConvertFrom-Json
Assert-NativeSuccess 'Read target node'
if($targetState.spec.unschedulable) { throw 'Target node is already cordoned; inspect before continuing' }
$alternateCordoned=$false
try {
    kubectl --kubeconfig $kubeconfig --context $context cordon $alternate | Out-Null
    Assert-NativeSuccess 'Temporarily cordon alternate node'
    $alternateCordoned=$true
    $configuration=@{
        name="drain-smoke-$($run.Substring(0,8))";image_id=$image;cluster_id='drain-kind';type='service';replicas=1
        placement=@{allowed_nodes=@($target,$alternate)}
        resources=@{gpu_per_replica=0;cpu_cores=0.1;memory_gib=0.0625}
        ports=@(@{name='http';port=8080;protocol='TCP'})
        readiness=@{type='http';path='/ready';port=8080}
        termination_grace_seconds=10
    }
    $created=Call-Api POST '/v1/workloads' $configuration "create-$run"
    Wait-Operation $created.operation_id | Out-Null
    $id=$created.target_id
    $pods=Workload-Pods $id
    if($pods.Count -ne 1 -or $pods[0].spec.nodeName -ne $target) { throw 'Workload did not become Ready on target worker' }
} finally {
    if($alternateCordoned) {
        kubectl --kubeconfig $kubeconfig --context $context uncordon $alternate | Out-Null
        Assert-NativeSuccess 'Restore alternate node scheduling'
    }
}
$before=(Workload-Pods $id)[0].metadata.uid
$node=Call-Api GET "/v1/nodes/$target" $null
if($node.stale -or $node.node.uid -ne $targetState.metadata.uid) { throw 'Node observation is stale or identity changed' }
$drain=Call-Api POST "/v1/nodes/$target/drain" @{expected_uid=$node.node.uid;expected_resource_version=$node.node.resource_version} "drain-$run"
Wait-Operation $drain.operation_id | Out-Null
$migrated=$false
for($attempt=0;$attempt -lt 60;$attempt++) {
    $pods=Workload-Pods $id
    $onTarget=@($pods | Where-Object { $_.spec.nodeName -eq $target })
    $readyOnAlternate=@($pods | Where-Object { $_.spec.nodeName -eq $alternate -and (@($_.status.conditions | Where-Object { $_.type -eq 'Ready' -and $_.status -eq 'True' })).Count -gt 0 })
    if(!$onTarget.Count -and $readyOnAlternate.Count -eq 1) { $migrated=$true; break }
    Start-Sleep -Seconds 2
}
if(!$migrated) { throw 'Drain operation succeeded but replacement Pod was not Ready on alternate worker' }
$after=$readyOnAlternate[0].metadata.uid
if($before -eq $after) { throw 'Pod UID did not change after eviction' }
$nodeState=kubectl --kubeconfig $kubeconfig --context $context get node $target -o json | ConvertFrom-Json
Assert-NativeSuccess 'Read target state after drain'
if(!$nodeState.spec.unschedulable) { throw 'Target node was not cordoned after drain' }
@{run=$run;completed_at=(Get-Date).ToUniversalTime().ToString('o');result='passed';target=$target;alternate=$alternate;workload=$id;drain_operation=$drain.operation_id;old_pod_uid=$before;new_pod_uid=$after} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '.local/drain-acceptance-result.json') -Encoding utf8
Write-Output "Real drain migration passed. Workload $id remains on $alternate; target remains cordoned for inspection."
