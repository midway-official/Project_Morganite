package aurora

import chisel3._
import chisel3.util._
import UopConst._

/** 分支执行级。入口、CDB 与恢复寄存器由 Core 持有；预测表在 BPU 中。
  * 同一组比较器和目标加法器服务所有分支，不合并执行入口与写回级。
  */
class BRU(implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val in          = Flipped(Valid(new ExecIn))
    val out         = Valid(new CdbEntry)
    val flush       = Input(Bool())
    val bpuUpdate   = Output(new BpuUpdate)  // 训练反馈给 BPU
    val bpuRepair   = Output(new BpuRepair)
    // 分支预测统计计数器（持续累计，不受 flush 影响）
    val brTotal     = Output(UInt(32.W))
    val brMispredict = Output(UInt(32.W))
  })
  val u = io.in.bits.uop
  val a = io.in.bits.src1
  val b = io.in.bits.src2
  val pc = u.pc

  val linkVal = pc + 4.U
  val equal = a === b
  val signedLess = a.asSInt < b.asSInt
  val unsignedLess = a < b
  val taken = MuxLookup(u.brOp, false.B, Seq(
    BR_EQ.U -> equal, BR_NE.U -> !equal,
    BR_LT.U -> signedLess, BR_GE.U -> !signedLess,
    BR_LTU.U -> unsignedLess, BR_GEU.U -> !unsignedLess,
    BR_B.U -> true.B, BR_BL.U -> true.B, BR_JIRL.U -> true.B))
  val targetBase = Mux(u.brOp === BR_JIRL.U, a, pc)
  val target = targetBase + u.imm

  // 实际是否跳转与目标已由上面计算。与 BPU 预测比较判定误预测：
  //  - 预测方向错误，或
  //  - 预测 taken 但目标与实际不一致（如 JIRL 间接目标）。
  val actualTarget = Mux(taken, target, linkVal)
  val targetMismatch = target =/= u.predTarget
  val mispredict = Mux(taken, !u.predTaken || targetMismatch, u.predTaken)

  // flush 沿的 CDB payload 只会写入被清 valid 的下游寄存器，因而不可见。
  // 保持 payload valid 不受 flush 门控，避免 flush 穿过 CDB 旁路到操作数 D。
  val execute = io.in.valid
  val fire = execute && !io.flush
  io.out.valid := execute
  val c = Wire(new CdbEntry)
  c := CdbEntry.default
  c.valid := execute
  c.prd := u.prd
  c.data := linkVal          // jirl/bl 写链接寄存器 = pc+4
  c.robIdx := u.robIdx
  c.isBranch := true.B
  c.mispredict := mispredict
  c.targetPc := actualTarget
  c.actualTaken := taken
  io.out.bits := c

  // ---- BPU 训练反馈 ----
  val isJirl     = u.brOp === BR_JIRL.U
  val isRet      = u.isRet
  val isIndirect = isJirl && !isRet

  io.bpuUpdate.valid      := fire
  io.bpuUpdate.pc         := pc
  io.bpuUpdate.taken      := taken
  io.bpuUpdate.target     := actualTarget
  io.bpuUpdate.isConditional := u.isConditional
  io.bpuUpdate.isCall     := u.isCall
  io.bpuUpdate.isRet      := isRet
  io.bpuUpdate.isIndirect := isIndirect
  io.bpuUpdate.bhtIdx     := u.predBhtIdx
  io.bpuUpdate.isBackward := u.isConditional && u.imm(p.xLen - 1)
  io.bpuUpdate.predTaken := taken
  io.bpuUpdate.predGhr := u.predGhr
  io.bpuUpdate.predTageTable := u.predTageTable
  io.bpuUpdate.predTageGhr := u.predTageGhr
  io.bpuUpdate.predCtr := u.predCtr

  val repairedConditionalHistory = if (p.ghrBits == 1) taken.asUInt
    else Cat(u.predGhr(p.ghrBits - 2, 0), taken)
  io.bpuRepair.history := Mux(u.isConditional,
    repairedConditionalHistory, u.predGhr)
  io.bpuRepair.pc := pc
  io.bpuRepair.actualTaken := taken
  io.bpuRepair.isConditional := u.isConditional
  io.bpuRepair.isBackward := u.isConditional && u.imm(p.xLen - 1)
  io.bpuRepair.isCall := u.isCall
  io.bpuRepair.isRet := isRet

  // ---- 分支预测统计计数器 ----
  val cntTotal     = RegInit(0.U(32.W))
  val cntMispredict = RegInit(0.U(32.W))
  when(fire) {
    cntTotal := cntTotal + 1.U
    when(mispredict) { cntMispredict := cntMispredict + 1.U }
  }
  io.brTotal      := cntTotal
  io.brMispredict := cntMispredict
}
