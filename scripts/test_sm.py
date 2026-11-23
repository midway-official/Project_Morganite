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
(args.out / "results.json").write_text(json.dumps(results, indent=2) + "\n")
print(f"{len(results)} SM/reference comparisons passed")
