param(
    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string]$Distro = "Ubuntu",
    [switch]$Resume,
    [string]$ExpectedWindowsSid
)

$ErrorActionPreference = "Stop"
$script:WslExe = Join-Path $env:SystemRoot "System32\wsl.exe"
$script:ResumeValueName = "RemoteDevTerminalInstallResume"

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-InstalledDistros {
    if (-not (Test-Path -LiteralPath $script:WslExe)) { return @() }
    $items = @(& $script:WslExe --list --quiet 2>$null)
    if ($LASTEXITCODE -ne 0) { return @() }
    return @($items | ForEach-Object { (($_ -replace "`0", "").Trim()) } | Where-Object { $_ })
}

function Start-ElevatedInstaller {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $args = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" -Distro "' + $Distro + '" -ExpectedWindowsSid "' + $sid + '"'
    Write-Host "需要管理员权限来启用或更新 WSL，接下来会显示 Windows 权限提示。"
    $process = Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $args -Wait -PassThru
    exit $process.ExitCode
}

function Register-ResumeAfterRestart {
    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" -Distro "' + $Distro + '" -Resume'
    New-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\RunOnce" -Name $script:ResumeValueName -Value $command -PropertyType String -Force | Out-Null
    Write-Host "已设置登录后自动续装。"
}

function Ask-RestartToContinue {
    Add-Type -AssemblyName System.Windows.Forms
    $choice = [System.Windows.Forms.MessageBox]::Show(
        "WSL 需要重启 Windows 才能完成启用。安装器已设置为下次登录自动继续。现在重启吗？",
        "Remote Dev Terminal 安装",
        [System.Windows.Forms.MessageBoxButtons]::YesNo,
        [System.Windows.Forms.MessageBoxIcon]::Information,
        [System.Windows.Forms.MessageBoxDefaultButton]::Button2
    )
    if ($choice -eq [System.Windows.Forms.DialogResult]::Yes) {
        Restart-Computer
    } else {
        Write-Host "请稍后重启 Windows；登录后安装会自动继续。也可以重新双击 install.bat 继续。"
    }
}

function Invoke-WslInstall {
    $output = @(& $script:WslExe --install --distribution $Distro --no-launch 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }

    if ($exitCode -ne 0) {
        $details = ($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
        if ($details -match '(?i)restart|reboot|重启|重新启动') {
            Register-ResumeAfterRestart
            Ask-RestartToContinue
            exit 0
        }
        if (-not (Test-IsAdministrator)) { Start-ElevatedInstaller }
        throw "WSL/Ubuntu 安装失败（退出码 $exitCode）。$([Environment]::NewLine)$details"
    }

    if ((Get-InstalledDistros) -notcontains $Distro) {
        Register-ResumeAfterRestart
        Ask-RestartToContinue
        exit 0
    }
}

function Invoke-WslUpdate {
    $output = @(& $script:WslExe --update 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        $details = ($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
        if ($details -match '(?i)restart|reboot|重启|重新启动') {
            Register-ResumeAfterRestart
            Ask-RestartToContinue
            exit 0
        }
        if (-not (Test-IsAdministrator)) { Start-ElevatedInstaller }
        throw "WSL 更新失败（退出码 $exitCode）。$([Environment]::NewLine)$details"
    }
    & $script:WslExe --shutdown
}

try {
    if ($Resume) { Write-Host "正在从 Windows 重启后继续安装 ..." }
    if (-not (Test-Path -LiteralPath $script:WslExe)) {
        throw "找不到 wsl.exe。请确认 Windows 11 已启用 Windows Subsystem for Linux。"
    }

    $os = Get-CimInstance -ClassName Win32_OperatingSystem
    if ([int]$os.BuildNumber -lt 22000) {
        throw "Remote Dev Terminal Windows 版需要 Windows 11（系统版本 22000 或更新），当前系统版本为 $($os.Caption) $($os.BuildNumber)。"
    }

    $installRoot = Join-Path $env:LOCALAPPDATA "RemoteDevTerminal"
    $installerDir = Join-Path $installRoot "installer"
    $stableScript = Join-Path $installerDir "install.ps1"
    $currentDirectory = (Resolve-Path -LiteralPath $PSScriptRoot).Path.TrimEnd('\')
    $stableDirectory = [IO.Path]::GetFullPath($installerDir).TrimEnd('\')

    if (-not [string]::Equals($currentDirectory, $stableDirectory, [StringComparison]::OrdinalIgnoreCase)) {
        New-Item -ItemType Directory -Force -Path $installerDir | Out-Null
        Get-ChildItem -LiteralPath $PSScriptRoot -File | Copy-Item -Destination $installerDir -Force
        $args = '-NoProfile -ExecutionPolicy Bypass -File "' + $stableScript + '" -Distro "' + $Distro + '"'
        $process = Start-Process -FilePath "powershell.exe" -ArgumentList $args -Wait -PassThru
        exit $process.ExitCode
    }

    if ($ExpectedWindowsSid) {
        $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        if ($currentSid -ne $ExpectedWindowsSid) {
            throw "Windows UAC 使用了另一个管理员账户。请使用当前登录账户的管理员权限运行安装器，这样 Ubuntu 才会安装到当前用户。"
        }
    }

    $distros = Get-InstalledDistros
    if ($distros -notcontains $Distro) {
        Write-Host "正在检查并安装 WSL 发行版 $Distro ..."
        Invoke-WslInstall
    }

    Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\RunOnce" -Name $script:ResumeValueName -ErrorAction SilentlyContinue

    Write-Host "正在确保 $Distro 使用 WSL 2 ..."
    $versionOutput = @(& $script:WslExe --set-version $Distro 2 2>&1)
    $versionExitCode = $LASTEXITCODE
    $versionOutput | ForEach-Object { Write-Host $_ }
    if ($versionExitCode -ne 0) {
        $details = ($versionOutput | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
        throw "无法将 $Distro 配置为 WSL 2。请检查 BIOS 虚拟化和 Windows 虚拟机平台。$([Environment]::NewLine)$details"
    }

    $uid = @(& $script:WslExe --distribution $Distro --exec bash -lc 'id -u' 2>&1)
    $uidExitCode = $LASTEXITCODE
    $uidValue = (($uid | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ } | Select-Object -Last 1))
    if ($uidExitCode -ne 0 -or $uidValue -eq "0") {
        Write-Host "首次启动 $Distro 时，需要创建 Linux 用户名和密码。请完成提示后，在打开的 Linux 终端输入 exit；安装会自动继续。"
        $launcherNames = @("ubuntu.exe", "ubuntu2404.exe", "ubuntu2204.exe", "ubuntu2004.exe")
        $launcher = $null
        foreach ($launcherName in $launcherNames) {
            $candidate = Get-Command $launcherName -ErrorAction SilentlyContinue
            if ($candidate) { $launcher = $candidate.Source; break }
        }
        if ($launcher) {
            & $launcher
        } else {
            Write-Host "未找到 $Distro 启动器。请从开始菜单打开 $Distro，完成 Linux 用户初始化并关闭该窗口，然后回到这里按 Enter。"
            Read-Host "完成后按 Enter 继续"
        }
        if ($launcher -and $LASTEXITCODE -ne 0) { throw "无法完成 $Distro 首次启动（退出码 $LASTEXITCODE）。" }
        $uid = @(& $script:WslExe --distribution $Distro --exec bash -lc 'id -u' 2>&1)
        if ($LASTEXITCODE -ne 0) {
            $details = ($uid | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
            throw "无法启动 $Distro。$([Environment]::NewLine)$details"
        }
        $uidValue = (($uid | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ } | Select-Object -Last 1))
        if ($uidValue -eq "0") {
            throw "$Distro 当前仍以 root 作为默认用户。请先在 Ubuntu 中创建普通用户，再重新运行 install.bat。"
        }
    }

    Write-Host "正在检查 WSL 图形支持 ..."
    $wslg = @(& $script:WslExe --distribution $Distro --exec bash -lc 'test -d /mnt/wslg && echo ready' 2>&1)
    $wslgExitCode = $LASTEXITCODE
    if ($wslgExitCode -ne 0 -or (($wslg -join "") -notmatch "ready")) {
        Write-Host "当前 WSL 尚无 WSLg，正在尝试更新 WSL ..."
        Invoke-WslUpdate
        $wslg = @(& $script:WslExe --distribution $Distro --exec bash -lc 'test -d /mnt/wslg && echo ready' 2>&1)
        if ($LASTEXITCODE -ne 0 -or (($wslg -join "") -notmatch "ready")) {
            throw "更新后仍未检测到 WSLg。请运行 wsl --update 并确认 Windows 11 支持 Linux 图形应用。"
        }
    }

    $linuxSource = @(& $script:WslExe --distribution $Distro --exec wslpath -u $PSScriptRoot)
    if ($LASTEXITCODE -ne 0 -or -not $linuxSource) {
        throw "WSL 无法访问安装目录：$PSScriptRoot。请把 windows 文件夹放在本机磁盘后重试。"
    }
    $linuxSource = ([string]($linuxSource | Select-Object -First 1)).Trim()

    Write-Host "正在安装 Remote Dev Terminal 及所需 Linux 组件 ..."
    & $script:WslExe --distribution $Distro --exec bash "$linuxSource/install-wsl.sh"
    if ($LASTEXITCODE -ne 0) {
        throw "Linux 组件安装失败（退出码 $LASTEXITCODE）。请确认网络可用，并按提示输入 Ubuntu 用户密码后重试。"
    }

    New-Item -ItemType Directory -Force -Path $installRoot | Out-Null
    Copy-Item (Join-Path $PSScriptRoot "launch.ps1") (Join-Path $installRoot "launch.ps1") -Force
    @{ distro = $Distro } | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $installRoot "settings.json")

    $programs = [Environment]::GetFolderPath("Programs")
    $shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $programs "Remote Dev Terminal.lnk"))
    $shortcut.TargetPath = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
    $shortcut.Arguments = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + (Join-Path $installRoot "launch.ps1") + '"'
    $shortcut.WorkingDirectory = $installRoot
    $shortcut.Save()

    Write-Host "安装完成。正在启动 Remote Dev Terminal ..."
    $launchArgs = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + (Join-Path $installRoot "launch.ps1") + '"'
    Start-Process -FilePath "powershell.exe" -ArgumentList $launchArgs -WindowStyle Hidden
    Write-Host "以后可从开始菜单打开 Remote Dev Terminal。"
} catch {
    Write-Host "`n安装未完成：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "解决提示：确认网络可用；若刚启用 WSL，请重启 Windows 后重新双击 install.bat。"
    Read-Host "按 Enter 关闭"
    exit 1
}
