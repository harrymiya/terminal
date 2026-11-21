$ErrorActionPreference = 'Stop'
$app = Join-Path $env:LOCALAPPDATA 'Programs\RemoteDevTerminal\RemoteDevTerminal.exe'
if (-not (Test-Path -LiteralPath $app)) {
    throw 'Remote Dev Terminal is not installed. Run windows\install.bat first.'
}
Start-Process -FilePath $app -WorkingDirectory (Split-Path -Parent $app)
