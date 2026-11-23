#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
jobs=()
for lanes in 2 4 8; do
    dir="build/smoke/8x$lanes"
    mkdir -p "$dir"
    jobs+=("runMain morganite.Generate WarpBeats $dir 2 8 $lanes")
done
sbt "${jobs[@]}"
for lanes in 2 4 8; do
    dir="build/smoke/8x$lanes"
    "$VERILATOR" --cc --exe --build -j 4 -Wno-fatal --top-module WarpBeats \
        --Mdir "$dir/obj" "$dir/WarpBeats.sv" "$PWD/sim/warp_beats.cpp"
    "$dir/obj/VWarpBeats" 8 "$lanes" | tee "$dir/result.json"
done
