# Odd-address byte and halfword transfers with sparse global addresses.
arg r1
gid r2
tid r3
slli r2, r2, 5
add r2, r2, r3
slli r4, r2, 3
add r4, r4, r1
addi r5, r2, -64
sb r5, 1(r4)
lb r6, 1(r4)
sh r6, 2(r4)
lhu r7, 2(r4)
sw r7, 4(r4)
exit
