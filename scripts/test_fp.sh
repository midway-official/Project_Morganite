#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/toolchain.sh
mkdir -p build/fp
fetch() {
    local name=$1 rev=$2 dir="build/$1"
    if [[ ! -d "$dir/.git" ]]; then
        git init -q "$dir"
        git -C "$dir" fetch -q --depth 1 "https://github.com/ucb-bar/$name.git" "$rev"
        git -C "$dir" checkout -q --detach FETCH_HEAD
    fi
    [[ $(git -C "$dir" rev-parse HEAD) == "$rev" ]]
    git -C "$dir" diff --quiet HEAD --
}
fetch berkeley-softfloat-3 07ebd29db0cfb55723a79fbbffe99103ef38e305
fetch berkeley-testfloat-3 a9c849f1b0eb0264b626d9686ffae167d996e3be
platform=Linux-x86_64-GCC
# Build separately so the RISCV NaN and conversion specialization cannot reuse
# stale 8086-SSE objects from an upstream build directory.
ref="$PWD/build/fp/reference"
mkdir -p "$ref/soft" "$ref/test"
soft="$PWD/build/berkeley-softfloat-3"
testfloat="$PWD/build/berkeley-testfloat-3"
cp "$soft/build/$platform/platform.h" "$ref/soft/"
cp "$testfloat/build/$platform/platform.h" "$ref/test/"
make -s -C "$ref/soft" -f "$soft/build/$platform/Makefile" -j8 \
    "SOURCE_DIR=$soft/source" SPECIALIZE_TYPE=RISCV
gcc -shared -fPIC -Wl,-Bsymbolic -O2 -I"$soft/source/include" \
    sw/ref_fp.c "$ref/soft/softfloat.a" -o "$ref/libreference.so"
make -s -C "$ref/test" -f "$testfloat/build/$platform/Makefile" -j8 \
    "SOURCE_DIR=$testfloat/source" "SOFTFLOAT_DIR=$soft" "SOFTFLOAT_LIB=$ref/soft/softfloat.a" testfloat_gen
sbt "runMain morganite.Generate Fp32Lane build/fp 2 8 4"
"$VERILATOR" --cc --exe --build -j 4 --top-module Fp32Lane \
    --Mdir build/fp/obj build/fp/Fp32Lane.sv "$PWD/sim/fp32_lane.cpp"
python3 scripts/fp_vectors.py "$ref/test/testfloat_gen" | build/fp/obj/VFp32Lane | tee build/fp/result.json
