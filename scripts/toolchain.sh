#!/usr/bin/env bash
# Source from the repository root.
set -euo pipefail
export CHISEL_FIRTOOL_PATH=${CHISEL_FIRTOOL_PATH:-"$HOME/.cache/llvm-firtool/1.62.1/bin"}
VERILATOR=${VERILATOR:-"$HOME/verilator-v5.050-src/bin/verilator"}
export VERILATOR
# Vivado 2019.2 settings64.sh exports an older C++ runtime than the WSL compiler.
unset VERILATOR_ROOT LD_LIBRARY_PATH
[[ $("$CHISEL_FIRTOOL_PATH/firtool" --version) == *"firtool-1.62.1"* ]]
[[ $("$VERILATOR" --version) == "Verilator 5.050 "* ]]
