. "$PSScriptRoot/acceptance-lib.ps1"
$run=[guid]::NewGuid().ToString('N')
$env:KUBECONFIG="$root/.local/kubeconfig"
$compose=@('compose','--env-file',"$root/.local/platform.env",'-f',"$root/deploy/compose.yml")
$image=(Call-Api GET '/v1/images?limit=100' $null).items | Select-Object -First 1
if (!$image) { throw 'Run acceptance.ps1 first to publish the fixture.' }
$results=[Collections.Generic.List[string]]::new()
function New-Configuration([string]$name) {
    @{
        name=$name; image_id=$image.id; cluster_id='local-kind'; type='service'; replicas=1
        resources=@{gpu_per_replica=0;cpu_cores=0.1;memory_gib=0.0625}
        ports=@(@{name='http';port=8080;protocol='TCP'})
        readiness=@{type='http';path='/ready';port=8080}; termination_grace_seconds=5
    }
}
function Wait-State([string]$operation,[string[]]$states,[int]$seconds=120) {
    $deadline=(Get-Date).AddSeconds($seconds)
    do {
        $op=Call-Api GET "/v1/operations/$operation" $null
        if($states -contains $op.status -or $states -contains $op.stage) { return $op }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $deadline)
    throw "Expected $states; observed $($op.status)/$($op.stage)"
}
function Remove-TestWorkload([string]$id) {
    $deadline=(Get-Date).AddSeconds(120)
    do {
        $current=Call-Api GET "/v1/workloads/$id" $null
        $h=$headers.Clone(); $h['Idempotency-Key']="delete-fault-$id"; $h['If-Match']='"' + $current.version + '"'
        try {
            $deleted=Invoke-RestMethod -Method Delete -Uri "$base/v1/workloads/$id" -Headers $h
            Wait-Operation $deleted.operation_id | Out-Null; return
        } catch { if([int]$_.Exception.Response.StatusCode -ne 409) { throw }; Start-Sleep -Seconds 1 }
    } while((Get-Date) -lt $deadline)
    throw 'Could not acquire deletion boundary'
}
# Missing repository artifact is an asynchronous business failure, not an HTTP 500.
$upload=Call-Api POST '/v1/image-uploads' @{repository='absent';tag='never-pushed'} "absent-$run"
Wait-Operation $upload.operation_id | Out-Null
$verify=Call-Api POST "/v1/image-uploads/$($upload.target_id)/complete" @{} "absent-complete-$run"
$failed=Wait-State $verify.operation_id @('failed')
if($failed.error.code -ne 'image_not_found') { throw 'Missing artifact error was not classified' }
$results.Add('image_not_found')

# A new digest has never been cached: disable only its dedicated pull robot before scheduling.
$authImageId=Publish-Image "auth-$run"
$authImage=Call-Api GET "/v1/images/$authImageId" $null
$registryProject=($authImage.reference -split '/')[1]
$authConfig="$root/.local/harbor-auth.cfg"
[IO.File]::WriteAllText($authConfig,('user = "' + $env:HARBOR_USER + ':' + $env:HARBOR_PASSWORD + '"' + "`nresolve = harbor.platform.test:30443:127.0.0.1`nsilent`nshow-error`nfail`n"))
try {
    $projectInfo=curl.exe --config $authConfig --cacert "$root/.local/harbor.crt" "$env:HARBOR_URL/api/v2.0/projects/$registryProject" | ConvertFrom-Json
    Assert-NativeSuccess 'Find dedicated registry project'
    $query=[Uri]::EscapeDataString("Level=project,ProjectID=$($projectInfo.project_id)")
    $robotList=curl.exe --config $authConfig --cacert "$root/.local/harbor.crt" "$env:HARBOR_URL/api/v2.0/robots?q=$query" | ConvertFrom-Json
    Assert-NativeSuccess 'Find dedicated test pull account'
    $robot=$robotList | Where-Object { $_.name.EndsWith("$registryProject+pull") } | Select-Object -First 1
    if(!$robot) { throw 'Dedicated pull account not found' }
    $robot.disable=$true
    $robot | ConvertTo-Json -Depth 20 | Set-Content "$root/.local/robot-update.json"
    curl.exe --config $authConfig --cacert "$root/.local/harbor.crt" -X PUT -H 'Content-Type: application/json' --data-binary "@$root/.local/robot-update.json" "$env:HARBOR_URL/api/v2.0/robots/$($robot.id)"
    Assert-NativeSuccess 'Disable dedicated test pull account'
    $config=New-Configuration 'fault-auth'; $config.image_id=$authImageId
    $created=Call-Api POST '/v1/workloads' $config "auth-$run"
    try {
        $state=Wait-State $created.operation_id @('blocked')
        if($state.error.code -ne 'image_pull_unauthorized') { throw "Pull authentication error was not classified: $($state.error.code)" }
        $results.Add('image_pull_unauthorized')
    } finally { Remove-TestWorkload $created.target_id }
} finally {
    if($robot) {
        $robot.disable=$false
        $robot | ConvertTo-Json -Depth 20 | Set-Content "$root/.local/robot-update.json"
        curl.exe --config $authConfig --cacert "$root/.local/harbor.crt" -X PUT -H 'Content-Type: application/json' --data-binary "@$root/.local/robot-update.json" "$env:HARBOR_URL/api/v2.0/robots/$($robot.id)"
    }
    Remove-Item -LiteralPath $authConfig -ErrorAction SilentlyContinue
}

foreach($scenario in @('readiness','crash','capacity')) {
    $config=New-Configuration "fault-$scenario"
    if($scenario -eq 'readiness') { $config.readiness.path='/does-not-exist' }
    if($scenario -eq 'crash') { $config.command=@('/bin/sh','-c'); $config.args=@('exit 7') }
    if($scenario -eq 'capacity') { $config.resources.cpu_cores=256 }
    $created=Call-Api POST '/v1/workloads' $config "$scenario-$run"
    try {
        if($scenario -eq 'readiness') {
            $state=Wait-State $created.operation_id @('starting')
            if($state.status -eq 'succeeded') { throw 'Unready service was marked successful' }
            $probeFailed=$false
            for($attempt=0;$attempt -lt 30;$attempt++) {
                $events=kubectl --context kind-gpu-platform -n platform-workloads get events --field-selector reason=Unhealthy -o json | ConvertFrom-Json
                Assert-NativeSuccess 'Observe readiness probe failure'
                if($events.items | Where-Object { $_.involvedObject.name.StartsWith($created.target_id+'-') -and $_.message -match 'statuscode: 404' }) { $probeFailed=$true; break }
                Start-Sleep -Seconds 2
            }
            if(!$probeFailed) { throw 'No real HTTP 404 readiness probe event was observed' }
        } else {
            $state=Wait-State $created.operation_id @('blocked')
            $expected=if($scenario -eq 'crash'){'container_crash'}else{'unschedulable'}
            if($state.error.code -ne $expected) { throw "Unexpected error for $scenario" }
        }
        $results.Add($scenario)
    } finally { Remove-TestWorkload $created.target_id }
}
# Restart with a durable pending operation; no duplicate workload may appear.
docker @compose stop worker | Out-Null
Assert-NativeSuccess 'Stop test worker'
try { $created=Call-Api POST '/v1/workloads' (New-Configuration 'fault-restart') "restart-$run" }
finally { docker @compose start worker | Out-Null; Assert-NativeSuccess 'Restart test worker' }
Wait-Operation $created.operation_id | Out-Null
Remove-TestWorkload $created.target_id
$results.Add('worker_restart')

# Disconnect only the worker from the dedicated kind network, leaving API/database available.
$worker=docker @compose ps -q worker
docker network disconnect kind $worker
Assert-NativeSuccess 'Disconnect test worker from cluster'
try {
    $created=Call-Api POST '/v1/workloads' (New-Configuration 'fault-cluster') "cluster-$run"
    Wait-State $created.operation_id @('unknown') | Out-Null
} finally { docker network connect kind $worker; Assert-NativeSuccess 'Restore cluster connectivity' }
Wait-Operation $created.operation_id | Out-Null
Remove-TestWorkload $created.target_id
$results.Add('cluster_disconnect_recovery')

# Suspend only this test registry's core, then restore it even if the check fails.
kubectl --context kind-gpu-platform -n harbor scale deployment/harbor-core --replicas=0 | Out-Null
Assert-NativeSuccess 'Suspend test Harbor core'
try {
    $upload=Call-Api POST '/v1/image-uploads' @{repository='recovery';tag='v1'} "registry-$run"
    Wait-State $upload.operation_id @('unknown') | Out-Null
} finally {
    kubectl --context kind-gpu-platform -n harbor scale deployment/harbor-core --replicas=1 | Out-Null
    Assert-NativeSuccess 'Restore test Harbor core'
    kubectl --context kind-gpu-platform -n harbor rollout status deployment/harbor-core --timeout=180s | Out-Null
    Assert-NativeSuccess 'Wait for restored Harbor'
}
Wait-Operation $upload.operation_id | Out-Null
$results.Add('registry_disconnect_recovery')
@{completed_at=(Get-Date).ToUniversalTime().ToString('o');passed=$results} | ConvertTo-Json -Depth 5 | Set-Content "$root/.local/fault-acceptance-result.json"
Write-Output "Fault acceptance passed: $($results -join ', ')"
