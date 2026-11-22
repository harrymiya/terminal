param(
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
$os = Get-CimInstance -ClassName Win32_OperatingSystem
if ([int]$os.BuildNumber -lt 22000) {
    throw "Remote Dev Terminal 原生版需要 Windows 11。当前系统版本：$($os.Caption) $($os.BuildNumber)。"
}

$sourceDir = Join-Path $PSScriptRoot 'native\publish'
$sourceExe = Join-Path $sourceDir 'RemoteDevTerminal.exe'
if (-not (Test-Path -LiteralPath $sourceExe)) {
    throw "未找到原生程序。请先从仓库根目录运行 windows\build.ps1，然后重新运行 install.bat。"
}
if (-not (Test-Path -LiteralPath (Join-Path $sourceDir 'OpenSSH\ssh.exe'))) {
    throw '安装包缺少内置 SSH 客户端。请重新运行 windows\build.ps1 后再次安装。'
}

$installDir = Join-Path $env:LOCALAPPDATA 'Programs\RemoteDevTerminal'
New-Item -ItemType Directory -Force -Path $installDir | Out-Null
if (Get-Process -Name 'RemoteDevTerminal' -ErrorAction SilentlyContinue) {
    throw 'Remote Dev Terminal 正在运行。请先关闭程序，再重新运行安装程序。'
}
Copy-Item -Path (Join-Path $sourceDir '*') -Destination $installDir -Recurse -Force
Get-ChildItem -LiteralPath $installDir -File -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -in @('.pdb', '.xml') } |
    Remove-Item -Force

$programs = [Environment]::GetFolderPath('Programs')
$shortcutPath = Join-Path $programs 'Remote Dev Terminal.lnk'
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcutPath)
$shortcut.TargetPath = Join-Path $installDir 'RemoteDevTerminal.exe'
$shortcut.IconLocation = "$($shortcut.TargetPath),0"
$shortcut.WorkingDirectory = $installDir
$shortcut.Description = 'Windows 原生 SSH 开发终端'
$shortcut.Save()

Write-Host "安装完成：$installDir"
Write-Host '开始菜单中已添加 Remote Dev Terminal。'
if (-not $NoLaunch) {
    Start-Process -FilePath (Join-Path $installDir 'RemoteDevTerminal.exe') -WorkingDirectory $installDir
}
