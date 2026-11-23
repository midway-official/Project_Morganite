# r0 is hardwired to zero. Each group owns 32 result words.
arg r1
gid r2
tid r3
slli r4, r2, 5
add r4, r4, r3
slli r5, r4, 2
add r5, r1, r5
mul r6, r4, r4
addi r6, r6, 13
sw r6, 0(r5)
fence
exit
