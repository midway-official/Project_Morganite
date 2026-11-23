#!/usr/bin/env python3
"""Interleave Berkeley TestFloat streams; append explicit IEEE boundary cases."""
import argparse
import contextlib
import subprocess
import sys

OPS = ["f32_mulAdd", "f32_add", "f32_sub", "f32_mul", "f32_eq", "f32_lt", "f32_le",
       "i32_to_f32", "ui32_to_f32", "f32_to_i32", "f32_to_ui32", "f32_to_i32", "f32_to_ui32"]
SEED = 31703

# op, a, b, c, result, NV:DZ:OF:UF:NX. All entries go through the RTL with active=1.
DIRECTED = [
    (0, 0x3F800001, 0x3F7FFFFE, 0xBF800000, 0xA8800000, 0),
    (0, 0x7F800000, 0, 0x7FC00000, 0x7FC00000, 16),
    (1, 0x80000000, 0x80000000, 0, 0x80000000, 0),
    (1, 1, 1, 0, 2, 0),
    (2, 0x7F800000, 0x7F800000, 0, 0x7FC00000, 16),
    (3, 0x80000000, 0x40000000, 0, 0x80000000, 0),
    (3, 0x7F7FFFFF, 0x40000000, 0, 0x7F800000, 5),
    (3, 1, 0x3F000000, 0, 0, 3),
    (4, 0x7FC01234, 0, 0, 0, 0),
    (4, 0x7F801234, 0, 0, 0, 16),
    (5, 0x7FC01234, 0, 0, 0, 16),
    (6, 0x80000000, 0, 0, 1, 0),
    (7, 0x80000000, 0, 0, 0xCF000000, 0),
    (8, 0xFFFFFFFF, 0, 0, 0x4F800000, 1),
    (9, 0x4F000000, 0, 0, 0x7FFFFFFF, 16),
    (9, 0x40200000, 0, 0, 2, 1),
    (10, 0xBF800000, 0, 0, 0, 16),
    (10, 0x7FC00000, 0, 0, 0xFFFFFFFF, 16),
    (11, 0xBFF33333, 0, 0, 0xFFFFFFFF, 1),
    (12, 0x3FF33333, 0, 0, 1, 1),
]


def vectors(generator, count):
    for row in DIRECTED:
        yield " ".join(f"{field:x}" for field in row)
    with contextlib.ExitStack() as stack:
        streams = []
        for op, name in enumerate(OPS):
            command = [generator, "-forever", "-seed", str(SEED + op), "-exact", "-tininessafter",
                       "-rminMag" if op >= 11 else "-rnear_even", name]
            child = stack.enter_context(subprocess.Popen(command, stdout=subprocess.PIPE, text=True))
            stack.callback(child.terminate)
            streams.append(child.stdout)
        for _ in range(count):
            for op, stream in enumerate(streams):
                line = stream.readline().split()
                arity = 3 if op == 0 else 2 if op < 7 else 1
                if len(line) != arity + 2:
                    raise ValueError(f"TestFloat op {op} returned {line}")
                yield " ".join([f"{op:x}", *line[:-2], *(["0"] * (3 - arity)), *line[-2:]])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("generator")
    parser.add_argument("--count", type=int, default=100000)
    args = parser.parse_args()
    sys.stdout.writelines(row + "\n" for row in vectors(args.generator, args.count))
