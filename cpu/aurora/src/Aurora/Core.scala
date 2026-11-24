package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation
import UopConst._


class Core(implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val axi         = new Axi4MasterIO
    val hardwareInterrupts = Input(UInt(8.W))
    val ipiInterrupt = Input(Bool())
    val brTotal     = Output(UInt(32.W))
    val brMispredict = Output(UInt(32.W))
    val instCommit  = Output(UInt(32.W))
    val perf        = Output(new PerfEvents)
    val difftestCommit = Output(Vec(p.coreWidth, new DifftestCommit))
    val difftestStore = Output(Vec(p.coreWidth, new DifftestMemoryEvent))
    val difftestLoad = Output(Vec(p.coreWidth, new DifftestMemoryEvent))
    val difftestTrap = Output(new DifftestTrap)
    val difftestCsr = Output(new DifftestCsrState)
    val difftestTimer = Output(UInt(64.W))
    val difftestTlbfillIndex = Output(UInt(p.tlbIndexBits.W))
    val orderedStoreDebug = Output(new OrderedStoreDebug)
  })
  val w = p.coreWidth
  val numAlu = p.numAlu
  val numCdb = p.numCdb
  val bruCdbLane = p.bruCdbLane
  val lsuCdbLane = p.lsuCdbLane
  private val dispatchCountBits = p.issueCountBits
  private val pendingCapacityBits = log2Ceil(2 * p.coreWidth + 1)
  private val robCountBits = p.robCountBits
  // ---- 子模块 ----
  val ifu = Module(new IFU(registerTranslation = true))
  val idu = Module(new IDU)
  val instructionBuffer  = Module(new InstructionBuffer)
  val isu = Module(new ISU(numCdb, useLsqCapacityMask = true))
  val rob = Module(new ROB(numCdb))
  val prf = Module(new PRF(numRead = p.numPrfReadPorts, numWrite = numCdb,
    numDebugRead = w))
  val aluIq = p.aluIqParams.map(q => Module(new GenIQ(q, prepareMultiply = true,
    useRegisteredLoadKind = true, prepareAluControl = true, prequalifiedLoadCdb = true, splitLsuData = true)))
  val bruIq = Module(new GenIQ(p.bruIqParams, useRegisteredLoadKind = true))
  val lsuIq = Module(new MemIQ(p.lsuIqDepth, numCdb, numEnq = w,
    captureCdbBeforeIssue = true))
  val divIq = p.divIqParams.map(q => Module(new GenIQ(q.copy(cdbForward = Seq.empty),
    useRegisteredLoadKind = true)))

  // Wake timing remains owned by the execution lanes. The queue statically
  // subscribes only to the producer/result/source routes it actually uses.
  def connectIqFast(iq: GenIQ): Unit = {
    for ((route, f) <- iq.fastRoutes.zipWithIndex) {
      val lane = route.producer.index
      val isMultiply = route.kind == FastResultKind.Multiply
      val producer = aluIq(lane).io
      iq.io.fastWake(f).valid := (if (isMultiply) producer.multiplyAluWake
                                 else producer.simpleAluWake)
      iq.io.fastWake(f).bits := producer.issue.bits.uop.prd
      iq.io.fastResult(f) := (if (isMultiply) alus(lane).io.earlyMultiplyResult
                             else alus(lane).io.earlySimpleResult)
    }
  }
  val alus = Seq.fill(numAlu)(Module(new ALU(
    preparedMultiply = true, preparedControl = true, localCdbData = true,
    registerMultiplyPartials = !p.allowAluMultiplyForward)))
  val bru   = Module(new BRU)
  val lsu = Module(new LSU(preselectLoadGrant = true, registerMissResponses = true,
    localCdbData = true))
  val divs  = Seq.fill(p.numDiv)(Module(new DivUnit))

  val flush = rob.io.flush
  val backendFullFlush = RegNext(flush.valid, false.B)
  val branchRecoveryValid = RegInit(false.B)
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(
      branchRecoveryValid.toTarget, "max_fanout = 32")
  })
  val branchRecoveryIdx = Reg(UInt(p.robIdxBits.W))
  val branchRecoveryHead = Reg(UInt(p.robIdxBits.W))
  val bruKilledByActiveRecovery = branchRecoveryValid &&
    (bru.io.out.bits.robIdx === branchRecoveryIdx ||
      RobOrder.isYounger(bru.io.out.bits.robIdx, branchRecoveryIdx,
        branchRecoveryHead))
  val rawEarlyMispredict = bru.io.out.valid && bru.io.out.bits.mispredict &&
    !flush.valid && !backendFullFlush && !bruKilledByActiveRecovery
  branchRecoveryValid := rawEarlyMispredict
  when(rawEarlyMispredict) {
    branchRecoveryIdx := bru.io.out.bits.robIdx
    branchRecoveryHead := rob.io.headIdx
  }
  val branchRecovery = Wire(Valid(UInt(p.robIdxBits.W)))
  branchRecovery.valid := branchRecoveryValid
  branchRecovery.bits := branchRecoveryIdx
  val frontendRedirectRequest = rawEarlyMispredict || flush.valid
  val frontendRedirectRequestPc = Mux(rawEarlyMispredict,
    bru.io.out.bits.targetPc, flush.newPc)
  val frontendRedirectValid = RegNext(frontendRedirectRequest, false.B)
  val frontendRedirectPc = RegNext(Mux(flush.valid,
    flush.newPc, bru.io.out.bits.targetPc))
  when(frontendRedirectRequest) {
    assert(Mux(flush.valid, flush.newPc, bru.io.out.bits.targetPc) === frontendRedirectRequestPc)
  }
  val frontendRepairValid = RegNext(rawEarlyMispredict, false.B)
  val frontendRepairBits = RegNext(bru.io.bpuRepair)

  // ============ CDB 收集（提前声明，供 IQ/ISU/ROB 连接）============
  // Step004：CDB 流水寄存器。
  // cdbWire：执行单元输出；ALU/LSU lane 已在单元内寄存，BRU/DIV 为组合结果
  // cdbReg ：统一广播边界；ALU/LSU 直连内部结果寄存器，BRU/DIV 在此打一拍
  // ALU/LSU 的正式 CDB 已来自单元内结果寄存器，可直接进入 IQ 的 CAM
  // 捕捉/发射旁路；这里只为组合输出的 BRU/DIV 增加广播寄存边界。
  val cdbWire = Wire(Vec(numCdb, new CdbEntry))
  val aluAddressFastWake = Wire(Vec(numAlu, Valid(new WakeEvent)))
  val aluFastWake = Wire(Vec(numAlu, Valid(UInt(p.physRegBits.W))))
  for (left <- 0 until numAlu) {
    for (right <- left + 1 until numAlu) {
      when(aluFastWake(left).valid && aluFastWake(right).valid) {
        assert(aluFastWake(left).bits =/= aluFastWake(right).bits)
      }
    }
  }
  for (i <- 0 until numAlu) { cdbWire(i) := alus(i).io.out.bits ; cdbWire(i).valid := alus(i).io.out.valid }
  cdbWire(bruCdbLane) := bru.io.out.bits ; cdbWire(bruCdbLane).valid := bru.io.out.valid
  cdbWire(lsuCdbLane) := lsu.io.out.bits ; cdbWire(lsuCdbLane).valid := lsu.io.out.valid
  for (d <- 0 until p.numDiv) {
    cdbWire(p.divCdbLanes(d)) := divs(d).io.out.bits
    cdbWire(p.divCdbLanes(d)).valid := divs(d).io.out.valid
  }

  // CDB 载荷由独立 valid 保护；reset/flush 只清 valid，数据位保持原值。
  val cdbRegBits = Reg(Vec(numCdb, new CdbEntry))
  val cdbRegValid = RegInit(VecInit(Seq.fill(numCdb)(false.B)))
  val cdbReg = Wire(Vec(numCdb, new CdbEntry))
  for (i <- 0 until numCdb) {
    if (i < numAlu || i == lsuCdbLane) {
      cdbReg(i) := cdbWire(i)
      cdbReg(i).valid := (if (i < numAlu) alus(i).io.cdbBroadcastValid
        else lsu.io.cdbBroadcastValid)
    } else {
      cdbReg(i) := cdbRegBits(i)
      cdbReg(i).valid := cdbRegValid(i)
      when(cdbWire(i).valid) { cdbRegBits(i) := cdbWire(i) }
    }
  }
  def queueCdb(target: Int): Vec[CdbEntry] = {
    val local = WireDefault(cdbReg)
    for (i <- 0 until numCdb) {
      local(i).data := (if (i < numAlu) alus(i).io.cdbData.get(target)
        else if (i == lsuCdbLane) lsu.io.cdbData.get(target)
        else CdbEntry.localDataCopy(cdbWire(i).data, cdbWire(i).valid))
      if (p.enableDifftest) {
        when(cdbReg(i).valid) { assert(local(i).data === cdbReg(i).data) }
      }
    }
    local
  }
  when(backendFullFlush) {
    for (i <- 0 until numCdb) { cdbRegValid(i) := false.B }
  }.otherwise {
    for (i <- 0 until numCdb) {
      cdbRegValid(i) := (if (i < numAlu || i == lsuCdbLane) false.B else
        cdbWire(i).valid && !branchRecovery.valid && !flush.valid)
    }
  }

  // backendFullFlush = RegNext(flush.valid). ALU/LSU absorb that mask in
  // their existing result-valid registers without adding a pipeline stage.
  for (alu <- alus) { alu.io.fullFlushAhead := flush.valid }
  lsu.io.fullFlushAhead := flush.valid
  if (p.enableDifftest) {
    for (i <- 0 until numCdb) {
      val oldValid = if (i < numAlu || i == lsuCdbLane) cdbWire(i).valid
        else RegNext(cdbWire(i).valid && !branchRecovery.valid &&
          !backendFullFlush, false.B)
      assert(cdbReg(i).valid === (oldValid && !backendFullFlush))
    }
  }

  private val bruIqTargetCount = numAlu + 2
  val bruIqResultPrd = Reg(Vec(bruIqTargetCount, UInt(p.physRegBits.W)))
  for (target <- 0 until bruIqTargetCount) {
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(
        bruIqResultPrd(target).toTarget,
        "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 8")
    })
  }
  when(cdbWire(bruCdbLane).valid) {
    for (target <- 0 until bruIqTargetCount) {
      bruIqResultPrd(target) := cdbWire(bruCdbLane).prd
    }
  }

  val aluIqCdb = Wire(Vec(numAlu, Vec(numCdb, new CdbEntry)))
  for (target <- 0 until numAlu) {
    aluIqCdb(target) := queueCdb(target)
    for (producer <- 0 until numAlu) {
      aluIqCdb(target)(producer).prd := alus(producer).io.iqCdbPrd(target)
    }
    aluIqCdb(target)(bruCdbLane).prd := bruIqResultPrd(target)
    aluIqCdb(target)(lsuCdbLane).prd := lsu.io.iqCdbPrd(target)
    aluIqCdb(target)(lsuCdbLane).valid := lsu.io.aluIqCdbValid(target)
  }
  val lsuIqCdb = Wire(Vec(numCdb, new CdbEntry))
  lsuIqCdb := queueCdb(numAlu)
  for (producer <- 0 until numAlu) {
    lsuIqCdb(producer).prd := alus(producer).io.iqCdbPrd(numAlu)
  }
  lsuIqCdb(bruCdbLane).prd := bruIqResultPrd(numAlu + 1)
  lsuIqCdb(lsuCdbLane).prd := lsu.io.iqCdbPrd(numAlu)

  // ---- 调试：全局周期计数器 ----
  // （已移除，综合目标不需要）

  // 同容量队列存原始指令，译码后的宽 uop 不再往返 BRAM。
  // 保留队列输出与三级 IDU 的寄存边界，RAM 不组合穿透到 Rename。
  instructionBuffer.io.in <> ifu.io.out
  instructionBuffer.io.flush := frontendRedirectValid
  idu.io.in <> instructionBuffer.io.out
  idu.io.flush := frontendRedirectValid
  isu.io.in <> idu.io.out

  rob.io.allocReq   := isu.io.robAllocReq
  rob.io.allocEntry := isu.io.robAllocEntry
  isu.io.robAllocIdx := rob.io.allocIdx
  isu.io.hasUnresolvedBranch := rob.io.hasUnresolvedBranch
  isu.io.interruptPending := lsu.io.interruptPending
  isu.io.flush := flush
  isu.io.recover := branchRecovery
  isu.io.robHeadIdx := rob.io.headIdx
  rob.io.recover := branchRecovery

  val retirePacketBits = Reg(Vec(w, new RobCommitPort))
  val retirePacketValid = RegInit(VecInit(Seq.fill(w)(false.B)))
  val retirePacket = Wire(Vec(w, new RobCommitPort))
  for (i <- 0 until w) {
    retirePacketBits(i) := rob.io.commit(i)
    retirePacketValid(i) := rob.io.commit(i).valid
    retirePacket(i) := retirePacketBits(i)
    retirePacket(i).valid := retirePacketValid(i)
  }
  // Count releases on the same edge that captures the retirement packet.
  // Rename credits and SQ retirement masks then start from a registered count.
  lsu.io.lqRetireCount := RegNext(PopCount(rob.io.commit.map(c =>
    c.valid && c.retireLq)), 0.U(p.issueCountBits.W))
  lsu.io.sqRetireCount := RegNext(PopCount(rob.io.commit.map(c =>
    c.valid && c.isStore)), 0.U(p.issueCountBits.W))

  for (i <- 0 until w) {
    isu.io.commit(i).valid     := retirePacket(i).valid
    isu.io.commit(i).writesReg := retirePacket(i).writesReg
    isu.io.commit(i).ldst      := retirePacket(i).ldst
    isu.io.commit(i).prd       := retirePacket(i).prd
    isu.io.commit(i).prevPrd   := retirePacket(i).prevPrd
    isu.io.commit(i).isSystem  := retirePacket(i).isSystem
  }

  // ============ Dispatch -> operand ingress -> IQ 路由 ============
  val disp = isu.io.dispatch // Vec(w, Decoupled(RenamedUop))

  val robReserved = PopCount((0 until w).map(i => disp(i).valid))
  val robFreeSlots = rob.io.allocationCredits
  val robFreeAfterReserved = Mux(robFreeSlots >= robReserved,
    robFreeSlots - robReserved, 0.U(robFreeSlots.getWidth.W))
  isu.io.robCanAlloc := Mux(robFreeAfterReserved >= w.U,
    w.U(dispatchCountBits.W),
    robFreeAfterReserved(dispatchCountBits - 1, 0))

  val dispatchOperands = Wire(Vec(w, new ExecIn))
  val resolvedDispatchOperands = Wire(Vec(w, new ExecIn))
  for (i <- 0 until w) {
    prf.io.raddr(2 * i) := disp(i).bits.prs1
    prf.io.raddr(2 * i + 1) := disp(i).bits.prs2
    dispatchOperands(i).uop := disp(i).bits
    dispatchOperands(i).src1 := Mux(isu.io.dispatchCapturedSrc1(i).valid,
      isu.io.dispatchCapturedSrc1(i).bits, prf.io.rdata(2 * i))
    dispatchOperands(i).src2 := Mux(isu.io.dispatchCapturedSrc2(i).valid,
      isu.io.dispatchCapturedSrc2(i).bits, prf.io.rdata(2 * i + 1))

    resolvedDispatchOperands(i) := dispatchOperands(i)
    val cdbHits1 = VecInit((0 until numCdb).map(c =>
      cdbReg(c).valid && cdbReg(c).prd === disp(i).bits.prs1 &&
        disp(i).bits.prs1 =/= 0.U))
    val cdbHits2 = VecInit((0 until numCdb).map(c =>
      cdbReg(c).valid && cdbReg(c).prd === disp(i).bits.prs2 &&
        disp(i).bits.prs2 =/= 0.U))
    when(disp(i).bits.useRs1 && cdbHits1.asUInt.orR) {
      resolvedDispatchOperands(i).src1 := Mux1H(cdbHits1, cdbReg.map(_.data))
      resolvedDispatchOperands(i).uop.prs1Ready := true.B
    }
    when(disp(i).bits.useRs2 && cdbHits2.asUInt.orR) {
      resolvedDispatchOperands(i).src2 := Mux1H(cdbHits2, cdbReg.map(_.data))
      resolvedDispatchOperands(i).uop.prs2Ready := true.B
    }
    assert(PopCount(cdbHits1) <= 1.U)
    assert(PopCount(cdbHits2) <= 1.U)
  }

  for (i <- 0 until w) {
    disp(i).ready := true.B
  }

  val operandBits = Reg(Vec(w, new ExecIn))
  val operandLsqNeeded = Reg(Vec(w, Bool()))
  val operandStoreDataReady = Reg(Vec(w, Bool()))
  val operandAluSelect = RegInit(VecInit(Seq.fill(numAlu)(
    VecInit(Seq.fill(w)(0.U(w.W))))))
  val operandBruSelect = RegInit(VecInit(Seq.fill(w)(0.U(w.W))))
  val operandLsuSelect = RegInit(VecInit(Seq.fill(w)(0.U(w.W))))
  val operandDivSelect = RegInit(VecInit(Seq.fill(p.numDiv)(
    VecInit(Seq.fill(w)(0.U(w.W))))))
  val operandAluCount = RegInit(VecInit(
    Seq.fill(numAlu)(0.U(dispatchCountBits.W))))
  val operandBruCount = RegInit(0.U(dispatchCountBits.W))
  val operandLsuCount = RegInit(0.U(dispatchCountBits.W))
  val operandLqCount = RegInit(0.U(dispatchCountBits.W))
  val operandSqCount = RegInit(0.U(dispatchCountBits.W))
  val operandDivCount = RegInit(VecInit(Seq.fill(p.numDiv)(
    0.U(dispatchCountBits.W))))

  for (i <- 0 until w) {
    operandBits(i) := resolvedDispatchOperands(i)
    val uop = resolvedDispatchOperands(i).uop
    operandLsqNeeded(i) := uop.isLoad || uop.isStore
    operandStoreDataReady(i) := !uop.useRs2 || uop.prs2Ready
  }
  operandAluSelect := isu.io.dispatchAluSelect
  operandBruSelect := isu.io.dispatchBruSelect
  operandLsuSelect := isu.io.dispatchLsuSelect
  operandDivSelect := isu.io.dispatchDivSelect

  when(flush.valid || branchRecovery.valid || backendFullFlush) {
    operandAluCount := VecInit(Seq.fill(numAlu)(0.U(dispatchCountBits.W)))
    operandBruCount := 0.U
    operandLsuCount := 0.U
    operandLqCount := 0.U
    operandSqCount := 0.U
    operandDivCount := VecInit(Seq.fill(p.numDiv)(0.U(dispatchCountBits.W)))
  }.otherwise {
    operandAluCount := isu.io.dispatchAluCount
    operandBruCount := isu.io.dispatchBruCount
    operandLsuCount := isu.io.dispatchLsuCount
    operandLqCount := isu.io.dispatchLqCount.get
    operandSqCount := isu.io.dispatchSqCount.get
    operandDivCount := isu.io.dispatchDivCount
  }

  // 容量在目标队列边界打一拍，切断 IQ/LSQ freeCount 经 rename 前缀判断
  // 返回 InstructionBuffer dequeue 的长组合路径。寄存的是“扣除本拍 enqueue 后”的容量，
  // 所以下一拍即使尚未观察到队列计数更新，也绝不会过量预约；本拍 issue
  // 或 retire 新释放的槽位保守地晚一拍可见。
  def clampDispatchCapacity(free: UInt): UInt =
    Mux(free >= w.U, w.U(dispatchCountBits.W), free(dispatchCountBits - 1, 0))

  val aluCapacityReg = RegInit(VecInit(Seq.fill(numAlu)(
    p.aluIqDepth.U(p.aluIqCountBits.W))))
  val bruCapacityReg = RegInit(p.bruIqDepth.U(p.bruIqCountBits.W))
  val divCapacityReg = RegInit(VecInit(Seq.fill(p.numDiv)(
    p.divIqDepth.U(p.divIqCountBits.W))))

  for (a <- 0 until numAlu) {
    val enqCount = operandAluCount(a)
    val freeAfterEnq = p.aluIqDepth.U - aluIq(a).io.occupancy - enqCount
    aluCapacityReg(a) := freeAfterEnq
    val freeAfterOperand = Mux(aluCapacityReg(a) >= operandAluCount(a),
      aluCapacityReg(a) - operandAluCount(a), 0.U)
    val freeAfterOperandWide = freeAfterOperand.pad(pendingCapacityBits)
    isu.io.aluIqCapacity(a) := Mux(
      freeAfterOperandWide >= (2 * w).U,
      (2 * w).U(pendingCapacityBits.W), freeAfterOperandWide)
  }
  val bruEnqCount = operandBruCount
  val bruFreeAfterEnq = p.bruIqDepth.U - bruIq.io.occupancy - bruEnqCount
  bruCapacityReg := bruFreeAfterEnq
  val bruPendingCount = operandBruCount +& isu.io.dispatchBruCount
  val bruAvailable = Mux(bruCapacityReg >= bruPendingCount,
    bruCapacityReg - bruPendingCount, 0.U)
  isu.io.bruIqCapacity := clampDispatchCapacity(bruAvailable)

  // The queue count already includes the last accepted issue and enqueue.
  // Subtract only the two stages that have reserved slots but not enqueued yet.
  val lsuFreeSlots = lsuIq.io.freeSlots
  val lsuPendingCount = operandLsuCount +& isu.io.dispatchLsuCount
  val lsuAvailable = Mux(lsuFreeSlots >= lsuPendingCount,
    lsuFreeSlots - lsuPendingCount, 0.U)
  isu.io.lsuIqCapacity := clampDispatchCapacity(lsuAvailable)
  for (d <- 0 until p.numDiv) {
    val divEnqCount = operandDivCount(d)
    val divFreeAfterEnq = p.divIqDepth.U - divIq(d).io.occupancy - divEnqCount
    divCapacityReg(d) := divFreeAfterEnq
    val divPendingCount = operandDivCount(d) +& isu.io.dispatchDivCount(d)
    val divAvailable = Mux(divCapacityReg(d) >= divPendingCount,
      divCapacityReg(d) - divPendingCount, 0.U)
    isu.io.divIqCapacity(d) := clampDispatchCapacity(divAvailable)
  }
  val lqPendingCount = operandLqCount +& isu.io.dispatchLqCount.get
  val sqPendingCount = operandSqCount +& isu.io.dispatchSqCount.get
  // Each rank asks its own threshold. No saturating subtraction or narrow
  // count encoding follows the capacity signal on its way back to rename.
  isu.io.lqCapacityMask.get := VecInit((1 to w).map(rank =>
    lsu.io.lqEnqCapacity >= (lqPendingCount +& rank.U))).asUInt
  isu.io.sqCapacityMask.get := VecInit((1 to w).map(rank =>
    lsu.io.sqEnqCapacity >= (sqPendingCount +& rank.U))).asUInt
  isu.io.lqCapacity := 0.U
  isu.io.sqCapacity := 0.U


  for (a <- 0 until numAlu) {
    assert(operandAluCount(a) <= p.aluIqParams(a).enqWidth.U)
    aluIq(a).io.enqCount := operandAluCount(a)
    for (rank <- 0 until p.aluIqParams(a).enqWidth) {
      aluIq(a).io.enqBits(rank) := Mux1H(
        operandAluSelect(a)(rank), operandBits)
    }
    aluIq(a).io.cdb := aluIqCdb(a)
    aluIq(a).io.secondSourceLsuData.get := lsu.io.iqSecondSourceData.get(a)
    if (p.enableDifftest) {
      when(aluIqCdb(a)(lsuCdbLane).valid) {
        assert(lsu.io.iqSecondSourceData.get(a) === aluIqCdb(a)(lsuCdbLane).data)
      }
    }
    aluIq(a).io.loadScheduledWake := lsu.io.loadScheduledCdbWake
    aluIq(a).io.loadCdbIsLoad.get := lsu.io.loadCdbIsLoad
    aluIq(a).io.qualifiedLoadCdb.get := lsu.io.aluIqLoadCdbValid(a)
    connectIqFast(aluIq(a))
    aluIq(a).io.flush := backendFullFlush
    aluIq(a).io.recover := branchRecovery
    aluIq(a).io.robHeadIdx := branchRecoveryHead
  }
  assert(operandBruCount <= 1.U)
  assert(isu.io.dispatchBruCount <= 1.U)
  bruIq.io.enqCount := operandBruCount(0)
  for (rank <- 0 until p.bruIqParams.enqWidth) {
    bruIq.io.enqBits(rank) := Mux1H(operandBruSelect(rank), operandBits)
  }
  lsuIq.io.enqCount := operandLsuCount
  for (rank <- 0 until w) {
    val lsuSelect = operandLsuSelect(rank)
    val selectedLsuOperands = Mux1H(lsuSelect, operandBits)
    val lsuLaneValid = operandLsuCount > rank.U && lsuSelect.orR
    val lsqNeeded = (lsuSelect & operandLsqNeeded.asUInt).orR
    lsu.io.lsqAllocValid(rank) := lsuLaneValid && lsqNeeded && !branchRecovery.valid
    lsu.io.lsqAllocBits(rank) := selectedLsuOperands.uop
    lsu.io.lsqAllocStoreData(rank) := selectedLsuOperands.src2
    lsu.io.lsqAllocStoreDataReady(rank) :=
      (lsuSelect & operandStoreDataReady.asUInt).orR
    if (p.enableDifftest) {
      when(lsuLaneValid) {
        assert(lsqNeeded === (selectedLsuOperands.uop.isLoad || selectedLsuOperands.uop.isStore))
        assert(lsu.io.lsqAllocStoreDataReady(rank) ===
          (!selectedLsuOperands.uop.useRs2 || selectedLsuOperands.uop.prs2Ready))
      }
    }
    val lsuDispatchBits = WireDefault(selectedLsuOperands)
    lsuDispatchBits.uop.lsqIdx := lsu.io.lsqAllocIdx(rank)
    lsuIq.io.enqBits(rank) := lsuDispatchBits
  }
  val bruIqCdb = Wire(Vec(numCdb, new CdbEntry))
  bruIqCdb := queueCdb(numAlu + 1)
  bruIqCdb(numAlu).prd := bruIqResultPrd(numAlu)
  bruIq.io.cdb   := bruIqCdb
  bruIq.io.loadScheduledWake := lsu.io.loadScheduledCdbWake
  bruIq.io.loadCdbIsLoad.get := lsu.io.loadCdbIsLoad
  connectIqFast(bruIq)
  bruIq.io.flush := backendFullFlush
  bruIq.io.recover := branchRecovery
  bruIq.io.robHeadIdx := branchRecoveryHead
  for (d <- 0 until p.numDiv) {
    assert(operandDivCount(d) <= p.divIqParams(d).enqWidth.U)
    divIq(d).io.enqCount := operandDivCount(d)
    for (rank <- 0 until p.divIqParams(d).enqWidth) {
      divIq(d).io.enqBits(rank) := Mux1H(
        operandDivSelect(d)(rank), operandBits)
    }
    divIq(d).io.cdb := queueCdb(numAlu + 2 + d)
    divIq(d).io.loadScheduledWake := lsu.io.loadScheduledCdbWake
    divIq(d).io.loadCdbIsLoad.get := lsu.io.loadCdbIsLoad
    connectIqFast(divIq(d))
    divIq(d).io.flush := backendFullFlush
    divIq(d).io.recover := branchRecovery
    divIq(d).io.robHeadIdx := branchRecoveryHead
  }
  lsuIq.io.cdb   := lsuIqCdb
  lsuIq.io.aluFastWake := aluAddressFastWake
  lsuIq.io.aluFastResult := VecInit(alus.map(_.io.earlyAddressResult))
  lsu.io.cdb := queueCdb(p.cdbDataCopies - 1)
  lsuIq.io.flush := backendFullFlush
  lsuIq.io.recover := branchRecovery
  lsuIq.io.robHeadIdx := branchRecoveryHead
  lsu.io.lsqQueryValid := lsuIq.io.lsqQueryValid
  lsu.io.lsqQueryIdx := lsuIq.io.lsqQueryIdx
  lsu.io.lsqQueryRobIdx := lsuIq.io.lsqQueryRobIdx
  lsuIq.io.lsqQuerySafe := lsu.io.lsqQuerySafe
  // ============ CDB 广播（Step004：IQ/ISU/ROB/PRF 使用 cdbReg）============
  isu.io.cdb := cdbReg   // ISU ready 表和 dispatch readyOf() 使用同一写回边界
  rob.io.cdb := cdbReg   // ROB done 标记延迟 +1 拍（ROB 容量足够，不影响正确性）

  val loadUseIssue = Wire(Vec(numAlu, Bool()))
  val loadUseIssuePc = Wire(Vec(numAlu, UInt(p.xLen.W)))
  val loadUseIssuePrd = Wire(Vec(numAlu, UInt(p.physRegBits.W)))
  val loadUseExecute = Wire(Vec(numAlu, Bool()))
  val loadUseExecutePc = Wire(Vec(numAlu, UInt(p.xLen.W)))
  val loadUseExecutePrd = Wire(Vec(numAlu, UInt(p.physRegBits.W)))
  val localFollowerAdvance = Wire(Vec(numAlu, Bool()))

  for (i <- 0 until numAlu) {
    val iss = aluIq(i).io.issue
    alus(i).io.multiplyOperands.get := aluIq(i).io.preparedMultiply.get
    alus(i).io.multiplyUnsigned.get := aluIq(i).io.preparedMultiplyUnsigned.get
    alus(i).io.operationOH.get := aluIq(i).io.issueAluOpOH.get
    alus(i).io.operationIsMultiply.get := aluIq(i).io.issueIsMultiply.get
    alus(i).io.simpleB.get := aluIq(i).io.issueSimpleB.get
    alus(i).io.in.valid := iss.valid
    alus(i).io.in.bits.uop := iss.bits.uop
    alus(i).io.in.bits.src1 := iss.bits.src1
    alus(i).io.in.bits.src2 := iss.bits.src2
    iss.ready := alus(i).io.in.ready
    alus(i).io.flush := backendFullFlush
    alus(i).io.recover := branchRecovery
    alus(i).io.robHeadIdx := branchRecoveryHead

    val simpleWake = aluIq(i).io.simpleAluWake
    val multiplyWake = aluIq(i).io.multiplyAluWake
    aluFastWake(i).valid := simpleWake || (p.allowAluMultiplyForward.B && multiplyWake)
    aluFastWake(i).bits := iss.bits.uop.prd
    aluAddressFastWake(i).valid := simpleWake
    aluAddressFastWake(i).bits.prd := iss.bits.uop.prd
    aluAddressFastWake(i).bits.isMultiply := false.B

    val usesLoad = iss.bits.src1FromScheduledCdb ||
      iss.bits.src2FromScheduledCdb
    loadUseIssue(i) := iss.fire && usesLoad
    loadUseIssuePc(i) := iss.bits.uop.pc
    loadUseIssuePrd(i) := Mux(iss.bits.src1FromScheduledCdb,
      iss.bits.uop.prs1, iss.bits.uop.prs2)
    loadUseExecute(i) := RegNext(loadUseIssue(i), false.B)
    loadUseExecutePc(i) := RegNext(loadUseIssuePc(i))
    loadUseExecutePrd(i) := RegNext(loadUseIssuePrd(i))
    localFollowerAdvance(i) := iss.fire &&
      (iss.bits.src1FastAlu.valid || iss.bits.src2FastAlu.valid)
  }

  val bruIssue = bruIq.io.issue
  val bruInputBits = Reg(new ExecIn)
  val bruInputValid = RegInit(false.B)
  val bruRecoveryReplayBits = Reg(new ExecIn)
  val bruRecoveryReplayValid = RegInit(false.B)
  bruIssue.ready := true.B
  bruInputBits.uop := bruIssue.bits.uop
  bruInputBits.src1 := bruIssue.bits.src1
  bruInputBits.src2 := bruIssue.bits.src2
  bruRecoveryReplayBits := bruInputBits
  val bruInputKilledByRecovery = branchRecovery.valid && bruInputValid &&
    RobOrder.isYounger(bruInputBits.uop.robIdx, branchRecovery.bits,
      branchRecoveryHead)
  when(backendFullFlush) {
    bruInputValid := false.B
  }.otherwise {
    bruInputValid := bruIssue.valid
  }
  when(backendFullFlush) {
    bruRecoveryReplayValid := false.B
  }.elsewhen(branchRecovery.valid) {
    bruRecoveryReplayValid := bruInputValid && !bruInputKilledByRecovery
  }.elsewhen(bruRecoveryReplayValid) {
    bruRecoveryReplayValid := false.B
  }
  val bruExecuteReplay = bruRecoveryReplayValid
  bru.io.in.valid := (bruInputValid || bruExecuteReplay) &&
    !branchRecovery.valid
  bru.io.in.bits := Mux(bruExecuteReplay,
    bruRecoveryReplayBits, bruInputBits)
  bru.io.flush := backendFullFlush

  val memIssue = lsuIq.io.issue
  lsu.io.in.valid := memIssue.valid
  lsu.io.in.bits.uop := memIssue.bits.uop
  lsu.io.in.bits.src1 := memIssue.bits.src1
  lsu.io.in.bits.src2 := memIssue.bits.src2
  memIssue.ready := lsu.io.in.ready
  lsu.io.flush := backendFullFlush
  lsu.io.recover := branchRecovery

  for (d <- 0 until p.numDiv) {
    val divIssue = divIq(d).io.issue
    divIssue.ready := divs(d).io.in.ready && !branchRecovery.valid
    divs(d).io.in.valid := divIssue.valid && !branchRecovery.valid
    divs(d).io.in.bits.uop := divIssue.bits.uop
    divs(d).io.in.bits.src1 := divIssue.bits.src1
    divs(d).io.in.bits.src2 := divIssue.bits.src2
    divs(d).io.flush := backendFullFlush
    divs(d).io.recover := branchRecovery
    divs(d).io.robHeadIdx := branchRecoveryHead
  }

  // ============ PRF 写口 ============
  // PRF 内部还有一级写入寄存器。所有执行结果从正式 CDB 边界进入；LSU
  // 输出本身已经寄存，不再把队列仲裁组合逻辑直接送入 PRF staging。
  for (i <- 0 until numCdb) {
    prf.io.waddr(i) := cdbReg(i).prd
    prf.io.wdata(i) := cdbReg(i).data
    if (i == lsuCdbLane) {
      val lsuWriteKilled = backendFullFlush
      prf.io.wen(i) := cdbReg(i).valid && !lsuWriteKilled &&
        (cdbReg(i).prd =/= 0.U)
    } else {
      prf.io.wen(i) := cdbReg(i).valid && (cdbReg(i).prd =/= 0.U)
    }
  }

  // ============ LSU <-> ROB 连接 ============
  // ROB -> LSU：store 提交通知
  lsu.io.storeCommitValid := rob.io.storeCommitValid
  lsu.io.storeCommitIdx   := rob.io.storeCommitIdx
  // LSU -> ROB：store 总线写完成确认
  rob.io.storeCommitDone  := lsu.io.storeCommitDone
  rob.io.storeWindowBase := lsu.io.storeWindowBase
  rob.io.storeWindowValid := lsu.io.storeWindowValid
  rob.io.storeWindowCacheable := lsu.io.storeWindowCacheable
  rob.io.storeWindowDataReady := lsu.io.storeWindowDataReady
  val pendingBufferedStores = PopCount(retirePacket.map(c =>
    c.valid && c.isCacheableStore))
  rob.io.storeCommitCapacity := Mux(
    lsu.io.storeCommitCapacity >= pendingBufferedStores,
    lsu.io.storeCommitCapacity - pendingBufferedStores, 0.U)
  rob.io.lqRelease := lsu.io.lqRelease
  if (p.enableDifftest) {
    io.orderedStoreDebug.recoverValid := branchRecovery.valid
    io.orderedStoreDebug.recoverIdx := branchRecovery.bits
    io.orderedStoreDebug.robRequestValid := rob.io.storeCommitValid
    io.orderedStoreDebug.robRequestIdx := rob.io.storeCommitIdx
    io.orderedStoreDebug.robRequestAcked := rob.io.storeCommitAcked
    io.orderedStoreDebug.lsuCompletion := lsu.io.storeCommitDone
    io.orderedStoreDebug.lsuCapture := lsu.io.orderedStoreCapture
    io.orderedStoreDebug.lsuPending := lsu.io.orderedStorePending
    io.orderedStoreDebug.lsuDoneHeld := lsu.io.orderedStoreDoneHeld
    io.orderedStoreDebug.lsuRobIdx := lsu.io.orderedStoreRobIdx
    io.orderedStoreDebug.awFire := lsu.io.orderedStoreAwFire
    io.orderedStoreDebug.wFire := lsu.io.orderedStoreWFire
    io.orderedStoreDebug.bFire := lsu.io.orderedStoreBFire
    io.orderedStoreDebug.addr := lsu.io.orderedStoreAddr
    io.orderedStoreDebug.data := lsu.io.orderedStoreData
    io.orderedStoreDebug.strb := lsu.io.orderedStoreStrb
    io.orderedStoreDebug.commitMask := VecInit(
      rob.io.commit.map(_.valid)).asUInt
    io.orderedStoreDebug.commitCount := PopCount(rob.io.commit.map(_.valid))
  } else {
    io.orderedStoreDebug := 0.U.asTypeOf(io.orderedStoreDebug)
  }
  // 系统指令副作用只在 ROB 头部发生，并用寄存确认切断提交组合反馈。
  lsu.io.sysCommitValid := rob.io.sysCommitValid
  lsu.io.sysCommitIdx   := rob.io.sysCommitIdx
  rob.io.sysCommitDone  := lsu.io.sysCommitDone
  rob.io.sysCommitRestartPc := lsu.io.sysCommitRestartPc
  lsu.io.trapCommitValid := rob.io.trapCommitValid
  lsu.io.trapCommit := rob.io.trapCommit
  rob.io.trapCommitDone := lsu.io.trapCommitDone
  rob.io.trapCommitRestartPc := lsu.io.trapCommitRestartPc
  lsu.io.robHeadIdx := rob.io.headIdx
  lsu.io.recoverHeadIdx := branchRecoveryHead
  lsu.io.hardwareInterrupts := io.hardwareInterrupts
  lsu.io.ipiInterrupt := io.ipiInterrupt
  for (i <- 0 until w) {
    lsu.io.commit(i).valid := retirePacket(i).valid
    lsu.io.commit(i).robIdx := retirePacket(i).robIdx
    lsu.io.commit(i).isLoad := retirePacket(i).retireLq
    lsu.io.commit(i).isStore := retirePacket(i).isStore
    lsu.io.commit(i).isCacheableStore := retirePacket(i).isCacheableStore
  }

  ifu.io.crmd := lsu.io.crmd
  ifu.io.dmw0 := lsu.io.dmw0
  ifu.io.dmw1 := lsu.io.dmw1
  ifu.io.idle := lsu.io.idle
  lsu.io.instructionTranslationReq <> ifu.io.translationReq
  ifu.io.translationResp <> lsu.io.instructionTranslationResp
  val ifuInvalidateAllReg = RegNext(lsu.io.icacheInvalidateAll, false.B)
  val ifuCacopValidReg = RegNext(lsu.io.cacop.valid, false.B)
  val ifuCacopCodeReg = RegEnable(lsu.io.cacop.bits.code, lsu.io.cacop.valid)
  val ifuCacopAddrReg = RegEnable(lsu.io.cacop.bits.addr, lsu.io.cacop.valid)
  ifu.io.invalidateAll := ifuInvalidateAllReg
  ifu.io.cacop.valid := ifuCacopValidReg
  ifu.io.cacop.bits.code := ifuCacopCodeReg
  ifu.io.cacop.bits.addr := ifuCacopAddrReg

  // ============ 重定向（flush）到 IFU ============
  ifu.io.redirect.valid := frontendRedirectValid
  ifu.io.redirect.pc    := frontendRedirectPc
  ifu.io.bpuRepair.valid := frontendRepairValid
  ifu.io.bpuRepair.bits := frontendRepairBits

  // ============ BPU 训练反馈：ROB 顺序提交 -> 寄存 -> IFU -> BPU ============
  // ROB 在任意分支后截断当拍提交，因此这里至多选择一条。预测器只看到
  // 已提交分支，乱序完成和待冲刷路径都不会污染 BHT/GHR。
  val branchCommitVec = VecInit(retirePacket.map(c => c.valid && c.isBranch))
  val branchCommitValid = branchCommitVec.asUInt.orR
  val branchCommitSel = PriorityEncoder(branchCommitVec)
  val branchCommit = retirePacket(branchCommitSel)
  val bpuUpdateBits = Wire(new BpuUpdate)
  bpuUpdateBits.valid := branchCommitValid
  bpuUpdateBits.pc := branchCommit.pc
  bpuUpdateBits.taken := branchCommit.actualTaken
  bpuUpdateBits.target := branchCommit.targetPc
  bpuUpdateBits.isConditional := branchCommit.isConditional
  bpuUpdateBits.isCall := branchCommit.isCall
  bpuUpdateBits.isRet := branchCommit.isRet
  bpuUpdateBits.isIndirect := branchCommit.isIndirect
  bpuUpdateBits.bhtIdx := branchCommit.bhtIdx
  bpuUpdateBits.isBackward := branchCommit.isBackward
  bpuUpdateBits.predTaken := branchCommit.predTaken
  bpuUpdateBits.predGhr := branchCommit.predGhr
  bpuUpdateBits.predTageTable := branchCommit.predTageTable
  bpuUpdateBits.predTageGhr := branchCommit.predTageGhr
  bpuUpdateBits.predCtr := branchCommit.predCtr
  // Training fields are consumed only for a valid commit packet.
  val bpuUpdateReg = RegNext(bpuUpdateBits)
  ifu.io.bpuUpdate := bpuUpdateReg
  ifu.io.bpuUpdate.valid := RegNext(branchCommitValid, false.B)

  // ============ AXI4 访存仲裁器 ============
  val busArbiter = Module(new BusArbiter)
  busArbiter.io.ifu <> ifu.io.bus
  busArbiter.io.lsu <> lsu.io.bus

  busArbiter.io.store <> lsu.io.storeBus

  io.axi <> busArbiter.io.axi

  // ============ 调试信号 ============
  if (p.enablePerfEvents) {
  val cycleCount = RegInit(0.U(32.W))
  cycleCount := cycleCount + 1.U

  // ============ 提交指令计数 ============
  val commitCount = PopCount(retirePacket.map(_.valid))
  val instCommitReg = RegInit(0.U(32.W))
  instCommitReg := instCommitReg + commitCount
  io.instCommit := instCommitReg

  io.perf.active := true.B
  io.perf.dcacheFillOccupancy := lsu.io.perf.dcacheFillOccupancy
  io.perf.dcacheFillFull := lsu.io.perf.dcacheFillFull
  io.perf.bus := busArbiter.io.perf
  io.perf.cycle := cycleCount
  io.perf.commitCount := commitCount
  io.perf.commitRegwriteCount := PopCount(retirePacket.map(c => c.valid && c.writesReg))
  io.perf.commitStoreCount := PopCount(retirePacket.map(c => c.valid && c.isStore))
  io.perf.commitSystemCount := PopCount(retirePacket.map(c => c.valid && c.isSystem))
  io.perf.commitBranchCount := PopCount(retirePacket.map(c => c.valid && c.isBranch))

  io.perf.branchValid := bru.io.bpuUpdate.valid
  io.perf.branchMispredict := bru.io.bpuUpdate.valid && bru.io.out.bits.mispredict

  io.perf.icacheAccess := ifu.io.perf.access
  io.perf.icacheMiss := ifu.io.perf.miss
  io.perf.icacheRefillBeat := ifu.io.perf.refillBeat
  io.perf.l1Lookup := ifu.io.perf.l1Lookup
  io.perf.l1Hit := ifu.io.perf.l1Hit
  io.perf.l1Taken := ifu.io.perf.l1Taken
  io.perf.predictionRedirect := ifu.io.perf.predictionRedirect
  io.perf.predictionRedirectPc := ifu.io.perf.predictionRedirectPc
  io.perf.fetchCount := Mux(ifu.io.out.fire,
    PopCount(ifu.io.out.bits.slots.map(_.valid)), 0.U)

  io.perf.isuInputValid := isu.io.inputActive
  io.perf.isuDispatchCount := PopCount(isu.io.robAllocReq)
  for (i <- 0 until w) {
    val entry = isu.io.robAllocEntry(i)
    io.perf.dispatchEvent(i).valid := isu.io.robAllocReq(i)
    io.perf.dispatchEvent(i).pc := entry.pc
    io.perf.dispatchEvent(i).inst := entry.inst
    io.perf.dispatchEvent(i).robIdx := isu.io.robAllocIdx(i)
    io.perf.dispatchEvent(i).writesReg := entry.writesReg
    io.perf.dispatchEvent(i).ldst := entry.ldst
    io.perf.dispatchEvent(i).prd := entry.prd
    io.perf.dispatchEvent(i).isLoad := entry.isLoad
    io.perf.dispatchEvent(i).isStore := entry.isStore
    io.perf.dispatchEvent(i).isSystem := entry.isSystem
    io.perf.dispatchEvent(i).isBranch := entry.isBranch
    io.perf.dispatchEvent(i).isJump := entry.isBranch && !entry.isConditional
    io.perf.dispatchEvent(i).isConditional := entry.isConditional
    io.perf.dispatchEvent(i).isCall := entry.isCall
    io.perf.dispatchEvent(i).isRet := entry.isRet
    io.perf.dispatchEvent(i).isIndirect := entry.isIndirect
    io.perf.dispatchEvent(i).predTaken := entry.predTaken
    io.perf.dispatchEvent(i).predGhr := entry.predGhr
    io.perf.dispatchEvent(i).predTageTable := entry.predTageTable
    io.perf.dispatchEvent(i).predTageGhr := entry.predTageGhr
    io.perf.dispatchEvent(i).predCtr := entry.predCtr
  }

  for (i <- 0 until numAlu) {
    io.perf.aluIqOccupancy(i) := aluIq(i).io.occupancy
    io.perf.aluIssue(i) := aluIq(i).io.issue.fire && !flush.valid
    val issue = aluIq(i).io.issue
    val writebackUop = Reg(new RenamedUop)
    when(issue.fire) { writebackUop := issue.bits.uop }
    io.perf.aluIssueEvent(i).valid := issue.fire && !flush.valid
    io.perf.aluIssueEvent(i).pc := issue.bits.uop.pc
    io.perf.aluIssueEvent(i).inst := issue.bits.uop.inst
    io.perf.aluIssueEvent(i).robIdx := issue.bits.uop.robIdx
    io.perf.aluIssueEvent(i).fuType := issue.bits.uop.fuType
    io.perf.aluIssueEvent(i).aluOp := issue.bits.uop.aluOp
    io.perf.aluIssueEvent(i).divOp := issue.bits.uop.divOp
    io.perf.aluIssueEvent(i).brOp := issue.bits.uop.brOp
    io.perf.aluIssueEvent(i).memOp := issue.bits.uop.memOp
    io.perf.aluIssueEvent(i).sysOp := issue.bits.uop.sysOp
    io.perf.aluIssueEvent(i).csrNum := issue.bits.uop.csrNum
    io.perf.aluIssueEvent(i).prs1 := issue.bits.uop.prs1
    io.perf.aluIssueEvent(i).prs2 := issue.bits.uop.prs2
    io.perf.aluIssueEvent(i).prd := issue.bits.uop.prd
    io.perf.aluIssueEvent(i).lsqIdx := issue.bits.uop.lsqIdx
    io.perf.aluIssueEvent(i).src1 := issue.bits.src1
    io.perf.aluIssueEvent(i).src2 := issue.bits.src2
    io.perf.aluIssueEvent(i).imm := issue.bits.uop.imm
    io.perf.aluIssueEvent(i).useRs1 := issue.bits.uop.useRs1
    io.perf.aluIssueEvent(i).useRs2 := issue.bits.uop.useRs2
    io.perf.aluIssueEvent(i).useImm := issue.bits.uop.useImm
    io.perf.aluIssueEvent(i).writesReg := issue.bits.uop.writesReg
    io.perf.aluIssueEvent(i).isBranch := issue.bits.uop.isBranch
    io.perf.aluIssueEvent(i).isJump := issue.bits.uop.isJump
    io.perf.aluIssueEvent(i).isConditional := issue.bits.uop.isConditional
    io.perf.aluIssueEvent(i).isIndirect :=
      issue.bits.uop.brOp === BR_JIRL.U && !issue.bits.uop.isRet
    io.perf.aluIssueEvent(i).isCall := issue.bits.uop.isCall
    io.perf.aluIssueEvent(i).isRet := issue.bits.uop.isRet
    io.perf.aluIssueEvent(i).isLoad := issue.bits.uop.isLoad
    io.perf.aluIssueEvent(i).isStore := issue.bits.uop.isStore
    io.perf.aluIssueEvent(i).isSystem := issue.bits.uop.isSystem
    io.perf.aluIssueEvent(i).predTaken := issue.bits.uop.predTaken
    io.perf.aluIssueEvent(i).predTarget := issue.bits.uop.predTarget
    io.perf.aluIssueEvent(i).predBhtIdx := issue.bits.uop.predBhtIdx
    io.perf.aluIssueEvent(i).predGhr := issue.bits.uop.predGhr
    io.perf.aluIssueEvent(i).predTageTable := issue.bits.uop.predTageTable
    io.perf.aluIssueEvent(i).predTageGhr := issue.bits.uop.predTageGhr
    io.perf.aluIssueEvent(i).predCtr := issue.bits.uop.predCtr
    io.perf.aluWritebackEvent(i).valid := cdbReg(i).valid
    io.perf.aluWritebackEvent(i).pc := writebackUop.pc
    io.perf.aluWritebackEvent(i).inst := writebackUop.inst
    io.perf.aluWritebackEvent(i).robIdx := cdbReg(i).robIdx
    io.perf.aluWritebackEvent(i).prd := cdbReg(i).prd
    io.perf.aluWritebackEvent(i).data := cdbReg(i).data
    io.perf.aluWritebackEvent(i).fuType := writebackUop.fuType
    io.perf.aluWritebackEvent(i).aluOp := writebackUop.aluOp
    io.perf.aluWritebackEvent(i).divOp := writebackUop.divOp
    io.perf.aluWritebackEvent(i).writesReg := writebackUop.writesReg
  }
  io.perf.bruIqOccupancy := bruIq.io.occupancy
  io.perf.lsuIqOccupancy := lsuIq.io.occupancy
  io.perf.bruIssue := bruIq.io.issue.fire && !flush.valid
  io.perf.lsuIssue := lsuIq.io.issue.fire && !flush.valid
  val branchIssue = bruIq.io.issue
  io.perf.bruIssueEvent.valid := branchIssue.fire && !flush.valid
  io.perf.bruIssueEvent.pc := branchIssue.bits.uop.pc
  io.perf.bruIssueEvent.inst := branchIssue.bits.uop.inst
  io.perf.bruIssueEvent.robIdx := branchIssue.bits.uop.robIdx
  io.perf.bruIssueEvent.fuType := branchIssue.bits.uop.fuType
  io.perf.bruIssueEvent.aluOp := branchIssue.bits.uop.aluOp
  io.perf.bruIssueEvent.divOp := branchIssue.bits.uop.divOp
  io.perf.bruIssueEvent.brOp := branchIssue.bits.uop.brOp
  io.perf.bruIssueEvent.memOp := branchIssue.bits.uop.memOp
  io.perf.bruIssueEvent.sysOp := branchIssue.bits.uop.sysOp
  io.perf.bruIssueEvent.csrNum := branchIssue.bits.uop.csrNum
  io.perf.bruIssueEvent.prs1 := branchIssue.bits.uop.prs1
  io.perf.bruIssueEvent.prs2 := branchIssue.bits.uop.prs2
  io.perf.bruIssueEvent.prd := branchIssue.bits.uop.prd
  io.perf.bruIssueEvent.lsqIdx := branchIssue.bits.uop.lsqIdx
  io.perf.bruIssueEvent.src1 := branchIssue.bits.src1
  io.perf.bruIssueEvent.src2 := branchIssue.bits.src2
  io.perf.bruIssueEvent.imm := branchIssue.bits.uop.imm
  io.perf.bruIssueEvent.useRs1 := branchIssue.bits.uop.useRs1
  io.perf.bruIssueEvent.useRs2 := branchIssue.bits.uop.useRs2
  io.perf.bruIssueEvent.useImm := branchIssue.bits.uop.useImm
  io.perf.bruIssueEvent.writesReg := branchIssue.bits.uop.writesReg
  io.perf.bruIssueEvent.isBranch := branchIssue.bits.uop.isBranch
  io.perf.bruIssueEvent.isJump := branchIssue.bits.uop.isJump
  io.perf.bruIssueEvent.isConditional := branchIssue.bits.uop.isConditional
  io.perf.bruIssueEvent.isIndirect :=
    branchIssue.bits.uop.brOp === BR_JIRL.U && !branchIssue.bits.uop.isRet
  io.perf.bruIssueEvent.isCall := branchIssue.bits.uop.isCall
  io.perf.bruIssueEvent.isRet := branchIssue.bits.uop.isRet
  io.perf.bruIssueEvent.isLoad := branchIssue.bits.uop.isLoad
  io.perf.bruIssueEvent.isStore := branchIssue.bits.uop.isStore
  io.perf.bruIssueEvent.isSystem := branchIssue.bits.uop.isSystem
  io.perf.bruIssueEvent.predTaken := branchIssue.bits.uop.predTaken
  io.perf.bruIssueEvent.predTarget := branchIssue.bits.uop.predTarget
  io.perf.bruIssueEvent.predBhtIdx := branchIssue.bits.uop.predBhtIdx
  io.perf.bruIssueEvent.predGhr := branchIssue.bits.uop.predGhr
  io.perf.bruIssueEvent.predTageTable := branchIssue.bits.uop.predTageTable
  io.perf.bruIssueEvent.predTageGhr := branchIssue.bits.uop.predTageGhr
  io.perf.bruIssueEvent.predCtr := branchIssue.bits.uop.predCtr
  io.perf.lsuIssueEvent.valid := lsu.io.in.fire && !flush.valid
  io.perf.lsuIssueEvent.pc := lsu.io.in.bits.uop.pc
  io.perf.lsuIssueEvent.inst := lsu.io.in.bits.uop.inst
  io.perf.lsuIssueEvent.robIdx := lsu.io.in.bits.uop.robIdx
  io.perf.lsuIssueEvent.fuType := lsu.io.in.bits.uop.fuType
  io.perf.lsuIssueEvent.aluOp := lsu.io.in.bits.uop.aluOp
  io.perf.lsuIssueEvent.divOp := lsu.io.in.bits.uop.divOp
  io.perf.lsuIssueEvent.brOp := lsu.io.in.bits.uop.brOp
  io.perf.lsuIssueEvent.memOp := lsu.io.in.bits.uop.memOp
  io.perf.lsuIssueEvent.sysOp := lsu.io.in.bits.uop.sysOp
  io.perf.lsuIssueEvent.csrNum := lsu.io.in.bits.uop.csrNum
  io.perf.lsuIssueEvent.prs1 := lsu.io.in.bits.uop.prs1
  io.perf.lsuIssueEvent.prs2 := lsu.io.in.bits.uop.prs2
  io.perf.lsuIssueEvent.prd := lsu.io.in.bits.uop.prd
  io.perf.lsuIssueEvent.lsqIdx := lsu.io.in.bits.uop.lsqIdx
  io.perf.lsuIssueEvent.src1 := lsu.io.in.bits.src1
  io.perf.lsuIssueEvent.src2 := lsu.io.in.bits.src2
  io.perf.lsuIssueEvent.imm := lsu.io.in.bits.uop.imm
  io.perf.lsuIssueEvent.useRs1 := lsu.io.in.bits.uop.useRs1
  io.perf.lsuIssueEvent.useRs2 := lsu.io.in.bits.uop.useRs2
  io.perf.lsuIssueEvent.useImm := lsu.io.in.bits.uop.useImm
  io.perf.lsuIssueEvent.writesReg := lsu.io.in.bits.uop.writesReg
  io.perf.lsuIssueEvent.isBranch := lsu.io.in.bits.uop.isBranch
  io.perf.lsuIssueEvent.isJump := lsu.io.in.bits.uop.isJump
  io.perf.lsuIssueEvent.isConditional := lsu.io.in.bits.uop.isConditional
  io.perf.lsuIssueEvent.isIndirect :=
    lsu.io.in.bits.uop.brOp === BR_JIRL.U && !lsu.io.in.bits.uop.isRet
  io.perf.lsuIssueEvent.isCall := lsu.io.in.bits.uop.isCall
  io.perf.lsuIssueEvent.isRet := lsu.io.in.bits.uop.isRet
  io.perf.lsuIssueEvent.isLoad := lsu.io.in.bits.uop.isLoad
  io.perf.lsuIssueEvent.isStore := lsu.io.in.bits.uop.isStore
  io.perf.lsuIssueEvent.isSystem := lsu.io.in.bits.uop.isSystem
  io.perf.lsuIssueEvent.predTaken := lsu.io.in.bits.uop.predTaken
  io.perf.lsuIssueEvent.predTarget := lsu.io.in.bits.uop.predTarget
  io.perf.lsuIssueEvent.predBhtIdx := lsu.io.in.bits.uop.predBhtIdx
  io.perf.lsuIssueEvent.predGhr := lsu.io.in.bits.uop.predGhr
  io.perf.lsuIssueEvent.predTageTable := lsu.io.in.bits.uop.predTageTable
  io.perf.lsuIssueEvent.predTageGhr := lsu.io.in.bits.uop.predTageGhr
  io.perf.lsuIssueEvent.predCtr := lsu.io.in.bits.uop.predCtr
  for (i <- 0 until p.numDiv) {
    val issue = divIq(i).io.issue
    val writebackUop = Reg(new RenamedUop)
    when(issue.fire) { writebackUop := issue.bits.uop }
    io.perf.divIssueEvent(i).valid := issue.fire && !flush.valid && !branchRecovery.valid
    io.perf.divIssueEvent(i).pc := issue.bits.uop.pc
    io.perf.divIssueEvent(i).inst := issue.bits.uop.inst
    io.perf.divIssueEvent(i).robIdx := issue.bits.uop.robIdx
    io.perf.divIssueEvent(i).fuType := issue.bits.uop.fuType
    io.perf.divIssueEvent(i).aluOp := issue.bits.uop.aluOp
    io.perf.divIssueEvent(i).divOp := issue.bits.uop.divOp
    io.perf.divIssueEvent(i).brOp := issue.bits.uop.brOp
    io.perf.divIssueEvent(i).memOp := issue.bits.uop.memOp
    io.perf.divIssueEvent(i).sysOp := issue.bits.uop.sysOp
    io.perf.divIssueEvent(i).csrNum := issue.bits.uop.csrNum
    io.perf.divIssueEvent(i).prs1 := issue.bits.uop.prs1
    io.perf.divIssueEvent(i).prs2 := issue.bits.uop.prs2
    io.perf.divIssueEvent(i).prd := issue.bits.uop.prd
    io.perf.divIssueEvent(i).lsqIdx := issue.bits.uop.lsqIdx
    io.perf.divIssueEvent(i).src1 := issue.bits.src1
    io.perf.divIssueEvent(i).src2 := issue.bits.src2
    io.perf.divIssueEvent(i).imm := issue.bits.uop.imm
    io.perf.divIssueEvent(i).useRs1 := issue.bits.uop.useRs1
    io.perf.divIssueEvent(i).useRs2 := issue.bits.uop.useRs2
    io.perf.divIssueEvent(i).useImm := issue.bits.uop.useImm
    io.perf.divIssueEvent(i).writesReg := issue.bits.uop.writesReg
    io.perf.divIssueEvent(i).isBranch := issue.bits.uop.isBranch
    io.perf.divIssueEvent(i).isJump := issue.bits.uop.isJump
    io.perf.divIssueEvent(i).isConditional := issue.bits.uop.isConditional
    io.perf.divIssueEvent(i).isIndirect :=
      issue.bits.uop.brOp === BR_JIRL.U && !issue.bits.uop.isRet
    io.perf.divIssueEvent(i).isCall := issue.bits.uop.isCall
    io.perf.divIssueEvent(i).isRet := issue.bits.uop.isRet
    io.perf.divIssueEvent(i).isLoad := issue.bits.uop.isLoad
    io.perf.divIssueEvent(i).isStore := issue.bits.uop.isStore
    io.perf.divIssueEvent(i).isSystem := issue.bits.uop.isSystem
    io.perf.divIssueEvent(i).predTaken := issue.bits.uop.predTaken
    io.perf.divIssueEvent(i).predTarget := issue.bits.uop.predTarget
    io.perf.divIssueEvent(i).predBhtIdx := issue.bits.uop.predBhtIdx
    io.perf.divIssueEvent(i).predGhr := issue.bits.uop.predGhr
    io.perf.divIssueEvent(i).predTageTable := issue.bits.uop.predTageTable
    io.perf.divIssueEvent(i).predTageGhr := issue.bits.uop.predTageGhr
    io.perf.divIssueEvent(i).predCtr := issue.bits.uop.predCtr
    io.perf.divWritebackEvent(i).valid := divs(i).io.out.valid
    io.perf.divWritebackEvent(i).pc := writebackUop.pc
    io.perf.divWritebackEvent(i).inst := writebackUop.inst
    io.perf.divWritebackEvent(i).robIdx := divs(i).io.out.bits.robIdx
    io.perf.divWritebackEvent(i).prd := divs(i).io.out.bits.prd
    io.perf.divWritebackEvent(i).data := divs(i).io.out.bits.data
    io.perf.divWritebackEvent(i).fuType := writebackUop.fuType
    io.perf.divWritebackEvent(i).aluOp := writebackUop.aluOp
    io.perf.divWritebackEvent(i).divOp := writebackUop.divOp
    io.perf.divWritebackEvent(i).writesReg := writebackUop.writesReg
  }
  io.perf.robOccupancy := rob.io.occupancy
  io.perf.flush := flush.valid
  io.perf.robHeadIdx := rob.io.headIdx
  io.perf.robHeadPc := rob.io.headPc
  io.perf.robHeadDone := rob.io.headDone
  io.perf.robHeadIsLoad := rob.io.headIsLoad
  io.perf.robHeadIsStore := rob.io.headIsStore
  io.perf.robHeadIsBranch := rob.io.headIsBranch
  for (i <- 0 until w) {
    io.perf.commitPc(i) := retirePacket(i).pc
    io.perf.retireEvent(i).valid := retirePacket(i).valid
    io.perf.retireEvent(i).pc := retirePacket(i).pc
    io.perf.retireEvent(i).inst := retirePacket(i).inst
    io.perf.retireEvent(i).robIdx := retirePacket(i).robIdx
    io.perf.retireEvent(i).writesReg := retirePacket(i).writesReg
    io.perf.retireEvent(i).ldst := retirePacket(i).ldst
    io.perf.retireEvent(i).prd := retirePacket(i).prd
    io.perf.retireEvent(i).isLoad := retirePacket(i).isLoad
    io.perf.retireEvent(i).isStore := retirePacket(i).isStore
    io.perf.retireEvent(i).isSystem := retirePacket(i).isSystem
    io.perf.retireEvent(i).isBranch := retirePacket(i).isBranch
    io.perf.retireEvent(i).isJump := retirePacket(i).isBranch &&
      !retirePacket(i).isConditional
    io.perf.retireEvent(i).isConditional := retirePacket(i).isConditional
    io.perf.retireEvent(i).isCall := retirePacket(i).isCall
    io.perf.retireEvent(i).isRet := retirePacket(i).isRet
    io.perf.retireEvent(i).isIndirect := retirePacket(i).isIndirect
    io.perf.retireEvent(i).predTaken := retirePacket(i).predTaken
    io.perf.retireEvent(i).actualTaken := retirePacket(i).actualTaken
    io.perf.retireEvent(i).targetPc := retirePacket(i).targetPc
  }
  io.perf.branchExecuteEvent.valid := bru.io.bpuUpdate.valid
  io.perf.branchExecuteEvent.pc := bru.io.in.bits.uop.pc
  io.perf.branchExecuteEvent.inst := bru.io.in.bits.uop.inst
  io.perf.branchExecuteEvent.robIdx := bru.io.in.bits.uop.robIdx
  io.perf.branchExecuteEvent.brOp := bru.io.in.bits.uop.brOp
  io.perf.branchExecuteEvent.conditional := bru.io.in.bits.uop.isConditional
  io.perf.branchExecuteEvent.call := bru.io.in.bits.uop.isCall
  io.perf.branchExecuteEvent.ret := bru.io.in.bits.uop.isRet
  io.perf.branchExecuteEvent.indirect := bru.io.in.bits.uop.brOp === UopConst.BR_JIRL.U &&
    !bru.io.in.bits.uop.isRet
  io.perf.branchExecuteEvent.predictedTaken := bru.io.in.bits.uop.predTaken
  io.perf.branchExecuteEvent.actualTaken := bru.io.out.bits.actualTaken
  io.perf.branchExecuteEvent.predictedTarget := bru.io.in.bits.uop.predTarget
  io.perf.branchExecuteEvent.actualTarget := bru.io.out.bits.targetPc
  io.perf.branchExecuteEvent.mispredict := bru.io.out.bits.mispredict
  io.perf.branchExecuteEvent.predBhtIdx := bru.io.in.bits.uop.predBhtIdx
  io.perf.branchExecuteEvent.predGhr := bru.io.in.bits.uop.predGhr
  io.perf.branchExecuteEvent.predTageTable := bru.io.in.bits.uop.predTageTable
  io.perf.branchExecuteEvent.predTageGhr := bru.io.in.bits.uop.predTageGhr
  io.perf.branchExecuteEvent.predCtr := bru.io.in.bits.uop.predCtr
  io.perf.bruExecuteValid := bru.io.bpuUpdate.valid
  io.perf.bruExecutePc := bru.io.bpuUpdate.pc
  io.perf.bruExecuteTarget := bru.io.bpuUpdate.target
  io.perf.bruExecuteSrc1 := bru.io.in.bits.src1
  io.perf.bruExecuteRobIdx := bru.io.in.bits.uop.robIdx
  io.perf.bruExecuteOp := bru.io.in.bits.uop.brOp
  io.perf.bruExecuteMispredict := bru.io.out.bits.mispredict

  io.perf.loadIssue := lsu.io.in.fire && !flush.valid && lsu.io.in.bits.uop.isLoad
  io.perf.storeIssue := lsu.io.in.fire && !flush.valid &&
    lsu.io.in.bits.uop.isStore
  io.perf.loadComplete := lsu.io.perf.loadComplete
  io.perf.dcacheHit := lsu.io.perf.dcacheHit
  io.perf.dcacheHitRobIdx := lsu.io.perf.dcacheHitRobIdx
  io.perf.dcacheHitUnderMiss := lsu.io.perf.dcacheHitUnderMiss
  io.perf.dcacheMshrBusy := lsu.io.perf.mshrBusy
  io.perf.dcacheMshrOccupancy := lsu.io.perf.mshrOccupancy
  io.perf.lsuBusy := lsu.io.perf.busy
  io.perf.lsuInputStall := lsu.io.in.valid && !lsu.io.in.ready && !flush.valid
  io.perf.loadReqWait := lsu.io.bus.requ_valid && !lsu.io.bus.requ_ready
  io.perf.loadRespWait := lsu.io.bus.resp_ready && !lsu.io.bus.resp_valid
  io.perf.storeReqWait := lsu.io.storeBus.req_valid &&
    !lsu.io.storeBus.req_ready
  io.perf.sqOccupancy := lsu.io.perf.sqOccupancy
  io.perf.lqOccupancy := lsu.io.perf.lqOccupancy
  io.perf.loadAddressAccepted := lsu.io.perf.loadAddressAccepted
  io.perf.loadAddressPrd := lsu.io.perf.loadAddressPrd
  io.perf.storeBufferLookup := lsu.io.perf.storeBufferLookup
  io.perf.storeBufferLookupPrd := lsu.io.perf.storeBufferLookupPrd
  io.perf.loadBusRequestAccepted := lsu.io.perf.loadBusRequestAccepted
  io.perf.loadBusRequestPrd := lsu.io.perf.loadBusRequestPrd
  io.perf.loadBusResponse := lsu.io.perf.loadBusResponse
  io.perf.loadBusResponsePrd := lsu.io.perf.loadBusResponsePrd
  io.perf.loadScheduledWake := lsu.io.perf.scheduledWakeValid
  io.perf.loadScheduledWakePrd := lsu.io.perf.scheduledWakePrd
  io.perf.loadUseIssue := loadUseIssue
  io.perf.loadUseIssuePc := loadUseIssuePc
  io.perf.loadUseIssuePrd := loadUseIssuePrd
  io.perf.loadUseExecute := loadUseExecute
  io.perf.loadUseExecutePc := loadUseExecutePc
  io.perf.loadUseExecutePrd := loadUseExecutePrd
  for (i <- 0 until numAlu) {
    io.perf.loadUseConsumerPrd(i) := aluIq(i).io.issue.bits.uop.prd
    io.perf.aluLocalWake(i) := aluFastWake(i).valid
    io.perf.aluLocalWakePrd(i) := aluFastWake(i).bits
  }
  io.perf.localFollowerAdvance := localFollowerAdvance
  io.perf.lsuReadyLoadPresent := lsu.io.perf.readyLoadPresent
  io.perf.lsuAguStageValid := lsu.io.perf.aguStageValid
  io.perf.lsuStoreBufferStageValid := lsu.io.perf.storeBufferStageValid
  io.perf.lsuResultStageValid := lsu.io.perf.resultStageValid
  io.perf.lsuBusOwnerValid := lsu.io.perf.busOwnerValid
  io.perf.lsuBusRequestBlocked := lsu.io.perf.busRequestBlocked
  io.perf.lsuOlderStoreBlocked := lsu.io.perf.olderStoreBlocked
  io.perf.dcacheStoreUpdateEnq := lsu.io.perf.dcacheStoreUpdateEnq
  io.perf.dcacheStoreUpdateMerge := lsu.io.perf.dcacheStoreUpdateMerge
  io.perf.dcacheStoreUpdateComplete :=
    lsu.io.perf.dcacheStoreUpdateComplete
  io.perf.dcacheStoreFastHit := lsu.io.perf.dcacheStoreFastHit
  io.perf.dcacheStorePartialHit := lsu.io.perf.dcacheStorePartialHit
  io.perf.dcacheStoreMiss := lsu.io.perf.dcacheStoreMiss
  io.perf.dcacheStoreFifoFull := lsu.io.perf.dcacheStoreFifoFull
  io.perf.dcacheStoreFifoOccupancy :=
    lsu.io.perf.dcacheStoreFifoOccupancy
  io.perf.dcacheLoadRefill := lsu.io.perf.dcacheLoadRefill
  io.perf.dcacheStoreRefill := lsu.io.perf.dcacheStoreRefill
  io.perf.dcacheVictimCapture := lsu.io.perf.dcacheVictimCapture
  io.perf.dcacheWbFullWait := lsu.io.perf.dcacheWbFullWait
  io.perf.dcacheWbOccupancy := lsu.io.perf.dcacheWbOccupancy
  io.perf.dcacheSameLineRawWait := lsu.io.perf.dcacheSameLineRawWait
  io.perf.dcacheSameLineRefillResponse :=
    lsu.io.perf.dcacheSameLineRefillResponse
  io.perf.dcacheStoreRefillBlocked := lsu.io.perf.dcacheStoreRefillBlocked
  io.perf.dcacheStoreRefillDifferentWay :=
    lsu.io.perf.dcacheStoreRefillDifferentWay
  io.perf.dcacheStoreRefillParallel := lsu.io.perf.dcacheStoreRefillParallel
  io.perf.dcacheSameSetFillParallel :=
    lsu.io.perf.dcacheSameSetFillParallel
  io.perf.dcacheWaiterDetached := lsu.io.perf.dcacheWaiterDetached
  io.perf.dcacheMissQueuedBehindEngine :=
    lsu.io.perf.dcacheMissQueuedBehindEngine
  io.perf.dcacheMissQueuedSameLine := lsu.io.perf.dcacheMissQueuedSameLine
  io.perf.dcacheStoreHeadSameSetWait :=
    lsu.io.perf.dcacheStoreHeadSameSetWait
  io.perf.dcacheStoreHeadSameLineWait :=
    lsu.io.perf.dcacheStoreHeadSameLineWait
  io.perf.lsuIssuePc := lsuIq.io.issue.bits.uop.pc
  io.perf.lsuIssueRobIdx := lsuIq.io.issue.bits.uop.robIdx
  io.perf.lsuCdbValid := lsu.io.out.valid
  io.perf.lsuCdbRobIdx := lsu.io.out.bits.robIdx
  io.perf.lsuCdbPrd := lsu.io.out.bits.prd
  io.perf.lsuCdbIsCacheableLoad :=
    lsu.io.out.valid && lsu.io.out.bits.isCacheableLoad

  // ============ 分支预测统计输出 ============
  io.brTotal      := bru.io.brTotal
  io.brMispredict := bru.io.brMispredict
  } else {
    io.brTotal := 0.U
    io.brMispredict := 0.U
    io.instCommit := 0.U
    io.perf := 0.U.asTypeOf(io.perf)
  }

  if (p.enableDifftest) {
    for (i <- 0 until w) {
      prf.io.debugRaddr(i) := retirePacket(i).prd
      io.difftestCommit(i).valid := retirePacket(i).valid
      io.difftestCommit(i).pc := retirePacket(i).pc
      io.difftestCommit(i).inst := retirePacket(i).inst
      io.difftestCommit(i).wen := retirePacket(i).valid &&
        retirePacket(i).writesReg
      io.difftestCommit(i).wdest := retirePacket(i).ldst
      io.difftestCommit(i).wdata := prf.io.debugRdata(i)
    }
    io.difftestStore := lsu.io.difftestStore
    io.difftestLoad := lsu.io.difftestLoad
    io.difftestTrap := lsu.io.difftestTrap
    io.difftestCsr := lsu.io.difftestCsr
    io.difftestTimer := lsu.io.difftestTimer
    io.difftestTlbfillIndex := lsu.io.difftestTlbfillIndex
  } else {
    io.difftestCommit := 0.U.asTypeOf(io.difftestCommit)
    io.difftestStore := 0.U.asTypeOf(io.difftestStore)
    io.difftestLoad := 0.U.asTypeOf(io.difftestLoad)
    io.difftestTrap := 0.U.asTypeOf(io.difftestTrap)
    io.difftestCsr := 0.U.asTypeOf(io.difftestCsr)
    io.difftestTimer := 0.U
    io.difftestTlbfillIndex := 0.U
    for (i <- 0 until w) {
      prf.io.debugRaddr(i) := 0.U
    }
  }
}
