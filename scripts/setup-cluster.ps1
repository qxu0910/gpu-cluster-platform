. "$PSScriptRoot/common.ps1"
& "$PSScriptRoot/init-local.ps1"
Import-PlatformEnvironment
$local = Join-Path $root '.local'
$tools = Join-Path $root '.tools'
New-Item -ItemType Directory -Force $tools | Out-Null
docker info --format '{{.OSType}}'
Assert-NativeSuccess 'Docker Linux engine check'
if (!(Test-Path "$tools/kind.exe")) { Invoke-WebRequest 'https://github.com/kubernetes-sigs/kind/releases/download/v0.30.0/kind-windows-amd64' -OutFile "$tools/kind.exe" -TimeoutSec 120 }
if (!(Test-Path "$tools/windows-amd64/helm.exe")) {
    Invoke-WebRequest 'https://get.helm.sh/helm-v3.18.6-windows-amd64.zip' -OutFile "$tools/helm.zip" -TimeoutSec 120
    Expand-Archive "$tools/helm.zip" $tools -Force
}
$kind = "$tools/kind.exe"
$helm = "$tools/windows-amd64/helm.exe"
$env:KUBECONFIG = "$local/kubeconfig"
$clusters = & $kind get clusters
Assert-NativeSuccess 'List kind clusters'
if ($clusters -notcontains 'gpu-platform') {
    & $kind create cluster --name gpu-platform --config "$root/deploy/kind.yml" --kubeconfig $env:KUBECONFIG --wait 180s
    Assert-NativeSuccess 'Create kind cluster'
} else {
    & $kind export kubeconfig --name gpu-platform --kubeconfig $env:KUBECONFIG
    Assert-NativeSuccess 'Export project kubeconfig'
}
foreach ($namespace in @('harbor','platform-workloads')) {
    kubectl --context kind-gpu-platform create namespace $namespace --dry-run=client -o yaml | kubectl --context kind-gpu-platform apply -f -
    Assert-NativeSuccess "Create $namespace namespace"
}
$openssl = 'C:/Program Files/Git/usr/bin/openssl.exe'
if (!(Test-Path "$local/harbor.crt")) {
    & $openssl req -x509 -newkey rsa:3072 -sha256 -days 30 -nodes -keyout "$local/harbor.key" -out "$local/harbor.crt" -subj '/CN=harbor.platform.test' -addext 'subjectAltName=DNS:harbor.platform.test' -addext 'basicConstraints=critical,CA:TRUE'
    Assert-NativeSuccess 'Generate local Harbor certificate'
}
kubectl --context kind-gpu-platform -n harbor create secret tls harbor-tls --cert="$local/harbor.crt" --key="$local/harbor.key" --dry-run=client -o yaml | kubectl --context kind-gpu-platform apply -f -
Assert-NativeSuccess 'Install Harbor certificate'
[IO.File]::WriteAllText("$local/harbor-admin.txt",$env:HARBOR_PASSWORD)
kubectl --context kind-gpu-platform -n harbor create secret generic harbor-admin --from-file="password=$local/harbor-admin.txt" --dry-run=client -o yaml | kubectl --context kind-gpu-platform apply -f -
Assert-NativeSuccess 'Install Harbor admin secret'
Remove-Item -LiteralPath "$local/harbor-admin.txt"
& $helm repo add harbor https://helm.goharbor.io
Assert-NativeSuccess 'Add Harbor chart repository'
& $helm repo update harbor
Assert-NativeSuccess 'Update Harbor chart index'
& $helm upgrade --install harbor harbor/harbor --version 1.18.0 --namespace harbor --kube-context kind-gpu-platform -f "$root/deploy/harbor-values.yml" --wait --timeout 10m
Assert-NativeSuccess 'Deploy Harbor'
$gateway = docker inspect gpu-platform-control-plane --format '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}'
Assert-NativeSuccess 'Find kind network gateway'
if ($gateway -notmatch '^\d+\.\d+\.\d+\.\d+$') { throw 'Invalid kind gateway' }
docker exec gpu-platform-control-plane sh -c "grep -q 'harbor.platform.test' /etc/hosts || echo '$gateway harbor.platform.test' >> /etc/hosts"
Assert-NativeSuccess 'Configure node registry resolution'
docker exec gpu-platform-control-plane mkdir -p /etc/containerd/certs.d/harbor.platform.test:30443
docker cp "$local/harbor.crt" gpu-platform-control-plane:/etc/containerd/certs.d/harbor.platform.test:30443/ca.crt
Assert-NativeSuccess 'Install node registry CA'
@'
server = "https://harbor.platform.test:30443"
[host."https://harbor.platform.test:30443"]
  capabilities = ["pull", "resolve"]
  ca = "/etc/containerd/certs.d/harbor.platform.test:30443/ca.crt"
'@ | Set-Content "$local/hosts.toml" -Encoding utf8
docker cp "$local/hosts.toml" gpu-platform-control-plane:/etc/containerd/certs.d/harbor.platform.test:30443/hosts.toml
Assert-NativeSuccess 'Configure containerd registry'
$runtimeConfig=docker exec gpu-platform-control-plane cat /etc/containerd/config.toml
Assert-NativeSuccess 'Read containerd configuration'
if(($runtimeConfig -join "`n") -notmatch 'config_path\s*=\s*"/etc/containerd/certs.d"') {
    @'

[plugins."io.containerd.grpc.v1.cri".registry]
  config_path = "/etc/containerd/certs.d"
'@ | docker exec -i gpu-platform-control-plane tee -a /etc/containerd/config.toml | Out-Null
    Assert-NativeSuccess 'Enable containerd registry trust directory'
    docker exec gpu-platform-control-plane systemctl restart containerd
    Assert-NativeSuccess 'Reload test container runtime'
}
if (!(Test-Path "$local/truststore.p12")) {
    & "$env:JAVA_HOME/bin/keytool.exe" -importcert -noprompt -alias local-harbor -file "$local/harbor.crt" -keystore "$local/truststore.p12" -storepass changeit
    Assert-NativeSuccess 'Create local Java trust store'
}
# Container-only kubeconfig uses the API server's existing internal certificate identity.
& $kind get kubeconfig --name gpu-platform --internal | Set-Content "$local/kubeconfig-container" -Encoding utf8
Assert-NativeSuccess 'Export container kubeconfig'
Write-Output 'Cluster and Harbor ready. Container DNS is configured without changing Windows hosts. Host curl requests use --resolve harbor.platform.test:30443:127.0.0.1. Run scripts/run-local.ps1 next.'
