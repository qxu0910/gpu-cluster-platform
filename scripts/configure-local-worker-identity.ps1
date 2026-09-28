. "$PSScriptRoot/common.ps1"
$adminConfig="$root/.local/kubeconfig"
$internalConfig="$root/.local/kubeconfig-container"
if(!(Test-Path $adminConfig) -or !(Test-Path $internalConfig)) { throw 'Run scripts/setup-cluster.ps1 first' }
kubectl --kubeconfig $adminConfig --context kind-gpu-platform apply -f "$root/deploy/worker-rbac.yml" | Out-Null
Assert-NativeSuccess 'Install namespace and read-only node permissions'
kubectl --kubeconfig $adminConfig --context kind-gpu-platform apply -f "$root/deploy/node-maintenance-rbac-local.yml" | Out-Null
Assert-NativeSuccess 'Install permission limited to the local test node'
$workerToken=kubectl --kubeconfig $adminConfig --context kind-gpu-platform -n platform-workloads create token platform-worker --duration=24h
Assert-NativeSuccess 'Request scoped worker token'
if([string]::IsNullOrWhiteSpace($workerToken)) { throw 'Empty scoped worker token' }
$internal=kubectl --kubeconfig $internalConfig config view --raw -o json | ConvertFrom-Json
Assert-NativeSuccess 'Read internal cluster address and CA'
$cluster=$internal.clusters[0].cluster
$config=@{
    apiVersion='v1'; kind='Config'; 'current-context'='kind-gpu-platform'
    clusters=@(@{name='kind-gpu-platform';cluster=@{server=$cluster.server;'certificate-authority-data'=$cluster.'certificate-authority-data'}})
    users=@(@{name='platform-worker';user=@{token=$workerToken.Trim()}})
    contexts=@(@{name='kind-gpu-platform';context=@{cluster='kind-gpu-platform';user='platform-worker';namespace='platform-workloads'}})
}
$destination="$root/.local/kubeconfig-worker"
[IO.File]::WriteAllText($destination,($config | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
$hostView=kubectl --kubeconfig $adminConfig config view --raw -o json | ConvertFrom-Json
Assert-NativeSuccess 'Read host API endpoint and CA'
$config.clusters[0].cluster.server=$hostView.clusters[0].cluster.server
$hostDestination="$root/.local/kubeconfig-worker-host"
[IO.File]::WriteAllText($hostDestination,($config | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
$allowed=kubectl --kubeconfig $hostDestination auth can-i update node/gpu-platform-control-plane
$denied=kubectl --kubeconfig $hostDestination auth can-i update node/unrelated-node
$listed=kubectl --kubeconfig $hostDestination auth can-i list nodes
if($allowed.Trim() -ne 'yes' -or $denied.Trim() -ne 'no' -or $listed.Trim() -ne 'yes') { throw 'Worker node permissions differ from the bounded policy' }
Write-Output 'Scoped worker kubeconfig saved locally for 24 hours; no token printed.'
