#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
[[ -f build/fp/reference/libreference.so ]] || bash scripts/test_fp.sh
variants=(1 2 4 2x2)
commands=()
for v in "${variants[@]}"; do
    lanes=4
    [[ $v != 2x2 ]] || lanes=2
    commands+=("runMain morganite.Generate Gpu build/gpu-$v ${v%x*} 8 $lanes")
done
sbt "${commands[@]}"
for v in "${variants[@]}"; do
    dir=build/gpu-$v
    "$VERILATOR" --cc --exe --build -j 4 --top-module Gpu \
        --Mdir "$dir/obj" "$dir/Gpu.sv" "$PWD/sim/gpu.cpp"
    python3 scripts/test_gpu.py "$dir/obj/VGpu" "${v%x*}" "$dir/cases"
done
