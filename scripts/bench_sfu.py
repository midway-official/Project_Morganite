#!/usr/bin/env python3
"""Compare SFU and software math on identical GPU RTL and input data."""
import argparse
import hashlib
import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from stage_stress import ROOT, assemble, bodies, raytrace

REPORT = ROOT / "docs/16sm-wide-hbm-rtl-20261126"
KERNEL_REV = "d9295b4"
BASE, CODE, ARGS, SRC, DST = 0x1e000000, 4096, 8192, 16384, 32768


def measure(sim, kernel, folder, sms):
    words = assemble(kernel.read_text())
    program = struct.pack(f"<{len(words)}I", *words)
    image = bytearray(65536)
    if kernel.stem == "nbody":
        params, initial, reference = bodies(256, 1, 6521)
        groups, threads = 16, 16
        params = struct.pack("<2I", BASE + SRC, BASE + DST) + params
        image[SRC:SRC+len(initial)] = initial
        expected = np.frombuffer(reference, dtype="<f4").astype(float)
        dtype, tolerance = "<f4", 2e-6
    else:
        params, rgba = raytrace(64, 48)
        groups, threads = 96, 32
        params = struct.pack("<I", BASE + DST) + params
        reference = rgba.tobytes()
        expected = rgba.ravel().astype(int)
        dtype, tolerance = "B", 1
    struct.pack_into("<8I", image, 0, 0x3144474d, 0x10000, BASE+CODE, len(words),
                     BASE+ARGS, groups, threads, 0)
    image[ARGS:ARGS+len(params)] = params
    image[CODE:CODE+len(program)] = program
    image[DST:DST+len(reference)] = bytes([0xa5])*len(reference)
    inp, out = folder/"input.bin", folder/"output.bin"
    inp.write_bytes(image)
    row = json.loads(subprocess.check_output([sim, inp, out, str(sms), str((1 << sms) - 1), "0", "render"]))
    raw = out.read_bytes()[DST:DST+len(reference)]
    error = float(np.abs(np.frombuffer(raw, dtype=dtype) - expected).max())
    if not error <= tolerance or row["fault"] or sum(row["groups"]) != groups or min(row["groups"]) < 1:
        raise RuntimeError(f"{kernel.name} failed with error {error} and counters {row}")
    return dict(row, max_abs_error=error, program_sha256=hashlib.sha256(program).hexdigest(),
                output_sha256=hashlib.sha256(raw).hexdigest())


def bench(sim, folder, grid):
    sms = grid[0] * grid[1]
    kernels = folder / "examples"
    kernels.mkdir()
    for name in ("nbody", "raytrace"):
        source = subprocess.check_output(["git", "show", f"{KERNEL_REV}:examples/{name}.s"], cwd=ROOT)
        (kernels/f"{name}.s").write_bytes(source)
    results = {"kernel_revision": KERNEL_REV, "sm_grid": grid, "sfus_per_sm": 4,
               "nbody": {"bodies": 256, "steps": 1, "seed": 6521},
               "raytrace": {"width": 64, "height": 48, "spheres": 4}, "cube_a": {}}
    for mode in ("software", "sfu"):
        units = ["setup", "bins", "raster", "rop"]
        if mode == "sfu":
            units.append("sfu")
            subprocess.run(["patch", "-p1", "-i", REPORT/"rsqrt-kernels.patch"], cwd=folder, check=True)
        for name in ("nbody", "raytrace"):
            results[name][mode] = measure(sim, kernels/f"{name}.s", folder, sms)
        out = folder/mode
        subprocess.run([sys.executable, ROOT/"graphics/test_render.py", "rtl", out, "--sim", sim,
                        "--sms", str(sms), "--width", "64", "--height", "32", "--scene", "cube_a", "--units", *units], check=True)
        cube = json.loads((out/"comparisons.json").read_text())["scenes"]["cube_a"]
        results["cube_a"][mode] = dict(cube, cycles=sum(s["cycles"] for s in cube["stages"]))
    for name in ("nbody", "raytrace", "cube_a"):
        results[name]["speedup"] = results[name]["software"]["cycles"] / results[name]["sfu"]["cycles"]
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sim", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--grid", choices=("1x2", "4x4"), default="1x2")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as temp:
        result = bench(args.sim.resolve(), Path(temp), [int(n) for n in args.grid.split("x")])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print({name: result[name]["speedup"] for name in ("nbody", "raytrace", "cube_a")})
