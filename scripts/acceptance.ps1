. "$PSScriptRoot/acceptance-lib.ps1"
$run = [guid]::NewGuid().ToString('N')
$image1 = Publish-Image v1
$image2 = Publish-Image v2
$configuration = @{
    name='acceptance'; image_id=$image1; cluster_id='local-kind'; type='service'; replicas=1
    resources=@{gpu_per_replica=0;cpu_cores=0.1;memory_gib=0.0625}
    ports=@(@{name='http';port=8080;protocol='TCP'})
    readiness=@{type='http';path='/ready';port=8080}
    termination_grace_seconds=10
}
$created = Call-Api POST '/v1/workloads' $configuration "create-$run"
$repeated = Call-Api POST '/v1/workloads' $configuration "create-$run"
if ($created.operation_id -ne $repeated.operation_id) { throw 'Idempotency replay created another operation' }
Wait-Operation $created.operation_id | Out-Null
$id=$created.target_id
$env:KUBECONFIG="$root/.local/kubeconfig"
function Assert-ServiceVersion([string]$expected) {
    $result = kubectl --context kind-gpu-platform -n platform-workloads run "check-$([guid]::NewGuid().ToString('N').Substring(0,8))" --image=busybox:1.37.0 --restart=Never --rm -i --quiet --command -- wget -qO- "http://${id}:8080/"
    Assert-NativeSuccess 'Access service from cluster'
    if (($result -join "`n").Trim() -ne $expected) { throw "Service did not return $expected" }
}
Assert-ServiceVersion v1
try { Call-Api DELETE "/v1/images/$image1" $null "protected-$run" | Out-Null; throw 'Referenced image deletion was allowed' }
catch { if ([int]$_.Exception.Response.StatusCode -ne 409) { throw } }
foreach ($action in @('scale','stop','start')) {
    $current=Call-Api GET "/v1/workloads/$id" $null
    $body=@{expected_version=$current.version}
    if($action -eq 'scale') { $body.replicas=2 }
    $changed=Call-Api POST "/v1/workloads/$id/$action" $body "$action-$run"
    Wait-Operation $changed.operation_id | Out-Null
}
$current=Call-Api GET "/v1/workloads/$id" $null
$configuration.image_id=$image2
$revised=Call-Api POST "/v1/workloads/$id/revisions" @{expected_version=$current.version;configuration=$configuration} "revision-$run"
Wait-Operation $revised.operation_id | Out-Null
Assert-ServiceVersion v2
$current=Call-Api GET "/v1/workloads/$id" $null
$deleteHeaders=$headers.Clone(); $deleteHeaders['Idempotency-Key']="delete-$run"; $deleteHeaders['If-Match']='"' + $current.version + '"'
$deleted=Invoke-RestMethod -Method Delete -Uri "$base/v1/workloads/$id" -Headers $deleteHeaders
Wait-Operation $deleted.operation_id | Out-Null
@{run=$run; completed_at=(Get-Date).ToUniversalTime().ToString('o'); result='passed'; scope='real CPU happy path'; workload=$id} | ConvertTo-Json | Set-Content "$root/.local/acceptance-result.json"
Write-Output 'Real CPU lifecycle acceptance passed. Fault acceptance is a separate suite.'
