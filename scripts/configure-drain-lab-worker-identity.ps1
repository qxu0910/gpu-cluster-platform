. "$PSScriptRoot/common.ps1"
$adminConfig=Join-Path $root '.local/kubeconfig-drain-lab'
$workerConfig=Join-Path $root '.local/kubeconfig-drain-lab-worker'
if(!(Test-Path -LiteralPath $adminConfig)) { throw 'Run scripts/setup-drain-lab.ps1 first' }
$kind=Join-Path $root '.tools/kind.exe'
if(!(Test-Path -LiteralPath $kind)) { throw 'Pinned kind tool is missing' }

$token=kubectl --kubeconfig $adminConfig --context kind-gpu-platform-drain -n platform-workloads create token platform-worker --duration=24h
Assert-NativeSuccess 'Request drain lab worker token'
if([string]::IsNullOrWhiteSpace($token)) { throw 'Empty worker token' }
$internalPath=Join-Path $root '.local/kubeconfig-drain-lab-container'
& $kind get kubeconfig --name gpu-platform-drain --internal | Set-Content -LiteralPath $internalPath -Encoding utf8
Assert-NativeSuccess 'Read internal drain lab API address and CA'
$internal=kubectl --kubeconfig $internalPath config view --raw -o json | ConvertFrom-Json
Assert-NativeSuccess 'Parse internal drain lab kubeconfig'
$cluster=$internal.clusters[0].cluster
$config=@{
    apiVersion='v1'; kind='Config'; 'current-context'='kind-gpu-platform-drain'
    clusters=@(@{name='kind-gpu-platform-drain';cluster=@{server=$cluster.server;'certificate-authority-data'=$cluster.'certificate-authority-data'}})
    users=@(@{name='platform-worker';user=@{token=$token.Trim()}})
    contexts=@(@{name='kind-gpu-platform-drain';context=@{cluster='kind-gpu-platform-drain';user='platform-worker';namespace='platform-workloads'}})
}
[IO.File]::WriteAllText($workerConfig,($config | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
$hostView=kubectl --kubeconfig $adminConfig config view --raw -o json | ConvertFrom-Json
Assert-NativeSuccess 'Read host drain lab API address'
$config.clusters[0].cluster.server=$hostView.clusters[0].cluster.server
$hostConfig=Join-Path $root '.local/kubeconfig-drain-lab-worker-host'
[IO.File]::WriteAllText($hostConfig,($config | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
$allowed=kubectl --kubeconfig $hostConfig auth can-i update node/gpu-platform-drain-worker
$denied=kubectl --kubeconfig $hostConfig auth can-i update node/gpu-platform-drain-worker2
$evict=kubectl --kubeconfig $hostConfig auth can-i create pods --subresource=eviction -n platform-workloads
$other=kubectl --kubeconfig $hostConfig auth can-i create pods --subresource=eviction -n default
if($allowed.Trim() -ne 'yes' -or $denied.Trim() -ne 'no' -or $evict.Trim() -ne 'yes' -or $other.Trim() -ne 'no') {
    throw 'Scoped worker token does not match the drain lab policy'
}
Write-Output 'Dedicated 24-hour drain lab worker credentials saved under ignored .local/; no token printed.'
