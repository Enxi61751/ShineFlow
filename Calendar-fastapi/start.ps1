param(
    [switch]$Reload
)

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonPath = Join-Path $projectRoot "..\.venv311\Scripts\python.exe"
$pythonPath = [System.IO.Path]::GetFullPath($pythonPath)

if (!(Test-Path $pythonPath)) {
    Write-Error "Python 3.11 virtual environment not found: $pythonPath"
    exit 1
}

Push-Location $projectRoot
try {
    $oldReload = $env:APP_RELOAD
    $env:APP_RELOAD = "false"
    if ($Reload) {
        $env:APP_RELOAD = "true"
    }

    & $pythonPath "run.py"
}
finally {
    if ($null -eq $oldReload) {
        Remove-Item Env:APP_RELOAD -ErrorAction SilentlyContinue
    }
    else {
        $env:APP_RELOAD = $oldReload
    }
    Pop-Location
}
