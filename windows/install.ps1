param([string]$Distro = "Ubuntu")
$ErrorActionPreference = "Stop"
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) { throw "WSL is required. Install WSL with Ubuntu and WSLg first." }
$linuxSource = & wsl.exe --distribution $Distro --exec wslpath -u $PSScriptRoot
if ($LASTEXITCODE -ne 0 -or -not $linuxSource) { throw "Cannot access this folder from WSL distribution: $Distro" }
$linuxSource = ($linuxSource | Select-Object -First 1).Trim()
& wsl.exe --distribution $Distro --exec bash "$linuxSource/install-wsl.sh"
if ($LASTEXITCODE -ne 0) { throw "WSL installation failed (exit code $LASTEXITCODE)." }
$appDir = Join-Path $env:LOCALAPPDATA "RemoteDevTerminal"
New-Item -ItemType Directory -Force -Path $appDir | Out-Null
Copy-Item (Join-Path $PSScriptRoot "launch.ps1") (Join-Path $appDir "launch.ps1") -Force
@{ distro = $Distro } | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $appDir "settings.json")
$programs = [Environment]::GetFolderPath("Programs")
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $programs "Remote Dev Terminal.lnk"))
$shortcut.TargetPath = (Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe")
$shortcut.Arguments = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + (Join-Path $appDir "launch.ps1") + '"'
$shortcut.WorkingDirectory = $appDir
$shortcut.Save()
Write-Host "Installed. Launch Remote Dev Terminal from the Start menu."
