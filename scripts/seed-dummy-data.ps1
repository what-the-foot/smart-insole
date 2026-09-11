[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $ReceiverArguments
)

$ErrorActionPreference = 'Stop'
$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = Get-Command python -ErrorAction SilentlyContinue
if ($null -ne $Python) {
    & $Python.Source (Join-Path $ScriptDirectory 'seed_dummy_data.py') @ReceiverArguments
} else {
    $PythonLauncher = Get-Command py -ErrorAction SilentlyContinue
    if ($null -eq $PythonLauncher) {
        Write-Error 'Python 3 is required.'
        exit 2
    }
    & $PythonLauncher.Source -3 (Join-Path $ScriptDirectory 'seed_dummy_data.py') @ReceiverArguments
}
exit $LASTEXITCODE
