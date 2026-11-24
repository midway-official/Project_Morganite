# Orthographic camera looks down +Z. Each thread traces one primary ray and
# one shadow ray toward a directional light (0.6, 0, -0.8).
# args: output, log2(width), width-1, first row, viewport step, sphere count,
# then spheres {x,y,z,radius^2,1/radius,R,G,B} as FP32.
arg r1
gid r2
ntid r3
mul r2, r2, r3
tid r3
add r2, r2, r3
lw r3, 0(r1)
slli r4, r2, 2
add r3, r3, r4
lw r4, 8(r1)
and r5, r2, r4
lw r4, 4(r1)
srl r6, r2, r4
lw r4, 12(r1)
add r6, r6, r4
utof r5, r5
utof r6, r6
li r4, 0x3f000000
fadd r5, r5, r4
fadd r6, r6, r4
lw r7, 16(r1)
li r8, 0xc0000000
fma r5, r5, r7, r8
li r8, 0xbfc00000
fma r6, r6, r7, r8
li r10, 0x42c80000
add r11, r0, r0
addi r12, r1, 24
lw r13, 20(r1)
li r25, 0x5f3759df
li r26, 0x3f000000
li r27, 0x3fc00000
primary: lw r14, 0(r12)
lw r15, 4(r12)
lw r16, 12(r12)
fsub r14, r5, r14
fsub r15, r6, r15
fmul r17, r14, r14
fmul r18, r15, r15
fadd r17, r17, r18
fsub r17, r16, r17
flt r18, r0, r17
split r18, endhit
# sqrt(h) via h * rsqrt(h), two Newton steps.
srli r19, r17, 1
sub r19, r25, r19
fmul r20, r17, r26
fmul r21, r19, r19
fmul r21, r21, r20
fsub r21, r27, r21
fmul r19, r19, r21
fmul r21, r19, r19
fmul r21, r21, r20
fsub r21, r27, r21
fmul r19, r19, r21
fmul r19, r17, r19
lw r16, 8(r12)
fsub r19, r16, r19
flt r18, r19, r10
split r18, nearest
add r10, r19, r0
add r11, r12, r0
next nearest
nearest: join
next endhit
endhit: join
addi r12, r12, 32
addi r13, r13, -1
bz r13, shade
jmp primary
shade: li r29, 0xff302414
split r11, pixel
li r23, 0x3f19999a
li r24, 0xbf4ccccd
lw r14, 0(r11)
lw r16, 8(r11)
lw r17, 16(r11)
fsub r14, r5, r14
fsub r16, r10, r16
fmul r14, r14, r23
fmul r16, r16, r24
fadd r22, r14, r16
fmul r22, r22, r17
flt r18, r0, r22
split r18, unlit
addi r12, r1, 24
lw r13, 20(r1)
shadow: lw r14, 0(r12)
lw r15, 4(r12)
lw r16, 8(r12)
fsub r14, r14, r5
fsub r15, r15, r6
fsub r16, r16, r10
fmul r17, r14, r23
fmul r18, r16, r24
fadd r17, r17, r18
fmul r19, r14, r14
fmul r18, r15, r15
fadd r19, r19, r18
fmul r18, r16, r16
fadd r19, r19, r18
fmul r18, r17, r17
fsub r19, r19, r18
lw r18, 12(r12)
flt r19, r19, r18
flt r17, r0, r17
and r19, r19, r17
xor r18, r11, r12
sltu r18, r0, r18
and r19, r19, r18
split r19, advance
add r22, r0, r0
next advance
advance: join
addi r12, r12, 32
addi r13, r13, -1
bz r13, lit
jmp shadow
lit: next intensity
unlit: add r22, r0, r0
intensity: join
li r18, 0x3e800000
li r19, 0x3f400000
fma r22, r22, r19, r18
lw r14, 20(r11)
lw r15, 24(r11)
lw r16, 28(r11)
fmul r14, r14, r22
fmul r15, r15, r22
fmul r16, r16, r22
ftou r14, r14
ftou r15, r15
ftou r16, r16
slli r15, r15, 8
slli r16, r16, 16
li r29, 0xff000000
or r29, r29, r14
or r29, r29, r15
or r29, r29, r16
next pixel
pixel: join
sw r29, 0(r3)
exit
