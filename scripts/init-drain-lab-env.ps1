. "$PSScriptRoot/common.ps1"
$destination=Join-Path $root '.local/drain-lab.env'
if(Test-Path -LiteralPath $destination) { Write-Output 'Existing ignored drain lab environment preserved.'; return }
$source=Join-Path $root '.local/platform.env'
if(!(Test-Path -LiteralPath $source)) { throw 'Run scripts/init-local.ps1 first' }
$settings=@{}
Get-Content -LiteralPath $source | ForEach-Object {
    if($_ -match '^([^#=]+)=(.*)$') { $settings[$Matches[1]]=$Matches[2] }
}
foreach($name in @('HARBOR_URL','HARBOR_USER','HARBOR_PASSWORD')) {
    if([string]::IsNullOrWhiteSpace($settings[$name])) { throw "Missing $name in existing local environment" }
}
function New-Secret { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
$lines=@(
    'AUTH_MODE=demo'
    "LOCAL_TOKEN=$(New-Secret)"
    "ENCRYPTION_KEY=$(New-Secret)"
    "DATABASE_PASSWORD=$(New-Secret)"
    "HARBOR_URL=$($settings['HARBOR_URL'])"
    "HARBOR_USER=$($settings['HARBOR_USER'])"
    "HARBOR_PASSWORD=$($settings['HARBOR_PASSWORD'])"
    'CPU_TEST=true'
    'CALLER_SUBJECT=drain-operator'
    'PROJECT_ID=drain-lab'
    'WORKLOAD_NAMESPACE=platform-workloads'
    'CLUSTER_ID=drain-kind'
    'NODE_MAINTENANCE_NODES=gpu-platform-drain-worker'
    'NODE_DRAIN_ENABLED=false'
)
[IO.File]::WriteAllLines($destination,$lines,[Text.UTF8Encoding]::new($false))
Write-Output 'Isolated drain lab credentials saved under ignored .local/; values not printed.'
