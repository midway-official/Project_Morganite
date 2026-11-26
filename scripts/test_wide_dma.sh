#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
out=build/wide-single
sbt "runMain morganite.Generate WideDma $out 1 8 4 dmaBits=256 dc=2048 line=8"
"$VERILATOR" --cc --exe --build --assert -j 4 --top-module WideDma --Mdir "$out/obj" "$out/WideDma.sv" "$PWD/sim/wide_dma.cpp"
"$out/obj/VWideDma"
