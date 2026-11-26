#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
out=build/graphics-units
wide="dmaBits=256 ports=2 reads=8 writes=4 ic=2048 dc=2048 line=8 earlyBranch=1 bramRf=1"
units="setup=1 bins=1 raster=1 rop=1 sfu=1"
sbt "runMain morganite.Generate GraphicsLane $out/lane 1x1 8 4 $units" \
    "runMain morganite.Generate Gpu $out/gpu 1x2 8 4 $wide $units" \
    "runMain morganite.Generate MorganiteBoard $out/board 4x4 8 4 $wide tensor=4 $units" \
    "runMain morganite.Generate Gpu $out/rect 2x3 8 4 $wide sfu=1"
"$VERILATOR" --cc --exe --build --assert -j 4 --top-module GraphicsLane --Mdir "$out/lane/obj" \
    "$out/lane/GraphicsLane.sv" "$PWD/sim/graphics_lane.cpp"
"$out/lane/obj/VGraphicsLane" | tee "$out/sfu.json"
for config in gpu rect; do
    "$VERILATOR" --cc --exe --build --assert -j 4 --top-module Gpu --Mdir "$out/$config/obj" \
        "$out/$config/Gpu.sv" "$PWD/sim/wide_gpu.cpp"
done
python3 scripts/test_graphics_isa.py "$out/gpu/obj/VGpu" 1 2 31
python3 scripts/test_graphics_isa.py "$out/rect/obj/VGpu" 2 3 16
python3 graphics/test_render.py rtl "$out/frames" --sim "$out/gpu/obj/VGpu" --width 64 --height 32 --units setup bins raster rop sfu
