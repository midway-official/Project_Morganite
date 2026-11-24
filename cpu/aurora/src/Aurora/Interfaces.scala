package aurora

import chisel3._
import chisel3.util.RegEnable
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

object RobOrder {
  /** 按 ROB head 为起点的环形年龄判断。 */
  def isYounger(idx: UInt, boundary: UInt, head: UInt)(implicit p: AuroraConfig): Bool = {
    val i = idx.pad(p.robIdxBits)(p.robIdxBits - 1, 0)
    val b = boundary.pad(p.robIdxBits)(p.robIdxBits - 1, 0)
    val h = head.pad(p.robIdxBits)(p.robIdxBits - 1, 0)
    val iBelowHead = i < h
    val bBelowHead = b < h
    Mux(iBelowHead === bBelowHead, i > b, iBelowHead)
  }
}

/** 分支误预测 / 冲刷广播。
  * 使用指令世代号（robIdx + tag）判断哪些在体指令需要被冲刷：所有 robIdx 在
  * 触发冲刷的分支之后（ROB 顺序上更年轻）的指令都应被清除。
  */
class FlushInfo(implicit p: AuroraConfig) extends Bundle {
  val valid   = Bool()
  val robIdx  = UInt(p.robIdxBits.W) // 触发冲刷的分支所在 ROB 表项
  val newPc   = UInt(p.xLen.W)       // 重定向目标 PC
}

/** 提交时向精确异常路径发送的陷入请求。 */
class TrapRequest(implicit p: AuroraConfig) extends Bundle {
  val robIdx = UInt(p.robIdxBits.W)
  val pc = UInt(p.xLen.W)
  val inst = UInt(32.W)
  val exception = new ExceptionInfo
}

/** IFU 单条取指槽（送往 IDU）。 */
class FetchSlot(implicit p: AuroraConfig) extends Bundle {
  val valid       = Bool()        // 该槽是否有效（组内截断/组起始偏移会置无效）
  val pc          = UInt(p.xLen.W)
  val inst        = UInt(32.W)
  val predTaken   = Bool()        // BPU 预测跳转
  val predTarget  = UInt(p.xLen.W)// 预测目标（用于后续误预测判定）
  val predBhtIdx  = UInt(p.bhtIdxBits.W) // 预测时使用的 BHT 索引（保留调试）
  val predGhr     = UInt(p.ghrBits.W) // 当前分支预测前的 speculative GHR
  val predTageTable = UInt(p.tageTableBits.W) // TAGE 提供者，0 表示 T0
  val predTageGhr = UInt(p.ghrBits.W) // TAGE 查询使用的窗口历史
  val predCtr     = UInt(p.tageCounterBits.W) // 提供者计数器快照
  val exception   = new ExceptionInfo
}

/** IFU -> IDU 的一整组取指结果（宽度 = coreWidth）。 */
class FetchGroup(implicit p: AuroraConfig) extends Bundle {
  val slots = Vec(p.coreWidth, new FetchSlot)
}


/** 重命名后、进入发射队列的 uop。 */
class RenamedUop(implicit p: AuroraConfig) extends Bundle {
  val pc       = UInt(p.xLen.W)
  val inst     = UInt(32.W)

  val fuType   = UInt(UopConst.fuTypeBits.W)
  val aluOp    = UInt(UopConst.aluOpBits.W)
  val divOp    = UInt(UopConst.divOpBits.W)
  val brOp     = UInt(UopConst.brOpBits.W)
  val memOp    = UInt(UopConst.memOpBits.W)
  val sysOp    = UInt(UopConst.sysOpBits.W)
  val csrNum   = UInt(14.W)

  // 物理寄存器编号
  val prs1     = UInt(p.physRegBits.W)
  val prs2     = UInt(p.physRegBits.W)
  val prd      = UInt(p.physRegBits.W)      // 目的物理寄存器（无目的时指向 p0）
  val prs1Ready = Bool()
  val prs2Ready = Bool()
  val useRs1   = Bool()
  val useRs2   = Bool()
  val writesReg = Bool()

  val imm      = UInt(p.xLen.W)
  val useImm   = Bool()

  val isBranch = Bool()
  val isJump   = Bool()
  val isConditional = Bool()
  val isCall   = Bool()
  val isRet    = Bool()
  val isLoad   = Bool()
  val isStore  = Bool()
  val isSystem = Bool()
  val exception = new ExceptionInfo

  // BPU 预测（供 BRU 判定误预测）
  val predTaken  = Bool()
  val predTarget = UInt(p.xLen.W)
  val predBhtIdx = UInt(p.bhtIdxBits.W)
  val predGhr    = UInt(p.ghrBits.W)
  val predTageTable = UInt(p.tageTableBits.W)
  val predTageGhr = UInt(p.ghrBits.W)
  val predCtr    = UInt(p.tageCounterBits.W)

  val robIdx   = UInt(p.robIdxBits.W)
  val lsqIdx   = UInt(p.lsqIdxBits.W)
}

/** 发射队列使用的快速唇醒事件。 */
class WakeEvent(implicit p: AuroraConfig) extends Bundle {
  val prd = UInt(p.physRegBits.W)
  val isMultiply = Bool()
}

/** 记录 ALU 快速结果的生产者通道，供后续执行级选择数据。 */
class FastAluSource(implicit p: AuroraConfig) extends Bundle {
  val valid = Bool()
  val producerLane = UInt(p.aluIdxBits.W)
}

/** ALU 快速结果载荷。 */
class FastAluResult(implicit p: AuroraConfig) extends Bundle {
  val prd = UInt(p.physRegBits.W)
  val data = UInt(p.xLen.W)
}

/** 整数执行单元的发射载荷。 */
class IntegerIssue(implicit p: AuroraConfig) extends Bundle {
  val uop = new RenamedUop
  val src1 = UInt(p.xLen.W)
  val src2 = UInt(p.xLen.W)
  val src1FastAlu = new FastAluSource
  val src2FastAlu = new FastAluSource
  val src1FromScheduledCdb = Bool()
  val src2FromScheduledCdb = Bool()
}

/** 内存执行单元的发射载荷。 */
class MemoryIssue(implicit p: AuroraConfig) extends Bundle {
  val uop = new RenamedUop
  val src1 = UInt(p.xLen.W)
  val src2 = UInt(p.xLen.W)
  val addrFastAlu = new FastAluSource
  val addrFromScheduledCdb = Bool()
}

/** CDB（公共数据总线）写回广播。 */
class CdbEntry(implicit p: AuroraConfig) extends Bundle {
  val valid  = Bool()
  val prd    = UInt(p.physRegBits.W)
  val data   = UInt(p.xLen.W)
  val robIdx = UInt(p.robIdxBits.W)
  val isCacheableLoad = Bool()
  // 分支解析结果（仅 BRU 写回时有效）
  val isBranch   = Bool()
  val mispredict = Bool()
  val targetPc   = UInt(p.xLen.W)
  val actualTaken = Bool()
  val exception = new ExceptionInfo
}

object CdbEntry {
  def default(implicit p: AuroraConfig): CdbEntry = 0.U.asTypeOf(new CdbEntry)

  // Keep the writeback edge and give each receiving queue its own data driver.
  def localDataCopy(data: UInt, capture: Bool): UInt = {
    val copy = RegEnable(data, capture)
    dontTouch(copy)
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(copy.toTarget,
        "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 24")
    })
    copy
  }
}

/** 已提交分支向 BPU 反馈的训练信息（每拍最多一条分支）。 */
class BpuUpdate(implicit p: AuroraConfig) extends Bundle {
  val valid      = Bool()
  val pc         = UInt(p.xLen.W)   // 分支指令 PC
  val taken      = Bool()           // 实际跳转方向
  val target     = UInt(p.xLen.W)   // 实际下一 PC；间接分支时也是 BTB 目标
  val isConditional = Bool()        // 六种条件分支
  val isCall     = Bool()           // bl 或 jirl rd=r1
  val isRet      = Bool()           // 是否为 ret（jirl r0,r1,0，需弹 RAS）
  val isIndirect = Bool()           // 是否为间接跳转（jirl 非 ret）
  val bhtIdx     = UInt(p.bhtIdxBits.W) // 取指时实际查询的 BHT 表项
  val isBackward = Bool()
  // TAGE 提交训练所需的预测时上下文（不在关键路径上）
  val predTaken  = Bool()
  val predGhr    = UInt(p.ghrBits.W)
  val predTageTable = UInt(p.tageTableBits.W)
  val predTageGhr = UInt(p.ghrBits.W)
  val predCtr    = UInt(p.tageCounterBits.W)
}

/** 已解析误预测对 speculative predictor state 的精确修复。 */
class BpuRepair(implicit p: AuroraConfig) extends Bundle {
  val history       = UInt(p.ghrBits.W)
  val pc            = UInt(p.xLen.W)
  val actualTaken   = Bool()
  val isConditional = Bool()
  val isBackward    = Bool()
  // RAS repair: 恢复时按该分支的实际动作修复投机栈
  val isCall        = Bool()
  val isRet         = Bool()
}

/** 差分测试使用的单路指令提交信息。 */
class DifftestCommit(implicit p: AuroraConfig) extends Bundle {
  val valid = Bool()
  val pc = UInt(p.xLen.W)
  val inst = UInt(32.W)
  val wen = Bool()
  val wdest = UInt(p.archRegBits.W)
  val wdata = UInt(p.xLen.W)
}

class DifftestMemoryEvent(implicit p: AuroraConfig) extends Bundle {
  val valid = Bool()
  val paddr = UInt(p.xLen.W)
  val vaddr = UInt(p.xLen.W)
  val data = UInt(p.xLen.W)
}

class DifftestTrap(implicit p: AuroraConfig) extends Bundle {
  val valid = Bool()
  val pc = UInt(p.xLen.W)
  val inst = UInt(32.W)
  val cause = UInt(6.W)
}

class DifftestCsrState extends Bundle {
  val crmd = UInt(32.W)
  val prmd = UInt(32.W)
  val euen = UInt(32.W)
  val ecfg = UInt(32.W)
  val estat = UInt(32.W)
  val era = UInt(32.W)
  val badv = UInt(32.W)
  val eentry = UInt(32.W)
  val tlbidx = UInt(32.W)
  val tlbehi = UInt(32.W)
  val tlbelo0 = UInt(32.W)
  val tlbelo1 = UInt(32.W)
  val asid = UInt(32.W)
  val pgdl = UInt(32.W)
  val pgdh = UInt(32.W)
  val save0 = UInt(32.W)
  val save1 = UInt(32.W)
  val save2 = UInt(32.W)
  val save3 = UInt(32.W)
  val tid = UInt(32.W)
  val tcfg = UInt(32.W)
  val tval = UInt(32.W)
  val ticlr = UInt(32.W)
  val llbctl = UInt(32.W)
  val tlbrentry = UInt(32.W)
  val dmw0 = UInt(32.W)
  val dmw1 = UInt(32.W)
}

/** 有序 store 提交路径的调试观测信息。 */
class OrderedStoreDebug(implicit p: AuroraConfig) extends Bundle {
  val recoverValid = Bool()
  val recoverIdx = UInt(p.robIdxBits.W)
  val robRequestValid = Bool()
  val robRequestIdx = UInt(p.robIdxBits.W)
  val robRequestAcked = Bool()
  val lsuCompletion = Bool()
  val lsuCapture = Bool()
  val lsuPending = Bool()
  val lsuDoneHeld = Bool()
  val lsuRobIdx = UInt(p.robIdxBits.W)
  val awFire = Bool()
  val wFire = Bool()
  val bFire = Bool()
  val addr = UInt(p.xLen.W)
  val data = UInt(p.xLen.W)
  val strb = UInt(p.strbBits.W)
  val commitMask = UInt(p.coreWidth.W)
  val commitCount = UInt(p.issueCountBits.W)
}
