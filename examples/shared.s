# Fill workgroup scratch and read it in reverse after all warps arrive.
arg r1
tid r2
ntid r3
slli r4, r2, 2
addi r5, r2, 65
s.sw r5, 0(r4)
bar
sub r6, r3, r2
addi r6, r6, -1
slli r6, r6, 2
s.lw r7, 0(r6)
add r1, r1, r4
sw r7, 0(r1)
exit
