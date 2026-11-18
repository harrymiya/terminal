# Remote Dev Terminal · MCP 服务说明

`omarchy` 与 `ubuntu` 桌面端已内置零依赖 MCP（Model Context Protocol）服务，**无需额外安装第三方 Python 包**。

当桌面端运行时，所有 MCP 调用都会直接调度到当前窗口的 GTK 主线程执行，因此 **Agent 的每一次操作（切换主机、打开终端、输入命令、切换平铺/标签、缩放界面等）都会实时呈现在界面上，人可以直接看到变化并随时接管**。

## 1. 启动与自检

1. 正常启动桌面端（应用菜单启动 **Remote Dev Terminal**，或 `python3 remote-dev-terminal.py`）。
2. 桌面端会自动在本机启动 MCP HTTP 服务（默认 `http://127.0.0.1:17333/mcp`），并在 `${XDG_RUNTIME_DIR:-/tmp}/remote-dev-terminal-mcp.json` 写入当前端口。
3. 验证服务是否就绪：

```bash
remote-dev-terminal-mcp --check
# 未运行 ./install.sh 时可直接运行：
python3 mcp_server.py --check
```

## 2. 其他 Agent 接入配置

### 方式 A：stdio 模式（推荐，通用兼容）

运行 `./install.sh` 后会自动安装 `~/.local/bin/remote-dev-terminal-mcp`：

```json
{
  "mcpServers": {
    "remote-dev-terminal": {
      "command": "remote-dev-terminal-mcp"
    }
  }
}
```

如果未使用 `install.sh`，可直接指向仓库中的脚本：

```json
{
  "mcpServers": {
    "remote-dev-terminal": {
      "command": "python3",
      "args": ["/你的路径/remote-dev-terminal/ubuntu/mcp_server.py"]
    }
  }
}
```

### 方式 B：Streamable HTTP 模式（支持 HTTP 的 Agent 可直连）

```json
{
  "mcpServers": {
    "remote-dev-terminal": {
      "url": "http://127.0.0.1:17333/mcp"
    }
  }
}
```

## 3. 支持的操作（与人工操作一一对应）

| 分类 | 工具名 | 说明 |
|---|---|---|
| 状态查看 | `get_app_state` | 查看窗口布局、缩放、主机、Agent、工作区与所有已打开终端 |
| 主机管理 | `select_host` / `add_host` / `edit_host` / `delete_host` | 切换或增删改 SSH 主机（密码自动存入系统密钥环，不回传明文） |
| Agent 管理 | `select_agent` / `add_agent` / `edit_agent` / `delete_agent` | 切换或增删改当前主机的 Agent 命令预设 |
| 工作区管理 | `add_workspace` / `edit_workspace` / `delete_workspace` / `move_workspace` / `browse_remote_directory` | 增删改、排序工作区，或通过 SSH 浏览远端目录 |
| 终端会话 | `open_terminal` / `focus_terminal` / `close_terminal` / `reconnect_terminal` | 打开、聚焦、关闭或重连终端 |
| 终端交互 | `send_terminal_text` / `send_terminal_keys` / `read_terminal_screen` / `send_image_to_terminal` | 输入文本并回车、发送快捷键（如 `Ctrl+C` / `Up` / `PageUp`）、读取屏幕、上传图片并发送路径 |
| 界面控制 | `set_view_mode` / `set_tile_layout` / `toggle_sidebar` / `set_ui_scale` | 切换平铺/标签模式、网格/左右/上下布局、展开收起侧栏、调整 50%–200% 缩放 |
| 设置与同步 | `update_settings` / `trigger_sync` / `get_ssh_public_key` | 修改自动回车与自动同步设置、立即同步、读取/生成本机 SSH 公钥 |

## 4. 环境变量（可选）

- `RDT_MCP_PORT`：指定桌面端内置 MCP 监听端口（默认 `17333`）。
- `RDT_MCP_URL`：指定 stdio 桥接器连接的目标地址。
- `RDT_MCP_DISABLED=1`：禁用内置 MCP 服务。
