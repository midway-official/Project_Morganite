#!/usr/bin/env python3
"""Link the CPU copy probe with Aurora's frozen reset stub into a JTAG RAM image."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("out", type=Path)
    p.add_argument("--prefix", default=os.environ.get("CROSS_COMPILE", "/home/midway/la32r/la32r-toolchains/install/bin/loongarch32r-linux-gnusf-"))
    args = p.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    flags = ["-O2", "-Wall", "-Werror", "-ffreestanding", "-fno-builtin", "-fno-pic", "-fno-pie",
             "-fno-stack-protector", "-fno-asynchronous-unwind-tables", "-DCOPY_STANDALONE"]
    objects = []
    sources = ["copy_bare_entry.S", "copy_bare.c", "inflate_copy_probe.c", "inflate_copy_probe.S"]
    for name in sources:
        obj = out / (name + ".o")
        subprocess.run([args.prefix+"gcc", *flags, "-c", root/"board"/name, "-o", obj], check=True)
        objects.append(obj)
    barrier = out/"copy_barrier.o"
    subprocess.run([args.prefix+"gcc", *flags, "-DCOPY_DBAR", "-DCOPY_FUNCTION=copy_barrier",
                    "-c", root/"board/inflate_copy_probe.S", "-o", barrier], check=True)
    ld = out/"layout.ld"
    ld.write_text("ENTRY(_start)\nSECTIONS { . = 0xa0300000; .text : { *(.text.entry) *(.text*) } "
                  ".rodata : { *(.rodata*) *(.srodata*) } .data : { *(.data*) *(.sdata*) } "
                  ".bss : { *(.bss*) *(.sbss*) *(COMMON) } "
                  "ASSERT(SIZEOF(.bss) == 0, \"unexpected BSS\") }\n")
    subprocess.run([args.prefix+"ld", "-T", ld, *objects, barrier, "-o", out/"probe.elf"], check=True)
    subprocess.run([args.prefix+"objcopy", "-O", "binary", out/"probe.elf", out/"probe.bin"], check=True)
    firmware = root/".worktrees/aurora/sim/software/examples/linux"
    subprocess.run([args.prefix+"gcc", "-I"+str(firmware), "-DKERNEL_ENTRY_ADDRESS=0xa0300000",
                    "-c", firmware/"start.S", "-o", out/"reset.o"], check=True)
    subprocess.run([args.prefix+"objcopy", "-O", "binary", "-j", ".text", out/"reset.o", out/"reset.bin"], check=True)
    regions = [(0x00300000, out/"probe.bin"), (0x1c000000, out/"reset.bin")]
    ram = out/"ram.dat"
    with ram.open("w") as f:
        for base, file in regions:
            f.write(f"@{base:x}\n" + file.read_bytes().hex("\n") + "\n")
    manifest = {"kind": "bare-metal LA32R copy", "sha256": hashlib.sha256(ram.read_bytes()).hexdigest(),
                "zero_ranges": [], "segments": [[b, f.stat().st_size] for b, f in regions],
                "inputs": {n: hashlib.sha256((root/"board"/n).read_bytes()).hexdigest() for n in sources},
                "frozen_reset_sha256": hashlib.sha256((firmware/"start.S").read_bytes()).hexdigest()}
    (out/"image.json").write_text(json.dumps(manifest, indent=2)+"\n")


if __name__ == "__main__":
    main()
