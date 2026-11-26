#!/usr/bin/env python3
"""Pack board assets and compare GPU RTL or downloaded board images with the CPU rasterizer."""
import argparse
import json
import struct
import subprocess
import tempfile
from pathlib import Path

import numpy as np

from reference import compare, read, render, save
from scenes import scenes
from shaders import assemble, programs, UNITS

BASE, CODE, ARG = 0x1e000000, 4096, 8192
VERTICES, CLIP, INDICES, SETUP, COLOR, DEPTH, WINNER = (0x3000,0x4000,0x5000,0x6000,0x10000,0x60000,0xb0000)


def simulate(sim, folder, scene, code, width, height, sms=2, readonly=False):
    mem = bytearray(WINNER + width*height*4)
    def put(offset, data):
        mem[offset:offset+len(data)] = data
    put(VERTICES, scene.vertices.tobytes())
    put(INDICES, scene.indices.tobytes())
    put(ARG+64, scene.matrices.tobytes())
    struct.pack_into("<8I", mem, ARG, BASE+VERTICES, BASE+CLIP, BASE+INDICES, BASE+SETUP,
                     len(scene.indices), BASE+COLOR, BASE+DEPTH, BASE+WINNER)
    if "bin" in code:
        if len(scene.indices) > 32:
            raise ValueError("the tile-mask ABI supports up to 32 triangles per draw")
        struct.pack_into("<2I", mem, ARG+32, BASE+0x8000, BASE+0x9000)
    # Distinct poison is checked by the reference, including background pixels.
    for offset in (COLOR, DEPTH, WINNER):
        put(offset, bytes([0xa5])*(width*height*4))
    stages = [("vertex_shift" if scene.shift else "vertex", 1, len(scene.vertices)),
              ("setup", 1, len(scene.indices)), ("pixel_swap" if scene.swap else "pixel", width*height//32, 32)]
    stats = []
    if "bin" in code:
        stages.insert(2,("bin", ((height+7)//8)*(width//32),4))
    with tempfile.TemporaryDirectory(dir=folder) as temp:
        inp, out = Path(temp)/"in", Path(temp)/"out"
        for name, groups, threads in stages:
            words = code[name]
            struct.pack_into("<8I", mem, 0, 0x3144474d,0x10000,BASE+CODE,len(words),BASE+ARG,groups,threads,0)
            struct.pack_into(f"<{len(words)}I", mem, CODE, *words)
            inp.write_bytes(mem)
            command = [sim,inp,out,str(sms),str((1<<sms)-1),"0","render"]
            if readonly:
                windows = {"vertex":(ARG,CLIP), "vertex_shift":(ARG,CLIP), "setup":(CLIP,SETUP),
                           "bin":(0x8000,0x9000), "pixel":(SETUP,COLOR), "pixel_swap":(SETUP,COLOR)}
                command.extend(map(str, windows[name]))
            counters = json.loads(subprocess.check_output(command))
            if sum(counters["groups"]) != groups:
                raise RuntimeError(f"{name} dispatched {counters['groups']} groups for {groups}")
            stats.append(dict(kernel=name, **counters))
            mem[:] = out.read_bytes()
    arrays = [np.frombuffer(mem, dtype=kind, count=width*height*count, offset=off).reshape(shape)
              for kind,count,off,shape in (("u1",4,COLOR,(height,width,4)),("<f4",1,DEPTH,(height,width)),
                                           ("<u4",1,WINNER,(height,width)))]
    save(folder, arrays)
    return arrays, stats


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode", choices=["assets","rtl","board"])
    p.add_argument("out", type=Path)
    p.add_argument("--width", type=int, default=320)
    p.add_argument("--height", type=int, default=240)
    p.add_argument("--sim", type=Path, default=Path("build/gpu-2/obj/VGpu"))
    p.add_argument("--sms", type=int, default=2)
    p.add_argument("--units", choices=UNITS, nargs="+", default=[])
    p.add_argument("--readonly", action="store_true", help="enable stage-specific data L1 windows in wide RTL simulation")
    p.add_argument("--software-assets", action="store_true", help="also package scalar shaders for on-board comparisons")
    p.add_argument("--scene", choices=scenes().keys(), action="append")
    args = p.parse_args()
    width, height = args.width, args.height
    if not (0 < width <= 320 and width % 32 == 0 and 0 < height <= 240):
        p.error("width must be a multiple of 32 through 320 and height in 1..240")
    args.out.mkdir(parents=True, exist_ok=True)
    if args.software_assets:
        if args.mode != "assets":
            p.error("--software-assets requires assets mode")
        (args.out/"sw_graphics-mask.bin").write_bytes(struct.pack("<I", 0))
        for name, lines in programs(width, height).items():
            words = assemble("\n".join(lines))
            (args.out/("sw_"+name+".bin")).write_bytes(struct.pack(f"<{len(words)}I", *words))
    if args.mode == "assets":
        mask = sum(1 << UNITS.index(name) for name in set(args.units))
        (args.out/"graphics-mask.bin").write_bytes(struct.pack("<I",mask))
    code = {}
    for name, lines in programs(width, height, args.units).items():
        assembly = "\n".join(lines)+"\n"
        code[name] = words = assemble(assembly)
        if len(words) > 1024:
            raise ValueError("oversized program "+name)
        if args.mode == "assets":
            (args.out/(name+".bin")).write_bytes(struct.pack(f"<{len(words)}I", *words))
    results, outputs = {}, {}
    failures = []
    for name, scene in scenes().items():
        if args.scene and name not in args.scene:
            continue
        if args.mode == "assets":
            (args.out/(name+".scene")).write_bytes(scene.encode())
            continue
        folder = args.out/name
        # The original board suites contain eight scenes. The paired benchmark
        # requests twin_cubes explicitly and stores it in a separate directory.
        if args.mode == "board" and not args.scene and name == "twin_cubes" and not folder.is_dir():
            continue
        folder.mkdir(exist_ok=True)
        expected = render(scene, width, height)
        save(folder/"reference", expected[:3])
        if name == "shared":
            assert expected[3].max() == 1
            assert np.count_nonzero(expected[3]) == np.sum(expected[2] != 0)
            assert np.count_nonzero(expected[3]) == round(width*.375)*round(height*.5)
        if name == "overlap":
            assert set(np.unique(expected[2])) == {0,2}
        if name == "degenerate":
            assert not np.any(expected[2])
        if args.mode == "rtl":
            actual, counters = simulate(args.sim.resolve(), folder, scene, code, width, height,args.sms,args.readonly)
        else:
            actual, counters = read(folder, width, height), []
        result = compare(actual, expected, name)
        result.update(triangles=len(scene.indices), width=width, height=height, stages=counters)
        results[name] = result
        print(name, json.dumps({k:v for k,v in result.items() if k not in ("stages","sha256")}), flush=True)
        if any(result[k] for k in ("coverage_errors","winner_errors","color_errors","depth_errors")):
            failures.append(name)
        outputs[name] = result["sha256"]
    if args.mode != "assets":
        if "triangle" in outputs and "fragment_swap" in outputs:
            assert outputs["triangle"][1:] == outputs["fragment_swap"][1:]
            assert outputs["triangle"][0] != outputs["fragment_swap"][0]
        if "triangle" in outputs and "vertex_shift" in outputs:
            assert outputs["triangle"][2] != outputs["vertex_shift"][2]
        (args.out/"comparisons.json").write_text(json.dumps({"backend":args.mode,"scenes":results},indent=2)+"\n")
    if failures:
        raise SystemExit("image comparison failed: "+", ".join(failures))


if __name__ == "__main__":
    main()
