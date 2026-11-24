package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** Static identities, resolved only at elaboration against the execution topology. */
sealed trait IqProducer {
  def cdbLane(p: AuroraConfig): Int
}
object IqProducer {
  final case class Alu(index: Int) extends IqProducer {
    def cdbLane(p: AuroraConfig): Int = {
      require(index >= 0 && index < p.numAlu, "unknown ALU producer")
      index
    }
  }
  case object Branch extends IqProducer {
    def cdbLane(p: AuroraConfig): Int = p.bruCdbLane
  }
  case object Load extends IqProducer {
    def cdbLane(p: AuroraConfig): Int = p.lsuCdbLane
  }
  final case class Divide(index: Int) extends IqProducer {
    def cdbLane(p: AuroraConfig): Int = {
      require(index >= 0 && index < p.numDiv, "unknown divide producer")
      p.divCdbBase + index
    }
  }
}

sealed trait IqTarget
object IqTarget {
  final case class Alu(index: Int) extends IqTarget
  case object Branch extends IqTarget
  final case class Divide(index: Int) extends IqTarget
}

sealed trait IqAllocation
object IqAllocation {
  case object Bitmap extends IqAllocation
  case object FreeIndex extends IqAllocation
}

sealed trait FastResultKind
object FastResultKind {
  case object SimpleAlu extends FastResultKind
  case object Multiply extends FastResultKind
}

sealed trait CdbForwardTiming
object CdbForwardTiming {
  case object SameCycle extends CdbForwardTiming
  case object ScheduledLoad extends CdbForwardTiming
}

final case class FastInputRoute(
    producer: IqProducer.Alu,
    kind: FastResultKind = FastResultKind.SimpleAlu,
    sources: Set[Int] = Set(0, 1))

final case class CdbForwardRoute(
    producer: IqProducer,
    timing: CdbForwardTiming = CdbForwardTiming.SameCycle,
    sources: Set[Int] = Set(0, 1))

/** One dual-value, oldest-ready, single-issue non-memory queue. */
final case class GenIqParams(
    depth: Int,
    enqWidth: Int,
    allocation: IqAllocation = IqAllocation.Bitmap,
    cdbForward: Seq[CdbForwardRoute] = Seq.empty,
    fastInputs: Seq[FastInputRoute] = Seq.empty) {
  require(depth >= 2, "IQ depth must be at least two")
  require(enqWidth >= 1 && enqWidth <= depth, "invalid IQ enqueue width")
  require(allocation != IqAllocation.FreeIndex || (depth & (depth - 1)) == 0,
    "free-index allocation requires a power-of-two depth")
  private def checkSources(sources: Set[Int]): Unit =
    require(sources.nonEmpty && sources.subsetOf(Set(0, 1)), "invalid IQ source mask")
  cdbForward.foreach(r => checkSources(r.sources))
  fastInputs.foreach(r => checkSources(r.sources))
  private val cdbKeys = cdbForward.flatMap(r => r.sources.map(s => (r.producer, s)))
  private val fastKeys = fastInputs.flatMap(r => r.sources.map(s => (r.producer, r.kind, s)))
  require(cdbKeys.distinct.size == cdbKeys.size, "duplicate CDB forwarding route")
  require(fastKeys.distinct.size == fastKeys.size, "duplicate fast forwarding route")
  cdbForward.foreach { r =>
    require((r.producer == IqProducer.Load) ==
      (r.timing == CdbForwardTiming.ScheduledLoad), "load forwarding needs scheduled alignment")
  }

  // Preserve lowest ALU lane priority, regardless of declaration order.
  val fastRoutes: Seq[FastInputRoute] = fastInputs.sortBy(r =>
    (r.producer.index, if (r.kind == FastResultKind.SimpleAlu) 0 else 1))

  def validate(p: AuroraConfig): Unit = {
    require(enqWidth <= p.coreWidth, "IQ enqueue width exceeds dispatch width")
    cdbForward.foreach(_.producer.cdbLane(p))
    fastInputs.foreach { r =>
      r.producer.cdbLane(p)
      require(r.kind != FastResultKind.Multiply || p.allowAluMultiplyForward,
        "multiply fast route requires a matching producer wake pipeline")
    }
  }
}

object GenIqParams {
  def cdbRoutes(p: AuroraConfig): Seq[CdbForwardRoute] =
    (0 until p.numAlu).map(a => CdbForwardRoute(IqProducer.Alu(a))) :+
      CdbForwardRoute(IqProducer.Load, CdbForwardTiming.ScheduledLoad)

  def aluFastRoutes(p: AuroraConfig, multiply: Boolean): Seq[FastInputRoute] =
    (0 until p.numAlu).flatMap { a =>
      Seq(FastInputRoute(IqProducer.Alu(a))) ++
        (if (multiply) Seq(FastInputRoute(IqProducer.Alu(a), FastResultKind.Multiply))
         else Seq.empty)
    }

  def alu(p: AuroraConfig): GenIqParams = GenIqParams(
    p.aluIqDepth, p.aluIqEnqWidth, IqAllocation.FreeIndex,
    cdbRoutes(p), aluFastRoutes(p, p.allowAluMultiplyForward))

  def branch(p: AuroraConfig): GenIqParams = GenIqParams(
    p.bruIqDepth, p.bruIqEnqWidth, IqAllocation.Bitmap,
    cdbRoutes(p), if (p.allowBruSimpleForward) aluFastRoutes(p, false) else Seq.empty)

  def divide(p: AuroraConfig): GenIqParams = GenIqParams(
    p.divIqDepth, p.divIqEnqWidth, IqAllocation.Bitmap,
    // A load CDB hit still captures the operand and marks the IQ source ready.
    // Dividers may wait one extra cycle and issue from the stored operand,
    // avoiding a same-cycle LSU-to-divider CDB bypass on this long control path.
    (0 until p.numAlu).map(a => CdbForwardRoute(IqProducer.Alu(a))))
}

/** Dual-value, oldest-ready queue for every non-memory execution lane. */
class GenIQ(val params: GenIqParams, prepareMultiply: Boolean = false,
            useRegisteredLoadKind: Boolean = false,
            prepareAluControl: Boolean = false, prequalifiedLoadCdb: Boolean = false,
            splitLsuData: Boolean = false)
    (implicit p: AuroraConfig) extends Module {
  params.validate(p)
  require(!prepareAluControl || prepareMultiply)
  require(!prequalifiedLoadCdb || useRegisteredLoadKind)
  private val depth = params.depth
  private val numEnq = params.enqWidth
  private val numCdb = p.numCdb
  private val useFreeIndexQueue = params.allocation == IqAllocation.FreeIndex
  val fastRoutes = params.fastRoutes
  private val fastCount = fastRoutes.size
  private val fastMaskBits = fastCount max 1
  private val enqCountBits = log2Ceil(numEnq + 1)
  private val countBits = log2Ceil(depth + 1)
  private val slotBits = log2Ceil(depth)

  val io = IO(new Bundle {
    val enqCount = Input(UInt(enqCountBits.W))
    val enqBits = Input(Vec(numEnq, new ExecIn))
    val secondSourceLsuData = if (splitLsuData) Some(Input(UInt(p.xLen.W))) else None
    val enqReady = Output(Bool())
    val enqCapacity = Output(UInt(enqCountBits.W))
    val freeSlots = Output(UInt(countBits.W))
    val occupancy = Output(UInt(countBits.W))
    val cdb = Input(Vec(numCdb, new CdbEntry))
    val loadScheduledWake = Input(Valid(new WakeEvent))
    val loadCdbIsLoad = if (useRegisteredLoadKind) Some(Input(Bool())) else None
    val qualifiedLoadCdb = if (prequalifiedLoadCdb) Some(Input(Bool())) else None
    val fastWake = Input(Vec(fastCount, Valid(UInt(p.physRegBits.W))))
    val fastResult = Input(Vec(fastCount, Valid(new FastAluResult)))
    val issue = Decoupled(new IntegerIssue)
    val preparedMultiply = if (prepareMultiply) Some(Output(new ExecIn)) else None
    val preparedMultiplyUnsigned = if (prepareMultiply) Some(Output(Bool())) else None
    val issueAluOpOH = if (prepareAluControl) Some(Output(UInt(UopConst.ALU_OP_NUM.W))) else None
    val issueIsMultiply = if (prepareAluControl) Some(Output(Bool())) else None
    val issueSimpleB = if (prepareAluControl) Some(Output(UInt(p.xLen.W))) else None
    val simpleAluWake = Output(Bool())
    val multiplyAluWake = Output(Bool())
    val flush = Input(Bool())
    val recover = Input(Valid(UInt(p.robIdxBits.W)))
    val robHeadIdx = Input(UInt(p.robIdxBits.W))
  })

  // The uop owns the only registered source-ready bits. Values have independent
  // write enables; transient fast availability never modifies those bits.
  val entries = Reg(Vec(depth, new ExecIn))
  val entryIsMultiply = if (prepareMultiply) Some(Reg(Vec(depth, Bool()))) else None
  val entryMultiplyUnsigned = if (prepareMultiply) Some(Reg(Vec(depth, Bool()))) else None
  val entryAluOpOH = if (prepareAluControl) Some(Reg(Vec(depth, UInt(UopConst.ALU_OP_NUM.W)))) else None
  val valids = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val olderThan = RegInit(VecInit(Seq.fill(depth)(VecInit(Seq.fill(depth)(false.B)))))
  val freeCount = RegInit(depth.U(countBits.W))
  val flushCountPending = RegNext(io.flush, false.B)
  val recoverApplyPending = RegInit(false.B)
  val recoverRebuildPending = RegInit(false.B)
  when(io.flush) {
    recoverApplyPending := false.B
    recoverRebuildPending := false.B
  }.otherwise {
    recoverApplyPending := io.recover.valid
    recoverRebuildPending := recoverApplyPending
  }
  val recoveryBusy = io.recover.valid || recoverApplyPending || recoverRebuildPending
  io.freeSlots := freeCount
  io.occupancy := depth.U - freeCount
  val enqCount = io.enqCount
  io.enqCapacity := Mux(recoveryBusy, 0.U,
    Mux(freeCount >= numEnq.U, numEnq.U, freeCount))
  io.enqReady := freeCount >= enqCount && !io.flush && !recoveryBusy
  when(!io.flush && !recoveryBusy) {
    assert(enqCount <= numEnq.U)
    assert(enqCount <= freeCount)
  }

  private val loadMatchContexts = depth + numEnq
  val localLoadValid = if (!useRegisteredLoadKind)
    Some(RegInit(VecInit(Seq.fill(loadMatchContexts)(false.B)))) else None
  val localLoadPrd = if (!useRegisteredLoadKind)
    Some(Reg(Vec(loadMatchContexts, UInt(p.physRegBits.W)))) else None
  // Preserve the original edge, but give each resident/enqueue comparison a
  // local driver instead of one valid/tag net spanning the entire issue queue.
  for (context <- 0 until (if (useRegisteredLoadKind) 0 else loadMatchContexts)) {
    localLoadPrd.get(context) := io.loadScheduledWake.bits.prd
    localLoadValid.get(context) := io.loadScheduledWake.valid && !io.flush && !recoveryBusy
    for (reg <- Seq(localLoadValid.get(context), localLoadPrd.get(context))) {
      dontTouch(reg)
      annotate(new ChiselAnnotation {
        override def toFirrtl = AttributeAnnotation(reg.toTarget,
          "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 8")
      })
    }
  }

  private val loadForwardAllowed = if (useRegisteredLoadKind)
    Some(RegNext(!io.flush && !recoveryBusy, false.B)) else None
  private val registeredLoadValid = if (useRegisteredLoadKind) {
    val witness = io.cdb(p.lsuCdbLane).valid && io.loadCdbIsLoad.get
    if (p.enableDifftest && prequalifiedLoadCdb) {
      assert(io.qualifiedLoadCdb.get === witness)
    }
    Some(io.qualifiedLoadCdb.getOrElse(witness) && loadForwardAllowed.get)
  } else None

  private val localFastValid = if (fastCount > 0)
    Some(RegInit(VecInit(Seq.fill(fastCount)(false.B)))) else None
  private val localFastPrd = if (fastCount > 0)
    Some(Reg(Vec(fastCount, UInt(p.physRegBits.W)))) else None
  for (f <- 0 until fastCount) {
    localFastPrd.get(f) := io.fastWake(f).bits
    localFastValid.get(f) := io.fastWake(f).valid && !io.flush && !recoveryBusy
    when(localFastValid.get(f)) {
      assert(io.fastResult(f).valid)
      assert(io.fastResult(f).bits.prd === localFastPrd.get(f))
    }
  }
  if (!useRegisteredLoadKind) {
    when(localLoadValid.get(0) && io.cdb(p.lsuCdbLane).valid) {
      assert(localLoadPrd.get(0) === io.cdb(p.lsuCdbLane).prd)
    }
  }

  private case class SourceMatch(
      captureHit: Bool, captureOH: UInt,
      immediateHit: Bool, immediateOH: UInt,
      fastHit: Bool, fastOH: UInt) {
    def captureData(source: Int): UInt = Mux1H(captureOH, io.cdb.zipWithIndex.map {
      case (lane, index) if source == 1 && index == p.lsuCdbLane =>
        io.secondSourceLsuData.getOrElse(lane.data)
      case (lane, _) => lane.data
    })
  }
  private def highestOH(mask: UInt): UInt = VecInit((0 until mask.getWidth).map(lane =>
    mask(lane) && !(if (lane == mask.getWidth - 1) false.B
      else mask(mask.getWidth - 1, lane + 1).orR))).asUInt

  private def forwardingRoute(lane: Int, source: Int): Option[CdbForwardRoute] =
    params.cdbForward.find(r => r.producer.cdbLane(p) == lane && r.sources(source))

  // A higher lane can shadow a lower lane only when they carry the same
  // tag. Compare producer tags once per queue, in parallel with the CAM,
  // instead of putting lane priority behind every entry's tag match.
  private def cdbUnshadowed(enabled: Seq[Bool]): UInt = VecInit(
    (0 until numCdb).map { lane =>
      val shadowed = (lane + 1 until numCdb).map { higher =>
        enabled(higher) && io.cdb(higher).prd === io.cdb(lane).prd
      }.foldLeft(false.B)(_ || _)
      !shadowed
    }).asUInt
  private val captureUnshadowed = if (useRegisteredLoadKind) {
    Some(cdbUnshadowed(io.cdb.map(_.valid).toSeq))
  } else None
  private val immediateUnshadowed = if (useRegisteredLoadKind) {
    Some((0 until 2).map { source =>
      cdbUnshadowed((0 until numCdb).map { lane =>
        forwardingRoute(lane, source) match {
          case Some(r) if r.timing == CdbForwardTiming.ScheduledLoad =>
            registeredLoadValid.get
          case Some(_) => io.cdb(lane).valid
          case None => false.B
        }
      })
    })
  } else None
  private val fastUnshadowed = if (fastCount > 0) {
    Some((0 until 2).map { source =>
      VecInit((0 until fastCount).map { lane =>
        val shadowed = (0 until lane).filter(fastRoutes(_).sources(source)).map { earlier =>
          localFastValid.get(earlier) && localFastPrd.get(earlier) === localFastPrd.get(lane)
        }.foldLeft(false.B)(_ || _)
        !shadowed
      }).asUInt
    })
  } else None
  private def sourceMatch(pr: UInt, source: Int, context: Int): SourceMatch = {
    // Compare both load tags in parallel. Select the one-bit match rather
    // than routing the scheduled valid bit through a mux on the full tag.
    val loadLane = p.lsuCdbLane
    val loadBroadcastValid = io.cdb(loadLane).valid && pr =/= 0.U
    val loadTagHit = loadBroadcastValid && io.cdb(loadLane).prd === pr
    val scheduledLoadHit = if (useRegisteredLoadKind)
      registeredLoadValid.get && io.cdb(loadLane).prd === pr && pr =/= 0.U
      else loadBroadcastValid && localLoadValid.get(context) && localLoadPrd.get(context) === pr
    val ordinaryLoadHit = if (useRegisteredLoadKind)
      loadTagHit && !(io.loadCdbIsLoad.get && loadForwardAllowed.get)
      else loadBroadcastValid && !localLoadValid.get(context) && io.cdb(loadLane).prd === pr
    val captureHits = VecInit((0 until numCdb).map { c =>
      if (c == loadLane) {
        if (useRegisteredLoadKind) loadTagHit else scheduledLoadHit || ordinaryLoadHit
      }
      else io.cdb(c).valid && io.cdb(c).prd === pr && pr =/= 0.U
    })
    val immediateHits = VecInit((0 until numCdb).map { c =>
      forwardingRoute(c, source) match {
        case Some(r) if r.timing == CdbForwardTiming.ScheduledLoad => scheduledLoadHit
        case Some(_) => captureHits(c)
        case None => false.B
      }
    })
    val (fastHit, fastOH) = if (fastCount == 0) {
      (false.B, 0.U(fastMaskBits.W))
    } else {
      val hits = VecInit(fastRoutes.zipWithIndex.map { case (r, f) =>
        if (r.sources(source)) localFastValid.get(f) && localFastPrd.get(f) === pr
        else false.B
      })
      val selected = hits.asUInt & fastUnshadowed.get(source)
      if (p.enableDifftest) { assert(selected === PriorityEncoderOH(hits.asUInt)) }
      (hits.asUInt.orR, selected)
    }
    val captureOH = captureUnshadowed.map(_ & captureHits.asUInt)
      .getOrElse(highestOH(captureHits.asUInt))
    val immediateOH = immediateUnshadowed.map(_(source) & immediateHits.asUInt)
      .getOrElse(highestOH(immediateHits.asUInt))
    if (p.enableDifftest && useRegisteredLoadKind) {
      assert(captureOH === highestOH(captureHits.asUInt))
      assert(immediateOH === highestOH(immediateHits.asUInt))
    }
    // Ready uses raw OR reductions; priority encoding serves only data selection.
    SourceMatch(captureHits.asUInt.orR, captureOH,
      immediateHits.asUInt.orR, immediateOH, fastHit, fastOH)
  }

  val ready = Wire(Vec(depth, Bool()))
  val multiplyReady = Wire(Vec(depth, Bool()))
  val cdbSelect = Seq.fill(2)(Wire(Vec(depth, UInt(numCdb.W))))
  val fastSelect = Seq.fill(2)(Wire(Vec(depth, UInt(fastMaskBits.W))))
  val storedSelect = Seq.fill(2)(Wire(Vec(depth, Bool())))
  for (slot <- 0 until depth) {
    val e = entries(slot)
    val available = Wire(Vec(2, Bool()))
    val isMultiply = entryIsMultiply.map(_(slot)).getOrElse(UopConst.isAluMultiply(e.uop.aluOp))
    val storedSourcesReady = (!e.uop.useRs1 || e.uop.prs1Ready) &&
      (!e.uop.useRs2 || e.uop.prs2Ready)
    multiplyReady(slot) := valids(slot) && isMultiply && storedSourcesReady
    for (source <- 0 until 2) {
      val use = if (source == 0) e.uop.useRs1 else e.uop.useRs2
      val stored = if (source == 0) e.uop.prs1Ready else e.uop.prs2Ready
      val pr = if (source == 0) e.uop.prs1 else e.uop.prs2
      val m = sourceMatch(pr, source, slot)
      val need = use && !stored
      val bypassAllowed = if (prepareMultiply) !isMultiply else true.B
      val fromCdb = need && m.immediateHit && bypassAllowed
      val fromFast = need && !m.immediateHit && m.fastHit && bypassAllowed
      available(source) := !use || stored || m.immediateHit || m.fastHit
      cdbSelect(source)(slot) := Mux(fromCdb, m.immediateOH, 0.U)
      fastSelect(source)(slot) := Mux(fromFast, m.fastOH, 0.U)
      // Preserve the old unused-source payload instead of forcing it to zero.
      storedSelect(source)(slot) := !fromCdb && !fromFast
      when(valids(slot) && need && m.captureHit) {
        if (source == 0) {
          entries(slot).src1 := m.captureData(source)
          entries(slot).uop.prs1Ready := true.B
        } else {
          entries(slot).src2 := m.captureData(source)
          entries(slot).uop.prs2Ready := true.B
        }
      }
    }
    ready(slot) := (if (prepareMultiply)
      Mux(isMultiply, multiplyReady(slot), valids(slot) && available.asUInt.andR)
      else valids(slot) && available.asUInt.andR)
  }

  private def oldestOneHot(candidates: Vec[Bool]): Vec[Bool] = VecInit(
    (0 until depth).map { slot =>
      val olderCandidate = VecInit((0 until depth).map(other =>
        candidates(other) && olderThan(other)(slot))).asUInt.orR
      candidates(slot) && !olderCandidate
    })
  // Multiplication has a distinct operand preparation cone. Its candidate
  // depends only on resident ready bits and registered operand words, never on
  // another ALU's combinational result or the general issue grant. Capturing a
  // producer result into the queue may cost a dependency cycle for multiply.
  val multiplyOH = if (prepareMultiply) Some(oldestOneHot(multiplyReady)) else None
  io.preparedMultiply.foreach(_ := Mux1H(multiplyOH.get, entries))
  io.preparedMultiplyUnsigned.foreach(_ := Mux1H(multiplyOH.get, entryMultiplyUnsigned.get))
  val selectedOH = oldestOneHot(ready)
  val selectedValid = ready.asUInt.orR
  val selectedIdx = OHToUInt(selectedOH)
  val selectedEntry = Mux1H(selectedOH, entries)
  io.issueAluOpOH.foreach(_ := Mux1H(selectedOH, entryAluOpOH.get))
  io.issueIsMultiply.foreach(_ := Mux1H(selectedOH, entryIsMultiply.get))
  io.issue.valid := selectedValid && !recoveryBusy
  io.issue.bits.uop := selectedEntry.uop
  io.issue.bits.uop.prs1Ready := true.B
  io.issue.bits.uop.prs2Ready := true.B

  // Qualify each resident destination before the late oldest-ready grant.
  // Wake valid need not wait for the selected opcode and tag payload muxes.
  val simpleWake = Wire(Vec(depth, Bool()))
  val multiplyWake = Wire(Vec(depth, Bool()))
  for (slot <- 0 until depth) {
    val uop = entries(slot).uop
    val writesDestination = uop.writesReg && uop.prd.orR
    val isMultiply = entryIsMultiply.map(_(slot)).getOrElse(UopConst.isAluMultiply(uop.aluOp))
    simpleWake(slot) := writesDestination && !isMultiply
    multiplyWake(slot) := writesDestination && isMultiply
  }
  val issueEnabled = io.issue.ready && !recoveryBusy
  io.simpleAluWake := issueEnabled && (selectedOH.asUInt & simpleWake.asUInt).orR
  io.multiplyAluWake := issueEnabled && (selectedOH.asUInt & multiplyWake.asUInt).orR
  val issueUop = io.issue.bits.uop
  val issuesDestination = io.issue.fire && issueUop.writesReg && issueUop.prd.orR
  val issueIsMultiply = UopConst.isAluMultiply(issueUop.aluOp)
  assert(io.simpleAluWake === (issuesDestination && !issueIsMultiply))
  assert(io.multiplyAluWake === (issuesDestination && issueIsMultiply))

  for (source <- 0 until 2) {
    val storedOH = VecInit((0 until depth).map(i => selectedOH(i) && storedSelect(source)(i)))
    val storedWord = Mux1H(storedOH, entries.map(e => if (source == 0) e.src1 else e.src2))
    val cdbOH = Mux1H(selectedOH, cdbSelect(source))
    val fastOH = Mux1H(selectedOH, fastSelect(source))
    val cdbWord = Mux1H(cdbOH, io.cdb.map(_.data))
    val fastWord = if (fastCount > 0) Mux1H(fastOH, io.fastResult.map(_.bits.data)) else 0.U(p.xLen.W)
    val fastLane = if (fastCount > 0) Mux1H(fastOH, fastRoutes.map(_.producer.index.U(p.aluIdxBits.W)))
                   else 0.U(p.aluIdxBits.W)
    val operand = storedWord | cdbWord | fastWord
    if (source == 1 && prepareAluControl) {
      val registerOH = VecInit((0 until depth).map(i =>
        selectedOH(i) && !entries(i).uop.useImm))
      val immediateOH = VecInit((0 until depth).map(i =>
        selectedOH(i) && entries(i).uop.useImm))
      val registerStoredOH = VecInit((0 until depth).map(i =>
        registerOH(i) && storedSelect(source)(i)))
      val registerCdbOH = Mux1H(registerOH, cdbSelect(source))
      val registerFastOH = Mux1H(registerOH, fastSelect(source))
      val registerFastWord = if (fastCount > 0)
        Mux1H(registerFastOH, io.fastResult.map(_.bits.data)) else 0.U(p.xLen.W)
      io.issueSimpleB.get := Mux1H(immediateOH, entries.map(_.uop.imm)) |
        Mux1H(registerStoredOH, entries.map(_.src2)) |
        Mux1H(registerCdbOH, io.cdb.map(_.data)) | registerFastWord
    }
    val isFast = io.issue.valid && fastOH.orR
    val isLoad = io.issue.valid && cdbOH(p.lsuCdbLane)
    if (source == 0) {
      io.issue.bits.src1 := operand
      io.issue.bits.src1FastAlu.valid := isFast
      io.issue.bits.src1FastAlu.producerLane := fastLane
      io.issue.bits.src1FromScheduledCdb := isLoad
    } else {
      io.issue.bits.src2 := operand
      io.issue.bits.src2FastAlu.valid := isFast
      io.issue.bits.src2FastAlu.producerLane := fastLane
      io.issue.bits.src2FromScheduledCdb := isLoad
    }
    when(io.issue.valid) {
      assert(PopCount(cdbOH) <= 1.U)
      assert(PopCount(fastOH) <= 1.U)
      assert(PopCount(Seq(storedOH.asUInt.orR, cdbOH.orR, fastOH.orR)) === 1.U)
    }
  }
  when(io.issue.valid) { assert(PopCount(selectedOH) === 1.U) }
  if (prepareAluControl) {
    when(io.issue.valid) {
      assert(io.issueAluOpOH.get === UIntToOH(io.issue.bits.uop.aluOp, UopConst.ALU_OP_NUM))
      assert(io.issueIsMultiply.get === UopConst.isAluMultiply(io.issue.bits.uop.aluOp))
      assert(io.issueSimpleB.get === Mux(io.issue.bits.uop.useImm,
        io.issue.bits.uop.imm, io.issue.bits.src2))
    }
  }
  if (prepareMultiply) {
    when(io.issue.fire && UopConst.isAluMultiply(io.issue.bits.uop.aluOp)) {
      assert(multiplyOH.get.asUInt === selectedOH.asUInt)
      assert(io.preparedMultiply.get.uop.robIdx === io.issue.bits.uop.robIdx)
      assert(io.preparedMultiply.get.uop.aluOp === io.issue.bits.uop.aluOp)
      assert(io.preparedMultiplyUnsigned.get === (io.issue.bits.uop.aluOp === UopConst.ALU_MULHU.U))
      assert(io.preparedMultiply.get.src1 === io.issue.bits.src1)
      assert(io.preparedMultiply.get.src2 === io.issue.bits.src2)
    }
  }
  val issueFire = io.issue.fire

  val freeIndexQueue = if (useFreeIndexQueue) Some(RegInit(VecInit(
    (0 until depth).map(_.U(slotBits.W))))) else None
  val freeHead = if (useFreeIndexQueue) Some(RegInit(0.U(slotBits.W))) else None
  val freeTail = if (useFreeIndexQueue) Some(RegInit(0.U(slotBits.W))) else None
  val pendingReleaseValid = if (useFreeIndexQueue) {
    Some(RegInit(false.B))
  } else None
  val pendingReleaseOH = if (useFreeIndexQueue) {
    Some(Reg(UInt(depth.W)))
  } else None

  val freeIdx = Wire(Vec(numEnq, UInt(slotBits.W)))
  if (useFreeIndexQueue) {
    for (rank <- 0 until numEnq) {
      freeIdx(rank) := freeIndexQueue.get(
        (freeHead.get +& rank.U) & (depth - 1).U)
    }
  } else {
    var remaining = VecInit(valids.map(!_)).asUInt
    for (rank <- 0 until numEnq) {
      freeIdx(rank) := PriorityEncoder(remaining)
      remaining = remaining & ~UIntToOH(freeIdx(rank), depth)
    }
  }

  // Decode one enqueue owner per slot; both values and the uop share it.
  val resolvedEnq = Wire(Vec(numEnq, new ExecIn))
  for (rank <- 0 until numEnq) {
    resolvedEnq(rank) := io.enqBits(rank)
    for (source <- 0 until 2) {
      val e = io.enqBits(rank)
      val pr = if (source == 0) e.uop.prs1 else e.uop.prs2
      val use = if (source == 0) e.uop.useRs1 else e.uop.useRs2
      val m = sourceMatch(pr, source, depth + rank)
      when(use && m.captureHit) {
        if (source == 0) {
          resolvedEnq(rank).src1 := m.captureData(source)
          resolvedEnq(rank).uop.prs1Ready := true.B
        } else {
          resolvedEnq(rank).src2 := m.captureData(source)
          resolvedEnq(rank).uop.prs2Ready := true.B
        }
      }
    }
  }
  for (slot <- 0 until depth) {
    val enqueueHits = VecInit((0 until numEnq).map(rank =>
      enqCount > rank.U && freeIdx(rank) === slot.U))
    val ownerOH = highestOH(enqueueHits.asUInt)
    when(enqueueHits.asUInt.orR) {
      entries(slot) := Mux1H(ownerOH, resolvedEnq)
      entryIsMultiply.foreach(_(slot) := Mux1H(ownerOH,
        resolvedEnq.map(e => UopConst.isAluMultiply(e.uop.aluOp))))
      entryMultiplyUnsigned.foreach(_(slot) := Mux1H(ownerOH,
        resolvedEnq.map(e => e.uop.aluOp === UopConst.ALU_MULHU.U)))
      entryAluOpOH.foreach(_(slot) := Mux1H(ownerOH,
        resolvedEnq.map(e => UIntToOH(e.uop.aluOp, UopConst.ALU_OP_NUM))))
    }
  }

  val acceptedEnqCount = Mux(recoveryBusy, 0.U, enqCount)
  val slotEnq = Wire(Vec(depth, Bool()))
  val slotEnqRank = Wire(Vec(depth, UInt(enqCountBits.W)))
  for (slot <- 0 until depth) {
    val rankHits = VecInit((0 until numEnq).map(rank =>
      acceptedEnqCount > rank.U && freeIdx(rank) === slot.U))
    slotEnq(slot) := rankHits.asUInt.orR
    slotEnqRank(slot) := Mux1H(rankHits,
      (0 until numEnq).map(_.U(enqCountBits.W)))
  }
  val recoverKill = VecInit((0 until depth).map(slot =>
    valids(slot) && RobOrder.isYounger(entries(slot).uop.robIdx,
      io.recover.bits, io.robHeadIdx)))
  val recoverKillMask = RegInit(0.U(depth.W))
  when(io.flush) {
    recoverKillMask := 0.U
  }.elsewhen(io.recover.valid) {
    recoverKillMask := recoverKill.asUInt
  }

  when(io.flush) {
    valids := VecInit(Seq.fill(depth)(false.B))
  }.elsewhen(recoverApplyPending) {
    for (slot <- 0 until depth) {
      when(recoverKillMask(slot)) { valids(slot) := false.B }
    }
  }.elsewhen(!recoveryBusy) {
    when(issueFire) { valids(selectedIdx) := false.B }
    for (rank <- 0 until numEnq) {
      when(enqCount > rank.U) { valids(freeIdx(rank)) := true.B }
    }
  }

  when(io.flush) {
    olderThan := VecInit(Seq.fill(depth)(
      VecInit(Seq.fill(depth)(false.B))))
  }.elsewhen(!recoveryBusy) {
    for (older <- 0 until depth) {
      for (younger <- 0 until depth) {
        if (older == younger) {
          olderThan(older)(younger) := false.B
        } else {
          when(slotEnq(older) || slotEnq(younger)) {
            olderThan(older)(younger) := Mux(
              slotEnq(older) && slotEnq(younger),
              slotEnqRank(older) < slotEnqRank(younger),
              !slotEnq(older) && slotEnq(younger) && valids(older))
          }
        }
      }
    }
  }

  if (useFreeIndexQueue) {
    when(flushCountPending) {
      for (slot <- 0 until depth) { freeIndexQueue.get(slot) := slot.U }
      freeHead.get := 0.U
      freeTail.get := 0.U
      pendingReleaseValid.get := false.B
    }.elsewhen(recoverRebuildPending) {
      val freeNow = VecInit(valids.map(!_))
      for (slot <- 0 until depth) {
        val rank = if (slot == 0) 0.U else PopCount(freeNow.take(slot))
        when(freeNow(slot)) { freeIndexQueue.get(rank) := slot.U }
      }
      freeHead.get := 0.U
      freeTail.get := PopCount(freeNow)(slotBits - 1, 0)
      pendingReleaseValid.get := false.B
    }.otherwise {
      when(pendingReleaseValid.get) {
        assert(PopCount(pendingReleaseOH.get) === 1.U)
        freeIndexQueue.get(freeTail.get) := OHToUInt(pendingReleaseOH.get)
      }
      pendingReleaseValid.get := issueFire
      pendingReleaseOH.get := selectedOH.asUInt
      freeHead.get := (freeHead.get +& acceptedEnqCount) & (depth - 1).U
      freeTail.get := (freeTail.get +& pendingReleaseValid.get.asUInt) &
        (depth - 1).U
    }
  }

  val releasedFreeCount = if (useFreeIndexQueue) {
    pendingReleaseValid.get.asUInt
  } else {
    issueFire.asUInt
  }
  when(flushCountPending) {
    freeCount := depth.U
  }.elsewhen(recoverRebuildPending) {
    freeCount := PopCount(valids.map(!_))
  }.elsewhen(!recoveryBusy) {
    freeCount := freeCount - acceptedEnqCount + releasedFreeCount
  }
}
