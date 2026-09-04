[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $SmokeArguments
)

$ErrorActionPreference = 'Stop'
$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = Get-Command python -ErrorAction SilentlyContinue
if ($null -ne $Python) {
    & $Python.Source (Join-Path $ScriptDirectory 'e2e_smoke.py') @SmokeArguments
} else {
    $PythonLauncher = Get-Command py -ErrorAction SilentlyContinue
    if ($null -eq $PythonLauncher) {
        Write-Error 'Python 3 is required.'
        exit 2
    }
    & $PythonLauncher.Source -3 (Join-Path $ScriptDirectory 'e2e_smoke.py') @SmokeArguments
}
exit $LASTEXITCODE
