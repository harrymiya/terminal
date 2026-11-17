# Remote Dev Terminal · Windows

此版本通过 **WSL 2 + Ubuntu + WSLg** 运行桌面界面，并提供 Windows 开始菜单入口；它不是原生 Windows 可执行文件。需要 Windows 11、可用的 WSLg 图形支持，以及能够在 WSL Ubuntu 中使用 `sudo` 的用户。SSH 客户端、SSH 密钥和配置位于 WSL 中。

标签或平铺卡片上的图片按钮支持选择本地图片或粘贴图片；Windows 文件通常可从 `/mnt/c` 等路径选择。上传后会将远端路径发送给对应 Agent，发送前请确认 Agent 正在等待输入且输入框为空。图片保存在远端 `~/.remote-dev-terminal/images/`，需自行清理；转换后的图片最大 20 MB。

## 安装

如尚未安装 WSL，在管理员 PowerShell 中运行 `wsl --install -d Ubuntu`，按提示重启并完成 Ubuntu 用户初始化。更新 WSL 后确认 Linux 图形应用可运行。将整个 `windows` 文件夹复制到本机，然后在普通 PowerShell 中进入该文件夹：

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

脚本会在 Ubuntu 内请求 sudo 密码、安装 GTK/VTE 和 OpenSSH，然后为当前用户创建 **Remote Dev Terminal** 开始菜单快捷方式。若发行版名称不是 `Ubuntu`，先通过 `wsl -l -v` 查看名称，再使用 `-Distro "发行版名称"` 参数。整个安装目录必须可从 WSL 访问（例如 Windows 本地磁盘）。

更新时替换整个 `windows` 文件夹并重新运行安装命令。配置保存在所选 WSL 发行版的 `~/.config/remote-dev-terminal/config.json`，更新不会删除配置。主机配置可选保存 SSH 密码，密码存入 WSL 中当前用户的系统密钥环，需要可用且已解锁的 Secret Service。远端需安装 tmux 才能在断线后恢复会话；目录浏览需要 GNU find。关闭标签或程序后，目前不能从界面恢复旧标签。

本版本尚未在真实 Windows/WSLg 环境和远端 SSH 主机上完成端到端验证。
