. "$PSScriptRoot/acceptance-lib.ps1"
$run=[guid]::NewGuid().ToString('N')
$image=(Call-Api GET '/v1/images?limit=100' $null).items | Select-Object -First 1
if(!$image) { throw 'Publish the CPU fixture with scripts/acceptance.ps1 first' }
$created=$null
try {
    $configuration=@{
        name="diagnostic-$($run.Substring(0,8))"; image_id=$image.id; cluster_id='local-kind'; type='service'; replicas=1
        resources=@{gpu_per_replica=0;cpu_cores=0.1;memory_gib=0.0625}
        ports=@(@{name='http';port=8080;protocol='TCP'})
        readiness=@{type='http';path='/does-not-exist';port=8080}; termination_grace_seconds=5
    }
    $created=Call-Api POST '/v1/workloads' $configuration "diagnostic-$run"
    $deadline=(Get-Date).AddMinutes(3)
    do {
        $workload=Call-Api GET "/v1/workloads/$($created.target_id)" $null
        $diagnostics=$workload.observed.diagnostics
        $unhealthy=@($diagnostics.events | Where-Object { $_.reason -eq 'Unhealthy' })
        if($unhealthy.Count -gt 0) { break }
        if($diagnostics.status -eq 'forbidden') { throw 'Worker cannot list namespace events' }
        Start-Sleep -Seconds 3
    } while((Get-Date) -lt $deadline)
    if($unhealthy.Count -eq 0) { throw "No Unhealthy event in platform observation; diagnostic status=$($diagnostics.status)" }
    if($workload.observed.complete) { throw 'Failed readiness probe was incorrectly marked ready' }
    if(($diagnostics | ConvertTo-Json -Depth 10) -match 'message|statuscode|token=') { throw 'Raw event message leaked into workload response' }
    Write-Output "Real kind readiness diagnostic passed: reason=Unhealthy, pod=$($unhealthy[0].pod), status=$($diagnostics.status)"
} finally {
    if($created) {
        $deadline=(Get-Date).AddMinutes(2)
        do {
            try {
                $current=Call-Api GET "/v1/workloads/$($created.target_id)" $null
                $h=$headers.Clone(); $h['Idempotency-Key']="diagnostic-delete-$run"; $h['If-Match']='"'+$current.version+'"'
                $deleted=Invoke-RestMethod -Method Delete -Uri "$base/v1/workloads/$($created.target_id)" -Headers $h
                Wait-Operation $deleted.operation_id | Out-Null
                break
            } catch {
                if([int]$_.Exception.Response.StatusCode -ne 409 -or (Get-Date) -ge $deadline) { throw }
                Start-Sleep -Seconds 2
            }
        } while((Get-Date) -lt $deadline)
    }
}
