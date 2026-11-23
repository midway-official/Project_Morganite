# arg points to {float a, float *x, float *y}. The group stride is ntid.
arg r1
lw r2, 0(r1)
lw r3, 4(r1)
lw r4, 8(r1)
gid r5
ntid r6
mul r5, r5, r6
tid r6
add r5, r5, r6
slli r5, r5, 2
add r3, r3, r5
add r4, r4, r5
lw r7, 0(r3)
lw r8, 0(r4)
fma r9, r2, r7, r8
sw r9, 0(r4)
exit
