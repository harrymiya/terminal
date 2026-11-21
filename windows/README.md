# Remote Dev Terminal · Windows

此版本通过 **WSL 2 + Ubuntu + WSLg** 运行桌面界面，并提供 Windows 开始菜单入口；它不是原生 Windows 可执行文件。需要 Windows 11、可用的 WSLg 图形支持，以及能够在 WSL Ubuntu 中使用 `sudo` 的用户。SSH 客户端、SSH 密钥和配置位于 WSL 中。

标签或平铺卡片上的图片按钮支持选择本地图片或粘贴图片；Windows 文件通常可从 `/mnt/c` 等路径选择。上传后会将远端路径发送给对应 Agent，发送前请确认 Agent 正在等待输入且输入框为空。图片保存在远端 `~/.remote-dev-terminal/images/`，需自行清理；转换后的图片最大 20 MB。

## 一键安装

1. 将整个 `windows` 文件夹解压到本机磁盘。
2. 双击 `install.bat`。安装器会检查 Windows 版本和 WSL，必要时请求管理员权限、安装 Ubuntu，并在需要重启时设置登录后续装。
3. 首次启动 Ubuntu 时，按提示创建 Linux 用户名和密码；进入 Linux 命令行后输入 `exit`，安装器会继续安装依赖、创建开始菜单快捷方式并启动应用。若 Ubuntu 没有自动打开，按安装器提示从开始菜单打开 Ubuntu，完成初始化后回到安装窗口继续。

安装过程需要网络。安装器不会自动重启电脑；若 WSL 要求重启，会询问是否现在重启，并在下次登录后自动继续。若取消重启，也可以稍后再次双击 `install.bat`。

也可以从 PowerShell 运行 `install.ps1`。默认安装 Ubuntu；如要使用已经安装的 Ubuntu 发行版，可运行 `powershell -ExecutionPolicy Bypass -File .\install.ps1 -Distro "发行版名称"`。安装目录会复制到 `%LOCALAPPDATA%\RemoteDevTerminal\installer`，因此安装完成后可以移动或删除解压目录。

更新时替换整个 `windows` 文件夹并重新运行安装命令。配置保存在所选 WSL 发行版的 `~/.config/remote-dev-terminal/config.json`，更新不会删除配置。主机配置可选保存 SSH 密码，密码存入 WSL 中当前用户的系统密钥环，需要可用且已解锁的 Secret Service。远端需安装 tmux 才能在断线后恢复会话；目录浏览需要 GNU find。关闭标签或程序后，目前不能从界面恢复旧标签。

本版本尚未在真实 Windows/WSLg 环境和远端 SSH 主机上完成端到端验证。
