# Remote Dev Terminal · Windows

Windows 11 原生桌面版，安装和运行都不需要 WSL、Ubuntu 或 Python。界面采用与 Ubuntu 版相同的深色侧栏、主机/开发者/工作区和多终端布局；终端使用 Windows ConPTY 与 xterm.js，支持 ANSI、全屏交互、鼠标和终端尺寸调整。SSH 客户端随程序打包，不要求安装 Windows 可选 OpenSSH 功能。

## 安装

从仓库根目录运行：

```powershell
.\windows\install.bat
```

安装程序将应用复制到 `%LOCALAPPDATA%\Programs\RemoteDevTerminal`，创建开始菜单快捷方式并启动程序，不需要管理员权限。应用使用 Windows 自带的 .NET Framework 4.8.1 和 WebView2 Runtime；若系统缺少 WebView2 Runtime，请先安装 Microsoft Edge WebView2 Runtime。

建议先配置 Windows SSH 密钥，首次连接时在终端确认服务器指纹。SSH 密钥和主机指纹仍由程序读取或保存到 `%USERPROFILE%\.ssh`。远端安装 tmux 后，SSH 断线时可以重新连接原会话。

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

生成文件位于 windows\native\publish。然后运行 windows\install.bat 完成安装。自包含的 .NET 运行时不会随应用分发；运行环境使用 Windows 11 已包含的 .NET Framework 和 WebView2 Runtime。
