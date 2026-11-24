package aurora

import chisel3._
import chisel3.util._

/** 记录操作数值的内存发射队列。
  *
  * 分发时保存已读取的源操作数，后续通过 CDB 唇醒未就绪源，
  * 并按 ROB 年龄从已就绪项中选择最老请求。
  */
class MemIQ(depth: Int, numCdb: Int, numEnq: Int = 1,
            captureCdbBeforeIssue: Boolean = false)(implicit p: AuroraConfig)
    extends Module {
  private val enqCountBits = log2Ceil(numEnq + 1)
  private val countBits = log2Ceil(depth + 1)
  private val slotBits = log2Ceil(depth)

  require(depth >= 2, "MemIQ depth must be at least two")
  require(numEnq <= depth, "MemIQ enqueue width exceeds its depth")

  val io = IO(new Bundle {
    val enqCount = Input(UInt(enqCountBits.W))
    val enqBits = Input(Vec(numEnq, new ExecIn))
    val enqReady = Output(Bool())
    val enqCapacity = Output(UInt(enqCountBits.W))
    val occupancy = Output(UInt(countBits.W))
    val freeSlots = Output(UInt(countBits.W))

    val cdb = Input(Vec(numCdb, new CdbEntry))
    val aluFastWake = Input(Vec(p.numAlu, Valid(new WakeEvent)))
    val aluFastResult = Input(Vec(p.numAlu, Valid(new FastAluResult)))
    val issue = Decoupled(new MemoryIssue)
    val robHeadIdx = Input(UInt(p.robIdxBits.W))

    val lsqQueryValid = Output(Vec(depth, Bool()))
    val lsqQueryIdx = Output(Vec(depth, UInt(p.lsqIdxBits.W)))
    val lsqQueryRobIdx = Output(Vec(depth, UInt(p.robIdxBits.W)))
    val lsqQuerySafe = Input(Vec(depth, Bool()))

    val flush = Input(Bool())
    val recover = Input(Valid(UInt(p.robIdxBits.W)))
  })

  val entries = Reg(Vec(depth, new ExecIn))
  val valids = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val olderThan = RegInit(VecInit(Seq.fill(depth)(
    VecInit(Seq.fill(depth)(false.B)))))
  val freeCount = RegInit(depth.U(countBits.W))
  val recoverApplyPending = RegInit(false.B)
  when(io.flush) {
    recoverApplyPending := false.B
  }.otherwise {
    recoverApplyPending := io.recover.valid
  }
  val recoveryBusy = io.recover.valid || recoverApplyPending

  io.freeSlots := freeCount
  io.occupancy := depth.U - freeCount
  val enqCount = io.enqCount
  io.enqCapacity := Mux(recoveryBusy, 0.U,
    Mux(freeCount >= numEnq.U, numEnq.U, freeCount))
  io.enqReady := freeCount >= enqCount && !io.flush && !recoveryBusy

  val localFastValid = RegInit(VecInit(Seq.fill(p.numAlu)(false.B)))
  val localFastPrd = Reg(Vec(p.numAlu, UInt(p.physRegBits.W)))
  for (a <- 0 until p.numAlu) {
    localFastPrd(a) := io.aluFastWake(a).bits.prd
  }
  when(io.flush || recoveryBusy) {
    localFastValid := VecInit(Seq.fill(p.numAlu)(false.B))
  }.otherwise {
    for (a <- 0 until p.numAlu) {
      localFastValid(a) := io.aluFastWake(a).valid
    }
  }

  def cdbMask(pr: UInt): UInt = {
    val hits = VecInit((0 until numCdb).map(c =>
      io.cdb(c).valid && io.cdb(c).prd === pr && pr =/= 0.U))
    VecInit((0 until numCdb).map(c => hits(c) &&
      !hits.drop(c + 1).foldLeft(false.B)(_ || _))).asUInt
  }
  def cdbMatch(pr: UInt): (Bool, UInt, Bool) = {
    val selected = cdbMask(pr)
    val data = Mux1H(selected, io.cdb.map(_.data))
    // Retain the original load-source event even if a higher lane wins data.
    val loadHit = io.cdb(p.lsuCdbLane).valid && pr =/= 0.U &&
      io.cdb(p.lsuCdbLane).prd === pr
    // Readiness needs only any raw match. Do not reconstruct that Boolean
    // through highest-lane priority and then an OR before age arbitration.
    val rawHit = io.cdb.map(c => c.valid && c.prd === pr && pr =/= 0.U).reduce(_ || _)
    (rawHit, data, loadHit)
  }

  def fastMatch(pr: UInt): (Bool, UInt) = {
    val hits = VecInit((0 until p.numAlu).map(a =>
      localFastValid(a) && io.aluFastResult(a).valid &&
        localFastPrd(a) === pr))
    val lane = PriorityEncoder(hits)
    (hits.asUInt.orR, lane)
  }

  val ready = Wire(Vec(depth, Bool()))
  val resolvedSrc1 = Wire(Vec(depth, UInt(p.xLen.W)))
  val resolvedSrc2 = Wire(Vec(depth, UInt(p.xLen.W)))
  val fromFast1 = Wire(Vec(depth, Bool()))
  val fastLane1 = Wire(Vec(depth, UInt(p.aluIdxBits.W)))
  val fromLoadCdb1 = Wire(Vec(depth, Bool()))

  for (slot <- 0 until depth) {
    val e = entries(slot)
    val (cdbHit1, cdbData1, loadCdb1) = cdbMatch(e.uop.prs1)
    val (cdbHit2, cdbData2, _) = cdbMatch(e.uop.prs2)
    val (fastHit1, lane1) = fastMatch(e.uop.prs1)
    val need1 = e.uop.useRs1 && !e.uop.prs1Ready
    val need2 = e.uop.useRs2 && !e.uop.isStore && !e.uop.prs2Ready
    val immediateCdb1 = !captureCdbBeforeIssue.B && cdbHit1
    val immediateCdb2 = !captureCdbBeforeIssue.B && cdbHit2
    fromFast1(slot) := valids(slot) && need1 && fastHit1 && !immediateCdb1
    fastLane1(slot) := lane1
    fromLoadCdb1(slot) := valids(slot) && need1 && immediateCdb1 && loadCdb1
    resolvedSrc1(slot) := Mux(cdbHit1, cdbData1, e.src1)
    resolvedSrc2(slot) := Mux(cdbHit2, cdbData2, e.src2)
    val src1Ready = !e.uop.useRs1 || e.uop.prs1Ready || fastHit1 || immediateCdb1
    val src2Ready = e.uop.isStore || !e.uop.useRs2 ||
      e.uop.prs2Ready || immediateCdb2
    ready(slot) := valids(slot) && src1Ready && src2Ready

    when(valids(slot) && need1 && cdbHit1) {
      entries(slot).src1 := resolvedSrc1(slot)
      entries(slot).uop.prs1Ready := true.B
    }
    when(valids(slot) && need2 && cdbHit2) {
      entries(slot).src2 := resolvedSrc2(slot)
      entries(slot).uop.prs2Ready := true.B
    }

    io.lsqQueryValid(slot) := false.B
    io.lsqQueryIdx(slot) := e.uop.lsqIdx
    io.lsqQueryRobIdx(slot) := e.uop.robIdx
  }

  val oldestValidOH = VecInit((0 until depth).map { slot =>
    val hasOlder = VecInit((0 until depth).map(other =>
      valids(other) && olderThan(other)(slot))).asUInt.orR
    valids(slot) && !hasOlder
  })
  // 普通访存允许地址就绪项越过未就绪项；系统及 LL/SC 是发射屏障。
  // 屏障自身须为最老 resident，年轻请求也不能越过尚未发射的旧屏障。
  val barriers = VecInit(entries.map(e => e.uop.isSystem ||
    e.uop.memOp === UopConst.MEM_LL.U || e.uop.memOp === UopConst.MEM_SC.U))
  val eligible = VecInit((0 until depth).map { slot =>
    val olderBarrier = VecInit((0 until depth).map(other =>
      valids(other) && barriers(other) && olderThan(other)(slot))).asUInt.orR
    ready(slot) && !olderBarrier && (!barriers(slot) || oldestValidOH(slot))
  })
  val oldestReadyOH = VecInit((0 until depth).map { slot =>
    val olderReady = VecInit((0 until depth).map(other =>
      eligible(other) && olderThan(other)(slot))).asUInt.orR
    eligible(slot) && !olderReady
  })
  val selectedIdx = OHToUInt(oldestReadyOH)
  val selectedEntry = Mux1H((0 until depth).map(slot =>
    oldestReadyOH(slot) -> entries(slot)))

  // Every eligible slot is ready. Only the payload needs the age selector.
  io.issue.valid := eligible.asUInt.orR && !recoveryBusy
  io.issue.bits.uop := selectedEntry.uop
  io.issue.bits.uop.prs1Ready := true.B
  io.issue.bits.uop.prs2Ready := true.B
  val selectedFast1 = Mux1H(oldestReadyOH, fromFast1)
  val selectedFastLane1 = Mux1H(oldestReadyOH, fastLane1)
  // Resolve source data alongside age selection. Reuse the CDB words
  // already computed for resident capture, then select the winning entry.
  val fastWords = VecInit(io.aluFastResult.map(_.bits.data))
  if (captureCdbBeforeIssue) {
    val storedGrant = VecInit((0 until depth).map(slot =>
      oldestReadyOH(slot) && !fromFast1(slot)))
    val fastGrant = VecInit((0 until p.numAlu).map(lane =>
      selectedFast1 && selectedFastLane1 === lane.U))
    // The ALU result drives one shared bypass per producer. Grant selection
    // runs beside the ALU calculation, including the carry propagation.
    val fastWord = if (p.numAlu == 1) Mux(selectedFast1, fastWords(0), 0.U)
      else Mux1H(fastGrant, fastWords)
    io.issue.bits.src1 := Mux1H(storedGrant, entries.map(_.src1)) | fastWord
  } else {
    val issueSrc1 = VecInit((0 until depth).map(slot =>
      Mux(fromFast1(slot), fastWords(fastLane1(slot)), resolvedSrc1(slot))))
    io.issue.bits.src1 := Mux1H(oldestReadyOH, issueSrc1)
  }
  io.issue.bits.src2 := Mux1H(oldestReadyOH,
    if (captureCdbBeforeIssue) entries.map(_.src2) else resolvedSrc2.toSeq)
  io.issue.bits.addrFastAlu.valid := io.issue.valid && selectedFast1
  io.issue.bits.addrFastAlu.producerLane := selectedFastLane1
  io.issue.bits.addrFromScheduledCdb := io.issue.valid &&
    Mux1H(oldestReadyOH, fromLoadCdb1)
  val issueFire = io.issue.fire

  when(io.issue.valid) {
    assert(PopCount(oldestReadyOH) === 1.U)
    when(io.issue.bits.addrFastAlu.valid) {
      assert(io.aluFastResult(io.issue.bits.addrFastAlu.producerLane).valid)
    }
  }

  for (a <- 0 until p.numAlu) {
    when(localFastValid(a) && io.aluFastResult(a).valid) {
      assert(io.aluFastResult(a).bits.prd === localFastPrd(a))
    }
  }

  val freeIdx = Wire(Vec(numEnq, UInt(slotBits.W)))
  var remainingFree = VecInit(valids.map(!_)).asUInt
  for (rank <- 0 until numEnq) {
    freeIdx(rank) := PriorityEncoder(remainingFree)
    remainingFree = remainingFree & ~UIntToOH(freeIdx(rank), depth)
  }

  val resolvedEnq = Wire(Vec(numEnq, new ExecIn))
  for (rank <- 0 until numEnq) {
    resolvedEnq(rank) := io.enqBits(rank)
    val (cdbHit1, cdbData1, _) = cdbMatch(io.enqBits(rank).uop.prs1)
    val (cdbHit2, cdbData2, _) = cdbMatch(io.enqBits(rank).uop.prs2)
    when(io.enqBits(rank).uop.useRs1 && cdbHit1) {
      resolvedEnq(rank).src1 := cdbData1
      resolvedEnq(rank).uop.prs1Ready := true.B
    }
    when(io.enqBits(rank).uop.useRs2 && !io.enqBits(rank).uop.isStore &&
        cdbHit2) {
      resolvedEnq(rank).src2 := cdbData2
      resolvedEnq(rank).uop.prs2Ready := true.B
    }
  }
  for (slot <- 0 until depth) {
    val enqueueHits = VecInit((0 until numEnq).map(rank =>
      enqCount > rank.U && freeIdx(rank) === slot.U))
    val selected = Reverse(PriorityEncoderOH(Reverse(enqueueHits.asUInt)))
    when(io.enqReady && enqueueHits.asUInt.orR) {
      entries(slot) := Mux1H(selected, resolvedEnq)
    }
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
  val recoverKillCount = PopCount(recoverKillMask & valids.asUInt)
  val slotEnq = Wire(Vec(depth, Bool()))
  val slotEnqRank = Wire(Vec(depth, UInt(enqCountBits.W)))
  for (slot <- 0 until depth) {
    val rankHits = VecInit((0 until numEnq).map(rank =>
      enqCount > rank.U && freeIdx(rank) === slot.U))
    slotEnq(slot) := io.enqReady && rankHits.asUInt.orR
    slotEnqRank(slot) := Mux1H(rankHits,
      (0 until numEnq).map(_.U(enqCountBits.W)))
  }

  when(io.flush) {
    valids := VecInit(Seq.fill(depth)(false.B))
  }.elsewhen(recoverApplyPending) {
    for (slot <- 0 until depth) {
      when(recoverKillMask(slot)) { valids(slot) := false.B }
    }
  }.elsewhen(!recoveryBusy) {
    when(issueFire) { valids(selectedIdx) := false.B }
    for (slot <- 0 until depth) {
      when(slotEnq(slot)) { valids(slot) := true.B }
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

  // Flush clears the entries and their credits on the same edge.
  when(io.flush) {
    freeCount := depth.U
  }.elsewhen(recoverApplyPending) {
    freeCount := freeCount + recoverKillCount
  }.elsewhen(!recoveryBusy) {
    freeCount := freeCount + issueFire.asUInt - Mux(io.enqReady, enqCount, 0.U)
  }
  // The SoC reset reaches the core after its first clock edge.
  val creditAssertionsReady = RegNext(true.B, false.B)
  when(creditAssertionsReady) {
    assert(freeCount +& PopCount(valids) === depth.U)
    when(!io.flush && !recoveryBusy) { assert(io.enqReady) }
  }
}
