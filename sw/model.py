#!/usr/bin/env python3
"""Functional ISA 1 model with independent scalar decoding, SIMT masks and barriers."""
import argparse
import ctypes
import struct
from pathlib import Path

MASK = (1 << 32) - 1
ROOT = Path(__file__).resolve().parents[1]


def signed(x, bits=32):
    return x - (1 << bits) if x & (1 << (bits - 1)) else x


class Fault(Exception):
    def __init__(self, code, address):
        super().__init__(f"fault {code} at 0x{address & MASK:08x}")
        self.code, self.address = code, address & MASK


class Memory:
    def __init__(self, base, data):
        self.base, self.data = base, bytearray(data)

    def offset(self, addr, size):
        if addr % size or addr < self.base or addr + size > self.base + len(self.data) or addr + size > 1 << 32:
            raise Fault(3, addr)
        return addr - self.base

    def load(self, addr, size, sign):
        i = self.offset(addr, size)
        return int.from_bytes(self.data[i:i + size], "little", signed=sign) & MASK

    def store(self, addr, size, value):
        i = self.offset(addr, size)
        self.data[i:i + size] = (value & MASK).to_bytes(4, "little")[:size]


class Warp:
    def __init__(self, tids):
        self.live = list(tids)
        self.pc = 0
        self.stack = []
        self.flags = 0
        self.barrier = False
        self.done = False


class Model:
    def __init__(self, memory, warp_width=8, slots=4, scratch_bytes=8192, tensor_k=0, graphics_mask=0):
        self.global_mem = memory
        self.width, self.slots, self.scratch_bytes = warp_width, slots, scratch_bytes
        self.fp = None
        self.tensor_k = tensor_k
        self.graphics_mask = graphics_mask

    def group(self, program, group, threads, argument, sm=0, program_base=0, max_steps=2000000):
        if not 0 < threads <= self.width * self.slots or not 0 < len(program) <= 1024:
            raise Fault(1, program_base)
        warps = [Warp(range(i, min(i + self.width, threads))) for i in range(0, threads, self.width)]
        regs = [[0] * 32 for _ in range(threads)]
        scratch = Memory(0, bytes(self.scratch_bytes))
        fp_ops = fmas = steps = 0
        while True:
            for w in warps:
                if w.done or w.barrier:
                    continue
                steps += 1
                loc = program_base + w.pc * 4
                if steps > max_steps:
                    raise TimeoutError(f"instruction limit at PC {w.pc}")
                if not 0 <= w.pc < len(program):
                    raise Fault(2, loc)
                pc = w.pc
                word = program[pc]
                w.pc += 1
                op, f, hi = word & 127, word >> 12 & 7, word >> 25
                rd, ra, rb, rc = [(word >> shift) & 31 for shift in (7, 15, 20, 27)]
                imm = signed(word >> 20, 12)
                if op == 0x7B:
                    if rd or (ra and f not in (4, 5)) or (imm and f not in (3, 4, 5, 6)):
                        raise Fault(2, loc)
                    if f in (0, 1):
                        if w.stack:
                            raise Fault(4, loc)
                        w.done, w.barrier = f == 0, f == 1
                    elif f == 3:
                        w.pc = pc + imm
                    elif f in (4, 5):
                        yes = [t for t in w.live if regs[t][ra]]
                        no = [t for t in w.live if not regs[t][ra]]
                        if f == 4:
                            if yes and no:
                                raise Fault(4, loc)
                            if not yes:
                                w.pc = pc + imm
                        else:
                            if len(w.stack) == 4 or not 0 <= pc + imm < len(program):
                                raise Fault(4, loc)
                            w.stack.append([w.live, no if yes else [], pc + imm])
                            if yes:
                                w.live = yes
                            else:
                                w.pc = pc + imm
                    elif f == 6:
                        if not w.stack:
                            raise Fault(4, loc)
                        frame = w.stack[-1]
                        if frame[1]:
                            w.live, w.pc = frame[1], frame[2]
                            frame[1] = []
                        else:
                            w.pc = pc + imm
                    elif f == 7:
                        if not w.stack or w.stack[-1][1]:
                            raise Fault(4, loc)
                        w.live = w.stack.pop()[0]
                    if w.pc != pc + 1 and not 0 <= w.pc < len(program):
                        raise Fault(2, loc)
                    continue
                if op in (3, 0x23, 0x0B, 0x2B):
                    writing = bool(op & 32)
                    if f not in ((0, 1, 2) if writing else (0, 1, 2, 4, 5)):
                        raise Fault(2, loc)
                    offset = signed((hi << 5) + rd, 12) if writing else imm
                    size = 1 << (f & 3)
                    store = scratch if op & 8 else self.global_mem
                    addresses = [regs[t][ra] + offset for t in w.live]
                    for addr in addresses:
                        store.offset(addr, size)
                    for t, addr in zip(w.live, addresses):
                        if writing:
                            store.store(addr, size, regs[t][rb])
                        else:
                            val = store.load(addr, size, f in (0, 1))
                            if rd:
                                regs[t][rd] = val
                    continue
                if op == 0x47:
                    if self.tensor_k not in (1, 2, 4) or f or (hi & 3):
                        raise Fault(2, loc)
                    tiles = {t // 4 for t in w.live}
                    if 4 * len(tiles) != len(w.live):
                        raise Fault(2, loc)
                    for tile in tiles:
                        rows = [regs[4*tile+i][ra] for i in (0, 2)]
                        cols = [regs[4*tile+i][rb] for i in (0, 1)]
                        for lane in range(4):
                            result = regs[4*tile+lane][rc]
                            for k in range(self.tensor_k):
                                x = signed((rows[lane//2] >> (8*k)) & 255, 8)
                                y = signed((cols[lane%2] >> (8*k)) & 255, 8)
                                result += x * y
                            if rd:
                                regs[4*tile+lane][rd] = result & MASK
                    continue
                for t in w.live:
                    a, b, c = (regs[t][k] for k in (ra, rb, rc))
                    if op == 0x37:
                        val = word & 0xFFFFF000
                    elif op == 0x5B and f <= 5 and not (word >> 15):
                        val = (t, group, threads, argument, sm, w.flags)[f]
                    elif op in (0x33, 0x13):
                        if op == 0x33 and (f, hi) in ((0, 32), (0, 1)):
                            val = a - b if hi == 32 else a * b
                        else:
                            if (op == 0x33 and hi and (f, hi) != (5, 32)) or (
                                    op == 0x13 and f in (1, 5) and hi and (f, hi) != (5, 32)):
                                raise Fault(2, loc)
                            b = imm & MASK if op == 0x13 else b
                            shift = b & 31
                            val = (a + b, a << shift, int(signed(a) < signed(b)), int(a < b),
                                   a ^ b, (signed(a) if hi == 32 else a) >> shift, a | b, a & b)[f]
                    elif op == 0x57:
                        from graphics_ops import graphics
                        fn = ((hi & 3) << 3) | f
                        feature = (0,0,0,1,1,2,2,3,3,4,4)
                        if fn >= len(feature) or not (self.graphics_mask >> feature[fn]) & 1 or (
                                fn not in (3,4,5,6) and rc) or (fn >= 9 and rb):
                            raise Fault(2,loc)
                        val = graphics(fn,a,b,c)
                    elif op in (0x43, 0x53):
                        if f or (op == 0x43 and (hi & 3)) or (op == 0x53 and
                                (not 1 <= hi <= 12 or (hi > 6 and rb))):
                            raise Fault(2, loc)
                        if self.fp is None:
                            lib = ctypes.CDLL(str(ROOT / "build/fp/reference/libreference.so"))
                            self.fp = lib.morganite_reference_fp
                            self.fp.argtypes = [ctypes.c_uint32] * 4
                            self.fp.restype = ctypes.c_uint64
                        val = self.fp(0 if op == 0x43 else hi, a, b, c)
                        w.flags |= val >> 32
                        fp_ops += 1
                        fmas += op == 0x43
                    else:
                        raise Fault(2, loc)
                    if rd:
                        regs[t][rd] = val & MASK
            if all(w.done for w in warps):
                return {"fp_ops": fp_ops, "fmas": fmas, "warp_instructions": steps}
            if any(w.barrier for w in warps):
                if any(w.done for w in warps):
                    raise Fault(4, program_base + 4 * (next(w.pc for w in warps if w.barrier) - 1))
                if all(w.barrier for w in warps):
                    if len({w.pc for w in warps}) != 1:
                        raise Fault(4, program_base + 4 * (warps[0].pc - 1))
                    for w in warps:
                        w.barrier = False


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("program", "memory", "output"):
        parser.add_argument(name, type=Path)
    for name, default in (("base", 0), ("arg", 0), ("threads", 32), ("groups", 1)):
        parser.add_argument("--" + name, type=lambda s: int(s, 0), default=default)
    args = parser.parse_args()
    program = [word for word, in struct.iter_unpack("<I", args.program.read_bytes())]
    memory = Memory(args.base, args.memory.read_bytes())
    machine = Model(memory)
    for gid in range(args.groups):
        print(machine.group(program, gid, args.threads, args.arg))
    args.output.write_bytes(memory.data)
