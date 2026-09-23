$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
function Import-PlatformEnvironment {
    Get-Content (Join-Path $root '.local/platform.env') | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process') }
    }
}
function Assert-NativeSuccess([string]$operation) { if ($LASTEXITCODE -ne 0) { throw "$operation failed with exit code $LASTEXITCODE" } }
