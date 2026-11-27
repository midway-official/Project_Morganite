# args: out, log2(width), width-1, iterations, julia, xmin, ymin, dx, dy, cx, cy,
# first row, then a packed RGB palette indexed by escape count.
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
lw r7, 44(r1)
add r6, r6, r7
# Keep per-pixel constants in registers across all four samples.
lw r28, 28(r1)
lw r29, 20(r1)
lw r30, 32(r1)
lw r31, 24(r1)
lw r9, 36(r1)
lw r10, 40(r1)
lw r4, 16(r1)
lw r11, 12(r1)
utof r20, r5
utof r21, r6
add r22, r0, r0
add r23, r0, r0
add r24, r0, r0
add r25, r0, r0
li r26, 0x3f000000
li r27, 0x3e800000
sample: andi r7, r22, 1
srli r8, r22, 1
utof r7, r7
utof r8, r8
fma r7, r7, r26, r27
fma r8, r8, r26, r27
fadd r5, r20, r7
fadd r6, r21, r8
fma r5, r5, r28, r29
fma r6, r6, r30, r31
bz r4, mandelbrot
jmp start
mandelbrot: add r9, r5, r0
add r10, r6, r0
add r5, r0, r0
add r6, r0, r0
start: add r12, r11, r0
addi r13, r0, 1
add r14, r0, r0
li r15, 0x40800000
# r13 tracks surviving lanes, r14 counts iterates inside radius 2.
loop: split r13, resume
fmul r16, r5, r5
fmul r17, r6, r6
fsub r16, r16, r17
fadd r17, r5, r5
fmul r17, r17, r6
fadd r5, r16, r9
fadd r6, r17, r10
fmul r16, r5, r5
fmul r17, r6, r6
fadd r16, r16, r17
fle r13, r16, r15
add r14, r14, r13
next resume
resume: join
addi r12, r12, -1
bz r12, paint
jmp loop
paint: slli r18, r14, 2
add r18, r18, r1
lw r18, 48(r18)
andi r19, r18, 255
add r23, r23, r19
srli r19, r18, 8
andi r19, r19, 255
add r24, r24, r19
srli r19, r18, 16
andi r19, r19, 255
add r25, r25, r19
addi r22, r22, 1
slti r7, r22, 4
bz r7, pixel
jmp sample
pixel: srli r23, r23, 2
srli r24, r24, 2
srli r25, r25, 2
slli r24, r24, 8
slli r25, r25, 16
li r18, 0xff000000
or r18, r18, r23
or r18, r18, r24
or r18, r18, r25
sw r18, 0(r3)
exit
