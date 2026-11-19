#!/usr/bin/env bash
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if ! command -v secret-tool >/dev/null 2>&1; then
    echo "提示：未安装 secret-tool，保存 SSH 密码需要运行：sudo apt install libsecret-tools gnome-keyring" >&2
fi
data_dir="${XDG_DATA_HOME:-$HOME/.local/share}"
install_dir="$data_dir/remote-dev-terminal"
bin_dir="$HOME/.local/bin"
mkdir -p "$install_dir" "$data_dir/applications" "$bin_dir"
install -m 644 "$source_dir/core.py" "$install_dir/core.py"
install -m 644 "$source_dir/host_metrics.py" "$install_dir/host_metrics.py"
install -m 644 "$source_dir/version.py" "$install_dir/version.py"
install -m 755 "$source_dir/remote-dev-terminal.py" "$install_dir/remote-dev-terminal.py"
install -m 755 "$source_dir/askpass.py" "$install_dir/askpass.py"
install -m 755 "$source_dir/mcp_server.py" "$install_dir/mcp_server.py"
ln -sf "$install_dir/mcp_server.py" "$bin_dir/remote-dev-terminal-mcp"
# Desktop entry Exec arguments require double-quote escaping.
escaped="${install_dir//\\/\\\\}"
escaped="${escaped//\"/\\\"}"
escaped="${escaped//\`/\\\`}"
escaped="${escaped//\$/\\\$}"
escaped="${escaped//%/%%}"
cat > "$data_dir/applications/remote-dev-terminal.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Remote Dev Terminal
Comment=Remote development terminal
Exec="$escaped/remote-dev-terminal.py"
Icon=utilities-terminal
Terminal=false
Categories=Development;TerminalEmulator;Network;
EOF
echo "已安装到 $install_dir，可从应用菜单启动 Remote Dev Terminal。"
