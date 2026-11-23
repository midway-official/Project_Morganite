#!/usr/bin/env bash
# U50C access through the operator's restricted SSH gateway. Never programs it.
set -euo pipefail
umask 077

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../../.." && pwd)
credential_dir=${JTAG_CREDENTIAL_DIR:-"$HOME/.ssh/fpga-jtag-client"}
state_dir=${JTAG_STATE_DIR:-"${XDG_STATE_HOME:-$HOME/.local/state}/aurora-u50c"}
control_socket="$credential_dir/u50c-control"
gateway=fpga@mc.mbot6183.fun
local_address=127.0.0.1:13121
ssh_options=(
    -F /dev/null -p 43121 -S "$control_socket"
    -i "$credential_dir/fpga_jtag_ed25519"
    -o IdentitiesOnly=yes -o BatchMode=yes
    -o StrictHostKeyChecking=yes
    -o "UserKnownHostsFile=$credential_dir/known_hosts"
    -o HostKeyAlias=fpga-jtag-gateway
    -o ConnectTimeout=15 -o ConnectionAttempts=1
    -o ServerAliveInterval=30 -o ServerAliveCountMax=3
    -o ExitOnForwardFailure=yes -o RequestTTY=no
)

usage() {
    cat <<'EOF'
Usage: remote_jtag.sh [probe|start|status|stop|setup [ZIP]|help]

  probe   Start/reuse the tunnel, then enumerate U50C with Vivado (default).
  start   Start an SSH tunnel in the background; does not reserve the FPGA.
  status  Check the SSH control connection; does not test remote JTAG.
  stop    Close only this script's SSH tunnel.
  setup   Import key and pinned host key from FPGA_JTAG_CLIENT.zip.
          Default ZIP: repository root/FPGA_JTAG_CLIENT.zip.

Vivado connects to 127.0.0.1:13121; the U50C backend is 127.0.0.1:3122.
Credentials: ~/.ssh/fpga-jtag-client (override: JTAG_CREDENTIAL_DIR).
Logs: ~/.local/state/aurora-u50c (override: JTAG_STATE_DIR).
Set VIVADO_BIN to the Vivado executable, or VIVADO_ROOT to its installation.
EOF
}

die() { printf '%s\n' "$*" >&2; exit 1; }

tunnel_running() {
    ssh "${ssh_options[@]}" -O check "$gateway" >/dev/null 2>&1
}

setup_credentials() {
    local archive=${1:-"$repo_root/FPGA_JTAG_CLIENT.zip"}
    command -v python3 >/dev/null || die 'setup requires python3.'
    python3 - "$archive" "$credential_dir" <<'PY'
import hashlib
import os
from pathlib import Path
import sys
import zipfile

archive, target = map(Path, sys.argv[1:])
if not archive.is_file():
    raise SystemExit(f"Archive not found: {archive}\nRun: remote_jtag.sh setup /path/to/FPGA_JTAG_CLIENT.zip")
names = ('fpga_jtag_ed25519', 'fpga_jtag_ed25519.pub', 'known_hosts')
with zipfile.ZipFile(archive) as bundle:
    # Extract only these exact files; never execute bundled scripts.
    payload = {name: bundle.read(name) for name in names}
for name, data in payload.items():
    dest = target / name
    if dest.is_symlink() or (dest.exists() and dest.read_bytes() != data):
        raise SystemExit(f"Refusing to replace different existing credentials: {dest}")
target.mkdir(mode=0o700, parents=True, exist_ok=True)
target.chmod(0o700)
for name, data in payload.items():
    dest = target / name
    if not dest.exists():
        fd = os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as out:
            out.write(data)
    dest.chmod(0o600)
print(f"Credentials imported: {target}")
print(f"Archive SHA-256: {hashlib.sha256(archive.read_bytes()).hexdigest()}")
PY
    ssh-keygen -lf "$credential_dir/known_hosts"
}

start_tunnel() {
    if tunnel_running; then
        printf 'Reusing SSH tunnel: %s -> U50C backend 127.0.0.1:3122\n' "$local_address"
        return
    fi
    if [[ ! -f "$credential_dir/fpga_jtag_ed25519" || ! -f "$credential_dir/known_hosts" ]]; then
        setup_credentials
    fi
    [[ ! -e "$control_socket" ]] || die "Unresponsive control socket: $control_socket. Inspect it before removing it."
    mkdir -p -- "$state_dir"
    chmod 700 -- "$credential_dir" "$state_dir"
    chmod 600 -- "$credential_dir/fpga_jtag_ed25519" "$credential_dir/known_hosts"
    if ! ssh "${ssh_options[@]}" -E "$state_dir/tunnel.log" \
        -o LogLevel=VERBOSE -o ControlPersist=no -f -N -T -M \
        -L "$local_address:127.0.0.1:3122" "$gateway"; then
        tail -n 12 -- "$state_dir/tunnel.log" >&2
        die 'SSH tunnel failed. Check the log and whether local port 13121 is already occupied.'
    fi
    tunnel_running || die 'SSH did not leave a running control connection.'
    printf 'SSH tunnel started: %s -> U50C backend 127.0.0.1:3122\n' "$local_address"
    printf 'Run probe to verify the backend and FPGA. Tunnel log: %s/tunnel.log\n' "$state_dir"
}

probe_target() {
    local vivado=${VIVADO_BIN:-}
    if [[ -z "$vivado" && -n "${VIVADO_ROOT:-}" ]]; then
        vivado="$VIVADO_ROOT/bin/vivado"
    fi
    if [[ -z "$vivado" ]]; then
        vivado=$(command -v vivado || true)
    fi
    if [[ -z "$vivado" && -x /opt/xilinx/Vivado/2019.2/bin/vivado ]]; then
        vivado=/opt/xilinx/Vivado/2019.2/bin/vivado
    fi
    [[ -n "$vivado" ]] || die 'Vivado was not found. Set VIVADO_BIN=/path/to/vivado, or use start and connect from Vivado Hardware Manager.'
    vivado=$(command -v "$vivado") || die 'VIVADO_BIN must name an executable.'
    # Resolve before changing to the log directory, including relative overrides.
    vivado=$(realpath -- "$vivado")
    start_tunnel
    mkdir -p -- "$state_dir"
    (
        cd -- "$state_dir"
        timeout 120s "$vivado" -mode batch -notrace \
            -log "$state_dir/probe.log" -journal "$state_dir/probe.jou" \
            -source "$script_dir/probe_target.tcl" -tclargs "$local_address"
    )
    grep -Eq '^PROBE_OK=xcu50(_u55n)?_[0-9]+$' "$state_dir/probe.log" \
        || die "No successful XCU50 enumeration in $state_dir/probe.log"
    printf 'U50C probe passed. Hardware Target is closed; SSH tunnel remains available.\nLog: %s/probe.log\n' "$state_dir"
}

case ${1:-probe} in
    setup) [[ $# -le 2 ]] || die 'setup accepts at most one ZIP path.'; setup_credentials "${2:-$repo_root/FPGA_JTAG_CLIENT.zip}" ;;
    start) start_tunnel ;;
    probe) probe_target ;;
    status)
        if ssh "${ssh_options[@]}" -O check "$gateway"; then
            printf 'SSH tunnel active: %s -> 127.0.0.1:3122\nUse probe to test the remote FPGA.\n' "$local_address"
        else
            die 'No active tunnel managed by this script. Run start or probe.'
        fi ;;
    stop)
        if tunnel_running; then
            ssh "${ssh_options[@]}" -O exit "$gateway"
            printf 'SSH tunnel stopped.\n'
        else
            printf 'No active tunnel managed by this script.\n'
        fi ;;
    help|-h|--help) usage ;;
    *) usage >&2; exit 2 ;;
esac
