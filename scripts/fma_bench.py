#!/usr/bin/env python3
"""Emit the register-resident 16-accumulator benchmark as Morganite assembly."""
import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--unroll", type=int, choices=(1, 2, 4, 8, 16), default=1)
parser.add_argument("--tensor", action="store_true", help="packed signed INT8 ones with INT32 accumulators")
args = parser.parse_args()
print(f"li r1, {0x01010101 if args.tensor else 0x3f800000}\nli r2, {1024//args.unroll}")
for r in range(8, 24):
    print(f"addi r{r},r0,0")
print("body:")
for _ in range(args.unroll):
    for r in range(8, 24):
        print(f"{'mma.s8' if args.tensor else 'fma'} r{r},r1,r1,r{r}")
print("addi r2,r2,-1\nbz r2,done\njmp body\ndone:")
for r in range(9, 24):
    print(f"{'add' if args.tensor else 'fadd'} r8,r8,r{r}")
print("arg r3\ngid r4\nslli r4,r4,5\ntid r5\nadd r4,r4,r5\nslli r4,r4,2\nadd r3,r3,r4\nsw r8,0(r3)\nexit")
