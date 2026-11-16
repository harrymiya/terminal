#!/usr/bin/env bash
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
data_dir="${XDG_DATA_HOME:-$HOME/.local/share}"
install_dir="$data_dir/remote-dev-terminal"
mkdir -p "$install_dir" "$data_dir/applications"
install -m 644 "$source_dir/core.py" "$install_dir/core.py"
install -m 644 "$source_dir/version.py" "$install_dir/version.py"
install -m 755 "$source_dir/remote-dev-terminal.py" "$install_dir/remote-dev-terminal.py"
install -m 755 "$source_dir/askpass.py" "$install_dir/askpass.py"
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
