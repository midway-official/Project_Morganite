#!/usr/bin/env python3
"""Check software-visible optional instructions, topology and disabled-unit recovery."""
import random
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/"sw"))
from asm import GRAPHICS, assemble, disassemble
from model import Fault, Memory, Model

sim = Path(sys.argv[1]).resolve()
rows, cols, features = map(int,sys.argv[2:])
sms, base = rows*cols, 0x1e000000
bits = (0,0,0,1,1,2,2,3,3,4,4)
with tempfile.TemporaryDirectory() as tmp:
    inp, out = Path(tmp)/"input", Path(tmp)/"output"
    for fn,(name,arity) in enumerate(GRAPHICS):
        operands = ",".join(f"r{i+2}" for i in range(arity))
        source = f"""arg r1
tid r5
slli r5,r5,4
add r1,r1,r5
lw r2,0(r1)
lw r3,4(r1)
lw r4,8(r1)
{name} r6,{operands}
sw r6,12(r1)
exit"""
        words = assemble(source)
        assert assemble("\n".join(map(disassemble,words))) == words
        raw = bytearray(random.Random(fn+782).randbytes(8192))
        struct.pack_into("<8I",raw,0,0x3144474d,0x10000,base+128,len(words),base+4096,1,32,0)
        struct.pack_into(f"<{len(words)}I",raw,128,*words)
        inp.write_bytes(raw)
        mem = Memory(base,raw)
        try:
            Model(mem,graphics_mask=features).group(words,0,32,base+4096,program_base=base+128)
            fault = 0
        except Fault as error:
            fault = error.code
        assert fault == (0 if features & (1<<bits[fn]) else 2)
        subprocess.run([sim,inp,out,str(sms),str((1<<sms)-1),str(fault),f"units-{rows*65536+cols}-{features}"],
                       check=True,stdout=subprocess.DEVNULL)
        if not fault: assert out.read_bytes() == mem.data, name
    print(f"PASS {rows}x{cols} graphics mask {features:#x} ISA, model, MMIO and recovery")
