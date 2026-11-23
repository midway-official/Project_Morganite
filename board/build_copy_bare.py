#!/usr/bin/env python3
"""Link the CPU copy probe with Aurora's frozen reset stub into a JTAG RAM image."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess


def user_segments(directory, out):
    """Load the three archived Linux probes at their ELF virtual addresses."""
    hashes = json.loads((directory/"binaries.json").read_text())
    segments = []
    entries = []
    for i, name in enumerate(("original", "nop", "dbar")):
        elf = (directory/name).read_bytes()
        if hashlib.sha256(elf).hexdigest() != hashes[name] or elf[:6] != b"\x7fELF\x01\x01":
            raise ValueError(f"unexpected ELF identity for {name}")
        hdr = struct.unpack_from("<HHIIIIIHHHHHH", elf, 16)
        if hdr[0:2] != (2, 258) or hdr[8] != 32:
            raise ValueError(f"expected a static LoongArch ELF32 in {name}")
        headers = [struct.unpack_from("<8I", elf, hdr[4] + j * hdr[8]) for j in range(hdr[9])]
        loads = [ph for ph in headers if ph[0] == 1]
        rx, bss = loads
        if (rx[2] != 0x10000 or rx[6] != 5 or rx[4] != rx[5] or rx[4] > 4096 or
                not rx[2] <= hdr[3] < rx[2] + rx[4] or rx[1] + rx[4] > len(elf) or
                bss[2] != 0x12000 or bss[4] != 0 or bss[5] != 0xa100 or bss[6] != 6):
            raise ValueError(f"ELF memory layout changed in {name}")
        text = out/(name + ".rx")
        text.write_bytes(elf[rx[1]:rx[1]+rx[4]])
        pa = 0x01000000 + i * 8192
        segments.append((pa, text))
        entries.append(f'{{"{name}", 0x{hdr[3]:x}, 0x{pa:x}}}')
    (out/"user_probes.h").write_text("static const struct probe probes[] = {\n" +
                                      ",\n".join(entries) + "\n};\n")
    return segments, hashes


def main():
    root = Path(__file__).resolve().parents[1]
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("out", type=Path)
    mode = p.add_mutually_exclusive_group()
    mode.add_argument("--irq-tlb", action="store_true", help="exercise periodic timer and mapped pages")
    mode.add_argument("--user-probes", type=Path, help="run original Linux probe ELFs at PLV3 without Linux")
    p.add_argument("--prefix", default=os.environ.get("CROSS_COMPILE", "/home/midway/la32r/la32r-toolchains/install/bin/loongarch32r-linux-gnusf-"))
    args = p.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    flags = ["-O2", "-Wall", "-Werror", "-ffreestanding", "-fno-builtin", "-fno-pic", "-fno-pie",
             "-fno-stack-protector", "-fno-asynchronous-unwind-tables", "-DCOPY_STANDALONE"]
    if args.irq_tlb:
        flags.append("-DCOPY_IRQ_TLB")
    objects = []
    extra_regions = []
    elf_hashes = {}
    sources = ["copy_bare_entry.S", "copy_bare.c", "inflate_copy_probe.c", "inflate_copy_probe.S"]
    if args.irq_tlb:
        sources.extend(["copy_irq_tlb.S", "copy_irq_tlb.c"])
    if args.user_probes:
        extra_regions, elf_hashes = user_segments(args.user_probes, out)
        flags.append("-I" + str(out))
        sources = ["copy_bare_entry.S", "copy_irq_tlb.S", "copy_user.S", "copy_user.c"]
    for name in sources:
        obj = out / (name + ".o")
        subprocess.run([args.prefix+"gcc", *flags, "-c", root/"board"/name, "-o", obj], check=True)
        objects.append(obj)
    if not args.user_probes:
        barrier = out/"copy_barrier.o"
        subprocess.run([args.prefix+"gcc", *flags, "-DCOPY_DBAR", "-DCOPY_FUNCTION=copy_barrier",
                        "-c", root/"board/inflate_copy_probe.S", "-o", barrier], check=True)
        objects.append(barrier)
    ld = out/"layout.ld"
    ld.write_text("ENTRY(_start)\nSECTIONS { . = 0xa0300000; .text : { *(.text.entry) *(.text*) } "
                  ".rodata : { *(.rodata*) *(.srodata*) } .data : { *(.data*) *(.sdata*) } "
                  ".bss : { *(.bss*) *(.sbss*) *(COMMON) } "
                  "ASSERT(SIZEOF(.bss) == 0, \"unexpected BSS\") }\n")
    subprocess.run([args.prefix+"ld", "-T", ld, *objects, "-o", out/"probe.elf"], check=True)
    subprocess.run([args.prefix+"objcopy", "-O", "binary", out/"probe.elf", out/"probe.bin"], check=True)
    firmware = root/".worktrees/aurora/sim/software/examples/linux"
    subprocess.run([args.prefix+"gcc", "-I"+str(firmware), "-DKERNEL_ENTRY_ADDRESS=0xa0300000",
                    "-c", firmware/"start.S", "-o", out/"reset.o"], check=True)
    subprocess.run([args.prefix+"objcopy", "-O", "binary", "-j", ".text", out/"reset.o", out/"reset.bin"], check=True)
    regions = [(0x00300000, out/"probe.bin"), *extra_regions, (0x1c000000, out/"reset.bin")]
    ram = out/"ram.dat"
    with ram.open("w") as f:
        for base, file in regions:
            f.write(f"@{base:x}\n" + file.read_bytes().hex("\n") + "\n")
    manifest = {"kind": "bare-metal LA32R copy", "irq_tlb": args.irq_tlb,
                "sha256": hashlib.sha256(ram.read_bytes()).hexdigest(),
                "zero_ranges": [], "segments": [[b, f.stat().st_size] for b, f in regions],
                "inputs": {n: hashlib.sha256((root/"board"/n).read_bytes()).hexdigest() for n in sources},
                "frozen_reset_sha256": hashlib.sha256((firmware/"start.S").read_bytes()).hexdigest()}
    if elf_hashes:
        manifest["user_elf_sha256"] = elf_hashes
    (out/"image.json").write_text(json.dumps(manifest, indent=2)+"\n")


if __name__ == "__main__":
    main()
