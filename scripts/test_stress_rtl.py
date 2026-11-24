"""Run small stress scenes through the AXI GPU testbench."""
import argparse
import hashlib
import json
import struct
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image
from stage_stress import ROOT, assemble, bodies, fractal, raytrace


def main(sim, out):
    out.mkdir(parents=True, exist_ok=True)
    base, code, arg, src, dst = 0x1e000000, 4096, 8192, 16384, 32768
    stats = {}
    for name in ("raytrace", "mandelbrot", "julia", "nbody"):
        mem = bytearray(65536)
        kernel = name if name in ("raytrace","nbody") else "fractal"
        words = assemble((ROOT/"examples"/(kernel+".s")).read_text())
        if name == "nbody":
            params, initial, ref = bodies(256, 1, 6521)
            params = struct.pack("<2I", base+src, base+dst) + params
            mem[src:src+len(initial)] = initial
            groups, threads = 16, 16
        else:
            params, colors = raytrace(64,48) if name == "raytrace" else fractal(name,64,48,32)
            params = struct.pack("<I",base+dst) + params
            ref = colors.tobytes()
            groups, threads = 96, 32
        mem[dst:dst+len(ref)] = bytes([0xa5])*len(ref)
        struct.pack_into("<8I", mem, 0, 0x3144474d, 0x10000, base+code, len(words),
                         base+arg, groups, threads, 0)
        struct.pack_into("<"+"I"*len(words), mem, code, *words)
        mem[arg:arg+len(params)] = params
        inp, output = out/"input.bin", out/"output.bin"
        inp.write_bytes(mem)
        row = json.loads(subprocess.check_output([sim, inp, output, "16","65535","0","render"]))
        raw = output.read_bytes()[dst:dst+len(ref)]
        if min(row["groups"]) == 0 or sum(row["groups"]) != groups or row["fault"]:
            raise ValueError("missing workgroups: {}".format(row))
        if name == "nbody":
            error = float(np.abs(np.frombuffer(ref,"<f4") - np.frombuffer(raw,"<f4")).max())
            if not error <= 2e-6:
                raise ValueError("N-body max absolute error {}".format(error))
        else:
            error = int(np.abs(np.frombuffer(ref,"B").astype(int) - np.frombuffer(raw,"B")).max())
            if error > int(name=="raytrace"):
                raise ValueError("{} max channel difference {}".format(name,error))
            Image.frombytes("RGBA",(64,48),raw).save(out/(name+".png"))
        stats[name] = dict(row, max_abs_error=error, sha256=hashlib.sha256(raw).hexdigest())
        (out/"results.json").write_text(json.dumps(stats,indent=2)+"\n")
        print("{} passed in {} cycles".format(name,row["cycles"]), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sim",type=Path)
    parser.add_argument("out",type=Path)
    args = parser.parse_args()
    main(args.sim.resolve(),args.out)
