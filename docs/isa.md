# Morganite ISA 1.0

[简体中文](isa.zh.md) · **English**

The host uploads little-endian 32-bit instruction words. PC is a word index,
relative targets are signed word deltas from the current instruction.
The program store holds up to 1024 words per SM. Registers hold 32-bit integer or
IEEE binary32 bits. r0 always reads zero. Programs must initialize every other
register before reading it. Arithmetic wraps modulo 2^32. Each workgroup starts
at word zero with one bit set per existing thread. Thread IDs are local to the
group. Instructions retire after all active physical slices complete. Four warp
slots can overlap independent arithmetic. Load/store and control instructions
wait for older arithmetic in that warp. Loads and stores serialize within a
warp through their response.

| Opcode | Fields | Semantics |
| --- | --- | --- |
| 0x33, 0x13, 0x37 | RV32 R, I, U | ADD/SUB, low MUL, shifts, signed/unsigned SLT, XOR/OR/AND, their immediate forms, LUI |
| 0x03, 0x23 | RV32 I, S | Global LB/LH/LW/LBU/LHU and SB/SH/SW, naturally aligned |
| 0x0b, 0x2b | Same load/store fields | Byte address relative to the current workgroup scratchpad |
| 0x43 | RV32 R4, fmt and rm zero | Fused rD = rA * rB + rC |
| 0x53 | funct7 = FP operation 1–12, funct3 zero | Binary and unary operations in fp32.md, rs2 zero for unary |
| 0x5b | rd and funct3, other fields zero | TID, GID, NTID, ARG, SMID, FFLAGS in funct3 order 0–5 |
| 0x7b | funct3 and signed imm12, rs1 predicate as applicable | EXIT, BAR, FENCE, JMP, BZ, SPLIT, NEXT, JOIN in order 0–7 |

ARG is the 32-bit device address supplied by the job descriptor. FFLAGS returns
the sticky OR of the active lane exceptions in the calling warp for this group.
Arithmetic comparison produces 0/1. Shift counts use the low five bits.
Address generation adds the signed offset with carry before region and alignment
checks. Global and scratch writes support byte enables. Conflicting same-group
accesses require synchronization. Software must avoid overlapping unsynchronized
writes from different warps or groups. Scratch contents are unspecified on entry.
FENCE waits for the calling warp's earlier memory operations. A job boundary
synchronizes independent workgroups.

SPLIT rP,else pushes the parent mask, saves the false lanes and executes the true
lanes. With no true lane, execution starts directly at else. NEXT join switches
to a saved false path or jumps to join if it is empty. JOIN restores the parent
mask and pops. Four nested splits are available. BZ takes its target when all
active predicates are zero and falls through when all are nonzero. A mixed BZ
predicate faults. BAR requires an empty split stack, all resident warps at the
same static barrier, and completed earlier scratchpad operations. EXIT also
requires an empty split stack. Tail lanes never access memory or update flags.

Fault codes are 1 for an invalid job, 2 for illegal instruction or target,
3 for address or alignment, 4 for reconvergence/barrier, 5 for AXI slave or
protocol error, 6 for decode response, and 7 for cancellation. Code faults
carry the device address of the instruction. Address faults carry the low
32 bits of the offending address. The assembler's LI expands to LUI and ADDI.

```bash
python3 sw/asm.py asm examples/divergence.s build/divergence.bin
python3 sw/asm.py dis build/divergence.bin build/divergence.dis
bash scripts/test_sm.sh
```

The RV32 integer field arrangement keeps three-source FP instructions in one
word. The workgroup, mask and structured branch ABI are local to Morganite.
Ventus and Radiance provide examples of Chisel warp scheduling and register
banking. Vortex provides a SIMT and software graphics reference. This design
uses its own instruction decoder and WSL model and imports Berkeley HardFloat
for arithmetic. [Ventus](https://github.com/THU-DSP-LAB/ventus-gpgpu) · [Radiance](https://github.com/ucb-bar/radiance) · [Vortex](https://github.com/vortexgpgpu/vortex)
