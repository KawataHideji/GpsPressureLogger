Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = Join-Path $root 'venv\Scripts\python.exe'
$viewer = Join-Path $root 'viewer_app.py'

if (-not (Test-Path $python)) {
    throw "Python venv が見つかりません: $python"
}

& $python $viewer @args
