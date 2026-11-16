$ErrorActionPreference = "Stop"
try {
    $settings = Get-Content -Raw (Join-Path $PSScriptRoot "settings.json") | ConvertFrom-Json
    & wsl.exe --distribution $settings.distro --exec bash -lc 'exec "${XDG_DATA_HOME:-$HOME/.local/share}/remote-dev-terminal/remote-dev-terminal.py"'
    if ($LASTEXITCODE -ne 0) { throw "WSL application exited with code $LASTEXITCODE." }
} catch {
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, "Remote Dev Terminal") | Out-Null
}
