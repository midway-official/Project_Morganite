/* Run an unchanged userspace ELF. The only system calls used are write and exit. */
	.text
	.globl enter_user
enter_user:
	addi.w $sp, $sp, -48
	st.w $ra, $sp, 0
	st.w $fp, $sp, 4
	st.w $s0, $sp, 8
	st.w $s1, $sp, 12
	st.w $s2, $sp, 16
	st.w $s3, $sp, 20
	st.w $s4, $sp, 24
	st.w $s5, $sp, 28
	st.w $s6, $sp, 32
	st.w $s7, $sp, 36
	st.w $s8, $sp, 40
	move $t0, $sp
	csrwr $t0, 0x30		/* KS0 holds the supervisor stack during user execution */
	la.local $t0, user_exception
	csrwr $t0, 0xc
	csrwr $a0, 0x6		/* ERA = original ELF entry point */
	li.w $t0, 3
	csrwr $t0, 0x1		/* PRMD.PPLV = 3, PIE = 0 */
	li.w $sp, 0x40002000
	ertn

	.balign 64
user_exception:
	csrwr $sp, 0x30
	addi.w $sp, $sp, -16
	st.w $t0, $sp, 0
	st.w $t1, $sp, 4
	st.w $ra, $sp, 8
	st.w $a1, $sp, 12
	csrrd $t0, 0x5
	srli.w $t0, $t0, 16
	andi $t0, $t0, 63
	li.w $t1, 11
	bne $t0, $t1, unexpected
	csrrd $t0, 1
	andi $t0, $t0, 7
	li.w $t1, 3
	bne $t0, $t1, unexpected
	li.w $t1, 93
	beq $a7, $t1, user_exit
	li.w $t1, 64
	bne $a7, $t1, unexpected
	move $a0, $a1
	move $a1, $a2
	bl write_message
	move $a0, $a2
	csrrd $t0, 6
	addi.w $t0, $t0, 4
	csrwr $t0, 6
	ld.w $a1, $sp, 12
	ld.w $ra, $sp, 8
	ld.w $t1, $sp, 4
	ld.w $t0, $sp, 0
	addi.w $sp, $sp, 16
	csrwr $sp, 0x30
	ertn
unexpected:
	li.w $a0, 127
user_exit:
	addi.w $sp, $sp, 16
	ld.w $ra, $sp, 0
	ld.w $fp, $sp, 4
	ld.w $s0, $sp, 8
	ld.w $s1, $sp, 12
	ld.w $s2, $sp, 16
	ld.w $s3, $sp, 20
	ld.w $s4, $sp, 24
	ld.w $s5, $sp, 28
	ld.w $s6, $sp, 32
	ld.w $s7, $sp, 36
	ld.w $s8, $sp, 40
	addi.w $sp, $sp, 48
	jr $ra
	.section .note.GNU-stack,"",@progbits
