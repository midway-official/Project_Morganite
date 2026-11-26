#!/usr/bin/env python3
"""Check the GPU's degree-eight spherical power against double-precision polar math."""
import argparse
import hashlib
import json
import math
import random
import struct
import subprocess
import tempfile
from pathlib import Path

from bench_sfu import ARGS, BASE, CODE, DST, ROOT, SRC, assemble


def reference(point):
    x, y, z = point
    radius = math.hypot(x, y, z)
    inclination = 8 * math.atan2(math.hypot(x, y), z)
    azimuth = 8 * math.atan2(y, x)
    transverse = radius**8 * math.sin(inclination)
    return (transverse * math.cos(azimuth), transverse * math.sin(azimuth),
            radius**8 * math.cos(inclination))


def run(sim, second_refinement):
    seed = 6521
    rng = random.Random(seed)
    points = [(0, 0, 0), (0, 0, -2), (-0.0, 0, 2), (0, 1, 0), (-1, 0, 0),
              (1, 1, 1), (-2, 2, -2), (0, -1, 0)]
    for e in (-8, -24, -62, -63, -64, -80, -120):
        for z in (-1, 1):
            points.append((2.0**e, -(2.0**e), z))
    while len(points) < 1024:
        points.append(tuple(rng.uniform(-2, 2) for _ in range(3)))

    source = ROOT / "examples/mandelbulb_power8.s"
    words = assemble(source.read_text())
    program = struct.pack(f"<{len(words)}I", *words)
    image = bytearray(65536)
    threads = 32
    groups = len(points) // threads
    struct.pack_into("<8I", image, 0, 0x3144474d, 0x10000, BASE+CODE, len(words),
                     BASE+ARGS, groups, threads, 0)
    struct.pack_into("<3I", image, ARGS, BASE+SRC, BASE+DST, int(second_refinement))
    image[CODE:CODE+len(program)] = program
    for index, point in enumerate(points):
        struct.pack_into("<3f", image, SRC+16*index, *point)
    image[DST:DST+16*len(points)] = bytes([0xa5]) * (16*len(points))
    with tempfile.TemporaryDirectory() as temp:
        inp, out = Path(temp)/"input.bin", Path(temp)/"output.bin"
        inp.write_bytes(image)
        counters = json.loads(subprocess.check_output([sim, inp, out, "16", "65535", "0", "render"]))
        result = out.read_bytes()

    if counters["fault"] or sum(counters["groups"]) != groups or min(counters["groups"]) < 1:
        raise ValueError(f"GPU dispatch failed: {counters}")
    worst = 0.0
    tolerance = 3e-6 if second_refinement else 1e-4
    for index in range(len(points)):
        point = struct.unpack_from("<3f", image, SRC+16*index)
        out_offset = DST + 16 * index
        actual = struct.unpack_from("<3f", result, out_offset)
        expected = reference(point)
        radius8 = math.hypot(*point)**8
        error = math.dist(actual, expected)
        scaled = error / radius8 if radius8 else error
        if not scaled <= tolerance or (radius8 == 0 and error != 0):
            raise ValueError(f"{point}: GPU {actual}, reference {expected}, scaled error {scaled}")
        if result[out_offset + 12:out_offset + 16] != b"\xa5"*4:
            raise ValueError(f"overwrote padding for point {index}")
        worst = max(worst, scaled)
    return dict(seed=seed, points=len(points), threads_per_group=threads, sm_grid=[4, 4],
                gpu_cycles=counters["cycles"], groups_by_sm=counters["groups"],
                max_scaled_vector_error=worst, code_sha256=hashlib.sha256(program).hexdigest(),
                output_sha256=hashlib.sha256(result[DST:DST+16*len(points)]).hexdigest())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sim", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = {name: run(args.sim.resolve(), second)
              for name, second in (("one_refinement", False), ("two_refinements", True))}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
