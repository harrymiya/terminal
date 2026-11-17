#!/usr/bin/env bash
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ ! -S /mnt/wslg/wayland-0 && ! -d /mnt/wslg ]]; then
    echo 'WSLg is unavailable. Install or update WSL and enable GUI app support.' >&2
    exit 1
fi
sudo apt-get update
sudo apt-get install -y python3 python3-gi gir1.2-gtk-3.0 gir1.2-vte-2.91 openssh-client libsecret-tools gnome-keyring
bash "$source_dir/install.sh"
