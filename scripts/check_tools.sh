#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
source /etc/os-release
printf 'distribution=%s\nkernel=%s\nuser=%s\n' "$PRETTY_NAME" "$(uname -r)" "$(id -un)"
java -version 2>&1
g++ --version | head -1
cat project/build.properties
rg 'scalaVersion|%% "chisel"|% "chisel-plugin"' build.sbt
"$CHISEL_FIRTOOL_PATH/firtool" --version
sha256sum "$CHISEL_FIRTOOL_PATH/firtool"
verilator --version
"$VERILATOR" --version
"$VERILATOR" -V | sed -n '/VERILATOR_ROOT/p'
if [[ -x /opt/xilinx/Vivado/2019.2/bin/vivado ]]; then
    /opt/xilinx/Vivado/2019.2/bin/vivado -version | head -4
fi
