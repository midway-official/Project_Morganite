	.text
	.globl enable_timer
enable_timer:
	la.local $t0, handle_timer
	csrwr $t0, 0xc
	li.w $t0, 0
	csrwr $t0, 0x32		/* interrupt count */
	li.w $t0, 1
	csrwr $t0, 0x44
	li.w $t0, 0x800
	csrwr $t0, 4
	li.w $t0, 8195		/* periodic every 8192 timer ticks */
	csrwr $t0, 0x41
	li.w $t0, 4
	li.w $t1, 4
	csrxchg $t0, $t1, 0
	jr $ra

	.balign 64
handle_timer:
	csrwr $t0, 0x30
	csrrd $t0, 0x32
	addi.w $t0, $t0, 1
	csrwr $t0, 0x32
	li.w $t0, 1
	csrwr $t0, 0x44
	csrrd $t0, 0x30
	ertn

	.globl stop_timer
stop_timer:
	li.w $t0, 0
	li.w $t1, 4
	csrxchg $t0, $t1, 0
	li.w $t0, 0
	csrwr $t0, 0x41
	li.w $t0, 0
	csrwr $t0, 4
	csrrd $a0, 0x32
	jr $ra

	.globl install_page_pair
install_page_pair:
	csrwr $a0, 0x10
	csrwr $a1, 0x11
	csrwr $a2, 0x12
	csrwr $a3, 0x13
	tlbwr
	jr $ra
	.section .note.GNU-stack,"",@progbits
