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

## 共享会话

在每台设备上分别配置连接同一 SSH 主机的地址、端口和用户名，并在系统设置中填写相同的同步服务地址和令牌。侧栏显示同步状态，可用「刷新同步」重试。同步服务只保存会话信息，不同步主机配置、SSH 凭据或终端内容；远端 tmux 会话仍须存在。同步失败时检查地址、令牌和网络连接。

终端支持 `Ctrl+Shift+C` 复制、`Ctrl+Shift+V` 粘贴；tmux 接管鼠标时按住 Shift 拖选。agy 会话也支持 `Ctrl+V` 粘贴文字。

更新时替换本目录并重新运行 `./install.sh`；配置不会被删除。本版本尚未在真实 Ubuntu 图形桌面和远端 SSH 主机上完成端到端验证。
