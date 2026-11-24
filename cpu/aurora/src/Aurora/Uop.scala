package aurora

import chisel3._
import chisel3.util._

/** 统一微操作（uop）编码定义。
  * 设计原则：译码模块采用 "查找表 + 统一 uop 编码"，后续新增指令时只需在
  * [[Decoder]] 的指令表中增加一行，无需改动数据通路结构。
  */
object UopConst {
  // ---- 功能单元类型 ----
  val FU_ALU = 0
  val FU_BRU = 1
  val FU_LSU = 2
  val FU_DIV = 3
  val FU_NUM = 4
  def fuTypeBits: Int = log2Ceil(FU_NUM)

  // ---- ALU 运算子类型 ----
  val ALU_ADD   = 0
  val ALU_SUB   = 1
  val ALU_AND   = 2
  val ALU_OR    = 3
  val ALU_XOR   = 4
  val ALU_SLL   = 5
  val ALU_SRL   = 6
  val ALU_LU12I = 7  // 结果 = imm（已在译码时左移 12）
  val ALU_COPY  = 8  // 结果 = src1（用于 pcaddu12i 已算好的值）
  val ALU_SRA   = 9  // 算术右移
  val ALU_SLT   = 10 // 有符号比较 (rs1 < rs2) ? 1 : 0
  val ALU_SLTU  = 11 // 无符号比较
  val ALU_MUL   = 12 // 乘法低32位
  val ALU_MULH  = 13 // 有符号乘法高32位
  val ALU_MULHU = 14 // 无符号乘法高32位
  val ALU_NOR   = 15
  val ALU_OP_NUM = 16
  def aluOpBits: Int = log2Ceil(ALU_OP_NUM)
  def isAluMultiply(op: UInt): Bool =
    op === ALU_MUL.U || op === ALU_MULH.U || op === ALU_MULHU.U

  // ---- 分支子类型（BRU）----
  val BR_NONE = 0
  val BR_EQ   = 1 // beq
  val BR_NE   = 2 // bne
  val BR_JIRL = 3 // jirl (间接跳转 + 链接)
  val BR_B    = 4 // b   (无条件相对跳转)
  val BR_BL   = 5 // bl  (无条件相对跳转 + 链接 r1)
  val BR_LT   = 6 // blt  (有符号 <)
  val BR_GE   = 7 // bge  (有符号 >=)
  val BR_LTU  = 8 // bltu (无符号 <)
  val BR_GEU  = 9 // bgeu (无符号 >=)
  val BR_OP_NUM = 10
  def brOpBits: Int = log2Ceil(BR_OP_NUM)

  // ---- 访存子类型（LSU）----
  val MEM_LW  = 0 // ld.w
  val MEM_LB  = 1 // ld.b  (符号扩展)
  val MEM_SW  = 2 // st.w
  val MEM_SB  = 3 // st.b
  val MEM_LH  = 4 // ld.h  (半字，符号扩展)
  val MEM_LHU = 5 // ld.hu (半字，零扩展)
  val MEM_SH  = 6 // st.h
  val MEM_LBU = 7 // ld.bu (字节，零扩展)
  val MEM_LL  = 8 // ll.w
  val MEM_SC  = 9 // sc.w
  val MEM_OP_NUM = 10
  def memOpBits: Int = log2Ceil(MEM_OP_NUM)

  // ---- 低频系统指令子类型（复用 LSU 的单发射/双读口通路）----
  val SYS_CPUCFG  = 0
  val SYS_CSRRD   = 1
  val SYS_CSRWR   = 2
  val SYS_CSRXCHG = 3
  val SYS_CACOP   = 4
  val SYS_DBAR    = 5
  val SYS_IBAR    = 6
  val SYS_TLBSRCH = 7
  val SYS_TLBRD   = 8
  val SYS_TLBWR   = 9
  val SYS_TLBFILL = 10
  val SYS_INVTLB  = 11
  val SYS_ERTN    = 12
  val SYS_IDLE    = 13
  val SYS_TRAP    = 14
  val SYS_RDCNT   = 15
  val SYS_OP_NUM  = 16
  def sysOpBits: Int = log2Ceil(SYS_OP_NUM)

  // ---- 除法/取模子类型（DIV backend）----
  val DIV_W   = 0
  val MOD_W   = 1
  val DIV_WU  = 2
  val MOD_WU  = 3
  val DIV_OP_NUM = 4
  def divOpBits: Int = log2Ceil(DIV_OP_NUM)
}

/** 译码产生的静态微操作信息（重命名前）。 */
class DecodedUop(implicit p: AuroraConfig) extends Bundle {
  val valid    = Bool()
  val pc       = UInt(p.xLen.W)
  val inst     = UInt(32.W)

  val fuType   = UInt(UopConst.fuTypeBits.W)
  val aluOp    = UInt(UopConst.aluOpBits.W)
  val divOp    = UInt(UopConst.divOpBits.W)
  val brOp     = UInt(UopConst.brOpBits.W)
  val memOp    = UInt(UopConst.memOpBits.W)
  val sysOp    = UInt(UopConst.sysOpBits.W)
  val csrNum   = UInt(14.W)

  // 架构寄存器号
  val lrs1     = UInt(p.archRegBits.W)
  val lrs2     = UInt(p.archRegBits.W)
  val lrd      = UInt(p.archRegBits.W)
  val useRs1   = Bool()
  val useRs2   = Bool()
  val writesReg = Bool() // 是否写目的寄存器（且 lrd != 0）

  val imm      = UInt(p.xLen.W) // 已符号/零扩展并按需左移的立即数
  val useImm   = Bool()         // src2 使用立即数

  val isBranch = Bool()
  val isJump   = Bool()         // 无条件跳转（b/bl/jirl）
  val isConditional = Bool()    // beq/bne/blt/bge/bltu/bgeu
  val isCall   = Bool()         // bl 或 jirl rd=r1
  val isRet    = Bool()         // 精确的 jirl r0,r1,0
  val isLoad   = Bool()
  val isStore  = Bool()
  val isSystem = Bool()
  val illegal  = Bool()
  val exception = new ExceptionInfo

  // BPU 预测（由 IFU 携带，供 BRU 判定误预测）
  val predTaken  = Bool()
  val predTarget = UInt(p.xLen.W)
  val predBhtIdx = UInt(p.bhtIdxBits.W)
  val predGhr    = UInt(p.ghrBits.W)
  val predTageTable = UInt(p.tageTableBits.W)
  val predTageGhr = UInt(p.ghrBits.W)
  val predCtr    = UInt(p.tageCounterBits.W)
}

object DecodedUop {
  def default(implicit p: AuroraConfig): DecodedUop = {
    val u = Wire(new DecodedUop)
    u := 0.U.asTypeOf(new DecodedUop)
    u
  }
}
