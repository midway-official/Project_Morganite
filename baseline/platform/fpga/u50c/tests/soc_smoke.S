.section .text,"ax"
.globl _start
_start:
    # Cached low segment and uncached 0xa... MMIO alias, as in CPU regressions.
    li.w $r12,0x19
    csrwr $r12,0x180
    li.w $r12,0xa0000009
    csrwr $r12,0x181
    li.w $r12,0x10
    csrwr $r12,0
    li.w $r13,0xbfe001e0
    li.w $r12,0x80
    st.b $r12,$r13,3
    st.b $r0,$r13,1
    li.w $r12,1
    st.b $r12,$r13,0
    li.w $r12,3
    st.b $r12,$r13,3
    st.b $r0,$r13,1
    st.b $r0,$r13,7

    # Fill and read 32 cache lines using an independently recomputed pattern.
    li.w $r14,0x10000
    li.w $r15,256
    li.w $r16,0x13579bdf
fill:
    st.w $r16,$r14,0
    addi.w $r14,$r14,4
    addi.w $r16,$r16,257
    addi.w $r15,$r15,-1
    bne $r15,$r0,fill
    dbar 0
    li.w $r14,0x10000
    li.w $r15,256
    li.w $r16,0x13579bdf
check:
    ld.w $r17,$r14,0
    bne $r17,$r16,fail
    addi.w $r14,$r14,4
    addi.w $r16,$r16,257
    addi.w $r15,$r15,-1
    bne $r15,$r0,check

    li.w $r14,0x10200
    li.w $r15,0x11223344
    st.w $r15,$r14,0
    li.w $r15,0xaa
    st.b $r15,$r14,1
    li.w $r15,0xbbcc
    st.h $r15,$r14,2
    ld.w $r16,$r14,0
    li.w $r15,0xbbccaa44
    bne $r15,$r16,fail

    li.w $r14,0x10300
    li.w $r15,17
    st.w $r15,$r14,0
    dbar 0
    ll.w $r16,$r14,0
    addi.w $r16,$r16,1
    sc.w $r16,$r14,0
    li.w $r15,1
    bne $r16,$r15,fail
    ld.w $r16,$r14,0
    li.w $r15,18
    bne $r16,$r15,fail

    # Let the host inject a byte; service a real external interrupt, then ERTN.
    li.w $r14,0x10400
    st.w $r0,$r14,0
    la.local $r12,irq_handler
    csrwr $r12,0xc
    li.w $r12,8
    csrwr $r12,4
    li.w $r12,1
    st.b $r12,$r13,1
    li.w $r12,0x14
    csrwr $r12,0
    li.w $r12,0x5a
    st.b $r12,$r13,7
wait_irq:
    ld.w $r15,$r14,0
    beq $r15,$r0,wait_irq
    li.w $r16,0x600dbeef
    bne $r15,$r16,fail
    # The debug master is not cache coherent. Tick2h's IBAR performs a full
    # cache clean; finish it before telling the host to inspect backing HBM.
    ibar 0
    la.local $r14,ok_text
    b print_string
fail:
    la.local $r14,fail_text
print_string:
    ld.bu $r15,$r14,0
    beq $r15,$r0,done
tx_wait:
    ld.bu $r16,$r13,5
    andi $r16,$r16,0x20
    beq $r16,$r0,tx_wait
    st.b $r15,$r13,0
    addi.w $r14,$r14,1
    b print_string
done:
    dbar 0
    b done

.balign 64
irq_handler:
    li.w $r21,0xbfe001e0
    ld.bu $r22,$r21,0
    li.w $r20,0x5a
    bne $r22,$r20,fail
    st.b $r0,$r21,1
    li.w $r21,0x10400
    li.w $r22,0x600dbeef
    st.w $r22,$r21,0
    ertn
ok_text:
    .asciz "AURORA_SOC_SMOKE_OK\n"
fail_text:
    .asciz "AURORA_SOC_SMOKE_FAIL\n"
