. "$PSScriptRoot/common.ps1"
Import-PlatformEnvironment
$base = 'http://localhost:18080'
$headers = @{Authorization="Bearer $env:LOCAL_TOKEN"}
function Call-Api([string]$method,[string]$path,$body,[string]$key = '') {
    $h = $headers.Clone()
    if ($key) { $h['Idempotency-Key'] = $key }
    $parameters = @{Method=$method; Uri="$base$path"; Headers=$h}
    if ($null -ne $body) { $parameters.Body = ConvertTo-Json $body -Depth 20 -Compress; $parameters.ContentType='application/json' }
    Invoke-RestMethod @parameters
}
function Wait-Operation([string]$id) {
    $deadline = (Get-Date).AddMinutes(5)
    do {
        $op = Call-Api GET "/v1/operations/$id" $null
        if ($op.status -eq 'succeeded') { return $op }
        if ($op.status -eq 'failed') { throw "Operation failed: $($op.error.code)" }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Operation did not converge: $id status=$($op.status) stage=$($op.stage)"
}
function Publish-Image([string]$version) {
    $upload = Call-Api POST '/v1/image-uploads' @{repository='demo';tag=$version} "upload-$run-$version"
    Wait-Operation $upload.operation_id | Out-Null
    $session = Call-Api GET "/v1/image-uploads/$($upload.target_id)" $null
    $credentials = Call-Api POST "/v1/image-uploads/$($upload.target_id)/credentials" $null
    $auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($credentials.username):$($credentials.password)"))
    @{auths=@{$credentials.registry=@{auth=$auth}}} | ConvertTo-Json -Depth 8 | Set-Content "$root/.local/push-auth.json" -Encoding utf8
    docker build --build-arg "APP_VERSION=$version" -t "platform-fixture:$version" "$root/tests/fixtures" | Out-Host
    Assert-NativeSuccess 'Build fixture'
    docker save -o "$root/.local/fixture.tar" "platform-fixture:$version" | Out-Host
    Assert-NativeSuccess 'Export fixture'
    try {
        docker run --rm --network kind --add-host harbor.platform.test:host-gateway -v "${root}/.local/fixture.tar:/input/fixture.tar:ro" -v "${root}/.local/push-auth.json:/auth/auth.json:ro" -v "${root}/.local/harbor.crt:/certs/ca.crt:ro" quay.io/skopeo/stable:v1.20.0 copy --dest-authfile /auth/auth.json --dest-cert-dir /certs --format v2s2 "docker-archive:/input/fixture.tar" "docker://$($session.target)" | Out-Host
        Assert-NativeSuccess 'Push fixture using upload credentials'
    } finally { Remove-Item -LiteralPath "$root/.local/push-auth.json" -ErrorAction SilentlyContinue }
    $complete = Call-Api POST "/v1/image-uploads/$($upload.target_id)/complete" @{} "complete-$run-$version"
    Wait-Operation $complete.operation_id | Out-Null
    (Call-Api GET "/v1/image-uploads/$($upload.target_id)" $null).image_id
}
