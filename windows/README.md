# Remote Dev Terminal · Windows

Windows 11 原生桌面版，安装和运行都不需要 WSL、Ubuntu 或 Python。界面采用与 Ubuntu 版相同的深色侧栏、主机/开发者/工作区和多终端布局；终端使用 Windows ConPTY 与 xterm.js，支持 ANSI、全屏交互、鼠标和终端尺寸调整。SSH 客户端随程序打包，不要求安装 Windows 可选 OpenSSH 功能。

## 安装

关闭已运行的 Remote Dev Terminal 后，从仓库根目录运行：

```powershell
.\windows\install.ps1
```

从源码目录安装时，脚本使用 .NET 10 SDK 和 Node.js 构建当前代码后安装。使用已发布的 ZIP 时，可直接运行解压后的 `install.bat`，只需运行环境。安装程序将应用复制到 `%LOCALAPPDATA%\Programs\RemoteDevTerminal`，创建开始菜单快捷方式并启动程序，不需要管理员权限。应用使用 .NET Framework 4.8 或以上及 WebView2 Runtime。若系统缺少 WebView2 Runtime，请安装 Microsoft Edge WebView2 Runtime。

建议先配置 Windows SSH 密钥，首次连接时在终端确认服务器指纹。SSH 密钥和主机指纹仍由程序读取或保存到 `%USERPROFILE%\.ssh`。远端安装 tmux 后，新开的终端可以在 SSH 断线时保留原会话。客户端会尝试自动恢复，也可点击对应终端标题栏的「重新连接」。重启客户端后可从「历史」重新打开原会话。侧栏的 ↻ 用于跨设备同步。

## 使用

1. 点击侧栏“编辑”，添加 SSH 主机。
2. 为主机添加远端绝对路径形式的工作区。
3. 选择开发者，点击工作区连接。
4. 顶部可切换标签和平铺视图；最多同时打开 8 个终端。

配置保存在 %USERPROFILE%\.config\remote-dev-terminal\config.json，SSH 密钥和主机指纹由 Windows OpenSSH 保存在 %USERPROFILE%\.ssh。原有配置中未识别的字段会在保存时保留。

## 从源码构建

需要 .NET 10 SDK 和 Node.js：

```powershell
.\windows\build.ps1
```

生成文件位于 `windows\native\publish`，安装包位于 `windows\dist\RemoteDevTerminal-win-x64.zip`。
