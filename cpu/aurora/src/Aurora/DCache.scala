package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

object DCacheEngineState extends ChiselEnum {
  val idle, loadMissSelect, loadMissDecision, loadMissHitReadWait,
    loadMissHitResponse, storeDecision, storeReadWait,
    writebackCapture, maintenanceProbe, maintenanceSelect,
    maintenanceMetadata, maintenanceCheck, maintenanceWritebackWait = Value
}

object DCacheEngineOwner {
  val load = 0.U(2.W)
  val store = 1.U(2.W)
  val maintenance = 2.U(2.W)
}

class DCacheMissRequest(implicit p: AuroraConfig) extends Bundle {
  val addr = UInt(p.xLen.W)
  val id = UInt(p.dcacheMshrIdxBits.W)
}

/** A miss slot samples data while free. Only queue ownership waits for the
  * late LSU admission. Keep the existing capacity and non-flow handshake.
  */
class DCacheMissQueue(implicit p: AuroraConfig) extends Module {
  private val depth = p.dcacheMshrEntries
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new DCacheMissRequest))
    val deq = Decoupled(new DCacheMissRequest)
  })

  val entries = Mem(depth, new DCacheMissRequest)
  val (head, _) = Counter(io.deq.fire, depth)
  val (tail, _) = Counter(io.enq.fire, depth)
  val maybeFull = RegInit(false.B)
  val pointersEqual = head === tail
  io.enq.ready := !(pointersEqual && maybeFull)
  io.deq.valid := !pointersEqual || maybeFull
  io.deq.bits := entries(head)
  when(io.enq.ready) { entries.write(tail, io.enq.bits) }
  when(io.enq.fire =/= io.deq.fire) { maybeFull := io.enq.fire }
}

class DCacheStoreUpdate(implicit p: AuroraConfig) extends Bundle {
  val addr = UInt(p.xLen.W)
  val data = UInt(p.xLen.W)
  val strb = UInt(p.strbBits.W)
}

class DCacheFillContext(implicit p: AuroraConfig) extends Bundle {
  val owner = UInt(2.W)
  val addr = UInt(p.xLen.W)
  val set = UInt(p.dcacheIndexBits.W)
  val tag = UInt(p.dcacheTagBits.W)
  val way = UInt(p.dcacheWayBits.W)
  val loadId = UInt(p.dcacheMshrIdxBits.W)
  val storeData = UInt(p.xLen.W)
  val storeStrb = UInt(p.strbBits.W)
  val victimDirty = Bool()
  val victimBase = UInt(p.xLen.W)
  val wbIdx = UInt(p.dcacheWritebackQueuePtrBits.W)
}

/** DCache 行引擎的存储体读请求。 */
class DCacheEngineReadRequest(implicit p: AuroraConfig) extends Bundle {
  val way = UInt(p.dcacheWayBits.W)
  val index = UInt(p.dcacheDataIndexBits.W)
}

/** 多路组相联数据 Cache。
  *
  * 支持命中访问、非阻塞 miss/refill、脏行写回、store update FIFO
  * 以及 CACOP 维护操作。所有总线请求通过寄存边界保持有效期稳定。
  */
class DCache(ownedLookupSnapshot: Boolean = false)(implicit p: AuroraConfig) extends Module {
  private val sets = p.dcacheSets
  private val ways = p.dcacheWays
  private val lineWords = p.dcacheLineWords
  private val storeUpdateDepth = p.dcacheStoreUpdateQueueDepth
  private val storeUpdateCountBits = log2Ceil(storeUpdateDepth + 1)
  private val writebackDepth = p.dcacheWritebackQueueDepth
  private val writebackPtrBits = log2Ceil(writebackDepth)
  private val writebackCountBits = log2Ceil(writebackDepth + 1)
  private val fillContextDepth = p.dcacheFillContextDepth
  private val fillContextPtrBits = log2Ceil(fillContextDepth)
  private val fillContextCountBits = log2Ceil(fillContextDepth + 1)

  val io = IO(new Bundle {
    val lookupReadValid = Input(Bool())
    val lookupReadReady = Output(Bool())
    val lookupReadAddr = Input(UInt(p.xLen.W))
    val lookupAddr = Input(UInt(p.xLen.W))
    val lookupHit = Output(Bool())
    val lookupData = Output(UInt(p.xLen.W))

    val loadMissValid = Input(Bool())
    val loadMissAddr = Input(UInt(p.xLen.W))
    val loadMissId = Input(UInt(p.dcacheMshrIdxBits.W))
    val loadMissReady = Output(Bool())
    val loadResp = Output(Valid(UInt(p.xLen.W)))
    val loadRespId = Output(UInt(p.dcacheMshrIdxBits.W))

    val storeValid = Input(Bool())
    val storeAddr = Input(UInt(p.xLen.W))
    val storeData = Input(UInt(p.xLen.W))
    val storeStrb = Input(UInt(p.strbBits.W))
    val storeReady = Output(Bool())

    val maintenanceValid = Input(Bool())
    val maintenanceAll = Input(Bool())
    val maintenanceIndex = Input(Bool())
    val maintenanceWriteback = Input(Bool())
    val maintenanceInvalidate = Input(Bool())
    val maintenanceAddr = Input(UInt(p.xLen.W))
    val maintenanceReady = Output(Bool())
    val maintenanceDone = Output(Bool())

    val readReqValid = Output(Bool())
    val readReqAddr = Output(UInt(p.xLen.W))
    val readReqLen = Output(UInt(8.W))
    val readReqBurst = Output(UInt(2.W))
    val readReqIsLoad = Output(Bool())
    val readReqLoadId = Output(UInt(p.dcacheMshrIdxBits.W))
    val readReqReady = Input(Bool())
    val readRespValid = Input(Bool())
    val readRespData = Input(UInt(p.xLen.W))
    val readRespLast = Input(Bool())
    val readRespReady = Output(Bool())

    val write = new BusWriteClientIO

    val busy = Output(Bool())
    val mshrOccupancy = Output(UInt(p.dcacheMshrCountBits.W))
    val perf = Output(new DCachePerfEvents)
  })

  def setOf(addr: UInt): UInt =
    addr(p.dcacheOffsetBits + p.dcacheIndexBits - 1, p.dcacheOffsetBits)

  def tagOf(addr: UInt): UInt =
    addr(p.xLen - 1, p.dcacheOffsetBits + p.dcacheIndexBits)

  def lineBaseOf(addr: UInt): UInt =
    Cat(addr(p.xLen - 1, p.dcacheOffsetBits), 0.U(p.dcacheOffsetBits.W))

  def wordOf(addr: UInt): UInt = {
    if (lineWords == 1) 0.U(p.dcacheLineWordBits.W)
    else addr(p.dcacheOffsetBits - 1, p.dcacheWordOffsetBits)
  }

  def lineAddress(tag: UInt, set: UInt): UInt =
    Cat(tag, set, 0.U(p.dcacheOffsetBits.W))

  def dataIndex(set: UInt, word: UInt): UInt = {
    if (lineWords == 1) set
    else Cat(set, word(log2Ceil(lineWords) - 1, 0))
  }

  def dataIndex(addr: UInt): UInt = dataIndex(setOf(addr), wordOf(addr))

  def wbDataIndex(entry: UInt, word: UInt): UInt = {
    if (lineWords == 1) entry
    else Cat(entry, word(log2Ceil(lineWords) - 1, 0))
  }

  def nextWay(way: UInt): UInt = (way + 1.U)(p.dcacheWayBits - 1, 0)

  def mergeBytes(base: UInt, overlay: UInt, mask: UInt): UInt =
    Cat((0 until p.strbBits).reverse.map(byte =>
      Mux(mask(byte), overlay(8 * byte + 7, 8 * byte),
        base(8 * byte + 7, 8 * byte))))

  val valids = RegInit(VecInit(Seq.fill(sets)(
    VecInit(Seq.fill(ways)(false.B)))))
  // Refill sets dirty with valid; victim and maintenance decisions qualify
  // dirty with the corresponding valid bit. A reset only invalidates lines.
  val dirty = Reg(Vec(sets, Vec(ways, Bool())))
  val replacement = RegInit(VecInit(Seq.fill(sets)(
    0.U(p.dcacheWayBits.W))))
  // Decode each metadata writer once, then resolve its exact source-order
  // priority at static cells. Dynamic nested Vec writes otherwise duplicate
  // large set/way mux and feedback trees across the metadata arrays.
  class MetadataWrite extends Bundle {
    val setsOH = UInt(sets.W)
    val waysOH = UInt(ways.W)
    val data = UInt(p.dcacheWayBits.W)
  }
  val validWrites = WireDefault(0.U.asTypeOf(Vec(5, Valid(new MetadataWrite))))
  val dirtyWrites = WireDefault(0.U.asTypeOf(Vec(7, Valid(new MetadataWrite))))
  val replacementWrites = WireDefault(0.U.asTypeOf(Vec(3, Valid(new MetadataWrite))))
  def metadataWrite(port: ValidIO[MetadataWrite], set: UInt, way: UInt, data: UInt,
                    predecodedSet: Option[UInt] = None,
                    predecodedWay: Option[UInt] = None): Unit = {
    port.valid := true.B
    port.bits.setsOH := predecodedSet.getOrElse(UIntToOH(set, sets))
    port.bits.waysOH := predecodedWay.getOrElse(UIntToOH(way, ways))
    port.bits.data := data
  }

  val tags = Seq.fill(ways)(Mem(sets, UInt(p.dcacheTagBits.W)))
  // Separate always-reading candidate/held replicas remove late acceptance
  // from RAM ADDR. Each replica forwards captured same-edge byte writes.
  val demandDataBanks = Seq.fill(ways)(
    Module(new DCacheDemandParallelRead(sets * lineWords, p.strbBits,
      separateWriteAddresses = true)))
  val engineDataBanks = Seq.fill(ways)(
    SyncReadMem(sets * lineWords, Vec(p.strbBits, UInt(8.W))))

  def selectedTag(set: UInt, way: UInt): UInt =
    Mux1H(UIntToOH(way, ways), tags.map(_(set)))

  tags.foreach { bank =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        AttributeAnnotation(bank.toTarget, "ram_style = \"distributed\"")
    })
  }
  engineDataBanks.foreach { bank =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        AttributeAnnotation(bank.toTarget, "ram_style = \"block\"")
    })
  }

  val arrayWriteValid = WireDefault(false.B)
  val arrayWriteWay = WireDefault(0.U(p.dcacheWayBits.W))
  val arrayWriteIndex = WireDefault(0.U(p.dcacheDataIndexBits.W))
  val arrayWriteData = WireDefault(0.U(p.xLen.W))
  val fastStoreWriteValid = WireDefault(false.B)
  val fastStoreWriteWay = WireDefault(0.U(p.dcacheWayBits.W))
  val fastStoreWriteWays = WireDefault(0.U(ways.W))
  val fastStoreWriteIndex = WireDefault(0.U(p.dcacheDataIndexBits.W))
  val fastStoreWriteData = WireDefault(0.U(p.xLen.W))
  val fastStoreWriteMask = WireDefault(0.U(p.strbBits.W))
  val internalReadValid = WireDefault(false.B)
  val internalReadWay = WireDefault(0.U(p.dcacheWayBits.W))
  val internalReadIndex = WireDefault(0.U(p.dcacheDataIndexBits.W))
  val lookupReadFire = io.lookupReadValid && io.lookupReadReady

  val demandReadWords = demandDataBanks.map { bank =>
    bank.io.candidateIndex := dataIndex(io.lookupReadAddr)
    bank.io.lookupFire := lookupReadFire
    bank.io.readData
  }
  val engineReadWords = engineDataBanks.zipWithIndex.map {
    case (bank, way) =>
      bank.read(internalReadIndex,
        internalReadValid && internalReadWay === way.U).asUInt
  }
  demandDataBanks.zip(engineDataBanks).zipWithIndex.foreach {
    case ((demandBank, engineBank), way) =>
    val engineWrite = arrayWriteValid && arrayWriteWay === way.U
    val storeWrite = fastStoreWriteWays(way)
    assert(!(engineWrite && storeWrite))
    val writeIndex = Mux(engineWrite, arrayWriteIndex, fastStoreWriteIndex)
    val writeData = Mux(engineWrite, arrayWriteData, fastStoreWriteData)
    val writeMask = Mux(engineWrite, Fill(p.strbBits, 1.U(1.W)), fastStoreWriteMask)
    // The single existing write arbitration broadcasts identically to both
    // demand replicas and the unchanged engine copy.
    demandBank.io.writeEnable := engineWrite || storeWrite
    demandBank.io.writeIndex := writeIndex
    demandBank.io.writeData := writeData
    demandBank.io.writeMask := writeMask
    val writeAddresses = demandBank.io.writeAddresses.get
    writeAddresses(0).valid := engineWrite
    writeAddresses(0).bits := arrayWriteIndex
    writeAddresses(1).valid := storeWrite
    writeAddresses(1).bits := fastStoreWriteIndex
    when(engineWrite || storeWrite) {
      val writeBytes = writeData.asTypeOf(Vec(p.strbBits, UInt(8.W)))
      engineBank.write(writeIndex, writeBytes, writeMask.asBools)
    }
  }

  val lookupReadSet = setOf(io.lookupReadAddr)
  val lookupResponseSet = Wire(UInt(p.dcacheIndexBits.W))
  val lookupRawWayHit = Wire(Vec(ways, Bool()))
  if (ownedLookupSnapshot) {
  // Prepare tag matches independently of the late lookup acceptance. Only the
  // one-bit owner changes on fire; the held bank retains its snapshot while
  // the other bank continuously checks the candidate address.
  val lookupMetadataOwner = RegInit(false.B)
  val lookupMatchBanks = Reg(Vec(2, Vec(ways, Bool())))
  val lookupSetBanks = Reg(Vec(2, UInt(p.dcacheIndexBits.W)))
  val candidateTags = tags.map(_(lookupReadSet))
  val candidateWayHits = VecInit((0 until ways).map(way =>
    valids(lookupReadSet)(way) && candidateTags(way) === tagOf(io.lookupReadAddr)))
  for (bank <- 0 until 2) {
    when(lookupMetadataOwner =/= bank.U) {
      lookupMatchBanks(bank) := candidateWayHits
      lookupSetBanks(bank) := lookupReadSet
    }
  }
  when(lookupReadFire) { lookupMetadataOwner := !lookupMetadataOwner }
    lookupResponseSet := lookupSetBanks(lookupMetadataOwner)
    lookupRawWayHit := lookupMatchBanks(lookupMetadataOwner)
  } else {
    // The general cache interface also supports combinational lookupAddr
    // changes against its held tag snapshot. The LSU opts into the narrower
    // accepted-owner contract; standalone callers retain the general behavior.
    val lookupValidBits = RegEnable(valids(lookupReadSet).asUInt, lookupReadFire)
    val lookupTags = RegEnable(VecInit(tags.map(_(lookupReadSet))), lookupReadFire)
    lookupResponseSet := RegEnable(lookupReadSet, lookupReadFire)
    lookupRawWayHit := VecInit((0 until ways).map(way =>
      lookupValidBits(way) && lookupTags(way) === tagOf(io.lookupAddr)))
  }
  val lookupSnapshotInvalidated = RegInit(false.B)
  val lookupWayHit = VecInit((0 until ways).map(way =>
    lookupRawWayHit(way) && !lookupSnapshotInvalidated))
  val lookupArrayHit = lookupWayHit.asUInt.orR

  val recentLineValid = RegInit(false.B)
  val recentLineBase = Reg(UInt(p.xLen.W))
  val recentLine = Mem(lineWords, UInt(p.xLen.W))
  annotate(new ChiselAnnotation {
    override def toFirrtl =
      AttributeAnnotation(recentLine.toTarget, "ram_style = \"distributed\"")
  })
  val recentLineWriteValid = WireDefault(false.B)
  val recentLineWriteIndex = WireDefault(0.U(p.dcacheLineWordBits.W))
  val recentLineWriteData = WireDefault(0.U(p.xLen.W))
  val recentBaseUpdate = Wire(Valid(UInt(p.xLen.W)))
  recentBaseUpdate.valid := false.B
  recentBaseUpdate.bits := 0.U
  when(recentBaseUpdate.valid) { recentLineBase := recentBaseUpdate.bits }
  val recentTagMatch = if (ownedLookupSnapshot) {
    val queryBase = lineBaseOf(io.lookupReadAddr)
    val heldBase = RegEnable(queryBase, lookupReadFire)
    val matches = RegInit(false.B)
    when(recentBaseUpdate.valid) {
      // A refill can replace the recent line while a lookup is stalled, or
      // at the same edge as its successor. Compare the post-edge owners.
      matches := Mux(lookupReadFire, queryBase === recentBaseUpdate.bits,
        heldBase === recentBaseUpdate.bits)
    }.elsewhen(lookupReadFire) {
      matches := queryBase === recentLineBase
    }
    if (p.enableDifftest) {
      val seen = RegInit(false.B)
      when(lookupReadFire) { seen := true.B }
      when(seen && recentLineValid) { assert(matches === (heldBase === recentLineBase)) }
    }
    matches
  } else recentLineBase === lineBaseOf(io.lookupAddr)
  val recentLookup = recentLineValid && recentTagMatch
  val lookupWord = wordOf(io.lookupAddr)
  val lookupResidentHit = lookupArrayHit || recentLookup
  val recentLookupData = recentLine(lookupWord)

  val state = RegInit(DCacheEngineState.idle)
  val owner = RegInit(DCacheEngineOwner.load)
  val opAddr = Reg(UInt(p.xLen.W))
  val opSet = Reg(UInt(p.dcacheIndexBits.W))
  val opTag = Reg(UInt(p.dcacheTagBits.W))
  val opWay = Reg(UInt(p.dcacheWayBits.W))
  val opStoreData = Reg(UInt(p.xLen.W))
  val opStoreStrb = Reg(UInt(p.strbBits.W))
  val opStoreHit = RegInit(false.B)
  val opLoadResidentHit = RegInit(false.B)
  val opVictimDirty = RegInit(false.B)
  val opVictimBase = Reg(UInt(p.xLen.W))
  val loadProbeValidBits = Reg(UInt(ways.W))
  val loadProbeDirtyBits = Reg(UInt(ways.W))
  val loadProbeTags = Reg(Vec(ways, UInt(p.dcacheTagBits.W)))
  val loadProbeReplacement = Reg(UInt(p.dcacheWayBits.W))
  val opLoadId = Reg(UInt(p.dcacheMshrIdxBits.W))
  val queuedHitData = Reg(UInt(p.xLen.W))
  val refillCount = RegInit(0.U(p.dcacheLineWordBits.W))
  val criticalWord = Reg(UInt(p.dcacheLineWordBits.W))

  val wbValid = RegInit(VecInit(Seq.fill(writebackDepth)(false.B)))
  val wbCaptured = RegInit(VecInit(Seq.fill(writebackDepth)(false.B)))
  val wbLineBases = Reg(Vec(writebackDepth, UInt(p.xLen.W)))
  val wbWords = Mem(writebackDepth * lineWords, UInt(p.xLen.W))
  annotate(new ChiselAnnotation {
    override def toFirrtl =
      AttributeAnnotation(wbWords.toTarget, "ram_style = \"distributed\"")
  })
  val wbMaintenance = RegInit(VecInit(Seq.fill(writebackDepth)(false.B)))
  val wbMaintenanceSet = Reg(Vec(writebackDepth,
    UInt(p.dcacheIndexBits.W)))
  val wbMaintenanceWay = Reg(Vec(writebackDepth,
    UInt(p.dcacheWayBits.W)))
  val wbMaintenanceInvalidate = RegInit(
    VecInit(Seq.fill(writebackDepth)(false.B)))
  val wbCommandIssued = RegInit(
    VecInit(Seq.fill(writebackDepth)(false.B)))
  val wbDataComplete = RegInit(
    VecInit(Seq.fill(writebackDepth)(false.B)))
  val wbHead = RegInit(0.U(writebackPtrBits.W))
  val wbTail = RegInit(0.U(writebackPtrBits.W))
  val wbCommandHead = RegInit(0.U(writebackPtrBits.W))
  val wbDataHead = RegInit(0.U(writebackPtrBits.W))
  val wbCount = RegInit(0.U(writebackCountBits.W))
  val wbBusy = wbCount =/= 0.U
  val wbFull = wbCount === writebackDepth.U
  val wbHasSpace = !wbFull

  val wbCaptureIdx = Reg(UInt(writebackPtrBits.W))
  val wbCaptureSet = Reg(UInt(p.dcacheIndexBits.W))
  val wbCaptureWay = Reg(UInt(p.dcacheWayBits.W))
  val wbCaptureIssueWord = RegInit(0.U(p.dcacheLineWordBits.W))
  val wbCaptureReceiveWord = RegInit(0.U(p.dcacheLineWordBits.W))
  val wbCaptureIssueDone = RegInit(false.B)
  val wbCapturePipeValid = RegInit(false.B)
  val wbSendWord = RegInit(0.U(p.dcacheLineWordBits.W))
  val wbAllocate = WireDefault(false.B)

  val pendingFillValid = RegInit(false.B)
  val pendingFill = Reg(new DCacheFillContext)
  val returnFillValid = RegInit(
    VecInit(Seq.fill(fillContextDepth)(false.B)))
  val returnFillContexts = Reg(Vec(fillContextDepth, new DCacheFillContext))
  val returnFillHead = RegInit(0.U(fillContextPtrBits.W))
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(
      returnFillHead.toTarget, "max_fanout = 16")
  })
  val returnFillTail = RegInit(0.U(fillContextPtrBits.W))
  val returnFillCount = RegInit(0.U(fillContextCountBits.W))
  val activeFillValid = returnFillCount =/= 0.U &&
    returnFillValid(returnFillHead)
  val activeFill = returnFillContexts(returnFillHead)
  val activeRefillCount = RegInit(0.U(p.dcacheLineWordBits.W))
  val activeReceivedWords = RegInit(0.U(lineWords.W))
  val victimCapturePipeValid = RegInit(false.B)
  val pendingVictimPrimed = RegInit(false.B)
  val pendingVictimPrime = Wire(Valid(new DCacheEngineReadRequest))
  pendingVictimPrime.valid := false.B
  pendingVictimPrime.bits := 0.U.asTypeOf(new DCacheEngineReadRequest)

  val waiterValid = RegInit(VecInit(
    Seq.fill(p.dcacheMshrEntries)(false.B)))
  val waiterAddr = Reg(Vec(p.dcacheMshrEntries, UInt(p.xLen.W)))
  // 退役等待项的字号在 detach 时确定，服务时只做4位选择，
  // 不再把32位地址 mux 和30位比较串在 completionData 使能路径上。
  val waiterWord = Reg(Vec(p.dcacheMshrEntries, UInt(p.dcacheLineWordBits.W)))
  val completionValid = RegInit(VecInit(
    Seq.fill(p.dcacheMshrEntries)(false.B)))
  val completionData = Reg(Vec(p.dcacheMshrEntries, UInt(p.xLen.W)))
  val completionWasWaiter = RegInit(VecInit(
    Seq.fill(p.dcacheMshrEntries)(false.B)))
  val completedLineDrainValid = RegInit(false.B)
  val completedLineDrainBase = Reg(UInt(p.xLen.W))

  // Specialize the two-entry implementation to preserve its short control paths.
  // Deeper experimental queues retain entries in oldest-to-youngest order.
  val deeperStoreEntries = if (storeUpdateDepth > 2)
    Some(Reg(Vec(storeUpdateDepth, new DCacheStoreUpdate))) else None
  val storeUpdateHeadEntry = deeperStoreEntries.map(_(0))
    .getOrElse(Reg(new DCacheStoreUpdate))
  val storeUpdateSecondEntry = deeperStoreEntries.map(_(1))
    .getOrElse(Reg(new DCacheStoreUpdate))
  val storeUpdateCount = RegInit(0.U(storeUpdateCountBits.W))
  val storeQueueValidBits = if (storeUpdateDepth > 2)
    Some(RegInit(VecInit(Seq.fill(storeUpdateDepth)(false.B)))) else None
  val storeUpdateHeadValid = storeQueueValidBits.map(_(0)).getOrElse(storeUpdateCount =/= 0.U)
  val storeUpdateSecondValid = storeQueueValidBits.map(_(1)).getOrElse(storeUpdateCount > 1.U)

  val lastStoreWriteValid = RegInit(false.B)
  val lastStoreWriteAddr = Reg(UInt(p.xLen.W))
  val lastStoreWriteData = Reg(UInt(p.xLen.W))
  val lastStoreWriteStrb = Reg(UInt(p.strbBits.W))
  lastStoreWriteValid := false.B

  val storeQueueEntries = Seq(storeUpdateHeadEntry, storeUpdateSecondEntry) ++
    deeperStoreEntries.toSeq.flatMap(entries => (2 until storeUpdateDepth).map(entries(_)))
  val storeForwardCandidates = Seq(
    (lastStoreWriteValid, lastStoreWriteAddr, lastStoreWriteData,
      lastStoreWriteStrb)) ++ storeQueueEntries.zipWithIndex.map { case (entry, slot) =>
    (storeQueueValidBits.map(_(slot)).getOrElse(storeUpdateCount > slot.U),
      entry.addr, entry.data, entry.strb)
  }
  val forwardMatchOwner = if (storeUpdateDepth > 2) Some(RegInit(false.B)) else None
  val forwardQueueMatchBanks = if (storeUpdateDepth > 2)
    Some(Reg(Vec(2, Vec(storeUpdateDepth, Bool())))) else None
  val forwardLastMatchBanks = if (storeUpdateDepth > 2)
    Some(Reg(Vec(2, Bool()))) else None
  val forwardQueueByteBanks = if (storeUpdateDepth > 2)
    Some(Reg(Vec(2, Vec(storeUpdateDepth, UInt(p.strbBits.W))))) else None
  val forwardLastByteBanks = if (storeUpdateDepth > 2)
    Some(Reg(Vec(2, UInt(p.strbBits.W)))) else None
  val forwardCoverageBanks = if (storeUpdateDepth > 2)
    Some(Reg(Vec(2, UInt(p.strbBits.W)))) else None
  var lookupForwardMask = 0.U(p.strbBits.W)
  var lookupForwardData = 0.U(p.xLen.W)
  if (storeUpdateDepth == 2) {
    for ((valid, addr, data, strb) <- storeForwardCandidates) {
      val addressMatch = valid &&
        addr(p.xLen - 1, 2) === io.lookupAddr(p.xLen - 1, 2)
      lookupForwardData = Mux(addressMatch,
        mergeBytes(lookupForwardData, data, strb), lookupForwardData)
      lookupForwardMask = Mux(addressMatch,
        lookupForwardMask | strb, lookupForwardMask)
    }
  } else {
    // Address matches follow the same edge as the corresponding queue entry
    // and demand owner. Resolve age independently for each byte, then select
    // data once; a late address no longer traverses a tree of data overwrites.
    def hitsByByte(bank: Int): Seq[Seq[Bool]] = {
      val masks = Seq(forwardLastByteBanks.get(bank)) ++
        forwardQueueByteBanks.get(bank).toSeq
      (0 until p.strbBits).map(byte => masks.map(_(byte)))
    }
    val bankHits = (0 until 2).map(hitsByByte)
    // Coverage is prepared with both possible owner states at the queue edge.
    // Data still uses exact youngest-byte priority, but hit/scan admission no
    // longer traverses that coverage tree in the current cycle.
    lookupForwardMask = forwardCoverageBanks.get(forwardMatchOwner.get)
    val coverageWitness = VecInit(bankHits.map(hits =>
      VecInit(hits.map(_.reduce(_ || _))).asUInt))(forwardMatchOwner.get)
    val matchOwnerAddr = RegEnable(io.lookupReadAddr, lookupReadFire)
    val matchOwnerSeen = RegInit(false.B)
    when(lookupReadFire) { matchOwnerSeen := true.B }
    when(matchOwnerSeen && io.lookupAddr === matchOwnerAddr) {
      assert(lookupForwardMask === coverageWitness)
    }
    if (p.enableDifftest) {
      val owner = forwardMatchOwner.get
      val matches = Seq(forwardLastMatchBanks.get(owner)) ++
        forwardQueueMatchBanks.get(owner).toSeq
      val masks = Seq(forwardLastByteBanks.get(owner)) ++
        forwardQueueByteBanks.get(owner).toSeq
      when(matchOwnerSeen) {
        for ((candidate, slot) <- storeForwardCandidates.zipWithIndex) {
          val (valid, _, _, strb) = candidate
          assert(masks(slot) === Mux(valid && matches(slot), strb, 0.U))
        }
      }
    }
    val bankData = bankHits.map { byteHits =>
      Cat(byteHits.zipWithIndex.reverse.map { case (hits, byte) =>
        val winners = hits.indices.map(slot =>
          hits(slot) && !hits.drop(slot + 1).foldLeft(false.B)(_ || _))
        Mux1H(winners.zip(storeForwardCandidates.map {
          case (_, _, data, _) => data(8 * byte + 7, 8 * byte)
        }))
      })
    }
    lookupForwardData = Mux(forwardMatchOwner.get, bankData(1), bankData(0))
  }
  io.lookupHit := lookupResidentHit || lookupForwardMask.andR
  io.lookupData := Cat((0 until p.strbBits).reverse.map { byte =>
    val fromStore = lookupForwardMask(byte)
    val fromRecent = !fromStore && recentLookup
    val fromArray = !fromStore && !recentLookup
    val bankChoices = demandReadWords.zipWithIndex.map { case (word, way) =>
      (fromArray && (if (ways == 1) true.B else lookupWayHit(way))) ->
        word(8 * byte + 7, 8 * byte)
    }
    Mux1H(bankChoices ++ Seq(
      fromStore -> lookupForwardData(8 * byte + 7, 8 * byte),
      fromRecent -> recentLookupData(8 * byte + 7, 8 * byte)))
  })

  val maintenanceAll = RegInit(false.B)
  val maintenanceIndex = RegInit(false.B)
  val maintenanceWriteback = RegInit(false.B)
  val maintenanceInvalidate = RegInit(false.B)
  val maintenanceAddr = Reg(UInt(p.xLen.W))
  val maintenanceSet = Reg(UInt(p.dcacheIndexBits.W))
  val maintenanceWay = Reg(UInt(p.dcacheWayBits.W))
  val maintenanceProbeSetReg = Reg(UInt(p.dcacheIndexBits.W))
  val maintenanceProbeTagReg = Reg(UInt(p.dcacheTagBits.W))
  val maintenanceProbeValidBits = Reg(UInt(ways.W))
  val maintenanceProbeDirtyBits = Reg(UInt(ways.W))
  val maintenanceProbeTags = Reg(Vec(ways, UInt(p.dcacheTagBits.W)))
  val maintenanceTargetSetReg = Reg(UInt(p.dcacheIndexBits.W))
  val maintenanceTargetWayReg = Reg(UInt(p.dcacheWayBits.W))
  val maintenanceTargetValidReg = RegInit(false.B)
  val maintenanceTargetDirtyReg = RegInit(false.B)
  val maintenanceTargetLineBaseReg = Reg(UInt(p.xLen.W))
  val maintenanceDoneReg = RegInit(false.B)
  val storeOperationComplete = WireDefault(false.B)
  val missQueue = Module(new DCacheMissQueue)
  val mshrOccupancy = RegInit(0.U(p.dcacheMshrCountBits.W))
  val mshrHasSpace = mshrOccupancy < p.dcacheMshrEntries.U
  missQueue.io.enq.valid := io.loadMissValid && mshrHasSpace
  missQueue.io.enq.bits.addr := io.loadMissAddr
  missQueue.io.enq.bits.id := io.loadMissId
  io.loadMissReady := mshrHasSpace && missQueue.io.enq.ready
  val loadMissAccept = io.loadMissValid && io.loadMissReady
  val loadMissStart = WireDefault(false.B)
  val waiterDetach = WireDefault(false.B)
  missQueue.io.deq.ready := loadMissStart || waiterDetach
  val fillReserveValid = WireDefault(false.B)
  val fillReserveSet = WireDefault(opSet)
  val fillReserveWay = WireDefault(opWay)

  maintenanceDoneReg := false.B

  io.maintenanceReady := state === DCacheEngineState.idle &&
    !missQueue.io.deq.valid && !storeUpdateHeadValid && !io.storeValid &&
    !maintenanceDoneReg && !wbBusy && !pendingFillValid &&
    returnFillCount === 0.U
  val maintenanceFire = io.maintenanceValid && io.maintenanceReady

  def allocateWriteback(set: UInt, way: UInt, lineBase: UInt,
      isMaintenance: Bool, invalidate: Bool): Unit = {
    assert(wbHasSpace)
    wbAllocate := true.B
    wbValid(wbTail) := true.B
    wbCaptured(wbTail) := false.B
    wbCommandIssued(wbTail) := false.B
    wbDataComplete(wbTail) := false.B
    wbLineBases(wbTail) := lineBase
    wbMaintenance(wbTail) := isMaintenance
    wbMaintenanceSet(wbTail) := set
    wbMaintenanceWay(wbTail) := way
    wbMaintenanceInvalidate(wbTail) := invalidate
    wbTail := (wbTail + 1.U)(writebackPtrBits - 1, 0)
  }

  val loadReservedWays = VecInit((0 until ways).map(way =>
    (pendingFillValid && pendingFill.set === opSet &&
      pendingFill.way === way.U) ||
      VecInit((0 until fillContextDepth).map(slot =>
        returnFillValid(slot) && returnFillContexts(slot).set === opSet &&
          returnFillContexts(slot).way === way.U)).asUInt.orR)).asUInt
  val loadVictimValidBits = loadProbeValidBits
  val loadResidentWays = VecInit((0 until ways).map(way =>
    loadProbeValidBits(way) && loadProbeTags(way) === opTag))
  val loadResidentHit = loadResidentWays.asUInt.orR
  val loadResidentWay = OHToUInt(loadResidentWays)
  val loadAvailableWays = ~loadReservedWays
  val loadInvalidWays = ~loadVictimValidBits & loadAvailableWays
  val loadFallbackWay = PriorityMux((0 until ways).map(offset => {
    val candidate = (loadProbeReplacement + offset.U)(
      p.dcacheWayBits - 1, 0)
    loadAvailableWays(candidate) -> candidate
  }))
  val loadVictimAvailable = loadAvailableWays.orR
  val loadVictimWay = Mux(loadInvalidWays.orR,
    PriorityEncoder(loadInvalidWays), loadFallbackWay)
  val loadVictimDirty = loadProbeValidBits(loadVictimWay) &&
    loadProbeDirtyBits(loadVictimWay)
  val loadVictimBase = lineAddress(
    loadProbeTags(loadVictimWay), opSet)

  val storeProbeValid = RegInit(false.B)
  val storeProbeEntry = Reg(new DCacheStoreUpdate)
  val storeProbeValidBits = Reg(UInt(ways.W))
  val storeProbeDirtyBits = Reg(UInt(ways.W))
  val storeProbeTags = Reg(Vec(ways, UInt(p.dcacheTagBits.W)))
  // Read the replacement state from the registered probe set.  Previously
  // the replacement-array mux was selected from storeProbeCandidateSet on
  // the same edge that captured the incoming store, placing the entire store
  // queue/address path in front of this register.  A same-cycle store hit is
  // forwarded because its replacement update is intentionally visible to
  // the next queued probe.
  val storeProbeReplacementFastHit = RegInit(false.B)
  val storeProbeReplacementFastValue = Reg(UInt(p.dcacheWayBits.W))
  val storeProbeSet = setOf(storeProbeEntry.addr)
  val storeProbeSetOH = Reg(UInt(sets.W))
  val storeProbeTag = tagOf(storeProbeEntry.addr)
  val storeProbeReplacement = Mux(storeProbeReplacementFastHit,
    storeProbeReplacementFastValue, replacement(storeProbeSet))
  // Split the probe tag comparison across its existing capture edge. The
  // upper tag still comes from the same snapshot as dirty/victim metadata.
  val storeTagSplit = (p.dcacheTagBits + 1) / 2
  val storeLowTagHit = Reg(Vec(ways, Bool()))
  val storeProbeWayHit = VecInit((0 until ways).map(way =>
    storeProbeValidBits(way) && storeLowTagHit(way) &&
      (storeProbeTags(way) >> storeTagSplit) === (storeProbeTag >> storeTagSplit)))
  val storeProbeHit = storeProbeWayHit.asUInt.orR
  val storeProbeHitWay = OHToUInt(storeProbeWayHit)
  // The payload is available from the registered probe before its hit.
  fastStoreWriteWay := storeProbeHitWay
  fastStoreWriteIndex := dataIndex(storeProbeEntry.addr)
  fastStoreWriteData := storeProbeEntry.data
  fastStoreWriteMask := storeProbeEntry.strb
  val storeProbeInvalidWays = ~storeProbeValidBits
  val storeProbeVictimWay = Mux(storeProbeInvalidWays.orR,
    PriorityEncoder(storeProbeInvalidWays), storeProbeReplacement)
  val storeProbeVictimDirty = storeProbeValidBits(storeProbeVictimWay) &&
    storeProbeDirtyBits(storeProbeVictimWay)
  val storeProbeVictimTag = storeProbeTags(storeProbeVictimWay)
  val storeProbeVictimBase = lineAddress(
    storeProbeVictimTag, storeProbeSet)

  val prepareOwnsOpSet = state === DCacheEngineState.loadMissSelect ||
    state === DCacheEngineState.loadMissDecision ||
    state === DCacheEngineState.loadMissHitReadWait ||
    state === DCacheEngineState.loadMissHitResponse ||
    state === DCacheEngineState.storeDecision ||
    state === DCacheEngineState.storeReadWait
  val pendingFillSameSet = pendingFillValid && pendingFill.set === storeProbeSet
  val returnFillSameSet = (0 until fillContextDepth).map(slot =>
    returnFillValid(slot) && returnFillContexts(slot).set === storeProbeSet)
  val lastFillCommitValid = RegNext(
    activeFillValid && io.readRespValid && io.readRespReady && io.readRespLast,
    false.B)
  val lastFillCommitSet = RegEnable(activeFill.set,
    activeFillValid && io.readRespValid && io.readRespReady && io.readRespLast)
  val lastFillCommitWay = RegEnable(activeFill.way,
    activeFillValid && io.readRespValid && io.readRespReady && io.readRespLast)
  val lastFillSameSet = lastFillCommitValid && lastFillCommitSet === storeProbeSet
  val storeProbeEngineConflict = prepareOwnsOpSet && opSet === storeProbeSet
  val fillBlockedWays = VecInit((0 until ways).map { way =>
    (pendingFillSameSet && pendingFill.way === way.U) ||
      returnFillSameSet.zipWithIndex.map { case (sameSet, slot) =>
        sameSet && returnFillContexts(slot).way === way.U
      }.reduce(_ || _) ||
      (lastFillSameSet && lastFillCommitWay === way.U)
  }).asUInt
  val anyFillSameSet = pendingFillSameSet || returnFillSameSet.reduce(_ || _) ||
    lastFillSameSet
  val storeProbeNeedsReplay = storeProbeValid && (storeProbeEngineConflict ||
    Mux(storeProbeHit, (storeProbeWayHit.asUInt & fillBlockedWays).orR, anyFillSameSet))
  val activeRefillBeat = activeFillValid &&
    io.readRespValid && io.readRespReady
  val storeArrayWriteBusy = state === DCacheEngineState.storeReadWait ||
    (activeRefillBeat && activeFill.way === storeProbeHitWay)
  val refillWriteWay = Mux(activeRefillBeat, UIntToOH(activeFill.way, ways), 0.U(ways.W))
  val fastStoreCommon = storeProbeValid && !storeProbeEngineConflict &&
    state =/= DCacheEngineState.storeReadWait
  // Each bank combines its local tag hit and both write hazards. The write
  // enable never traverses the encoded way or the global hit reduction.
  fastStoreWriteWays := Mux(fastStoreCommon,
    storeProbeWayHit.asUInt & ~fillBlockedWays & ~refillWriteWay, 0.U(ways.W))
  val storeFastHit = fastStoreWriteWays.orR
  if (p.enableDifftest) {
    when(storeProbeValid && !storeProbeNeedsReplay) {
      assert(PopCount(storeProbeWayHit) <= 1.U)
    }
    assert(storeFastHit === (storeProbeValid && storeProbeHit &&
      !storeProbeNeedsReplay && !storeArrayWriteBusy))
  }
  val storeFullHitReady = storeProbeValid && storeProbeHit &&
    !storeProbeNeedsReplay
  val storeRefillBlocked = storeFullHitReady && storeArrayWriteBusy &&
    state =/= DCacheEngineState.storeReadWait
  val storeRefillDifferentWay = storeFullHitReady && activeRefillBeat &&
    storeProbeHitWay =/= activeFill.way
  val fillContextsInUse = returnFillCount + pendingFillValid.asUInt
  val fillCreditAvailable = fillContextsInUse < fillContextDepth.U
  val storeSlowResourceReady = Mux(storeProbeHit,
    fillContextsInUse === 0.U, fillCreditAvailable && !pendingFillValid)
  val storeSlowStart = storeProbeValid &&
    !storeProbeHit &&
    !storeProbeNeedsReplay && state === DCacheEngineState.idle &&
    storeSlowResourceReady
  val storePartialHit = storeFastHit && !storeProbeEntry.strb.andR
  val storeMiss = storeSlowStart && !storeProbeHit
  val storeUpdateComplete = storeFastHit || storeOperationComplete

  val storeFillOutstanding = pendingFillValid &&
    pendingFill.owner === DCacheEngineOwner.store ||
    VecInit((0 until fillContextDepth).map(slot =>
      returnFillValid(slot) && returnFillContexts(slot).owner ===
        DCacheEngineOwner.store)).asUInt.orR

  val storeNewestEntry = deeperStoreEntries.map(entries =>
    Mux1H((0 until storeUpdateDepth).map(slot =>
      (storeUpdateCount === (slot + 1).U) -> entries(slot))))
    .getOrElse(Mux(storeUpdateSecondValid, storeUpdateSecondEntry, storeUpdateHeadEntry))
  val storeHeadOwned = storeProbeValid ||
    (owner === DCacheEngineOwner.store && state =/= DCacheEngineState.idle) ||
    storeFillOutstanding
  val storeNewestMergeSafe = storeUpdateHeadValid &&
    (storeUpdateCount > 1.U || !storeHeadOwned)
  val storeInputSameWord = storeNewestEntry.addr(p.xLen - 1, 2) ===
    io.storeAddr(p.xLen - 1, 2)
  io.storeReady := storeQueueValidBits.map(bits => !bits(storeUpdateDepth - 1))
    .getOrElse(storeUpdateCount < storeUpdateDepth.U)
  val storeInputAccept = io.storeValid && io.storeReady
  val storeUpdateMerge = storeInputAccept && storeNewestMergeSafe &&
    storeInputSameWord
  val storeUpdateEnq = storeInputAccept && !storeUpdateMerge
  val storeMergedData = mergeBytes(
    storeNewestEntry.data, io.storeData, io.storeStrb)
  val storeMergedStrb = storeNewestEntry.strb | io.storeStrb
  // The queue count identifies the newest entry directly for these probe
  // cases. Keep probe capture off the variable-index newest-entry mux.
  val storeUpdateMergeHead = storeInputAccept && storeUpdateHeadValid &&
    storeUpdateCount === 1.U && !storeHeadOwned &&
    storeUpdateHeadEntry.addr(p.xLen - 1, 2) ===
      io.storeAddr(p.xLen - 1, 2)
  val storeUpdateMergeSecond = storeInputAccept && storeUpdateHeadValid &&
    storeUpdateCount === 2.U &&
    storeUpdateSecondEntry.addr(p.xLen - 1, 2) ===
      io.storeAddr(p.xLen - 1, 2)
  val storeUpdateEnqHead = storeInputAccept && storeUpdateCount === 1.U &&
    !storeUpdateMergeHead
  val storeUpdateEnqEmpty = storeInputAccept && storeUpdateCount === 0.U

  if (storeUpdateDepth == 2) {
    when(storeUpdateMerge) {
      when(storeUpdateSecondValid) {
        storeUpdateSecondEntry.data := storeMergedData
        storeUpdateSecondEntry.strb := storeMergedStrb
      }.otherwise {
        storeUpdateHeadEntry.data := storeMergedData
        storeUpdateHeadEntry.strb := storeMergedStrb
      }
    }.elsewhen(storeUpdateComplete && storeUpdateEnq) {
      assert(storeUpdateCount === 1.U)
      storeUpdateHeadEntry.addr := io.storeAddr
      storeUpdateHeadEntry.data := io.storeData
      storeUpdateHeadEntry.strb := io.storeStrb
    }.elsewhen(storeUpdateEnq) {
      when(storeUpdateCount === 0.U) {
        storeUpdateHeadEntry.addr := io.storeAddr
        storeUpdateHeadEntry.data := io.storeData
        storeUpdateHeadEntry.strb := io.storeStrb
      }.otherwise {
        storeUpdateSecondEntry.addr := io.storeAddr
        storeUpdateSecondEntry.data := io.storeData
        storeUpdateSecondEntry.strb := io.storeStrb
      }
    }.elsewhen(storeUpdateComplete && storeUpdateSecondValid) {
      storeUpdateHeadEntry := storeUpdateSecondEntry
    }
  } else {
    val entries = deeperStoreEntries.get
    when(storeUpdateComplete) {
      for (slot <- 0 until storeUpdateDepth - 1) {
        entries(slot) := entries(slot + 1)
      }
    }
    for (slot <- 0 until storeUpdateDepth) {
      // Decode positions from registered count in parallel. Completion chooses
      // Boolean position predicates rather than feeding a subtract/decode path.
      val mergeHere = Mux(storeUpdateComplete,
        storeUpdateCount === (slot + 2).U, storeUpdateCount === (slot + 1).U)
      val insertHere = Mux(storeUpdateComplete,
        storeUpdateCount === (slot + 1).U, storeUpdateCount === slot.U)
      when(storeUpdateMerge && mergeHere) {
        entries(slot).data := storeMergedData
        entries(slot).strb := storeMergedStrb
      }
      when(storeUpdateEnq && insertHere) {
        entries(slot).addr := io.storeAddr
        entries(slot).data := io.storeData
        entries(slot).strb := io.storeStrb
      }
    }
    when(storeUpdateMerge) { assert(storeUpdateCount > storeUpdateComplete.asUInt) }

  }
  when(storeUpdateEnq =/= storeUpdateComplete) {
    storeUpdateCount := Mux(storeUpdateEnq,
      storeUpdateCount + 1.U, storeUpdateCount - 1.U)
  }

  storeQueueValidBits.foreach { bits =>
    when(storeUpdateEnq =/= storeUpdateComplete) {
      for (slot <- 0 until storeUpdateDepth) {
        val grown = if (slot == 0) true.B else bits(slot - 1)
        val shrunk = if (slot == storeUpdateDepth - 1) false.B else bits(slot + 1)
        bits(slot) := Mux(storeUpdateEnq, grown, shrunk)
      }
    }
    val checkValid = RegNext(true.B, false.B)
    when(checkValid) {
      for (slot <- 0 until storeUpdateDepth) {
        assert(bits(slot) === (storeUpdateCount > slot.U))
      }
    }
  }

  if (storeUpdateDepth > 2) {
    // The active bank tracks the held demand owner; the inactive bank prepares
    // the new candidate on every edge. Late admission only flips ownership.
    // Queue shift/insertion and both word comparisons settle independently of
    // lookupReadFire, preserving the original demand/queue visibility edge.
    val owner = forwardMatchOwner.get
    val banks = forwardQueueMatchBanks.get
    val lastBanks = forwardLastMatchBanks.get
    val currentMatches = banks(owner)
    val queryMatches = storeQueueEntries.map(entry =>
      entry.addr(p.xLen - 1, 2) === io.lookupReadAddr(p.xLen - 1, 2))
    val incomingQuery = io.storeAddr(p.xLen - 1, 2) === io.lookupReadAddr(p.xLen - 1, 2)
    val incomingHeld = io.storeAddr(p.xLen - 1, 2) === io.lookupAddr(p.xLen - 1, 2)
    val slowWrite = state === DCacheEngineState.storeReadWait
    val lastQuery = Mux(slowWrite,
      opAddr(p.xLen - 1, 2) === io.lookupReadAddr(p.xLen - 1, 2),
      storeProbeEntry.addr(p.xLen - 1, 2) === io.lookupReadAddr(p.xLen - 1, 2))
    val lastHeld = Mux(slowWrite,
      opAddr(p.xLen - 1, 2) === io.lookupAddr(p.xLen - 1, 2),
      storeProbeEntry.addr(p.xLen - 1, 2) === io.lookupAddr(p.xLen - 1, 2))
    for (bank <- 0 until 2) {
      val isActive = owner === bank.U
      val beforeMove = (0 until storeUpdateDepth).map(slot =>
        Mux(isActive, currentMatches(slot), queryMatches(slot)))
      val beforeMoveBytes = (0 until storeUpdateDepth).map(slot =>
        Mux(storeQueueValidBits.get(slot) && beforeMove(slot),
          storeQueueEntries(slot).strb, 0.U(p.strbBits.W)))
      val incomingMatch = Mux(isActive, incomingHeld, incomingQuery)
      for (slot <- 0 until storeUpdateDepth) {
        val moved = if (slot < storeUpdateDepth - 1)
          Mux(storeUpdateComplete, beforeMove(slot + 1), beforeMove(slot))
          else beforeMove(slot)
        val insertHere = Mux(storeUpdateComplete,
          storeUpdateCount === (slot + 1).U, storeUpdateCount === slot.U)
        banks(bank)(slot) := Mux(storeUpdateEnq && insertHere,
          incomingMatch, moved)
        val shiftedBytes = if (slot + 1 < storeUpdateDepth) beforeMoveBytes(slot + 1)
          else 0.U(p.strbBits.W)
        val movedBytes = Mux(storeUpdateComplete, shiftedBytes, beforeMoveBytes(slot))
        val mergeHere = Mux(storeUpdateComplete,
          storeUpdateCount === (slot + 2).U, storeUpdateCount === (slot + 1).U)
        val mergedBytes = Mux(storeUpdateMerge && mergeHere && moved,
          io.storeStrb, 0.U(p.strbBits.W))
        forwardQueueByteBanks.get(bank)(slot) := Mux(storeUpdateEnq && insertHere,
          Mux(incomingMatch, io.storeStrb, 0.U(p.strbBits.W)), movedBytes | mergedBytes)
      }
      val newLastMatch = Mux(isActive, lastHeld, lastQuery)
      lastBanks(bank) := newLastMatch
      val newLastMask = Mux(slowWrite, Fill(p.strbBits, 1.U(1.W)), storeProbeEntry.strb)
      forwardLastByteBanks.get(bank) := Mux((storeFastHit || slowWrite) && newLastMatch,
        newLastMask, 0.U(p.strbBits.W))
      forwardCoverageBanks.get(bank) := VecInit((0 until p.strbBits).map { byte =>
        val remainingQueue = (0 until storeUpdateDepth).map { slot =>
          storeQueueValidBits.get(slot) && beforeMove(slot) &&
            storeQueueEntries(slot).strb(byte) &&
            (if (slot == 0) !storeUpdateComplete else true.B)
        }.foldLeft(false.B)(_ || _)
        val incoming = storeInputAccept && incomingMatch && io.storeStrb(byte)
        val lastWrite = (storeFastHit || slowWrite) && newLastMatch && newLastMask(byte)
        remainingQueue || incoming || lastWrite
      }).asUInt
    }
    when(lookupReadFire) { owner := !owner }
  }

  // 候选地址按已寄存的 probe 所有权预选，不等待本拍 tag 命中。
  // 捕获仍由原有 merge/完成/启动条件仲裁；未捕获的候选值不使用。
  val storeProbeCandidate = Wire(new DCacheStoreUpdate)
  storeProbeCandidate := storeUpdateHeadEntry
  when(storeUpdateMergeHead) {
    storeProbeCandidate.data := mergeBytes(
      storeUpdateHeadEntry.data, io.storeData, io.storeStrb)
    storeProbeCandidate.strb := storeUpdateHeadEntry.strb | io.storeStrb
  }.elsewhen(storeProbeValid && storeUpdateSecondValid) {
    storeProbeCandidate := storeUpdateSecondEntry
    if (storeUpdateDepth > 2) {
      // When the head completes while the sole remaining entry merges,
      // the next probe must capture that entry's newly merged bytes.
      when(storeUpdateMergeSecond) {
        storeProbeCandidate.data := mergeBytes(
          storeUpdateSecondEntry.data, io.storeData, io.storeStrb)
        storeProbeCandidate.strb :=
          storeUpdateSecondEntry.strb | io.storeStrb
      }
    }
  }.elsewhen(storeProbeValid && storeUpdateEnqHead) {
    storeProbeCandidate.addr := io.storeAddr
    storeProbeCandidate.data := io.storeData
    storeProbeCandidate.strb := io.storeStrb
  }.elsewhen(!storeProbeValid && storeUpdateEnqEmpty) {
    storeProbeCandidate.addr := io.storeAddr
    storeProbeCandidate.data := io.storeData
    storeProbeCandidate.strb := io.storeStrb
  }

  val storeProbeCapture =
    storeUpdateMergeHead ||
    (storeFastHit && (storeUpdateSecondValid ||
      storeUpdateEnqHead)) ||
    (!storeProbeValid && storeUpdateHeadValid && !storeFillOutstanding &&
      !(owner === DCacheEngineOwner.store &&
        state =/= DCacheEngineState.idle)) ||
    (!storeProbeValid && storeUpdateEnqEmpty)

  // Candidate address ownership is known from registered probe/queue state.
  // Incoming data is only captured when the old admission logic accepts it;
  // choosing its address earlier removes accept/merge from metadata addresses.
  val probeFromSecond = storeProbeValid && storeUpdateSecondValid
  val probeFromIncoming = (storeProbeValid && !storeUpdateSecondValid) ||
    (!storeProbeValid && !storeUpdateHeadValid)
  val earlyProbeAddress = Mux(probeFromSecond, storeUpdateSecondEntry.addr,
    Mux(probeFromIncoming, io.storeAddr, storeUpdateHeadEntry.addr))
  val storeProbeCandidateSet = setOf(earlyProbeAddress)
  when(storeProbeCapture) {
    assert(earlyProbeAddress === storeProbeCandidate.addr)
  }
  val candidateLineEngineConflict =
    prepareOwnsOpSet && opSet === storeProbeCandidateSet
  val storeProbeCaptureSafe = storeProbeCapture &&
    !candidateLineEngineConflict
  val candidateSharesFastWriteSet = storeFastHit &&
    storeProbeCandidateSet === storeProbeSet

  when(storeProbeNeedsReplay || storeSlowStart || storeFastHit) {
    storeProbeValid := false.B
  }
  when(storeProbeCaptureSafe) {
    storeProbeEntry := storeProbeCandidate
    storeProbeSetOH := UIntToOH(storeProbeCandidateSet, sets)
    storeProbeValidBits := valids(storeProbeCandidateSet).asUInt
    storeProbeDirtyBits := dirty(storeProbeCandidateSet).asUInt |
      Mux(candidateSharesFastWriteSet,
        UIntToOH(storeProbeHitWay, ways), 0.U(ways.W))
    for (way <- 0 until ways) {
      val tag = tags(way)(storeProbeCandidateSet)
      storeProbeTags(way) := tag
      storeLowTagHit(way) := tag(storeTagSplit - 1, 0) ===
        tagOf(earlyProbeAddress)(storeTagSplit - 1, 0)
    }
    storeProbeReplacementFastHit := candidateSharesFastWriteSet
    when(candidateSharesFastWriteSet) {
      storeProbeReplacementFastValue := nextWay(storeProbeHitWay)
    }
    storeProbeValid := true.B
  }

  val unprobedStorePending = !storeProbeValid &&
    (storeUpdateHeadValid || io.storeValid)
  val missHeadSet = setOf(missQueue.io.deq.bits.addr)
  val missHeadLine = lineBaseOf(missQueue.io.deq.bits.addr)
  val missHeadReservedLine = pendingFillValid &&
    lineBaseOf(pendingFill.addr) === missHeadLine ||
    VecInit((0 until fillContextDepth).map(slot =>
      returnFillValid(slot) &&
        lineBaseOf(returnFillContexts(slot).addr) === missHeadLine)).asUInt.orR
  val missHeadOwnerCompleting = activeRefillBeat && io.readRespLast &&
    lineBaseOf(activeFill.addr) === missHeadLine
  waiterDetach := missQueue.io.deq.valid && missHeadReservedLine &&
    !missHeadOwnerCompleting && !waiterValid(missQueue.io.deq.bits.id)
  when(waiterDetach) {
    assert(!completionValid(missQueue.io.deq.bits.id))
    waiterValid(missQueue.io.deq.bits.id) := true.B
    waiterAddr(missQueue.io.deq.bits.id) := missQueue.io.deq.bits.addr
    waiterWord(missQueue.io.deq.bits.id) := wordOf(missQueue.io.deq.bits.addr)
  }
  val missHeadFillCommitConflict = activeRefillBeat && io.readRespLast &&
    activeFill.set === missHeadSet
  loadMissStart := missQueue.io.deq.valid &&
    state === DCacheEngineState.idle && !storeSlowStart &&
    !unprobedStorePending &&
    fillCreditAvailable && !pendingFillValid &&
    !missHeadReservedLine && !missHeadFillCommitConflict

  io.lookupReadReady := true.B

  when(storeFastHit) {
    fastStoreWriteValid := true.B
    metadataWrite(dirtyWrites(0), storeProbeSet, storeProbeHitWay, 1.U,
      Some(storeProbeSetOH), Some(fastStoreWriteWays))
    metadataWrite(replacementWrites(0), storeProbeSet, 0.U,
      nextWay(storeProbeHitWay), Some(storeProbeSetOH))
    if (p.enableDifftest) { assert(storeProbeSetOH === UIntToOH(storeProbeSet, sets)) }
    lastStoreWriteValid := true.B
    lastStoreWriteAddr := storeProbeEntry.addr
    lastStoreWriteData := storeProbeEntry.data
    lastStoreWriteStrb := storeProbeEntry.strb
    when(recentLineValid &&
        recentLineBase === lineBaseOf(storeProbeEntry.addr)) {
      recentLineWriteValid := true.B
      recentLineWriteIndex := wordOf(storeProbeEntry.addr)
      recentLineWriteData := mergeBytes(
        recentLine(wordOf(storeProbeEntry.addr)),
        storeProbeEntry.data, storeProbeEntry.strb)
    }
  }

  when(storeSlowStart) {
    owner := DCacheEngineOwner.store
    opAddr := storeProbeEntry.addr
    opSet := storeProbeSet
    opTag := storeProbeTag
    opWay := Mux(storeProbeHit, storeProbeHitWay, storeProbeVictimWay)
    opStoreHit := storeProbeHit
    opVictimDirty := storeProbeVictimDirty
    opVictimBase := storeProbeVictimBase
    opStoreData := storeProbeEntry.data
    opStoreStrb := storeProbeEntry.strb
    criticalWord := wordOf(storeProbeEntry.addr)
    refillCount := 0.U
    when(storeProbeHit) {
      internalReadValid := true.B
      internalReadWay := storeProbeHitWay
      internalReadIndex := dataIndex(storeProbeEntry.addr)
      state := DCacheEngineState.storeReadWait
    }.otherwise {
      state := DCacheEngineState.storeDecision
    }
  }.elsewhen(loadMissStart) {
    val loadStartSet = setOf(missQueue.io.deq.bits.addr)
    val storeFastHitSameSet = storeFastHit && storeProbeSet === loadStartSet
    owner := DCacheEngineOwner.load
    opAddr := missQueue.io.deq.bits.addr
    opSet := loadStartSet
    opTag := tagOf(missQueue.io.deq.bits.addr)
    loadProbeValidBits := valids(loadStartSet).asUInt
    loadProbeDirtyBits := dirty(loadStartSet).asUInt |
      Mux(storeFastHitSameSet,
        UIntToOH(storeProbeHitWay, ways), 0.U(ways.W))
    loadProbeTags := VecInit(tags.map(_(loadStartSet)))
    loadProbeReplacement := Mux(storeFastHitSameSet,
      nextWay(storeProbeHitWay), replacement(loadStartSet))
    opLoadId := missQueue.io.deq.bits.id
    criticalWord := wordOf(missQueue.io.deq.bits.addr)
    refillCount := 0.U
    state := DCacheEngineState.loadMissSelect
  }.elsewhen(maintenanceFire) {
    maintenanceAll := io.maintenanceAll
    maintenanceIndex := io.maintenanceIndex
    maintenanceWriteback := io.maintenanceWriteback
    maintenanceInvalidate := io.maintenanceInvalidate
    maintenanceAddr := io.maintenanceAddr
    maintenanceSet := Mux(io.maintenanceAll, 0.U, setOf(io.maintenanceAddr))
    maintenanceWay := 0.U
    state := DCacheEngineState.maintenanceProbe
  }

  when(state === DCacheEngineState.loadMissSelect) {
    when(loadResidentHit || loadVictimAvailable) {
      opLoadResidentHit := loadResidentHit
      opWay := Mux(loadResidentHit, loadResidentWay, loadVictimWay)
      opVictimDirty := loadVictimDirty
      opVictimBase := loadVictimBase
      state := DCacheEngineState.loadMissDecision
    }.otherwise {
      loadProbeValidBits := valids(opSet).asUInt
      loadProbeDirtyBits := dirty(opSet).asUInt
      loadProbeTags := VecInit(tags.map(_(opSet)))
      loadProbeReplacement := replacement(opSet)
    }
  }

  when(state === DCacheEngineState.loadMissDecision) {
    when(opLoadResidentHit) {
      when(fillContextsInUse === 0.U) {
        internalReadValid := true.B
        internalReadWay := opWay
        internalReadIndex := dataIndex(opSet, criticalWord)
        state := DCacheEngineState.loadMissHitReadWait
      }
    }.otherwise {
      when(!opVictimDirty || wbHasSpace) {
        fillReserveValid := true.B
        fillReserveSet := opSet
        fillReserveWay := opWay
        pendingFill.owner := DCacheEngineOwner.load
        pendingFill.addr := opAddr
        pendingFill.set := opSet
        pendingFill.tag := opTag
        pendingFill.way := opWay
        pendingFill.loadId := opLoadId
        pendingFill.storeData := 0.U
        pendingFill.storeStrb := 0.U
        pendingFill.victimDirty := opVictimDirty
        pendingFill.victimBase := opVictimBase
        pendingFill.wbIdx := wbTail
        pendingFillValid := true.B
        when(opVictimDirty) {
          allocateWriteback(opSet, opWay, opVictimBase,
            false.B, false.B)
        }
        metadataWrite(validWrites(0), opSet, opWay, 0.U)
        metadataWrite(dirtyWrites(1), opSet, opWay, 0.U)
        when(recentLineValid && recentLineBase === opVictimBase) {
          recentLineValid := false.B
        }
        state := DCacheEngineState.idle
      }
    }
  }

  when(state === DCacheEngineState.loadMissHitReadWait) {
    queuedHitData := Mux1H(UIntToOH(opWay, ways), engineReadWords)
    state := DCacheEngineState.loadMissHitResponse
  }

  when(state === DCacheEngineState.storeDecision) {
    when(opStoreHit) {
      assert(false.B)
    }.elsewhen(!opVictimDirty || wbHasSpace) {
      fillReserveValid := true.B
      fillReserveSet := opSet
      fillReserveWay := opWay
      pendingFill.owner := DCacheEngineOwner.store
      pendingFill.addr := opAddr
      pendingFill.set := opSet
      pendingFill.tag := opTag
      pendingFill.way := opWay
      pendingFill.loadId := 0.U
      pendingFill.storeData := opStoreData
      pendingFill.storeStrb := opStoreStrb
      pendingFill.victimDirty := opVictimDirty
      pendingFill.victimBase := opVictimBase
      pendingFill.wbIdx := wbTail
      pendingFillValid := true.B
      when(opVictimDirty) {
        allocateWriteback(opSet, opWay, opVictimBase, false.B, false.B)
      }
      metadataWrite(validWrites(1), opSet, opWay, 0.U)
      metadataWrite(dirtyWrites(2), opSet, opWay, 0.U)
      when(recentLineValid && recentLineBase === opVictimBase) {
        recentLineValid := false.B
      }
      state := DCacheEngineState.idle
    }
  }

  val fillReserveVictimValid = valids(fillReserveSet)(fillReserveWay)
  val fillReserveVictimTag = selectedTag(fillReserveSet, fillReserveWay)
  val invalidatesHeldLookup = fillReserveValid &&
    lookupResponseSet === fillReserveSet && lookupRawWayHit(fillReserveWay)
  val invalidatesNewLookup = fillReserveValid && lookupReadFire &&
    lookupReadSet === fillReserveSet && fillReserveVictimValid &&
    fillReserveVictimTag === tagOf(io.lookupReadAddr)
  when(lookupReadFire) {
    lookupSnapshotInvalidated := invalidatesNewLookup
  }.elsewhen(invalidatesHeldLookup) {
    lookupSnapshotInvalidated := true.B
  }

  when(state === DCacheEngineState.storeReadWait) {
    val storeOldWord = Mux1H(UIntToOH(opWay, ways), engineReadWords)
    val storeNewWord = mergeBytes(storeOldWord, opStoreData, opStoreStrb)
    arrayWriteValid := true.B
    arrayWriteWay := opWay
    arrayWriteIndex := dataIndex(opSet, criticalWord)
    arrayWriteData := storeNewWord
    metadataWrite(dirtyWrites(3), opSet, opWay, 1.U)
    metadataWrite(replacementWrites(1), opSet, 0.U, nextWay(opWay))
    when(recentLineValid && recentLineBase === lineBaseOf(opAddr)) {
      recentLineWriteValid := true.B
      recentLineWriteIndex := criticalWord
      recentLineWriteData := storeNewWord
    }
    lastStoreWriteValid := true.B
    lastStoreWriteAddr := opAddr

    lastStoreWriteData := storeNewWord
    lastStoreWriteStrb := Fill(p.strbBits, 1.U(1.W))
    storeOperationComplete := true.B
    state := DCacheEngineState.idle
  }

  def finishMaintenanceEntry(): Unit = {
    when(maintenanceAll) {
      when(maintenanceWay === (ways - 1).U) {
        maintenanceWay := 0.U
        when(maintenanceSet === (sets - 1).U) {
          state := DCacheEngineState.idle
          maintenanceDoneReg := true.B
        }.otherwise {
          maintenanceSet := maintenanceSet + 1.U
          state := DCacheEngineState.maintenanceProbe
        }
      }.otherwise {
        maintenanceWay := maintenanceWay + 1.U
        state := DCacheEngineState.maintenanceProbe
      }
    }.elsewhen(maintenanceIndex) {
      when(maintenanceWay === (ways - 1).U) {
        state := DCacheEngineState.idle
        maintenanceDoneReg := true.B
      }.otherwise {
        maintenanceWay := maintenanceWay + 1.U
        state := DCacheEngineState.maintenanceProbe
      }
    }.otherwise {
      state := DCacheEngineState.idle
      maintenanceDoneReg := true.B
    }
  }

  val maintenanceProbeSet = Mux(maintenanceAll || maintenanceIndex,
    maintenanceSet, setOf(maintenanceAddr))

  when(state === DCacheEngineState.maintenanceProbe) {
    maintenanceProbeSetReg := maintenanceProbeSet
    maintenanceProbeTagReg := tagOf(maintenanceAddr)
    maintenanceProbeValidBits := valids(maintenanceProbeSet).asUInt
    maintenanceProbeDirtyBits := dirty(maintenanceProbeSet).asUInt
    maintenanceProbeTags := VecInit(tags.map(_(maintenanceProbeSet)))
    state := DCacheEngineState.maintenanceSelect
  }

  val maintenanceProbeHitWays = VecInit((0 until ways).map(way =>
    maintenanceProbeValidBits(way) &&
      maintenanceProbeTags(way) === maintenanceProbeTagReg))
  val maintenanceProbeHit = maintenanceProbeHitWays.asUInt.orR
  val maintenanceSelectedWay = Mux(maintenanceAll || maintenanceIndex,
    maintenanceWay, OHToUInt(maintenanceProbeHitWays))
  val maintenanceSelectedValid = Mux(maintenanceAll || maintenanceIndex,
    maintenanceProbeValidBits(maintenanceSelectedWay), maintenanceProbeHit)

  when(state === DCacheEngineState.maintenanceSelect) {
    maintenanceTargetSetReg := maintenanceProbeSetReg
    maintenanceTargetWayReg := maintenanceSelectedWay
    maintenanceTargetValidReg := maintenanceSelectedValid
    state := DCacheEngineState.maintenanceMetadata
  }

  when(state === DCacheEngineState.maintenanceMetadata) {
    maintenanceTargetDirtyReg := maintenanceTargetValidReg &&
      maintenanceProbeDirtyBits(maintenanceTargetWayReg)
    maintenanceTargetLineBaseReg := lineAddress(
      maintenanceProbeTags(maintenanceTargetWayReg),
      maintenanceTargetSetReg)
    state := DCacheEngineState.maintenanceCheck
  }

  when(state === DCacheEngineState.maintenanceCheck) {
    when(!maintenanceTargetValidReg) {
      finishMaintenanceEntry()
    }.elsewhen(maintenanceTargetDirtyReg && maintenanceWriteback) {
      when(wbHasSpace) {
        owner := DCacheEngineOwner.maintenance
        opSet := maintenanceTargetSetReg
        opWay := maintenanceTargetWayReg
        wbCaptureIdx := wbTail
        wbCaptureSet := maintenanceTargetSetReg
        wbCaptureWay := maintenanceTargetWayReg
        wbCaptureIssueWord := 0.U
        wbCaptureReceiveWord := 0.U
        wbCaptureIssueDone := false.B
        wbCapturePipeValid := false.B
        allocateWriteback(maintenanceTargetSetReg,
          maintenanceTargetWayReg, maintenanceTargetLineBaseReg,
          true.B, maintenanceInvalidate)
        state := DCacheEngineState.writebackCapture
      }
    }.otherwise {
      metadataWrite(dirtyWrites(4), maintenanceTargetSetReg, maintenanceTargetWayReg, 0.U)
      when(maintenanceInvalidate) {
        metadataWrite(validWrites(2), maintenanceTargetSetReg, maintenanceTargetWayReg, 0.U)
        when(recentLineValid &&
            recentLineBase === maintenanceTargetLineBaseReg) {
          recentLineValid := false.B
        }
      }
      finishMaintenanceEntry()
    }
  }

  val wbCaptureIssue = state === DCacheEngineState.writebackCapture &&
    !wbCaptureIssueDone
  when(wbCaptureIssue) {
    internalReadValid := true.B
    internalReadWay := wbCaptureWay
    internalReadIndex := dataIndex(wbCaptureSet, wbCaptureIssueWord)
    when(wbCaptureIssueWord === (lineWords - 1).U) {
      wbCaptureIssueDone := true.B
    }.otherwise {
      wbCaptureIssueWord := wbCaptureIssueWord + 1.U
    }
  }

  when(state === DCacheEngineState.writebackCapture) {
    wbCapturePipeValid := wbCaptureIssue
    when(wbCapturePipeValid) {
      when(wbCaptureReceiveWord === (lineWords - 1).U) {
        wbCaptured(wbCaptureIdx) := true.B
        wbCapturePipeValid := false.B
        state := DCacheEngineState.maintenanceWritebackWait
      }.otherwise {
        wbCaptureReceiveWord := wbCaptureReceiveWord + 1.U
      }
    }
  }

  val wbHeadValid = wbBusy && wbValid(wbHead)
  val wbHeadLineBase = wbLineBases(wbHead)
  val wbCommandValid = wbValid(wbCommandHead) &&
    wbCaptured(wbCommandHead) && !wbCommandIssued(wbCommandHead)
  io.write.req_valid := wbCommandValid
  io.write.req_addr := wbLineBases(wbCommandHead)
  io.write.req_size := 2.U
  io.write.req_len := (lineWords - 1).U
  io.write.req_burst := Axi4Const.BurstIncr
  val wbCommandFire = io.write.req_valid && io.write.req_ready
  when(wbCommandFire) {
    wbCommandIssued(wbCommandHead) := true.B
    wbCommandHead := (wbCommandHead + 1.U)(writebackPtrBits - 1, 0)
  }

  val wbDataValid = wbValid(wbDataHead) && wbCaptured(wbDataHead) &&
    wbCommandIssued(wbDataHead) && !wbDataComplete(wbDataHead)
  io.write.data_valid := wbDataValid
  io.write.data_bits := wbWords(wbDataIndex(wbDataHead, wbSendWord))
  io.write.data_strb := Fill(p.strbBits, 1.U(1.W))
  io.write.data_last := wbSendWord === (lineWords - 1).U

  when(io.write.data_valid && io.write.data_ready) {
    assert(io.write.data_last === (wbSendWord === (lineWords - 1).U))
    when(io.write.data_last) {
      wbDataComplete(wbDataHead) := true.B
      wbDataHead := (wbDataHead + 1.U)(writebackPtrBits - 1, 0)
      wbSendWord := 0.U
    }.otherwise {
      wbSendWord := wbSendWord + 1.U
    }
  }

  io.write.resp_ready := wbHeadValid && wbCaptured(wbHead) &&
    wbCommandIssued(wbHead) && wbDataComplete(wbHead)
  val wbComplete = io.write.resp_valid && io.write.resp_ready
  when(wbComplete) {
    assert(wbHeadValid && wbCaptured(wbHead))
    wbValid(wbHead) := false.B
    wbCaptured(wbHead) := false.B
    wbCommandIssued(wbHead) := false.B
    wbDataComplete(wbHead) := false.B
    wbHead := (wbHead + 1.U)(writebackPtrBits - 1, 0)
    when(wbMaintenance(wbHead)) {
      metadataWrite(dirtyWrites(5), wbMaintenanceSet(wbHead), wbMaintenanceWay(wbHead), 0.U)
      when(wbMaintenanceInvalidate(wbHead)) {
        metadataWrite(validWrites(3), wbMaintenanceSet(wbHead), wbMaintenanceWay(wbHead), 0.U)
        when(recentLineValid && recentLineBase === wbHeadLineBase) {
          recentLineValid := false.B
        }
      }
      assert(state === DCacheEngineState.maintenanceWritebackWait)
      finishMaintenanceEntry()
    }
  }

  when(wbAllocate =/= wbComplete) {
    wbCount := Mux(wbAllocate, wbCount + 1.U, wbCount - 1.U)
  }

  val pendingRefillBlockedByWriteback = VecInit(
    (0 until writebackDepth).map(entry =>
      wbValid(entry) &&
        wbLineBases(entry) === lineBaseOf(pendingFill.addr))).asUInt.orR
  io.readReqIsLoad := pendingFill.owner === DCacheEngineOwner.load
  io.readReqLoadId := pendingFill.loadId
  io.readReqValid := pendingFillValid &&
    !pendingRefillBlockedByWriteback
  io.readReqAddr := Cat(
    pendingFill.addr(p.xLen - 1, p.dcacheWordOffsetBits),
    0.U(p.dcacheWordOffsetBits.W))
  io.readReqLen := (lineWords - 1).U
  io.readReqBurst := (if (lineWords == 1) Axi4Const.BurstIncr
    else Axi4Const.BurstWrap)
  val fillIssue = io.readReqValid && io.readReqReady
  val activeFillCompletesNow = activeFillValid && io.readRespValid &&
    io.readRespReady && io.readRespLast
  val pendingVictimCanPrime = returnFillCount === 0.U ||
    (returnFillCount === 1.U && activeFillCompletesNow)
  pendingVictimPrime.valid := pendingFillValid && pendingFill.victimDirty &&
    !pendingVictimPrimed && pendingVictimCanPrime
  pendingVictimPrime.bits.way := pendingFill.way
  pendingVictimPrime.bits.index := dataIndex(
    pendingFill.set, wordOf(pendingFill.addr))
  when(pendingVictimPrime.valid) {
    pendingVictimPrimed := true.B
  }

  when(fillIssue) {
    assert(!returnFillValid(returnFillTail))
    returnFillContexts(returnFillTail) := pendingFill
    returnFillValid(returnFillTail) := true.B
    returnFillTail :=
      (returnFillTail + 1.U)(fillContextPtrBits - 1, 0)
    pendingFillValid := false.B
    pendingVictimPrimed := false.B
    recentLineValid := false.B
    when(returnFillCount === 0.U) {
      activeRefillCount := 0.U
      activeReceivedWords := 0.U
      assert(!pendingFill.victimDirty || pendingVictimPrimed ||
        pendingVictimPrime.valid)
      victimCapturePipeValid := pendingFill.victimDirty
    }
  }

  val activeCriticalWord = wordOf(activeFill.addr)
  val refillWord = if (lineWords == 1) {
    0.U(p.dcacheLineWordBits.W)
  } else {
    (activeCriticalWord + activeRefillCount)(p.dcacheLineWordBits - 1, 0)
  }
  io.readRespReady := activeFillValid && !completedLineDrainValid &&
    (!activeFill.victimDirty || victimCapturePipeValid)
  val refillResponse = activeFillValid &&
    io.readRespValid && io.readRespReady
  val victimRefillResponse = refillResponse && activeFill.victimDirty
  val ownerCriticalResponse = refillResponse &&
    activeFill.owner === DCacheEngineOwner.load &&
      activeRefillCount === 0.U
  when(refillResponse) {
    activeReceivedWords := activeReceivedWords |
      UIntToOH(refillWord, lineWords)
  }

  val activeWaiterReady = VecInit((0 until p.dcacheMshrEntries).map(id => {
    val waiterWord = wordOf(waiterAddr(id))
    waiterValid(id) && activeFillValid &&
      activeFill.owner === DCacheEngineOwner.load &&
      lineBaseOf(waiterAddr(id)) === lineBaseOf(activeFill.addr) &&
      ((refillResponse && refillWord === waiterWord) ||
        (if (lineWords == 1) activeReceivedWords(0) else activeReceivedWords(waiterWord)))
  })).asUInt
  val drainWaiterReady = VecInit((0 until p.dcacheMshrEntries).map(id =>
    waiterValid(id) && completedLineDrainValid &&
      lineBaseOf(waiterAddr(id)) === completedLineDrainBase)).asUInt
  val waiterServiceMask = Mux(completedLineDrainValid,
    drainWaiterReady, activeWaiterReady)
  val waiterServiceId = PriorityEncoder(waiterServiceMask)
  // 每个 waiter 的 store-pending 资格与优先级编码并行计算，最后只选1位；
  // 避免“先选32位地址、再串30位比较”的宽 mux + 长进位链。
  val waiterStorePendingMask = VecInit((0 until p.dcacheMshrEntries).map(id =>
    VecInit(storeForwardCandidates.map { case (valid, addr, _, _) =>
      valid && addr(p.xLen - 1, 2) === waiterAddr(id)(p.xLen - 1, 2)
    }).asUInt.orR))
  val waiterStorePending = waiterStorePendingMask(waiterServiceId)
  val waiterServiceValid = waiterServiceMask.orR && !waiterStorePending
  val waiterServiceWord = waiterWord(waiterServiceId)
  val waiterServiceLive = !completedLineDrainValid && refillResponse &&
    refillWord === waiterServiceWord
  val waiterServiceData = Mux(waiterServiceLive,
    io.readRespData, recentLine(waiterServiceWord))
  val waiterServiceOh = UIntToOH(waiterServiceId, p.dcacheMshrEntries)
  val activeLineWaiters = VecInit((0 until p.dcacheMshrEntries).map(id =>
    waiterValid(id) &&
      lineBaseOf(waiterAddr(id)) === lineBaseOf(activeFill.addr))).asUInt
  val drainLineWaiters = VecInit((0 until p.dcacheMshrEntries).map(id =>
    waiterValid(id) &&
      lineBaseOf(waiterAddr(id)) === completedLineDrainBase)).asUInt
  val activeLineWaitersAfterService = activeLineWaiters &
    ~Mux(waiterServiceValid && !completedLineDrainValid,
      waiterServiceOh, 0.U(p.dcacheMshrEntries.W))
  val drainLineWaitersAfterService = drainLineWaiters &
    ~Mux(waiterServiceValid && completedLineDrainValid,
      waiterServiceOh, 0.U(p.dcacheMshrEntries.W))

  when(waiterServiceValid) {
    assert(waiterValid(waiterServiceId))
    assert(!completionValid(waiterServiceId))
    waiterValid(waiterServiceId) := false.B
    completionValid(waiterServiceId) := true.B
    completionData(waiterServiceId) := waiterServiceData
    completionWasWaiter(waiterServiceId) := true.B
  }
  when(completedLineDrainValid &&
      (!drainLineWaiters.orR ||
        (waiterServiceValid && !drainLineWaitersAfterService.orR))) {
    completedLineDrainValid := false.B
  }

  val queuedHitCompletion =
    state === DCacheEngineState.loadMissHitResponse
  when(queuedHitCompletion) {
    assert(!completionValid(opLoadId))
    completionValid(opLoadId) := true.B
    completionData(opLoadId) := queuedHitData
    completionWasWaiter(opLoadId) := false.B
    state := DCacheEngineState.idle
  }

  // 完成 ID 与分配顺序解耦。已有结果优先，critical word 可在无积压时直达。
  // owner 只在该 fill 的第一个 beat 完成一次；后续 beat 不再引用其 loadId，
  // 因而该 ID 可在消费后重用，且不会被旧 fill 尾部再次完成。
  val completionReady = completionValid.asUInt.orR
  val completionId = PriorityEncoder(completionValid)
  val ownerCriticalDirect = ownerCriticalResponse && !completionReady
  when(ownerCriticalResponse && !ownerCriticalDirect) {
    assert(!completionValid(activeFill.loadId))
    completionValid(activeFill.loadId) := true.B
    completionData(activeFill.loadId) := io.readRespData
    completionWasWaiter(activeFill.loadId) := false.B
  }
  io.loadResp.valid := completionReady || ownerCriticalDirect
  io.loadResp.bits := Mux(completionReady,
    completionData(completionId), io.readRespData)
  io.loadRespId := Mux(completionReady, completionId, activeFill.loadId)
  when(completionReady) {
    completionValid(completionId) := false.B
    completionWasWaiter(completionId) := false.B
  }

  val refillStoreWord = activeFill.owner === DCacheEngineOwner.store &&
    refillWord === activeCriticalWord
  val refillData = Mux(refillStoreWord,
    mergeBytes(io.readRespData, activeFill.storeData, activeFill.storeStrb),
    io.readRespData)

  when(victimRefillResponse) {
    assert(wbBusy && wbValid(activeFill.wbIdx) &&
      !wbCaptured(activeFill.wbIdx))
  }

  val maintenanceCaptureMode = state === DCacheEngineState.writebackCapture
  val maintenanceCaptureWrite = maintenanceCaptureMode && wbCapturePipeValid
  assert(!(maintenanceCaptureWrite && victimRefillResponse))
  when(maintenanceCaptureWrite || victimRefillResponse) {
    wbWords.write(Mux(maintenanceCaptureMode,
      wbDataIndex(wbCaptureIdx, wbCaptureReceiveWord),
      wbDataIndex(activeFill.wbIdx, refillWord)),
      Mux1H(UIntToOH(Mux(maintenanceCaptureMode, wbCaptureWay, activeFill.way), ways),
        engineReadWords))
  }

  when(refillResponse) {
    arrayWriteValid := true.B
    arrayWriteWay := activeFill.way
    arrayWriteIndex := dataIndex(activeFill.set, refillWord)
    arrayWriteData := refillData
    recentLineWriteValid := true.B
    recentLineWriteIndex := refillWord
    recentLineWriteData := refillData
    assert(io.readRespLast ===
      (activeRefillCount === (lineWords - 1).U))
    when(io.readRespLast) {
      for (way <- 0 until ways) {
        when(activeFill.way === way.U) {
          tags(way).write(activeFill.set, activeFill.tag)
        }
      }
      metadataWrite(validWrites(4), activeFill.set, activeFill.way, 1.U)
      metadataWrite(dirtyWrites(6), activeFill.set, activeFill.way,
        (activeFill.owner === DCacheEngineOwner.store).asUInt)
      metadataWrite(replacementWrites(2), activeFill.set, 0.U, nextWay(activeFill.way))
      recentBaseUpdate.valid := true.B
      recentBaseUpdate.bits := lineBaseOf(activeFill.addr)
      completedLineDrainBase := lineBaseOf(activeFill.addr)
      completedLineDrainValid := activeLineWaitersAfterService.orR
      when(activeFill.victimDirty) {
        wbCaptured(activeFill.wbIdx) := true.B
      }
      when(activeFill.owner === DCacheEngineOwner.store) {
        storeOperationComplete := true.B
      }
      returnFillValid(returnFillHead) := false.B
      returnFillHead :=
        (returnFillHead + 1.U)(fillContextPtrBits - 1, 0)
      activeRefillCount := 0.U
      activeReceivedWords := 0.U

      val nextReturnIdx =
        (returnFillHead + 1.U)(fillContextPtrBits - 1, 0)
      val storedNextValid = returnFillCount > 1.U &&
        returnFillValid(nextReturnIdx)
      val issuedNextValid = returnFillCount === 1.U && fillIssue
      val nextFillValid = storedNextValid || issuedNextValid
      val nextFill = Wire(new DCacheFillContext)
      nextFill := Mux(storedNextValid,
        returnFillContexts(nextReturnIdx), pendingFill)
      recentLineValid := !nextFillValid
      victimCapturePipeValid := nextFillValid && nextFill.victimDirty
      when(nextFillValid && nextFill.victimDirty) {
        when(storedNextValid) {
          internalReadValid := true.B
          internalReadWay := nextFill.way
          internalReadIndex := dataIndex(
            nextFill.set, wordOf(nextFill.addr))
        }.otherwise {
          assert(pendingVictimPrimed || pendingVictimPrime.valid)
        }
      }
    }.otherwise {
      activeRefillCount := activeRefillCount + 1.U
      when(activeFill.victimDirty) {
        val nextRefillWord = if (lineWords == 1) {
          0.U(p.dcacheLineWordBits.W)
        } else {
          (activeCriticalWord + activeRefillCount + 1.U)(
            p.dcacheLineWordBits - 1, 0)
        }
        internalReadValid := true.B
        internalReadWay := activeFill.way
        internalReadIndex := dataIndex(activeFill.set, nextRefillWord)
        victimCapturePipeValid := true.B
      }
    }
  }

  when(pendingVictimPrime.valid) {
    internalReadValid := true.B
    internalReadWay := pendingVictimPrime.bits.way
    internalReadIndex := pendingVictimPrime.bits.index
  }

  val fillResponseLast = refillResponse && io.readRespLast
  when(fillIssue =/= fillResponseLast) {
    returnFillCount := Mux(fillIssue,
      returnFillCount + 1.U, returnFillCount - 1.U)
  }

  val mshrRelease = io.loadResp.valid
  when(loadMissAccept =/= mshrRelease) {
    mshrOccupancy := Mux(loadMissAccept,
      mshrOccupancy + 1.U, mshrOccupancy - 1.U)
  }
  io.mshrOccupancy := mshrOccupancy

  io.maintenanceDone := maintenanceDoneReg
  io.busy := state =/= DCacheEngineState.idle || mshrOccupancy =/= 0.U ||
    storeUpdateHeadValid || wbBusy || pendingFillValid ||
    returnFillCount =/= 0.U

  io.perf := 0.U.asTypeOf(new DCachePerfEvents)
  io.perf.fillOccupancy := fillContextsInUse
  io.perf.fillFull := fillContextsInUse === fillContextDepth.U
  io.perf.storeUpdateEnq := storeInputAccept
  io.perf.storeUpdateMerge := storeUpdateMerge
  io.perf.storeUpdateComplete := storeUpdateComplete
  io.perf.storeFastHit := storeFastHit
  io.perf.storePartialHit := storePartialHit
  io.perf.storeMiss := storeMiss
  io.perf.storeFifoFull := io.storeValid && !io.storeReady
  io.perf.storeFifoOccupancy := storeUpdateCount
  io.perf.loadRefill := fillIssue &&
    pendingFill.owner === DCacheEngineOwner.load
  io.perf.storeRefill := fillIssue &&
    pendingFill.owner === DCacheEngineOwner.store
  io.perf.victimCapture :=
    state === DCacheEngineState.writebackCapture || victimRefillResponse
  io.perf.wbFullWait := wbFull && (
    (state === DCacheEngineState.loadMissDecision && !opLoadResidentHit &&
      opVictimDirty) ||
      (state === DCacheEngineState.storeDecision && !opStoreHit &&
        opVictimDirty))
  io.perf.wbOccupancy := wbCount
  io.perf.sameLineRawWait := pendingFillValid &&
    pendingRefillBlockedByWriteback
  io.perf.sameLineRefillResponse := completionReady &&
    completionWasWaiter(completionId)
  val lineEngineActive = state =/= DCacheEngineState.idle ||
    fillContextsInUse =/= 0.U
  val missHeadMatchesFill = pendingFillValid &&
    lineBaseOf(pendingFill.addr) === missHeadLine ||
    VecInit((0 until fillContextDepth).map(slot =>
      returnFillValid(slot) &&
        lineBaseOf(returnFillContexts(slot).addr) === missHeadLine)).asUInt.orR
  val storeHeadPrepareSetConflict = prepareOwnsOpSet &&
    setOf(storeUpdateHeadEntry.addr) === opSet
  val storeHeadPrepareLineConflict = prepareOwnsOpSet &&
    lineBaseOf(storeUpdateHeadEntry.addr) === lineBaseOf(opAddr)
  val storeHeadFillSetConflict = pendingFillValid &&
    pendingFill.set === setOf(storeUpdateHeadEntry.addr) ||
    VecInit((0 until fillContextDepth).map(slot =>
      returnFillValid(slot) && returnFillContexts(slot).set ===
        setOf(storeUpdateHeadEntry.addr))).asUInt.orR
  val storeHeadFillLineConflict = pendingFillValid &&
    lineBaseOf(pendingFill.addr) === lineBaseOf(storeUpdateHeadEntry.addr) ||
    VecInit((0 until fillContextDepth).map(slot =>
      returnFillValid(slot) &&
        lineBaseOf(returnFillContexts(slot).addr) ===
          lineBaseOf(storeUpdateHeadEntry.addr))).asUInt.orR
  io.perf.storeRefillBlocked := RegNext(storeRefillBlocked, false.B)
  io.perf.storeRefillDifferentWay :=
    RegNext(storeRefillDifferentWay, false.B)
  io.perf.storeRefillParallel := RegNext(
    storeFastHit && activeRefillBeat &&
      storeProbeHitWay =/= activeFill.way, false.B)
  io.perf.sameSetFillParallel := RegNext(fillReserveValid && (
    (pendingFillValid && pendingFill.set === fillReserveSet) ||
      VecInit((0 until fillContextDepth).map(slot =>
        returnFillValid(slot) &&
          returnFillContexts(slot).set === fillReserveSet)).asUInt.orR),
    false.B)
  io.perf.waiterDetached := RegNext(waiterDetach, false.B)
  io.perf.missQueuedBehindEngine := RegNext(
    missQueue.io.deq.valid && lineEngineActive, false.B)
  io.perf.missQueuedSameLine := RegNext(
    missQueue.io.deq.valid && missHeadMatchesFill, false.B)
  io.perf.storeHeadSameSetWait := RegNext(
    storeUpdateHeadValid &&
      (storeHeadPrepareSetConflict || storeHeadFillSetConflict), false.B)
  io.perf.storeHeadSameLineWait := RegNext(
    storeUpdateHeadValid &&
      (storeHeadPrepareLineConflict || storeHeadFillLineConflict), false.B)

  when(state === DCacheEngineState.writebackCapture) {
    assert(wbBusy && wbValid(wbCaptureIdx) && !wbCaptured(wbCaptureIdx))
  }
  when(wbCommandFire) {
    assert(wbCommandValid && wbBusy)
  }
  when(io.readReqValid) {
    assert(pendingFillValid && !pendingRefillBlockedByWriteback)
  }
  when(maintenanceDoneReg) {
    assert(!wbBusy)
  }

  when(io.loadMissValid) {
    assert(!io.lookupHit)
  }
  when(io.storeValid) {
    assert(io.storeStrb.orR)
  }
  when(storeUpdateComplete) {
    assert(storeUpdateHeadValid)
  }
  assert(storeUpdateCount <= storeUpdateDepth.U)
  assert(!(loadMissStart && waiterDetach))
  assert(PopCount(waiterValid) +& PopCount(completionValid) <=
    mshrOccupancy)
  for (id <- 0 until p.dcacheMshrEntries) {
    val waiterLine = lineBaseOf(waiterAddr(id))
    val waiterHasOwner = pendingFillValid &&
      lineBaseOf(pendingFill.addr) === waiterLine ||
      VecInit((0 until fillContextDepth).map(slot =>
        returnFillValid(slot) &&
          lineBaseOf(returnFillContexts(slot).addr) === waiterLine)).asUInt.orR ||
      completedLineDrainValid && completedLineDrainBase === waiterLine
    when(waiterValid(id)) {
      assert(waiterHasOwner)
    }
  }
  assert(wbCount <= writebackDepth.U)
  assert(PopCount(wbValid) === wbCount)
  assert(returnFillCount <= fillContextDepth.U)
  assert(PopCount(returnFillValid) === returnFillCount)
  assert(fillContextsInUse <= fillContextDepth.U)
  when(internalReadValid) {
    assert(!arrayWriteValid || arrayWriteWay =/= internalReadWay ||
      arrayWriteIndex =/= internalReadIndex)
    assert(!fastStoreWriteValid || fastStoreWriteWay =/= internalReadWay ||
      fastStoreWriteIndex =/= internalReadIndex)
  }
  when(recentLineWriteValid) {
    recentLine.write(recentLineWriteIndex, recentLineWriteData)
  }
  def winningMetadata(writes: Vec[ValidIO[MetadataWrite]], set: Int,
                      way: Option[Int]): (Bool, UInt) = {
    val hits = writes.map(event => event.valid && event.bits.setsOH(set) &&
      way.map(w => event.bits.waysOH(w)).getOrElse(true.B))
    val winners = hits.indices.map(i => hits(i) &&
      !hits.drop(i + 1).foldLeft(false.B)(_ || _))
    (hits.foldLeft(false.B)(_ || _), Mux1H(winners.zip(writes.map(_.bits.data))))
  }
  for (set <- 0 until sets) {
    for (way <- 0 until ways) {
      val (validWrite, validData) = winningMetadata(validWrites, set, Some(way))
      val (dirtyWrite, dirtyData) = winningMetadata(dirtyWrites, set, Some(way))
      valids(set)(way) := validData(0) || (!validWrite && valids(set)(way))
      dirty(set)(way) := dirtyData(0) || (!dirtyWrite && dirty(set)(way))
    }
    val (write, data) = winningMetadata(replacementWrites, set, None)
    when(write) { replacement(set) := data }
  }

}
