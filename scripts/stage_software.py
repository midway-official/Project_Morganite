#!/usr/bin/env python3
"""Package the built LA32R image, actual GPU programs and board test runtime."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import struct
import sys
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("out",type=Path)
p.add_argument("--sms", type=int, choices=(2, 8, 16, 32), default=2,
               help="SM count in the matching FPGA build")
p.add_argument("--gpu-features", type=lambda value: int(value, 0), default=0,
               help="GPU feature register value in the matching FPGA build")
p.add_argument("--verify-load", action="store_true",
               help="compare the entire image and zero ranges over JTAG before releasing the CPU")
args = p.parse_args()
out = args.out.resolve()
out.mkdir(parents=True,exist_ok=False)
shutil.copytree(root/"baseline/runtime",out/"runtime")
shutil.copytree(root/"build/initramfs-extra/gpu",out/"gpu")
for src in ("build/kernel/ram.dat","build/kernel/image.json","board/start_test.py","board/uart_jobs.py",
            "board/memory_layout.py","board/uart_cache.py","board/uart_stress.py","board/uart_graphics.py"):
    shutil.copy2(root/src,out)
shutil.copy2(root/"board/observe.tcl",out/"runtime")
if args.verify_load:
    shutil.copy2(root/"board/verify_image.tcl",out/"runtime")
    loader = out/"runtime/debug_load.tcl"
    original = loader.read_text()
    marker = '    puts "DEBUG_IMAGE_LOADED_BYTES=$total"'
    if original.count(marker) != 1:
        raise ValueError("unexpected board loader")
    loader.write_text(original.replace(marker, "    source [file join $script_dir verify_image.tcl]\n"
                                       "    morganite_verify_image $axi $image $zero_ranges\n" + marker))
bridge = out/"runtime/uart_bridge.tcl"
original = bridge.read_text()
marker = "        set status [u50hw::read_word $axi 0x1fe10000]"
if original.count(marker)!=1:
    raise ValueError("unexpected UART bridge")
bridge.write_text(original.replace("set clients {}", "source [file join $script_dir observe.tcl]\nset clients {}",1)
                  .replace(marker,"        morganite_observe $axi [file dirname $ready_file]\n"+marker))
layout=json.loads((out/"image.json").read_text())
if layout["sha256"] != sha(out/"ram.dat"):
    raise ValueError("image hash mismatch")
sources = [f for d in ("board","linux","graphics","examples") for f in (root/d).glob("*") if f.is_file()]
sources.extend(root/s for s in ("scripts/stage_software.py","scripts/fma_bench.py","scripts/bench_graphics_board.py","scripts/stage_stress.py","sw/asm.py"))
gpu_config = {"sms": args.sms}
mask_file = out/"gpu/graphics-mask.bin"
if mask_file.is_file():
    mask, = struct.unpack("<I", mask_file.read_bytes())
    if mask & ~31:
        raise ValueError("unknown graphics instruction requirement")
    gpu_config["required_graphics_mask"] = mask
    if (out/"gpu/sw_graphics-mask.bin").is_file():
        sys.path.insert(0, str(root/"graphics"))
        from reference import render
        from scenes import scenes
        refs = out/"references"
        refs.mkdir()
        color, depth, winner, _ = render(scenes()["twin_cubes"],320,240)
        for name,array in (("color.rgba",color),("depth.f32",depth),("winner.u32",winner)):
            (refs/(name+".gz")).write_bytes(gzip.compress(array.tobytes(),mtime=0))
manifest={"source_commit":subprocess.check_output(["git","rev-parse","HEAD"],cwd=root,text=True).strip(),
          "sources":{str(f.relative_to(root)):sha(f) for f in sorted(sources)},
          "gpu_config":gpu_config,
          "gpu_features":args.gpu_features,
          "cli":sha(root/"build/initramfs-extra/bin/mgpu"),
          "gpu_programs_and_scenes":{p.name:sha(p) for p in sorted((out/"gpu").iterdir())},
          "image":layout}
(out/"software.json").write_text(json.dumps(manifest,indent=2)+"\n")
(out/"files.sha256").write_text("".join(f"{sha(f)}  {f.relative_to(out)}\n"
                             for f in sorted(out.rglob("*")) if f.is_file()))
