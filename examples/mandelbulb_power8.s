# One spherical power-8 map per thread, before adding the orbit constant.
# args: device pointers to input and output arrays of 16-byte {x,y,z,pad}.
# args[2] selects a second Newton refinement (0 or 1).
# Finite coordinates in [-2,2]. Treat subnormal transverse radius squared as axial.
arg r1
gid r2
ntid r3
mul r2, r2, r3
tid r3
add r2, r2, r3
slli r2, r2, 4
lw r3, 0(r1)
lw r4, 4(r1)
lw r18, 8(r1)
add r3, r3, r2
add r4, r4, r2
lw r5, 0(r3)
lw r6, 4(r3)
lw r7, 8(r3)
fmul r8, r5, r5
fma r8, r6, r6, r8
# Start with the pole values (rho=0, azimuth=0).
add r9, r0, r0
li r10, 0x3f800000
add r11, r0, r0
li r12, 0x00800000
fle r12, r12, r8
split r12, polar
rsqrt.approx.f32 r13, r8
# y *= 1.5 - 0.5*(rhoSquared*y)*y. Multiply in this order near the axis.
fmul r14, r8, r13
li r15, 0xbf000000
fmul r14, r14, r15
li r17, 0x3fc00000
fma r14, r14, r13, r17
fmul r13, r13, r14
bz r18, normalized
fmul r14, r8, r13
fmul r14, r14, r15
fma r14, r14, r13, r17
fmul r13, r13, r14
normalized: fmul r9, r8, r13
fmul r10, r5, r13
fmul r11, r6, r13
next polar
polar: join
# Repeatedly square (z+i*rho) and (cos(phi)+i*sin(phi)) as complex pairs.
# The first pair retains the radius, including at the origin and either pole.
li r17, 0x80000000
addi r12, r0, 3
double_angle: fmul r13, r9, r9
fmul r14, r11, r11
fadd r15, r7, r7
fadd r16, r10, r10
fmul r9, r15, r9
fmul r11, r16, r11
xor r13, r13, r17
xor r14, r14, r17
fma r7, r7, r7, r13
fma r10, r10, r10, r14
addi r12, r12, -1
bz r12, store
jmp double_angle
store: fmul r10, r9, r10
fmul r11, r9, r11
sw r10, 0(r4)
sw r11, 4(r4)
sw r7, 8(r4)
exit
