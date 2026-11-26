#!/usr/bin/env python3
"""Run the Linux payload binaries on wide GPU RTL and validate the retired work."""
import argparse
import hashlib
import json
import struct
import subprocess
from pathlib import Path

import numpy as np

from stage_stress import bodies

BASE, CODE, ARGS, SRC, DST = 0x1e000000, 4096, 8192, 16384, 32768
GROUPS, THREADS = 16, 32
CLOCK_HZ = 100_000_000


def dispatch(sim, payload, folder, name, mask, params=b"", initial=b""):
    program = (payload/(name+".bin")).read_bytes()
    if not program or len(program)%4 or len(program)>ARGS-CODE:
        raise ValueError("invalid "+name+" program length")
    image = bytearray(65536)
    struct.pack_into("<8I",image,0,0x3144474d,0x10000,BASE+CODE,len(program)//4,
                     BASE+ARGS,GROUPS,THREADS,0)
    image[CODE:CODE+len(program)] = program
    image[ARGS:ARGS+len(params)] = params
    image[SRC:SRC+len(initial)] = initial
    inp, out = folder/"input.bin",folder/"output.bin"
    inp.write_bytes(image)
    cmd = [sim,inp,out,"16",str(mask),"0","render"]
    if initial:
        cmd += [str(SRC),str(SRC+len(initial))]
    job = json.loads(subprocess.check_output(cmd))
    if (job["fault"] or sum(job["groups"])!=GROUPS or
        any(bool(count) != bool(mask & 1<<i) for i,count in enumerate(job["groups"]))):
        raise ValueError("incomplete dispatch for "+name)
    return job,out.read_bytes(),hashlib.sha256(program).hexdigest()


def benchmark(sim, payload, folder, kernels):
    folder.mkdir(parents=True,exist_ok=True)
    results = {"backend":"16-SM RTL","assumed_hz":CLOCK_HZ}
    for name in kernels:
        if name=="nbody":
            params,initial,ref = bodies(GROUPS*THREADS,1,6521)
            params = struct.pack("<2I",BASE+SRC,BASE+DST)+params
            job,raw,digest = dispatch(sim,payload,folder,name,65535,params,initial)
            got=np.frombuffer(raw[DST:DST+len(ref)],dtype="<f4")
            error=float(np.max(np.abs(got-np.frombuffer(ref,dtype="<f4"))))
            if not error<=2e-6:
                raise ValueError("N-body reference mismatch: "+str(error))
            results[name]=dict(job,bodies=GROUPS*THREADS,steps=1,seed=6521,max_abs_error=error,
                               program_sha256=digest, readonly=True)
        else:
            fma = name=="fma_bench"
            ops = GROUPS*THREADS*16384*(2 if fma else 8)
            expected = struct.pack("<f",16384.) if fma else struct.pack("<I",65536)
            test = {"groups":GROUPS,"threads":THREADS,"instructions_per_thread":16384}
            for mask in (1,65535):
                job,raw,digest = dispatch(sim,payload,folder,name,mask)
                if raw[ARGS:ARGS+GROUPS*THREADS*4] != expected*(GROUPS*THREADS):
                    raise ValueError("per-thread accumulator mismatch: "+name)
                if fma and sum(job["fmas"])!=ops//2:
                    raise ValueError("unexpected retired FMA count")
                test[str(mask)]=dict(job,operations=ops,giga_ops_per_s=ops*0.1/job["cycles"])
            test["speedup"]=test["1"]["cycles"]/test["65535"]["cycles"]
            test["program_sha256"]=digest
            results[name]=test
        (folder/"results.json").write_text(json.dumps(results,indent=2)+"\n")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sim",type=Path)
    parser.add_argument("payload",type=Path,help="gpu directory from the staged Linux package")
    parser.add_argument("out",type=Path)
    parser.add_argument("--kernels",nargs="+",choices=("fma_bench","tensor_bench","nbody"),
                        default=("fma_bench","tensor_bench","nbody"))
    args=parser.parse_args()
    benchmark(args.sim.resolve(),args.payload,args.out,args.kernels)
