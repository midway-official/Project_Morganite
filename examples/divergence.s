# Outer predicate selects odd threads. Nested split tests the second bit.
arg r1
tid r2
andi r3, r2, 1
split r3, even
andi r4, r2, 2
split r4, oddlow
addi r5, r2, 300
next innerjoin
oddlow: addi r5, r2, 100
innerjoin: join
next outerjoin
even: sub r5, r0, r2
outerjoin: join
slli r4, r2, 2
add r1, r1, r4
sw r5, 0(r1)
exit
