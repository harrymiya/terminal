# Remote Dev Terminal · Omarchy

完整中文安装及使用步骤见 [安装说明.md](安装说明.md)。

这是与 Android 版并列的 Linux 桌面版本，适用于基于 Arch Linux 的 Omarchy。使用 GTK 3、VTE 和系统 OpenSSH，支持管理主机、浏览远端目录、配置命令预设和多个终端标签。SSH 连接意外中断时自动重连；远端安装 tmux 时可恢复原会话。标签显示工作区和 Agent 名称；Codex 和 Antigravity 还依次显示 5 小时及每周剩余额度的百分比，暂不可用的额度显示为「--」。

工作区默认以标签显示终端，点击右上角「平铺模式」可同时查看最多 8 个终端（最多 4 列、2 行），再点击「标签模式」可切回。左上角「菜单」可收起或展开左侧管理栏；切换布局不会主动断开终端。

左侧点击主机后选择该主机的 Agent，点击工作区名称即可直达终端；已打开的相同主机、目录和 Agent 会回到原会话。每台主机的工作区和 Agent 可以分别维护。

右上角的百分比选项可输入或以 1% 为步长调整界面和终端文字大小，范围为 50%–200%，设置会自动保存。

## 安装和启动

```bash
sudo pacman -S --needed python python-gobject gtk3 vte3 openssh
./omarchy/install.sh
```

在应用菜单中启动 **Remote Dev Terminal**。也可以从仓库直接运行：

```bash
python3 omarchy/remote-dev-terminal.py
```

配置保存在 `${XDG_CONFIG_HOME:-~/.config}/remote-dev-terminal/config.json`。认证和主机密钥检查交给系统 OpenSSH；首次连接时确认主机指纹，并根据提示输入密码或密钥口令。推荐使用 `ssh-keygen` 和 `ssh-copy-id` 配置密钥。目录浏览需要远端提供 GNU find；密码和指纹确认会通过 SSH 提示窗口进行。远端需安装 tmux 才能保留断线会话。关闭标签或应用后，当前版本没有恢复旧标签的界面；远端 tmux 会话不会被自动删除。

自动回车默认关闭，可在侧栏启用并设置 1–30 秒延迟。它会自动确认匹配的肯定选项或回车确认提示，请仅在信任远端命令时启用。桌面版使用独立配置，不能自动导入 Android 密码、密钥或会话。
