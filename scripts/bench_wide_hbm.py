#!/usr/bin/env python3
"""Compare a fixed 16-SM programmable page-copy workload on one and two 256-bit ports."""
import argparse
import hashlib
import json
import random
import struct
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "sw"))
from asm import assemble

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("single", type=Path)
parser.add_argument("dual", type=Path)
parser.add_argument("out", type=Path)
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)
words = assemble((root / "examples/hbm_copy.s").read_text())
program = struct.pack(f"<{len(words)}I", *words)
base = 0x1e000000
src, dst = 0x10000, 0x90000
groups, page, repeats = 64, 4096, 4
length = groups * page
raw = bytearray(random.Random(732).randbytes(2 << 20))
struct.pack_into("<8I", raw, 0, 0x3144474d, 0x10000, base+0x1000, len(words), base+0x2000, groups, 32, 0)
struct.pack_into("<2I", raw, 0x2000, base+src, base+dst)
raw[0x1000:0x1000+len(program)] = program
input_file = args.out / "input.bin"
input_file.write_bytes(raw)
raw[dst:dst+length] = raw[src:src+length]
results = {"sms": 16, "clock_assumption_hz": 100_000_000, "groups": groups,
           "bytes_each_direction": length*repeats, "program_sha256": hashlib.sha256(program).hexdigest()}
for name, sim in (("single", args.single), ("dual", args.dual)):
    output = args.out / f"{name}.bin"
    r = json.loads(subprocess.check_output([sim.resolve(), input_file, output, "16", "65535", "0", "copy"]))
    if output.read_bytes() != raw or sum(r["groups"]) != groups or min(r["groups"]) == 0:
        raise RuntimeError(f"{name} copy or group ownership mismatch")
    if sum(r["write_beats"]) != length*repeats//32 or (name == "dual" and not r["dual_data_cycles"]):
        raise RuntimeError(f"{name} copy missed or duplicated DMA transfers")
    r["useful_GBps_per_direction"] = length*repeats * 0.1 / r["cycles"]
    results[name] = r
results["speedup"] = results["single"]["cycles"] / results["dual"]["cycles"]
(args.out / "results.json").write_text(json.dumps(results, indent=2)+"\n")
print(json.dumps({k: results[k]["cycles"] for k in ("single", "dual")} | {"speedup": results["speedup"]}))
