. "$PSScriptRoot/common.ps1"
$kind=Join-Path $root '.tools/kind.exe'
$config=Join-Path $root 'deploy/kind-drain-lab.yml'
$kubeconfig=Join-Path $root '.local/kubeconfig-drain-lab'
if(!(Test-Path -LiteralPath $kind)) { throw 'Run scripts/setup-cluster.ps1 to install the pinned kind tool first' }
$engine=docker info --format '{{.OSType}}'
Assert-NativeSuccess 'Docker engine check'
if($engine.Trim() -ne 'linux') { throw 'Docker Desktop Linux engine required' }
$clusters=& $kind get clusters
Assert-NativeSuccess 'List kind clusters'
if($clusters -contains 'gpu-platform-drain') {
    if(!(Test-Path -LiteralPath $kubeconfig)) { throw 'Dedicated cluster exists but local kubeconfig is missing; inspect ownership before reuse' }
} else {
    if(Test-Path -LiteralPath $kubeconfig) { throw 'Dedicated drain lab kubeconfig already exists; inspect it before reuse' }
    & $kind create cluster --name gpu-platform-drain --config $config --kubeconfig $kubeconfig --wait 180s
    Assert-NativeSuccess 'Create dedicated drain lab cluster'
}
$kubectlArgs=@('--kubeconfig', $kubeconfig, '--context', 'kind-gpu-platform-drain')
kubectl @kubectlArgs create namespace platform-workloads --dry-run=client -o yaml | kubectl @kubectlArgs apply -f - | Out-Null
Assert-NativeSuccess 'Create dedicated workload namespace'
foreach($manifest in @('worker-rbac.yml','node-drain-rbac.yml','node-maintenance-rbac-drain-lab.yml')) {
    kubectl @kubectlArgs apply -f (Join-Path $root "deploy/$manifest") | Out-Null
    Assert-NativeSuccess "Apply $manifest to dedicated cluster"
}
$subject='system:serviceaccount:platform-workloads:platform-worker'
$allowed=kubectl @kubectlArgs auth can-i update node/gpu-platform-drain-worker --as=$subject
$denied=kubectl @kubectlArgs auth can-i update node/gpu-platform-drain-worker2 --as=$subject
$evict=kubectl @kubectlArgs auth can-i create pods --subresource=eviction -n platform-workloads --as=$subject
$otherNamespace=kubectl @kubectlArgs auth can-i create pods --subresource=eviction -n default --as=$subject
if($allowed.Trim() -ne 'yes' -or $denied.Trim() -ne 'no' -or $evict.Trim() -ne 'yes' -or $otherNamespace.Trim() -ne 'no') {
    throw 'Dedicated worker permissions differ from the bounded drain policy'
}
$nodes=kubectl @kubectlArgs get nodes -o json | ConvertFrom-Json
Assert-NativeSuccess 'Observe dedicated drain lab nodes'
if($nodes.items.Count -ne 3) { throw 'Dedicated drain lab requires one control plane and two workers' }
Write-Output 'Dedicated multi-node kind cluster and scoped RBAC are ready. The existing gpu-platform cluster and default kubeconfig were not changed.'
