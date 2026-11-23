#!/usr/bin/env python3
"""Cross-build the LA32R image with its frozen initramfs and Morganite device."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import zlib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--source", type=Path, default=Path("/home/midway/la32r-Linux-v0.3-20260819"))
p.add_argument("--prefix", default="/home/midway/la32r/la32r-toolchains/install/bin/loongarch32r-linux-gnusf-")
p.add_argument("--jobs", type=int, default=8)
args = p.parse_args()
obj, src = root / "build/kernel", root / "build/linux-src"
obj.mkdir(exist_ok=True)
kernel_rev = "fbd87836058ae5e676bf97463076916a18c94ad7"
if not src.exists():
    subprocess.run(["git", "-C", args.source, "worktree", "add", "--detach", src, kernel_rev], check=True)
if subprocess.check_output(["git", "-C", src, "rev-parse", "HEAD"], text=True).strip() != kernel_rev:
    raise ValueError("unexpected source revision")
dmw_patch = root / "linux/la32r-dmw.patch"
tlb_patch = root / "linux/la32r-tlb.patch"
clock_patch = root / "linux/la32r-clock.patch"
apply = ["git", "-C", src, "apply"]
for patch in (dmw_patch, tlb_patch, clock_patch):
    if subprocess.run([*apply, "--reverse", "--check", patch], capture_output=True).returncode:
        subprocess.run([*apply, patch], check=True)
aurora = root / ".worktrees/aurora/sim"
image = aurora / "linux-boot-runs/v0.3/image"
frozen = (image / "vmlinux.bin").read_bytes()
if hashlib.sha256(frozen).hexdigest() != "909c0479371a45c49346eef769da0305e76073f27f24428ec4dd0f0b2a92f845":
    raise ValueError("unexpected baseline kernel for initramfs extraction")


def write(path, data):
    if not path.exists() or path.read_bytes() != data:
        path.write_bytes(data)


def append(path, extra):
    data = path.read_text()
    if extra not in data:
        path.write_text(data + "\n" + extra + "\n")


cpios = []
for m in re.finditer(b"\x1f\x8b\x08", frozen):
    try:
        data = zlib.decompress(frozen[m.start():], 31)
    except zlib.error:
        continue
    if data.startswith(b"070701"):
        cpios.append(data)
if len(cpios) != 1:
    raise ValueError("expected one gzip newc archive in frozen kernel")
overlay = root / "build/initramfs-extra"
if overlay.is_dir():
    names = [str(p.relative_to(overlay)) for p in sorted(overlay.rglob("*"))]
    cpios.append(subprocess.check_output(["cpio", "-o", "--null", "-H", "newc", "-R", "0:0", "--quiet"],
                                        input=("\0".join(names) + "\0").encode(), cwd=overlay))
write(obj / "initramfs.cpio", b"".join(cpios))
for name in ["morganite.c", "morganite.h"]:
    write(src / "drivers/misc" / name, (root / "linux" / name).read_bytes())
append(src / "drivers/misc/Makefile", "obj-y += morganite.o")
dts = src / "arch/loongarch/boot/dts/loongson"
write(dts / "morganite.dtsi", (root / "linux/morganite.dtsi").read_bytes())
append(dts / "loongson32_ls.dts", '#include "morganite.dtsi"')
env = dict(os.environ, ARCH="loongarch", CROSS_COMPILE=args.prefix)
env.pop("LD_LIBRARY_PATH", None)


def make(target):
    subprocess.run(["make", "-s", "-C", src, f"O={obj}", f"-j{args.jobs}", target], env=env, check=True)


if not (obj / ".config").exists():
    make("la32_defconfig")
subprocess.run([src / "scripts/config", "--file", obj / ".config", "--set-str", "INITRAMFS_SOURCE",
                str(obj / "initramfs.cpio"), "--enable", "INITRAMFS_COMPRESSION_GZIP",
                "--disable", "INITRAMFS_COMPRESSION_NONE",
                "--set-str", "LOCALVERSION", "-morganite"], check=True)
make("olddefconfig")
make("vmlinux")
kernel = obj / "vmlinux"
symbols = {}
for line in subprocess.check_output([args.prefix+"nm", "-n", kernel], text=True).splitlines():
    fields = line.split()
    if len(fields) == 3:
        symbols[fields[2]] = int(fields[0], 16)
boot = aurora / "software/examples/linux"
subprocess.run([args.prefix+"gcc", "-I"+str(boot), "-DKERNEL_ENTRY_ADDRESS="+hex(symbols["kernel_entry"]),
                "-c", boot / "start.S", "-o", obj / "start.o"], check=True)
subprocess.run([args.prefix+"objcopy", "-O", "binary", "-j", ".text", obj/"start.o", obj/"start.bin"], check=True)
sections = ".text __ex_table .notes .rodata __param .sdata __modver .data .data..page_aligned .init.text .init.data .exit.text".split()
subprocess.run([args.prefix+"objcopy", "-O", "binary", *[x for s in sections for x in ("-j", s)],
                kernel, obj/"vmlinux.bin"], check=True)
# Keep the firmware argv and reset addresses of the exact verified image.
with (image / "ram.dat").open() as file:
    segments = {}
    for line in file:
        if line.startswith("@"):
            address = int(line[1:].strip(), 16)
            segments[address] = bytearray()
        elif address != 0x300000:
            segments[address].extend(bytes.fromhex(line))
if set(segments) != {0x300000, 0x05f00000, 0x1c000000}:
    raise ValueError("unexpected baseline load map")
segments[0x300000] = (obj/"vmlinux.bin").read_bytes()
segments[0x1c000000] = (obj/"start.bin").read_bytes()
phys = lambda addr: addr & 0x1fffffff
bss_start = phys(symbols["__bss_start"])
bss_end = (phys(symbols["_end"]) + 3) & ~3
if phys(symbols["_text"]) != 0x300000 or bss_start > bss_end or bss_start < 0x300000 + len(segments[0x300000]):
    raise ValueError("inconsistent kernel load/BSS ranges")
for start, data in segments.items():
    if start < bss_end and start+len(data) > bss_start:
        raise ValueError("BSS overlaps an image segment")
with (obj/"ram.dat").open("w") as dest:
    for address, data in segments.items():
        dest.write(f"@{address:x}\n")
        # The standalone NEMU loader consumes exactly one byte per data line.
        for i in range(0, len(data), 4096):
            dest.write(data[i:i+4096].hex("\n")+"\n")
result = {"kernel_commit": kernel_rev, "kernel_entry": hex(symbols["kernel_entry"]),
          "dmw_patch_sha256": hashlib.sha256(dmw_patch.read_bytes()).hexdigest(),
          "tlb_patch_sha256": hashlib.sha256(tlb_patch.read_bytes()).hexdigest(),
          "clock_patch_sha256": hashlib.sha256(clock_patch.read_bytes()).hexdigest(),
          "sha256": hashlib.sha256((obj/"ram.dat").read_bytes()).hexdigest(),
          "config_sha256": hashlib.sha256((obj/".config").read_bytes()).hexdigest(),
          "zero_ranges": [[bss_start, bss_end]],
          "segments": [[a,len(b)] for a,b in segments.items()], "dma_window":[0x1f800000,0x1fa00000]}
(obj/"image.json").write_text(json.dumps(result,indent=2)+"\n")
print(json.dumps(result))
