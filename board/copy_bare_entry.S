/* The frozen UART/DMW firmware enters at 0xa0300000 with no OS. */
	.section .text.entry,"ax"
	.globl _start
_start:
	li.w $t0, 0
	csrwr $t0, 0x41		/* TCFG, stop timer */
	li.w $t0, 0
	csrwr $t0, 0x4		/* ECFG, mask external interrupts */
	li.w $t0, 0
	li.w $t1, 4
	csrxchg $t0, $t1, 0	/* CRMD.IE = 0 */
	li.w $t0, 0x80000001
	csrwr $t0, 0x181		/* uncached low-29-bit direct map */
	li.w $t0, 0xa0000011
	csrwr $t0, 0x180		/* cached low-29-bit direct map */
	dbar 0
	ibar 0
	li.w $sp, 0xa0380000
	bl run_bare
1:	b 1b

	.text
	.globl write_message
write_message:
	li.w $t0, 0x9fe001e0
	beq $a1, $zero, 3f
1:	ld.bu $t1, $t0, 5
	andi $t1, $t1, 0x20
	beq $t1, $zero, 1b
	ld.bu $t1, $a0, 0
	st.b $t1, $t0, 0
	addi.w $a0, $a0, 1
	addi.w $a1, $a1, -1
	bne $a1, $zero, 1b
3:	jr $ra
	.section .note.GNU-stack,"",@progbits
