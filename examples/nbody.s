# One symplectic Euler step with softened gravity. 32 threads per group.
# args: input, output, body count (multiple of 32), eps squared, dt
# body layout: float x,y,z,mass,vx,vy,vz,pad
arg r1
lw r2, 0(r1)
lw r3, 4(r1)
lw r4, 8(r1)
lw r5, 12(r1)
lw r6, 16(r1)
gid r7
tid r8
slli r7, r7, 5
add r7, r7, r8
slli r7, r7, 5
add r9, r2, r7
add r3, r3, r7
lw r10, 0(r9)
lw r11, 4(r9)
lw r12, 8(r9)
add r13, r0, r0
add r14, r0, r0
add r15, r0, r0
slli r16, r8, 2
slli r7, r8, 5
add r2, r2, r7
li r26, 0x5f3759df
li r27, 0x3f000000
li r28, 0x3fc00000
tile: lw r17, 0(r2)
s.sw r17, 0(r16)
lw r17, 4(r2)
s.sw r17, 128(r16)
lw r17, 8(r2)
s.sw r17, 256(r16)
lw r17, 12(r2)
s.sw r17, 384(r16)
bar
add r17, r0, r0
pair: s.lw r18, 0(r17)
s.lw r19, 128(r17)
s.lw r20, 256(r17)
s.lw r21, 384(r17)
fsub r18, r18, r10
fsub r19, r19, r11
fsub r20, r20, r12
fma r22, r18, r18, r5
fma r22, r19, r19, r22
fma r22, r20, r20, r22
# Two Newton refinements of the FP32 inverse square root.
srli r23, r22, 1
sub r23, r26, r23
fmul r22, r22, r27
fmul r24, r23, r23
fmul r24, r22, r24
fsub r24, r28, r24
fmul r23, r23, r24
fmul r24, r23, r23
fmul r24, r22, r24
fsub r24, r28, r24
fmul r23, r23, r24
fmul r24, r23, r23
fmul r24, r24, r23
fmul r24, r24, r21
fma r13, r18, r24, r13
fma r14, r19, r24, r14
fma r15, r20, r24, r15
addi r17, r17, 4
sltiu r25, r17, 128
bz r25, loaded
jmp pair
loaded: bar
addi r2, r2, 1024
addi r4, r4, -32
bz r4, integrate
jmp tile
integrate: lw r18, 16(r9)
lw r19, 20(r9)
lw r20, 24(r9)
lw r21, 12(r9)
fma r18, r13, r6, r18
fma r19, r14, r6, r19
fma r20, r15, r6, r20
fma r10, r18, r6, r10
fma r11, r19, r6, r11
fma r12, r20, r6, r12
sw r10, 0(r3)
sw r11, 4(r3)
sw r12, 8(r3)
sw r21, 12(r3)
sw r18, 16(r3)
sw r19, 20(r3)
sw r20, 24(r3)
sw r0, 28(r3)
exit
