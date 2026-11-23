#!/usr/bin/env python3
"""Assemble and disassemble Morganite ISA 1.0 programs, in little-endian word order."""
import argparse
import re
import struct
from pathlib import Path

R = {"add": (0, 0), "sub": (0, 32), "mul": (0, 1), "sll": (1, 0),
     "slt": (2, 0), "sltu": (3, 0), "xor": (4, 0), "srl": (5, 0),
     "sra": (5, 32), "or": (6, 0), "and": (7, 0)}
I = {"addi": 0, "slti": 2, "sltiu": 3, "xori": 4, "ori": 6, "andi": 7}
SHIFT = {"slli": (1, 0), "srli": (5, 0), "srai": (5, 32)}
LOAD = {"lb": 0, "lh": 1, "lw": 2, "lbu": 4, "lhu": 5}
STORE = {"sb": 0, "sh": 1, "sw": 2}
SYSTEM = ["tid", "gid", "ntid", "arg", "smid", "fflags"]
CTRL = ["exit", "bar", "fence", "jmp", "bz", "split", "next", "join"]
FP = ["fma", "fadd", "fsub", "fmul", "feq", "flt", "fle", "itof", "utof",
      "ftoi", "ftou", "ftoi.rtz", "ftou.rtz"]


def reg(value):
    if not re.fullmatch(r"r(?:[0-9]|[12][0-9]|3[01])", value):
        raise ValueError(f"invalid register {value}")
    return int(value[1:])


def field(n, width, signed=False):
    low = -(1 << (width - 1)) if signed else 0
    high = 1 << (width - int(signed))
    if not low <= n < high:
        raise ValueError(f"{n} does not fit {'signed' if signed else 'unsigned'} {width} bits")
    return n & ((1 << width) - 1)


def sext(n, width):
    return n - (1 << width) if n & (1 << (width - 1)) else n


def encode(op, args, pc, labels):
    def operands(count):
        if len(args) != count:
            raise ValueError(f"{op} expects {count} operands")
        return args

    if op == ".word":
        return field(int(operands(1)[0], 0), 32)
    if op in R or op in SHIFT or op in I:
        rd, a, b = operands(3)
        code = 0x33 if op in R else 0x13
        if op in R:
            f, high = R[op]
            rest = reg(b) | (high << 5)
        elif op in I:
            f, rest = I[op], field(int(b, 0), 12, True)
        else:
            f, high = SHIFT[op]
            rest = field(int(b, 0), 5) | (high << 5)
        return code | reg(rd) << 7 | f << 12 | reg(a) << 15 | rest << 20
    if op == "lui":
        rd, imm = operands(2)
        return 0x37 | reg(rd) << 7 | field(int(imm, 0), 20) << 12
    if op in SYSTEM:
        return 0x5B | reg(operands(1)[0]) << 7 | SYSTEM.index(op) << 12
    if op in FP:
        f = FP.index(op)
        rd, *sources = list(map(reg, operands(4 if f == 0 else 3 if f <= 6 else 2)))
        b = sources[1] if len(sources) >= 2 else 0
        base = rd << 7 | sources[0] << 15 | b << 20
        return base | (0x43 | sources[2] << 27 if f == 0 else 0x53 | f << 25)
    if op in CTRL:
        pred = op in ("bz", "split")
        target = op in ("bz", "split", "jmp", "next")
        operands(int(pred) + int(target))
        delta = 0
        if target:
            arg = args[-1]
            delta = labels[arg] - pc if arg in labels else int(arg, 0)
        return (0x7B | CTRL.index(op) << 12 | (reg(args[0]) << 15 if pred else 0)
                | field(delta, 12, True) << 20)
    scratch = op.startswith("s.")
    mem = op[2:] if scratch else op
    if mem in LOAD or mem in STORE:
        val, address = operands(2)
        match = re.fullmatch(r"([^()]*)\((r\d+)\)", address)
        if not match:
            raise ValueError(f"expected offset(base), got {address}")
        imm = field(int(match[1] or "0", 0), 12, True)
        base = reg(match[2]) << 15
        if mem in LOAD:
            return (0x0B if scratch else 3) | reg(val) << 7 | LOAD[mem] << 12 | base | imm << 20
        return ((0x2B if scratch else 0x23) | (imm & 31) << 7 | STORE[mem] << 12
                | base | reg(val) << 20 | (imm >> 5) << 25)
    raise ValueError(f"unknown instruction {op}")


def assemble(source):
    labels, items = {}, []
    for line, raw in enumerate(source.splitlines(), 1):
        code = raw.split("#", 1)[0].strip()
        if ":" in code:
            name, code = map(str.strip, code.split(":", 1))
            if not re.fullmatch(r"[A-Za-z_][\w.]*", name) or name in labels:
                raise ValueError(f"line {line}: invalid or duplicate label {name}")
            labels[name] = len(items)
        if not code:
            continue
        op, *tail = code.split(None, 1)
        args = [x.strip() for x in tail[0].split(",")] if tail else []
        if op == "li":
            if len(args) != 2:
                raise ValueError(f"line {line}: li expects two operands")
            value = int(args[1], 0)
            if not -(1 << 31) <= value < (1 << 32):
                raise ValueError(f"line {line}: literal out of range")
            items.append((line, "lui", [args[0], str(((value + 0x800) >> 12) & 0xFFFFF)]))
            items.append((line, "addi", [args[0], args[0], str(sext(value & 4095, 12))]))
        else:
            items.append((line, op, args))
    words = []
    for pc, (line, op, args) in enumerate(items):
        try:
            words.append(encode(op, args, pc, labels))
        except ValueError as error:
            raise ValueError(f"line {line}: {error}") from error
    return words


def disassemble(word):
    code, f, high = word & 127, (word >> 12) & 7, word >> 25
    d, a, b, c = (f"r{(word >> n) & 31}" for n in (7, 15, 20, 27))
    imm = sext(word >> 20, 12)
    op, args = ".word", [hex(word)]
    if code in (0x33, 0x13):
        for name, pair in (R if code == 0x33 else SHIFT).items():
            if pair == (f, high):
                op, args = name, [d, a, b if code == 0x33 else str((word >> 20) & 31)]
        if code == 0x13 and f in I.values():
            op, args = next(k for k, v in I.items() if v == f), [d, a, str(imm)]
    elif code == 0x37:
        op, args = "lui", [d, hex(word >> 12)]
    elif code == 0x5B and f < len(SYSTEM):
        op, args = SYSTEM[f], [d]
    elif code == 0x43:
        op, args = "fma", [d, a, b, c]
    elif code == 0x53 and 0 < high < len(FP):
        op, args = FP[high], [d, a] + ([b] if high <= 6 else [])
    elif code == 0x7B:
        op = CTRL[f]
        args = ([a] if f in (4, 5) else []) + ([str(imm)] if 3 <= f <= 6 else [])
    elif code in (3, 0x0B, 0x23, 0x2B):
        load = code in (3, 0x0B)
        table = LOAD if load else STORE
        if f in table.values():
            prefix = "s." if code in (0x0B, 0x2B) else ""
            offset = imm if load else sext(high << 5 | (word >> 7) & 31, 12)
            op = prefix + next(k for k, v in table.items() if v == f)
            args = [d if load else b, f"{offset}({a})"]
    if encode(op, args, 0, {}) != word:
        return f".word 0x{word:08x}"
    return op + (" " + ", ".join(args) if args else "")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["asm", "dis"])
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.action == "asm":
        words = assemble(args.input.read_text())
        args.output.write_bytes(b"".join(struct.pack("<I", w) for w in words))
    else:
        words = struct.iter_unpack("<I", args.input.read_bytes())
        args.output.write_text("".join(disassemble(w) + "\n" for w, in words))
