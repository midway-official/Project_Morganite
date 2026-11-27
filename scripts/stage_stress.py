"""Assemble the GPU programs, generate independent references and exercise small ISA cases."""
import argparse
import ctypes
import hashlib
import json
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "sw"))
from asm import assemble
from model import Memory, Model


def fractal(name, width, height, steps):
    julia = int(name == "julia")
    xmin, ymin = (-1.5 if julia else -2.25), -1.125
    dx = 3.0 / width
    dy = 2.25 / height
    jx, jy = np.float32(-0.8), np.float32(0.156)
    fields = (width.bit_length() - 1, width - 1, steps, julia, xmin, ymin, dx, dy, jx, jy)
    fmaf = ctypes.CDLL("libm.so.6").fmaf
    fmaf.argtypes = (ctypes.c_float, ctypes.c_float, ctypes.c_float)
    fmaf.restype = ctypes.c_float
    x_indices = np.arange(width, dtype=np.float32)
    y_indices = np.arange(height, dtype=np.float32)
    stops = np.array([[9,15,45], [20,68,125], [30,165,183], [252,228,154],
                      [245,108,70], [94,37,99], [9,15,45]])
    phase = 6 * np.mod(np.log2(1 + np.arange(steps + 1)/6) * 0.76, 1)
    palette = np.full((steps + 1, 4), 255, dtype=np.uint8)
    for c in range(3):
        palette[:, c] = np.rint(np.interp(phase, np.arange(7), stops[:, c]))
    palette[-1, :3] = (5, 8, 24)
    accum = np.zeros((height, width, 4), dtype=np.uint16)
    for ox, oy in ((.25,.25), (.75,.25), (.25,.75), (.75,.75)):
        xs = np.array([fmaf(float(v)+ox, dx, xmin) for v in x_indices], dtype="f")
        ys = np.array([fmaf(float(v)+oy, dy, ymin) for v in y_indices], dtype="f")
        x, y = np.meshgrid(xs, ys)
        cx, cy = (jx, jy) if julia else (x.copy(), y.copy())
        if not julia:
            x.fill(0)
            y.fill(0)
        count = np.zeros_like(x, dtype=np.uint16)
        live = np.ones_like(x, dtype=bool)
        for _ in range(steps):
            x, y = (x*x - y*y) + cx, ((x+x)*y) + cy
            live &= x*x + y*y <= 4
            count += live
            x[~live] = y[~live] = 0
        accum += palette[count]
    return struct.pack("<4I6fI", *fields, 0) + palette.tobytes(), (accum // 4).astype("B")


def raytrace(width, height, dense=False):
    spheres = [(-.65,.10,3.1,.81, (232,89,74)), (.68,.24,3.25,.68,(51,187,175)),
               (.04,-.63,2.45,.48,(245,190,82)), (1.14,-.54,4.2,.36,(115,126,222))]
    if dense:
        spheres += [(x, y, 3.8 + .2*((i+j)%3), .28 + .04*((2*i+j)%3),
                     (72+35*i, 82+36*j, 184-12*i))
                    for j,y in enumerate((-.95,-.31,.43,1.04)) for i,x in enumerate((-1.55,-.52,.59,1.52))]
    packed = bytearray(struct.pack("<3IfI", width.bit_length()-1, width-1, 0, 4/width, len(spheres)))
    y, x = np.indices((height, width), dtype="d")
    x, y = (x+.5)*4/width-2, (y+.5)*4/width-1.5
    hit = np.full(x.shape, -1)
    depth = np.full(x.shape, 100.0)
    for i, (cx, cy, cz, radius, rgb) in enumerate(spheres):
        packed.extend(struct.pack("<8f", cx, cy, cz, radius**2, 1/radius, *rgb))
        disc = radius**2 - (x-cx)**2 - (y-cy)**2
        t = cz - np.sqrt(np.maximum(0, disc))
        wins = (disc > 0) & (t < depth)
        hit[wins], depth[wins] = i, t[wins]
    color = np.empty((*x.shape, 4), dtype="B")
    color[:] = [20,36,48,255]
    for i, (cx, cy, cz, radius, rgb) in enumerate(spheres):
        lit = np.maximum(0, (.6*(x-cx) - .8*(depth-cz))/radius)
        for j, (sx, sy, sz, sr, _) in enumerate(spheres):
            if i == j:
                continue
            vx, vy, vz = sx-x, sy-y, sz-depth
            projection = .6*vx - .8*vz
            lit[(projection > 0) & (vx*vx+vy*vy+vz*vz-projection**2 < sr**2)] = 0
        mask = hit == i
        color[mask, :3] = np.rint((.25+.75*lit[mask, None])*np.array(rgb))
    return bytes(packed), color


def bodies(n, steps, seed):
    rng = np.random.RandomState(seed)
    initial = np.zeros((n, 8), dtype="<f4")
    initial[:, :3] = rng.uniform(-1, 1, (n, 3))
    initial[:, 3] = rng.uniform(0.5, 1.5, n) / n
    initial[:, 4:7] = rng.uniform(-0.05, 0.05, (n, 3))
    eps2, dt = 1 / 256, 1 / 512
    state = initial.astype("d")
    for _ in range(steps):
        delta = state[None, :, :3] - state[:, None, :3]
        r2 = (delta * delta).sum(axis=2) + eps2
        scale = state[None, :, 3] / (r2 * np.sqrt(r2))
        state[:, 4:7] += dt * (delta * scale[:, :, None]).sum(axis=1)
        state[:, :3] += dt * state[:, 4:7]
    return struct.pack("<Iff", n, eps2, dt), initial.tobytes(), state.astype("<f4").tobytes()


def model_check(words, name):
    mem = Memory(0, bytes(32768))
    model = Model(mem)
    src, dst = 4096, 16384
    if name == "nbody":
        params, init, ref = bodies(32, 2, 37)
        mem.data[src:src + len(init)] = init
        for _ in range(2):
            mem.data[:20] = struct.pack("<2I", src, dst) + params
            model.group(words, 0, 32, 0)
            src, dst = dst, src
        got = np.frombuffer(mem.data[src:src + len(ref)], dtype="<f4")
        error = float(np.max(np.abs(got - np.frombuffer(ref, dtype="<f4"))))
        if not error <= 2e-6:
            raise ValueError(f"{name}: max error {error}")
        return {"bodies": 32, "steps": 2, "max_abs_error": error}
    params, ref = raytrace(32, 24) if name == "raytrace" else fractal(name, 32, 24, 24)
    mem.data[:4+len(params)] = struct.pack("<I", dst) + params
    for gid in range(24):
        model.group(words, gid, 32, 0)
    delta = np.abs(np.frombuffer(mem.data[dst:dst+ref.nbytes], dtype="B").astype(int) - ref.ravel())
    if delta.max() > (1 if name == "raytrace" else 0):
        raise ValueError(f"{name}: RGBA mismatch")
    return {"width": 32, "height": 24, "max_channel_error": int(delta.max())}


def stage(out):
    out.mkdir(parents=True, exist_ok=True)
    manifest = {"cases": {}, "model": {}, "sha256": {}}
    for name in ("mandelbrot", "julia", "raytrace", "nbody"):
        kernel = "fractal" if name in ("mandelbrot", "julia") else name
        source = ROOT / "examples" / (kernel + ".s")
        words = assemble(source.read_text())
        (out / (kernel + ".bin")).write_bytes(struct.pack("<" + "I" * len(words), *words))
        manifest["model"][name] = model_check(words, name)
        if name == "nbody":
            params, data, expected = bodies(512, 4, 6521)
            (out / "nbody.init").write_bytes(data)
            config = dict(bodies=512, steps=4, seed=6521, threads=32, groups=16, max_abs_error=2e-6)
        else:
            width, height = (512, 384) if name == "raytrace" else (1024, 768)
            params, color = raytrace(width,height) if name == "raytrace" else fractal(name,width,height,192)
            expected = color.tobytes()
            Image.fromarray(color).save(out / (name + "-reference.png"))
            config = dict(width=width, height=height, tile_rows=16, threads=32,
                          groups=width*16//32, max_channel_error=int(name == "raytrace"))
            if name != "raytrace":
                config.update(iterations=192, samples_per_pixel=4)
        (out / (name + ".ref")).write_bytes(expected)
        manifest["cases"][name] = dict(config, program=kernel + ".bin", params_hex=params.hex(),
                                       source_sha256=hashlib.sha256(source.read_bytes()).hexdigest())
    for file in sorted(out.iterdir()):
        if file.suffix in (".init", ".ref", ".bin"):
            manifest["sha256"][file.name] = hashlib.sha256(file.read_bytes()).hexdigest()
    (out / "suite.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest["model"], indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    stage(parser.parse_args().out)
