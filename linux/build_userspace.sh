#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
prefix=${CROSS_COMPILE:-/home/midway/la32r/la32r-toolchains/install/bin/loongarch32r-linux-gnusf-}
overlay=build/initramfs-extra
mkdir -p "$overlay/bin" "$overlay/gpu"
"${prefix}gcc" -static -O2 -Wall -Werror linux/cli.c -o "$overlay/bin/mgpu"
for asm in examples/*.s; do
    name=$(basename "$asm" .s)
    python3 sw/asm.py asm "$asm" "$overlay/gpu/$name.bin"
done
python3 scripts/fma_bench.py --unroll 16 > build/fma_bench.s
python3 sw/asm.py asm build/fma_bench.s "$overlay/gpu/fma_bench.bin"
python3 scripts/fma_bench.py --unroll 16 --tensor > build/tensor_bench.s
python3 sw/asm.py asm build/tensor_bench.s "$overlay/gpu/tensor_bench.bin"
python3 graphics/test_render.py assets "$overlay/gpu" "$@"
