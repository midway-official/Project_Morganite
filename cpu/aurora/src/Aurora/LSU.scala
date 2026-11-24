package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation
import UopConst._

object LoadQueueState extends ChiselEnum {
  val waitingForAddress, readyToIssue, executing, complete = Value
}

object StoreDataState extends ChiselEnum {
  val waitingForCdb, rawCaptured, ready = Value
}

class LqEntry(implicit p: AuroraConfig) extends Bundle {
  val robIdx = UInt(p.robIdxBits.W)
  val prd = UInt(p.physRegBits.W)
  val memOp = UInt(UopConst.memOpBits.W)
  val olderSqMask = UInt(p.sqDepth.W)
  val addr = UInt(p.xLen.W)
  val vaddr = UInt(p.xLen.W)
  val requiredMask = UInt(p.strbBits.W)
  val cacheable = Bool()
  val peripheral = Bool()
  val knownMiss = Bool()
  val state = LoadQueueState()
}

class SqEntry(implicit p: AuroraConfig) extends Bundle {
  val robIdx = UInt(p.robIdxBits.W)
  val prd = UInt(p.physRegBits.W)
  val memOp = UInt(UopConst.memOpBits.W)
  val addrValid = Bool()
  val addr = UInt(p.xLen.W)
  val vaddr = UInt(p.xLen.W)
  val cacheable = Bool()
  val peripheral = Bool()
  val storeData = UInt(p.xLen.W)
  val storeStrb = UInt(p.strbBits.W)
  val storeDataPrs = UInt(p.physRegBits.W)
  val dataState = StoreDataState()
}

class CommittedStoreEntry(implicit p: AuroraConfig) extends Bundle {
  val addr = UInt(p.xLen.W)
  val data = UInt(p.xLen.W)
  val strb = UInt(p.strbBits.W)
}

class LsqCommitInfo(implicit p: AuroraConfig) extends Bundle {
  val valid = Bool()
  val robIdx = UInt(p.robIdxBits.W)
  val isLoad = Bool()
  val isStore = Bool()
  // ROB 已经用预捕获的 store window 完成了该属性判定。普通 cacheable
  // store 直接使用这个 sideband，避免在提交拍重新读取 SQ metadata。
  val isCacheableStore = Bool()
}

object SystemState extends ChiselEnum {
  val idle, waitingForCommit, draining, maintaining,
    tlbAdminRequest, tlbAdminResponse, applied = Value
}

/** 等待精确提交的系统指令状态。 */
class SystemPendingEntry(implicit p: AuroraConfig) extends Bundle {
  val robIdx = UInt(p.robIdxBits.W)
  val restartPc = UInt(p.xLen.W)
  val sysOp = UInt(UopConst.sysOpBits.W)
  val csrNum = UInt(14.W)
  val writeData = UInt(p.xLen.W)
  val writeMask = UInt(p.xLen.W)
  val src2 = UInt(p.xLen.W)
  val cacopAddr = UInt(p.xLen.W)
  val cacopCode = UInt(5.W)
  val stableCounter = UInt(64.W)
}

object SequentialLoadState extends ChiselEnum {
  val idle, request, response, drainResponse = Value
}

class SequentialLoadRequest(implicit p: AuroraConfig) extends Bundle {
  val lqIdx = UInt(p.lsqIdxBits.W)
  val robIdx = UInt(p.robIdxBits.W)
  val prd = UInt(p.physRegBits.W)
  val olderSqMask = UInt(p.sqDepth.W)
  val addr = UInt(p.xLen.W)
  val memOp = UInt(UopConst.memOpBits.W)
  val cacheable = Bool()
  val peripheral = Bool()
  val forwardMask = UInt(p.strbBits.W)
  val forwardData = UInt(p.xLen.W)
}

/** 负责 load/store、地址翻译、DCache 访问和系统指令提交的 LSU。
  *
  * 投机访存由 LQ/SQ 跟踪，store 只在 ROB 顺序提交后对外可见；
  * 外设和强序写经过独立的有序提交路径。
  */
class LSU(preselectLoadGrant: Boolean = false,
          registerMissResponses: Boolean = false,
          localCdbData: Boolean = false)(implicit p: AuroraConfig) extends Module {
  private val w = p.coreWidth
  private val lqDepth = p.lqDepth
  private val sqDepth = p.sqDepth
  private val numCdb = p.numCdb
  private val storeCommitWindowDepth = p.storeCommitWindowDepth
  private val storeBufferDepth = p.committedStoreBufferDepth
  private val storeBufferPtrBits = p.committedStoreBufferPtrBits
  private val storeBufferCountBits = p.committedStoreBufferCountBits
  private val allocCountBits = p.issueCountBits
  private val dcacheMshrEntries = p.dcacheMshrEntries

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ExecIn))
    val out = Valid(new CdbEntry)
    val iqCdbPrd = Output(Vec(p.numAlu + 1, UInt(p.physRegBits.W)))
    val aluIqCdbValid = Output(Vec(p.numAlu, Bool()))
    val aluIqLoadCdbValid = Output(Vec(p.numAlu, Bool()))
    val cdbBroadcastValid = Output(Bool())
    val iqSecondSourceData = if (localCdbData) Some(Output(Vec(p.numAlu, UInt(p.xLen.W)))) else None
    val cdbData = if (localCdbData) Some(Output(Vec(p.cdbDataCopies, UInt(p.xLen.W)))) else None
    val fullFlushAhead = Input(Bool())
    val loadScheduledCdbWake = Output(Valid(new WakeEvent))
    val loadCdbIsLoad = Output(Bool())
    val cdb = Input(Vec(numCdb, new CdbEntry))
    val bus = new BusClientIO
    val instructionTranslationReq = Flipped(Decoupled(new TranslationRequest))
    val instructionTranslationResp = Decoupled(new TranslationResponse)
    val hardwareInterrupts = Input(UInt(8.W))
    val ipiInterrupt = Input(Bool())
    val interruptPending = Output(Bool())
    val idle = Output(Bool())
    val flush = Input(Bool())
    val recover = Input(Valid(UInt(p.robIdxBits.W)))

    val lsqAllocValid = Input(Vec(w, Bool()))
    val lsqAllocBits = Input(Vec(w, new RenamedUop))
    val lsqAllocStoreData = Input(Vec(w, UInt(p.xLen.W)))
    val lsqAllocStoreDataReady = Input(Vec(w, Bool()))
    val lsqAllocIdx = Output(Vec(w, UInt(p.lsqIdxBits.W)))
    val lqEnqCapacity = Output(UInt(p.lqCountBits.W))
    val sqEnqCapacity = Output(UInt(p.sqCountBits.W))
    // The rename boundary can prepare reservation checks before same-cycle
    // retirement selects the released capacity. These are registered-state
    // credits; the original post-retirement outputs remain the contract oracle.
    val lqFreeBeforeRetire = Output(UInt(p.lqCountBits.W))
    val sqFreeBeforeRetire = Output(UInt(p.sqCountBits.W))
    val lsqCreditBlocked = Output(Bool())
    val lqRelease = Output(Valid(UInt(p.robIdxBits.W)))

    val lsqQueryValid = Input(Vec(p.lsuIqDepth, Bool()))
    val lsqQueryIdx = Input(Vec(p.lsuIqDepth, UInt(p.lsqIdxBits.W)))
    val lsqQueryRobIdx = Input(Vec(p.lsuIqDepth, UInt(p.robIdxBits.W)))
    val lsqQuerySafe = Output(Vec(p.lsuIqDepth, Bool()))

    val robHeadIdx = Input(UInt(p.robIdxBits.W))
    val recoverHeadIdx = Input(UInt(p.robIdxBits.W))
    val commit = Input(Vec(w, new LsqCommitInfo))
    val lqRetireCount = Input(UInt(p.issueCountBits.W))
    val sqRetireCount = Input(UInt(p.issueCountBits.W))

    val storeCommitValid = Input(Bool())
    val storeCommitIdx = Input(UInt(p.robIdxBits.W))
    val storeBus = new BusWriteClientIO
    val storeCommitDone = Output(Bool())
    val orderedStoreCapture = Output(Bool())
    val orderedStorePending = Output(Bool())
    val orderedStoreDoneHeld = Output(Bool())
    val orderedStoreRobIdx = Output(UInt(p.robIdxBits.W))
    val orderedStoreAwFire = Output(Bool())
    val orderedStoreWFire = Output(Bool())
    val orderedStoreBFire = Output(Bool())
    val orderedStoreAddr = Output(UInt(p.xLen.W))
    val orderedStoreData = Output(UInt(p.xLen.W))
    val orderedStoreStrb = Output(UInt(p.strbBits.W))
    val storeWindowBase = Output(UInt(p.robIdxBits.W))
    val storeWindowValid = Output(Vec(storeCommitWindowDepth, Bool()))
    val storeWindowCacheable = Output(Vec(storeCommitWindowDepth, Bool()))
    val storeWindowDataReady = Output(Vec(storeCommitWindowDepth, Bool()))
    val storeCommitCapacity = Output(UInt(allocCountBits.W))

    val sysCommitValid = Input(Bool())
    val sysCommitIdx = Input(UInt(p.robIdxBits.W))
    val sysCommitDone = Output(Bool())
    val sysCommitRestartPc = Output(UInt(p.xLen.W))

    val trapCommitValid = Input(Bool())
    val trapCommit = Input(new TrapRequest)
    val trapCommitDone = Output(Bool())
    val trapCommitRestartPc = Output(UInt(p.xLen.W))

    val crmd = Output(UInt(32.W))
    val dmw0 = Output(UInt(32.W))
    val dmw1 = Output(UInt(32.W))
    val icacheInvalidateAll = Output(Bool())
    val cacop = Output(Valid(new Bundle {
      val code = UInt(5.W)
      val addr = UInt(p.xLen.W)
    }))
    val perf = Output(new LsuPerfEvents)
    val difftestStore = Output(Vec(w, new DifftestMemoryEvent))
    val difftestLoad = Output(Vec(w, new DifftestMemoryEvent))
    val difftestTrap = Output(new DifftestTrap)
    val difftestCsr = Output(new DifftestCsrState)
    val difftestTimer = Output(UInt(64.W))
    val difftestTlbfillIndex = Output(UInt(p.tlbIndexBits.W))
  })

  private val CSR_CRMD = 0x0
  private val CSR_PRMD = 0x1
  private val CSR_ECFG = 0x4
  private val CSR_ESTAT = 0x5
  private val CSR_ERA = 0x6
  private val CSR_BADV = 0x7
  private val CSR_EENTRY = 0xc
  private val CSR_TLBIDX = 0x10
  private val CSR_TLBEHI = 0x11
  private val CSR_TLBELO0 = 0x12
  private val CSR_TLBELO1 = 0x13
  private val CSR_ASID = 0x18
  private val CSR_PGDL = 0x19
  private val CSR_PGDH = 0x1a
  private val CSR_PGD = 0x1b
  private val CSR_CPUID = 0x20
  private val CSR_SAVE0 = 0x30
  private val CSR_SAVE1 = 0x31
  private val CSR_SAVE2 = 0x32
  private val CSR_SAVE3 = 0x33
  private val CSR_TID = 0x40
  private val CSR_TCFG = 0x41
  private val CSR_TVAL = 0x42
  private val CSR_TICLR = 0x44
  private val CSR_LLBCTL = 0x60
  private val CSR_TLBRENTRY = 0x88
  private val CSR_DMW0 = 0x180
  private val CSR_DMW1 = 0x181

  def wrapLq(value: UInt): UInt = LsqRing.wrap(value, lqDepth)
  def wrapSq(value: UInt): UInt = LsqRing.wrap(value, sqDepth)
  def wrapStoreBuffer(value: UInt): UInt =
    value & (storeBufferDepth - 1).U
  def wrapDcacheMshr(value: UInt): UInt =
    value & (dcacheMshrEntries - 1).U
  def alignedWord(addr: UInt): UInt = Cat(addr(p.xLen - 1, 2), 0.U(2.W))

  def storeMask(memOp: UInt, byteOffset: UInt): UInt =
    MuxLookup(memOp, "b1111".U(p.strbBits.W))(Seq(
      MEM_SB.U -> ((1.U(p.strbBits.W) << byteOffset)(p.strbBits - 1, 0)),
      MEM_SH.U -> ((3.U(p.strbBits.W) << byteOffset)(p.strbBits - 1, 0))
    ))

  def packStoreData(raw: UInt, memOp: UInt, byteOffset: UInt): UInt =
    MuxLookup(memOp, raw)(Seq(
      MEM_SB.U -> ((raw(7, 0) << (byteOffset << 3))(p.xLen - 1, 0)),
      MEM_SH.U -> ((raw(15, 0) << (byteOffset << 3))(p.xLen - 1, 0))
    ))

  def loadMask(memOp: UInt, byteOffset: UInt): UInt =
    MuxLookup(memOp, "b1111".U(p.strbBits.W))(Seq(
      MEM_LB.U -> ((1.U(p.strbBits.W) << byteOffset)(p.strbBits - 1, 0)),
      MEM_LBU.U -> ((1.U(p.strbBits.W) << byteOffset)(p.strbBits - 1, 0)),
      MEM_LH.U -> ((3.U(p.strbBits.W) << byteOffset)(p.strbBits - 1, 0)),
      MEM_LHU.U -> ((3.U(p.strbBits.W) << byteOffset)(p.strbBits - 1, 0))
    ))

  def mergeBytes(base: UInt, overlay: UInt, mask: UInt): UInt =
    Cat((0 until p.strbBits).reverse.map(byte =>
      Mux(mask(byte), overlay(8 * byte + 7, 8 * byte),
        base(8 * byte + 7, 8 * byte))))

  def formatLoad(raw: UInt, byteOffset: UInt, memOp: UInt): UInt = {
    val shifted = raw >> (byteOffset << 3)
    val byte = shifted(7, 0)
    val half = shifted(15, 0)
    MuxLookup(memOp, raw)(Seq(
      MEM_LW.U -> raw,
      MEM_LL.U -> raw,
      MEM_LB.U -> Cat(Fill(p.xLen - 8, byte(7)), byte),
      MEM_LBU.U -> Cat(0.U((p.xLen - 8).W), byte),
      MEM_LH.U -> Cat(Fill(p.xLen - 16, half(15)), half),
      MEM_LHU.U -> Cat(0.U((p.xLen - 16).W), half)
    ))
  }

  val csrCrmd = RegInit("h00000008".U(32.W))
  val csrPrmd = RegInit(0.U(32.W))
  val csrEcfg = RegInit(0.U(32.W))
  val csrEstat = RegInit(0.U(32.W))
  val csrEra = RegInit(0.U(32.W))
  val csrBadv = RegInit(0.U(32.W))
  val csrEentry = RegInit(0.U(32.W))
  val csrTlbidx = RegInit(0.U(32.W))
  val csrTlbehi = RegInit(0.U(32.W))
  val csrTlbelo0 = RegInit(0.U(32.W))
  val csrTlbelo1 = RegInit(0.U(32.W))
  val csrAsid = RegInit("h000a0000".U(32.W))
  val csrPgdl = RegInit(0.U(32.W))
  val csrPgdh = RegInit(0.U(32.W))
  val csrSave = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))
  val csrTid = RegInit(0.U(32.W))
  val csrTcfg = RegInit(0.U(32.W))
  val timerValue = RegInit(0.U(32.W))
  val timerActive = RegInit(false.B)
  val timerInterrupt = RegInit(false.B)
  val stableCounter = RegInit(0.U(64.W))
  stableCounter := stableCounter + 1.U
  val csrLlbctl = RegInit(0.U(3.W))
  val llBit = RegInit(false.B)
  val llAddress = Reg(UInt((p.xLen - 2).W))
  val csrTlbRentry = RegInit(0.U(32.W))
  val csrDmw0 = RegInit(0.U(32.W))
  val csrDmw1 = RegInit(0.U(32.W))
  io.crmd := csrCrmd
  io.dmw0 := csrDmw0
  io.dmw1 := csrDmw1

  val estatValue = (csrEstat & "h7fff0003".U) |
    (io.hardwareInterrupts << 2) | (timerInterrupt << 11) |
    (io.ipiInterrupt << 12)

  io.difftestCsr.crmd := csrCrmd
  io.difftestCsr.prmd := csrPrmd
  io.difftestCsr.euen := 0.U
  io.difftestCsr.ecfg := csrEcfg
  io.difftestCsr.estat := estatValue
  io.difftestCsr.era := csrEra
  io.difftestCsr.badv := csrBadv
  io.difftestCsr.eentry := csrEentry
  io.difftestCsr.tlbidx := csrTlbidx
  io.difftestCsr.tlbehi := csrTlbehi
  io.difftestCsr.tlbelo0 := csrTlbelo0
  io.difftestCsr.tlbelo1 := csrTlbelo1
  io.difftestCsr.asid := csrAsid
  io.difftestCsr.pgdl := csrPgdl
  io.difftestCsr.pgdh := csrPgdh
  io.difftestCsr.save0 := csrSave(0)
  io.difftestCsr.save1 := csrSave(1)
  io.difftestCsr.save2 := csrSave(2)
  io.difftestCsr.save3 := csrSave(3)
  io.difftestCsr.tid := csrTid
  io.difftestCsr.tcfg := csrTcfg
  io.difftestCsr.tval := timerValue
  io.difftestCsr.ticlr := 0.U
  val llbctlValue = Cat(csrLlbctl(2), 0.U(1.W), llBit)
  io.difftestCsr.llbctl := llbctlValue
  io.difftestCsr.tlbrentry := csrTlbRentry
  io.difftestCsr.dmw0 := csrDmw0
  io.difftestCsr.dmw1 := csrDmw1
  val interruptVector = estatValue(12, 0) & csrEcfg(12, 0)
  val interruptEligible = csrCrmd(2) && interruptVector.orR
  val interruptPendingReg = RegNext(interruptEligible, false.B)
  io.interruptPending := interruptPendingReg && csrCrmd(2)

  val idleMode = RegInit(false.B)
  io.idle := idleMode

  val mmu = Module(new MMU(stageDataTlb = true))
  mmu.io.crmd := csrCrmd
  mmu.io.dmw0 := csrDmw0
  mmu.io.dmw1 := csrDmw1
  mmu.io.asid := csrAsid(9, 0)
  mmu.io.flushLookup := io.flush
  mmu.io.lookup(0).req <> io.instructionTranslationReq
  io.instructionTranslationResp <> mmu.io.lookup(0).resp

  def csrRead(num: UInt): UInt = MuxLookup(num, 0.U(32.W))(Seq(
    CSR_CRMD.U -> csrCrmd,
    CSR_PRMD.U -> csrPrmd,
    CSR_ECFG.U -> csrEcfg,
    CSR_ESTAT.U -> estatValue,
    CSR_ERA.U -> csrEra,
    CSR_BADV.U -> csrBadv,
    CSR_EENTRY.U -> csrEentry,
    CSR_TLBIDX.U -> csrTlbidx,
    CSR_TLBEHI.U -> csrTlbehi,
    CSR_TLBELO0.U -> csrTlbelo0,
    CSR_TLBELO1.U -> csrTlbelo1,
    CSR_ASID.U -> csrAsid,
    CSR_PGDL.U -> csrPgdl,
    CSR_PGDH.U -> csrPgdh,
    CSR_PGD.U -> Mux(csrBadv(31), csrPgdh, csrPgdl),
    CSR_CPUID.U -> 0.U,
    CSR_SAVE0.U -> csrSave(0),
    CSR_SAVE1.U -> csrSave(1),
    CSR_SAVE2.U -> csrSave(2),
    CSR_SAVE3.U -> csrSave(3),
    CSR_TID.U -> csrTid,
    CSR_TCFG.U -> csrTcfg,
    CSR_TVAL.U -> timerValue,
    CSR_TICLR.U -> 0.U,
    CSR_LLBCTL.U -> llbctlValue,
    CSR_TLBRENTRY.U -> csrTlbRentry,
    CSR_DMW0.U -> csrDmw0,
    CSR_DMW1.U -> csrDmw1
  ))

  def cpuCfg(index: UInt): UInt = MuxLookup(index, 0.U(32.W))(Seq(
    0.U -> "h41555230".U,
    1.U -> "h0001f1f1".U,
    0x10.U -> 5.U,
    0x11.U -> p.iCacheCfgWord.U(32.W),
    0x12.U -> p.dCacheCfgWord.U(32.W)
  ))

  val dcache = Module(new DCache(ownedLookupSnapshot = true))

  val sysPending = Reg(new SystemPendingEntry)
  val systemState = RegInit(SystemState.idle)
  val sysPendingValid = systemState =/= SystemState.idle

  when(timerActive) {
    when(timerValue <= 1.U) {
      timerInterrupt := true.B
      when(csrTcfg(1)) {
        timerValue := Cat(csrTcfg(31, 2), 0.U(2.W))
      }.otherwise {
        timerValue := 0.U
        timerActive := false.B
      }
    }.otherwise {
      timerValue := timerValue - 1.U
    }
  }

  val lqEntries = Reg(Vec(lqDepth, new LqEntry))
  val lqValids = RegInit(VecInit(Seq.fill(lqDepth)(false.B)))
  val lqHead = RegInit(0.U(p.lsqIdxBits.W))
  val lqTail = RegInit(0.U(p.lsqIdxBits.W))
  val lqCount = RegInit(0.U(p.lqCountBits.W))
  // 地址匹配增量更新：一次翻译只比较一行/列，不生成 LQ×SQ×32 位全组合 CAM。
  val lqSqKnown = Reg(Vec(lqDepth, Vec(sqDepth, Bool())))
  val lqSqMatch = Reg(Vec(lqDepth, Vec(sqDepth, Bool())))
  val lqSqOrdered = Reg(Vec(lqDepth, Vec(sqDepth, Bool())))
  val readyLoadPresent = VecInit((0 until lqDepth).map(slot =>
    lqValids(slot) && lqEntries(slot).state === LoadQueueState.readyToIssue)).asUInt.orR

  val sqEntries = Reg(Vec(sqDepth, new SqEntry))
  val sqValids = RegInit(VecInit(Seq.fill(sqDepth)(false.B)))
  val sqOlderThan = RegInit(VecInit(Seq.fill(sqDepth)(
    VecInit(Seq.fill(sqDepth)(false.B)))))
  val sqHead = RegInit(0.U(p.lsqIdxBits.W))
  val sqYoungerSegment = RegInit(0.U(sqDepth.W))
  val sqTail = RegInit(0.U(p.lsqIdxBits.W))
  val sqCount = RegInit(0.U(p.sqCountBits.W))

  private val cdbLaneBits = log2Ceil(numCdb max 2)
  val sqCdbValid = RegInit(VecInit(Seq.fill(numCdb)(false.B)))
  val sqCdbPrd = Reg(Vec(numCdb, UInt(p.physRegBits.W)))
  val sqCdbData = Reg(Vec(numCdb, UInt(p.xLen.W)))
  val sqCdbReplayData = Reg(Vec(numCdb, UInt(p.xLen.W)))
  val sqDataTokenValid = RegInit(VecInit(Seq.fill(sqDepth)(false.B)))
  val sqDataTokenLane = Reg(Vec(sqDepth, UInt(cdbLaneBits.W)))

  for (c <- 0 until numCdb) {
    sqCdbValid(c) := io.cdb(c).valid && io.cdb(c).prd =/= 0.U
    sqCdbPrd(c) := io.cdb(c).prd
    sqCdbData(c) := io.cdb(c).data
    sqCdbReplayData(c) := sqCdbData(c)
  }
  when(io.flush) {
    sqCdbValid := VecInit(Seq.fill(numCdb)(false.B))
  }

  val sqDataCdbHits = Wire(Vec(sqDepth, Vec(numCdb, Bool())))
  for (slot <- 0 until sqDepth) {
    for (c <- 0 until numCdb) {
      sqDataCdbHits(slot)(c) := sqCdbValid(c) && sqValids(slot) &&
        sqEntries(slot).dataState === StoreDataState.waitingForCdb &&
        !sqDataTokenValid(slot) &&
        sqCdbPrd(c) === sqEntries(slot).storeDataPrs
    }
  }

  val recoverApplyPending = RegInit(false.B)
  when(io.flush) {
    recoverApplyPending := false.B
  }.otherwise {
    recoverApplyPending := io.recover.valid
  }
  val recoveryBusy = io.recover.valid || recoverApplyPending

  val lqAllocValid = VecInit((0 until w).map(lane =>
    io.lsqAllocValid(lane) && io.lsqAllocBits(lane).isLoad && !recoveryBusy))
  val sqAllocValid = VecInit((0 until w).map(lane =>
    io.lsqAllocValid(lane) && io.lsqAllocBits(lane).isStore && !recoveryBusy))
  val lqAllocCount = PopCount(lqAllocValid)
  val sqAllocCount = PopCount(sqAllocValid)

  val lqAllocRank = Wire(Vec(w, UInt(allocCountBits.W)))
  val sqAllocRank = Wire(Vec(w, UInt(allocCountBits.W)))
  for (lane <- 0 until w) {
    lqAllocRank(lane) :=
      (if (lane == 0) 0.U else PopCount(lqAllocValid.take(lane)))
    sqAllocRank(lane) :=
      (if (lane == 0) 0.U else PopCount(sqAllocValid.take(lane)))
    val nextLq = wrapLq(lqTail +& lqAllocRank(lane))
    val nextSq = wrapSq(sqTail +& sqAllocRank(lane))
    io.lsqAllocIdx(lane) := Mux(io.lsqAllocBits(lane).isLoad,
      nextLq, nextSq)
  }

  val sqSlotEnq = Wire(Vec(sqDepth, Bool()))
  val sqSlotEnqRank = Wire(Vec(sqDepth, UInt(allocCountBits.W)))
  for (slot <- 0 until sqDepth) {
    val laneHits = VecInit((0 until w).map(lane =>
      sqAllocValid(lane) && io.lsqAllocIdx(lane) === slot.U))
    sqSlotEnq(slot) := laneHits.asUInt.orR
    sqSlotEnqRank(slot) := Mux1H(laneHits, sqAllocRank)
  }

  val lqRetireCount = io.lqRetireCount
  val sqRetireCount = io.sqRetireCount
  val sqNextHead = wrapSq(sqHead +& sqRetireCount)
  sqYoungerSegment := Mux(io.flush, 0.U,
    VecInit((0 until sqDepth).map(_.U < sqNextHead)).asUInt)
  assert(lqRetireCount === PopCount(io.commit.map(c => c.valid && c.isLoad)))
  assert(sqRetireCount === PopCount(io.commit.map(c => c.valid && c.isStore)))
  val sqRetireMask = (0 until w).map(rank =>
    Mux(sqRetireCount > rank.U,
      UIntToOH(wrapSq(sqHead +& rank.U), sqDepth), 0.U(sqDepth.W))
  ).reduce(_ | _)
  val liveSqAfterRetire = sqValids.asUInt & ~sqRetireMask

  val lqFree = lqDepth.U - lqCount
  val sqFree = sqDepth.U - sqCount
  io.lqFreeBeforeRetire := lqFree
  io.sqFreeBeforeRetire := sqFree
  io.lsqCreditBlocked := recoveryBusy
  val lqAvailable = lqFree +& lqRetireCount
  val sqAvailable = sqFree +& sqRetireCount
  io.lqEnqCapacity := Mux(recoveryBusy, 0.U,
    lqAvailable(p.lqCountBits - 1, 0))
  io.sqEnqCapacity := Mux(recoveryBusy, 0.U,
    sqAvailable(p.sqCountBits - 1, 0))
  io.lqRelease.valid := false.B
  io.lqRelease.bits := 0.U

  for (query <- 0 until p.lsuIqDepth) {
    io.lsqQuerySafe(query) := true.B
  }

  val recoverKillLq = VecInit((0 until lqDepth).map(slot =>
    lqValids(slot) && RobOrder.isYounger(lqEntries(slot).robIdx,
      io.recover.bits, io.recoverHeadIdx)))
  val recoverKillSq = VecInit((0 until sqDepth).map(slot =>
    sqValids(slot) && RobOrder.isYounger(sqEntries(slot).robIdx,
      io.recover.bits, io.recoverHeadIdx)))
  val recoverKillLqMask = RegInit(0.U(lqDepth.W))
  val recoverKillSqMask = RegInit(0.U(sqDepth.W))
  when(io.flush) {
    recoverKillLqMask := 0.U
    recoverKillSqMask := 0.U
  }.elsewhen(io.recover.valid) {
    recoverKillLqMask := recoverKillLq.asUInt
    recoverKillSqMask := recoverKillSq.asUInt
  }
  val recoverKillLqCount = PopCount(recoverKillLqMask)
  val recoverKillSqCount = PopCount(recoverKillSqMask)

  val readBusy = Wire(Bool())
  val committedStores = Reg(Vec(storeBufferDepth, new CommittedStoreEntry))
  val committedStoreValid = RegInit(
    VecInit(Seq.fill(storeBufferDepth)(false.B)))
  val committedStoreOlderThan = RegInit(VecInit(Seq.fill(storeBufferDepth)(
    VecInit(Seq.fill(storeBufferDepth)(false.B)))))
  val committedStoreHead = RegInit(0.U(storeBufferPtrBits.W))
  val committedStoreHeadOH = RegInit(1.U(storeBufferDepth.W))
  val committedStoreTail = RegInit(0.U(storeBufferPtrBits.W))
  val committedStoreCount = RegInit(0.U(storeBufferCountBits.W))
  val bufferedStoreHandoffValid = RegInit(false.B)
  val bufferedStoreHandoffAddr = Reg(UInt(p.xLen.W))
  val bufferedStoreHandoffData = Reg(UInt(p.xLen.W))
  val bufferedStoreHandoffStrb = Reg(UInt(p.strbBits.W))

  val committedAvailable = storeBufferDepth.U - committedStoreCount
  io.storeCommitCapacity := Mux(committedAvailable >= w.U,
    w.U, committedAvailable)(allocCountBits - 1, 0)

  val commitStoreHits = Wire(Vec(w, Vec(sqDepth, Bool())))
  val commitStoreEntries = Wire(Vec(w, new SqEntry))
  val commitLoadHits = Wire(Vec(w, Vec(lqDepth, Bool())))
  val commitLoadEntries = Wire(Vec(w, new LqEntry))
  val bufferEnqValid = Wire(Vec(w, Bool()))
  for (lane <- 0 until w) {
    for (slot <- 0 until lqDepth) {
      commitLoadHits(lane)(slot) := lqValids(slot) &&
        lqEntries(slot).robIdx === io.commit(lane).robIdx
    }
    for (slot <- 0 until sqDepth) {
      commitStoreHits(lane)(slot) := sqValids(slot) &&
        sqEntries(slot).robIdx === io.commit(lane).robIdx
    }
    commitLoadEntries(lane) := lqEntries(PriorityEncoder(commitLoadHits(lane)))
    commitStoreEntries(lane) := sqEntries(PriorityEncoder(commitStoreHits(lane)))
    bufferEnqValid(lane) := io.commit(lane).valid &&
      io.commit(lane).isCacheableStore &&
      commitStoreHits(lane).asUInt.orR

    when(io.commit(lane).valid && io.commit(lane).isCacheableStore) {
      assert(io.commit(lane).isStore)
      assert(commitStoreHits(lane).asUInt.orR)
      when(commitStoreHits(lane).asUInt.orR) {
        assert(commitStoreEntries(lane).cacheable)
        assert(!commitStoreEntries(lane).peripheral)
        assert(commitStoreEntries(lane).memOp =/= MEM_SC.U)
      }
    }

    val commitIsSc = commitStoreHits(lane).asUInt.orR &&
      commitStoreEntries(lane).memOp === MEM_SC.U
    io.difftestStore(lane).valid := io.commit(lane).valid &&
      io.commit(lane).isStore && commitStoreHits(lane).asUInt.orR &&
      !commitIsSc
    io.difftestStore(lane).paddr := commitStoreEntries(lane).addr
    io.difftestStore(lane).vaddr := commitStoreEntries(lane).vaddr
    io.difftestStore(lane).data := commitStoreEntries(lane).storeData
    io.difftestLoad(lane).valid := io.commit(lane).valid &&
      io.commit(lane).isLoad && commitLoadHits(lane).asUInt.orR
    io.difftestLoad(lane).paddr := commitLoadEntries(lane).addr
    io.difftestLoad(lane).vaddr := commitLoadEntries(lane).vaddr
    io.difftestLoad(lane).data := 0.U
  }

  val committedReservationValid = Wire(Vec(w + 1, Bool()))
  val committedReservationAddr = Wire(Vec(w + 1,
    UInt((p.xLen - 2).W)))
  committedReservationValid(0) := llBit
  committedReservationAddr(0) := llAddress
  for (lane <- 0 until w) {
    val retiringLl = io.commit(lane).valid && io.commit(lane).isLoad &&
      commitLoadHits(lane).asUInt.orR &&
      commitLoadEntries(lane).memOp === MEM_LL.U
    val retiringSc = io.commit(lane).valid && io.commit(lane).isStore &&
      commitStoreHits(lane).asUInt.orR &&
      commitStoreEntries(lane).memOp === MEM_SC.U
    when(retiringLl) {
      assert(commitLoadEntries(lane).state === LoadQueueState.complete)
    }
    committedReservationValid(lane + 1) := Mux(retiringSc, false.B,
      Mux(retiringLl, true.B, committedReservationValid(lane)))
    committedReservationAddr(lane + 1) := Mux(retiringLl,
      commitLoadEntries(lane).addr(p.xLen - 1, 2),
      committedReservationAddr(lane))
  }

  val bufferEnqCount = PopCount(bufferEnqValid)
  val bufferEnqRank = Wire(Vec(w, UInt(allocCountBits.W)))
  val bufferEnqTarget = Wire(Vec(w, UInt(storeBufferPtrBits.W)))
  for (lane <- 0 until w) {
    bufferEnqRank(lane) :=
      (if (lane == 0) 0.U else PopCount(bufferEnqValid.take(lane)))
    bufferEnqTarget(lane) := wrapStoreBuffer(
      committedStoreTail +& bufferEnqRank(lane))
  }
  val bufferSlotEnq = Wire(Vec(storeBufferDepth, Bool()))
  val bufferSlotEnqRank = Wire(Vec(storeBufferDepth, UInt(allocCountBits.W)))
  for (slot <- 0 until storeBufferDepth) {
    val laneHits = VecInit((0 until w).map(lane =>
      bufferEnqValid(lane) && bufferEnqTarget(lane) === slot.U))
    bufferSlotEnq(slot) := laneHits.asUInt.orR
    bufferSlotEnqRank(slot) := Mux1H(laneHits, bufferEnqRank)
  }

  val orderedStoreHits = VecInit((0 until sqDepth).map(slot =>
    sqValids(slot) && sqEntries(slot).robIdx === io.storeCommitIdx))
  val orderedStoreGrantOH = PriorityEncoderOH(orderedStoreHits.asUInt)
  val selectedStorePrd = Mux1H(orderedStoreGrantOH, sqEntries.map(_.prd))
  val selectedStoreAddr = Mux1H(orderedStoreGrantOH, sqEntries.map(_.addr))
  val selectedStoreData = Mux1H(orderedStoreGrantOH, sqEntries.map(_.storeData))
  val selectedStoreStrb = Mux1H(orderedStoreGrantOH, sqEntries.map(_.storeStrb))
  val orderedStorePending = RegInit(false.B)
  val orderedStoreRobIdx = Reg(UInt(p.robIdxBits.W))
  val orderedStorePrd = Reg(UInt(p.physRegBits.W))
  val orderedStoreAddr = Reg(UInt(p.xLen.W))
  val orderedStoreData = Reg(UInt(p.xLen.W))
  val orderedStoreStrb = Reg(UInt(p.strbBits.W))
  val orderedStoreIsSc = RegInit(false.B)
  val orderedStoreScSuccess = RegInit(false.B)
  val orderedStoreScNoBus = RegInit(false.B)
  // cacheable SC 独占的一级 enqueue staging。reservation 判定和 SQ
  // metadata 只到达这些寄存器；下一拍 committed-store buffer 仅接收
  // 已寄存的 valid/addr/data/strb，不再与普通 store 的提交路径串联。
  val scBufferEnqPending = RegInit(false.B)
  val scWritebackPending = RegInit(false.B)
  val lastScRobIdx = Reg(UInt(p.robIdxBits.W))
  val lastScSuccess = RegInit(false.B)
  val orderedStoreCacheClean = RegInit(false.B)
  val orderedStoreDone = RegInit(false.B)
  // Compare each candidate with the registered reservation in parallel with
  // the ROB match. The selected address never drives the reservation compare.
  val scSlots = VecInit(sqEntries.map(_.memOp === MEM_SC.U)).asUInt
  val reservationMatches = VecInit(sqEntries.map(entry =>
    llBit && llAddress === entry.addr(p.xLen - 1, 2))).asUInt
  val cacheableSlots = VecInit(sqEntries.map(entry =>
    entry.cacheable && !entry.peripheral)).asUInt
  val orderedEntryIsSc = (orderedStoreGrantOH & scSlots).orR
  val scReservationMatches = (orderedStoreGrantOH & reservationMatches).orR
  val scUsesCache = (orderedStoreGrantOH & cacheableSlots).orR
  val scBufferReady = committedStoreCount === 0.U &&
    bufferEnqCount === 0.U && committedAvailable =/= 0.U
  val orderedStoreEligible = VecInit(sqEntries.zipWithIndex.map { case (entry, slot) =>
    val useCache = cacheableSlots(slot)
    val successfulCachedSc = scSlots(slot) && reservationMatches(slot) && useCache
    (!useCache || scSlots(slot)) && entry.addrValid &&
      entry.dataState === StoreDataState.ready && (!successfulCachedSc || scBufferReady)
  }).asUInt
  val captureOrderedStore = io.storeCommitValid &&
    (orderedStoreGrantOH & orderedStoreEligible).orR &&
    !orderedStorePending && !orderedStoreDone
  if (p.enableDifftest) {
    val oldEntry = sqEntries(PriorityEncoder(orderedStoreHits))
    val oldSc = oldEntry.memOp === MEM_SC.U
    val oldMatch = llBit && llAddress === oldEntry.addr(p.xLen - 1, 2)
    val oldUseCache = oldEntry.cacheable && !oldEntry.peripheral
    val oldCapture = io.storeCommitValid && orderedStoreHits.asUInt.orR &&
      (!oldUseCache || oldSc) && oldEntry.addrValid &&
      oldEntry.dataState === StoreDataState.ready &&
      (!oldSc || !oldMatch || !oldUseCache || scBufferReady) &&
      !orderedStorePending && !orderedStoreDone
    assert(captureOrderedStore === oldCapture)
    when(captureOrderedStore) {
      assert(selectedStorePrd === oldEntry.prd)
      assert(selectedStoreAddr === oldEntry.addr)
      assert(selectedStoreData === oldEntry.storeData)
      assert(selectedStoreStrb === oldEntry.storeStrb)
      assert(orderedEntryIsSc === oldSc)
      assert(scReservationMatches === oldMatch)
      assert(scUsesCache === oldUseCache)
    }
  }
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(
      captureOrderedStore.toTarget, "max_fanout = 32")
  })

  when(captureOrderedStore) {
    orderedStorePending := true.B
    orderedStoreRobIdx := io.storeCommitIdx
    orderedStorePrd := selectedStorePrd
    orderedStoreAddr := selectedStoreAddr
    orderedStoreData := selectedStoreData
    orderedStoreStrb := selectedStoreStrb
    orderedStoreIsSc := orderedEntryIsSc
    orderedStoreScSuccess := orderedEntryIsSc && scReservationMatches
    orderedStoreScNoBus := orderedEntryIsSc &&
      (!scReservationMatches || scUsesCache)
    orderedStoreCacheClean := false.B
    when(orderedEntryIsSc) {
      lastScRobIdx := io.storeCommitIdx
      lastScSuccess := scReservationMatches
      when(!scReservationMatches) {
        scWritebackPending := true.B
      }
    }
  }

  val scBufferEnq = scBufferEnqPending && !io.flush &&
    committedAvailable =/= 0.U && bufferEnqCount === 0.U
  val scBufferTarget = committedStoreTail

  when(io.flush) {
    scBufferEnqPending := false.B
  }.elsewhen(scBufferEnq) {
    scBufferEnqPending := false.B
  }.elsewhen(captureOrderedStore && orderedEntryIsSc &&
      scReservationMatches && scUsesCache) {
    scBufferEnqPending := true.B
  }
  when(scBufferEnqPending) {
    assert(orderedStorePending && orderedStoreIsSc &&
      orderedStoreScSuccess && orderedStoreScNoBus)
  }

  for (lane <- 0 until w) {
    val retiringSc = io.commit(lane).valid && io.commit(lane).isStore &&
      commitStoreHits(lane).asUInt.orR &&
      commitStoreEntries(lane).memOp === MEM_SC.U
    when(retiringSc) {
      io.difftestStore(lane).valid := lastScSuccess &&
        lastScRobIdx === io.commit(lane).robIdx
    }
  }

  llBit := committedReservationValid(w)
  llAddress := committedReservationAddr(w)

  val drainBufferedStore = committedStoreCount =/= 0.U
  dcache.io.storeValid := drainBufferedStore
  val committedStoreHeadEntry = Reg(new CommittedStoreEntry)
  when(drainBufferedStore) {
    assert(committedStoreHeadEntry.asUInt ===
      Mux1H(committedStoreHeadOH, committedStores).asUInt)
  }
  dcache.io.storeAddr := committedStoreHeadEntry.addr
  dcache.io.storeData := committedStoreHeadEntry.data
  dcache.io.storeStrb := committedStoreHeadEntry.strb

  val dcacheWriteOutstanding = RegInit(0.U(p.dcacheWritebackQueueCountBits.W))
  val orderedWriteOutstanding = RegInit(false.B)
  val requestOrderedStore = !drainBufferedStore && orderedStorePending &&
    !orderedStoreScNoBus &&
    orderedStoreCacheClean && !readBusy && !dcache.io.busy &&
    !dcache.io.write.req_valid && dcacheWriteOutstanding === 0.U &&
    !orderedWriteOutstanding
  val orderedStoreSize = Mux(orderedStoreStrb.andR, 2.U,
    Mux(orderedStoreStrb === "b0011".U ||
      orderedStoreStrb === "b1100".U, 1.U, 0.U))

  val selectDcacheWrite = dcache.io.write.req_valid &&
    !orderedWriteOutstanding
  io.storeBus.req_valid := selectDcacheWrite || requestOrderedStore
  io.storeBus.req_addr := Mux(selectDcacheWrite,
    dcache.io.write.req_addr, orderedStoreAddr)
  io.storeBus.req_size := Mux(selectDcacheWrite,
    dcache.io.write.req_size, orderedStoreSize)
  io.storeBus.req_len := Mux(selectDcacheWrite,
    dcache.io.write.req_len, 0.U)
  io.storeBus.req_burst := Mux(selectDcacheWrite,
    dcache.io.write.req_burst, Axi4Const.BurstIncr)
  dcache.io.write.req_ready := selectDcacheWrite && io.storeBus.req_ready
  val dcacheWriteRequest = dcache.io.write.req_valid &&
    dcache.io.write.req_ready
  val orderedWriteRequest = requestOrderedStore && !selectDcacheWrite &&
    io.storeBus.req_ready
  when(orderedWriteRequest) {
    orderedWriteOutstanding := true.B
  }

  val dcacheWriteDataOwner = dcacheWriteOutstanding =/= 0.U
  io.storeBus.data_valid := Mux(dcacheWriteDataOwner,
    dcache.io.write.data_valid, orderedWriteOutstanding)
  io.storeBus.data_bits := Mux(dcacheWriteDataOwner,
    dcache.io.write.data_bits, orderedStoreData)
  io.storeBus.data_strb := Mux(dcacheWriteDataOwner,
    dcache.io.write.data_strb, orderedStoreStrb)
  io.storeBus.data_last := Mux(dcacheWriteDataOwner,
    dcache.io.write.data_last, true.B)
  dcache.io.write.data_ready := io.storeBus.data_ready &&
    dcacheWriteDataOwner

  io.storeBus.resp_ready := Mux(dcacheWriteDataOwner,
    dcache.io.write.resp_ready, orderedWriteOutstanding)
  dcache.io.write.resp_valid := io.storeBus.resp_valid &&
    dcacheWriteDataOwner
  val dcacheWriteResponse = dcache.io.write.resp_valid &&
    dcache.io.write.resp_ready
  when(dcacheWriteRequest =/= dcacheWriteResponse) {
    dcacheWriteOutstanding := Mux(dcacheWriteRequest,
      dcacheWriteOutstanding + 1.U, dcacheWriteOutstanding - 1.U)
  }
  val bufferDeq = dcache.io.storeReady && drainBufferedStore
  val scBufferedStoreAccepted = bufferDeq && orderedStorePending &&
    orderedStoreIsSc && orderedStoreScSuccess && orderedStoreScNoBus
  when(scBufferedStoreAccepted) {
    scWritebackPending := true.B
  }
  val orderedStoreGrant = io.storeBus.resp_valid && io.storeBus.resp_ready &&
    orderedWriteOutstanding
  val orderedStoreCommitMatch = io.storeCommitValid &&
    io.storeCommitIdx === orderedStoreRobIdx
  io.storeCommitDone := orderedStoreDone && orderedStoreCommitMatch

  when(orderedStoreDone && !orderedStoreCommitMatch) {
    orderedStoreDone := false.B
  }
  when(orderedStoreGrant) {
    orderedWriteOutstanding := false.B
    orderedStoreCacheClean := false.B
    when(orderedStoreIsSc) {
      scWritebackPending := true.B
    }.otherwise {
      orderedStorePending := false.B
      orderedStoreDone := true.B
    }
  }
  assert(dcacheWriteOutstanding <= p.dcacheWritebackQueueDepth.U)

  io.orderedStoreCapture := captureOrderedStore
  io.orderedStorePending := orderedStorePending
  io.orderedStoreDoneHeld := orderedStoreDone
  io.orderedStoreRobIdx := orderedStoreRobIdx
  io.orderedStoreAwFire := orderedWriteRequest
  io.orderedStoreWFire := io.storeBus.data_valid && io.storeBus.data_ready &&
    orderedWriteOutstanding && !dcacheWriteDataOwner
  io.orderedStoreBFire := orderedStoreGrant
  io.orderedStoreAddr := orderedStoreAddr
  io.orderedStoreData := orderedStoreData
  io.orderedStoreStrb := orderedStoreStrb

  for (lane <- 0 until w) {
    when(bufferEnqValid(lane)) {
      val target = bufferEnqTarget(lane)
      committedStores(target).addr := commitStoreEntries(lane).addr
      committedStores(target).data := commitStoreEntries(lane).storeData
      committedStores(target).strb := commitStoreEntries(lane).storeStrb
      committedStoreValid(target) := true.B
    }
  }
  when(scBufferEnq) {
    committedStores(scBufferTarget).addr := orderedStoreAddr
    committedStores(scBufferTarget).data := orderedStoreData
    committedStores(scBufferTarget).strb := orderedStoreStrb
    committedStoreValid(scBufferTarget) := true.B
  }
  when(bufferDeq) {
    committedStoreValid(committedStoreHead) := false.B
    committedStoreHead := wrapStoreBuffer(committedStoreHead +& 1.U)
    committedStoreHeadOH := Cat(committedStoreHeadOH(storeBufferDepth - 2, 0),
      committedStoreHeadOH(storeBufferDepth - 1))
  }
  val totalBufferEnqCount = bufferEnqCount +& scBufferEnq.asUInt
  // Mirror the next live head at the existing enqueue/dequeue edge. The
  // DCache probe no longer launches from a store-ring pointer and wide mux.
  val nextStoreHeadOH = Cat(committedStoreHeadOH(storeBufferDepth - 2, 0),
    committedStoreHeadOH(storeBufferDepth - 1))
  val firstEnqLane = PriorityEncoderOH(bufferEnqValid.asUInt)
  val firstEnqStore = Wire(new CommittedStoreEntry)
  firstEnqStore.addr := Mux1H(firstEnqLane, commitStoreEntries.map(_.addr))
  firstEnqStore.data := Mux1H(firstEnqLane, commitStoreEntries.map(_.storeData))
  firstEnqStore.strb := Mux1H(firstEnqLane, commitStoreEntries.map(_.storeStrb))
  val scEnqStore = Wire(new CommittedStoreEntry)
  scEnqStore.addr := orderedStoreAddr
  scEnqStore.data := orderedStoreData
  scEnqStore.strb := orderedStoreStrb
  when(bufferDeq && committedStoreCount > 1.U) {
    committedStoreHeadEntry := Mux1H(nextStoreHeadOH, committedStores)
  }.elsewhen((!drainBufferedStore || bufferDeq) && totalBufferEnqCount =/= 0.U) {
    committedStoreHeadEntry := Mux(bufferEnqValid.asUInt.orR, firstEnqStore, scEnqStore)
  }
  when(totalBufferEnqCount =/= 0.U) {
    committedStoreTail := wrapStoreBuffer(
      committedStoreTail +& totalBufferEnqCount)
  }
  when(totalBufferEnqCount =/= 0.U || bufferDeq) {
    committedStoreCount := committedStoreCount +& totalBufferEnqCount -
      bufferDeq.asUInt
  }

  val bufferDeqMask = Mux(bufferDeq,
    UIntToOH(committedStoreHead, storeBufferDepth),
    0.U(storeBufferDepth.W))
  val liveBufferAfterDeq = committedStoreValid.asUInt & ~bufferDeqMask
  for (older <- 0 until storeBufferDepth) {
    for (younger <- 0 until storeBufferDepth) {
      val olderEnq = bufferSlotEnq(older) ||
        (scBufferEnq && scBufferTarget === older.U)
      val youngerEnq = bufferSlotEnq(younger) ||
        (scBufferEnq && scBufferTarget === younger.U)
      val olderRank = Mux(scBufferEnq && scBufferTarget === older.U,
        0.U, bufferSlotEnqRank(older))
      val youngerRank = Mux(scBufferEnq && scBufferTarget === younger.U,
        0.U, bufferSlotEnqRank(younger))
      if (older == younger) {
        committedStoreOlderThan(older)(younger) := false.B
      } else {
        when(olderEnq || youngerEnq) {
          committedStoreOlderThan(older)(younger) := Mux(
            olderEnq && youngerEnq,
            olderRank < youngerRank,
            !olderEnq && youngerEnq &&
              liveBufferAfterDeq(older))
        }
      }
    }
  }

  val storeWindowBaseReg = RegInit(0.U(p.robIdxBits.W))
  val storeWindowValidReg = RegInit(
    VecInit(Seq.fill(storeCommitWindowDepth)(false.B)))
  val storeWindowCacheableReg = Reg(Vec(storeCommitWindowDepth, Bool()))
  val storeWindowDataReadyReg = Reg(Vec(storeCommitWindowDepth, Bool()))
  io.storeWindowBase := storeWindowBaseReg
  io.storeWindowValid := storeWindowValidReg
  io.storeWindowCacheable := storeWindowCacheableReg
  io.storeWindowDataReady := storeWindowDataReadyReg
  storeWindowBaseReg := io.robHeadIdx
  for (offset <- 0 until storeCommitWindowDepth) {
    val targetRobIdx = (io.robHeadIdx + offset.U)(p.robIdxBits - 1, 0)
    val hits = VecInit((0 until sqDepth).map(slot =>
      sqValids(slot) && sqEntries(slot).robIdx === targetRobIdx))
    val entry = sqEntries(PriorityEncoder(hits))
    when(io.flush || recoveryBusy) {
      storeWindowValidReg(offset) := false.B
    }.otherwise {
      storeWindowValidReg(offset) := hits.asUInt.orR && entry.addrValid
      storeWindowCacheableReg(offset) := entry.cacheable && !entry.peripheral &&
        entry.memOp =/= MEM_SC.U
      storeWindowDataReadyReg(offset) := hits.asUInt.orR && entry.addrValid &&
        entry.dataState === StoreDataState.ready
    }
  }

  // The timing-established two-slot path is unchanged at the default depth.
  val deeperAgu = if (p.lsuAguQueueDepth > 2)
    Some(Module(new RecoverableAguQueue(p.lsuAguQueueDepth))) else None
  deeperAgu.foreach { queue =>
    queue.io.flush := io.flush
    queue.io.recover := io.recover
    queue.io.recoverHead := io.recoverHeadIdx
  }
  val aguEntries = Reg(Vec(2, new ExecIn))
  val aguHead = RegInit(false.B)
  val aguValid = deeperAgu.map(_.io.deq.valid).getOrElse(RegInit(false.B))
  val aguSpillValid = deeperAgu.map(q => q.io.occupancy > 1.U ||
    (q.io.occupancy =/= 0.U && !q.io.deq.valid)).getOrElse(RegInit(false.B))
  val aguBits = deeperAgu.map(_.io.deq.bits)
    .getOrElse(Mux(aguHead, aguEntries(1), aguEntries(0)))
  val aguSpillBits = Mux(aguHead, aguEntries(0), aguEntries(1))
  val scanRequest = Reg(new SequentialLoadRequest)
  val scanValid = RegInit(false.B)
  val scanCompareWordAddr = Reg(UInt((p.xLen - 2).W))
  val scanRequiredLoadMask = Reg(UInt(p.strbBits.W))
  val scanOffsetOH = Reg(UInt(p.strbBits.W))
  val scanSignedByte = Reg(Bool())
  val scanSignedHalf = Reg(Bool())
  val scanByteAccess = Reg(Bool())
  val scanHalfAccess = Reg(Bool())
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(
      scanCompareWordAddr.toTarget,
      "EQUIVALENT_REGISTER_REMOVAL = \"NO\"")
  })
  class ScanSqSnapshot extends Bundle {
    val known = Vec(sqDepth, Bool())
    val matched = Vec(sqDepth, Bool())
    val ordered = Vec(sqDepth, Bool())
  }
  val scanSqOwner = RegInit(false.B)
  val scanSqBanks = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new ScanSqSnapshot))))
  val scanSq = scanSqBanks(scanSqOwner)
  val scanSqAddressKnown = scanSq.known
  val scanSqAddressMatch = scanSq.matched
  val scanSqOrdered = scanSq.ordered
  val scanLookupHeld = RegInit(false.B)
  val scanLookupData = Reg(UInt(p.xLen.W))
  val scanMissObserved = RegInit(false.B)
  val readRequest = Reg(new SequentialLoadRequest)
  val dcacheMissRequests = Reg(Vec(dcacheMshrEntries,
    new SequentialLoadRequest))
  val dcacheMissValid = RegInit(VecInit(
    Seq.fill(dcacheMshrEntries)(false.B)))
  val dcacheMissKilled = RegInit(VecInit(
    Seq.fill(dcacheMshrEntries)(false.B)))
  // 显式 MSHR 空闲位图；上下文按返回 ID 释放，不再要求 head 顺序。
  val dcacheMissFree = ~dcacheMissValid.asUInt
  val dcacheMissAllocateId = PriorityEncoder(dcacheMissFree)
  val dcacheMissCount = RegInit(0.U(p.dcacheMshrCountBits.W))
  val readState = RegInit(SequentialLoadState.idle)

  val resultPayload = Reg(new CdbEntry)
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(
      resultPayload.data.toTarget, "max_fanout = 64")
  })
  val resultBits = Wire(new CdbEntry)
  resultBits := resultPayload
  val iqResultPrd = Reg(Vec(p.numAlu + 1, UInt(p.physRegBits.W)))
  val resultValid = RegInit(false.B)
  io.out.valid := resultValid
  io.out.bits := resultBits
  for (target <- 0 until p.numAlu + 1) {
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(
        iqResultPrd(target).toTarget,
        "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 8")
    })
    io.iqCdbPrd(target) := iqResultPrd(target)
    assert(iqResultPrd(target) === resultBits.prd)
  }

  val aguUop = aguBits.uop
  // LL/SC are deliberately serial: a younger CSR must not sample LLBit
  // before the registered retirement packet has updated the reservation.
  val atomicInFlight = RegInit(false.B)
  val atomicRobIdx = Reg(UInt(p.robIdxBits.W))
  val atomicRetiring = VecInit(io.commit.map(c =>
    c.valid && c.robIdx === atomicRobIdx)).asUInt.orR
  val atomicKilled = io.recover.valid && atomicInFlight &&
    RobOrder.isYounger(atomicRobIdx, io.recover.bits, io.recoverHeadIdx)
  val dataTranslationBits = Reg(new ExecIn)
  val dataTranslationValid = RegInit(false.B)
  val dataTranslationKilled = RegInit(false.B)
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(
      dataTranslationValid.toTarget, "max_fanout = 8")
  })
  val dataTranslationUop = dataTranslationBits.uop
  val recoverKillAgu = RegInit(false.B)
  val recoverKillAguSpill = RegInit(false.B)
  val recoverKillScan = RegInit(false.B)
  val recoverKillRead = RegInit(false.B)
  val recoverKillDcacheMiss = RegInit(VecInit(
    Seq.fill(dcacheMshrEntries)(false.B)))
  // Compare each live owner before the DCache chooses a completion ID.
  val recoverKillDcacheMissNow = VecInit((0 until dcacheMshrEntries).map(slot =>
    dcacheMissValid(slot) && RobOrder.isYounger(dcacheMissRequests(slot).robIdx,
      io.recover.bits, io.recoverHeadIdx)))
  val recoverKillSystem = RegInit(false.B)
  when(io.flush) {
    recoverKillAgu := false.B
    recoverKillAguSpill := false.B
    recoverKillScan := false.B
    recoverKillRead := false.B
    recoverKillDcacheMiss := VecInit(
      Seq.fill(dcacheMshrEntries)(false.B))
    recoverKillSystem := false.B
    dataTranslationKilled := false.B
  }.elsewhen(io.recover.valid) {
    recoverKillAgu := aguValid && RobOrder.isYounger(aguUop.robIdx,
      io.recover.bits, io.recoverHeadIdx)
    recoverKillAguSpill := aguSpillValid && RobOrder.isYounger(
      aguSpillBits.uop.robIdx, io.recover.bits, io.recoverHeadIdx)
    recoverKillScan := scanValid && RobOrder.isYounger(scanRequest.robIdx,
      io.recover.bits, io.recoverHeadIdx)
    recoverKillRead := readState =/= SequentialLoadState.idle &&
      RobOrder.isYounger(readRequest.robIdx,
        io.recover.bits, io.recoverHeadIdx)
    recoverKillDcacheMiss := recoverKillDcacheMissNow
    recoverKillSystem := sysPendingValid && RobOrder.isYounger(
      sysPending.robIdx, io.recover.bits, io.recoverHeadIdx)
    when(dataTranslationValid) {
      dataTranslationKilled := RobOrder.isYounger(
        dataTranslationUop.robIdx, io.recover.bits, io.recoverHeadIdx)
    }
  }
  // Calculate the 4 KiB and 2 MiB VPN slices in parallel with the offset.
  def aguAddress(base: UInt, offset: UInt): (UInt, DataPageCandidates) = {
    val low = base(11, 0) +& offset(11, 0)
    val middleBase = base(21, 12)
    val middleOffset = offset(21, 12)
    val middle = middleBase +& middleOffset
    val middleWithCarry = (Cat(middleBase, 1.U(1.W)) +
      Cat(middleOffset, 1.U(1.W)))(10, 1)
    val upperBase = base(31, 22)
    val upperOffset = offset(31, 22)
    val upper = upperBase + upperOffset
    val upperWithCarry = (Cat(upperBase, 1.U(1.W)) +
      Cat(upperOffset, 1.U(1.W)))(10, 1)
    val middleCarry = middle(10) || (low(12) && (middleBase ^ middleOffset).andR)
    val pages = Wire(new DataPageCandidates)
    pages.prefix := upper
    pages.prefixWithCarry := upperWithCarry
    pages.prefixCarry := middleCarry
    pages.suffix := middle(9, 0)
    pages.suffixWithCarry := middleWithCarry
    pages.suffixCarry := low(12)
    (Cat(Mux(middleCarry, upperWithCarry, upper),
      Mux(low(12), middleWithCarry, middle(9, 0)), low(11, 0)), pages)
  }
  val (aguVirtualAddr, aguDataPages) = if (p.lsuAguQueueDepth == 2) {
    val addresses = aguEntries.map(entry => aguAddress(entry.src1, entry.uop.imm))
    (Mux(aguHead, addresses(1)._1, addresses(0)._1),
      Mux(aguHead, addresses(1)._2, addresses(0)._2))
  } else {
    aguAddress(aguBits.src1, aguUop.imm)
  }
  mmu.io.dataPages.foreach(_ := aguDataPages)
  when(aguValid) {
    assert(aguVirtualAddr === (aguBits.src1 + aguUop.imm))
  }
  val aguSystemIsHitCacop = aguUop.isSystem &&
    aguUop.sysOp === SYS_CACOP.U && aguUop.csrNum(4, 3) === 2.U
  val aguNeedsDataTranslation = aguUop.isLoad || aguUop.isStore ||
    aguSystemIsHitCacop
  val translatedVirtualAddr = mmu.io.lookup(1).resp.bits.va
  val translatedPhysAddr = mmu.io.lookup(1).resp.bits.pa
  val translatedMat = mmu.io.lookup(1).resp.bits.mat
  val translatedCacheable = MemoryAddressing.isCacheable(
    translatedPhysAddr, translatedMat)
  val translatedPeripheral = MemoryAddressing.isPeripheral(translatedPhysAddr)
  val translatedStoreStrb = storeMask(
    dataTranslationUop.memOp, translatedPhysAddr(1, 0))
  val translatedLoadMask = loadMask(
    dataTranslationUop.memOp, translatedPhysAddr(1, 0))

  for (slot <- 0 until sqDepth) {
    val cdbHits = sqDataCdbHits(slot)
    when(cdbHits.asUInt.orR) {
      sqDataTokenValid(slot) := true.B
      sqDataTokenLane(slot) := PriorityEncoder(cdbHits)
    }

    when(sqValids(slot) && sqDataTokenValid(slot)) {
      val raw = sqCdbReplayData(sqDataTokenLane(slot))
      sqDataTokenValid(slot) := false.B
      when(sqEntries(slot).addrValid) {
        sqEntries(slot).storeData := packStoreData(raw,
          sqEntries(slot).memOp, sqEntries(slot).addr(1, 0))
        sqEntries(slot).dataState := StoreDataState.ready
      }.otherwise {
        sqEntries(slot).storeData := raw
        sqEntries(slot).dataState := StoreDataState.rawCaptured
      }
    }

    when(sqValids(slot) && sqEntries(slot).addrValid &&
        sqEntries(slot).dataState === StoreDataState.rawCaptured) {
      sqEntries(slot).storeData := packStoreData(sqEntries(slot).storeData,
        sqEntries(slot).memOp, sqEntries(slot).addr(1, 0))
      sqEntries(slot).dataState := StoreDataState.ready
    }

    when(cdbHits.asUInt.orR) {
      assert(PopCount(cdbHits) === 1.U)
    }
  }

  // A returned miss remains an LSU resource owner through the added response
  // stage. DCache may have released its MSHR already; system/ordered traffic
  // must also wait for the LSU context to be consumed.
  readBusy := readState =/= SequentialLoadState.idle || dcache.io.busy ||
    (registerMissResponses.B && dcacheMissCount =/= 0.U)
  val scanStageReady = WireDefault(false.B)
  val scanPark = WireDefault(false.B)
  val systemInputReady = !dataTranslationValid && !scanValid && !readBusy &&
    !sysPendingValid && !readyLoadPresent
  val externalLoadResponse = Wire(Bool())
  val faultResultPortBusy = Wire(Bool())
  val dataIsHalf = dataTranslationUop.memOp === MEM_LH.U ||
    dataTranslationUop.memOp === MEM_LHU.U ||
    dataTranslationUop.memOp === MEM_SH.U
  val dataIsWord = dataTranslationUop.memOp === MEM_LW.U ||
    dataTranslationUop.memOp === MEM_SW.U ||
    dataTranslationUop.memOp === MEM_LL.U ||
    dataTranslationUop.memOp === MEM_SC.U
  val dataMisaligned = (dataTranslationUop.isLoad ||
    dataTranslationUop.isStore) &&
    ((dataIsWord && translatedVirtualAddr(1, 0).orR) ||
      (dataIsHalf && translatedVirtualAddr(0)))
  val dataException = WireDefault(mmu.io.lookup(1).resp.bits.exception)
  when(dataMisaligned) {
    dataException.valid := true.B
    dataException.ecode := ExceptionCode.ALE.U
    dataException.esubcode := 0.U
    dataException.badv := translatedVirtualAddr
    dataException.badvValid := true.B
  }
  val dataTranslationFault = dataException.valid
  val dataFaultPending = dataTranslationValid &&
    mmu.io.lookup(1).resp.valid && dataTranslationFault &&
    !dataTranslationKilled && !recoveryBusy && !io.flush
  val dataFaultCanComplete = dataFaultPending && !faultResultPortBusy
  val translatedStoreCanAdvance = dataTranslationValid &&
    dataTranslationUop.isStore && !dataTranslationFault
  val translatedLoadCanAdvance = dataTranslationValid &&
    dataTranslationUop.isLoad && !dataTranslationFault
  val translatedCacopCanAdvance = dataTranslationValid &&
    dataTranslationUop.isSystem &&
    dataTranslationUop.sysOp === SYS_CACOP.U &&
    dataTranslationUop.csrNum(4, 3) === 2.U && !dataTranslationFault
  val translatedMemoryCanAdvance = translatedStoreCanAdvance ||
    translatedLoadCanAdvance || translatedCacopCanAdvance
  mmu.io.lookup(1).resp.ready := dataTranslationValid &&
    (dataTranslationKilled || dataFaultCanComplete ||
      (translatedMemoryCanAdvance && !recoveryBusy && !io.flush))
  val dataTranslationCompletes = mmu.io.lookup(1).resp.fire
  // Pending includes response valid; allowing the fault to complete also
  // supplies its ready. Keep this event out of the generic ready/fire loop.
  val dataFaultCompletes = dataFaultCanComplete
  assert(dataFaultCompletes === (dataTranslationCompletes && dataTranslationFault &&
    !dataTranslationKilled))

  val dataTranslationStageReady = !dataTranslationValid ||
    dataTranslationCompletes
  val aguIsAtomic = (aguUop.isLoad && aguUop.memOp === MEM_LL.U) ||
    (aguUop.isStore && aguUop.memOp === MEM_SC.U)
  // The ROB head advances one cycle before io.commit is consumed here.
  // Wait through that packet as well as older memory traffic; allocated
  // younger LQ/SQ entries must not prevent the atomic from making progress.
  val atomicCanStart = aguUop.robIdx === io.robHeadIdx &&
    !VecInit(io.commit.map(_.valid)).asUInt.orR &&
    !dataTranslationValid && !scanValid && !readBusy && !readyLoadPresent &&
    dcacheMissCount === 0.U && committedStoreCount === 0.U &&
    bufferEnqCount === 0.U && !bufferedStoreHandoffValid &&
    !orderedStorePending && !orderedStoreDone &&
    dcacheWriteOutstanding === 0.U && !orderedWriteOutstanding &&
    !sysPendingValid
  mmu.io.lookup(1).req.valid := aguValid &&
    aguNeedsDataTranslation && dataTranslationStageReady &&
    (!aguSystemIsHitCacop || systemInputReady) &&
    !atomicInFlight && (!aguIsAtomic || atomicCanStart) &&
    !recoveryBusy && !io.flush
  mmu.io.lookup(1).req.bits.va := aguVirtualAddr
  mmu.io.lookup(1).req.bits.access := Mux(aguUop.isStore,
    TranslationAccess.Store.U, TranslationAccess.Load.U)
  val aguMemoryRequests = mmu.io.lookup(1).req.fire
  val aguOtherCanAdvance = aguValid && !aguUop.isStore && !aguUop.isLoad &&
    !aguSystemIsHitCacop && systemInputReady && !atomicInFlight
  val aguExecutionAllowed = !io.flush && !recoveryBusy
  val aguAdvances = aguMemoryRequests ||
    (aguOtherCanAdvance && aguExecutionAllowed)

  when(io.flush || atomicKilled) {
    atomicInFlight := false.B
  }.elsewhen(atomicInFlight && atomicRetiring) {
    // LLBit/LLAddress are updated on this same edge by io.commit below.
    atomicInFlight := false.B
  }.elsewhen(aguMemoryRequests && aguIsAtomic) {
    atomicInFlight := true.B
    atomicRobIdx := aguUop.robIdx
  }
  when(atomicInFlight) {
    assert(!aguMemoryRequests && !aguOtherCanAdvance)
  }

  when(io.flush) {
    dataTranslationValid := false.B
    dataTranslationKilled := false.B
  }.otherwise {
    switch(Cat(aguMemoryRequests, dataTranslationCompletes)) {
      is("b10".U) { dataTranslationValid := true.B }
      is("b01".U) { dataTranslationValid := false.B }
      is("b11".U) { dataTranslationValid := true.B }
    }
    when(aguMemoryRequests) {
      dataTranslationBits := aguBits
      dataTranslationKilled := false.B
    }
  }

  if (p.lsuAguQueueDepth == 2) {
    io.in.ready := !io.flush && !recoveryBusy && !aguSpillValid && !atomicInFlight
  } else {
    io.in.ready := !io.flush && !recoveryBusy && deeperAgu.get.io.enq.ready && !atomicInFlight
  }
  val aguAcceptsInput = io.in.valid && io.in.ready
  val recoveryKeepsAgu = aguValid && !recoverKillAgu
  val recoveryKeepsAguSpill = aguSpillValid && !recoverKillAguSpill

  if (p.lsuAguQueueDepth == 2) {
    val aguWriteSecond = aguHead ^ aguValid
    // Empty payload slots can sample every edge. Acceptance controls their
    // ownership, so late IQ issue valid need not drive all payload enables.
    when(!aguSpillValid && !aguWriteSecond) {
      aguEntries(0) := io.in.bits
    }
    when(!aguSpillValid && aguWriteSecond) {
      aguEntries(1) := io.in.bits
    }
    val ownedAguWitness = Reg(Vec(2, new ExecIn))
    when(aguAcceptsInput) { ownedAguWitness(aguWriteSecond) := io.in.bits }
    when(aguValid) { assert(aguBits.asUInt === ownedAguWitness(aguHead).asUInt) }
    when(aguSpillValid) {
      assert(aguSpillBits.asUInt === ownedAguWitness(!aguHead).asUInt)
    }

    when(io.flush) {
      aguValid := false.B
      aguSpillValid := false.B
    }.elsewhen(recoverApplyPending) {
      when(recoveryKeepsAgu) {
        aguValid := true.B
        aguSpillValid := recoveryKeepsAguSpill
      }.elsewhen(recoveryKeepsAguSpill) {
        aguHead := !aguHead
        aguValid := true.B
        aguSpillValid := false.B
      }.otherwise {
        aguValid := false.B
        aguSpillValid := false.B
      }
    }.elsewhen(!recoveryBusy) {
      when(aguAdvances) {
        when(aguSpillValid) {
          aguHead := !aguHead
          aguValid := true.B
          aguSpillValid := false.B
        }.elsewhen(aguAcceptsInput) {
          aguHead := !aguHead
          aguValid := true.B
        }.otherwise {
          aguValid := false.B
        }
      }.elsewhen(!aguValid) {
        aguValid := aguAcceptsInput
      }.elsewhen(aguAcceptsInput) {
        aguSpillValid := true.B
      }
    }

  } else {
    deeperAgu.get.io.enq.valid := aguAcceptsInput
    deeperAgu.get.io.enq.bits := io.in.bits
    deeperAgu.get.io.deq.ready := aguAdvances
  }

  val aguLoadExecutes = translatedLoadCanAdvance &&
    mmu.io.lookup(1).resp.valid && !dataTranslationKilled &&
    !recoveryBusy && !io.flush
  assert(aguLoadExecutes === (dataTranslationCompletes &&
    translatedLoadCanAdvance && !dataTranslationKilled))
  val aguStoreExecutes = translatedStoreCanAdvance &&
    mmu.io.lookup(1).resp.valid && !dataTranslationKilled &&
    !recoveryBusy && !io.flush
  val translatedCacopExecutes = translatedCacopCanAdvance &&
    mmu.io.lookup(1).resp.valid && !dataTranslationKilled &&
    !recoveryBusy && !io.flush
  val aguSystemCompletes = aguOtherCanAdvance && aguUop.isSystem &&
    aguExecutionAllowed
  val systemRequiresPlv0 = aguUop.sysOp === SYS_CSRRD.U ||
    aguUop.sysOp === SYS_CSRWR.U || aguUop.sysOp === SYS_CSRXCHG.U ||
    (aguUop.sysOp === SYS_CACOP.U && !aguSystemIsHitCacop) ||
    aguUop.sysOp === SYS_TLBSRCH.U || aguUop.sysOp === SYS_TLBRD.U ||
    aguUop.sysOp === SYS_TLBWR.U || aguUop.sysOp === SYS_TLBFILL.U ||
    aguUop.sysOp === SYS_INVTLB.U || aguUop.sysOp === SYS_ERTN.U ||
    aguUop.sysOp === SYS_IDLE.U
  val systemPrivilegeFault = systemRequiresPlv0 && csrCrmd(1, 0) =/= 0.U
  val systemInvOpFault = aguUop.sysOp === SYS_INVTLB.U &&
    aguUop.csrNum(4, 0) > 6.U
  val systemFault = aguUop.exception.valid || systemPrivilegeFault ||
    systemInvOpFault
  val aguSystemExecutes = aguSystemCompletes && !systemFault

  val aguOlderSqMask = Mux(
    lqValids(dataTranslationUop.lsqIdx) &&
      lqEntries(dataTranslationUop.lsqIdx).robIdx === dataTranslationUop.robIdx,
    lqEntries(dataTranslationUop.lsqIdx).olderSqMask & ~sqRetireMask, 0.U)
  val aguSqAddressKnown = VecInit((0 until sqDepth).map(slot =>
    aguOlderSqMask(slot) && sqValids(slot) && sqEntries(slot).addrValid))
  val aguSqAddressMatch = VecInit((0 until sqDepth).map(slot =>
    aguSqAddressKnown(slot) &&
      alignedWord(sqEntries(slot).addr) === alignedWord(translatedPhysAddr)))
  val aguSqOrdered = VecInit((0 until sqDepth).map(slot =>
    aguSqAddressKnown(slot) &&
      (!sqEntries(slot).cacheable || sqEntries(slot).peripheral ||
        sqEntries(slot).memOp === MEM_SC.U)))

  // DTLB 完成只负责存地址，不被 scan、CDB 或 miss 资源的等待占住。
  when(aguLoadExecutes) {
    val idx = dataTranslationUop.lsqIdx
    assert(lqValids(idx) && lqEntries(idx).robIdx === dataTranslationUop.robIdx)
    lqEntries(idx).addr := translatedPhysAddr
    lqEntries(idx).vaddr := translatedVirtualAddr
    lqEntries(idx).requiredMask := translatedLoadMask
    lqEntries(idx).cacheable := translatedCacheable
    lqEntries(idx).peripheral := translatedPeripheral
    lqEntries(idx).knownMiss := false.B
    lqEntries(idx).state := LoadQueueState.readyToIssue
    lqSqKnown(idx) := aguSqAddressKnown
    lqSqMatch(idx) := aguSqAddressMatch
    lqSqOrdered(idx) := aguSqOrdered
  }
  // Load and store uops share a transport index, but only SQ address bits select a store.
  val translatedStoreSlot = dataTranslationUop.lsqIdx(log2Ceil(sqDepth) - 1, 0)
  if (p.enableDifftest) {
    when(aguStoreExecutes) { assert(dataTranslationUop.lsqIdx < sqDepth.U) }
  }
  for (load <- 0 until lqDepth) {
    when(aguStoreExecutes && lqValids(load) &&
        lqEntries(load).olderSqMask(translatedStoreSlot)) {
      val store = translatedStoreSlot
      lqSqKnown(load)(store) := true.B
      lqSqMatch(load)(store) := alignedWord(lqEntries(load).addr) === alignedWord(translatedPhysAddr)
      lqSqOrdered(load)(store) := !translatedCacheable || translatedPeripheral ||
        dataTranslationUop.memOp === MEM_SC.U
    }
  }

  // 重试资格先登记，避免 LQ 的 memOp/旧 store 依赖网络经优先选择器
  // 和载荷 Mux 一直串到 DCache BRAM 地址。scan 仍复核实际依赖和序条件。
  val loadReadySnapshot = RegInit(0.U(lqDepth.W))
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(loadReadySnapshot.toTarget,
      "DONT_TOUCH = \"TRUE\"")
  })
  val loadEligibleNext = VecInit((0 until lqDepth).map { load =>
    val entry = lqEntries(load)
    val dependencies = VecInit((0 until sqDepth).map { store =>
      val older = entry.olderSqMask(store) && !sqRetireMask(store)
      val relevantBytes = (sqEntries(store).storeStrb & entry.requiredMask).orR
      older && (!lqSqKnown(load)(store) || lqSqOrdered(load)(store) ||
        (lqSqMatch(load)(store) && relevantBytes && sqEntries(store).dataState =/= StoreDataState.ready))
    }).asUInt.orR
    val ordered = entry.cacheable || (entry.robIdx === io.robHeadIdx &&
      committedStoreCount === 0.U && bufferEnqCount === 0.U &&
      !bufferedStoreHandoffValid && !orderedStorePending)
    val missReady = !entry.knownMiss || (dcacheMissFree.orR && dcache.io.loadMissReady)
    lqValids(load) && entry.state === LoadQueueState.readyToIssue &&
      !dependencies && ordered && missReady
  }).asUInt
  for (load <- 0 until lqDepth) {
    when(lqValids(load) && !recoveryBusy && !io.flush) {
      assert((lqEntries(load).olderSqMask & ~sqValids.asUInt) === 0.U)
    }
  }
  val currentReadyLoads = VecInit((0 until lqDepth).map(load =>
    lqValids(load) && lqEntries(load).state === LoadQueueState.readyToIssue)).asUInt
  val loadCandidates = loadReadySnapshot & currentReadyLoads
  val registeredLoadGrant = if (preselectLoadGrant) Some(RegInit(0.U(lqDepth.W))) else None
  val queuedLoadOH = if (preselectLoadGrant)
    registeredLoadGrant.get & currentReadyLoads
    else LsqRing.firstFromOH(loadCandidates, lqHead, lqDepth)
  val queuedLoadIdx = OHToUInt(queuedLoadOH)
  // Keep the grant one-hot through payload selection. Only the narrow
  // identity fields need a binary index; no encode/add/decode precedes addr.
  // Current readiness only qualifies ownership. The registered one-hot
  // grant selects the payload in parallel with the LQ validity check.
  val queuedLoad = Mux1H(registeredLoadGrant.getOrElse(queuedLoadOH), lqEntries)
  val scanCanAccept = scanStageReady && dcache.io.lookupReadReady && !recoveryBusy && !io.flush
  val queuedLoadSelected = queuedLoadOH.orR
  assert(queuedLoadSelected === loadCandidates.orR)
  // The old circular selector is an independent simulation witness. Its
  // binary path has no execution fanout and is pruned from hardware RTL.
  val rotatedCandidateWitness = (Cat(loadCandidates, loadCandidates) >> lqHead)(lqDepth - 1, 0)
  val queuedLoadIdxWitness = wrapLq(lqHead +& PriorityEncoder(rotatedCandidateWitness))
  when(queuedLoadSelected) {
    assert(PopCount(queuedLoadOH) === 1.U)
    assert(queuedLoadOH === UIntToOH(queuedLoadIdxWitness, lqDepth))
    assert(queuedLoad.asUInt === lqEntries(queuedLoadIdxWitness).asUInt)
  }
  val queuedLoadIssue = queuedLoadSelected && scanCanAccept
  val directLoadIssue = aguLoadExecutes && !queuedLoadSelected && scanCanAccept
  val scanIssue = queuedLoadIssue || directLoadIssue
  val issueRequest = Wire(new SequentialLoadRequest)
  // 所有者选择独立于接收方 ready；scan 的完成/转发链不进入 BRAM 地址。
  issueRequest.lqIdx := Mux(queuedLoadSelected, queuedLoadIdx, dataTranslationUop.lsqIdx)
  issueRequest.robIdx := Mux(queuedLoadSelected, queuedLoad.robIdx, dataTranslationUop.robIdx)
  issueRequest.prd := Mux(queuedLoadSelected, queuedLoad.prd, dataTranslationUop.prd)
  issueRequest.olderSqMask := Mux(queuedLoadSelected,
    queuedLoad.olderSqMask & ~sqRetireMask, aguOlderSqMask)
  issueRequest.addr := Mux(queuedLoadSelected, queuedLoad.addr, translatedPhysAddr)
  issueRequest.memOp := Mux(queuedLoadSelected, queuedLoad.memOp, dataTranslationUop.memOp)
  issueRequest.cacheable := Mux(queuedLoadSelected, queuedLoad.cacheable, translatedCacheable)
  issueRequest.peripheral := Mux(queuedLoadSelected, queuedLoad.peripheral, translatedPeripheral)
  issueRequest.forwardMask := 0.U
  issueRequest.forwardData := 0.U
  // 新翻译请求若未能直达 scan，下一拍即可从登记队列发起首次查询。
  // 这与原先直达路径一样只启动可撤销的 cache 查询，不绕过 scan 验证。
  val newlyTranslatedLoad = Mux(aguLoadExecutes,
    UIntToOH(dataTranslationUop.lsqIdx, lqDepth), 0.U(lqDepth.W))
  val issuedLoad = Mux(queuedLoadIssue, queuedLoadOH,
    Mux(directLoadIssue, UIntToOH(dataTranslationUop.lsqIdx, lqDepth), 0.U(lqDepth.W)))
  val nextLoadCandidates = loadEligibleNext | newlyTranslatedLoad
  when(io.flush || recoveryBusy) {
    loadReadySnapshot := 0.U
    registeredLoadGrant.foreach(_ := 0.U)
  }.otherwise {
    loadReadySnapshot := nextLoadCandidates & ~issuedLoad
    if (preselectLoadGrant) {
      // Prepare all grant outcomes before the late scan handshake. Only an
      // eight-bit choice follows admission, instead of placing ring priority
      // and a wide LQ payload mux ahead of the next cache query.
      val heldGrant = LsqRing.firstFromOH(nextLoadCandidates, lqHead, lqDepth)
      val afterQueuedIssue = LsqRing.firstFromOH(nextLoadCandidates & ~queuedLoadOH,
        lqHead, lqDepth)
      val afterDirectIssue = LsqRing.firstFromOH(nextLoadCandidates & ~newlyTranslatedLoad,
        lqHead, lqDepth)
      registeredLoadGrant.get := Mux(queuedLoadIssue, afterQueuedIssue,
        Mux(directLoadIssue, afterDirectIssue, heldGrant))
    }
  }
  dcache.io.lookupReadValid := scanIssue
  dcache.io.lookupReadAddr := issueRequest.addr
  dcache.io.lookupAddr := scanRequest.addr

  when(scanIssue) {
    scanRequest := issueRequest
    scanCompareWordAddr := issueRequest.addr(p.xLen - 1, 2)
    scanOffsetOH := UIntToOH(issueRequest.addr(1, 0), p.strbBits)
    scanSignedByte := issueRequest.memOp === MEM_LB.U
    scanSignedHalf := issueRequest.memOp === MEM_LH.U
    scanByteAccess := issueRequest.memOp === MEM_LB.U || issueRequest.memOp === MEM_LBU.U
    scanHalfAccess := issueRequest.memOp === MEM_LH.U || issueRequest.memOp === MEM_LHU.U
    val issueLoadMask = Mux(queuedLoadSelected, queuedLoad.requiredMask, translatedLoadMask)
    scanRequiredLoadMask := issueLoadMask
    assert(issueLoadMask === loadMask(issueRequest.memOp, issueRequest.addr(1, 0)))
    scanSqOwner := !scanSqOwner
    scanValid := true.B
    lqEntries(issueRequest.lqIdx).state := LoadQueueState.executing
  }

  val removedStores = sqRetireMask | Mux(recoverApplyPending, recoverKillSqMask, 0.U)
  when(scanValid && !scanIssue) {
    scanRequest.olderSqMask := scanRequest.olderSqMask & ~removedStores
  }

  // Prepare the next request in the inactive bank. The late scan handshake
  // only transfers ownership and never selects the data written to a bank.
  val nextScanSq = Wire(new ScanSqSnapshot)
  val queuedPayloadOH = registeredLoadGrant.getOrElse(queuedLoadOH)
  nextScanSq.known := Mux(queuedLoadSelected, Mux1H(queuedPayloadOH, lqSqKnown), aguSqAddressKnown)
  nextScanSq.matched := Mux(queuedLoadSelected, Mux1H(queuedPayloadOH, lqSqMatch), aguSqAddressMatch)
  nextScanSq.ordered := Mux(queuedLoadSelected, Mux1H(queuedPayloadOH, lqSqOrdered), aguSqOrdered)
  for (bank <- 0 until 2) {
    val snapshot = scanSqBanks(bank)
    when(scanSqOwner =/= (bank == 1).B) {
      snapshot := nextScanSq
    }.elsewhen(scanValid) {
      for (slot <- 0 until sqDepth) {
        val entry = sqEntries(slot)
        when(removedStores(slot)) {
          snapshot.known(slot) := false.B
          snapshot.matched(slot) := false.B
          snapshot.ordered(slot) := false.B
        }.elsewhen(scanRequest.olderSqMask(slot) && sqValids(slot) &&
            !snapshot.known(slot) && entry.addrValid) {
          snapshot.known(slot) := true.B
          snapshot.matched(slot) := alignedWord(entry.addr) === scanCompareWordAddr
          snapshot.ordered(slot) := !entry.cacheable || entry.peripheral || entry.memOp === MEM_SC.U
        }
      }
    }
  }

  when(aguStoreExecutes) {
    when(sqValids(dataTranslationUop.lsqIdx) &&
        sqEntries(translatedStoreSlot).robIdx === dataTranslationUop.robIdx) {
      sqEntries(translatedStoreSlot).addrValid := true.B
      sqEntries(translatedStoreSlot).addr := translatedPhysAddr
      sqEntries(translatedStoreSlot).vaddr := translatedVirtualAddr
      sqEntries(translatedStoreSlot).cacheable := translatedCacheable
      sqEntries(translatedStoreSlot).peripheral := translatedPeripheral
      sqEntries(translatedStoreSlot).storeStrb := translatedStoreStrb
      when(sqEntries(translatedStoreSlot).dataState ===
          StoreDataState.rawCaptured) {
        sqEntries(translatedStoreSlot).storeData := packStoreData(
          sqEntries(translatedStoreSlot).storeData,
          dataTranslationUop.memOp, translatedPhysAddr(1, 0))
        sqEntries(translatedStoreSlot).dataState := StoreDataState.ready
      }
    }
  }

  val sysResult = MuxLookup(aguUop.sysOp, 0.U(32.W))(Seq(
    SYS_CPUCFG.U -> cpuCfg(aguBits.src1),
    SYS_CSRRD.U -> csrRead(aguUop.csrNum),
    SYS_CSRWR.U -> csrRead(aguUop.csrNum),
    SYS_CSRXCHG.U -> csrRead(aguUop.csrNum),
    SYS_RDCNT.U -> MuxLookup(aguUop.csrNum(1, 0), 0.U(32.W))(Seq(
      0.U -> stableCounter(31, 0),
      1.U -> stableCounter(63, 32),
      2.U -> csrTid
    ))
  ))
  when(aguSystemExecutes) {
    sysPending.robIdx := aguUop.robIdx
    sysPending.restartPc := aguUop.pc + 4.U
    sysPending.sysOp := aguUop.sysOp
    sysPending.csrNum := aguUop.csrNum
    sysPending.writeData := aguBits.src1
    sysPending.writeMask := Mux(aguUop.sysOp === SYS_CSRWR.U,
      "hffffffff".U, Mux(aguUop.sysOp === SYS_CSRXCHG.U,
        aguBits.src2, 0.U))
    sysPending.src2 := aguBits.src2
    sysPending.cacopAddr := aguVirtualAddr
    sysPending.cacopCode := aguUop.csrNum(4, 0)
    sysPending.stableCounter := stableCounter
    systemState := SystemState.waitingForCommit
  }
  when(translatedCacopExecutes) {
    sysPending.robIdx := dataTranslationUop.robIdx
    sysPending.restartPc := dataTranslationUop.pc + 4.U
    sysPending.sysOp := dataTranslationUop.sysOp
    sysPending.csrNum := dataTranslationUop.csrNum
    sysPending.writeData := dataTranslationBits.src1
    sysPending.writeMask := 0.U
    sysPending.src2 := dataTranslationBits.src2
    sysPending.cacopAddr := translatedPhysAddr
    sysPending.cacopCode := dataTranslationUop.csrNum(4, 0)
    sysPending.stableCounter := stableCounter
    systemState := SystemState.waitingForCommit
  }

  val olderSq = VecInit(scanRequest.olderSqMask.asBools)
  when(scanValid && !recoveryBusy && !io.flush) {
    assert((scanRequest.olderSqMask & ~sqValids.asUInt) === 0.U)
  }
  val unknownOlderStore = VecInit((0 until sqDepth).map(slot =>
    olderSq(slot) && !scanSqAddressKnown(slot))).asUInt.orR
  val olderOrderedStore = VecInit((0 until sqDepth).map(slot =>
    olderSq(slot) && scanSqOrdered(slot))).asUInt.orR
  val sqAddressMatch = VecInit((0 until sqDepth).map(slot =>
    olderSq(slot) && scanSqAddressMatch(slot)))

  val requiredLoadMask = scanRequiredLoadMask
  val sqByteMatch = Wire(Vec(p.strbBits, Vec(sqDepth, Bool())))
  val sqByteHasMatch = Wire(Vec(p.strbBits, Bool()))
  val sqByteWinnerReady = Wire(Vec(p.strbBits, Bool()))
  val sqByteForwardData = Wire(Vec(p.strbBits, UInt(8.W)))
  if (p.enableDifftest) {
    assert(sqYoungerSegment === VecInit((0 until sqDepth).map(_.U < sqHead)).asUInt)
  }
  val sqDataReady = VecInit(sqEntries.map(_.dataState === StoreDataState.ready))
  for (byte <- 0 until p.strbBits) {
    for (slot <- 0 until sqDepth) {
      sqByteMatch(byte)(slot) := sqAddressMatch(slot) &&
        sqEntries(slot).storeStrb(byte)
    }
    val matches = sqByteMatch(byte).asUInt
    // Each byte lane checks the already registered age mask in parallel.
    // A late byte strobe no longer crosses two encoders and a segment mux.
    val winner = VecInit((0 until sqDepth).map(slot =>
      matches(slot) && !(matches & sqOlderThan(slot).asUInt).orR)).asUInt
    if (p.enableDifftest) {
      val lower = matches & sqYoungerSegment
      val upper = matches & ~sqYoungerSegment
      val segmentWinner = Mux(lower.orR, Reverse(PriorityEncoderOH(Reverse(lower))),
        Reverse(PriorityEncoderOH(Reverse(upper))))
      when(scanValid && !recoveryBusy && !io.flush) { assert(winner === segmentWinner) }
    }
    sqByteHasMatch(byte) := sqByteMatch(byte).asUInt.orR
    val readyWinner = winner & sqDataReady.asUInt
    sqByteWinnerReady(byte) := readyWinner.orR
    sqByteForwardData(byte) := Mux1H(readyWinner,
      sqEntries.map(_.storeData(8 * byte + 7, 8 * byte)))
  }
  val sqRequiredDataBlocked = VecInit((0 until p.strbBits).map(byte =>
    requiredLoadMask(byte) && sqByteHasMatch(byte) &&
      !sqByteWinnerReady(byte))).asUInt.orR

  // Carry committed-buffer word matches across the existing scan edge.
  // Inactive state prepares the next candidate; the held owner tracks writes
  // to ring slots. Admission changes only ownership, not comparison inputs.
  val scanBufferMatchOwner = RegInit(false.B)
  val scanBufferMatchBanks = Reg(Vec(2, Vec(storeBufferDepth, Bool())))
  val scanHandoffMatchBanks = Reg(Vec(2, Bool()))
  val heldBufferMatches = scanBufferMatchBanks(scanBufferMatchOwner)
  val candidateBufferMatches = committedStores.map(entry =>
    entry.addr(p.xLen - 1, 2) === issueRequest.addr(p.xLen - 1, 2))
  val sqQueryMatches = VecInit(sqEntries.map(entry =>
    entry.addr(p.xLen - 1, 2) === issueRequest.addr(p.xLen - 1, 2))).asUInt
  val sqHeldMatches = VecInit(sqEntries.map(entry =>
    entry.addr(p.xLen - 1, 2) === scanCompareWordAddr)).asUInt
  val commitStoreGrant = commitStoreHits.map(hits => PriorityEncoderOH(hits.asUInt))
  val enqueueQueryMatches = commitStoreGrant.map(grant => (grant & sqQueryMatches).orR)
  val enqueueHeldMatches = commitStoreGrant.map(grant => (grant & sqHeldMatches).orR)
  if (p.enableDifftest) {
    for (lane <- 0 until w) {
      when(bufferEnqValid(lane)) {
        val address = commitStoreEntries(lane).addr(p.xLen - 1, 2)
        assert(enqueueQueryMatches(lane) === (address === issueRequest.addr(p.xLen - 1, 2)))
        assert(enqueueHeldMatches(lane) === (address === scanCompareWordAddr))
      }
    }
  }
  val scQueryMatch = orderedStoreAddr(p.xLen - 1, 2) === issueRequest.addr(p.xLen - 1, 2)
  val scHeldMatch = orderedStoreAddr(p.xLen - 1, 2) === scanCompareWordAddr
  val headQueryMatch = Mux1H(committedStoreHeadOH, candidateBufferMatches)
  val headHeldMatch = Mux1H(committedStoreHeadOH, heldBufferMatches)
  val handoffQueryMatch = bufferedStoreHandoffAddr(p.xLen - 1, 2) ===
    issueRequest.addr(p.xLen - 1, 2)
  for (bank <- 0 until 2) {
    val active = scanBufferMatchOwner === bank.U
    for (slot <- 0 until storeBufferDepth) {
      scanBufferMatchBanks(bank)(slot) :=
        Mux(active, heldBufferMatches(slot), candidateBufferMatches(slot))
      for (lane <- 0 until w) {
        when(bufferEnqValid(lane) && bufferEnqTarget(lane) === slot.U) {
          scanBufferMatchBanks(bank)(slot) :=
            Mux(active, enqueueHeldMatches(lane), enqueueQueryMatches(lane))
        }
      }
      when(scBufferEnq && scBufferTarget === slot.U) {
        scanBufferMatchBanks(bank)(slot) := Mux(active, scHeldMatch, scQueryMatch)
      }
    }
    scanHandoffMatchBanks(bank) := Mux(bufferDeq,
      Mux(active, headHeldMatch, headQueryMatch),
      Mux(active, scanHandoffMatchBanks(scanBufferMatchOwner), handoffQueryMatch))
  }
  when(scanIssue) { scanBufferMatchOwner := !scanBufferMatchOwner }
  val bufferAddressMatch = VecInit((0 until storeBufferDepth).map(slot =>
    committedStoreValid(slot) && heldBufferMatches(slot)))
  when(scanValid) {
    for (slot <- 0 until storeBufferDepth) {
      when(committedStoreValid(slot)) {
        assert(heldBufferMatches(slot) ===
          (committedStores(slot).addr(p.xLen - 1, 2) === scanCompareWordAddr))
      }
    }
    when(bufferedStoreHandoffValid) {
      assert(scanHandoffMatchBanks(scanBufferMatchOwner) ===
        (bufferedStoreHandoffAddr(p.xLen - 1, 2) === scanCompareWordAddr))
    }
  }
  val bufferByteWinner = Wire(Vec(p.strbBits,
    Vec(storeBufferDepth, Bool())))
  val bufferByteMatch = Wire(Vec(p.strbBits,
    Vec(storeBufferDepth, Bool())))
  val bufferByteHasMatch = Wire(Vec(p.strbBits, Bool()))
  val bufferByteForwardData = Wire(Vec(p.strbBits, UInt(8.W)))
  for (byte <- 0 until p.strbBits) {
    for (slot <- 0 until storeBufferDepth) {
      bufferByteMatch(byte)(slot) := bufferAddressMatch(slot) &&
        committedStores(slot).strb(byte)
      val youngerHit = VecInit((0 until storeBufferDepth).map(other =>
        bufferAddressMatch(other) && committedStores(other).strb(byte) &&
          committedStoreOlderThan(slot)(other))).asUInt.orR
      bufferByteWinner(byte)(slot) :=
        bufferByteMatch(byte)(slot) && !youngerHit
    }
    bufferByteHasMatch(byte) := bufferByteMatch(byte).asUInt.orR
    bufferByteForwardData(byte) := Mux1H(bufferByteWinner(byte),
      committedStores.map(_.data(8 * byte + 7, 8 * byte)))
  }
  val handoffAddressMatch = bufferedStoreHandoffValid &&
    scanHandoffMatchBanks(scanBufferMatchOwner)
  val scanForwardCoverageBits = Wire(Vec(p.strbBits, Bool()))
  val scanForwardMaskBits = Wire(Vec(p.strbBits, Bool()))
  val scanForwardDataBytes = Wire(Vec(p.strbBits, UInt(8.W)))
  for (byte <- 0 until p.strbBits) {
    val sqByteValid = sqByteHasMatch(byte) && sqByteWinnerReady(byte)
    val handoffByteValid = handoffAddressMatch &&
      bufferedStoreHandoffStrb(byte)
    val fromBuffer = !sqByteHasMatch(byte) && bufferByteHasMatch(byte)
    val fromHandoff = !sqByteHasMatch(byte) && !bufferByteHasMatch(byte) &&
      handoffByteValid
    val committedByteMatch = bufferByteHasMatch(byte) || handoffByteValid
    scanForwardCoverageBits(byte) := sqByteHasMatch(byte) || committedByteMatch
    scanForwardMaskBits(byte) := sqByteValid || (!sqByteHasMatch(byte) && committedByteMatch)
    // Mask each source at its selection point. These selects are disjoint,
    // and unforwarded bytes are zero so the cache contribution can be ORed in.
    scanForwardDataBytes(byte) := sqByteForwardData(byte) | Mux1H(Seq(
      fromBuffer -> bufferByteForwardData(byte),
      fromHandoff -> bufferedStoreHandoffData(8 * byte + 7, 8 * byte)))
  }
  val scanForwardMask = scanForwardMaskBits.asUInt
  val scanForwardData = Cat(scanForwardDataBytes.reverse)
  // Coverage only needs address and strobes. The dependency check separately
  // waits for the youngest matching store's data on every required byte.
  val scanForwardCoverage =
    (scanForwardCoverageBits.asUInt & requiredLoadMask) === requiredLoadMask
  val scanFullyForwarded = scanForwardCoverage && !sqRequiredDataBlocked
  when(scanValid) {
    assert(scanFullyForwarded === ((scanForwardMask & requiredLoadMask) === requiredLoadMask))
  }
  val slowLoadOrdered = scanRequest.robIdx === io.robHeadIdx &&
    !olderSq.asUInt.orR && committedStoreCount === 0.U &&
    bufferEnqCount === 0.U && !orderedStorePending &&
    !bufferedStoreHandoffValid
  val loadDependencyBlocked = unknownOlderStore || olderOrderedStore ||
    sqRequiredDataBlocked || (!scanRequest.cacheable && !slowLoadOrdered)
  val olderStoreBlocked = loadDependencyBlocked
  val scanDcacheHit = scanRequest.cacheable &&
    (scanLookupHeld || dcache.io.lookupHit)
  val scanLocalDataPresent = scanForwardCoverage || scanDcacheHit
  val peripheralLoadOrdered = !scanRequest.peripheral ||
    (scanRequest.robIdx === io.robHeadIdx && !olderSq.asUInt.orR &&
      committedStoreCount === 0.U && bufferEnqCount === 0.U &&
      !orderedStorePending && !bufferedStoreHandoffValid)
  val uncachedMissPipeReady = readState === SequentialLoadState.idle &&
    !dcache.io.busy
  val dcacheContextHasSpace =
    dcacheMissFree.orR
  val missPipeReady = Mux(scanRequest.cacheable,
    dcache.io.loadMissReady && dcacheContextHasSpace &&
      readState === SequentialLoadState.idle,
    uncachedMissPipeReady)
  val observedDcacheResponse = Wire(Valid(UInt(p.xLen.W)))
  val observedDcacheResponseId = Wire(UInt(p.dcacheMshrIdxBits.W))
  if (registerMissResponses) {
    // Cache hits and local forwarding retain their existing result edge.
    // Detached miss returns cross a dedicated boundary before result-port
    // arbitration; one response per cycle is still accepted without backpressure.
    observedDcacheResponse.valid := RegNext(dcache.io.loadResp.valid, false.B)
    observedDcacheResponse.bits := RegNext(dcache.io.loadResp.bits)
    observedDcacheResponseId := RegNext(dcache.io.loadRespId)
  } else {
    observedDcacheResponse := dcache.io.loadResp
    observedDcacheResponseId := dcache.io.loadRespId
  }
  val dcacheResponseId = observedDcacheResponseId
  val dcacheResponseRequest = dcacheMissRequests(dcacheResponseId)
  val dcacheResponseContextValid = dcacheMissValid(dcacheResponseId)
  val dcacheResponseKilled = dcacheMissKilled(dcacheResponseId) || io.flush ||
    (recoverApplyPending && recoverKillDcacheMiss(dcacheResponseId)) ||
    (io.recover.valid && recoverKillDcacheMissNow(dcacheResponseId))
  val dcacheLoadResponse = observedDcacheResponse.valid &&
    dcacheResponseContextValid && !dcacheResponseKilled
  val uncachedResponseReady =
    (readState === SequentialLoadState.response ||
      readState === SequentialLoadState.drainResponse) &&
      !io.recover.valid && !recoverApplyPending
  val readResponse = readState === SequentialLoadState.response &&
    io.bus.resp_valid && uncachedResponseReady
  externalLoadResponse := dcacheLoadResponse || readResponse
  // A new translation fault can arbitrate only outside recovery/flush.
  // Ignore the concurrent recovery-age filter in this reservation view;
  // the actual completion path above still rejects killed responses.
  val cachedPortOwner = observedDcacheResponse.valid &&
    dcacheResponseContextValid && !dcacheMissKilled(dcacheResponseId)
  val uncachedPortOwner = readState === SequentialLoadState.response && io.bus.resp_valid
  faultResultPortBusy := cachedPortOwner || uncachedPortOwner
  when(dataFaultPending) { assert(faultResultPortBusy === externalLoadResponse) }
  // Raw response-valid and pending fault independently reserve the result
  // port for scan. The filtered MSHR ID and age only arbitrate actual faults
  // and completions, avoiding a feedback path to DCache demand-read enable.
  val localPipeReady =
    !(observedDcacheResponse.valid || readResponse || dataFaultPending)
  val scanCanAdvance = scanValid && !io.flush && !recoveryBusy &&
    !loadDependencyBlocked && peripheralLoadOrdered
  val localCompletes = scanCanAdvance && scanLocalDataPresent && localPipeReady
  val missAdvances = scanCanAdvance && !scanLocalDataPresent && missPipeReady
  val scanMayAdvance = localCompletes || missAdvances
  // 普通 load 资源/依赖/ROB-head 阻塞时让出 scan；地址留在 LQ。
  // 非缓存 load 只在 head 发起总线事务，不能占住 scan 等待更老 load 完成。
  // LL 保持已经从 head 启动的串行路径。
  // 仅缺已知普通store数据且没有其他load可服务时保留所有者，避免空闲重放。
  // 旧load的翻译完成或登记候选出现时必须让出scan，不能挡住数据生产者。
  val retainKnownStoreDataWait = scanRequest.cacheable &&
    sqRequiredDataBlocked && !unknownOlderStore && !olderOrderedStore &&
    (scanDcacheHit || missPipeReady) &&
    !loadCandidates.orR && !aguLoadExecutes
  scanPark := scanValid && scanRequest.memOp =/= MEM_LL.U &&
    !io.flush && !recoveryBusy && !retainKnownStoreDataWait &&
    (loadDependencyBlocked || (!scanLocalDataPresent && !missPipeReady))
  scanStageReady := !scanValid || scanMayAdvance || scanPark
  when(scanPark) {
    lqEntries(scanRequest.lqIdx).state := LoadQueueState.readyToIssue
    lqEntries(scanRequest.lqIdx).knownMiss := scanRequest.cacheable &&
      !loadDependencyBlocked && !scanLocalDataPresent
    when(!scanIssue) { scanValid := false.B }
  }

  when(!scanLookupHeld) {
    scanLookupData := dcache.io.lookupData
  }
  val scanLookupSnapshotSafe = !olderSq.asUInt.orR &&
    committedStoreCount === 0.U && bufferEnqCount === 0.U &&
    !bufferedStoreHandoffValid
  when(io.flush || (recoverApplyPending && recoverKillScan)) {
    scanLookupHeld := false.B
  }.elsewhen(scanIssue || scanMayAdvance || scanPark) {
    scanLookupHeld := false.B
  }.elsewhen(scanValid && dcache.io.lookupHit && !scanLookupHeld &&
      scanLookupSnapshotSafe) {
    scanLookupHeld := true.B
  }

  when(io.flush) {
    scanMissObserved := false.B
  }.elsewhen(scanIssue) {
    scanMissObserved := false.B
  }.elsewhen(scanMayAdvance) {
    scanMissObserved := false.B
  }.elsewhen(scanValid && scanRequest.cacheable &&
      !scanDcacheHit && !scanFullyForwarded) {
    scanMissObserved := true.B
  }

  val mergedLocalWord = Cat((0 until p.strbBits).reverse.map { byte =>
    val fromStore = scanForwardMask(byte)
    Mux1H(Seq(
      fromStore -> scanForwardData(8 * byte + 7, 8 * byte),
      (!fromStore && scanLookupHeld) -> scanLookupData(8 * byte + 7, 8 * byte),
      (!fromStore && !scanLookupHeld) -> dcache.io.lookupData(8 * byte + 7, 8 * byte)))
  })
  // Decode the byte selects and sign mode on the scan admission edge. The
  // BRAM output only traverses data gates and the final byte OR reductions.
  val scanWordAccess = !(scanByteAccess || scanHalfAccess)
  val localBytes = mergedLocalWord.asTypeOf(Vec(p.strbBits, UInt(8.W)))
  val upperByteOffsets = 0 until p.strbBits - 1
  val lowByte = Mux1H((0 until p.strbBits).map(i =>
    (scanOffsetOH(i) && !scanWordAccess) -> localBytes(i)) :+
    (scanWordAccess -> localBytes(0)))
  val highByte = Mux1H(upperByteOffsets.map(i =>
    (scanOffsetOH(i) && scanHalfAccess) -> localBytes(i + 1)) :+
    (scanWordAccess -> localBytes(1)))
  val byteSign = VecInit((0 until p.strbBits).map(i =>
    scanOffsetOH(i) && scanSignedByte && localBytes(i)(7))).asUInt.orR
  val halfSign = VecInit(upperByteOffsets.map(i =>
    scanOffsetOH(i) && scanSignedHalf && localBytes(i + 1)(7))).asUInt.orR
  val cachedLoadData = Cat(
    Mux(scanWordAccess, mergedLocalWord(p.xLen - 1, 16), 0.U) |
      Fill(p.xLen - 16, byteSign || halfSign),
    highByte | Fill(8, byteSign), lowByte)
  if (p.enableDifftest) {
    when(localCompletes) {
      assert(cachedLoadData === formatLoad(
        mergedLocalWord, scanRequest.addr(1, 0), scanRequest.memOp))
    }
  }
  val localLoadData = cachedLoadData

  val scanPreparedRequest = Wire(new SequentialLoadRequest)
  scanPreparedRequest := scanRequest
  scanPreparedRequest.forwardMask := scanForwardMask
  scanPreparedRequest.forwardData := scanForwardData
  val cacheableMissStart = missAdvances && scanRequest.cacheable
  val uncachedMissStart = missAdvances && !scanRequest.cacheable
  dcache.io.loadMissValid := cacheableMissStart
  dcache.io.loadMissAddr := scanRequest.addr
  dcache.io.loadMissId := dcacheMissAllocateId
  // Payload ownership starts only when the original miss acceptance sets
  // dcacheMissValid. Pre-capture the current proposal into every free slot;
  // the accepted slot therefore gets the identical payload on the original
  // edge, without the late SQ/forwarding/ready decision on its write enable.
  // Occupied slots stay frozen through response, flush and recovery. A slot
  // freed by a response is eligible again on the following cycle, as before.
  for (slot <- 0 until dcacheMshrEntries) {
    when(!dcacheMissValid(slot)) {
      dcacheMissRequests(slot) := scanPreparedRequest
    }
  }
  when(cacheableMissStart) {
    assert(dcacheMissFree.orR && !dcacheMissValid(dcacheMissAllocateId))
    dcacheMissValid(dcacheMissAllocateId) := true.B
    dcacheMissKilled(dcacheMissAllocateId) := false.B
  }
  when(uncachedMissStart) {
    readRequest := scanPreparedRequest
    readState := SequentialLoadState.request
  }
  when(scanMayAdvance && !scanIssue) { scanValid := false.B }

  val uncachedReadRequest = readState === SequentialLoadState.request
  io.bus.requ_valid := dcache.io.readReqValid || uncachedReadRequest
  io.bus.requ_addr := Mux(dcache.io.readReqValid,
    dcache.io.readReqAddr, readRequest.addr)
  io.bus.requ_size := Mux(dcache.io.readReqValid, 2.U,
    Mux(readRequest.memOp === MEM_LB.U || readRequest.memOp === MEM_LBU.U,
      0.U, Mux(readRequest.memOp === MEM_LH.U ||
        readRequest.memOp === MEM_LHU.U, 1.U, 2.U)))
  io.bus.requ_len := Mux(dcache.io.readReqValid,
    dcache.io.readReqLen, 0.U)
  io.bus.requ_burst := Mux(dcache.io.readReqValid,
    dcache.io.readReqBurst, Axi4Const.BurstIncr)
  io.bus.requ_type := false.B
  io.bus.requ_wdata := 0.U
  io.bus.requ_wstrb := "b1111".U
  dcache.io.readReqReady := io.bus.requ_ready && dcache.io.readReqValid
  dcache.io.readRespValid := io.bus.resp_valid && dcache.io.readRespReady
  dcache.io.readRespData := io.bus.resp_rdata
  dcache.io.readRespLast := io.bus.resp_last
  io.bus.resp_ready := Mux(dcache.io.readRespReady,
    true.B, uncachedResponseReady)

  when(uncachedReadRequest && !dcache.io.readReqValid && io.bus.requ_ready) {
    readState := SequentialLoadState.response
  }
  val readMergedWord = mergeBytes(io.bus.resp_rdata,
    readRequest.forwardData, readRequest.forwardMask)
  val readLoadData = formatLoad(readMergedWord,
    readRequest.addr(1, 0), readRequest.memOp)
  when(readResponse) {
    readState := SequentialLoadState.idle
  }
  val drainedResponse = readState === SequentialLoadState.drainResponse &&
    io.bus.resp_valid && io.bus.resp_ready
  when(drainedResponse) {
    readState := SequentialLoadState.idle
  }

  val dcacheMissResponse = observedDcacheResponse.valid &&
    dcacheResponseContextValid
  when(dcacheMissResponse) {
    dcacheMissValid(dcacheResponseId) := false.B
    dcacheMissKilled(dcacheResponseId) := false.B
  }
  when(cacheableMissStart =/= dcacheMissResponse) {
    dcacheMissCount := Mux(cacheableMissStart,
      dcacheMissCount + 1.U, dcacheMissCount - 1.U)
  }

  val dcacheMergedWord = mergeBytes(observedDcacheResponse.bits,
    dcacheResponseRequest.forwardData, dcacheResponseRequest.forwardMask)
  val dcacheLoadData = formatLoad(dcacheMergedWord,
    dcacheResponseRequest.addr(1, 0), dcacheResponseRequest.memOp)
  val loadCompletes = localCompletes || externalLoadResponse
  val completedLoad = Wire(new SequentialLoadRequest)
  completedLoad := Mux(dcacheLoadResponse, dcacheResponseRequest,
    Mux(readResponse, readRequest, scanRequest))
  val visibleLoadCompletion = loadCompletes && !io.flush

  assert(!(localCompletes && externalLoadResponse))

  // Qualify each producer before selecting the payload. In particular the
  // scan-to-IQ wake path need not select a tag and then test it for zero.
  val readResponseSelected = readResponse && !dcacheLoadResponse
  io.loadScheduledCdbWake.valid := !io.flush &&
    ((localCompletes && scanRequest.prd =/= 0.U) ||
      (readResponseSelected && readRequest.prd =/= 0.U) ||
      (dcacheLoadResponse && dcacheResponseRequest.prd =/= 0.U))
  io.loadScheduledCdbWake.bits.prd := completedLoad.prd
  io.loadScheduledCdbWake.bits.isMultiply := false.B
  assert(io.loadScheduledCdbWake.valid ===
    (visibleLoadCompletion && completedLoad.prd =/= 0.U))

  val resultCandidate = WireDefault(CdbEntry.default)
  val anySystemCompletes = aguSystemCompletes || translatedCacopExecutes
  assert(!(anySystemCompletes && visibleLoadCompletion), "system and load completion share one result port")
  val ordinaryResultCandidateValid = visibleLoadCompletion || anySystemCompletes ||
    dataFaultCompletes
  val emitScWriteback = scWritebackPending && !ordinaryResultCandidateValid
  val resultCandidateValid = ordinaryResultCandidateValid || emitScWriteback
  // An atomic starts only after older memory/system traffic has drained.
  // atomicInFlight then blocks every younger AGU request until retirement.
  // Thus the registered SC pending state can select metadata before the
  // result flops, without waiting for this cycle's completion arbitration.
  when(scWritebackPending) {
    assert(atomicInFlight, "SC writeback must remain inside the atomic window")
    assert(orderedStorePending && orderedStoreIsSc && orderedStoreRobIdx === atomicRobIdx)
    assert(!ordinaryResultCandidateValid,
      "SC metadata selection requires ordinary completions to be drained")
  }
  // Select result ownership in parallel. In particular, a late local-load
  // completion need not pass through the outer SC mux and then the nested
  // system/fault/load metadata muxes before reaching the result registers.
  // Keep the load owner on an idle non-SC cycle: resultBits always captures.
  val resultSystemSelected = anySystemCompletes
  val resultFaultSelected = !anySystemCompletes && dataFaultCompletes
  val resultLoadSelected = !anySystemCompletes && !dataFaultCompletes &&
    (!scWritebackPending || visibleLoadCompletion)
  val resultOwnerOH = VecInit(Seq(emitScWriteback, resultSystemSelected,
    resultFaultSelected, resultLoadSelected))
  assert(PopCount(resultOwnerOH) === 1.U)
  resultCandidate.valid := true.B
  val resultPrdWitness = Mux(emitScWriteback, orderedStorePrd,
    Mux(anySystemCompletes,
      Mux(aguSystemCompletes && !systemFault, aguUop.prd, 0.U),
      Mux(dataFaultCompletes, 0.U, completedLoad.prd)))
  resultCandidate.prd := Mux1H(Seq(
    emitScWriteback -> orderedStorePrd,
    (resultSystemSelected && aguSystemCompletes && !systemFault) -> aguUop.prd,
    resultLoadSelected -> completedLoad.prd))
  assert(resultCandidate.prd === resultPrdWitness)
  // The atomic window makes the registered SC payload owner independent of
  // load completion. A failed SC supplies zero, and a successful SC supplies one.
  val completedDataSelected = !scWritebackPending && !aguSystemCompletes &&
    !dataFaultCompletes
  val scDataSelected = scWritebackPending && orderedStoreScSuccess
  val nonScData = Mux1H(Seq(
    aguSystemCompletes -> sysResult,
    (completedDataSelected && dcacheLoadResponse) -> dcacheLoadData,
    (completedDataSelected && readResponseSelected) -> readLoadData,
    (completedDataSelected && !externalLoadResponse) -> localLoadData))
  resultCandidate.data := nonScData |
    Cat(0.U((p.xLen - 1).W), scDataSelected)
  val resultRobIdxWitness = Mux(emitScWriteback, orderedStoreRobIdx,
    Mux(anySystemCompletes,
      Mux(translatedCacopExecutes, dataTranslationUop.robIdx, aguUop.robIdx),
      Mux(dataFaultCompletes,
        dataTranslationUop.robIdx, completedLoad.robIdx)))
  resultCandidate.robIdx := Mux1H(resultOwnerOH, Seq(orderedStoreRobIdx,
    Mux(translatedCacopExecutes, dataTranslationUop.robIdx, aguUop.robIdx),
    dataTranslationUop.robIdx, completedLoad.robIdx))
  assert(resultCandidate.robIdx === resultRobIdxWitness)
  val resultCacheableWitness =
    !emitScWriteback && !anySystemCompletes && !dataFaultCompletes &&
      completedLoad.cacheable
  resultCandidate.isCacheableLoad := resultLoadSelected && completedLoad.cacheable
  assert(resultCandidate.isCacheableLoad === resultCacheableWitness)
  when(dataFaultCompletes) {
    resultCandidate.exception := dataException
  }
  when(aguSystemCompletes && systemFault) {
    resultCandidate.exception := aguUop.exception
    when(systemPrivilegeFault) {
      resultCandidate.exception.valid := true.B
      resultCandidate.exception.ecode := ExceptionCode.IPE.U
      resultCandidate.exception.esubcode := 0.U
      resultCandidate.exception.badvValid := false.B
    }.elsewhen(systemInvOpFault) {
      resultCandidate.exception.valid := true.B
      resultCandidate.exception.ecode := ExceptionCode.INE.U
      resultCandidate.exception.esubcode := 0.U
      resultCandidate.exception.badvValid := false.B
    }
  }

  // Select SC metadata with the early registered pending state. The IQ
  // tag replicas now directly launch into wakeup comparisons; no owner mux
  // remains between these flops and the consumers. Normal load latency,
  // result-valid arbitration, and recovery filtering keep their old edges.
  val ordinaryResultPrd = Mux(anySystemCompletes,
    Mux(aguSystemCompletes && !systemFault, aguUop.prd, 0.U),
    Mux(dataFaultCompletes, 0.U, completedLoad.prd))
  val ordinaryResultRobIdx = Mux(anySystemCompletes,
    Mux(translatedCacopExecutes, dataTranslationUop.robIdx, aguUop.robIdx),
    Mux(dataFaultCompletes, dataTranslationUop.robIdx, completedLoad.robIdx))
  // Classification does not wait for a cache hit or scan completion. The
  // registered formal CDB valid qualifies it in consumers, so the late
  // scheduled-wake valid need not fan out into every IQ match register.
  val isLoadResult = !scWritebackPending && !anySystemCompletes && !dataFaultCompletes
  val registeredLoadKind = RegNext(isLoadResult, false.B)
  io.loadCdbIsLoad := registeredLoadKind
  val scheduledLoadWitness = RegNext(io.loadScheduledCdbWake.valid, false.B)
  when(io.out.valid && resultBits.prd =/= 0.U) {
    assert(registeredLoadKind === scheduledLoadWitness)
  }
  resultPayload := resultCandidate
  io.iqSecondSourceData.foreach { copies =>
    for (copy <- copies) { copy := CdbEntry.localDataCopy(resultCandidate.data, true.B) }
  }
  io.cdbData.foreach { copies =>
    for (target <- 0 until p.cdbDataCopies) {
      copies(target) := CdbEntry.localDataCopy(resultCandidate.data, true.B)
    }
  }
  val resultPrdEarly = Mux(scWritebackPending, orderedStorePrd, ordinaryResultPrd)
  resultPayload.prd := resultPrdEarly
  resultPayload.robIdx := Mux(scWritebackPending,
    orderedStoreRobIdx, ordinaryResultRobIdx)
  resultPayload.isCacheableLoad := !scWritebackPending && !anySystemCompletes &&
    !dataFaultCompletes && completedLoad.cacheable
  for (target <- 0 until p.numAlu + 1) {
    iqResultPrd(target) := resultPrdEarly
  }
  val resultPacketWitness = RegNext(resultCandidate)
  assert(resultBits.asUInt === resultPacketWitness.asUInt,
    "LSU result metadata retiming changed the result packet")
  // 年龄判断取寄存的各结果所有者，先于晚产生的完成/转发选择并行计算。
  // 不让 localCompletes 选出 ROB index 后再启动年龄减法和回绕判断。
  def resultRecoveryKill(idx: UInt): Bool = io.recover.valid &&
    RobOrder.isYounger(idx, io.recover.bits, io.recoverHeadIdx)
  val scResultRecoveryKill = resultRecoveryKill(orderedStoreRobIdx)
  val aguResultRecoveryKill = resultRecoveryKill(aguUop.robIdx)
  val translationResultRecoveryKill = resultRecoveryKill(dataTranslationUop.robIdx)
  val scanResultRecoveryKill = resultRecoveryKill(scanRequest.robIdx)
  val cachedResultRecoveryKill = io.recover.valid &&
    recoverKillDcacheMissNow(dcacheResponseId)
  val uncachedResultRecoveryKill = resultRecoveryKill(readRequest.robIdx)
  val systemResultRecoveryKill = Mux(translatedCacopExecutes,
    translationResultRecoveryKill, aguResultRecoveryKill)
  val loadResultRecoveryKill = Mux(dcacheLoadResponse, cachedResultRecoveryKill,
    Mux(readResponse, uncachedResultRecoveryKill, scanResultRecoveryKill))
  val resultRecoveryKillWitness = resultCandidateValid &&
    Mux(emitScWriteback, scResultRecoveryKill,
      Mux(anySystemCompletes, systemResultRecoveryKill,
        Mux(dataFaultCompletes, translationResultRecoveryKill, loadResultRecoveryKill)))
  val resultCandidateKilledByRecovery = resultCandidateValid &&
    Mux1H(resultOwnerOH, Seq(scResultRecoveryKill, systemResultRecoveryKill,
      translationResultRecoveryKill, loadResultRecoveryKill))
  assert(resultCandidateKilledByRecovery === resultRecoveryKillWitness)
  // Each load producer has already filtered recovery before announcing completion.
  assert(!visibleLoadCompletion || !loadResultRecoveryKill)
  val survivingOrdinaryResult =
    (anySystemCompletes && !systemResultRecoveryKill) ||
    (!anySystemCompletes && dataFaultCompletes && !translationResultRecoveryKill) ||
    (!anySystemCompletes && !dataFaultCompletes && visibleLoadCompletion)
  val survivingScResult = scWritebackPending && !ordinaryResultCandidateValid &&
    !scResultRecoveryKill
  val survivingResult = survivingOrdinaryResult || survivingScResult
  assert(survivingResult === (resultCandidateValid && !resultCandidateKilledByRecovery))
  when(io.flush) {
    resultValid := false.B
  }.otherwise {
    resultValid := survivingResult
  }
  // Same result boundary as resultValid; the ROB full flush reaches Core's
  // backend one cycle later. Internal SC visibility still uses resultValid.
  val broadcastResult = survivingResult && !io.flush && !io.fullFlushAhead
  val cdbBroadcastValid = RegNext(broadcastResult, false.B)
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(
      cdbBroadcastValid.toTarget, "max_fanout = 32")
  })
  io.cdbBroadcastValid := cdbBroadcastValid
  for (target <- 0 until p.numAlu) {
    val localValid = RegNext(broadcastResult, false.B)
    dontTouch(localValid)
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(localValid.toTarget,
        "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 16")
    })
    io.aluIqCdbValid(target) := localValid
    val localLoadValid = RegNext(broadcastResult && isLoadResult, false.B)
    dontTouch(localLoadValid)
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(localLoadValid.toTarget,
        "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 16")
    })
    io.aluIqLoadCdbValid(target) := localLoadValid
    if (p.enableDifftest) { assert(localValid === cdbBroadcastValid) }
  }
  when(emitScWriteback) {
    scWritebackPending := false.B
  }

  val scWritebackVisible = resultValid && orderedStorePending &&
    orderedStoreIsSc && resultBits.robIdx === orderedStoreRobIdx
  when(scWritebackVisible) {
    orderedStorePending := false.B
    orderedStoreScNoBus := false.B
    orderedStoreDone := true.B
  }

  when(visibleLoadCompletion && lqValids(completedLoad.lqIdx) &&
      lqEntries(completedLoad.lqIdx).robIdx === completedLoad.robIdx) {
    lqEntries(completedLoad.lqIdx).state := LoadQueueState.complete
  }

  val sysWritesCsr = sysPending.sysOp === SYS_CSRWR.U ||
    sysPending.sysOp === SYS_CSRXCHG.U
  val sysTlbSearch = sysPending.sysOp === SYS_TLBSRCH.U
  val sysTlbRead = sysPending.sysOp === SYS_TLBRD.U
  val sysTlbWrite = sysPending.sysOp === SYS_TLBWR.U
  val sysTlbFill = sysPending.sysOp === SYS_TLBFILL.U
  val sysTlbInvalidate = sysPending.sysOp === SYS_INVTLB.U
  val sysNeedsTlbAdmin = sysTlbSearch || sysTlbRead || sysTlbWrite ||
    sysTlbFill || sysTlbInvalidate
  val affectsTranslation = sysPending.csrNum === CSR_CRMD.U ||
    sysPending.csrNum === CSR_DMW0.U || sysPending.csrNum === CSR_DMW1.U
  val sysDcacheCacop = sysPending.sysOp === SYS_CACOP.U &&
    sysPending.cacopCode(2, 0) === 1.U
  val sysNeedsFullClean = sysPending.sysOp === SYS_IBAR.U ||
    (sysWritesCsr && affectsTranslation)
  val sysNeedsDcacheMaintenance = sysNeedsFullClean || sysDcacheCacop
  val sysMaintenanceInvalidates = (sysWritesCsr && affectsTranslation) ||
    sysDcacheCacop
  val sysDcacheCacopIndex = sysDcacheCacop &&
    sysPending.cacopCode(4, 3) =/= 2.U
  val sysDcacheCacopWritesBack = sysDcacheCacop &&
    sysPending.cacopCode(4, 3) === 1.U

  val sysMemoryQuiescent = lqCount === 0.U && sqCount === 0.U &&
    committedStoreCount === 0.U && !orderedStorePending &&
    bufferEnqCount === 0.U && !bufferedStoreHandoffValid &&
    !aguValid && !aguSpillValid && !scanValid &&
    readState === SequentialLoadState.idle && !dcache.io.busy
  val sysMemoryQuiescentReg = RegNext(sysMemoryQuiescent, false.B)
  val sysPendingMatch = sysPendingValid &&
    sysPending.robIdx === io.sysCommitIdx
  val sysCommitRequest = systemState === SystemState.waitingForCommit &&
    io.sysCommitValid && sysPendingMatch

  val tlbAdminOp = MuxLookup(sysPending.sysOp, TlbAdminOp.Search.U)(Seq(
    SYS_TLBSRCH.U -> TlbAdminOp.Search.U,
    SYS_TLBRD.U -> TlbAdminOp.Read.U,
    SYS_TLBWR.U -> TlbAdminOp.Write.U,
    SYS_TLBFILL.U -> TlbAdminOp.Fill.U,
    SYS_INVTLB.U -> TlbAdminOp.Invalidate.U
  ))
  mmu.io.adminReq.valid := systemState === SystemState.tlbAdminRequest &&
    !io.flush
  mmu.io.adminReq.bits.op := tlbAdminOp
  mmu.io.adminReq.bits.index := csrTlbidx(p.tlbIndexBits - 1, 0)
  mmu.io.adminReq.bits.vppn := csrTlbehi(31, 13)
  mmu.io.adminReq.bits.ps := csrTlbidx(29, 24)
  mmu.io.adminReq.bits.asid := csrAsid(9, 0)
  mmu.io.adminReq.bits.elo0 := csrTlbelo0
  mmu.io.adminReq.bits.elo1 := csrTlbelo1
  mmu.io.adminReq.bits.enable :=
    csrEstat(21, 16) === ExceptionCode.TLBR.U || !csrTlbidx(31)
  mmu.io.adminReq.bits.invOp := sysPending.cacopCode
  mmu.io.adminReq.bits.invAsid := sysPending.writeData(9, 0)
  mmu.io.adminReq.bits.invVa := sysPending.src2
  mmu.io.adminResp.ready := systemState === SystemState.tlbAdminResponse &&
    !io.flush

  val sysTlbAdminStart = systemState === SystemState.draining &&
    sysMemoryQuiescentReg && sysNeedsTlbAdmin
  val sysMaintenanceStart = systemState === SystemState.draining &&
    sysMemoryQuiescentReg &&
    sysNeedsDcacheMaintenance && dcache.io.maintenanceReady
  val sysCommitApplyDirect = systemState === SystemState.draining &&
    sysMemoryQuiescentReg &&
    !sysNeedsDcacheMaintenance && !sysNeedsTlbAdmin

  val orderedMaintenanceStart = orderedStorePending &&
    !orderedStoreScNoBus &&
    !orderedStoreCacheClean && committedStoreCount === 0.U &&
    readState === SequentialLoadState.idle &&
    !dcache.io.busy &&
    systemState =/= SystemState.maintaining &&
    dcache.io.maintenanceReady && !sysMaintenanceStart
  val maintenanceOwnerSystem = RegInit(false.B)
  when(sysMaintenanceStart) {
    maintenanceOwnerSystem := true.B
    systemState := SystemState.maintaining
  }.elsewhen(orderedMaintenanceStart) {
    maintenanceOwnerSystem := false.B
  }

  dcache.io.maintenanceValid := sysMaintenanceStart ||
    orderedMaintenanceStart
  dcache.io.maintenanceAll := sysMaintenanceStart && sysNeedsFullClean
  dcache.io.maintenanceIndex := sysMaintenanceStart &&
    sysDcacheCacopIndex
  dcache.io.maintenanceWriteback := Mux(sysMaintenanceStart,
    sysNeedsFullClean || sysDcacheCacopWritesBack, true.B)
  dcache.io.maintenanceInvalidate := Mux(sysMaintenanceStart,
    sysMaintenanceInvalidates, true.B)
  dcache.io.maintenanceAddr := Mux(sysMaintenanceStart,
    sysPending.cacopAddr, orderedStoreAddr)

  when(dcache.io.maintenanceDone && !maintenanceOwnerSystem) {
    orderedStoreCacheClean := true.B
  }
  val sysCommitApplyAfterMaintenance =
    systemState === SystemState.maintaining &&
      dcache.io.maintenanceDone && maintenanceOwnerSystem
  val sysCommitApplyAfterTlbAdmin = mmu.io.adminResp.fire
  val sysCommitApply = sysCommitApplyDirect ||
    sysCommitApplyAfterMaintenance || sysCommitApplyAfterTlbAdmin
  val sysCommitDoneReg = RegNext(sysCommitApply, false.B)
  io.sysCommitDone := sysCommitDoneReg
  io.sysCommitRestartPc := Mux(sysPending.sysOp === SYS_ERTN.U,
    csrEra, sysPending.restartPc)
  io.difftestTimer := sysPending.stableCounter
  val lastTlbfillIndex = RegInit(0.U(p.tlbIndexBits.W))
  when(mmu.io.adminResp.fire && sysTlbFill) {
    lastTlbfillIndex := mmu.io.adminResp.bits.index
  }
  io.difftestTlbfillIndex := lastTlbfillIndex
  when(sysCommitRequest) { systemState := SystemState.draining }
  when(sysTlbAdminStart) { systemState := SystemState.tlbAdminRequest }
  when(mmu.io.adminReq.fire) { systemState := SystemState.tlbAdminResponse }
  when(sysCommitApply) { systemState := SystemState.applied }

  val sysMaintenanceWindow = sysCommitDoneReg
  io.icacheInvalidateAll := sysMaintenanceWindow &&
    ((sysWritesCsr && affectsTranslation) || sysPending.sysOp === SYS_IBAR.U)
  io.cacop.valid := sysMaintenanceWindow && sysPending.sysOp === SYS_CACOP.U
  io.cacop.bits.code := Mux(io.cacop.valid, sysPending.cacopCode, 0.U)
  io.cacop.bits.addr := Mux(io.cacop.valid, sysPending.cacopAddr, 0.U)

  bufferedStoreHandoffValid := bufferDeq
  when(bufferDeq) {
    bufferedStoreHandoffAddr := committedStoreHeadEntry.addr
    bufferedStoreHandoffData := committedStoreHeadEntry.data
    bufferedStoreHandoffStrb := committedStoreHeadEntry.strb
  }
  when(bufferDeq) { assert(committedStores(committedStoreHead).strb.orR) }
  // The top-level reset synchronizer can produce a clock before the local
  // reset has first asserted in simulation. Do not check a one-hot reset
  // invariant while these registers have not yet observed a local clock.
  val storeHeadCheckValid = RegNext(true.B, false.B)
  when(storeHeadCheckValid) {
    assert(committedStoreHeadOH === UIntToOH(committedStoreHead, storeBufferDepth))
  }

  val tlbIndexMask = ((BigInt(1) << p.tlbIndexBits) - 1).U(32.W)
  when(mmu.io.adminResp.fire && sysTlbSearch) {
    csrTlbidx := Mux(mmu.io.adminResp.bits.found,
      (csrTlbidx & ~(tlbIndexMask | "h80000000".U)) |
        mmu.io.adminResp.bits.index,
      csrTlbidx | "h80000000".U)
  }
  when(mmu.io.adminResp.fire && sysTlbRead) {
    when(mmu.io.adminResp.bits.entryValid) {
      csrTlbidx := (csrTlbidx & "h00ffffff".U) |
        (mmu.io.adminResp.bits.ps << 24)
      csrTlbehi := Cat(mmu.io.adminResp.bits.vppn, 0.U(13.W))
      csrTlbelo0 := mmu.io.adminResp.bits.elo0
      csrTlbelo1 := mmu.io.adminResp.bits.elo1
      csrAsid := (10.U << 16) | mmu.io.adminResp.bits.asid
    }.otherwise {
      csrTlbidx := (csrTlbidx & "h00ffffff".U) | "h80000000".U
      csrTlbehi := 0.U
      csrTlbelo0 := 0.U
      csrTlbelo1 := 0.U
      csrAsid := 10.U << 16
    }
  }

  val csrMerged = (csrRead(sysPending.csrNum) & ~sysPending.writeMask) |
    (sysPending.writeData & sysPending.writeMask)
  when(sysCommitDoneReg && sysWritesCsr) {
    switch(sysPending.csrNum) {
      is(CSR_CRMD.U) { csrCrmd := csrMerged & "h000001ff".U }
      is(CSR_PRMD.U) { csrPrmd := csrMerged & "h00000007".U }
      is(CSR_ECFG.U) { csrEcfg := csrMerged & "h00001bff".U }
      is(CSR_ESTAT.U) {
        csrEstat := (csrEstat & ~3.U(32.W)) | (csrMerged & 3.U)
      }
      is(CSR_ERA.U) { csrEra := csrMerged }
      is(CSR_BADV.U) { csrBadv := csrMerged }
      is(CSR_EENTRY.U) { csrEentry := csrMerged & "hffffffc0".U }
      is(CSR_TLBIDX.U) { csrTlbidx := csrMerged & "hbf00000f".U }
      is(CSR_TLBEHI.U) { csrTlbehi := csrMerged & "hffffe000".U }
      is(CSR_TLBELO0.U) { csrTlbelo0 := csrMerged & "h0fffff7f".U }
      is(CSR_TLBELO1.U) { csrTlbelo1 := csrMerged & "h0fffff7f".U }
      is(CSR_ASID.U) { csrAsid := (10.U << 16) | (csrMerged & "h000003ff".U) }
      is(CSR_PGDL.U) { csrPgdl := csrMerged & "hfffff000".U }
      is(CSR_PGDH.U) { csrPgdh := csrMerged & "hfffff000".U }
      is(CSR_SAVE0.U) { csrSave(0) := csrMerged }
      is(CSR_SAVE1.U) { csrSave(1) := csrMerged }
      is(CSR_SAVE2.U) { csrSave(2) := csrMerged }
      is(CSR_SAVE3.U) { csrSave(3) := csrMerged }
      is(CSR_TID.U) { csrTid := csrMerged }
      is(CSR_TCFG.U) {
        csrTcfg := csrMerged
        timerValue := Cat(csrMerged(31, 2), 0.U(2.W))
        timerActive := csrMerged(0)
      }
      is(CSR_TICLR.U) {
        when(sysPending.writeData(0) && sysPending.writeMask(0)) {
          timerInterrupt := false.B
        }
      }
      is(CSR_LLBCTL.U) {
        csrLlbctl := Cat(csrMerged(2), 0.U(2.W))
        when(sysPending.writeData(1) && sysPending.writeMask(1)) {
          llBit := false.B
        }
      }
      is(CSR_TLBRENTRY.U) { csrTlbRentry := csrMerged & "hffffffc0".U }
      is(CSR_DMW0.U) { csrDmw0 := csrMerged & "hee000039".U }
      is(CSR_DMW1.U) { csrDmw1 := csrMerged & "hee000039".U }
    }
  }

  when(sysCommitDoneReg && sysPending.sysOp === SYS_ERTN.U) {
    val restoredAddressMode = Mux(
      csrEstat(21, 16) === ExceptionCode.TLBR.U,
      "b10".U(2.W), csrCrmd(4, 3))
    csrCrmd := Cat(csrCrmd(31, 5), restoredAddressMode, csrPrmd(2, 0))
    when(!csrLlbctl(2)) {
      llBit := false.B
    }
    csrLlbctl := 0.U
  }

  when(sysCommitDoneReg && sysPending.sysOp === SYS_IDLE.U) {
    idleMode := true.B
  }

  val trapRequestSeen = RegInit(false.B)
  when(!io.trapCommitValid) { trapRequestSeen := false.B }
  val trapApply = io.trapCommitValid && !trapRequestSeen
  when(trapApply) { trapRequestSeen := true.B }
  val trapDoneReg = RegNext(trapApply, false.B)
  io.trapCommitDone := trapDoneReg
  io.trapCommitRestartPc := Mux(
    io.trapCommit.exception.ecode === ExceptionCode.TLBR.U,
    csrTlbRentry, csrEentry)
  io.difftestTrap.valid := trapApply
  io.difftestTrap.pc := io.trapCommit.pc
  io.difftestTrap.inst := io.trapCommit.inst
  io.difftestTrap.cause := io.trapCommit.exception.ecode
  val trapUpdatesTlbehi = io.trapCommit.exception.ecode === ExceptionCode.TLBR.U ||
    io.trapCommit.exception.ecode === ExceptionCode.PIL.U ||
    io.trapCommit.exception.ecode === ExceptionCode.PIS.U ||
    io.trapCommit.exception.ecode === ExceptionCode.PIF.U ||
    io.trapCommit.exception.ecode === ExceptionCode.PME.U ||
    io.trapCommit.exception.ecode === ExceptionCode.PPI.U
  when(trapApply) {
    csrPrmd := Cat(0.U(29.W), csrCrmd(2, 0))
    val trapAddressMode = Mux(
      io.trapCommit.exception.ecode === ExceptionCode.TLBR.U,
      "b01".U(2.W), csrCrmd(4, 3))
    csrCrmd := Cat(csrCrmd(31, 5), trapAddressMode, 0.U(3.W))
    csrEstat := (csrEstat & "h8000ffff".U) |
      (io.trapCommit.exception.ecode << 16) |
      (io.trapCommit.exception.esubcode << 22)
    csrEra := io.trapCommit.pc
    when(io.trapCommit.exception.badvValid) {
      csrBadv := io.trapCommit.exception.badv
    }
    when(io.trapCommit.exception.badvValid && trapUpdatesTlbehi) {
      csrTlbehi := Cat(io.trapCommit.exception.badv(31, 13), 0.U(13.W))
    }
  }
  when(interruptEligible || trapApply) {
    idleMode := false.B
  }

  when(io.flush) {
    for (slot <- 0 until dcacheMshrEntries) {
      when(dcacheMissValid(slot)) {
        dcacheMissKilled(slot) := true.B
      }
    }
    lqValids := VecInit(Seq.fill(lqDepth)(false.B))
    sqValids := VecInit(Seq.fill(sqDepth)(false.B))
    sqOlderThan := VecInit(Seq.fill(sqDepth)(
      VecInit(Seq.fill(sqDepth)(false.B))))
    sqDataTokenValid := VecInit(Seq.fill(sqDepth)(false.B))
    lqHead := 0.U
    lqTail := 0.U
    lqCount := 0.U
    sqHead := 0.U
    sqTail := 0.U
    sqCount := 0.U
    scanValid := false.B
    systemState := SystemState.idle
    orderedStorePending := false.B
    orderedStoreIsSc := false.B
    orderedStoreScNoBus := false.B
    scWritebackPending := false.B
    orderedStoreCacheClean := false.B
    orderedStoreDone := false.B
    when(readState === SequentialLoadState.request) {
      readState := Mux(io.bus.requ_ready,
        SequentialLoadState.drainResponse, SequentialLoadState.idle)
    }.elsewhen(readState === SequentialLoadState.response) {
      readState := Mux(io.bus.resp_valid,
        SequentialLoadState.idle, SequentialLoadState.drainResponse)
    }
  }.elsewhen(recoverApplyPending) {
    for (slot <- 0 until dcacheMshrEntries) {
      when(recoverKillDcacheMiss(slot)) {
        dcacheMissKilled(slot) := true.B
      }
    }
    for (slot <- 0 until lqDepth) {
      when(recoverKillLqMask(slot)) {
        lqValids(slot) := false.B
      }.otherwise {
        lqEntries(slot).olderSqMask := lqEntries(slot).olderSqMask &
          ~(sqRetireMask | recoverKillSqMask)
      }
    }
    for (slot <- 0 until sqDepth) {
      when(recoverKillSqMask(slot)) {
        sqValids(slot) := false.B
        sqDataTokenValid(slot) := false.B
      }
    }
    for (rank <- 0 until w) {
      when(lqRetireCount > rank.U) {
        lqValids(wrapLq(lqHead +& rank.U)) := false.B
      }
      when(sqRetireCount > rank.U) {
        val idx = wrapSq(sqHead +& rank.U)
        sqValids(idx) := false.B
        sqDataTokenValid(idx) := false.B
      }
    }
    lqHead := wrapLq(lqHead +& lqRetireCount)
    sqHead := sqNextHead
    lqTail := LsqRing.retreat(lqTail, recoverKillLqCount, lqDepth)
    sqTail := LsqRing.retreat(sqTail, recoverKillSqCount, sqDepth)
    lqCount := lqCount - lqRetireCount - recoverKillLqCount
    sqCount := sqCount - sqRetireCount - recoverKillSqCount
    when(recoverKillScan) {
      scanValid := false.B
    }
    when(recoverKillRead) {
      when(readState === SequentialLoadState.request) {
        readState := Mux(io.bus.requ_ready,
          SequentialLoadState.drainResponse, SequentialLoadState.idle)
      }.elsewhen(readState === SequentialLoadState.response) {
        readState := SequentialLoadState.drainResponse
      }
    }
    when(recoverKillSystem) {
      systemState := SystemState.idle
    }
  }.otherwise {
    for (rank <- 0 until w) {
      when(lqRetireCount > rank.U) {
        lqValids(wrapLq(lqHead +& rank.U)) := false.B
      }
      when(sqRetireCount > rank.U) {
        val idx = wrapSq(sqHead +& rank.U)
        sqValids(idx) := false.B
        sqDataTokenValid(idx) := false.B
      }
    }
    lqHead := wrapLq(lqHead +& lqRetireCount)
    sqHead := sqNextHead
    lqTail := wrapLq(lqTail +& lqAllocCount)
    sqTail := wrapSq(sqTail +& sqAllocCount)
    lqCount := lqCount +& lqAllocCount - lqRetireCount
    sqCount := sqCount +& sqAllocCount - sqRetireCount

    for (slot <- 0 until lqDepth) {
      when(lqValids(slot)) {
        lqEntries(slot).olderSqMask :=
          lqEntries(slot).olderSqMask & ~sqRetireMask
      }
    }

    for (older <- 0 until sqDepth) {
      for (younger <- 0 until sqDepth) {
        if (older == younger) {
          sqOlderThan(older)(younger) := false.B
        } else {
          when(sqSlotEnq(older) || sqSlotEnq(younger)) {
            sqOlderThan(older)(younger) := Mux(
              sqSlotEnq(older) && sqSlotEnq(younger),
              sqSlotEnqRank(older) < sqSlotEnqRank(younger),
              !sqSlotEnq(older) && sqSlotEnq(younger) &&
                liveSqAfterRetire(older))
          }
        }
      }
    }

    for (lane <- 0 until w) {
      when(lqAllocValid(lane)) {
        val idx = io.lsqAllocIdx(lane)
        val uop = io.lsqAllocBits(lane)
        val sameGroupOlderStores = (0 until lane).map(olderLane =>
          Mux(sqAllocValid(olderLane),
            UIntToOH(io.lsqAllocIdx(olderLane), sqDepth),
            0.U(sqDepth.W))).foldLeft(0.U(sqDepth.W))(_ | _)
        lqValids(idx) := true.B
        lqEntries(idx).robIdx := uop.robIdx
        lqEntries(idx).prd := uop.prd
        lqEntries(idx).memOp := uop.memOp
        lqEntries(idx).olderSqMask :=
          liveSqAfterRetire | sameGroupOlderStores
        // Translation writes the address payload before the entry becomes ready.
        lqEntries(idx).cacheable := false.B
        lqEntries(idx).peripheral := false.B
        lqEntries(idx).knownMiss := false.B
        lqEntries(idx).state := LoadQueueState.waitingForAddress
      }
      when(sqAllocValid(lane)) {
        val idx = io.lsqAllocIdx(lane)
        val uop = io.lsqAllocBits(lane)
        sqValids(idx) := true.B
        sqDataTokenValid(idx) := false.B
        sqEntries(idx).robIdx := uop.robIdx
        sqEntries(idx).prd := uop.prd
        sqEntries(idx).memOp := uop.memOp
        sqEntries(idx).addrValid := false.B
        sqEntries(idx).cacheable := false.B
        sqEntries(idx).peripheral := false.B
        sqEntries(idx).storeData := io.lsqAllocStoreData(lane)
        sqEntries(idx).storeStrb := 0.U
        sqEntries(idx).storeDataPrs := uop.prs2
        sqEntries(idx).dataState := Mux(io.lsqAllocStoreDataReady(lane),
          StoreDataState.rawCaptured, StoreDataState.waitingForCdb)
      }
    }
  }

  when(!io.flush) {
    assert(lqAllocCount <= lqAvailable)
    assert(sqAllocCount <= sqAvailable)
    assert(bufferEnqCount <= committedAvailable)
    assert(dcacheMissCount === PopCount(dcacheMissValid))
    when(observedDcacheResponse.valid) { assert(dcacheResponseContextValid) }
    assert(lqCount === PopCount(lqValids))
    assert(sqCount === PopCount(sqValids))
    assert(lqHead < lqDepth.U && lqTail < lqDepth.U && lqCount <= lqDepth.U)
    assert(sqHead < sqDepth.U && sqTail < sqDepth.U && sqCount <= sqDepth.U)
    when(scanMayAdvance && scanRequest.peripheral) {
      assert(scanRequest.robIdx === io.robHeadIdx)
      assert(committedStoreCount === 0.U)
      assert(!orderedStorePending)
    }
    for (lane <- 0 until w) {
      when(bufferEnqValid(lane)) {
        assert(commitStoreEntries(lane).addrValid)
        assert(commitStoreEntries(lane).dataState === StoreDataState.ready)
      }
    }
  }

  io.perf := 0.U.asTypeOf(new LsuPerfEvents)
  io.perf.loadComplete := visibleLoadCompletion
  io.perf.dcacheHit := localCompletes && scanDcacheHit &&
    !scanFullyForwarded && !scanMissObserved
  io.perf.dcacheHitRobIdx := scanRequest.robIdx
  io.perf.dcacheHitUnderMiss := io.perf.dcacheHit && dcache.io.busy
  io.perf.mshrBusy := dcache.io.busy
  io.perf.mshrOccupancy := dcache.io.mshrOccupancy
  io.perf.busy := aguValid || scanValid || readyLoadPresent || readBusy ||
    committedStoreCount =/= 0.U || orderedStorePending
  io.perf.lqOccupancy := lqCount
  io.perf.sqOccupancy := sqCount
  io.perf.loadAddressAccepted := aguLoadExecutes
  io.perf.loadAddressPrd := dataTranslationUop.prd
  io.perf.storeBufferLookup := scanMayAdvance
  io.perf.storeBufferLookupPrd := scanRequest.prd
  io.perf.loadBusRequestAccepted := io.bus.requ_valid && io.bus.requ_ready
  io.perf.loadBusRequestPrd := Mux(dcache.io.readReqValid,
    Mux(dcache.io.readReqIsLoad, dcacheMissRequests(dcache.io.readReqLoadId).prd, 0.U),
    readRequest.prd)
  io.perf.loadBusResponse := dcacheLoadResponse || readResponse
  io.perf.loadBusResponsePrd := Mux(dcacheLoadResponse,
    dcacheResponseRequest.prd, readRequest.prd)
  io.perf.scheduledWakeValid := io.loadScheduledCdbWake.valid
  io.perf.scheduledWakePrd := io.loadScheduledCdbWake.bits.prd
  io.perf.readyLoadPresent := (scanValid && !olderStoreBlocked) || loadCandidates.orR
  io.perf.aguStageValid := aguValid
  io.perf.storeBufferStageValid := scanValid
  io.perf.resultStageValid := resultValid
  io.perf.busOwnerValid := readBusy
  io.perf.busRequestBlocked := io.bus.requ_valid && !io.bus.requ_ready
  io.perf.olderStoreBlocked := scanValid && olderStoreBlocked
  io.perf.dcacheStoreUpdateEnq := dcache.io.perf.storeUpdateEnq
  io.perf.dcacheFillOccupancy := dcache.io.perf.fillOccupancy
  io.perf.dcacheFillFull := dcache.io.perf.fillFull
  io.perf.dcacheStoreUpdateMerge := dcache.io.perf.storeUpdateMerge
  io.perf.dcacheStoreUpdateComplete := dcache.io.perf.storeUpdateComplete
  io.perf.dcacheStoreFastHit := dcache.io.perf.storeFastHit
  io.perf.dcacheStorePartialHit := dcache.io.perf.storePartialHit
  io.perf.dcacheStoreMiss := dcache.io.perf.storeMiss
  io.perf.dcacheStoreFifoFull := dcache.io.perf.storeFifoFull
  io.perf.dcacheStoreFifoOccupancy := dcache.io.perf.storeFifoOccupancy
  io.perf.dcacheLoadRefill := dcache.io.perf.loadRefill
  io.perf.dcacheStoreRefill := dcache.io.perf.storeRefill
  io.perf.dcacheVictimCapture := dcache.io.perf.victimCapture
  io.perf.dcacheWbFullWait := dcache.io.perf.wbFullWait
  io.perf.dcacheWbOccupancy := dcache.io.perf.wbOccupancy
  io.perf.dcacheSameLineRawWait := dcache.io.perf.sameLineRawWait
  io.perf.dcacheSameLineRefillResponse :=
    dcache.io.perf.sameLineRefillResponse
  io.perf.dcacheStoreRefillBlocked := dcache.io.perf.storeRefillBlocked
  io.perf.dcacheStoreRefillDifferentWay :=
    dcache.io.perf.storeRefillDifferentWay
  io.perf.dcacheStoreRefillParallel := dcache.io.perf.storeRefillParallel
  io.perf.dcacheSameSetFillParallel := dcache.io.perf.sameSetFillParallel
  io.perf.dcacheWaiterDetached := dcache.io.perf.waiterDetached
  io.perf.dcacheMissQueuedBehindEngine :=
    dcache.io.perf.missQueuedBehindEngine
  io.perf.dcacheMissQueuedSameLine := dcache.io.perf.missQueuedSameLine
  io.perf.dcacheStoreHeadSameSetWait := dcache.io.perf.storeHeadSameSetWait
  io.perf.dcacheStoreHeadSameLineWait := dcache.io.perf.storeHeadSameLineWait
}
