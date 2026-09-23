. "$PSScriptRoot/common.ps1"
Import-PlatformEnvironment
Invoke-WebRequest -Uri 'http://localhost:18080/v3/api-docs' -Headers @{Authorization="Bearer $env:LOCAL_TOKEN"} -OutFile "$root/docs/openapi.json"
Write-Output 'Exported authenticated OpenAPI contract to docs/openapi.json.'
