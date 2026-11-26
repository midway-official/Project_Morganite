#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
wide="dmaBits=256 reads=8 writes=4 ic=2048 dc=2048 line=8 tensor=4 bramRf=1 earlyBranch=1"
single=build/wide-single-gpu-16
dual=build/wide-gpu-16
sbt "runMain morganite.Generate Gpu $single 16 8 4 ports=1 $wide" "runMain morganite.Generate Gpu $dual 16 8 4 ports=2 $wide"
for dir in "$single" "$dual"; do
    flags=()
    if [[ "$dir" == "$single" ]]; then flags=(-CFLAGS -DSINGLE_HBM); fi
    "$VERILATOR" --cc --exe --build --assert -j 4 --top-module Gpu --Mdir "$dir/obj" "${flags[@]}" "$dir/Gpu.sv" "$PWD/sim/wide_gpu.cpp"
done
python3 scripts/bench_wide_hbm.py "$single/obj/VGpu" "$dual/obj/VGpu" build/wide-copy
