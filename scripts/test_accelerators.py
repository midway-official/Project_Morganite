#!/usr/bin/env python3
"""Submit cache and cooperative INT8 matrix tests through AXI-Lite."""
import argparse
import json
import random
import struct
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "sw"))
from asm import assemble, disassemble
from model import Memory, Model

parser = argparse.ArgumentParser()
parser.add_argument("sim", type=Path)
parser.add_argument("sms", type=int)
parser.add_argument("out", type=Path)
parser.add_argument("--k", type=int, default=4)
parser.add_argument("--no-dcache", action="store_true")
parser.add_argument("--no-icache", action="store_true")
args = parser.parse_args()
args.out.mkdir(exist_ok=True, parents=True)
base, code, arg = 0x1e000000, 0xff4, 0x4000

cache = """arg r1
lw r2,0(r1)
lb r3,0(r1)
add r4,r2,r3
gid r5
addi r4,r4,1
slli r5,r5,2
add r5,r5,r1
sw r4,64(r5)
exit"""
matrix = """arg r3
tid r4
slli r4,r4,2
add r3,r3,r4
lw r5,0(r3)
lw r6,128(r3)
lw r7,256(r3)
mma.s8 r5,r5,r6,r7
sw r5,384(r3)
exit"""
conflict = """arg r1
tid r2
slli r2,r2,2
add r1,r1,r2
lw r3,0(r1)
li r5,4096
add r5,r5,r1
lw r4,0(r5)
lw r6,0(r1)
lw r7,0(r1)
add r3,r3,r4
add r3,r3,r6
add r3,r3,r7
sw r3,64(r5)
exit"""
results = []
tests = [] if args.no_dcache else [
        ("l1",cache,8,0), ("ro-write","arg r1\nsh r0,2(r1)\nexit",5,3),
        ("fill-error",cache,8,6), ("fill-cancel",cache,8,7), ("conflict",conflict,4,0),
        ("cross-sm","arg r1\naddi r2,r0,42\nsw r2,32(r1)\nexit\n"
                    "arg r1\nlw r2,32(r1)\nlw r3,32(r1)\nadd r4,r2,r3\nsw r4,64(r1)\nexit",8,0)]
if not (args.no_icache and args.no_dcache):
    tests.append(("code-write", f"li r1,{base+code}\nsb r0,0(r1)\nexit",7,3))
tests += [("matrix",matrix,28,0 if args.k else 2), ("partial-tile",matrix,7,2)]
for name, source, threads, fault in tests:
    words = assemble(source)
    assert assemble("\n".join(map(disassemble,words))) == words
    groups = args.sms if name == "l1" else 1
    mem = Memory(base,random.Random(824).randbytes(65536))
    struct.pack_into("<8I",mem.data,0,0x3144474d,0x10000,base+code,4 if name == "cross-sm" else len(words),
                     base+arg,groups,threads,0)
    struct.pack_into("<"+"I"*len(words),mem.data,code,*words)
    struct.pack_into("<I",mem.data,arg,7)
    inp, out = args.out/"input.bin", args.out/"output.bin"
    inp.write_bytes(mem.data)
    if name in ("matrix","conflict") and not fault:
        Model(mem,tensor_k=args.k).group(words,0,threads,base+arg,program_base=base+code)
    result = json.loads(subprocess.check_output([args.sim.resolve(),inp,out,str(args.sms),
        str((1 << args.sms)-1),str(fault),name]))
    if name in ("matrix","conflict") and not fault:
        assert out.read_bytes() == mem.data, name
    if fault:
        addr = {"ro-write":base+arg+2, "fill-error":base+arg, "fill-cancel":0, "code-write":base+code,
                "matrix":base+code+7*4, "partial-tile":base+code+7*4}[name]
        assert result["address"] == addr, result
    results.append(dict(case=name,**result))
(args.out/"results.json").write_text(json.dumps(results,indent=2)+"\n")
print(f"{args.sms} SM, K={args.k}: cache visibility, protection, refill error and matrix tests passed")
