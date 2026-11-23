#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
[[ -f build/fp/reference/libreference.so ]] || bash scripts/test_fp.sh
sbt "runMain morganite.Generate Sm build/sm 1 8 4"
"$VERILATOR" --cc --exe --build -j 6 --top-module Sm --Mdir build/sm/obj \
    build/sm/Sm.sv "$PWD/sim/sm.cpp"
python3 scripts/test_sm.py build/sm/obj/VSm
