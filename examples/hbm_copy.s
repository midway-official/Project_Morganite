# One 32-thread group copies a 4 KiB page four times.
# arg contains the source and destination device addresses.
arg r1
lw r2, 0(r1)
lw r3, 4(r1)
gid r4
slli r4, r4, 12
tid r5
slli r5, r5, 2
add r4, r4, r5
add r2, r2, r4
add r3, r3, r4
li r6, 4
repeat:
addi r7, r2, 0
addi r8, r3, 0
li r9, 32
copy:
lw r10, 0(r7)
sw r10, 0(r8)
addi r7, r7, 128
addi r8, r8, 128
addi r9, r9, -1
bz r9, page_done
jmp copy
page_done:
addi r6, r6, -1
bz r6, done
jmp repeat
done:
exit
