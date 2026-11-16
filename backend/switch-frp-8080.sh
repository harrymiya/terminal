#!/usr/bin/env bash
# Run as root on the frpc host after reviewing this file.
set -euo pipefail
config=/etc/frp/frpc.toml
check_only=false
if [[ ${1:-} == --check ]]; then check_only=true; fi
if [[ $check_only == false && $(id -u) != 0 ]]; then
    echo "请使用 sudo 运行此脚本。" >&2
    exit 1
fi
if [[ $check_only == true ]]; then
    tmp="$(mktemp --suffix=.toml)"
else
    tmp="$(mktemp --suffix=.toml /etc/frp/.rdt-frpc.XXXXXX)"
fi
backup="/etc/frp/frpc.toml.before-rdt-$(date +%Y%m%d-%H%M%S)-$$"
trap 'rm -f "$tmp"' EXIT
python3 - "$config" "$tmp" <<'PY'
import re
import sys
import tomllib
from pathlib import Path

source, target = map(Path, sys.argv[1:])
text = source.read_text()
config = tomllib.loads(text)
matches = [p for p in config.get("proxies", []) if p.get("name") == "web" and p.get("remotePort") == 8080 and p.get("type") == "tcp"]
if len(matches) != 1:
    raise SystemExit("未找到唯一的 web TCP 8080 代理；未修改配置。")
parts = re.split(r'(?=^\s*\[\[proxies\]\]\s*$)', text, flags=re.M)
changed = 0
for index, part in enumerate(parts):
    if re.search(r'^\s*name\s*=\s*["\']web["\']\s*$', part, re.M):
        part, n1 = re.subn(r'^(\s*localIP\s*=\s*)[^\n]+', r'\g<1>"127.0.0.1"', part, flags=re.M)
        part, n2 = re.subn(r'^(\s*localPort\s*=\s*)[^\n]+', r'\g<1>8765', part, flags=re.M)
        if n1 != 1 or n2 != 1:
            raise SystemExit("代理配置不符合预期；未修改配置。")
        parts[index] = part
        changed += 1
if changed != 1:
    raise SystemExit("无法唯一定位 web 代理；未修改配置。")
result = "".join(parts)
tomllib.loads(result)
target.write_text(result)
PY
frpc verify -c "$tmp"
if [[ $check_only == true ]]; then
    echo "配置验证通过；未修改 FRP。"
    exit 0
fi
cp -p "$config" "$backup"
cat "$tmp" > "$config"
if ! systemctl restart frpc || ! systemctl is-active --quiet frpc; then
    cp -p "$backup" "$config"
    systemctl restart frpc || true
    echo "FRP 重启失败，已恢复备份：$backup" >&2
    exit 1
fi
echo "FRP 已重启；8080 已切换至 127.0.0.1:8765。备份：$backup"
