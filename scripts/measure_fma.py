#!/usr/bin/env python3
"""Compare the same 2-SM register-resident FMA workload, including job overhead."""
import argparse
import json
import struct
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "sw"))
from asm import assemble

parser = argparse.ArgumentParser()
parser.add_argument("sim", type=Path)
parser.add_argument("directory", type=Path)
parser.add_argument("--unroll", type=int, choices=(1, 2, 4, 8, 16), default=1)
args = parser.parse_args()
d = args.directory
d.mkdir(parents=True, exist_ok=True)
source = subprocess.check_output([sys.executable, root/"scripts/fma_bench.py"], text=True)
if args.unroll > 1:
    start = source.index("body:\n") + len("body:\n")
    end = source.index("addi r2,r2,-1")
    source = (source[:start] + source[start:end] * args.unroll + source[end:]).replace(
        "li r2, 1024", f"li r2, {1024//args.unroll}")
program = assemble(source)
base = 0x1e000000
memory = bytearray(65536)
struct.pack_into("<8I", memory, 0, 0x3144474d, 0x10000, base+4096, len(program),
                 base+32768, 16, 32, 0)
struct.pack_into("<"+"I"*len(program), memory, 4096, *program)
(d/"in.bin").write_bytes(memory)
job = json.loads(subprocess.check_output([args.sim.resolve(), d/"in.bin", d/"out.bin",
                                          "2", "3", "0", "bench"]))
count = sum(job["fmas"])
assert count == 8388608
assert struct.unpack_from("<512f",(d/"out.bin").read_bytes(),32768) == (16384.,)*512
job.update(unroll=args.unroll, fma_count=count, utilization=count/(8*job["cycles"]),
           gflops_at_100mhz=count*0.2/job["cycles"])
(d/"performance.json").write_text(json.dumps(job, indent=2)+"\n")
print(d, job["cycles"],job["utilization"])
