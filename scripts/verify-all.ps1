[CmdletBinding()]
param(
    [switch] $E2E,
    [switch] $NoE2E,
    # Hardware-free gateway E2E (MySQL + backend + smart-insole-ble-gateway CLI required). Default: skip.
    [switch] $GatewayE2E
)

$ErrorActionPreference = 'Stop'
$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepositoryRoot = Split-Path -Parent $ScriptDirectory

function Find-Python {
    $Python = Get-Command python -ErrorAction SilentlyContinue
    if ($null -ne $Python) {
        return [PSCustomObject]@{ Executable = $Python.Source; Prefix = @() }
    }
    $Launcher = Get-Command py -ErrorAction SilentlyContinue
    if ($null -ne $Launcher) {
        return [PSCustomObject]@{ Executable = $Launcher.Source; Prefix = @('-3') }
    }
    throw 'Python 3 is required.'
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string] $Label,
        [Parameter(Mandatory = $true)][scriptblock] $Action
    )
    Write-Host "[RUN] $Label"
    & $Action
    $Code = $LASTEXITCODE
    if ($Code -ne 0) {
        throw "$Label failed with exit code $Code"
    }
    Write-Host "[PASS] $Label"
}

try {
    $PythonCommand = Find-Python
    $PythonExecutable = $PythonCommand.Executable
    $PythonPrefix = @($PythonCommand.Prefix)
    Invoke-Checked 'contracts and fixtures' {
        & $PythonExecutable @PythonPrefix (Join-Path $ScriptDirectory 'validate_contracts.py')
    }
    Invoke-Checked 'integration helper unit tests' {
        & $PythonExecutable @PythonPrefix -m unittest discover -s $ScriptDirectory -p 'test_*.py'
    }

    $BackendDirectory = Join-Path $RepositoryRoot 'backend'
    if (-not (Test-Path -LiteralPath $BackendDirectory -PathType Container)) {
        throw "Required backend directory is missing: $BackendDirectory"
    }
    Push-Location $BackendDirectory
    try {
        if (Test-Path -LiteralPath 'gradlew.bat' -PathType Leaf) {
            Invoke-Checked 'backend tests' { & .\gradlew.bat test }
            Invoke-Checked 'backend checks' { & .\gradlew.bat check }
        } elseif (Test-Path -LiteralPath 'gradlew' -PathType Leaf) {
            $Shell = Get-Command bash -ErrorAction SilentlyContinue
            if ($null -eq $Shell) {
                $Shell = Get-Command sh -ErrorAction SilentlyContinue
            }
            if ($null -eq $Shell) {
                throw 'backend/gradlew exists, but neither bash nor sh is available.'
            }
            Invoke-Checked 'backend tests' { & $Shell.Source './gradlew' test }
            Invoke-Checked 'backend checks' { & $Shell.Source './gradlew' check }
        } else {
            throw 'Required Gradle wrapper is missing: backend/gradlew or backend/gradlew.bat'
        }
    } finally {
        Pop-Location
    }

    $FrontendDirectory = Join-Path $RepositoryRoot 'frontend'
    if (-not (Test-Path -LiteralPath (Join-Path $FrontendDirectory 'package.json') -PathType Leaf)) {
        throw 'Required frontend/package.json is missing.'
    }
    $Lockfiles = @('package-lock.json', 'pnpm-lock.yaml', 'yarn.lock') | Where-Object {
        Test-Path -LiteralPath (Join-Path $FrontendDirectory $_) -PathType Leaf
    }
    if ($Lockfiles.Count -ne 1) {
        throw 'Expected exactly one frontend lockfile (package-lock.json, pnpm-lock.yaml, or yarn.lock).'
    }
    Push-Location $FrontendDirectory
    try {
        switch ($Lockfiles[0]) {
            'package-lock.json' {
                $PackageManager = Get-Command npm.cmd -ErrorAction SilentlyContinue
                if ($null -eq $PackageManager) { $PackageManager = Get-Command npm -ErrorAction SilentlyContinue }
                if ($null -eq $PackageManager) { throw 'npm is required.' }
                Invoke-Checked 'frontend OpenAPI type check (api:check)' { & $PackageManager.Source run api:check }
                Invoke-Checked 'frontend lint' { & $PackageManager.Source run lint }
                Invoke-Checked 'frontend tests' { & $PackageManager.Source run test -- --run }
                Invoke-Checked 'frontend build' { & $PackageManager.Source run build }
            }
            'pnpm-lock.yaml' {
                $PackageManager = Get-Command pnpm.cmd -ErrorAction SilentlyContinue
                if ($null -eq $PackageManager) { $PackageManager = Get-Command pnpm -ErrorAction SilentlyContinue }
                if ($null -eq $PackageManager) { throw 'pnpm is required.' }
                Invoke-Checked 'frontend OpenAPI type check (api:check)' { & $PackageManager.Source run api:check }
                Invoke-Checked 'frontend lint' { & $PackageManager.Source run lint }
                Invoke-Checked 'frontend tests' { & $PackageManager.Source run test -- --run }
                Invoke-Checked 'frontend build' { & $PackageManager.Source run build }
            }
            'yarn.lock' {
                $PackageManager = Get-Command yarn.cmd -ErrorAction SilentlyContinue
                if ($null -eq $PackageManager) { $PackageManager = Get-Command yarn -ErrorAction SilentlyContinue }
                if ($null -eq $PackageManager) { throw 'yarn is required.' }
                Invoke-Checked 'frontend OpenAPI type check (api:check)' { & $PackageManager.Source run api:check }
                Invoke-Checked 'frontend lint' { & $PackageManager.Source run lint }
                Invoke-Checked 'frontend tests' { & $PackageManager.Source run test --run }
                Invoke-Checked 'frontend build' { & $PackageManager.Source run build }
            }
        }
    } finally {
        Pop-Location
    }

    $RunE2E = $E2E -or ($env:RUN_E2E -match '^(1|true|yes|on)$')
    if ($NoE2E) {
        $RunE2E = $false
    }
    if ($RunE2E) {
        Invoke-Checked 'optional API E2E smoke' {
            & $PythonExecutable @PythonPrefix (Join-Path $ScriptDirectory 'e2e_smoke.py')
        }
    } else {
        Write-Host '[SKIP] Optional API E2E smoke is disabled. Use -E2E or RUN_E2E=1 to enable it.'
    }
    $RunGatewayE2E = $GatewayE2E -or ($env:RUN_GATEWAY_E2E -match '^(1|true|yes|on)$')
    if ($RunGatewayE2E) {
        Invoke-Checked 'optional gateway mock E2E' {
            & $PythonExecutable @PythonPrefix (Join-Path $ScriptDirectory 'e2e_gateway_mock.py')
        }
    } else {
        Write-Host '[SKIP] Optional gateway mock E2E is disabled. Use -GatewayE2E or RUN_GATEWAY_E2E=1 (needs MySQL, backend and the gateway CLI).'
    }
    Write-Host '[PASS] All required verification stages completed.'
    exit 0
} catch {
    Write-Error "[FAIL] $($_.Exception.Message)"
    exit 1
}
