#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
out=build/wide-tree
sbt "runMain morganite.Generate WideDma $out 12 8 4 dmaBits=256 reads=8 writes=4"
"$VERILATOR" --cc --exe --build --assert -j 4 --top-module WideDma --Mdir "$out/obj" "$out/WideDma.sv" "$PWD/sim/wide_tree.cpp"
"$out/obj/VWideDma"
