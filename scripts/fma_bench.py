#!/usr/bin/env python3
"""Emit the register-resident 16-accumulator benchmark as Morganite assembly."""
print("li r1, 0x3f800000\nli r2, 1024")
for r in range(8, 24):
    print(f"addi r{r},r0,0")
print("body:")
for r in range(8, 24):
    print(f"fma r{r},r1,r1,r{r}")
print("addi r2,r2,-1\nbz r2,done\njmp body\ndone:")
for r in range(9, 24):
    print(f"fadd r8,r8,r{r}")
print("arg r3\ngid r4\nslli r4,r4,5\ntid r5\nadd r4,r4,r5\nslli r4,r4,2\nadd r3,r3,r4\nsw r8,0(r3)\nexit")
