param([switch]$DeleteDatabase)
. "$PSScriptRoot/common.ps1"
Import-PlatformEnvironment
$composeArgs = @('compose','--env-file',"$root/.local/platform.env",'-f',"$root/deploy/compose.yml",'--profile','app','down')
if ($DeleteDatabase) { $composeArgs += '--volumes' }
docker @composeArgs
Assert-NativeSuccess 'Stop platform'
& "$root/.tools/kind.exe" delete cluster --name gpu-platform
Assert-NativeSuccess 'Delete project test cluster'
