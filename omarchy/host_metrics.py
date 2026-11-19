"""Read host resource usage over a verified, noninteractive SSH connection."""
import math
import subprocess
from pathlib import Path

from core import ssh_address, ssh_port, ssh_auth_environment


# The CPU sample is an interval measurement; Linux iowait counts as idle.
REMOTE_COMMAND = r'''LC_ALL=C; export LC_ALL
if [ -r /proc/meminfo ] && [ -r /proc/stat ]; then
  mem=$(awk '/^MemTotal:/ {t=$2} /^MemAvailable:/ {a=$2} END {if (t>0 && a!="") printf "%.2f",100*a/t}' /proc/meminfo)
  first=$(awk '/^cpu / {print $2,$3,$4,$5,$6,$7,$8,$9; exit}' /proc/stat)
  sleep 1
  second=$(awk '/^cpu / {print $2,$3,$4,$5,$6,$7,$8,$9; exit}' /proc/stat)
  cpu=$(printf '%s\n%s\n' "$first" "$second" | awk 'NR==1 {for(i=1;i<=NF;i++) a[i]=$i; next} {for(i=1;i<=NF;i++) d[i]=$i-a[i]; total=0; for(i=1;i<=NF;i++) total+=d[i]; if(total>0) printf "%.2f",100*(total-d[4]-d[5])/total}')
elif [ "$(uname -s)" = Darwin ]; then
  mem=$(vm_stat | awk '/page size of/ {size=$8; gsub(/[^0-9]/,"",size)} /Pages free:/ {free=$3} /Pages inactive:/ {inactive=$3} END {gsub(/\./,"",free); gsub(/\./,"",inactive); if(size>0) print (free+inactive)*size}')
  total=$(sysctl -n hw.memsize)
  mem=$(awk -v a="$mem" -v t="$total" 'BEGIN {if(t>0) printf "%.2f",100*a/t}')
  cpu=$(top -l 2 -n 0 -s 1 | awk '/CPU usage:/ {for(i=1;i<=NF;i++) if($(i+1)=="idle") {gsub(/%/,"",$i); idle=$i}} END {if(idle!="") printf "%.2f",100-idle}')
fi
printf '\nRDT_METRICS %s %s\n' "$mem" "$cpu"
'''


def metrics_key(host):
    return tuple(host.get(k) for k in ("id", "username", "credential_id", "address", "port", "lan_address", "lan_port", "external_address", "external_port"))


def parse_metrics(output):
    for line in reversed(output.splitlines()):
        parts = line.split()
        if len(parts) != 3 or parts[0] != "RDT_METRICS":
            continue
        try:
            values = tuple(float(value) for value in parts[1:])
        except ValueError:
            return None
        return values if all(math.isfinite(v) and 0 <= v <= 100 for v in values) else None
    return None


def read_host_metrics(host):
    address, port = ssh_address(host), ssh_port(host)
    if not address or not port:
        return None
    batch = "no" if host.get("credential_id") else "yes"
    argv = ["ssh", "-o", f"BatchMode={batch}", "-o", "StrictHostKeyChecking=yes",
            "-o", "NumberOfPasswordPrompts=1", "-o", "ConnectTimeout=5",
            "-p", str(port), "-l", host["username"], "--", address, REMOTE_COMMAND]
    env = ssh_auth_environment(host, Path(__file__).with_name("askpass.py"))
    env["RDT_SSH_NONINTERACTIVE"] = "1"
    output = subprocess.check_output(argv, stdin=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                     env=env, timeout=12, text=True)
    return parse_metrics(output)
