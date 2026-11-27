"""Run programmable compute workloads through the AXI GPU testbench."""
import argparse
import hashlib
import json
import struct
import subprocess
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image
from stage_stress import ROOT, assemble, bodies, fractal, raytrace

BASE, CODE, ARG, SRC = 0x1e000000, 4096, 8192, 16384


def job(sim, temp, mem, words, sms, groups):
    struct.pack_into("<8I", mem, 0, 0x3144474d, 0x10000, BASE+CODE, len(words),
                     BASE+ARG, groups, 32, 0)
    struct.pack_into(f"<{len(words)}I", mem, CODE, *words)
    inp, output = temp/"input.bin", temp/"output.bin"
    inp.write_bytes(mem)
    row = json.loads(subprocess.check_output(
        [sim, inp, output, str(sms), str((1 << sms)-1), "0", "render"]))
    if (len(row["groups"]) != sms or sum(row["groups"]) != groups or row["fault"]
            or sum(count > 0 for count in row["groups"]) != min(sms, groups)):
        raise ValueError(f"incomplete dispatch: {row}")
    return row, output.read_bytes()


def nbody_case(args, temp, words):
    params, initial, reference = bodies(args.bodies, args.steps, 6521)
    src, dst = SRC, max(32768, SRC + len(initial))
    mem = bytearray(max(65536, dst + len(initial)))
    mem[src:src+len(initial)] = initial
    records = []
    for _ in range(args.steps):
        mem[dst:dst+len(initial)] = bytes([0xa5])*len(initial)
        packed = struct.pack("<2I", BASE+src, BASE+dst) + params
        mem[ARG:ARG+len(packed)] = packed
        row, data = job(args.sim, temp, mem, words, args.sms, args.bodies//32)
        records.append(row)
        mem[:] = data
        src, dst = dst, src
    raw = bytes(mem[src:src+len(initial)])
    error = float(np.abs(np.frombuffer(reference,"<f4") - np.frombuffer(raw,"<f4")).max())
    if not error <= 2e-6:
        raise ValueError(f"N-body max absolute error {error}")
    (args.out/"nbody.f32").write_bytes(raw)
    (args.out/"nbody-reference.f32").write_bytes(reference)
    result = dict(records[0]) if args.steps == 1 else {"jobs": records}
    return dict(result, cycles=sum(r["cycles"] for r in records), bodies=args.bodies,
                steps=args.steps, max_abs_error=error, sha256=hashlib.sha256(raw).hexdigest(),
                reference_sha256=hashlib.sha256(reference).hexdigest())


def render_case(args, temp, words, name):
    width, height, dst = args.width, args.height, 32768
    if name == "raytrace":
        params, reference = raytrace(width, height, dense=args.dense_rays)
        settings = {"spheres": 20 if args.dense_rays else 4}
    else:
        params, reference = fractal(name, width, height, args.iterations)
        settings = {"iterations": args.iterations, "samples_per_pixel": 4}
    tile_rows = min(args.tile_rows or height, height)
    mem = bytearray(max(65536, dst + width*tile_rows*4))
    packed = struct.pack("<I", BASE+dst) + params
    mem[ARG:ARG+len(packed)] = packed
    frame = np.empty_like(reference)
    tiles = []
    max_error = 0
    for y in range(0, height, tile_rows):
        rows = min(tile_rows, height-y)
        size = width*rows*4
        mem[dst:dst+size] = bytes([0xa5])*size
        struct.pack_into("<I", mem, ARG + (12 if name == "raytrace" else 44), y)
        counters, data = job(args.sim, temp, mem, words, args.sms, width*rows//32)
        tile = np.frombuffer(data, dtype="B", count=size, offset=dst).reshape(rows,width,4)
        error = int(np.abs(reference[y:y+rows].astype(np.int16) - tile).max())
        if error > int(name == "raytrace"):
            raise ValueError(f"{name} row {y} max channel error {error}")
        max_error = max(max_error, error)
        frame[y:y+rows] = tile
        tiles.append(dict(y=y, **counters))
    Image.fromarray(frame).save(args.out/(name+".png"))
    Image.fromarray(reference).save(args.out/(name+"-reference.png"))
    result = dict(tiles[0]) if len(tiles) == 1 else {"tiles": tiles}
    return dict(result, **settings, width=width, height=height, tile_rows=tile_rows,
                cycles=sum(t["cycles"] for t in tiles), max_abs_error=max_error,
                reference_sha256=hashlib.sha256(reference.tobytes()).hexdigest(),
                sha256=hashlib.sha256(frame.tobytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sim", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--width", type=int, default=64)
    parser.add_argument("--height", type=int, default=48)
    parser.add_argument("--iterations", type=int, default=32)
    parser.add_argument("--sms", type=int, default=16)
    parser.add_argument("--tile-rows", type=int)
    parser.add_argument("--dense-rays", action="store_true")
    parser.add_argument("--case", choices=("nbody","julia","raytrace","mandelbrot"),
                        action="append", dest="cases")
    parser.add_argument("--bodies", type=int, default=256)
    parser.add_argument("--steps", type=int, default=1)
    args = parser.parse_args()
    if (args.width < 32 or args.width & (args.width-1) or args.height < 1
            or not 1 <= args.iterations <= (SRC-ARG-52)//4 or args.bodies < 32
            or args.bodies % 32 or args.steps < 1 or not 1 <= args.sms <= 32
            or (args.tile_rows is not None and args.tile_rows < 1)):
        parser.error("invalid power-of-two width, workload size, or SM count")
    args.sim = args.sim.resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    results = {}
    with tempfile.TemporaryDirectory(dir=args.out) as folder:
        for name in args.cases or ("raytrace","mandelbrot","julia","nbody"):
            kernel = name if name in ("raytrace","nbody") else "fractal"
            words = assemble((ROOT/"examples"/(kernel+".s")).read_text())
            if name == "nbody":
                result = nbody_case(args, Path(folder), words)
            else:
                result = render_case(args, Path(folder), words, name)
            result["equivalent_100mhz_ms"] = result["cycles"]/100000
            results[name] = result
            (args.out/"results.json").write_text(json.dumps(results, indent=2)+"\n")
            print(f"{name} passed in {result['cycles']} cycles", flush=True)


if __name__ == "__main__":
    main()
