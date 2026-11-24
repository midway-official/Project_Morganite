#!/usr/bin/env python3
import argparse
import json
import random
import struct
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "sw"))
from asm import assemble
from model import Memory, Model

parser = argparse.ArgumentParser()
parser.add_argument("sim", type=Path)
parser.add_argument("sms", type=int)
parser.add_argument("out", type=Path)
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)
base, code = 0x1e000000, 0x0ff0
arg = 0x3ff4
tests = [(n, (root / "examples" / (n + ".s")).read_text(), 0, "normal")
         for n in ("integer", "axpy", "shared", "divergence", "strides")]
tests += [
    ("rerror", "exit", 6, "rerror"),
    ("read-protocol", "exit", 6, "rprotocol"),
    ("berror", "arg r1\nsw r0, 0(r1)\nexit", 5, "berror"),
    ("cancel", "jmp 0", 7, "cancel"),
    ("wrap", "li r1,0xffffffff\nlbu r2,1(r1)\nexit", 3, "wrap"),
    ("below", "arg r1\nlw r2,-4(r1)\nexit", 3, "below"),
]
evidence = []
for name, source, fault, mode in tests:
    words = assemble(source)
    groups = max(9, args.sms) if name in ("integer", "axpy", "strides") else 1
    threads = 31
    argument = 0 if mode == "below" else arg
    mem = Memory(base, random.Random(742).randbytes(65536))
    struct.pack_into("<8I", mem.data, 0, 0x3144474d, 0x10000, base+code, len(words),
                     base+argument, groups, threads, 0)
    struct.pack_into("<" + "I"*len(words), mem.data, code, *words)
    if name == "axpy":
        struct.pack_into("<fII", mem.data, argument, 1.125, base+0x4ff0, base+0x6ff4)
    inp, output = args.out / "input.bin", args.out / "output.bin"
    inp.write_bytes(mem.data)
    counts = []
    if not fault:
        model = Model(mem)
        for gid in range(groups):
            counts.append(model.group(words, gid, threads, base+argument, program_base=base+code))
    result = json.loads(subprocess.check_output([
        args.sim.resolve(), inp, output, str(args.sms), str((1 << args.sms)-1), str(fault), mode]))
    if fault:
        address = {"wrap":0, "below":base-4, "rerror":base+code, "rprotocol":base+code,
                   "berror":base+arg, "cancel":0}[mode]
        if result["address"] != address:
            raise ValueError(f"{name} first fault {result}")
    elif output.read_bytes() != mem.data or sum(result["groups"]) != groups or (
            sum(result["fmas"]) != sum(c["fmas"] for c in counts)):
        raise ValueError(f"{name} result {result}")
    if name in ("integer", "axpy", "strides") and (
            min(result["groups"]) == 0 or (args.sms > 1 and sum(result["active_cycles"]) <= result["cycles"])):
        raise ValueError(f"missing concurrent SM work {result}")
    evidence.append(dict(kernel=name, **result))
if max(r["longest_burst"] for r in evidence) < 4 or not sum(r["byte_writes"] for r in evidence) or not sum(
        r["half_writes"] for r in evidence):
    raise ValueError("missing coalesced or narrow transactions")
if args.sms > 1 and not sum(r["concurrent_rw_cycles"] for r in evidence):
    raise ValueError("missing overlapping DMA read and write")
(args.out / "results.json").write_text(json.dumps(evidence, indent=2) + "\n")
print(f"{args.sms} SM: {len(evidence)} AXI-Lite-submitted jobs verified, including error recovery")
