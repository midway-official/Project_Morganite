/* LA32R userspace probe for the two-byte match copy in zlib_inflate/inffast.o.
 * a0 = aligned output, a1 = preceding byte stream, a2 = halfword count.
 * This instruction ordering matches the linked kernel loop at .L32.
 */
#ifndef COPY_STANDALONE
#include <asm/unistd.h>
#endif
	.text
#ifndef COPY_FUNCTION
#define COPY_FUNCTION copy_match
#endif
	.globl COPY_FUNCTION
COPY_FUNCTION:
1:	addi.w $a1, $a1, 2
	ld.bu  $t0, $a1, -1
	addi.w $a0, $a0, 2
	addi.w $a2, $a2, -1
	slli.w $t1, $t0, 8
	ld.bu  $t0, $a1, -2
	or     $t0, $t0, $t1
	st.h   $t0, $a0, -2
#if defined(COPY_DBAR)
	dbar 0
#elif defined(COPY_NOP)
	nop
#endif
	bne    $a2, $zero, 1b
	jr     $ra

#ifndef COPY_STANDALONE
	.globl write_message
write_message:
	/* write(1, a0, a1) */
	move $a2, $a1
	move $a1, $a0
	li.w $a0, 1
	li.w $a7, __NR_write
	syscall 0
	jr $ra

	.globl _start
_start:
	bl run_probe
	li.w $a7, __NR_exit
	syscall 0
#endif

	.section .note.GNU-stack,"",@progbits
