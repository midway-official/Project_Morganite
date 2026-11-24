package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** 取指请求路径上的一级快速预测器。
  *
  * 使用小容量组相联 BTB 和 PC 索引两位方向表，在当拍给出下一 PC。
  */
class FastBPU(implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val pc = Input(UInt(p.xLen.W))
    val nextPc = Output(UInt(p.xLen.W))
    val hit = Output(Bool())
    val taken = Output(Bool())
    val update = Input(new BpuUpdate)
  })

  class FastBtbEntry extends Bundle {
    val tag = UInt(p.l1BtbTagBits.W)
    val slot = UInt(p.slotBits.W)
    val target = UInt(p.xLen.W)
    val conditional = Bool()
    val isReturn = Bool()
  }

  private def group(pc: UInt): UInt =
    pc(p.xLen - 1, p.groupOffsetBits)
  private def btbIdx(pc: UInt): UInt =
    group(pc)(p.l1BtbIdxBits - 1, 0)
  private def btbTag(pc: UInt): UInt =
    group(pc)(p.xLen - p.groupOffsetBits - 1, p.l1BtbIdxBits)
  private def bhtIdx(pc: UInt): UInt =
    pc(p.l1BhtIdxBits + 1, 2)
  private def btbRow(idx: UInt): UInt =
    if (p.l1BtbSets == 2) 0.U(1.W) else idx(p.l1BtbIdxBits - 1, 1)
  private def branchSlot(pc: UInt): UInt =
    pc(p.groupOffsetBits - 1, 2)
  private def nextWay(way: UInt): UInt =
    (way + 1.U)(p.l1BtbWayBits - 1, 0)

  // 两个相邻对齐组分属不同 bank，跨组窗口不复制整张 BTB。
  val btb = Seq.fill(p.l1BtbWays)(Seq.fill(2)(
    Mem(p.l1BtbSets / 2, new FastBtbEntry)))
  val btbValid = RegInit(VecInit(Seq.fill(p.l1BtbWays)(
    VecInit(Seq.fill(p.l1BtbSets)(false.B)))))
  val btbReplacement = RegInit(VecInit(Seq.fill(p.l1BtbSets)(
    0.U(p.l1BtbWayBits.W))))
  val directionTable = Module(new BankedDirectionTable(p.l1BhtEntries, 2, 1,
    reportPhysicalBanks = true))
  directionTable.io.pc := io.pc
  directionTable.io.history := 0.U
  directionTable.io.update := io.update.valid && io.update.isConditional
  directionTable.io.updateIndex := bhtIdx(io.update.pc)
  directionTable.io.taken := io.update.taken
  val rasStack = Reg(Vec(p.rasDepth, UInt(p.xLen.W)))
  val rasPtr = RegInit(0.U(p.rasPtrBits.W))
  val rasEmpty = rasPtr === 0.U
  val rasFull = rasPtr === p.rasDepth.U
  val rasTop = Mux(rasEmpty, 0.U, rasStack(rasPtr - 1.U))

  for (memory <- btb.flatten) {
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(memory.toTarget,
        "ram_style = \"distributed\"")
    })
  }

  val updateBtbIdx = btbIdx(io.update.pc)
  val updateBtbTag = btbTag(io.update.pc)
  val updateBtbSlot = branchSlot(io.update.pc)
  // 训练的 tag 查询/替换决策在本级结束；查询只能旁路已寄存的写事务，
  // 不让退休训练 RAM 读 -> 命中/替换 -> F0 next-PC 串成一条组合路径。
  val writeValid = RegInit(false.B)
  val writeIdx = Reg(UInt(p.l1BtbIdxBits.W))
  val writeWay = Reg(UInt(p.l1BtbWayBits.W))
  val writeEntry = Reg(new FastBtbEntry)
  val updateEntries = VecInit(btb.zipWithIndex.map { case (banks, way) =>
    val resident = VecInit(banks.map(_(btbRow(updateBtbIdx))))(updateBtbIdx(0))
    Mux(writeValid && writeIdx === updateBtbIdx && writeWay === way.U,
      writeEntry, resident)
  })
  val updateValids = VecInit((0 until p.l1BtbWays).map(way =>
    btbValid(way)(updateBtbIdx) ||
      (writeValid && writeIdx === updateBtbIdx && writeWay === way.U)))
  val updateHits = VecInit((0 until p.l1BtbWays).map(way =>
    updateValids(way) &&
      // 同组的不同分支可占用现有不同way，不能让较早的NT分支挡住后面的跳转。
      updateEntries(way).tag === updateBtbTag &&
      updateEntries(way).slot === updateBtbSlot))
  val residentUpdateHit = updateHits.asUInt.orR
  val residentUpdateWay = PriorityEncoder(updateHits)
  val invalidUpdateWays = VecInit((0 until p.l1BtbWays).map(way =>
    !updateValids(way)))
  val updateReplacement = Mux(writeValid && writeIdx === updateBtbIdx,
    nextWay(writeWay), btbReplacement(updateBtbIdx))
  val allocateUpdateWay = Mux(invalidUpdateWays.asUInt.orR,
    PriorityEncoder(invalidUpdateWays), updateReplacement)
  val updateWay = Mux(residentUpdateHit,
    residentUpdateWay, allocateUpdateWay)
  val updateBtb = io.update.valid && io.update.taken
  val newEntry = Wire(new FastBtbEntry)
  newEntry.tag := updateBtbTag
  newEntry.slot := updateBtbSlot
  newEntry.target := io.update.target
  newEntry.conditional := io.update.isConditional
  newEntry.isReturn := io.update.isRet
  writeValid := updateBtb
  when(updateBtb) {
    writeIdx := updateBtbIdx
    writeWay := updateWay
    writeEntry := newEntry
  }
  when(writeValid) {
    for (way <- 0 until p.l1BtbWays) {
      when(writeWay === way.U) {
        for (bank <- 0 until 2) {
          when(writeIdx(0) === bank.U) {
            btb(way)(bank).write(btbRow(writeIdx), writeEntry)
          }
        }
        btbValid(way)(writeIdx) := true.B
      }
    }
    btbReplacement(writeIdx) := nextWay(writeWay)
  }

  val startGroup = group(io.pc)
  val startSlot = branchSlot(io.pc)
  val nextGroupInPage = if (p.groupOffsetBits < 12)
    !io.pc(11, p.groupOffsetBits).andR else false.B
  val sequentialPc = FetchWindow.nextPc(io.pc)
  val bankEntries = btb.map { banks =>
    VecInit((0 until 2).map { bank =>
      val groupRow = btbRow(startGroup(p.l1BtbIdxBits - 1, 0)) +
        (bank.U < startGroup(0)).asUInt
      banks(bank)(if (p.l1BtbSets == 2) 0.U else groupRow(p.l1BtbIdxBits - 2, 0))
    })
  }
  val entries = Wire(Vec(2 * p.l1BtbWays, new FastBtbEntry))
  val entryHits = Wire(Vec(2 * p.l1BtbWays, Bool()))
  for (offset <- 0 until 2; way <- 0 until p.l1BtbWays) {
    val candidate = offset * p.l1BtbWays + way
    val queryGroup = startGroup + offset.U
    val queryIdx = queryGroup(p.l1BtbIdxBits - 1, 0)
    val queryTag = queryGroup(p.xLen - p.groupOffsetBits - 1, p.l1BtbIdxBits)
    val tableEntry = bankEntries(way)(queryGroup(0))
    val bypass = writeValid && writeIdx === queryIdx && writeWay === way.U
    entries(candidate) := Mux(bypass, writeEntry, tableEntry)
    val inWindow = if (offset == 0) entries(candidate).slot >= startSlot
      else entries(candidate).slot < startSlot && nextGroupInPage
    // Compare next to each physical bank. Only the hit bit crosses the
    // bank selector on the FastBPU-to-next-PC path.
    val bankTagMatches = VecInit(bankEntries(way).map(_.tag === queryTag))
    entryHits(candidate) := Mux(bypass, writeEntry.tag === queryTag,
      btbValid(way)(queryIdx) && bankTagMatches(queryGroup(0))) &&
      inWindow
  }
  // 方向位按对齐组内 slot 预旋转，独立于晚到的 BTB 表项。
  val windowDirections = VecInit((0 until p.fetchWidth).map(slot =>
    directionTable.io.valid(slot) && directionTable.io.counters(slot)(1)))
  val alignedDirections = directionTable.io.takenByBank.get
  if (p.enableDifftest) {
    val rotated = (Cat(windowDirections.asUInt, windowDirections.asUInt) <<
      startSlot)(2 * p.fetchWidth - 1, p.fetchWidth)
    assert(alignedDirections === rotated)
  }
  val candidateTaken = VecInit((0 until 2 * p.l1BtbWays).map { candidate =>
    val entry = entries(candidate)
    entryHits(candidate) && (!entry.conditional || alignedDirections(entry.slot))
  })
  val groupTaken = VecInit((0 until 2).map(offset =>
    candidateTaken.asUInt((offset + 1) * p.l1BtbWays - 1, offset * p.l1BtbWays).orR))
  // Compute target payloads and the both-hit priority before the late BTB
  // comparison. The normal two-way bank then selects one of three words.
  val groupTargets = (0 until 2).map { offset =>
    val first = offset * p.l1BtbWays
    val ways = first until first + p.l1BtbWays
    def target(i: Int): UInt =
      Mux(entries(i).isReturn && !rasEmpty, rasTop, entries(i).target)
    if (p.l1BtbWays == 2) {
      val second = first + 1
      val bothTarget = Mux(entries(second).slot < entries(first).slot,
        target(second), target(first))
      val a = candidateTaken(first)
      val b = candidateTaken(second)
      Mux1H(Seq((a && !b) -> target(first), (!a && b) -> target(second),
        (a && b) -> bothTarget))
    } else {
      Mux1H(ways.map { i =>
        val earlier = ways.filter(_ != i).map { j =>
          candidateTaken(j) && (entries(j).slot < entries(i).slot ||
            (entries(j).slot === entries(i).slot && (j < i).B))
        }.foldLeft(false.B)(_ || _)
        (candidateTaken(i) && !earlier) -> target(i)
      })
    }
  }
  // 保留原来的重复 tag+slot 检查；训练、寄存写旁路与页末窗口均未改动。
  for (slot <- 0 until p.fetchWidth) {
    val windowSlot = startSlot +& slot.U
    val hits = VecInit((0 until 2 * p.l1BtbWays).map(candidate =>
      entryHits(candidate) && windowSlot(p.slotBits) === (candidate / p.l1BtbWays).U &&
        entries(candidate).slot === windowSlot(p.slotBits - 1, 0)))
    assert(PopCount(hits) <= 1.U)
  }
  io.hit := entryHits.asUInt.orR
  io.taken := groupTaken.asUInt.orR
  io.nextPc := Mux1H(Seq(
    groupTaken(0) -> groupTargets(0),
    (!groupTaken(0) && groupTaken(1)) -> groupTargets(1),
    !io.taken -> sequentialPc))

  when(io.update.valid && io.update.taken) {
    when(io.update.isRet) {
      when(!rasEmpty) { rasPtr := rasPtr - 1.U }
    }.elsewhen(io.update.isCall) {
      when(!rasFull) {
        rasStack(rasPtr) := io.update.pc + 4.U
        rasPtr := rasPtr + 1.U
      }
    }
  }

  assert(PopCount(updateHits) <= 1.U)
}

/** 二级精确分支预测器。
  *
  * 组合目标表、TAGE、RAS 和循环预测，对一级结果进行校验；
  * 提交时训练表项，误预测恢复时精确修正投机历史。
  */
class BPU(precompareNextPc: Boolean = false)(implicit p: AuroraConfig) extends Module {
  // Preserve the established predictor for direct module verification.
  // The production IFU selects its conditional-direction policy explicitly.
  protected def loopOverrideEnabled: Boolean = true
  val io = IO(new Bundle {
    val groupPc        = Input(UInt(p.xLen.W))
    val lookupPc       = Input(UInt(p.xLen.W))
    val lookupEnable   = Input(Bool())
    val lookupInsts    = Input(Vec(p.fetchWidth, UInt(32.W)))
    val lookupValid    = Input(Vec(p.fetchWidth, Bool()))
    val insts          = Input(Vec(p.coreWidth, UInt(32.W)))
    val slotBeginValid = Input(Vec(p.coreWidth, Bool()))
    val fire           = Input(Bool())

    val predTaken      = Output(Vec(p.coreWidth, Bool()))
    val predTarget     = Output(Vec(p.coreWidth, UInt(p.xLen.W)))
    val predBhtIdx     = Output(Vec(p.coreWidth, UInt(p.bhtIdxBits.W)))
    val predGhr        = Output(Vec(p.coreWidth, UInt(p.ghrBits.W)))
    val predTageTable  = Output(Vec(p.coreWidth, UInt(p.tageTableBits.W)))
    val predTageGhr    = Output(Vec(p.coreWidth, UInt(p.ghrBits.W)))
    val predCtr        = Output(Vec(p.coreWidth, UInt(p.tageCounterBits.W)))
    val slotValid      = Output(Vec(p.coreWidth, Bool()))
    val nextPc         = Output(UInt(p.xLen.W))
    val compareNextPc  = if (precompareNextPc) Some(Input(UInt(p.xLen.W))) else None
    val nextPcMatches  = if (precompareNextPc) Some(Output(Bool())) else None
    val takenInGroup   = Output(Bool())

    val update = Input(new BpuUpdate)
    val repair = Input(Valid(new BpuRepair))
    val flush  = Input(Bool())
  })

  private val w = p.coreWidth
  private val btbIdxBits = p.btbIdxBits

  def BEQ  = BitPat("b010110??????????????????????????")
  def BNE  = BitPat("b010111??????????????????????????")
  def BLT  = BitPat("b011000??????????????????????????")
  def BGE  = BitPat("b011001??????????????????????????")
  def BLTU = BitPat("b011010??????????????????????????")
  def BGEU = BitPat("b011011??????????????????????????")
  def JIRL = BitPat("b010011??????????????????????????")
  def B    = BitPat("b010100??????????????????????????")
  def BL   = BitPat("b010101??????????????????????????")

  def isConditionalInst(inst: UInt): Bool =
    (BEQ === inst) || (BNE === inst) || (BLT === inst) ||
      (BGE === inst) || (BLTU === inst) || (BGEU === inst)

  def isRetInst(inst: UInt): Bool = {
    val si16 = inst(25, 10)
    (JIRL === inst) && inst(4, 0) === 0.U && inst(9, 5) === 1.U && si16 === 0.U
  }

  def isCallInst(inst: UInt): Bool =
    (BL === inst) || ((JIRL === inst) && inst(4, 0) === 1.U)

  def shiftHistory(history: UInt, taken: Bool): UInt = {
    if (p.ghrBits == 1) taken.asUInt else Cat(history(p.ghrBits - 2, 0), taken)
  }

  def bhtIdxWithHistory(pc: UInt, history: UInt): UInt = {
    val pcLow = pc(p.bhtIdxBits + 1, 2)
    val historyExt = if (p.bhtIdxBits == p.ghrBits) history
      else if (p.bhtIdxBits > p.ghrBits)
        Cat(0.U((p.bhtIdxBits - p.ghrBits).W), history)
      else history(p.bhtIdxBits - 1, 0)
    (pcLow ^ historyExt)(p.bhtIdxBits - 1, 0)
  }

  val committedGhr = RegInit(0.U(p.ghrBits.W))
  val speculativeGhr = RegInit(0.U(p.ghrBits.W))
  val speculationStarted = RegInit(false.B)
  val ghrUpdate = io.update.valid && io.update.isConditional
  val ghrAfterUpdate = shiftHistory(committedGhr, io.update.taken)
  val committedHistoryNow = Mux(ghrUpdate, ghrAfterUpdate, committedGhr)
  val lookupHistory = Wire(UInt(p.ghrBits.W))
  when(ghrUpdate) { committedGhr := ghrAfterUpdate }

  val bhtTable = Module(new BankedDirectionTable(p.bhtEntries, 2, 2))
  bhtTable.io.pc := io.lookupPc
  bhtTable.io.history := lookupHistory.pad(p.bhtIdxBits)
  bhtTable.io.update := io.update.valid && io.update.isConditional
  bhtTable.io.updateIndex := io.update.bhtIdx
  bhtTable.io.taken := io.update.taken

  val bhtLookupIdx = RegInit(VecInit(Seq.fill(w)(0.U(p.bhtIdxBits.W))))
  val bhtLookupPred = RegInit(VecInit(Seq.fill(w)(false.B)))
  val bhtLookupValid = RegInit(VecInit(Seq.fill(w)(false.B)))
  val bhtLookupHistory = RegInit(0.U(p.ghrBits.W))
  // The speculative history fans out to every TAGE bank address (measured
  // fo=32 / 1.4 ns on bit 10). Replicate the register near its consumers.
  // Annotate the whole UInt: a bit-select has no stable FIRRTL target.
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(
      bhtLookupHistory.toTarget,
      "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 8")
  })

  val bimodalTable = Module(new BankedDirectionTable(p.bhtEntries, 3, 3))
  bimodalTable.io.pc := io.lookupPc
  bimodalTable.io.history := 0.U
  bimodalTable.io.update := io.update.valid && io.update.isConditional &&
    !io.update.isBackward
  bimodalTable.io.updateIndex := io.update.pc(p.bhtIdxBits + 1, 2)
  bimodalTable.io.taken := io.update.taken

  val bimodalLookupPred = RegInit(VecInit(Seq.fill(w)(false.B)))
  val bimodalLookupValid = RegInit(VecInit(Seq.fill(w)(false.B)))
  val bimodalLookupStrong = RegInit(VecInit(Seq.fill(w)(false.B)))

  private val loopTagBits = p.loopPredTagBits
  private val loopCountMax = ((BigInt(1) << p.loopPredCountBits) - 1).U
  val loopValid = RegInit(VecInit(Seq.fill(p.loopPredEntries)(false.B)))
  val loopTags = Reg(Vec(p.loopPredEntries, UInt(loopTagBits.W)))
  val loopTripCount = RegInit(VecInit(
    Seq.fill(p.loopPredEntries)(0.U(p.loopPredCountBits.W))))
  val loopCommitIter = RegInit(VecInit(
    Seq.fill(p.loopPredEntries)(0.U(p.loopPredCountBits.W))))
  val loopSpecIter = RegInit(VecInit(
    Seq.fill(p.loopPredEntries)(0.U(p.loopPredCountBits.W))))
  val loopConfidence = RegInit(VecInit(Seq.fill(p.loopPredEntries)(0.U(2.W))))
  val loopSpecNext = WireDefault(loopSpecIter)
  // A combinational predicate view, not additional predictor state. Compare
  // each early iteration candidate before the late update/repair selection.
  // This keeps commit-PC tag matching out of the iteration comparator chain.
  val loopSpecNextTaken = Wire(Vec(p.loopPredEntries, Bool()))
  for (entry <- 0 until p.loopPredEntries) {
    loopSpecNextTaken(entry) :=
      loopSpecIter(entry) < (loopTripCount(entry) - 1.U)
  }

  def loopIdx(pc: UInt): UInt = {
    val wordLow = pc(p.loopPredIdxBits + 1, 2)
    val wordFold = pc(2 * p.loopPredIdxBits + 1, p.loopPredIdxBits + 2)
    wordLow ^ wordFold
  }
  def loopTag(pc: UInt): UInt = pc(p.xLen - 1, p.loopPredIdxBits + 2)
  def satLoopInc(value: UInt): UInt = Mux(value === loopCountMax,
    value, value + 1.U)

  val loopUpdateEnable = io.update.valid && io.update.isConditional &&
    io.update.isBackward
  val loopUpdateIdx = loopIdx(io.update.pc)
  val loopUpdateHit = loopValid(loopUpdateIdx) &&
    loopTags(loopUpdateIdx) === loopTag(io.update.pc)
  val loopObservedTrip = satLoopInc(loopCommitIter(loopUpdateIdx))
  val loopCommitIterAfterUpdate = Mux(!loopUpdateHit,
    Mux(io.update.taken, 1.U, 0.U),
    Mux(io.update.taken, satLoopInc(loopCommitIter(loopUpdateIdx)), 0.U))

  when(loopUpdateEnable) {
    when(!loopUpdateHit) {
      loopValid(loopUpdateIdx) := true.B
      loopTags(loopUpdateIdx) := loopTag(io.update.pc)
      loopTripCount(loopUpdateIdx) := Mux(io.update.taken, 0.U, 1.U)
      loopCommitIter(loopUpdateIdx) := Mux(io.update.taken, 1.U, 0.U)
      loopConfidence(loopUpdateIdx) := Mux(io.update.taken, 0.U, 1.U)
    }.elsewhen(io.update.taken) {
      loopCommitIter(loopUpdateIdx) := satLoopInc(loopCommitIter(loopUpdateIdx))
      when(loopCommitIter(loopUpdateIdx) >= loopCountMax - 1.U) {
        loopTripCount(loopUpdateIdx) := 0.U
        loopConfidence(loopUpdateIdx) := 0.U
      }
    }.elsewhen(loopCommitIter(loopUpdateIdx) === loopCountMax) {
      // 饱和观察不是精确 trip count；精确 max 次循环只有 max-1 次 taken。
      loopCommitIter(loopUpdateIdx) := 0.U
      loopTripCount(loopUpdateIdx) := 0.U
      loopConfidence(loopUpdateIdx) := 0.U
    }.otherwise {
      loopCommitIter(loopUpdateIdx) := 0.U
      when(loopTripCount(loopUpdateIdx) === loopObservedTrip) {
        when(loopConfidence(loopUpdateIdx) =/= 3.U) {
          loopConfidence(loopUpdateIdx) := loopConfidence(loopUpdateIdx) + 1.U
        }
      }.elsewhen(loopConfidence(loopUpdateIdx) === 0.U) {
        loopTripCount(loopUpdateIdx) := loopObservedTrip
        loopConfidence(loopUpdateIdx) := 1.U
      }.otherwise {
        loopConfidence(loopUpdateIdx) := loopConfidence(loopUpdateIdx) - 1.U
      }
    }
  }

  val loopLookupHit = RegInit(VecInit(Seq.fill(w)(false.B)))
  val loopLookupIdx = Reg(Vec(w, UInt(p.loopPredIdxBits.W)))
  val loopLookupIter = Reg(Vec(w, UInt(p.loopPredCountBits.W)))
  val loopLookupUsable = RegInit(VecInit(Seq.fill(w)(false.B)))
  val loopLookupTaken = Reg(Vec(w, Bool()))

  // All candidate histories are available from registered state before
  // the current F2 direction/cutoff arrives. Only the final selection is late.
  val tageHistoryChoices = Wire(Vec(2 * w + 2, UInt(p.ghrBits.W)))
  val tageHistorySelect = Wire(UInt((2 * w + 2).W))
  val tage = Module(new ParallelHistoryTage(2 * w + 2, selectDefaultAfterQuery = false,
    withFallback = true, parallelQueries = true))
  tage.io.lookupPc := io.lookupPc
  tage.io.historyChoices := tageHistoryChoices
  tage.io.historySelect := tageHistorySelect
  tage.io.lookupEnable := io.lookupEnable
  tage.io.lookupFallbackTaken.get := VecInit(io.lookupInsts.map(inst => inst(25)))
  tage.io.update := io.update
  tage.io.flush := io.flush

  val targetTable = Module(new IndirectTargetTable)
  val lookupIndirect = VecInit((0 until w).map(slot =>
    io.lookupValid(slot) && io.lookupInsts(slot)(31, 26) === "b010011".U))
  targetTable.io.slot := PriorityEncoder(lookupIndirect)
  targetTable.io.pc := io.lookupPc
  targetTable.io.enable := io.lookupEnable
  targetTable.io.update := io.update

  val pcs = Wire(Vec(w, UInt(p.xLen.W)))
  // 条件分支译码随 F1 接受的窗口寄存，与 bpBits/TAGE 结果同拍到达 F2。
  // 历史旁路不再从 F2 指令位重新译码后才驱动下一窗口的读表地址。
  val condVec = RegEnable(VecInit(io.lookupInsts.map(isConditionalInst)),
    io.lookupEnable)
  // 所有分支语义随既有 F1 接受沿保存，F2 只选择对齐后的标志。
  // 独立 flags 避免 kind 编码后在历史反馈路径重新译码；优先级保持。
  val directVec = RegEnable(VecInit(io.lookupInsts.map(inst =>
    (B === inst) || (BL === inst))), io.lookupEnable)
  val jirlVec = RegEnable(VecInit(io.lookupInsts.map(inst => JIRL === inst)),
    io.lookupEnable)
  val retVec = RegEnable(VecInit(io.lookupInsts.map(isRetInst)), io.lookupEnable)
  val callVec = RegEnable(VecInit(io.lookupInsts.map(isCallInst)), io.lookupEnable)
  val backwardVec = RegEnable(VecInit(io.lookupInsts.map(inst => inst(25))),
    io.lookupEnable)
  for (i <- 0 until w) {
    pcs(i) := io.groupPc + (i * 4).U
  }

  // F1 在接受沿保存静态目标和顺序末地址，F2 仅选择已对齐的目标；
  // 背压期间与预测窗口一起保持，避免相对地址加法串入修正反馈。
  val relativeTargets = RegEnable(VecInit((0 until w).map { slot =>
    val lookupPc = io.lookupPc + (slot * p.instructionBytes).U
    val inst = io.lookupInsts(slot)
    val si16 = inst(25, 10)
    val offs16 = Cat(Fill(p.xLen - 18, si16(15)), si16, 0.U(2.W))
    val offs26 = Cat(Fill(p.xLen - 28, inst(9)),
      inst(9, 0), inst(25, 10), 0.U(2.W))
    lookupPc + Mux((B === inst) || (BL === inst), offs26, offs16)
  }), io.lookupEnable)
  val fallthroughTargets = RegEnable(VecInit((0 until w).map(slot =>
    io.lookupPc + (slot * p.instructionBytes + 4).U)), io.lookupEnable)
  val sequentialPc = RegEnable(
    io.lookupPc + (PopCount(io.lookupValid) << 2), io.lookupEnable)

  when(io.lookupEnable) {
    bhtLookupHistory := lookupHistory
  }

  for (i <- 0 until w) {
    val lookupPc = io.lookupPc + (i * p.instructionBytes).U
    val directionIdx = bhtIdxWithHistory(lookupPc, lookupHistory)
    val currentLoopIdx = loopIdx(lookupPc)
    val currentLoopHit = loopValid(currentLoopIdx) &&
      loopTags(currentLoopIdx) === loopTag(lookupPc)
    val currentLoopTrip = loopTripCount(currentLoopIdx)
    val currentLoopUsable = currentLoopHit &&
      loopConfidence(currentLoopIdx)(1) && currentLoopTrip =/= 0.U
    val currentLoopTaken = Mux1H(UIntToOH(currentLoopIdx, p.loopPredEntries),
      loopSpecNextTaken)

    when(io.lookupEnable) {
      bhtLookupIdx(i) := directionIdx
      bhtLookupPred(i) := bhtTable.io.counters(i)(1)
      bhtLookupValid(i) := bhtTable.io.valid(i)
      val localCntNow = bimodalTable.io.counters(i)
      bimodalLookupPred(i) := localCntNow(2)
      bimodalLookupValid(i) := bimodalTable.io.valid(i)
      bimodalLookupStrong(i) := localCntNow <= 1.U || localCntNow >= 6.U
      loopLookupHit(i) := currentLoopHit
      loopLookupIdx(i) := currentLoopIdx
      loopLookupIter(i) := loopSpecNext(currentLoopIdx)
      loopLookupUsable(i) := currentLoopUsable &&
        isConditionalInst(io.lookupInsts(i)) && io.lookupInsts(i)(25)
      loopLookupTaken(i) := currentLoopTaken
    }
  }

  val rasStack = Reg(Vec(p.rasDepth, UInt(p.xLen.W)))
  val rasPtr = RegInit(0.U(p.rasPtrBits.W))
  val rasEmpty = rasPtr === 0.U
  val rasFull = rasPtr === p.rasDepth.U
  val rasTop = Mux(rasEmpty, 0.U, rasStack(rasPtr - 1.U))
  // 已提交影子栈：只由退休的 call/ret 更新。恢复时用它重建投机栈，
  // 再按正在解析的分支动作修正，避免 recovery 清空 RAS 后返回退化为 PC+4。
  val committedRasStack = Reg(Vec(p.rasDepth, UInt(p.xLen.W)))
  val committedRasPtr = RegInit(0.U(p.rasPtrBits.W))
  val committedRasPush = io.update.valid && io.update.isCall &&
    !io.update.isRet && committedRasPtr < p.rasDepth.U
  val committedRasPop = io.update.valid && io.update.isRet &&
    committedRasPtr =/= 0.U
  when(committedRasPush) {
    committedRasStack(committedRasPtr) := io.update.pc + 4.U
    committedRasPtr := committedRasPtr + 1.U
  }.elsewhen(committedRasPop) {
    committedRasPtr := committedRasPtr - 1.U
  }

  for (i <- 0 until w) {
    // Select an already registered confidence predicate at elaboration.
    // The legacy option preserves both strong directions; the default keeps
    // strong taken while allowing a learned loop to correct strong NT.
    val keepTageDirection = if (p.loopRespectStrongNotTaken)
      tage.io.predStrongTagged(i) else tage.io.predStrongTaken(i)
    val loopUsable = loopLookupUsable(i) &&
      !(p.loopRespectStrongTage.B && keepTageDirection)
    val tageDirection = tage.io.predWithFallback.get(i)
    val directionTaken = if (loopOverrideEnabled)
      Mux(loopUsable, loopLookupTaken(i), tageDirection) else tageDirection

    val taken = WireDefault(false.B)
    val target = WireDefault(fallthroughTargets(i))
    when(retVec(i)) {
      taken := true.B
      target := Mux(!rasEmpty, rasTop,
        Mux(targetTable.io.hit(i), targetTable.io.target(i), fallthroughTargets(i)))
    }.elsewhen(directVec(i)) {
      taken := true.B
      target := relativeTargets(i)
    }.elsewhen(condVec(i)) {
      taken := directionTaken
      target := Mux(directionTaken, relativeTargets(i), fallthroughTargets(i))
    }.elsewhen(jirlVec(i)) {
      taken := true.B
      target := Mux(targetTable.io.hit(i), targetTable.io.target(i), fallthroughTargets(i))
    }

    io.predTaken(i) := taken
    io.predTarget(i) := target
    // 保留旧表查询信号用于性能探针/调试；方向选择已不使用它们。
    io.predBhtIdx(i) := bhtLookupIdx(i) ^ Cat(
      bhtLookupValid(i), bhtLookupPred(i), bimodalLookupValid(i),
      bimodalLookupStrong(i), bimodalLookupPred(i), 0.U(2.W))
    io.predTageTable(i) := tage.io.predTable(i)
    io.predTageGhr(i) := bhtLookupHistory
    io.predCtr(i) := tage.io.predCtr(i)
  }

  val takenVec = Wire(Vec(w, Bool()))
  for (i <- 0 until w) {
    takenVec(i) := io.slotBeginValid(i) && io.predTaken(i)
  }
  val anyTaken = takenVec.asUInt.orR
  // Keep the first-taken decision one-hot through target selection. The
  // priority encoder plus dynamic target index adds a serial control path from
  // TAGE direction to the fetch PC; the one-hot mask can select each target
  // locally and combine the selected words in parallel.
  val cutoffTaken = VecInit((0 until w).map { slot =>
    val earlierTaken = takenVec.take(slot).foldLeft(false.B)(_ || _)
    takenVec(slot) && !earlierTaken
  })
  val firstTaken = PriorityEncoder(takenVec)
  for (i <- 0 until w) {
    io.slotValid(i) := io.slotBeginValid(i) && (!anyTaken || i.U <= firstTaken)
  }

  val historyBeforeSlot = Wire(Vec(w, UInt(p.ghrBits.W)))
  var historyCursor = bhtLookupHistory
  for (i <- 0 until w) {
    historyBeforeSlot(i) := historyCursor
    historyCursor = Mux(io.slotValid(i) && condVec(i),
      shiftHistory(historyCursor, io.predTaken(i)), historyCursor)
    io.predGhr(i) := historyBeforeSlot(i)
  }
  // 条件分支前缀的计数/移位只依赖已寄存的有效位、译码和旧历史，
  // 提前并行计算。晚到的 taken 只选择最早截断位置，不进入 PopCount。
  // 逐槽 predGhr 仍保持上面的参考前缀，F2 fire/flush/repair 所有权不变。
  val beginConditional = VecInit((0 until w).map(i =>
    io.slotBeginValid(i) && condVec(i)))
  // The accepted window's valid bits and conditional decode are known at the
  // same F1 edge as condVec. Capture one-hot shift amounts so F2 can select
  // shifted histories without decoding a binary count before the TAGE RAM.
  // Hold under backpressure exactly like the accepted instruction window.
  val lookupConditional = VecInit((0 until w).map(i =>
    io.lookupValid(i) && isConditionalInst(io.lookupInsts(i))))
  val prefixShiftOH = (1 to w).map(end =>
    RegEnable(UIntToOH(PopCount(lookupConditional.take(end)), end + 1),
      io.lookupEnable))
  when(io.fire) {
    for (end <- 1 to w) {
      assert(prefixShiftOH(end - 1) ===
        UIntToOH(PopCount(beginConditional.take(end)), end + 1))
    }
  }
  val prefixShift = prefixShiftOH.zipWithIndex.map { case (shiftOH, index) =>
    Mux1H((0 to index + 1).map(k =>
      shiftOH(k) -> (bhtLookupHistory << k)(p.ghrBits - 1, 0)))
  }
  val prefixHasConditional = prefixShiftOH.map(shiftOH => !shiftOH(0))
  // 有效 taken 条件分支是该前缀最后一个条件分支；追加位仅为 condVec。
  // taken 非条件分支只截断，不插入历史位。无 taken 时选择完整零方向前缀。
  val speculativeHistoryAfterGroup = Mux1H(
    (0 until w).map(i =>
      cutoffTaken(i) -> (prefixShift(i) | condVec(i).asUInt)) :+
      (!anyTaken -> prefixShift(w - 1)))
  val groupHasConditional = Mux1H(
    (0 until w).map(i => cutoffTaken(i) -> prefixHasConditional(i)) :+
      (!anyTaken -> prefixHasConditional(w - 1)))

  val defaultLookupHistory = Mux(!speculationStarted && ghrUpdate,
    ghrAfterUpdate, speculativeGhr)
  lookupHistory := Mux(io.fire && groupHasConditional,
    speculativeHistoryAfterGroup, defaultLookupHistory)
  // Query every possible count/direction history with static shifts. The
  // previous window's prefix count and late cutoff only select registered
  // predictions; neither enters a RAM address or tag-comparison datapath.
  // No zero-count advanced query can be selected: advancement requires at
  // least one conditional. Default and advanced views share one training
  // image and one registered selection instead of two serial result muxes.
  for (count <- 1 to w) {
    tageHistoryChoices(count - 1) := (bhtLookupHistory << count)(p.ghrBits - 1, 0)
  }
  for (count <- 1 to w) {
    tageHistoryChoices(w + count - 1) :=
      ((bhtLookupHistory << count) | 1.U)(p.ghrBits - 1, 0)
  }
  // Query both default histories alongside advanced histories. Retirement
  // eligibility now selects the sampled prediction, outside the RAM address.
  tageHistoryChoices(2 * w) := speculativeGhr
  tageHistoryChoices(2 * w + 1) := ghrAfterUpdate
  val zeroHistoryChoices = (1 to w).map { count =>
    val selectedPrefix = (0 until w).map { slot =>
      val countMatches = if (count <= slot + 1) prefixShiftOH(slot)(count) else false.B
      cutoffTaken(slot) && !condVec(slot) && countMatches
    }.foldLeft(false.B)(_ || _)
    selectedPrefix || (!anyTaken && prefixShiftOH(w - 1)(count))
  }
  val takenHistoryChoices = (1 to w).map { count =>
    (0 until w).map { slot =>
      val countMatches = if (count <= slot + 1) prefixShiftOH(slot)(count) else false.B
      cutoffTaken(slot) && condVec(slot) && countMatches
    }.foldLeft(false.B)(_ || _)
  }
  val earlyTageHistorySelect = VecInit(zeroHistoryChoices ++ takenHistoryChoices).asUInt
  val useAdvancedHistory = io.fire && groupHasConditional
  val useCommitHistory = !speculationStarted && ghrUpdate
  tageHistorySelect := Cat(!useAdvancedHistory && useCommitHistory,
    !useAdvancedHistory && !useCommitHistory,
    Mux(useAdvancedHistory, earlyTageHistorySelect, 0.U((2 * w).W)))
  when(io.lookupEnable) {
    assert(PopCount(tageHistorySelect) === 1.U)
    assert(Mux1H(tageHistorySelect, tageHistoryChoices) === lookupHistory)
  }

  when(io.repair.valid) {
    speculativeGhr := io.repair.bits.history
    speculationStarted := true.B
  }.elsewhen(io.flush) {
    speculativeGhr := committedHistoryNow
    speculationStarted := false.B
  }.elsewhen(io.fire && groupHasConditional) {
    speculativeGhr := speculativeHistoryAfterGroup
    speculationStarted := true.B
  }.elsewhen(!speculationStarted && ghrUpdate) {
    speculativeGhr := ghrAfterUpdate
  }

  // 同拍旧窗口前进、新窗口查询同一循环时，查询看到前进后的投机次数。
  when(io.flush) {
    for (entry <- 0 until p.loopPredEntries) {
      val updatedHere = loopUpdateEnable && loopUpdateIdx === entry.U
      val limit = loopTripCount(entry) - 1.U
      val localHit = loopValid(entry) && loopTags(entry) === loopTag(io.update.pc)
      val committedInc = satLoopInc(loopCommitIter(entry))
      val updatedIter = Mux(io.update.taken,
        Mux(localHit, committedInc, 1.U), 0.U)
      loopSpecNext(entry) := Mux(updatedHere, updatedIter, loopCommitIter(entry))
      val updatedTaken = Mux(io.update.taken,
        Mux(localHit, committedInc < limit, 1.U < limit), 0.U < limit)
      loopSpecNextTaken(entry) := Mux(updatedHere,
        updatedTaken, loopCommitIter(entry) < limit)
    }
  }.elsewhen(io.fire) {
    for (i <- 0 until w) {
      when(io.slotValid(i) && condVec(i) && loopLookupHit(i)) {
        loopSpecNext(loopLookupIdx(i)) := Mux(io.predTaken(i),
          satLoopInc(loopLookupIter(i)), 0.U)
        for (entry <- 0 until p.loopPredEntries) {
          when(loopLookupIdx(i) === entry.U) {
            val limit = loopTripCount(entry) - 1.U
            loopSpecNextTaken(entry) := Mux(io.predTaken(i),
              satLoopInc(loopLookupIter(i)) < limit, 0.U < limit)
          }
        }
      }
    }
  }
  when(io.repair.valid && io.repair.bits.isConditional &&
      io.repair.bits.isBackward) {
    val repairIdx = loopIdx(io.repair.bits.pc)
    when(loopValid(repairIdx) && loopTags(repairIdx) === loopTag(io.repair.bits.pc)) {
      loopSpecNext(repairIdx) := Mux(io.repair.bits.actualTaken,
        satLoopInc(loopCommitIter(repairIdx)), 0.U)
    }
    for (entry <- 0 until p.loopPredEntries) {
      when(repairIdx === entry.U && loopValid(entry) &&
          loopTags(entry) === loopTag(io.repair.bits.pc)) {
        val limit = loopTripCount(entry) - 1.U
        loopSpecNextTaken(entry) := Mux(io.repair.bits.actualTaken,
          satLoopInc(loopCommitIter(entry)) < limit, 0.U < limit)
      }
    }
  }
  loopSpecIter := loopSpecNext
  io.takenInGroup := anyTaken
  // Select each kind of next PC directly. The indirect-table tag match
  // gates a single shared target alongside the RAS and per-slot targets.
  val relativeSelected = VecInit((0 until w).map(i =>
    cutoffTaken(i) && (directVec(i) || condVec(i))))
  val rasSelected = VecInit((0 until w).map(i =>
    cutoffTaken(i) && retVec(i) && !rasEmpty))
  val indirectSelected = VecInit((0 until w).map(i =>
    cutoffTaken(i) && !relativeSelected(i) && !rasSelected(i)))
  val tableSelected = (indirectSelected.asUInt & targetTable.io.hit.asUInt).orR
  val relativeSelections = (0 until w).map(i =>
    relativeSelected(i) -> relativeTargets(i))
  val fallthroughSelections = (0 until w).map(i =>
    (indirectSelected(i) && !targetTable.io.hit(i)) -> fallthroughTargets(i))
  val nextPcChoices = relativeSelections ++ fallthroughSelections ++ Seq(
    tableSelected -> targetTable.io.target(0),
    rasSelected.asUInt.orR -> rasTop, !anyTaken -> sequentialPc)
  io.nextPc := Mux1H(nextPcChoices)
  io.nextPcMatches.foreach { matches =>
    val expected = io.compareNextPc.get
    // Equality settles beside the target word. Late direction and cutoff
    // select a Boolean for redirect rather than starting a 32-bit compare.
    matches := nextPcChoices.map { case (selected, target) =>
      selected && target === expected
    }.reduce(_ || _)
    when(io.fire) { assert(matches === (io.nextPc === expected)) }
  }
  when(io.fire) {
    assert(io.nextPc === Mux1H(
      (0 until w).map(i => cutoffTaken(i) -> io.predTarget(i)) :+
        (!anyTaken -> sequentialPc)))
  }

  val rawCallVec = VecInit((0 until w).map(i =>
    io.slotBeginValid(i) && callVec(i)))
  val callValidVec = VecInit((0 until w).map(i =>
    io.slotValid(i) && callVec(i)))
  val retValidVec = VecInit((0 until w).map(i =>
    io.slotValid(i) && retVec(i)))
  val anyCall = callValidVec.asUInt.orR
  val anyRet = retValidVec.asUInt.orR
  val rawCallPc = pcs(PriorityEncoder(rawCallVec))

  when(io.fire && anyCall && !rasFull && !io.flush) {
    rasStack(rasPtr) := rawCallPc + 4.U
  }
  // 恢复：先回到已提交影子栈，再应用正在解析分支的 call/ret 动作。
  when(io.repair.valid) {
    rasStack := committedRasStack
    rasPtr := committedRasPtr
    when(io.repair.bits.isCall && !io.repair.bits.isRet &&
        committedRasPtr < p.rasDepth.U) {
      rasStack(committedRasPtr) := io.repair.bits.pc + 4.U
      rasPtr := committedRasPtr + 1.U
    }.elsewhen(io.repair.bits.isRet && committedRasPtr =/= 0.U) {
      rasPtr := committedRasPtr - 1.U
    }
  }.elsewhen(io.flush) {
    rasStack := committedRasStack
    rasPtr := committedRasPtr
  }.elsewhen(io.fire && anyRet) {
    when(!rasEmpty) { rasPtr := rasPtr - 1.U }
  }.elsewhen(io.fire && anyCall) {
    when(!rasFull) { rasPtr := rasPtr + 1.U }
  }
}

/** Same lookup edge, history/training and recovery; TAGE owns conditional
  * direction. Synthesis can remove unused loop iteration/confidence state.
  * Performance is measured against the accepted K model.
  */
class TageOnlyBPU(precompareNextPc: Boolean = false)(implicit p: AuroraConfig)
    extends BPU(precompareNextPc) {
  override protected def loopOverrideEnabled: Boolean = false
}
