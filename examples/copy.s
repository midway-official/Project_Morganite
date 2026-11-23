arg r1
lw r2, 0(r1)
lw r3, 4(r1)
gid r4
ntid r5
mul r4, r4, r5
tid r5
add r4, r4, r5
slli r4, r4, 2
add r2, r2, r4
add r3, r3, r4
lw r6, 0(r2)
sw r6, 0(r3)
exit
