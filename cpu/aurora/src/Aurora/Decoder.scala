package aurora

import chisel3._
import chisel3.util._
import UopConst._

/** LA32R 指令译码器（组合逻辑）。
  * 采用 "查找表(BitPat) + 统一 uop 字段" 结构：每条指令用一个 BitPat 匹配，
  * 产出一组控制字段。新增指令时只需在 [[Decoder.table]] 增加一行，无需改动
  * 数据通路。
  * LA32R 指令编码（LoongArch 32-bit reduced），字段（[31:0]）：
  *  - 3R 型:  op[31:15] | rk[14:10] | rj[9:5] | rd[4:0]
  *  - I26:    op[31:26] | offs[25:10] | offs[15:0]低位在[9:0]... (b/bl)
  */
class Decoder(implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val pc   = Input(UInt(p.xLen.W))
    val uop  = Output(new DecodedUop)
  })

  val inst = io.inst

  // ---- 字段提取 ----
  val rd  = inst(4, 0)
  val rj  = inst(9, 5)
  val rk  = inst(14, 10)
  val ui5 = inst(14, 10)
  val si12 = inst(21, 10)
  val si14 = inst(23, 10)
  val ui12 = inst(21, 10)
  val si16 = inst(25, 10)
  val si20 = inst(24, 5)
  val csrNum = inst(23, 10)
  val offs15_0 = inst(25, 10)
  val offs25_16 = inst(9, 0)

  // 立即数扩展
  val immSi12 = Cat(Fill(p.xLen - 12, si12(11)), si12)          // 符号扩展 12->32
  val immUi12 = Cat(0.U((p.xLen - 12).W), ui12)                   // 零扩展
  val immSi14Sh2 = Cat(Fill(p.xLen - 14 - 2, si14(13)), si14,
    0.U(2.W))
  val immSi20Sh12 = Cat(si20, 0.U(12.W))               // lu12i/pcaddu12i: si20<<12
  // 分支偏移（<<2，字对齐）
  val offs16 = Cat(Fill(p.xLen - 16 - 2, si16(15)), si16, 0.U(2.W)) // 2RI16: si16<<2 符号扩展
  // I26格式: inst[9:0]是offs[25:16], inst[25:10]是offs[15:0]
  // 拼接为 offs26 = {offs[25:16], offs[15:0]}，然后<<2，用offs[25]做符号扩展
  val offs26 = Cat(Fill(p.xLen - 10 - 16 - 2, offs25_16(9)), offs25_16, offs15_0, 0.U(2.W))

  // ---- 指令编码常量（BitPat，均为完整 32 位）----
  def ADD_W   = BitPat("b00000000000100000???????????????")
  def SUB_W   = BitPat("b00000000000100010???????????????")
  def NOR     = BitPat("b00000000000101000???????????????")
  def AND     = BitPat("b00000000000101001???????????????")
  def OR      = BitPat("b00000000000101010???????????????")
  def XOR     = BitPat("b00000000000101011???????????????")
  def SLT     = BitPat("b00000000000100100???????????????")
  def SLTU    = BitPat("b00000000000100101???????????????")
  def MUL_W   = BitPat("b00000000000111000???????????????")
  def MULH_W  = BitPat("b00000000000111001???????????????")
  def MULH_WU = BitPat("b00000000000111010???????????????")
  def DIV_W   = BitPat("b00000000001000000???????????????")
  def MOD_W   = BitPat("b00000000001000001???????????????")
  def DIV_WU  = BitPat("b00000000001000010???????????????")
  def MOD_WU  = BitPat("b00000000001000011???????????????")
  // shift (立即数)
  def SLLI_W  = BitPat("b00000000010000001???????????????")
  def SRLI_W  = BitPat("b00000000010001001???????????????")
  def SRAI_W  = BitPat("b00000000010010001???????????????")
  // 3R shift (寄存器)
  def SLL_W   = BitPat("b00000000000101110???????????????")
  def SRL_W   = BitPat("b00000000000101111???????????????")
  def SRA_W   = BitPat("b00000000000110000???????????????")
  def ADDI_W  = BitPat("b0000001010??????????????????????")
  def ANDI    = BitPat("b0000001101??????????????????????")
  def ORI     = BitPat("b0000001110??????????????????????")
  def XORI    = BitPat("b0000001111??????????????????????")
  def SLTI    = BitPat("b0000001000??????????????????????")
  def SLTUI   = BitPat("b0000001001??????????????????????")
  def LU12I_W    = BitPat("b0001010?????????????????????????")
  def PCADDU12I  = BitPat("b0001110?????????????????????????")
  // 访存 2RI12
  def LD_B    = BitPat("b0010100000??????????????????????")
  def LD_H    = BitPat("b0010100001??????????????????????")
  def LD_W    = BitPat("b0010100010??????????????????????")
  def LD_BU   = BitPat("b0010101000??????????????????????")
  def LD_HU   = BitPat("b0010101001??????????????????????")
  def ST_B    = BitPat("b0010100100??????????????????????")
  def ST_H    = BitPat("b0010100101??????????????????????")
  def ST_W    = BitPat("b0010100110??????????????????????")
  def PRELD   = BitPat("b0010101011??????????????????????")
  def LL_W    = BitPat("b00100000????????????????????????")
  def SC_W    = BitPat("b00100001????????????????????????")
  // 分支 2RI16
  def JIRL    = BitPat("b010011??????????????????????????")
  def BEQ     = BitPat("b010110??????????????????????????")
  def BNE     = BitPat("b010111??????????????????????????")
  def BLT     = BitPat("b011000??????????????????????????")
  def BGE     = BitPat("b011001??????????????????????????")
  def BLTU    = BitPat("b011010??????????????????????????")
  def BGEU    = BitPat("b011011??????????????????????????")
  def B       = BitPat("b010100??????????????????????????")
  def BL      = BitPat("b010101??????????????????????????")
  // 系统指令
  def CPUCFG  = BitPat("b0000000000000000011011??????????")
  def CSR     = BitPat("b00000100????????????????????????")
  def CACOP   = BitPat("b0000011000??????????????????????")
  def TLBSRCH = BitPat("b00000110010010000010100000000000")
  def TLBRD   = BitPat("b00000110010010000010110000000000")
  def TLBWR   = BitPat("b00000110010010000011000000000000")
  def TLBFILL = BitPat("b00000110010010000011010000000000")
  def ERTN    = BitPat("b00000110010010000011100000000000")
  def IDLE    = BitPat("b00000110010010001???????????????")
  def INVTLB  = BitPat("b00000110010010011???????????????")
  def DBAR    = BitPat("b00111000011100100???????????????")
  def IBAR    = BitPat("b00111000011100101???????????????")
  def BREAK   = BitPat("b00000000001010100???????????????")
  def SYSCALL = BitPat("b00000000001010110???????????????")
  def RDCNTID = BitPat("b0000000000000000011000?????00000")
  def RDCNTVL = BitPat("b000000000000000001100000000?????")
  def RDCNTVH = BitPat("b000000000000000001100100000?????")

  val u = DecodedUop.default
  u.valid := true.B
  u.pc    := io.pc
  u.inst  := inst
  u.illegal := true.B

  // 默认字段
  u.lrs1 := rj
  u.lrs2 := rk
  u.lrd  := rd

  // 便捷设置函数
  def setAlu(op: Int, useI: Boolean, useR2: Boolean, imm: UInt): Unit = {
    u.illegal := false.B
    u.fuType := FU_ALU.U
    u.aluOp  := op.U
    u.useRs1 := true.B
    u.useRs2 := (!useI && useR2).B
    u.useImm := useI.B
    u.imm    := imm
    u.writesReg := (rd =/= 0.U)
  }

  when(ADD_W === inst)        { setAlu(ALU_ADD, false, true, 0.U) }
  .elsewhen(SUB_W === inst)   { setAlu(ALU_SUB, false, true, 0.U) }
  .elsewhen(NOR === inst)     { setAlu(ALU_NOR, false, true, 0.U) }
  .elsewhen(AND === inst)     { setAlu(ALU_AND, false, true, 0.U) }
  .elsewhen(OR === inst)      { setAlu(ALU_OR,  false, true, 0.U) }
  .elsewhen(XOR === inst)     { setAlu(ALU_XOR, false, true, 0.U) }
  .elsewhen(SLT === inst)     { setAlu(ALU_SLT, false, true, 0.U) }
  .elsewhen(SLTU === inst)    { setAlu(ALU_SLTU, false, true, 0.U) }
  .elsewhen(MUL_W === inst)   { setAlu(ALU_MUL, false, true, 0.U) }
  .elsewhen(MULH_W === inst)  { setAlu(ALU_MULH, false, true, 0.U) }
  .elsewhen(MULH_WU === inst) { setAlu(ALU_MULHU, false, true, 0.U) }
  .elsewhen(DIV_W === inst)   { setDiv(UopConst.DIV_W) }
  .elsewhen(MOD_W === inst)   { setDiv(UopConst.MOD_W) }
  .elsewhen(DIV_WU === inst)  { setDiv(UopConst.DIV_WU) }
  .elsewhen(MOD_WU === inst)  { setDiv(UopConst.MOD_WU) }
  .elsewhen(SLL_W === inst)   { setAlu(ALU_SLL, false, true, 0.U) }
  .elsewhen(SRL_W === inst)   { setAlu(ALU_SRL, false, true, 0.U) }
  .elsewhen(SRA_W === inst)   { setAlu(ALU_SRA, false, true, 0.U) }
  .elsewhen(SLLI_W === inst)  { setAlu(ALU_SLL, true, false, Cat(0.U(27.W), ui5)) }
  .elsewhen(SRLI_W === inst)  { setAlu(ALU_SRL, true, false, Cat(0.U(27.W), ui5)) }
  .elsewhen(SRAI_W === inst)  { setAlu(ALU_SRA, true, false, Cat(0.U(27.W), ui5)) }
  .elsewhen(ADDI_W === inst)  { setAlu(ALU_ADD, true, false, immSi12) }
  .elsewhen(ANDI === inst)    { setAlu(ALU_AND, true, false, immUi12) }
  .elsewhen(ORI === inst)     { setAlu(ALU_OR,  true, false, immUi12) }
  .elsewhen(XORI === inst)    { setAlu(ALU_XOR, true, false, immUi12) }
  .elsewhen(SLTI === inst)    { setAlu(ALU_SLT, true, false, immSi12) }
  .elsewhen(SLTUI === inst)   { setAlu(ALU_SLTU, true, false, immSi12) }
  .elsewhen(LU12I_W === inst) {
    // rd = si20 << 12 ; 无源寄存器
    u.illegal := false.B
    u.fuType := FU_ALU.U; u.aluOp := ALU_LU12I.U
    u.useRs1 := false.B; u.useRs2 := false.B
    u.useImm := true.B; u.imm := immSi20Sh12
    u.writesReg := (rd =/= 0.U)
  }
  .elsewhen(PCADDU12I === inst) {
    // rd = pc + (si20 << 12) ; 用 imm 携带 (pc + off) 结果，ALU_COPY
    u.illegal := false.B
    u.fuType := FU_ALU.U; u.aluOp := ALU_COPY.U
    u.useRs1 := false.B; u.useRs2 := false.B
    u.useImm := true.B; u.imm := io.pc + immSi20Sh12
    u.writesReg := (rd =/= 0.U)
  }
  // 访存
  .elsewhen(LD_W === inst)  { setMem(MEM_LW, isLd = true, immSi12) }
  .elsewhen(LD_H === inst)  { setMem(MEM_LH, isLd = true, immSi12) }
  .elsewhen(LD_HU === inst) { setMem(MEM_LHU, isLd = true, immSi12) }
  .elsewhen(LD_B === inst)  { setMem(MEM_LB, isLd = true, immSi12) }
  .elsewhen(LD_BU === inst) { setMem(MEM_LBU, isLd = true, immSi12) }
  .elsewhen(ST_W === inst)  { setMem(MEM_SW, isLd = false, immSi12) }
  .elsewhen(ST_H === inst)  { setMem(MEM_SH, isLd = false, immSi12) }
  .elsewhen(ST_B === inst)  { setMem(MEM_SB, isLd = false, immSi12) }
  .elsewhen(LL_W === inst)  { setMem(MEM_LL, isLd = true, immSi14Sh2) }
  .elsewhen(SC_W === inst)  { setSc(immSi14Sh2) }
  .elsewhen(PRELD === inst) {
    u.illegal := false.B
    u.fuType := FU_ALU.U
    u.useRs1 := false.B
    u.useRs2 := false.B
    u.useImm := false.B
    u.writesReg := false.B
  }
  // 分支
  .elsewhen(BEQ === inst)  { setBr(BR_EQ, cond = true, link = false, useR2 = true, offs16) }
  .elsewhen(BNE === inst)  { setBr(BR_NE, cond = true, link = false, useR2 = true, offs16) }
  .elsewhen(BLT === inst)  { setBr(BR_LT, cond = true, link = false, useR2 = true, offs16) }
  .elsewhen(BGE === inst)  { setBr(BR_GE, cond = true, link = false, useR2 = true, offs16) }
  .elsewhen(BLTU === inst) { setBr(BR_LTU, cond = true, link = false, useR2 = true, offs16) }
  .elsewhen(BGEU === inst) { setBr(BR_GEU, cond = true, link = false, useR2 = true, offs16) }
  .elsewhen(JIRL === inst) {
    // rd = pc+4 ; target = rj + si16<<2 ; 有条件？无条件跳转
    u.illegal := false.B
    u.fuType := FU_BRU.U; u.brOp := BR_JIRL.U
    u.useRs1 := true.B; u.useRs2 := false.B
    u.useImm := true.B; u.imm := offs16
    u.isBranch := true.B; u.isJump := true.B
    u.isCall := rd === 1.U
    u.isRet := (rd === 0.U) && (rj === 1.U) && (si16 === 0.U)
    u.writesReg := (rd =/= 0.U)
    u.lrd := rd
  }
  .elsewhen(B === inst) {
    u.illegal := false.B
    u.fuType := FU_BRU.U; u.brOp := BR_B.U
    u.useRs1 := false.B; u.useRs2 := false.B
    u.useImm := true.B; u.imm := offs26
    u.isBranch := true.B; u.isJump := true.B
    u.writesReg := false.B
  }
  .elsewhen(BL === inst) {
    u.illegal := false.B
    u.fuType := FU_BRU.U; u.brOp := BR_BL.U
    u.useRs1 := false.B; u.useRs2 := false.B
    u.useImm := true.B; u.imm := offs26
    u.isBranch := true.B; u.isJump := true.B
    u.isCall := true.B
    u.writesReg := true.B
    u.lrd := 1.U // bl 隐式写 r1
  }
  .elsewhen(RDCNTID === inst) {
    setSystem(SYS_RDCNT, writesRd = false)
    u.csrNum := 2.U
    u.lrd := rj
    u.writesReg := rj =/= 0.U
  }
  .elsewhen(RDCNTVL === inst) {
    setSystem(SYS_RDCNT, writesRd = true)
    u.csrNum := 0.U
  }
  .elsewhen(RDCNTVH === inst) {
    setSystem(SYS_RDCNT, writesRd = true)
    u.csrNum := 1.U
  }
  .elsewhen(CPUCFG === inst) {
    setSystem(SYS_CPUCFG, writesRd = true)
    u.useRs1 := true.B
    u.lrs1 := rj
  }
  .elsewhen(CSR === inst && rj === 0.U) {
    // CSRRD: CSR 号在立即数字段中，无通用源寄存器。
    setSystem(SYS_CSRRD, writesRd = true)
    u.csrNum := csrNum
  }
  .elsewhen(CSR === inst && rj === 1.U) {
    // CSRWR: rd 同时是源和目的；源必须读取重命名前的 rd 映射。
    setSystem(SYS_CSRWR, writesRd = true)
    u.csrNum := csrNum
    u.useRs1 := true.B
    u.lrs1 := rd
  }
  .elsewhen(CSR === inst) {
    // CSRXCHG: rd 提供写数据，rj 提供逐位写掩码，结果写回 rd。
    setSystem(SYS_CSRXCHG, writesRd = true)
    u.csrNum := csrNum
    u.useRs1 := true.B
    u.useRs2 := true.B
    u.lrs1 := rd
    u.lrs2 := rj
  }
  .elsewhen(CACOP === inst) {
    setSystem(SYS_CACOP, writesRd = false)
    u.useRs1 := true.B
    u.lrs1 := rj
    u.useImm := true.B
    u.imm := immSi12
    // CACOP 的 code 位于 rd 字段；复用 csrNum 载荷传递该 5 位值。
    u.csrNum := Cat(0.U(9.W), rd)
  }
  .elsewhen(TLBSRCH === inst) {
    setSystem(SYS_TLBSRCH, writesRd = false)
  }
  .elsewhen(TLBRD === inst) {
    setSystem(SYS_TLBRD, writesRd = false)
  }
  .elsewhen(TLBWR === inst) {
    setSystem(SYS_TLBWR, writesRd = false)
  }
  .elsewhen(TLBFILL === inst) {
    setSystem(SYS_TLBFILL, writesRd = false)
  }
  .elsewhen(ERTN === inst) {
    setSystem(SYS_ERTN, writesRd = false)
  }
  .elsewhen(IDLE === inst) {
    setSystem(SYS_IDLE, writesRd = false)
  }
  .elsewhen(INVTLB === inst) {
    setSystem(SYS_INVTLB, writesRd = false)
    u.useRs1 := true.B
    u.useRs2 := true.B
    u.lrs1 := rj
    u.lrs2 := rk
    u.csrNum := Cat(0.U(9.W), rd)
  }
  .elsewhen(SYSCALL === inst) {
    setSystem(SYS_TRAP, writesRd = false)
    u.exception.valid := true.B
    u.exception.ecode := ExceptionCode.SYS.U
  }
  .elsewhen(BREAK === inst) {
    setSystem(SYS_TRAP, writesRd = false)
    u.exception.valid := true.B
    u.exception.ecode := ExceptionCode.BRK.U
  }
  .elsewhen(DBAR === inst) {
    setSystem(SYS_DBAR, writesRd = false)
  }
  .elsewhen(IBAR === inst) {
    setSystem(SYS_IBAR, writesRd = false)
  }

  when(u.illegal) {
    u.fuType := FU_LSU.U
    u.sysOp := SYS_TRAP.U
    u.isSystem := true.B
    u.writesReg := false.B
    u.exception.valid := true.B
    u.exception.ecode := ExceptionCode.INE.U
  }

  def setMem(op: Int, isLd: Boolean, imm: UInt): Unit = {
    u.illegal := false.B
    u.fuType := FU_LSU.U; u.memOp := op.U
    u.useRs1 := true.B          // 基址 rj
    u.useRs2 := (!isLd).B       // store 需要数据 rd
    u.useImm := true.B; u.imm := imm
    u.isLoad := isLd.B
    u.isStore := (!isLd).B
    u.writesReg := (if (isLd) (rd =/= 0.U) else false.B)
    // store 的数据寄存器是 rd 字段，映射到 lrs2
    if (!isLd) { u.lrs2 := rd }
  }

  def setSc(imm: UInt): Unit = {
    u.illegal := false.B
    u.fuType := FU_LSU.U
    u.memOp := MEM_SC.U
    u.useRs1 := true.B
    u.useRs2 := true.B
    u.useImm := true.B
    u.imm := imm
    u.isStore := true.B
    u.writesReg := rd =/= 0.U
    u.lrs2 := rd
  }

  def setBr(op: Int, cond: Boolean, link: Boolean, useR2: Boolean, imm: UInt): Unit = {
    u.illegal := false.B
    u.fuType := FU_BRU.U; u.brOp := op.U
    u.useRs1 := true.B
    u.useRs2 := useR2.B
    u.useImm := true.B; u.imm := imm
    u.isBranch := true.B
    u.isConditional := cond.B
    u.isCall := link.B
    u.writesReg := false.B
    // beq/bne 比较 rj 与 rd 字段 -> lrs2 = rd
    u.lrs2 := rd
  }

  def setDiv(op: Int): Unit = {
    u.illegal := false.B
    u.fuType := FU_DIV.U
    u.divOp := op.U
    u.useRs1 := true.B
    u.useRs2 := true.B
    u.useImm := false.B
    u.writesReg := (rd =/= 0.U)
  }

  def setSystem(op: Int, writesRd: Boolean): Unit = {
    u.illegal := false.B
    // 系统指令复用串行 LSU 通路，避免增加 CDB、PRF 读口和普通 ALU 关键路径。
    u.fuType := FU_LSU.U
    u.sysOp := op.U
    u.isSystem := true.B
    u.writesReg := (if (writesRd) (rd =/= 0.U) else false.B)
  }

  io.uop := u
}
