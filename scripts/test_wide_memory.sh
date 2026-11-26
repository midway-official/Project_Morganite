#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
sbt "runMain morganite.Generate DualDataL1 build/wide-cache 1 8 4 dmaBits=256 dc=2048 line=8" \
    "runMain morganite.Generate Scratchpad build/wide-scratch 1 8 4" \
    "runMain morganite.Generate Sm build/wide-sm 1 8 4 dmaBits=256 bramRf=1 earlyBranch=1"
for entry in 'wide-cache DualDataL1 dual_l1' 'wide-scratch Scratchpad scratch_broadcast' 'wide-sm Sm sm'; do
    read -r dir top bench <<< "$entry"
    path="build/$dir"
    "$VERILATOR" --cc --exe --build --assert -j 4 --top-module "$top" --Mdir "$path/obj" -CFLAGS -DWIDE_LSU \
        "$path/$top.sv" "$PWD/sim/$bench.cpp"
    if [[ "$top" == Sm ]]; then
        python3 scripts/test_sm.py "$path/obj/VSm" --out "$path/cases" --wide
    else
        "$path/obj/V$top"
    fi
done
