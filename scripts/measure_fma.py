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
parser.add_argument("--tensor-k", type=int, choices=(0, 1, 2, 4), default=0)
args = parser.parse_args()
d = args.directory
d.mkdir(parents=True, exist_ok=True)
command = [sys.executable, root/"scripts/fma_bench.py", "--unroll", str(args.unroll)]
if args.tensor_k:
    command.append("--tensor")
source = subprocess.check_output(command, text=True)
program = assemble(source)
base = 0x1e000000
memory = bytearray(65536)
struct.pack_into("<8I", memory, 0, 0x3144474d, 0x10000, base+4096, len(program),
                 base+32768, 16, 32, 0)
struct.pack_into("<"+"I"*len(program), memory, 4096, *program)
(d/"in.bin").write_bytes(memory)
job = json.loads(subprocess.check_output([args.sim.resolve(), d/"in.bin", d/"out.bin",
                                          "2", "3", "0", "bench"]))
output = (d/"out.bin").read_bytes()
count = 16*32*16*1024
if args.tensor_k:
    assert not sum(job["fmas"])
    assert struct.unpack_from("<512I", output, 32768) == (16384*args.tensor_k,)*512
    job.update(tensor_k=args.tensor_k, expected_macs=count*args.tensor_k,
               int8_gops_at_100mhz=count*args.tensor_k*0.2/job["cycles"])
else:
    assert sum(job["fmas"]) == count
    assert struct.unpack_from("<512f", output, 32768) == (16384.,)*512
    job.update(fma_count=count, gflops_at_100mhz=count*0.2/job["cycles"])
job.update(unroll=args.unroll, utilization=count/(8*job["cycles"]))
(d/"performance.json").write_text(json.dumps(job, indent=2)+"\n")
print(d, job["cycles"],job["utilization"])
