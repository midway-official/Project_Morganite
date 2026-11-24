package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation
import UopConst._

/** ISU：与 coreWidth 对齐的并行重命名 / 分配 / 分发单元。
  * 结构：
  *  - specRat / archRat：架构->物理 映射（推测态 + 提交态，用于冲刷恢复）。
  *  - FreeList：空闲物理寄存器，支持每周期多弹出（<= coreWidth）。
  *  - ready 表：物理寄存器数据是否就绪。
  * 组内并行重命名的相关性处理：
  *  - RAW 旁路：组内较年轻指令的源寄存器若与更旧指令的目的架构寄存器相同，
  *    则源物理号取该更旧指令新分配的 prd（而非 RAT 读出的旧值）。
  *  - WAW：同组多条写同一架构寄存器时，prevPrd 需取组内上一个写者的 prd；
  *    最终 specRat 更新为组内最后一个写者的 prd。
  * 分支处理（多分支推测）：
  *  - 组内在"第一个分支"处截断：只分发到第一个分支（含）为止的前缀；
  *  - 允许多条未解析分支同时在体，分支后的指令作为推测路径执行；
  *  - 误预测时 ROB 冲刷清除所有更年轻指令。
  * 分发策略：按程序顺序分发当前可接受的最长前缀。每条指令依次检查
  * FreeList、ROB 和目标 IQ 容量；遇到第一条资源不足的指令即停止，下一拍
  * 从该指令继续，绝不越过阻塞指令分发更年轻指令。
  */
class ISU(numCdb: Int, useLsqCapacityMask: Boolean = false)(implicit p: AuroraConfig) extends Module {
  private val dispatchCountBits = p.issueCountBits
  private val pendingCapacityBits = log2Ceil(2 * p.coreWidth + 1)
  private val aluIdxBits = p.aluIdxBits
  private val divIdxBits = p.divIdxBits
  private val checkpointIdxBits = p.checkpointIdxBits

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new DecodeGroup))

    // 分发端口：每路一个（送往对应 FU 的 IQ，由顶层按 fuType 路由）
    val dispatch = Vec(p.coreWidth, Decoupled(new RenamedUop))
    val dispatchCapturedSrc1 = Output(Vec(p.coreWidth, Valid(UInt(p.xLen.W))))
    val dispatchCapturedSrc2 = Output(Vec(p.coreWidth, Valid(UInt(p.xLen.W))))
    val aluIqCapacity = Input(Vec(p.numAlu, UInt(pendingCapacityBits.W)))
    val bruIqCapacity = Input(UInt(dispatchCountBits.W))
    val lsuIqCapacity = Input(UInt(dispatchCountBits.W))
    val divIqCapacity = Input(Vec(p.numDiv, UInt(dispatchCountBits.W)))
    val lqCapacity = Input(UInt(dispatchCountBits.W))
    val sqCapacity = Input(UInt(dispatchCountBits.W))
    val lqCapacityMask = if (useLsqCapacityMask) Some(Input(UInt(p.coreWidth.W))) else None
    val sqCapacityMask = if (useLsqCapacityMask) Some(Input(UInt(p.coreWidth.W))) else None
    val dispatchAluSelect = Output(Vec(p.numAlu,
      Vec(p.coreWidth, UInt(p.coreWidth.W))))
    val dispatchBruSelect = Output(Vec(p.coreWidth, UInt(p.coreWidth.W)))
    val dispatchLsuSelect = Output(Vec(p.coreWidth, UInt(p.coreWidth.W)))
    val dispatchDivSelect = Output(Vec(p.numDiv,
      Vec(p.coreWidth, UInt(p.coreWidth.W))))
    val dispatchAluCount = Output(Vec(p.numAlu, UInt(dispatchCountBits.W)))
    val dispatchBruCount = Output(UInt(dispatchCountBits.W))
    val dispatchLsuCount = Output(UInt(dispatchCountBits.W))
    val dispatchLqCount = if (useLsqCapacityMask) Some(Output(UInt(dispatchCountBits.W))) else None
    val dispatchSqCount = if (useLsqCapacityMask) Some(Output(UInt(dispatchCountBits.W))) else None
    val dispatchDivCount = Output(Vec(p.numDiv, UInt(dispatchCountBits.W)))
    val inputActive = Output(Bool())

    // ROB 多分配
    val robAllocReq  = Output(Vec(p.coreWidth, Bool()))
    val robAllocEntry = Output(Vec(p.coreWidth, new RobEntry))
    val robAllocIdx  = Input(Vec(p.coreWidth, UInt(p.robIdxBits.W))) // 每路将分配到的 ROB 序号
    val robCanAlloc  = Input(UInt(p.issueCountBits.W))              // ROB 本周期可接收的条数

    // CDB 唤醒
    val cdb = Input(Vec(numCdb, new CdbEntry))

    // 提交端口（多提交）
    val commit = Input(Vec(p.coreWidth, new Bundle {
      val valid     = Bool()
      val writesReg = Bool()
      val ldst      = UInt(p.archRegBits.W)
      val prd       = UInt(p.physRegBits.W)
      val prevPrd   = UInt(p.physRegBits.W)
      val isSystem  = Bool()
    }))

    val flush = Input(new FlushInfo)
    val recover = Input(Valid(UInt(p.robIdxBits.W)))
    val robHeadIdx = Input(UInt(p.robIdxBits.W))
    val hasUnresolvedBranch = Input(Bool())
    val interruptPending = Input(Bool())
  })

  val w = p.coreWidth
  val nArch = p.numArchRegs
  val nPhys = p.numPhysRegs

  val specRat = RegInit(VecInit((0 until nArch).map(_.U(p.physRegBits.W))))
  val archRat = RegInit(VecInit((0 until nArch).map(_.U(p.physRegBits.W))))
  // flush 当拍先提交 archRat，下一拍再恢复 specRat，切断 ROB 提交组合链。
  val ratRestorePending = RegInit(false.B)
  val allPhysReady = ((BigInt(1) << nPhys) - 1).U(nPhys.W)
  val readyStorage = RegInit(allPhysReady)
  val pendingReadyAllocCount = RegInit(0.U(log2Ceil(w + 1).W))
  val pendingReadyAllocPrd = Reg(Vec(w, UInt(p.physRegBits.W)))
  val pendingReadyWakeValid = RegInit(VecInit(Seq.fill(numCdb)(false.B)))
  val pendingReadyWakePrd = Reg(Vec(numCdb, UInt(p.physRegBits.W)))
  val pendingReadyAllocMask = (0 until w).map(rank =>
    Mux(pendingReadyAllocCount > rank.U,
      UIntToOH(pendingReadyAllocPrd(rank), nPhys), 0.U(nPhys.W))).reduce(_ | _)
  val pendingReadyWakeMask = (0 until numCdb).map(c =>
    Mux(pendingReadyWakeValid(c), UIntToOH(pendingReadyWakePrd(c), nPhys),
      0.U(nPhys.W))).reduce(_ | _)
  // Apply registered allocations to storage one edge later, while forwarding
  // their exact busy state to this cycle's reads. Late dispatch only captures
  // a narrow allocation count; its decoded mask never drives the ready flops.
  val ready = (readyStorage & ~pendingReadyAllocMask) | pendingReadyWakeMask
  // FreeList 指针/恢复边界由本级维护；连续窗口存储按端口数分 bank。
  val flSize = nPhys
  val freeList = Module(new FreeListStorage)
  val flHead  = RegInit(0.U(log2Ceil(flSize).W))
  val flTail  = RegInit((nPhys - nArch).U(log2Ceil(flSize + 1).W))
  private val flCountBits = p.freeListCountBits
  val flCount = RegInit(p.freeListCapacity.U(flCountBits.W))
  // 已提交映射对应的分配边界。它到 flHead 之间恰好是可在 flush 时回收的年轻分配。
  val flCommitHead = RegInit(0.U(log2Ceil(flSize).W))
  freeList.io.readHead := flHead
  freeList.io.writeTail := flTail(log2Ceil(flSize) - 1, 0)

  private val ratCheckpointWidth = nArch * p.physRegBits
  private val checkpointCount = p.numCheckpoints
  val branchRatCheckpoint = Mem(checkpointCount, UInt(ratCheckpointWidth.W))
  val branchFlHeadCheckpoint = Mem(checkpointCount,
    UInt(log2Ceil(flSize).W))
  for (memory <- Seq(branchRatCheckpoint, branchFlHeadCheckpoint)) {
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(
        memory.toTarget, "ram_style = \"distributed\"")
    })
  }
  val checkpointValidBits = RegInit(0.U(checkpointCount.W))
  val checkpointRobIdx = Reg(Vec(checkpointCount, UInt(p.robIdxBits.W)))
  val recoverCheckpointOH = VecInit((0 until checkpointCount).map(i =>
    checkpointValidBits(i) && checkpointRobIdx(i) === io.recover.bits)).asUInt
  val recoverCheckpointIdx = if (checkpointCount == 1) 0.U else
    PriorityEncoder(recoverCheckpointOH)
  val recoverRat = branchRatCheckpoint(recoverCheckpointIdx).asTypeOf(
    Vec(nArch, UInt(p.physRegBits.W)))
  val recoverFlHead = branchFlHeadCheckpoint(recoverCheckpointIdx)
  when(io.recover.valid) {
    assert(PopCount(recoverCheckpointOH) === 1.U)
  }

  val recoverApplyPending = RegInit(false.B)
  val recoverRebuildPending = RegInit(false.B)
  when(io.flush.valid) {
    recoverApplyPending := false.B
    recoverRebuildPending := false.B
  }.otherwise {
    recoverApplyPending := io.recover.valid
    recoverRebuildPending := recoverApplyPending
  }
  val renameRecoveryBusy = io.recover.valid || recoverApplyPending ||
    recoverRebuildPending

  val heldInputGroup = Reg(new DecodeGroup)
  val heldInputValid = RegInit(false.B)
  val heldInputSelect = RegInit(VecInit(Seq.fill(w)(false.B)))
  for (i <- 0 until w) {
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(
        heldInputSelect(i).toTarget, "EQUIVALENT_REGISTER_REMOVAL = \"NO\"")
    })
  }
  val inputReady = !heldInputValid &&
    !ratRestorePending && !renameRecoveryBusy
  io.in.ready := inputReady
  val inputEnqueue = io.in.valid && inputReady
  val groupValid = heldInputValid || io.in.valid
  val incomingGroup = WireDefault(io.in.bits)
  val incomingValid = VecInit(io.in.bits.uops.map(_.valid))
  val interruptSlot = PriorityEncoder(incomingValid)
  for (i <- 0 until w) {
    when(io.interruptPending && incomingValid(i) && interruptSlot === i.U) {
      val trap = incomingGroup.uops(i)
      trap.fuType := FU_LSU.U
      trap.aluOp := 0.U
      trap.divOp := 0.U
      trap.brOp := 0.U
      trap.memOp := 0.U
      trap.sysOp := SYS_TRAP.U
      trap.csrNum := 0.U
      trap.lrs1 := 0.U
      trap.lrs2 := 0.U
      trap.lrd := 0.U
      trap.useRs1 := false.B
      trap.useRs2 := false.B
      trap.writesReg := false.B
      trap.imm := 0.U
      trap.useImm := false.B
      trap.isBranch := false.B
      trap.isJump := false.B
      trap.isConditional := false.B
      trap.isCall := false.B
      trap.isRet := false.B
      trap.isLoad := false.B
      trap.isStore := false.B
      trap.isSystem := true.B
      trap.illegal := false.B
      trap.exception := ExceptionInfo.none
      trap.exception.valid := true.B
      trap.exception.ecode := ExceptionCode.INT.U
    }
  }
  val g = Wire(new DecodeGroup)
  for (i <- 0 until w) {
    g.uops(i) := Mux(heldInputSelect(i),
      heldInputGroup.uops(i), incomingGroup.uops(i))
  }
  val groupDequeue = Wire(Bool())

  // ---- Rename -> ROB/IQ 写入寄存边界 ----
  // Stage 1 完成 rename 和资源预约；Stage 2 将同一组已重命名 uop 同拍写入
  // ROB 与目标 IQ。寄存级支持同拍排空和重装，稳态吞吐率仍为每周期一组。
  // flush 会与 ROB 全局回收同拍清除此处尚未入队的预约项。
  val iqWriteBits = Reg(Vec(w, new RenamedUop))
  val iqWriteValid = RegInit(VecInit(Seq.fill(w)(false.B)))
  val iqWriteLdst = Reg(Vec(w, UInt(p.archRegBits.W)))
  val iqWritePrevPrd = Reg(Vec(w, UInt(p.physRegBits.W)))
  val iqWriteCheckpointIdx = Reg(UInt(checkpointIdxBits.W))
  val iqWriteSrc1RawCdbOH = RegInit(VecInit(Seq.fill(w)(0.U(numCdb.W))))
  val iqWriteSrc2RawCdbOH = RegInit(VecInit(Seq.fill(w)(0.U(numCdb.W))))
  val iqWriteAluSelect = RegInit(VecInit(Seq.fill(p.numAlu)(
    VecInit(Seq.fill(w)(0.U(w.W))))))
  val iqWriteBruSelect = RegInit(VecInit(Seq.fill(w)(0.U(w.W))))
  val iqWriteLsuSelect = RegInit(VecInit(Seq.fill(w)(0.U(w.W))))
  val iqWriteDivSelect = RegInit(VecInit(Seq.fill(p.numDiv)(
    VecInit(Seq.fill(w)(0.U(w.W))))))

  val cdbReadyValid = RegInit(VecInit(Seq.fill(numCdb)(false.B)))
  val cdbReadyPrd = Reg(Vec(numCdb, UInt(p.physRegBits.W)))
  val cdbReadyData = Reg(Vec(numCdb, UInt(p.xLen.W)))
  for (c <- 0 until numCdb) {
    cdbReadyPrd(c) := io.cdb(c).prd
    cdbReadyData(c) := io.cdb(c).data
  }
  when(io.flush.valid) {
    cdbReadyValid := VecInit(Seq.fill(numCdb)(false.B))
  }.otherwise {
    for (c <- 0 until numCdb) {
      cdbReadyValid(c) := io.cdb(c).valid && io.cdb(c).prd =/= 0.U
    }
  }

  def cdbWakes(pr: UInt): Bool = VecInit((0 until numCdb).map(c =>
    cdbReadyValid(c) && cdbReadyPrd(c) === pr && pr =/= 0.U)).asUInt.orR

  def rawCdbHits(pr: UInt): UInt = VecInit((0 until numCdb).map(c =>
    io.cdb(c).valid && io.cdb(c).prd === pr && pr =/= 0.U)).asUInt

  val iqWriteAny = iqWriteValid.asUInt.orR
  val iqWriteBranchVec = VecInit((0 until w).map(i =>
    iqWriteValid(i) && iqWriteBits(i).isBranch))
  val iqWriteHasBranch = iqWriteBranchVec.asUInt.orR
  val stagedCheckpointMask = Mux(iqWriteHasBranch,
    UIntToOH(iqWriteCheckpointIdx, checkpointCount), 0.U(checkpointCount.W))
  val checkpointFreeBits = ~(checkpointValidBits | stagedCheckpointMask)
  val checkpointCanAllocate = checkpointFreeBits.orR
  val checkpointFreeIdx = if (checkpointCount == 1) 0.U else
    PriorityEncoder(checkpointFreeBits)
  io.inputActive := groupValid || iqWriteAny
  val iqWriteAllReady = VecInit((0 until w).map(i =>
    !iqWriteValid(i) || io.dispatch(i).ready)).asUInt.andR
  val iqWriteStageReady = !iqWriteAny || iqWriteAllReady
  io.dispatchAluSelect := iqWriteAluSelect
  io.dispatchBruSelect := iqWriteBruSelect
  io.dispatchLsuSelect := iqWriteLsuSelect
  io.dispatchDivSelect := iqWriteDivSelect

  def speculativeMap(lreg: UInt): UInt = {
    val mapped = WireDefault(specRat(lreg))
    for (i <- 0 until w) {
      when(iqWriteValid(i) && iqWriteBits(i).writesReg &&
          iqWriteLdst(i) === lreg && lreg =/= 0.U) {
        mapped := iqWriteBits(i).prd
      }
    }
    mapped
  }

  for (i <- 0 until w) {
    val src1RawCdbValid = iqWriteSrc1RawCdbOH(i).orR
    val src2RawCdbValid = iqWriteSrc2RawCdbOH(i).orR
    io.dispatch(i).bits := iqWriteBits(i)
    io.dispatch(i).bits.robIdx := io.robAllocIdx(i)
    io.dispatch(i).bits.prs1Ready := iqWriteBits(i).prs1Ready ||
      src1RawCdbValid
    io.dispatch(i).bits.prs2Ready := iqWriteBits(i).prs2Ready ||
      src2RawCdbValid
    io.dispatchCapturedSrc1(i).valid :=
      src1RawCdbValid && (!useLsqCapacityMask.B || iqWriteValid(i))
    io.dispatchCapturedSrc1(i).bits := Mux1H(
      iqWriteSrc1RawCdbOH(i), cdbReadyData)
    io.dispatchCapturedSrc2(i).valid :=
      src2RawCdbValid && (!useLsqCapacityMask.B || iqWriteValid(i))
    io.dispatchCapturedSrc2(i).bits := Mux1H(
      iqWriteSrc2RawCdbOH(i), cdbReadyData)
    // 目标 IQ 在 flush 沿优先清空 valid；该拍写入的 payload 不可见。
    // 不在这里门控 valid，避免 flush 穿过 enq 排序逻辑进入宽 IQ payload。
    io.dispatch(i).valid := iqWriteValid(i)
    io.robAllocReq(i) := io.dispatch(i).fire && !renameRecoveryBusy
    val stagedRobEntry = Wire(new RobEntry)
    stagedRobEntry := 0.U.asTypeOf(new RobEntry)
    stagedRobEntry.busy := true.B
    stagedRobEntry.done := false.B
    stagedRobEntry.pc := iqWriteBits(i).pc
    stagedRobEntry.inst := iqWriteBits(i).inst
    stagedRobEntry.ldst := iqWriteLdst(i)
    stagedRobEntry.prd := iqWriteBits(i).prd
    stagedRobEntry.prevPrd := iqWritePrevPrd(i)
    stagedRobEntry.writesReg := iqWriteBits(i).writesReg
    stagedRobEntry.isLoad := iqWriteBits(i).isLoad
    stagedRobEntry.isStore := iqWriteBits(i).isStore
    stagedRobEntry.isSystem := iqWriteBits(i).isSystem
    stagedRobEntry.isBranch := iqWriteBits(i).isBranch
    stagedRobEntry.isConditional := iqWriteBits(i).isConditional
    stagedRobEntry.isBackward := iqWriteBits(i).isConditional &&
      iqWriteBits(i).imm(p.xLen - 1)
    stagedRobEntry.isCall := iqWriteBits(i).isCall
    stagedRobEntry.isRet := iqWriteBits(i).isRet
    stagedRobEntry.isIndirect := iqWriteBits(i).brOp === BR_JIRL.U &&
      !iqWriteBits(i).isRet
    stagedRobEntry.bhtIdx := iqWriteBits(i).predBhtIdx
    stagedRobEntry.predTaken := iqWriteBits(i).predTaken
    stagedRobEntry.predGhr := iqWriteBits(i).predGhr
    stagedRobEntry.predTageTable := iqWriteBits(i).predTageTable
    stagedRobEntry.predTageGhr := iqWriteBits(i).predTageGhr
    stagedRobEntry.predCtr := iqWriteBits(i).predCtr
    stagedRobEntry.exception := iqWriteBits(i).exception
    io.robAllocEntry(i) := stagedRobEntry
  }

  // 物理 IQ 空位尚未包含本寄存级下一拍要写入的预约项。Stage 1 必须先
  // 扣除这些 reservation，避免连续两拍对同一空槽重复授予 credit。
  val aluReserved = RegInit(VecInit(Seq.fill(p.numAlu)(0.U(dispatchCountBits.W))))
  val bruReserved = RegInit(0.U(dispatchCountBits.W))
  val lsuReserved = RegInit(0.U(dispatchCountBits.W))
  val lqReserved = RegInit(0.U(dispatchCountBits.W))
  val sqReserved = RegInit(0.U(dispatchCountBits.W))
  val divReserved = RegInit(VecInit(Seq.fill(p.numDiv)(
    0.U(dispatchCountBits.W))))
  io.dispatchAluCount := aluReserved
  io.dispatchBruCount := bruReserved
  io.dispatchLsuCount := lsuReserved
  io.dispatchLqCount.foreach(_ := lqReserved)
  io.dispatchSqCount.foreach(_ := sqReserved)
  if (p.enableDifftest) {
    assert(lqReserved === PopCount((0 until w).map(i => iqWriteValid(i) && iqWriteBits(i).isLoad)))
    assert(sqReserved === PopCount((0 until w).map(i => iqWriteValid(i) && iqWriteBits(i).isStore)))
  }
  io.dispatchDivCount := divReserved
  val aluIqCredit = Wire(Vec(p.numAlu, UInt(dispatchCountBits.W)))
  for (a <- 0 until p.numAlu) {
    val afterReserved = Mux(io.aluIqCapacity(a) >= aluReserved(a),
      io.aluIqCapacity(a) - aluReserved(a), 0.U)
    val writeBudget = p.aluIqParams(a).enqWidth
    aluIqCredit(a) := Mux(afterReserved >= writeBudget.U,
      writeBudget.U(dispatchCountBits.W),
      afterReserved(dispatchCountBits - 1, 0))
  }
  val bruIqCredit = Mux(io.bruIqCapacity >= p.bruIqParams.enqWidth.U,
    p.bruIqParams.enqWidth.U(dispatchCountBits.W), io.bruIqCapacity)
  val lsuIqCredit = io.lsuIqCapacity
  val divIqCredit = Wire(Vec(p.numDiv, UInt(dispatchCountBits.W)))
  for (d <- 0 until p.numDiv) {
    val afterReserved = Mux(io.divIqCapacity(d) >= divReserved(d),
      io.divIqCapacity(d) - divReserved(d), 0.U)
    val writeBudget = p.divIqParams(d).enqWidth
    divIqCredit(d) := Mux(afterReserved >= writeBudget.U,
      writeBudget.U(dispatchCountBits.W),
      afterReserved(dispatchCountBits - 1, 0))
  }

  // ---- 组内部分消费指针 ----
  // startSlot 指向当前组内第一个尚未分发的槽位。分支屏障（需求 4.2）要求分支之后
  // 的指令在分支结果计算出之前不得发射；因此含分支的组需分多拍消费，而不能把
  // 分支后的指令直接丢弃（否则 not-taken 分支的顺序后继指令会丢失，导致死循环）。
  val startSlot = RegInit(0.U(log2Ceil(w).W))

  // 系统指令从分发到提交期间形成全局屏障。它们很少执行，这一保守策略让
  // CSR/CACOP 保持精确状态，同时不把提交相关逻辑接入普通指令执行路径。
  val systemInFlight = RegInit(false.B)

  // ---- 候选窗口：从 startSlot 起，到第一个分支/系统指令（含）为止 ----
  // 有效槽（仅计 startSlot 及之后）
  val slotValid = VecInit((0 until w).map(i => g.uops(i).valid && (i.U >= startSlot)))
  val isBoundary = VecInit((0 until w).map(i =>
    slotValid(i) && (g.uops(i).isBranch || g.uops(i).isSystem)))
  val anyBoundary = isBoundary.asUInt.orR
  val firstBoundary = PriorityEncoder(isBoundary)
  // 候选掩码：屏障在体时暂停；否则最多分发到本组第一个边界指令。
  val candidateMask = VecInit((0 until w).map(i =>
    slotValid(i) && !systemInFlight && (!anyBoundary || (i.U <= firstBoundary))
  ))

  val candidateIsAlu = VecInit((0 until w).map(i =>
    candidateMask(i) && g.uops(i).fuType === FU_ALU.U))
  val candidateIsBru = VecInit((0 until w).map(i =>
    candidateMask(i) && g.uops(i).fuType === FU_BRU.U))
  val candidateIsLsu = VecInit((0 until w).map(i =>
    candidateMask(i) && g.uops(i).fuType === FU_LSU.U))
  val candidateIsDiv = VecInit((0 until w).map(i =>
    candidateMask(i) && g.uops(i).fuType === FU_DIV.U))
  val candidateIsLoad = VecInit((0 until w).map(i =>
    candidateIsLsu(i) && g.uops(i).isLoad))
  val candidateIsStore = VecInit((0 until w).map(i =>
    candidateIsLsu(i) && g.uops(i).isStore))
  val aluSteerPtr = RegInit(0.U(aluIdxBits.W))
  val divSteerPtr = RegInit(0.U(divIdxBits.W))

  val plannedAluTarget = Wire(Vec(w, UInt(aluIdxBits.W)))
  if (p.numAlu == 1) {
    for (i <- 0 until w) { plannedAluTarget(i) := 0.U }
  } else if (p.numAlu == 2) {
    val lane1Fuller = aluIqCredit(1) > aluIqCredit(0)
    val creditDifference = Mux(lane1Fuller,
      aluIqCredit(1) - aluIqCredit(0),
      aluIqCredit(0) - aluIqCredit(1))
    val fullerLane = lane1Fuller
    val equalStart = aluSteerPtr(0)
    val aluRank = Wire(Vec(w, UInt(dispatchCountBits.W)))
    for (i <- 0 until w) {
      aluRank(i) := (if (i == 0) 0.U else
        PopCount(candidateIsAlu.take(i)))
      val alternatingRank = aluRank(i) - creditDifference
      val alternatingStart = Mux(creditDifference === 0.U,
        equalStart, !fullerLane)
      val alternatingLane = alternatingStart ^ alternatingRank(0)
      plannedAluTarget(i) := Mux(aluRank(i) < creditDifference,
        fullerLane, alternatingLane).asUInt
    }
  } else {
    val plannedCredit = Wire(Vec(w + 1,
      Vec(p.numAlu, UInt(dispatchCountBits.W))))
    val plannedPtr = Wire(Vec(w + 1, UInt(aluIdxBits.W)))
    plannedCredit(0) := aluIqCredit
    plannedPtr(0) := aluSteerPtr
    for (i <- 0 until w) {
      val maxCredit = plannedCredit(i).reduce((x, y) => Mux(x >= y, x, y))
      val maxHit = VecInit((0 until p.numAlu).map(a =>
        plannedCredit(i)(a) === maxCredit))
      val winnerForStart = VecInit((0 until p.numAlu).map { start =>
        val cyclicOrder = (0 until p.numAlu).map(k => (start + k) % p.numAlu)
        MuxCase(start.U(aluIdxBits.W), cyclicOrder.map(a =>
          maxHit(a) -> a.U(aluIdxBits.W)))
      })
      val winner = winnerForStart(plannedPtr(i))
      plannedAluTarget(i) := winner
      for (a <- 0 until p.numAlu) {
        val decremented = Mux(plannedCredit(i)(a) === 0.U, 0.U,
          plannedCredit(i)(a) - 1.U)
        plannedCredit(i + 1)(a) := Mux(candidateIsAlu(i) && winner === a.U,
          decremented, plannedCredit(i)(a))
      }
      val laneAfterWinner = Mux(winner === (p.numAlu - 1).U,
        0.U, winner + 1.U)
      plannedPtr(i + 1) := Mux(candidateIsAlu(i), laneAfterWinner, plannedPtr(i))
    }
  }
  val aluTarget = Wire(Vec(w, UInt(aluIdxBits.W)))
  val divTarget = Wire(Vec(w, UInt(divIdxBits.W)))
  for (i <- 0 until w) {
    val divRank = if (i == 0) 0.U(dispatchCountBits.W) else
      PopCount(candidateIsDiv.take(i))
    if (p.numDiv == 1) {
      divTarget(i) := 0.U
    } else {
      divTarget(i) := ((divSteerPtr + divRank) % p.numDiv.U).asUInt
    }
  }

  // 本拍最终被 IQ 接受的有序前缀。它在下方结合逐槽资源和 IQ 容量检查生成。
  val dispatchMask = Wire(Vec(w, Bool()))

  // 真实接受的目的寄存器只用于 RAT/FreeList 等架构状态更新。候选版本
  // 独立于 IQ capacity，用于预计算受 iqWriteValid/robAllocReq 保护的 payload。
  val needDest = VecInit((0 until w).map(i => {
    val u = g.uops(i)
    dispatchMask(i) && u.writesReg && (u.lrd =/= 0.U)
  }))

  // 候选窗口内每个槽之前需要的物理寄存器数，以及包含当前槽时的累计需求。
  // 前者用于选择 FreeList 表项，后者用于逐槽容量判断。
  val candidateNeedDest = VecInit((0 until w).map(i => {
    val u = g.uops(i)
    candidateMask(i) && u.writesReg && (u.lrd =/= 0.U)
  }))
  val destPrefix = Wire(Vec(w, UInt(p.issueCountBits.W)))
  val destNeedThrough = Wire(Vec(w, UInt(p.issueCountBits.W)))
  for (i <- 0 until w) {
    destPrefix(i) := (if (i == 0) 0.U else PopCount(candidateNeedDest.take(i)))
    destNeedThrough(i) := PopCount(candidateNeedDest.take(i + 1))
  }
  val robNeedThrough = Wire(Vec(w, UInt(p.issueCountBits.W)))
  val checkpointNeedThrough = Wire(Vec(w, Bool()))
  for (i <- 0 until w) {
    robNeedThrough(i) := PopCount(candidateMask.take(i + 1))
    checkpointNeedThrough(i) := VecInit((0 to i).map(j =>
      candidateMask(j) && g.uops(j).isBranch)).asUInt.orR
  }

  // 本拍实际消耗的资源数量
  val needDestCount = PopCount(needDest)
  val dispatchCount = PopCount(dispatchMask)

  // ---- 组内 prd 预分配（顺序从 FreeList 取）----
  // destPrefix(i) = 第 i 槽若需要目的寄存器，它是本候选窗口中第几个目的（0-based）。
  // 关键路径优化：numPhysRegs 为 2 的幂，用位掩码替代取模运算，消除除法器组合路径。
  require(isPow2(nPhys), "numPhysRegs 必须为 2 的幂以使用位掩码 wrap")
  // 先按 flHead 并行读取固定窗口，使译码产生的 destPrefix 只控制一个 w:1 小 Mux，
  // 而不再进入 64 项 freeList 的动态读地址网络。
  val flHeadWindow = freeList.io.readWindow
  val newPrd = Wire(Vec(w, UInt(p.physRegBits.W)))
  for (i <- 0 until w) {
    newPrd(i) := flHeadWindow(destPrefix(i)(log2Ceil(w) - 1, 0))
  }

  // ---- 组内相关性：为每槽计算 prs1/prs2（含 RAW 旁路）与 prevPrd（含 WAW）----
  val prs1 = Wire(Vec(w, UInt(p.physRegBits.W)))
  val prs2 = Wire(Vec(w, UInt(p.physRegBits.W)))
  val prd  = Wire(Vec(w, UInt(p.physRegBits.W)))
  val prevPrd = Wire(Vec(w, UInt(p.physRegBits.W)))
  val prs1Rdy = Wire(Vec(w, Bool()))
  val prs2Rdy = Wire(Vec(w, Bool()))

  def readyOf(pr: UInt): Bool = {
    val justAllocated = (0 until w).map(rank =>
      pendingReadyAllocCount > rank.U && pendingReadyAllocPrd(rank) === pr).reduce(_ || _)
    val pendingWake = (0 until numCdb).map(c =>
      pendingReadyWakeValid(c) && pendingReadyWakePrd(c) === pr).reduce(_ || _)
    pr === 0.U || (readyStorage(pr) && !justAllocated) || pendingWake || cdbWakes(pr)
  }

  for (i <- 0 until w) {
    val u = g.uops(i)
    // 源 1：从最年长到 i-1 中查找最近的写同一架构寄存器者
    def bypass(lsrc: UInt, ratValue: UInt): (UInt, Bool) = {
      // 默认取 RAT
      val res = WireDefault(ratValue)
      val fromGroup = WireDefault(false.B)
      for (j <- 0 until i) {
        when(candidateNeedDest(j) && (g.uops(j).lrd === lsrc) && (lsrc =/= 0.U)) {
          res := newPrd(j)
          fromGroup := true.B
        }
      }
      (res, fromGroup)
    }
    val ratP1 = speculativeMap(u.lrs1)
    val ratP2 = speculativeMap(u.lrs2)
    val (p1, fg1) = bypass(u.lrs1, ratP1)
    val (p2, fg2) = bypass(u.lrs2, ratP2)
    prs1(i) := p1
    prs2(i) := p2
    prs1Rdy(i) := !u.useRs1 || (!fg1 && readyOf(ratP1))
    prs2Rdy(i) := !u.useRs2 || (!fg2 && readyOf(ratP2))

    // 目的物理寄存器
    prd(i) := Mux(candidateNeedDest(i), newPrd(i), 0.U)
    // prevPrd：组内上一个写 lrd 的槽的 prd；否则 RAT
    val pv = WireDefault(speculativeMap(u.lrd))
    for (j <- 0 until i) {
      when(candidateNeedDest(j) && (g.uops(j).lrd === u.lrd) && (u.lrd =/= 0.U)) {
        pv := newPrd(j)
      }
    }
    prevPrd(i) := pv
  }

  for (i <- 0 until w) {
    aluTarget(i) := plannedAluTarget(i)
  }

  // ---- 逐槽资源检查：形成严格有序的最长可接受前缀 ----
  // 目标 IQ 的已用容量按候选前缀直接计算，不再依赖更老槽的 dispatch.valid。
  val targetReady = Wire(Vec(w, Bool()))
  for (i <- 0 until w) {
    val aluCapacity = MuxLookup(aluTarget(i), 0.U)(
      (0 until p.numAlu).map(a => a.U -> aluIqCredit(a)))
    val aluUsedBefore = if (i == 0) 0.U else PopCount((0 until i).map(j =>
      candidateIsAlu(j) && aluTarget(j) === aluTarget(i)))
    val aluReady = aluUsedBefore < aluCapacity
    val bruUsedBefore = if (i == 0) 0.U else PopCount(candidateIsBru.take(i))
    val lsuUsedBefore = if (i == 0) 0.U else PopCount(candidateIsLsu.take(i))
    val lqUsedBefore = if (i == 0) 0.U else PopCount(candidateIsLoad.take(i))
    val sqUsedBefore = if (i == 0) 0.U else PopCount(candidateIsStore.take(i))
    val lqReady = io.lqCapacityMask.map(mask => Mux1H((0 to i).map(rank =>
      (lqUsedBefore === rank.U) -> mask(rank)))).getOrElse(lqUsedBefore < io.lqCapacity)
    val sqReady = io.sqCapacityMask.map(mask => Mux1H((0 to i).map(rank =>
      (sqUsedBefore === rank.U) -> mask(rank)))).getOrElse(sqUsedBefore < io.sqCapacity)
    val memoryQueueReady = MuxCase(true.B, Seq(
      candidateIsLoad(i) -> lqReady,
      candidateIsStore(i) -> sqReady
    ))
    targetReady(i) := MuxCase(true.B, Seq(
      candidateIsAlu(i) -> aluReady,
      candidateIsBru(i) -> (bruUsedBefore < bruIqCredit),
      candidateIsLsu(i) ->
        ((lsuUsedBefore < lsuIqCredit) && memoryQueueReady),
      candidateIsDiv(i) -> {
        val divUsedBefore = if (i == 0) 0.U else PopCount(
          (0 until i).map(j => candidateIsDiv(j) &&
            divTarget(j) === divTarget(i)))
        divUsedBefore < MuxLookup(divTarget(i), 0.U)(
          (0 until p.numDiv).map(d => d.U -> divIqCredit(d)))
      }
    ))
  }

  // 每槽是否可保持前缀开放只依赖候选数据和寄存态资源。并行前缀 AND
  // 取代逐槽 valid/ready 反馈，保持“遇到第一条阻塞即停止”的精确语义。
  val slotAcceptable = Wire(Vec(w, Bool()))
  val dispatchEnabled = groupValid && !ratRestorePending && !renameRecoveryBusy &&
    iqWriteStageReady
  for (i <- 0 until w) {
    val haveFreeThrough = flCount >= destNeedThrough(i)
    val haveRobThrough = io.robCanAlloc >= robNeedThrough(i)
    val haveCheckpointThrough = !checkpointNeedThrough(i) ||
      checkpointCanAllocate
    slotAcceptable(i) := !candidateMask(i) ||
      (haveFreeThrough && haveRobThrough && haveCheckpointThrough &&
        targetReady(i))
    val prefixAcceptable = VecInit(slotAcceptable.take(i + 1)).asUInt.andR
    dispatchMask(i) := dispatchEnabled && candidateMask(i) && prefixAcceptable
  }

  val anyDispatch = dispatchMask.asUInt.orR
  val nextAluReserved = VecInit((0 until p.numAlu).map(a =>
    PopCount((0 until w).map(i => dispatchMask(i) &&
      g.uops(i).fuType === FU_ALU.U && aluTarget(i) === a.U))))
  val nextBruReserved = PopCount((0 until w).map(i =>
    dispatchMask(i) && g.uops(i).fuType === FU_BRU.U))
  val nextLsuReserved = PopCount((0 until w).map(i =>
    dispatchMask(i) && g.uops(i).fuType === FU_LSU.U))
  val nextLqReserved = PopCount((0 until w).map(i => dispatchMask(i) && g.uops(i).isLoad))
  val nextSqReserved = PopCount((0 until w).map(i => dispatchMask(i) && g.uops(i).isStore))
  val nextDivReserved = VecInit((0 until p.numDiv).map(d =>
    PopCount((0 until w).map(i => dispatchMask(i) &&
      candidateIsDiv(i) && divTarget(i) === d.U))))
  for (a <- 0 until p.numAlu) {
    assert(nextAluReserved(a) <= p.aluIqParams(a).enqWidth.U)
  }
  assert(nextBruReserved <= p.bruIqParams.enqWidth.U)
  for (d <- 0 until p.numDiv) {
    assert(nextDivReserved(d) <= p.divIqParams(d).enqWidth.U)
  }
  val nextTargetRank = Wire(Vec(w, UInt(dispatchCountBits.W)))
  for (i <- 0 until w) {
    val olderSameTarget = (0 until i).map { j =>
      val sameAlu = candidateIsAlu(i) && candidateIsAlu(j) &&
        aluTarget(j) === aluTarget(i)
      val sameBru = candidateIsBru(i) && candidateIsBru(j)
      val sameLsu = candidateIsLsu(i) && candidateIsLsu(j)
      val sameDiv = candidateIsDiv(i) && candidateIsDiv(j) &&
        divTarget(i) === divTarget(j)
      dispatchMask(j) && (sameAlu || sameBru || sameLsu || sameDiv)
    }
    nextTargetRank(i) := (if (i == 0) 0.U else PopCount(olderSameTarget))
  }
  val nextAluSelect = Wire(Vec(p.numAlu, Vec(w, UInt(w.W))))
  val nextBruSelect = Wire(Vec(w, UInt(w.W)))
  val nextLsuSelect = Wire(Vec(w, UInt(w.W)))
  val nextDivSelect = Wire(Vec(p.numDiv, Vec(w, UInt(w.W))))
  for (rank <- 0 until w) {
    for (a <- 0 until p.numAlu) {
      nextAluSelect(a)(rank) := VecInit((0 until w).map(i =>
        dispatchMask(i) && candidateIsAlu(i) && aluTarget(i) === a.U &&
          nextTargetRank(i) === rank.U)).asUInt
    }
    nextBruSelect(rank) := VecInit((0 until w).map(i =>
      dispatchMask(i) && candidateIsBru(i) &&
        nextTargetRank(i) === rank.U)).asUInt
    nextLsuSelect(rank) := VecInit((0 until w).map(i =>
      dispatchMask(i) && candidateIsLsu(i) &&
        nextTargetRank(i) === rank.U)).asUInt
    for (d <- 0 until p.numDiv) {
      nextDivSelect(d)(rank) := VecInit((0 until w).map(i =>
        dispatchMask(i) && candidateIsDiv(i) && divTarget(i) === d.U &&
          nextTargetRank(i) === rank.U)).asUInt
    }
  }
  // 只有当前组所有剩余有效槽均已分发时才让 InstructionBuffer 出队；空组直接丢弃。
  val hasRemaining = slotValid.asUInt.orR
  val coversTail = (0 until w).map(i => !slotValid(i) || dispatchMask(i)).reduce(_ && _)
  groupDequeue := groupValid && !ratRestorePending && !renameRecoveryBusy &&
    (!hasRemaining || (anyDispatch && coversTail))

  // ---- Stage 1：rename 结果 ----
  val renamedBits = Wire(Vec(w, new RenamedUop))
  for (i <- 0 until w) {
    val u = g.uops(i)
    val d = Wire(new RenamedUop)
    d.pc := u.pc
    d.inst := u.inst
    d.fuType := u.fuType
    d.aluOp := u.aluOp
    d.divOp := u.divOp
    d.brOp := u.brOp
    d.memOp := u.memOp
    d.sysOp := u.sysOp
    d.csrNum := u.csrNum
    d.prs1 := prs1(i)
    d.prs2 := prs2(i)
    d.prd := prd(i)
    d.prs1Ready := prs1Rdy(i)
    d.prs2Ready := prs2Rdy(i)
    d.useRs1 := u.useRs1
    d.useRs2 := u.useRs2
    d.writesReg := candidateNeedDest(i)
    d.imm := u.imm
    d.useImm := u.useImm
    d.isBranch := u.isBranch
    d.isJump := u.isJump
    d.isConditional := u.isConditional
    d.isCall := u.isCall
    d.isRet := u.isRet
    d.isLoad := u.isLoad
    d.isStore := u.isStore
    d.isSystem := u.isSystem
    d.exception := u.exception
    d.predTaken := u.predTaken
    d.predTarget := u.predTarget
    d.predBhtIdx := u.predBhtIdx
    d.predGhr := u.predGhr
    d.predTageTable := u.predTageTable
    d.predTageGhr := u.predTageGhr
    d.predCtr := u.predCtr
    d.robIdx := 0.U
    d.lsqIdx := 0.U
    renamedBits(i) := d
  }

  // ============ 状态更新 ============
  val fire = anyDispatch
  val doFlush = io.flush.valid

  when(doFlush || io.recover.valid) {
    heldInputValid := false.B
    heldInputSelect := VecInit(Seq.fill(w)(false.B))
  }.otherwise {
    when(inputEnqueue) {
      heldInputGroup := incomingGroup
    }
    switch(Cat(inputEnqueue, groupDequeue)) {
      is("b10".U) {
        heldInputValid := true.B
        heldInputSelect := VecInit(Seq.fill(w)(true.B))
      }
      is("b01".U) {
        heldInputValid := false.B
        heldInputSelect := VecInit(Seq.fill(w)(false.B))
      }
    }
  }

  var aluSteerPtrAfterDispatch = aluSteerPtr
  for (i <- 0 until w) {
    val nextLane = Mux(aluTarget(i) === (p.numAlu - 1).U,
      0.U, aluTarget(i) + 1.U)
    aluSteerPtrAfterDispatch = Mux(dispatchMask(i) && candidateIsAlu(i),
      nextLane, aluSteerPtrAfterDispatch)
  }
  val dispatchedDiv = VecInit((0 until w).map(i =>
    dispatchMask(i) && candidateIsDiv(i)))
  val lastDiv = PriorityEncoder(dispatchedDiv)
  when(doFlush || io.recover.valid) {
    aluSteerPtr := 0.U
    divSteerPtr := 0.U
  }.elsewhen(anyDispatch) {
    aluSteerPtr := aluSteerPtrAfterDispatch
    when(dispatchedDiv.asUInt.orR) {
      divSteerPtr := Mux(divTarget(lastDiv) === (p.numDiv - 1).U,
        0.U, divTarget(lastDiv) + 1.U)
    }
  }

  // flush 沿的 archRatNext 会在本拍写入 archRat。恢复拍从该寄存边界复制，
  // 并由上方门控阻止 stale specRat 被 rename 观察。
  when(doFlush) {
    ratRestorePending := true.B
  }.elsewhen(ratRestorePending) {
    ratRestorePending := false.B
  }

  val checkpointMap = VecInit((0 until nArch).map(a => speculativeMap(a.U)))
  val checkpointBranchVec = VecInit((0 until w).map(i =>
    io.robAllocReq(i) && iqWriteBits(i).isBranch))
  val checkpointBranchValid = checkpointBranchVec.asUInt.orR
  val checkpointBranchSlot = PriorityEncoder(checkpointBranchVec)
  val checkpointAllocMask = Mux(checkpointBranchValid,
    UIntToOH(iqWriteCheckpointIdx, checkpointCount), 0.U(checkpointCount.W))
  when(checkpointBranchValid) {
    if (checkpointCount == 1) {
      assert(!checkpointValidBits(0))
      checkpointRobIdx(0) := io.robAllocIdx(checkpointBranchSlot)
      branchRatCheckpoint.write(0.U, checkpointMap.asUInt)
      branchFlHeadCheckpoint.write(0.U, flHead)
    } else {
      assert(!checkpointValidBits(iqWriteCheckpointIdx))
      checkpointRobIdx(iqWriteCheckpointIdx) :=
        io.robAllocIdx(checkpointBranchSlot)
      branchRatCheckpoint.write(iqWriteCheckpointIdx, checkpointMap.asUInt)
      branchFlHeadCheckpoint.write(iqWriteCheckpointIdx, flHead)
    }
  }
  val branchResolvedValid = io.cdb(p.bruCdbLane).valid
  val branchResolvedIdx = io.cdb(p.bruCdbLane).robIdx
  val branchResolvedMask = VecInit((0 until checkpointCount).map(i =>
    checkpointValidBits(i) && checkpointRobIdx(i) === branchResolvedIdx)).asUInt
  val recoverDiscardMask = VecInit((0 until checkpointCount).map(i =>
    checkpointValidBits(i) && io.recover.valid &&
      (checkpointRobIdx(i) === io.recover.bits ||
        RobOrder.isYounger(checkpointRobIdx(i), io.recover.bits,
          io.robHeadIdx)))).asUInt
  when(branchResolvedValid && !io.flush.valid) {
    assert(PopCount(branchResolvedMask) === 1.U)
  }
  val checkpointAfterUpdates =
    (checkpointValidBits | checkpointAllocMask) &
      ~Mux(branchResolvedValid, branchResolvedMask, 0.U) &
      ~recoverDiscardMask
  checkpointValidBits := Mux(io.flush.valid, 0.U, checkpointAfterUpdates)

  // Stage 2 状态：正常情况下每拍写入 IQ 并由下一组替换；如果下游停顿，
  // 保留预约项并累计期间出现的 CDB 唤醒，防止入 IQ 时丢失单拍广播。
  when(iqWriteStageReady) {
    iqWriteCheckpointIdx := checkpointFreeIdx
    iqWriteAluSelect := nextAluSelect
    iqWriteBruSelect := nextBruSelect
    iqWriteLsuSelect := nextLsuSelect
    iqWriteDivSelect := nextDivSelect
    for (i <- 0 until w) {
      iqWriteBits(i) := renamedBits(i)
      iqWriteLdst(i) := g.uops(i).lrd
      iqWritePrevPrd(i) := prevPrd(i)
    }
  }

  when(doFlush || io.recover.valid) {
    for (i <- 0 until w) { iqWriteValid(i) := false.B }
    for (a <- 0 until p.numAlu) { aluReserved(a) := 0.U }
    bruReserved := 0.U
    lsuReserved := 0.U
    lqReserved := 0.U
    sqReserved := 0.U
    divReserved := VecInit(Seq.fill(p.numDiv)(0.U(dispatchCountBits.W)))
  }.elsewhen(iqWriteStageReady) {
    aluReserved := nextAluReserved
    bruReserved := nextBruReserved
    lsuReserved := nextLsuReserved
    lqReserved := nextLqReserved
    sqReserved := nextSqReserved
    divReserved := nextDivReserved
    for (i <- 0 until w) {
      iqWriteValid(i) := dispatchMask(i)
      val src1RawHits = rawCdbHits(renamedBits(i).prs1)
      val src2RawHits = rawCdbHits(renamedBits(i).prs2)
      val captureCandidate = if (useLsqCapacityMask) true.B else dispatchMask(i)
      iqWriteSrc1RawCdbOH(i) := Mux(
        captureCandidate && renamedBits(i).useRs1, src1RawHits, 0.U)
      iqWriteSrc2RawCdbOH(i) := Mux(
        captureCandidate && renamedBits(i).useRs2, src2RawHits, 0.U)
      assert(PopCount(src1RawHits) <= 1.U)
      assert(PopCount(src2RawHits) <= 1.U)
    }
  }.otherwise {
    assert(!iqWriteAny)
  }

  when(renameRecoveryBusy) {
    assert(!dispatchMask.asUInt.orR)
    assert(!io.robAllocReq.asUInt.orR)
  }

  val dispatchesSystem = VecInit((0 until w).map(i =>
    dispatchMask(i) && g.uops(i).isSystem)).asUInt.orR

  when(doFlush || io.recover.valid) {
    systemInFlight := false.B
  }.elsewhen(dispatchesSystem) {
    systemInFlight := true.B
  }

  // ---- startSlot 更新（组内部分消费指针）----
  val lastDispatched = WireDefault(startSlot)
  for (i <- 0 until w) {
    when(dispatchMask(i)) { lastDispatched := i.U }
  }
  when(io.recover.valid || ratRestorePending) {
    startSlot := 0.U
  }.elsewhen(groupDequeue) {
    startSlot := 0.U
  }.elsewhen(fire) {
    startSlot := lastDispatched + 1.U
  }

  // ---- 提交事件 ----
  val commitPush = VecInit((0 until w).map(i =>
    io.commit(i).valid && io.commit(i).writesReg && io.commit(i).prevPrd =/= 0.U))
  val archRatNext = Wire(Vec(nArch, UInt(p.physRegBits.W)))
  for (a <- 0 until nArch) { archRatNext(a) := archRat(a) }
  for (i <- 0 until w) {
    when(io.commit(i).valid && io.commit(i).writesReg) {
      archRatNext(io.commit(i).ldst) := io.commit(i).prd
    }
  }

  // ---- specRat 更新 ----
  when(io.recover.valid) {
    for (a <- 0 until nArch) { specRat(a) := recoverRat(a) }
  }.elsewhen(ratRestorePending) {
    for (a <- 0 until nArch) { specRat(a) := archRatNext(a) }
  }.otherwise {
    for (i <- 0 until w) {
      when(iqWriteValid(i) && iqWriteBits(i).writesReg) {
        specRat(iqWriteLdst(i)) := iqWriteBits(i).prd
      }
    }
  }

  // ---- archRat 更新（提交，多路，顺序覆盖）----
  for (a <- 0 until nArch) { archRat(a) := archRatNext(a) }

  // ---- ready 表 ----
  val allocClearMask = (0 until w).map(i =>
    Mux(needDest(i), UIntToOH(newPrd(i), nPhys), 0.U(nPhys.W))).reduce(_ | _)
  val cdbReadyMask = (0 until numCdb).map(c =>
    Mux(cdbReadyValid(c), UIntToOH(cdbReadyPrd(c), nPhys),
      0.U(nPhys.W))).reduce(_ | _)
  readyStorage := Mux(ratRestorePending, allPhysReady, ready) | cdbReadyMask
  pendingReadyAllocCount := Mux(ratRestorePending, 0.U, needDestCount)
  pendingReadyAllocPrd := flHeadWindow
  pendingReadyWakeValid := cdbReadyValid
  pendingReadyWakePrd := cdbReadyPrd
  val registeredAllocWitness = RegNext(Mux(ratRestorePending,
    0.U(nPhys.W), allocClearMask), 0.U(nPhys.W))
  assert(pendingReadyAllocMask === registeredAllocWitness)
  val readyWitness = RegInit(allPhysReady)
  readyWitness := Mux(ratRestorePending, allPhysReady,
    readyWitness & ~allocClearMask) | cdbReadyMask
  assert(ready === readyWitness, "deferred ready allocation changed visible readiness")

  // ---- FreeList 更新 ----
  // 每次提交一个目的寄存器，窗口前端丢弃其新 prd、尾端加入其 prevPrd，
  // 因而 flCommitHead 到 flTail 始终保存完整的非架构物理寄存器集合。
  val availablePushCount = PopCount(commitPush)

  // 固定尾部窗口只依赖寄存态 flTail；已寄存的稀疏 commit 先按 rank 压紧，
  // 再由第 k 项固定写 flTail+k。
  val flTailWindow = VecInit((0 to w).map(k =>
    (flTail +& k.U) & (flSize - 1).U))
  val stagedPrevPrdByRank = Wire(Vec(w, UInt(p.physRegBits.W)))
  for (k <- 0 until w) {
    val rankHit = VecInit((0 until w).map(i => {
      val pushRank = if (i == 0) 0.U else PopCount(commitPush.take(i))
      commitPush(i) && pushRank === k.U
    }))
    stagedPrevPrdByRank(k) := Mux1H(rankHit, io.commit.map(_.prevPrd))
  }
  freeList.io.writeCount := availablePushCount
  freeList.io.writeData := stagedPrevPrdByRank
  val flCommitHeadAfterPush =
    (flCommitHead +& availablePushCount) & (flSize - 1).U
  val flTailAfterPush = flTailWindow(availablePushCount)

  // Queue capacity can arrive late through same-cycle retirement. Prepare the
  // state update for every possible slot prefix before that capacity selects
  // the accepted prefix. Count destinations in the candidate prefix: valid
  // slots can be sparse, and accepted instructions need not write a register.
  val acceptedPrefixOH = VecInit((0 to w).map { k =>
    if (k == 0) {
      !dispatchEnabled || !slotAcceptable(0)
    } else {
      val earlierAcceptable = VecInit(slotAcceptable.take(k)).asUInt.andR
      val stopsHere = if (k == w) true.B else !slotAcceptable(k)
      dispatchEnabled && earlierAcceptable && stopsHere
    }
  })
  val countAfterPush = flCount + availablePushCount
  val headByPrefix = (0 to w).map { k =>
    val destinations = if (k == 0) 0.U else destNeedThrough(k - 1)
    (flHead +& destinations) & (flSize - 1).U
  }
  val countByPrefix = (0 to w).map { k =>
    val destinations = if (k == 0) 0.U else destNeedThrough(k - 1)
    countAfterPush - destinations
  }
  val flHeadAfterDispatch = Wire(UInt(flHead.getWidth.W))
  val flCountAfterDispatch = Wire(UInt(flCountBits.W))
  flHeadAfterDispatch := Mux1H(acceptedPrefixOH, headByPrefix)
  flCountAfterDispatch := Mux1H(acceptedPrefixOH, countByPrefix)
  assert(PopCount(acceptedPrefixOH) === 1.U)
  assert(flHeadAfterDispatch === ((flHead +& needDestCount) & (flSize - 1).U))
  assert(flCountAfterDispatch === (flCount - needDestCount + availablePushCount))

  val recoverFreeCount = (flTailAfterPush - recoverFlHead)(log2Ceil(flSize) - 1, 0)
  when(io.recover.valid) {
    flHead := recoverFlHead
    flCount := recoverFreeCount
  }.elsewhen(ratRestorePending) {
    flHead := flCommitHeadAfterPush
    flCount := (nPhys - nArch).U
  }.otherwise {
    flHead := flHeadAfterDispatch
    flCount := flCountAfterDispatch
  }
  flCommitHead := flCommitHeadAfterPush
  flTail := flTailAfterPush
}
