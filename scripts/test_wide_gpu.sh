#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
counts=("$@")
if [[ ${#counts[@]} == 0 ]]; then counts=(1 2 4 16); fi
commands=()
for n in "${counts[@]}"; do
    commands+=("runMain morganite.Generate Gpu build/wide-gpu-$n $n 8 4 dmaBits=256 ports=2 reads=8 writes=4 ic=2048 dc=2048 line=8 tensor=4 bramRf=1 earlyBranch=1")
done
sbt "${commands[@]}"
for n in "${counts[@]}"; do
    dir=build/wide-gpu-$n
    "$VERILATOR" --cc --exe --build --assert -j 4 --top-module Gpu --Mdir "$dir/obj" "$dir/Gpu.sv" "$PWD/sim/wide_gpu.cpp"
    python3 scripts/test_gpu.py "$dir/obj/VGpu" "$n" "$dir/cases" --wide
done
