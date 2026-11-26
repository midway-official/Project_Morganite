#!/usr/bin/env python3
"""Prepare paired software/SFU ray programs and pixel references, or time them on wide RTL."""
import argparse
import hashlib
import json
import struct
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image

from stage_stress import ROOT, assemble, raytrace

BASE, CODE, ARGS, OUT = 0x1e000000, 4096, 8192, 0x10000


def stage(folder, width, height):
    if width < 32 or width & (width - 1) or height < 1 or OUT + width*height*4 > 2**21:
        raise ValueError("require power-of-two width >= 32, positive height, and image within DMA allocation")
    folder.mkdir(parents=True, exist_ok=True)
    params, expected = raytrace(width, height, dense=True)
    source = (ROOT / "examples/raytrace.s").read_text()
    start = source.index("# sqrt(h) via")
    end = source.index("fmul r19, r17, r19", start)
    sfu = source[:start] + """rsqrt.approx.f32 r19, r17
fmul r20, r17, r26
fmul r21, r19, r19
fma r21, r20, r21, r27
fmul r19, r19, r21
""" + source[end:]
    sfu = sfu.replace("li r25, 0x5f3759df\n", "").replace("li r26, 0x3f000000", "li r26, 0xbf000000")
    (folder / "ray-params.bin").write_bytes(params)
    for mode, asm in (("software",source),("sfu",sfu)):
        words = assemble(asm)
        (folder / ("ray-"+mode+".bin")).write_bytes(struct.pack(f"<{len(words)}I", *words))
    (folder/"ray-reference.rgba").write_bytes(expected.tobytes())
    return params, expected


def run(sim, folder, width, height, sms):
    params, expected = stage(folder,width,height)
    results = {}
    for mode in ("software","sfu"):
        program = (folder/("ray-"+mode+".bin")).read_bytes()
        image = bytearray(OUT+width*height*4)
        struct.pack_into("<8I",image,0,0x3144474d,0x10000,BASE+CODE,len(program)//4,
                         BASE+ARGS,width*height//32,32,0)
        image[CODE:CODE+len(program)] = program
        struct.pack_into("<I",image,ARGS,BASE+OUT)
        image[ARGS+4:ARGS+4+len(params)] = params
        image[OUT:] = bytes([0xa5])*(width*height*4)
        inp, out = folder/"input.bin",folder/"output.bin"
        inp.write_bytes(image)
        ro_end = ARGS+((4+len(params)+31)//32)*32
        job = json.loads(subprocess.check_output([sim,inp,out,str(sms),str((1<<sms)-1),"0","render",str(ARGS),str(ro_end)]))
        frame = np.frombuffer(out.read_bytes()[OUT:],np.uint8).reshape(expected.shape)
        error = int(np.abs(expected.astype(int) - frame).max())
        if job["fault"] or sum(job["groups"]) != width*height//32 or error > 1:
            raise ValueError(f"{mode} ray test failed: max channel error {error}, fault {job['fault']}")
        results[mode] = dict(job,max_channel_error=error,frame_sha256=hashlib.sha256(frame.tobytes()).hexdigest(),
                             program_sha256=hashlib.sha256(program).hexdigest())
        Image.fromarray(frame).save(folder/(mode+".png"))
    result = dict(backend="16-SM RTL" if sms==16 else f"{sms}-SM RTL", width=width,height=height,
                  spheres=20, primary_rays=width*height, shadow_rays="one per lit hit", readonly=True,
                  modes=results, speedup=results["software"]["cycles"]/results["sfu"]["cycles"])
    (folder/"ray.json").write_text(json.dumps(result,indent=2)+"\n")
    print(result["speedup"])


if __name__ == "__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("folder",type=Path)
    p.add_argument("--width",type=int,default=128)
    p.add_argument("--height",type=int,default=96)
    p.add_argument("--sms",type=int,default=16)
    p.add_argument("--sim",type=Path)
    args=p.parse_args()
    if args.sim:
        run(args.sim.resolve(),args.folder,args.width,args.height,args.sms)
    else:
        stage(args.folder,args.width,args.height)
