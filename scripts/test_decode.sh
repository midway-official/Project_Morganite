#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
out=build/decode
rtl=baseline/server/fpga/u50c/rtl
mkdir -p "$out"
sbt "runMain morganite.Generate MorganiteBoard build/board-rtl 2 8 4"
PYTHONPATH=scripts python3 -c 'from stage_soc import instantiate; print(instantiate())' > "$out/test_gpu_instance.svh"
"$VERILATOR" --binary --timing --build -Wno-fatal -j 4 --top-module board_decode \
    -I"$rtl" -I"$out" --Mdir "$out/obj" sim/board_decode.sv \
    build/board-rtl/MorganiteBoard.sv "$rtl/jtag_uart_16550.sv"
"$out/obj/Vboard_decode"
