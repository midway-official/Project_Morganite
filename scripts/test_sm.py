#!/usr/bin/env python3
import argparse
import json
import random
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "sw"))
from asm import assemble, disassemble
from model import Fault, Memory, Model

parser = argparse.ArgumentParser()
parser.add_argument("simulator", type=Path)
parser.add_argument("--out", type=Path, default=ROOT / "build/sm/cases")
parser.add_argument("--wide", action="store_true")
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)
base = 0x1E000000
tests = [(name, (ROOT / "examples" / (name + ".s")).read_text()) for name in
         ["integer", "divergence", "shared", "axpy"]]
tests += [("bad-align", "arg r1\n lw r2, 1(r1)\n exit"),
          ("overflow", "li r1, 0xffffffff\n lw r2, 1(r1)\n exit"),
          ("invalid", ".word 0xffffffff"),
          ("stack-underflow", "join\n exit"),
          ("bad-barrier", "tid r1\n split r1, other\n bar\n next end\n other: fence\n end: join\n exit")]
if args.wide:
    tests += [
        ("store-load", "arg r1\ntid r2\nslli r3,r2,2\nadd r1,r1,r3\nsw r2,256(r1)\n"
         "lw r4,256(r1)\nsw r4,512(r1)\nexit"),
        ("local-global", "arg r1\ntid r2\nsrli r3,r2,3\nslli r4,r2,2\nadd r1,r1,r4\n"
         "sltiu r5,r3,2\nbz r5,local\nlw r6,128(r1)\njmp sync\nlocal: s.sw r2,0(r4)\n"
         "s.lw r6,0(r4)\nsync: bar\nsw r6,512(r1)\nexit"),
    ]
    # Warp 0 waits for a global load, while other warps feed independent FMA chains.
    tests.append(("fma-load", "arg r1\ntid r2\nsrli r3, r2, 3\nbz r3, memory\n" +
                  "li r4, 0x3f800000\n" +
                  "\n".join(f"fma r{5+i%8}, r4, r4, r4" for i in range(48)) +
                  "\njmp sync\nmemory: lw r5, 0(r1)\nsync: bar\nslli r2, r2, 2\nadd r1, r1, r2\nsw r5, 128(r1)\nexit"))
results = []
for name, source in tests:
    words = assemble(source)
    if assemble("\n".join(disassemble(w) for w in words)) != words:
        raise RuntimeError("assembler/disassembler mismatch: " + name)
    prog = args.out / (name + ".bin")
    prog.write_bytes(b"".join(struct.pack("<I", w) for w in words))
    for threads in (1, 7, 8, 9, 17, 31, 32):
        gid = 3
        raw = bytearray(random.Random(3501).randbytes(4096))
        if name == "axpy":
            struct.pack_into("<fII", raw, 0, 1.125, base + 512, base + 2048)
        inp, out = args.out / "input.bin", args.out / "output.bin"
        inp.write_bytes(raw)
        memory = Memory(base, raw)
        fault = 0
        try:
            expected = Model(memory).group(words, gid, threads, base, program_base=base)
        except Fault as err:
            fault, address = err.code, err.address
        result = json.loads(subprocess.check_output(
            [args.simulator.resolve(), prog, inp, out, str(gid), str(threads), hex(base), hex(base), str(fault)]))
        if memory.data != out.read_bytes() or result["fault"] != fault:
            raise RuntimeError(f"memory/fault mismatch {name}/{threads}: {result}")
        if fault and result["address"] != address:
            raise RuntimeError(f"fault address mismatch {name}/{threads}: {result} vs {address}")
        if not fault and any(expected[k] != result[k] for k in ("fp_ops", "fmas")):
            raise RuntimeError(f"FP counter mismatch {name}/{threads}: {result}")
        results.append(dict(kernel=name, threads=threads, **result))
if args.wide and max(r["peak_global"] for r in results if r["kernel"] == "axpy") != 2:
    raise RuntimeError("same-SM dual global issue not observed")
(args.out / "results.json").write_text(json.dumps(results, indent=2) + "\n")
print(f"{len(results)} SM/reference comparisons passed")
