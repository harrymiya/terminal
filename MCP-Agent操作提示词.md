# Remote Dev Terminal MCP：Agent 操作提示词

Ubuntu 和 Omarchy 桌面版内置 MCP 服务。启动桌面应用后，Agent 可以通过 MCP 查看主机和终端状态、打开 SSH 会话、输入命令、读取终端屏幕，以及调整界面布局。操作会实时显示在桌面应用中。

## 使用前准备

在安装了桌面版的 Linux 机器上启动 **Remote Dev Terminal**，并保持窗口运行。使用安装桌面应用的同一用户执行自检：

```bash
~/.local/bin/remote-dev-terminal-mcp --check
```

如果返回的信息包含 `"ok": true`，说明服务已启动。然后将下方提示词发给支持 MCP 的 Agent，让它按自身客户端的配置方式接入。

推荐使用 stdio 桥接程序，它会自动读取桌面应用的实际服务地址。Agent 需要能够访问桌面应用所在机器的服务；如果 Agent 运行在另一台机器、容器或远程 SSH 环境中，需要先解决网络或桥接问题。

## 可直接复制的提示词

```text
请帮我连接并使用 Remote Dev Terminal 的 MCP 服务。

背景：
- Remote Dev Terminal Ubuntu/Omarchy 桌面应用已经安装并启动。
- 安装后的 stdio 桥接程序位于 ~/.local/bin/remote-dev-terminal-mcp。
- 该 MCP 控制的是当前运行中的桌面窗口；操作会实时显示在界面上。

请按以下步骤执行：

1. 先确认你运行的环境与桌面应用位于同一台机器，并运行：
   ~/.local/bin/remote-dev-terminal-mcp --check

2. 根据你当前使用的 Agent 客户端，找到正确的 MCP 配置方式，
   将该程序注册为名为 remote-dev-terminal 的 stdio MCP 服务。
   使用当前用户的实际绝对路径，保留已有配置，不覆盖其他 MCP 服务。
   如果配置生效需要重启或重新加载，请明确告诉我。

3. 连接后先调用 get_app_state，查看当前主机、工作区、
   Agent 和已打开的终端，并用简洁的话告诉我发现了什么。
   不要输出密码、密钥或其他敏感信息。

4. 后续根据我的指令，通过 MCP 操作桌面应用。
   常见操作包括：
   - select_host：选择主机
   - browse_remote_directory：浏览远程目录
   - open_terminal：打开指定主机、工作区和 Agent 的终端
   - send_terminal_text / send_terminal_keys：输入命令或按键
   - read_terminal_screen：读取当前终端显示内容
   - set_view_mode / set_tile_layout：调整终端布局

5. 执行命令前先确认目标主机和终端会话；执行后读取屏幕，
   告诉我实际结果。删除主机、工作区或执行有破坏性的远程命令前，
   先说明目标和影响并征得我的同意。

如果连接失败，请检查桌面应用是否运行、桥接程序是否存在、
Agent 是否处于同一机器和用户环境，以及 MCP 是否被禁用。
不要猜测连接成功；请根据实际检查结果报告。
```

## 连接配置示例

不同 Agent 客户端的配置文件位置和格式可能不同。支持以下 JSON 格式的客户端可以参考，将路径替换为实际用户目录：

```json
{
  "mcpServers": {
    "remote-dev-terminal": {
      "command": "/home/你的用户名/.local/bin/remote-dev-terminal-mcp"
    }
  }
}
```

保存后按客户端要求重新加载配置或重启。桌面服务默认监听 `127.0.0.1:17333`；端口被占用时，应用会换用其他端口，stdio 桥接程序会自动读取实际地址。

连接成功后，可以继续给 Agent 下达具体任务，例如：“选择开发机，打开项目 A 工作区的 Shell，查看当前目录和 Git 状态。”

本地 MCP 服务没有单独的身份验证，能访问该本机端口的程序也可能操作终端，请在可信的本机环境中使用。完整工具列表和其他接入方式见 [Ubuntu MCP 使用说明](ubuntu/MCP使用说明.md) 或 [Omarchy MCP 使用说明](omarchy/MCP使用说明.md)。
