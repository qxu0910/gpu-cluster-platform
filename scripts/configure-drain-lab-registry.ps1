. "$PSScriptRoot/common.ps1"
$certificate=Join-Path $root '.local/harbor.crt'
if(!(Test-Path -LiteralPath $certificate)) { throw 'Local Harbor CA is missing; run scripts/setup-cluster.ps1 first' }
$gateway=docker inspect gpu-platform-control-plane --format '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}'
Assert-NativeSuccess 'Find kind network gateway'
if($gateway -notmatch '^\d+\.\d+\.\d+\.\d+$') { throw 'Invalid kind gateway address' }
$nodes=@('gpu-platform-drain-control-plane','gpu-platform-drain-worker','gpu-platform-drain-worker2')
$hostsFile=Join-Path $root '.local/hosts-drain-lab.toml'
@'
server = "https://harbor.platform.test:30443"
[host."https://harbor.platform.test:30443"]
  capabilities = ["pull", "resolve"]
  ca = "/etc/containerd/certs.d/harbor.platform.test:30443/ca.crt"
'@ | Set-Content -LiteralPath $hostsFile -Encoding utf8
foreach($node in $nodes) {
    docker inspect $node --format '{{.Name}}' | Out-Null
    Assert-NativeSuccess "Find $node"
    docker exec $node sh -c "grep -q 'harbor.platform.test' /etc/hosts || echo '$gateway harbor.platform.test' >> /etc/hosts"
    Assert-NativeSuccess "Configure Harbor address on $node"
    docker exec $node mkdir -p /etc/containerd/certs.d/harbor.platform.test:30443
    Assert-NativeSuccess "Create registry trust directory on $node"
    docker cp $certificate "${node}:/etc/containerd/certs.d/harbor.platform.test:30443/ca.crt"
    Assert-NativeSuccess "Install Harbor CA on $node"
    docker cp $hostsFile "${node}:/etc/containerd/certs.d/harbor.platform.test:30443/hosts.toml"
    Assert-NativeSuccess "Configure Harbor pull host on $node"
    $runtimeConfig=docker exec $node cat /etc/containerd/config.toml
    Assert-NativeSuccess "Read containerd configuration on $node"
    if(($runtimeConfig -join "`n") -notmatch 'config_path\s*=\s*"/etc/containerd/certs.d"') {
        @'

[plugins."io.containerd.grpc.v1.cri".registry]
  config_path = "/etc/containerd/certs.d"
'@ | docker exec -i $node tee -a /etc/containerd/config.toml | Out-Null
        Assert-NativeSuccess "Enable registry trust directory on $node"
        docker exec $node systemctl restart containerd
        Assert-NativeSuccess "Restart containerd on $node"
    }
}
Write-Output 'Drain lab nodes use the project-local Harbor CA and resolve harbor.platform.test through the kind gateway.'
