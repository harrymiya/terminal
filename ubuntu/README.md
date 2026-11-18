# Remote Dev Terminal · Ubuntu

Ubuntu 桌面版与 Omarchy 版功能一致：管理 SSH 主机和工作区、配置 Agent、使用标签或平铺终端、断线重连，以及显示支持的 Agent 额度。

标签或平铺卡片上的图片按钮支持选择本地图片或粘贴图片；上传后会将远端路径发送给对应 Agent。发送前请确认 Agent 正在等待输入且输入框为空。图片保存在远端 `~/.remote-dev-terminal/images/`，需自行清理；转换后的图片最大 20 MB。

## 安装

需要 Ubuntu 图形桌面和 Python 3。在终端进入此目录后运行：

```bash
sudo apt update
sudo apt install python3 python3-gi gir1.2-gtk-3.0 gir1.2-vte-2.91 openssh-client libsecret-tools gnome-keyring
./install.sh
```

从应用菜单启动 **Remote Dev Terminal**，或直接运行 `python3 remote-dev-terminal.py`。安装和配置均属于当前用户；配置位于 `~/.config/remote-dev-terminal/config.json`（可用 `XDG_CONFIG_HOME` 覆盖）。

远端需要 `tmux` 才能在断线后恢复会话，浏览目录需要 GNU `find`。主机配置可选保存 SSH 密码，密码存入当前用户的系统密钥环；需要可用且已解锁的 Secret Service。SSH 密钥和主机指纹由系统 OpenSSH 处理。

如果提示无法访问系统密钥环，请先运行 `sudo apt install libsecret-tools gnome-keyring`，然后退出并重新登录 Ubuntu 图形桌面，再按提示解锁登录密钥环。

## MCP 服务

本端内置 MCP 服务，可供其他 Agent 像人一样操作并实时同步显示在界面上，接入与操作说明见 [MCP使用说明.md](MCP使用说明.md)。

## 共享会话

在系统设置中填写同步服务地址，并使用账号和密码登录。管理员可在系统设置的「用户维护」中管理账号。同步在后台每 5 秒自动进行，不需要手动刷新。编辑主机时可填写「多端同步标识」；同一台主机在各设备上填写相同值后，会优先按此标识同步。留空时按 SSH 主机密钥识别，无法获取时按地址识别；会话按 SSH 用户名区分。修改标识会切换云端同步记录分组。工作区、Agent、主机资料和会话数据按登录账号隔离。SSH 凭据和终端内容不会上传；远端 tmux 会话仍须存在。服务部署说明及初始账号见[同步服务说明](../backend/README.md)。

终端支持 `Ctrl+Shift+C` 复制、`Ctrl+Shift+V` 粘贴；tmux 接管鼠标时按住 Shift 拖选。agy 会话也支持 `Ctrl+V` 粘贴文字。

更新时替换本目录并重新运行 `./install.sh`；配置不会被删除。本版本尚未在真实 Ubuntu 图形桌面和远端 SSH 主机上完成端到端验证。
