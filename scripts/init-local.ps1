$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$local = Join-Path $root '.local'
New-Item -ItemType Directory -Force $local | Out-Null
$envPath = Join-Path $local 'platform.env'
if (!(Test-Path $envPath)) {
    function New-Secret { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
    @(
        'AUTH_MODE=local'
        "LOCAL_TOKEN=$(New-Secret)"
        "ENCRYPTION_KEY=$(New-Secret)"
        "DATABASE_PASSWORD=$(New-Secret)"
        "HARBOR_PASSWORD=$(New-Secret)"
        'HARBOR_USER=admin'
        'HARBOR_URL=https://harbor.platform.test:30443'
        'CPU_TEST=true'
        'CALLER_SUBJECT=local-operator'
        'PROJECT_ID=local'
        'WORKLOAD_NAMESPACE=platform-workloads'
        'CLUSTER_ID=local-kind'
    ) | Set-Content -LiteralPath $envPath -Encoding utf8
}
Write-Output 'Local credentials initialized in ignored .local/platform.env (values not printed).'
