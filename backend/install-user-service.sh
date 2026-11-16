#!/usr/bin/env bash
set -euo pipefail
service_dir="${XDG_CONFIG_HOME:-$HOME/.config}/remote-dev-terminal"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p -m 700 "$service_dir" "$unit_dir"
if [[ ! -f "$service_dir/sync.env" ]]; then
    umask 077
    python3 -c 'import secrets; print("RDT_SYNC_TOKEN=" + secrets.token_urlsafe(32))' > "$service_dir/sync.env"
fi
chmod 600 "$service_dir/sync.env"
cat > "$unit_dir/remote-dev-terminal-sync.service" <<EOF
[Unit]
Description=Remote Dev Terminal session sync
After=network.target

[Service]
Type=simple
EnvironmentFile=$service_dir/sync.env
ExecStart=/usr/bin/python3 $source_dir/server.py --host 127.0.0.1 --port 8765
Restart=on-failure
RestartSec=3
UMask=0077

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now remote-dev-terminal-sync.service
systemctl --user is-active remote-dev-terminal-sync.service
