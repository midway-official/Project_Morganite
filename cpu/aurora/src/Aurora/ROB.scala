package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** 重排序缓冲（ROB）：多端口顺序分配、多端口顺序提交、写回释放、冲刷。
  * 参数化多发射版本：
  *  - 分配：每周期最多 coreWidth 条，占用连续的 tail..tail+k-1 表项（环形）。
  *    robCanAlloc 告知 ISU 本周期最多可接收几条；robAllocIdx(i) 给出第 i 路
  *    将占用的 ROB 序号。分配为整前缀（ISU 侧 all-or-nothing）。
  *  - 提交：每周期从 head 起顺序提交最多 coreWidth 条已完成表项；遇到未完成、
  *    或需要总线握手未完成的 store、或误预测分支（提交后即冲刷）时截断。
  *  - 冲刷：提交误预测分支时清空该分支之后的所有年轻指令（本设计分支为屏障，
  *    故直接全清 ROB）。
  * 重构说明：
  *  - store 的地址/数据/字节选通信息由 LSU 内部维护，ROB 表项不再包含这些字段。
  *  - ROB 仅通知 LSU 当前提交的 store 的 robIdx，LSU 自行查找对应数据并驱动总线写。
  */
class RobEntry(implicit p: AuroraConfig) extends Bundle {
  val busy      = Bool()
  val done      = Bool()
  val pc        = UInt(p.xLen.W)
  val inst      = UInt(32.W)
  val ldst      = UInt(p.archRegBits.W)
  val prd       = UInt(p.physRegBits.W)
  val prevPrd   = UInt(p.physRegBits.W)
  val writesReg = Bool()
  val isLoad    = Bool()
  val isStore   = Bool()
  val isSystem  = Bool()
  val isBranch  = Bool()
  val isConditional = Bool()
  val isBackward = Bool()
  val isCall    = Bool()
  val isRet     = Bool()
  val isIndirect = Bool()
  val bhtIdx    = UInt(p.bhtIdxBits.W)
  val predTaken = Bool()
  val predGhr   = UInt(p.ghrBits.W)
  val predTageTable = UInt(p.tageTableBits.W)
  val predTageGhr = UInt(p.ghrBits.W)
  val predCtr   = UInt(p.tageCounterBits.W)
  val mispredict = Bool()
  val targetPc   = UInt(p.xLen.W)
  val actualTaken = Bool()
  val exception  = new ExceptionInfo
}

/** ROB 中只在分配时写入、提交时读取的稳定载荷。
  * busy/done/isBranch/mispredict/exception 需要由分配、CDB 和 flush 随机更新，
  * 因此保留在 FF；分支目标由 CDB 写入独立的分布式 RAM。
  */
class RobPayload(implicit p: AuroraConfig) extends Bundle {
  val pc        = UInt(p.xLen.W)
  val inst      = UInt(32.W)
  val ldst      = UInt(p.archRegBits.W)
  val prd       = UInt(p.physRegBits.W)
  val prevPrd   = UInt(p.physRegBits.W)
  val writesReg = Bool()
  val isLoad    = Bool()
  val isStore   = Bool()
  val isSystem  = Bool()
  val isConditional = Bool()
  val isBackward = Bool()
  val isCall    = Bool()
  val isRet     = Bool()
  val isIndirect = Bool()
  val bhtIdx    = UInt(p.bhtIdxBits.W)
  val predTaken = Bool()
  val predGhr   = UInt(p.ghrBits.W)
  val predTageTable = UInt(p.tageTableBits.W)
  val predTageGhr = UInt(p.ghrBits.W)
  val predCtr   = UInt(p.tageCounterBits.W)
}

/** 提交端口暴露给外部（ISU archRat/freeList）的信息。 */
class RobCommitPort(implicit p: AuroraConfig) extends Bundle {
  val valid     = Bool()
  val pc        = UInt(p.xLen.W)
  val inst      = UInt(32.W)
  val writesReg = Bool()
  val ldst      = UInt(p.archRegBits.W)
  val prd       = UInt(p.physRegBits.W)
  val prevPrd   = UInt(p.physRegBits.W)
  val robIdx    = UInt(p.robIdxBits.W)
  val isLoad    = Bool()
  val retireLq  = Bool()
  val isStore   = Bool()
  val isCacheableStore = Bool()
  val isSystem  = Bool()
  val isBranch  = Bool()
  val isConditional = Bool()
  val isBackward = Bool()
  val isCall    = Bool()
  val isRet     = Bool()
  val isIndirect = Bool()
  val bhtIdx    = UInt(p.bhtIdxBits.W)
  val predTaken = Bool()
  val predGhr   = UInt(p.ghrBits.W)
  val predTageTable = UInt(p.tageTableBits.W)
  val predTageGhr = UInt(p.ghrBits.W)
  val predCtr   = UInt(p.tageCounterBits.W)
  val actualTaken = Bool()
  val targetPc  = UInt(p.xLen.W)
}

class ROB(numCdb: Int)(implicit p: AuroraConfig) extends Module {
  val w = p.coreWidth
  private val storeCommitWindowDepth = p.storeCommitWindowDepth

  val io = IO(new Bundle {
    // 多端口分配（来自 ISU）
    val allocReq   = Input(Vec(w, Bool()))
    val allocEntry = Input(Vec(w, new RobEntry))
    val allocIdx   = Output(Vec(w, UInt(p.robIdxBits.W)))
    val canAlloc   = Output(UInt(p.issueCountBits.W)) // 本周期可接收的条数
    val allocationCredits = Output(UInt(log2Ceil(3 * w + 1).W))

    // CDB 写回，标记完成
    val cdb = Input(Vec(numCdb, new CdbEntry))

    val lqRelease = Input(Valid(UInt(p.robIdxBits.W)))

    // 多提交端口
    val commit = Output(Vec(w, new RobCommitPort))

    // store 提交通知（告知 LSU 当前正在提交的 store 的 robIdx）
    val storeCommitValid = Output(Bool())
    val storeCommitIdx   = Output(UInt(p.robIdxBits.W))
    val storeCommitDone  = Input(Bool())  // LSU 确认 store 总线写完成
    val storeCommitAcked = Output(Bool())
    val storeWindowBase = Input(UInt(p.robIdxBits.W))
    val storeWindowValid = Input(Vec(storeCommitWindowDepth, Bool()))
    val storeWindowCacheable = Input(Vec(storeCommitWindowDepth, Bool()))
    val storeWindowDataReady = Input(Vec(storeCommitWindowDepth, Bool()))
    val storeCommitCapacity = Input(UInt(p.issueCountBits.W))

    // 低频系统指令在 ROB 头部串行提交，副作用由 LSU/CSR 侧确认。
    val sysCommitValid = Output(Bool())
    val sysCommitIdx   = Output(UInt(p.robIdxBits.W))
    val sysCommitDone  = Input(Bool())
    val sysCommitRestartPc = Input(UInt(p.xLen.W))

    val trapCommitValid = Output(Bool())
    val trapCommit = Output(new TrapRequest)
    val trapCommitDone = Input(Bool())
    val trapCommitRestartPc = Input(UInt(p.xLen.W))

    val flush = Output(new FlushInfo)
    val recover = Input(Valid(UInt(p.robIdxBits.W)))
    val hasUnresolvedBranch = Output(Bool())

    val headIdx = Output(UInt(p.robIdxBits.W))
    val empty   = Output(Bool())
    val occupancy = Output(UInt(p.robCountBits.W))
    val headPc = Output(UInt(p.xLen.W))
    val headDone = Output(Bool())
    val headIsLoad = Output(Bool())
    val headIsStore = Output(Bool())
    val headIsBranch = Output(Bool())
  })

  val n = p.numRobEntries
  private val numBanks = p.robBanks
  private val bankBits = p.robBankBits
  private val bankDepth = p.robBankDepth
  private val rowBits = p.robRowBits
  private val payloadWidth = (new RobPayload).getWidth

  // robIdx 的低 robBankBits 位选择 bank，其余高位选择行。主载荷和分支目标都不复位，
  // 由 busy/done 状态屏蔽无效内容；异步读 Mem 保持原有零额外拍提交时序。
  val payloadBanks = Seq.fill(numBanks)(Mem(bankDepth, UInt(payloadWidth.W)))
  val targetBanks = Seq.fill(numBanks)(Mem(bankDepth, UInt(p.xLen.W)))
  for (bank <- payloadBanks ++ targetBanks) {
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        AttributeAnnotation(bank.toTarget, "ram_style = \"distributed\"")
    })
  }

  // busy 是表项唯一有效性边界，需要 reset/flush。其余状态在每次分配时完整
  // 初始化，并且只在 busy=true 时可见，因此保留为无复位 FF。
  val busyBits       = RegInit(0.U(n.W))
  val busyRead       = VecInit((0 until n).map(i => busyBits(i)))
  val doneBits       = Reg(UInt(n.W))
  val doneRead       = VecInit((0 until n).map(i => doneBits(i)))
  val lqReleasedBits = RegInit(0.U(n.W))
  val lqReleasedRead = VecInit((0 until n).map(i => lqReleasedBits(i)))
  val branchBits     = Reg(UInt(n.W))
  val branchRead     = VecInit((0 until n).map(i => branchBits(i)))
  val mispredictBits = Reg(UInt(n.W))
  val mispredictRead = VecInit((0 until n).map(i => mispredictBits(i)))
  val takenBits      = Reg(UInt(n.W))
  val takenRead      = VecInit((0 until n).map(i => takenBits(i)))
  val exceptionBits  = Reg(Vec(n, Bool()))
  val exceptionEcode = Reg(Vec(n, UInt(6.W)))
  val exceptionEsubcode = Reg(Vec(n, UInt(9.W)))
  val exceptionBadv = Reg(Vec(n, UInt(p.xLen.W)))
  val exceptionBadvValid = Reg(Vec(n, Bool()))

  val head = RegInit(0.U(log2Ceil(n).W))
  val tail = RegInit(0.U(log2Ceil(n).W))
  val recoverApplyPending = RegInit(false.B)
  val count = RegInit(0.U(p.robCountBits.W))
  val retirePending = RegInit(0.U(p.issueCountBits.W))
  val effectiveCount = count - retirePending
  private val creditBits = log2Ceil(2 * w + 1)
  val allocationBaseCredits = RegInit((2 * w).U(creditBits.W))
  // Saturation at two dispatch groups preserves every allocation decision:
  // one group can be reserved while another is admitted. Keep the registered
  // retirement boundary; current commit arbitration must not feed count.
  io.allocationCredits := allocationBaseCredits + retirePending
  io.occupancy := effectiveCount

  val full = effectiveCount === n.U
  val empty = effectiveCount === 0.U
  io.empty := empty
  io.headIdx := head

  // 关键路径优化：numRobEntries 为 2 的幂时用位掩码替代 Mux+减法。
  def wrap(x: UInt): UInt = x & (n - 1).U
  def bankOf(idx: UInt): UInt =
    if (numBanks == 1) 0.U else idx(bankBits - 1, 0)
  def rowOf(idx: UInt): UInt =
    if (numBanks == 1) idx else idx(p.robIdxBits - 1, bankBits)

  // ---- 分配：连续占用 tail.. ----
  val allocPrefix = Wire(Vec(w, UInt(p.issueCountBits.W)))
  locally {
    var acc = 0.U(p.issueCountBits.W)
    for (i <- 0 until w) {
      allocPrefix(i) := acc
      acc = acc + io.allocReq(i).asUInt
    }
  }
  for (i <- 0 until w) {
    io.allocIdx(i) := wrap(tail +& allocPrefix(i))
  }
  val allocCount = PopCount(io.allocReq)
  val freeSlots = n.U - effectiveCount
  val freeAfterAlloc = Mux(freeSlots >= allocCount,
    freeSlots - allocCount, 0.U)
  io.canAlloc := Mux(freeAfterAlloc >= w.U,
    w.U, freeAfterAlloc(p.issueCountBits - 1, 0))

  val stateAllocCount = RegInit(0.U(p.issueCountBits.W))
  val stateAllocTail = Reg(UInt(p.robIdxBits.W))
  val stateAllocBranch = Reg(Vec(w, Bool()))
  val allocStateByRank = (0 until w).map(k => {
    val idx = wrap(stateAllocTail +& k.U)
    Mux(stateAllocCount > k.U, UIntToOH(idx, n), 0.U(n.W))
  })
  val allocStateMask = allocStateByRank.reduce(_ | _)

  // 状态阵列按分配 rank 写连续的 tail..tail+w-1。外部 lane 仍通过 allocPrefix
  // 获得原有 robIdx，但状态写地址不再依赖实时 allocReq 前缀，避免为每个
  // 稀疏 lane 生成一套 64 项动态地址译码。
  val allocEntryByRank = Wire(Vec(w, new RobEntry))
  for (k <- 0 until w) {
    val rankHit = VecInit((0 until w).map(i =>
      io.allocReq(i) && allocPrefix(i) === k.U))
    allocEntryByRank(k) := Mux1H(rankHit, io.allocEntry)
    when(allocCount > k.U) {
      val idx = wrap(tail +& k.U)
      val entry = allocEntryByRank(k)
      exceptionBits(idx)  := entry.exception.valid
      exceptionEcode(idx) := entry.exception.ecode
      exceptionEsubcode(idx) := entry.exception.esubcode
      exceptionBadv(idx) := entry.exception.badv
      exceptionBadvValid(idx) := entry.exception.badvValid
    }
  }
  val allocBranchSetMask = (0 until w).map(k =>
    Mux(stateAllocBranch(k), allocStateByRank(k), 0.U(n.W))).reduce(_ | _)
  branchBits := (branchBits & ~allocStateMask) | allocBranchSetMask
  val allocPayloadByRank = Wire(Vec(w, new RobPayload))
  for (k <- 0 until w) {
    val entry = allocEntryByRank(k)
    allocPayloadByRank(k).pc        := entry.pc
    allocPayloadByRank(k).inst      := entry.inst
    allocPayloadByRank(k).ldst      := entry.ldst
    allocPayloadByRank(k).prd       := entry.prd
    allocPayloadByRank(k).prevPrd   := entry.prevPrd
    allocPayloadByRank(k).writesReg := entry.writesReg
    allocPayloadByRank(k).isLoad    := entry.isLoad
    allocPayloadByRank(k).isStore   := entry.isStore
    allocPayloadByRank(k).isSystem  := entry.isSystem
    allocPayloadByRank(k).isConditional := entry.isConditional
    allocPayloadByRank(k).isBackward := entry.isBackward
    allocPayloadByRank(k).isCall    := entry.isCall
    allocPayloadByRank(k).isRet     := entry.isRet
    allocPayloadByRank(k).isIndirect := entry.isIndirect
    allocPayloadByRank(k).bhtIdx    := entry.bhtIdx
    allocPayloadByRank(k).predTaken := entry.predTaken
    allocPayloadByRank(k).predGhr   := entry.predGhr
    allocPayloadByRank(k).predTageTable := entry.predTageTable
    allocPayloadByRank(k).predTageGhr := entry.predTageGhr
    allocPayloadByRank(k).predCtr   := entry.predCtr
  }

  val allocRankIdx = VecInit((0 until w).map(k => wrap(tail +& k.U)))
  for (b <- 0 until numBanks) {
    val bankLocation = VecInit((0 until w).map(k => bankOf(allocRankIdx(k)) === b.U))
    val bankHit = VecInit((0 until w).map(k =>
      allocCount > k.U && bankLocation(k)))
    val writeEnable = bankHit.asUInt.orR
    // Consecutive ranks cover every bank once, independent of this cycle's
    // allocation count. A late valid need only drive WE, not the RAM address.
    val writeRow = Mux1H((0 until w).map(k =>
      bankLocation(k) -> rowOf(allocRankIdx(k))))
    val writeData = Mux1H((0 until w).map(k =>
      bankLocation(k) -> allocPayloadByRank(k).asUInt))
    when(writeEnable) {
      payloadBanks(b).write(writeRow, writeData)
    }
  }

  // ---- CDB 写回标记完成 ----
  val cdbAccepted = Wire(Vec(numCdb, Bool()))
  for (c <- 0 until numCdb) {
    cdbAccepted(c) := io.cdb(c).valid && busyRead(io.cdb(c).robIdx)
  }
  val cdbDoneSetMask = (0 until numCdb).map(c =>
    Mux(cdbAccepted(c), UIntToOH(io.cdb(c).robIdx, n), 0.U(n.W))).reduce(_ | _)
  doneBits := (doneBits & ~allocStateMask) | cdbDoneSetMask
  for (c <- 0 until numCdb) {
    when(cdbAccepted(c) && io.cdb(c).exception.valid) {
      val idx = io.cdb(c).robIdx
      exceptionBits(idx) := true.B
      exceptionEcode(idx) := io.cdb(c).exception.ecode
      exceptionEsubcode(idx) := io.cdb(c).exception.esubcode
      exceptionBadv(idx) := io.cdb(c).exception.badv
      exceptionBadvValid(idx) := io.cdb(c).exception.badvValid
    }
  }

  val lqReleaseAccepted = io.lqRelease.valid
  val lqReleaseSetMask = Mux(io.lqRelease.valid,
    UIntToOH(io.lqRelease.bits, n), 0.U(n.W))

  // BRU 数量固定为 1，因此每拍至多一个分支 CDB。分支目标使用独立 bank，
  // 不会和主载荷的分配写端口发生冲突。
  val branchCdbValid = VecInit((0 until numCdb).map(c =>
    cdbAccepted(c) && io.cdb(c).isBranch))
  val branchWriteValid = branchCdbValid.asUInt.orR
  val branchWriteSel = PriorityEncoder(branchCdbValid)
  val branchWrite = io.cdb(branchWriteSel)
  val branchStateWriteMask = Mux(branchWriteValid,
    UIntToOH(branchWrite.robIdx, n), 0.U(n.W))
  val branchMispredictSetMask = Mux(branchWriteValid && branchWrite.mispredict,
    branchStateWriteMask, 0.U(n.W))
  mispredictBits :=
    (mispredictBits & ~(allocStateMask | branchStateWriteMask)) |
      branchMispredictSetMask
  val branchTakenSetMask = Mux(branchWriteValid && branchWrite.actualTaken,
    branchStateWriteMask, 0.U(n.W))
  takenBits :=
    (takenBits & ~(allocStateMask | branchStateWriteMask)) |
      branchTakenSetMask
  for (b <- 0 until numBanks) {
    when(branchWriteValid && bankOf(branchWrite.robIdx) === b.U) {
      targetBanks(b).write(rowOf(branchWrite.robIdx), branchWrite.targetPc)
    }
  }

  // ---- 多提交：从 head 起顺序，遇到不可提交条件截断 ----
  val cand = Wire(Vec(w, new RobEntry))
  val candIdx = RegInit(VecInit((0 until w).map(_.U(p.robIdxBits.W))))

  // head..head+w-1 恰好每个 bank 读一次。窗口跨 bank 行边界时，编号小于
  // headBank 的 bank 读取下一行；行地址自然按 bankDepth 回绕。
  val headBank = bankOf(head)
  def bankRowFor(index: UInt, bank: Int): UInt = {
    val row = rowOf(index)
    val next = (row + 1.U)(rowBits - 1, 0)
    Mux(bank.U < bankOf(index), next, row)
  }
  val bankReadRow = RegInit(VecInit(Seq.fill(numBanks)(0.U(rowBits.W))))
  val headBankOH = RegInit(1.U(numBanks.W))
  when(effectiveCount =/= 0.U) {
    assert(headBankOH === UIntToOH(headBank, numBanks))
    for (bank <- 0 until numBanks) { assert(bankReadRow(bank) === bankRowFor(head, bank)) }
  }
  val payloadBankRead = VecInit((0 until numBanks).map(b =>
    payloadBanks(b).read(bankReadRow(b))))
  val targetBankRead = VecInit((0 until numBanks).map(b =>
    targetBanks(b).read(bankReadRow(b))))

  for (k <- 0 until w) {
    when(effectiveCount =/= 0.U) { assert(candIdx(k) === wrap(head +& k.U)) }
    val candBankOH = VecInit((0 until numBanks).map(bank =>
      headBankOH((bank - k % numBanks + numBanks) % numBanks)))
    val payload = Mux1H(candBankOH, payloadBankRead).asTypeOf(new RobPayload)

    cand(k) := 0.U.asTypeOf(new RobEntry)
    cand(k).busy       := busyRead(candIdx(k))
    cand(k).done       := doneRead(candIdx(k))
    cand(k).pc         := payload.pc
    cand(k).inst       := payload.inst
    cand(k).ldst       := payload.ldst
    cand(k).prd        := payload.prd
    cand(k).prevPrd    := payload.prevPrd
    cand(k).writesReg  := payload.writesReg
    cand(k).isLoad     := payload.isLoad
    cand(k).isStore    := payload.isStore
    cand(k).isSystem   := payload.isSystem
    cand(k).isBranch   := branchRead(candIdx(k))
    cand(k).isConditional := payload.isConditional
    cand(k).isBackward := payload.isBackward
    cand(k).isCall     := payload.isCall
    cand(k).isRet      := payload.isRet
    cand(k).isIndirect := payload.isIndirect
    cand(k).bhtIdx     := payload.bhtIdx
    cand(k).predTaken  := payload.predTaken
    cand(k).predGhr    := payload.predGhr
    cand(k).predTageTable := payload.predTageTable
    cand(k).predTageGhr := payload.predTageGhr
    cand(k).predCtr    := payload.predCtr
    cand(k).mispredict := mispredictRead(candIdx(k))
    cand(k).targetPc   := Mux1H(candBankOH, targetBankRead)
    cand(k).actualTaken := takenRead(candIdx(k))
    cand(k).exception.valid := exceptionBits(candIdx(k))
    cand(k).exception.ecode := exceptionEcode(candIdx(k))
    cand(k).exception.esubcode := exceptionEsubcode(candIdx(k))
    cand(k).exception.badv := exceptionBadv(candIdx(k))
    cand(k).exception.badvValid := exceptionBadvValid(candIdx(k))
  }
  io.headPc := cand(0).pc
  io.headDone := cand(0).done
  io.headIsLoad := cand(0).isLoad
  io.headIsStore := cand(0).isStore
  io.headIsBranch := cand(0).isBranch

  val storeCommitPending = RegInit(false.B)
  val storeCommitAcked = RegInit(false.B)
  val storeCommitIdxReg = Reg(UInt(p.robIdxBits.W))
  val storeCommitIdxAt = VecInit((0 until w).map(k =>
    cand(k).isStore && candIdx(k) === storeCommitIdxReg))
  val pendingOrderedStoreAt = VecInit((0 until w).map(k =>
    storeCommitPending && storeCommitIdxAt(k)))

  private val storeCommitWindowIdxBits = p.storeCommitWindowIdxBits
  val storeWindowKnownAt = Wire(Vec(w, Bool()))
  val storeWindowCacheableAt = Wire(Vec(w, Bool()))
  val storeWindowDataReadyAt = Wire(Vec(w, Bool()))
  for (k <- 0 until w) {
    val delta = (candIdx(k) - io.storeWindowBase)(p.robIdxBits - 1, 0)
    val windowIdx = delta(storeCommitWindowIdxBits - 1, 0)
    val inWindow = delta < storeCommitWindowDepth.U
    storeWindowKnownAt(k) := inWindow && io.storeWindowValid(windowIdx)
    storeWindowCacheableAt(k) := inWindow &&
      io.storeWindowCacheable(windowIdx)
    storeWindowDataReadyAt(k) := inWindow &&
      io.storeWindowDataReady(windowIdx)
  }
  val cacheableStoreReady = VecInit((0 until w).map(k =>
    cand(k).isStore && storeWindowKnownAt(k) &&
      storeWindowCacheableAt(k) && storeWindowDataReadyAt(k)))
  val orderedStoreReady = VecInit((0 until w).map(k =>
    cand(k).isStore && storeWindowKnownAt(k) &&
      !storeWindowCacheableAt(k) && storeWindowDataReadyAt(k)))

  val cacheableLoadCompletingNow = VecInit((0 until w).map(k =>
    VecInit((0 until numCdb).map(c => io.cdb(c).valid &&
      io.cdb(c).isCacheableLoad && io.cdb(c).robIdx === candIdx(k))).asUInt.orR))
  val rawSlotReady = VecInit((0 until w).map(k =>
    (k.U < effectiveCount) && cand(k).busy && Mux(cand(k).exception.valid,
      cand(k).done, Mux(cand(k).isStore,
        (storeWindowKnownAt(k) && storeWindowDataReadyAt(k)) ||
          pendingOrderedStoreAt(k),
        cand(k).done || (cand(k).isLoad && cacheableLoadCompletingNow(k))))))
  val slotReady = Wire(Vec(w, Bool()))
  for (k <- 0 until w) {
    val olderReadyBranch = if (k == 0) false.B else
      VecInit((0 until k).map(j => rawSlotReady(j) && cand(j).isBranch)).asUInt.orR
    slotReady(k) := rawSlotReady(k) && !(cand(k).isBranch && olderReadyBranch)
  }

  val slotContinues = VecInit((0 until w).map(k =>
    slotReady(k) && !cand(k).isSystem && !cand(k).exception.valid &&
      (!cand(k).isStore || cacheableStoreReady(k))))
  val basePrefix = Wire(Vec(w, Bool()))
  for (k <- 0 until w) {
    basePrefix(k) := (if (k == 0) true.B
      else VecInit(slotContinues.take(k)).asUInt.andR)
  }
  val inCommitPrefix = VecInit((0 until w).map(k => basePrefix(k) && slotReady(k)))

  val cacheableStoresThrough = Wire(Vec(w, UInt(p.issueCountBits.W)))
  for (k <- 0 until w) {
    cacheableStoresThrough(k) := PopCount(cacheableStoreReady.take(k + 1))
  }
  val storeCapacityOk = VecInit((0 until w).map(k =>
    cacheableStoresThrough(k) <= io.storeCommitCapacity))

  // 系统指令提交后统一重取 PC+4，使地址模式和 ICache 维护立即生效。
  val flushAtK = VecInit((0 until w).map(k => inCommitPrefix(k) &&
    cand(k).isSystem && !cand(k).exception.valid))

  val orderedStoreCandidateVec = VecInit((0 until w).map(k =>
    inCommitPrefix(k) && orderedStoreReady(k) && storeCapacityOk(k)))
  val orderedStoreCandidate = orderedStoreCandidateVec.asUInt.orR
  val orderedStoreCandidateK = PriorityEncoder(orderedStoreCandidateVec)

  io.storeCommitValid := storeCommitPending
  io.storeCommitIdx   := storeCommitIdxReg
  io.storeCommitAcked := storeCommitAcked

  val sysCommitVec = VecInit((0 until w).map(k =>
    inCommitPrefix(k) && cand(k).isSystem &&
      !cand(k).exception.valid &&
      cacheableStoresThrough(k) === 0.U))
  val sysCommitCandidate = sysCommitVec.asUInt.orR
  val sysCommitK = PriorityEncoder(sysCommitVec)
  val sysCommitPending = RegInit(false.B)
  val sysCommitIdxReg = Reg(UInt(p.robIdxBits.W))
  when(io.recover.valid || recoverApplyPending) {
    sysCommitPending := false.B
  }.elsewhen(!sysCommitPending && sysCommitCandidate) {
    sysCommitPending := true.B
    sysCommitIdxReg := candIdx(sysCommitK)
  }.elsewhen(sysCommitPending && io.sysCommitDone) {
    sysCommitPending := false.B
  }
  io.sysCommitValid := sysCommitPending &&
    !io.recover.valid && !recoverApplyPending
  io.sysCommitIdx := sysCommitIdxReg

  // 实际提交：cacheable stores consume counted FIFO credits; ordered stores
  val commitEn = Wire(Vec(w, Bool()))
  for (k <- 0 until w) {
    val e = cand(k)
    val orderedStoreOk = storeCommitPending && !storeCommitAcked &&
      io.storeCommitDone &&
      storeCommitIdxReg === candIdx(k)
    val storeOk = !e.isStore || cacheableStoreReady(k) || orderedStoreOk
    val sysOk = !e.isSystem ||
      (cacheableStoresThrough(k) === 0.U && io.sysCommitDone)
    commitEn(k) := inCommitPrefix(k) && !e.exception.valid && storeCapacityOk(k) &&
      storeOk && sysOk && !io.recover.valid && !recoverApplyPending
  }

  val orderedStoreRetiredNow = VecInit((0 until w).map(k =>
    commitEn(k) && cand(k).isStore &&
      candIdx(k) === storeCommitIdxReg)).asUInt.orR
  when(!storeCommitPending && !storeCommitAcked &&
      !io.recover.valid && !recoverApplyPending &&
      orderedStoreCandidate) {
    storeCommitPending := true.B
    storeCommitIdxReg := candIdx(orderedStoreCandidateK)
  }.elsewhen(orderedStoreRetiredNow) {
    storeCommitAcked := true.B
  }.elsewhen(storeCommitAcked && !storeCommitIdxAt.asUInt.orR) {
    storeCommitPending := false.B
    storeCommitAcked := false.B
  }

  val trapCommitPending = RegInit(false.B)
  val trapCommitAcked = RegInit(false.B)
  val trapCommitReg = Reg(new TrapRequest)
  val headTrapCandidate = effectiveCount =/= 0.U && cand(0).busy &&
    cand(0).done && cand(0).exception.valid &&
    !io.recover.valid && !recoverApplyPending
  when(!headTrapCandidate) {
    trapCommitAcked := false.B
  }
  when(!trapCommitPending && !trapCommitAcked && headTrapCandidate) {
    trapCommitPending := true.B
    trapCommitReg.robIdx := candIdx(0)
    trapCommitReg.pc := cand(0).pc
    trapCommitReg.inst := cand(0).inst
    trapCommitReg.exception := cand(0).exception
  }.elsewhen(trapCommitPending && io.trapCommitDone) {
    trapCommitPending := false.B
    trapCommitAcked := true.B
  }
  io.trapCommitValid := trapCommitPending
  io.trapCommit := trapCommitReg

  val commitCount = PopCount(commitEn)
  // Retire is a contiguous prefix. Compute each possible head in parallel
  // with retirement, so the late commit bits only select a head value.
  val headStepOH = VecInit((0 to w).map(k => {
    val prefixReached = if (k == 0) true.B else commitEn(k - 1)
    val prefixEnds = if (k == w) true.B else !commitEn(k)
    prefixReached && prefixEnds
  }))
  val nextHead = Mux1H(headStepOH, (0 to w).map(k => wrap(head +& k.U)))
  assert(nextHead === wrap(head +& commitCount))
  val anyFlush = VecInit((0 until w).map(k => flushAtK(k) && commitEn(k))).asUInt.orR
  val flushK = PriorityEncoder(VecInit((0 until w).map(k => flushAtK(k) && commitEn(k))))

  // ---- 提交端口输出 ----
  for (k <- 0 until w) {
    val e = cand(k)
    io.commit(k).valid     := commitEn(k)
    io.commit(k).pc        := e.pc
    io.commit(k).inst      := e.inst
    io.commit(k).writesReg := e.writesReg
    io.commit(k).ldst      := e.ldst
    io.commit(k).prd       := e.prd
    io.commit(k).prevPrd   := e.prevPrd
    io.commit(k).robIdx    := candIdx(k)
    io.commit(k).isLoad    := e.isLoad
    val lqReleasedNow = lqReleasedRead(candIdx(k)) ||
      (lqReleaseAccepted && io.lqRelease.bits === candIdx(k))
    io.commit(k).retireLq  := e.isLoad && !lqReleasedNow
    io.commit(k).isStore   := e.isStore
    io.commit(k).isCacheableStore := e.isStore && cacheableStoreReady(k)
    io.commit(k).isSystem  := e.isSystem
    io.commit(k).isBranch  := e.isBranch
    io.commit(k).isConditional := e.isConditional
    io.commit(k).isBackward := e.isBackward
    io.commit(k).isCall    := e.isCall
    io.commit(k).isRet     := e.isRet
    io.commit(k).isIndirect := e.isIndirect
    io.commit(k).bhtIdx    := e.bhtIdx
    io.commit(k).predTaken := e.predTaken
    io.commit(k).predGhr   := e.predGhr
    io.commit(k).predTageTable := e.predTageTable
    io.commit(k).predTageGhr := e.predTageGhr
    io.commit(k).predCtr   := e.predCtr
    io.commit(k).actualTaken := e.actualTaken
    io.commit(k).targetPc  := e.targetPc
  }

  // ---- 冲刷 ----
  val flushPending = RegInit(false.B)
  val flushRobIdxReg = Reg(UInt(p.robIdxBits.W))
  val flushNewPcReg = Reg(UInt(p.xLen.W))
  val trapFlush = trapCommitPending && io.trapCommitDone
  flushPending := anyFlush || trapFlush
  when(trapFlush) {
    flushRobIdxReg := trapCommitReg.robIdx
    flushNewPcReg := io.trapCommitRestartPc
  }.elsewhen(anyFlush) {
    flushRobIdxReg := candIdx(flushK)
    flushNewPcReg := io.sysCommitRestartPc
  }

  // Payload preparation does not wait for the late retirement flush decision.
  // The pending trap owner is registered and excludes an ordinary system flush.
  val preparedFlushPc = Mux(trapCommitPending,
    io.trapCommitRestartPc, io.sysCommitRestartPc)
  val preparedFlushIdx = Mux(trapCommitPending, trapCommitReg.robIdx, sysCommitIdxReg)
  when(trapFlush || anyFlush) {
    assert(preparedFlushPc === Mux(trapFlush, io.trapCommitRestartPc, io.sysCommitRestartPc))
    assert(preparedFlushIdx === Mux(trapFlush, trapCommitReg.robIdx, candIdx(flushK)))
  }
  flushNewPcReg := preparedFlushPc
  flushRobIdxReg := preparedFlushIdx

  val doFlush = flushPending
  io.flush.valid  := flushPending
  io.flush.robIdx := flushRobIdxReg
  io.flush.newPc  := flushNewPcReg

  when(doFlush) {
    recoverApplyPending := false.B
  }.otherwise {
    recoverApplyPending := io.recover.valid
  }

  when(doFlush) {
    storeCommitPending := false.B
    storeCommitAcked := false.B
  }

  when(doFlush || io.recover.valid || recoverApplyPending) {
    stateAllocCount := 0.U
  }.otherwise {
    stateAllocCount := allocCount
    stateAllocTail := tail
    for (k <- 0 until w) {
      stateAllocBranch(k) := allocEntryByRank(k).isBranch
    }
  }

  val retireClearHead = Reg(UInt(p.robIdxBits.W))
  val retireClearCount = RegInit(0.U(p.issueCountBits.W))
  when(doFlush) {
    retireClearCount := 0.U
  }.otherwise {
    retireClearHead := head
    retireClearCount := commitCount
  }
  val commitBusyClearMask = (0 until w).map(k =>
    Mux(retireClearCount > k.U,
      UIntToOH(wrap(retireClearHead +& k.U), n), 0.U(n.W))).reduce(_ | _)
  val recoverKeepMask = VecInit((0 until n).map(i =>
    !RobOrder.isYounger(i.U, io.recover.bits, head))).asUInt
  val recoverKeepMaskReg = RegInit(0.U(n.W))
  val recoverTailReg = Reg(UInt(p.robIdxBits.W))
  val recoverCountReg = Reg(UInt(p.robCountBits.W))
  when(io.recover.valid) {
    recoverKeepMaskReg := recoverKeepMask
    recoverTailReg := wrap(io.recover.bits +& 1.U)
    recoverCountReg := ((io.recover.bits - head)(p.robIdxBits - 1, 0) +&
      1.U)
  }
  val busyAfterNormalUpdates = (busyBits | allocStateMask) & ~commitBusyClearMask
  val lqReleasedAfterNormalUpdates =
    ((lqReleasedBits & ~allocStateMask) | lqReleaseSetMask) &
      ~commitBusyClearMask
  busyBits := Mux(doFlush, 0.U(n.W),
    Mux(recoverApplyPending, busyAfterNormalUpdates & recoverKeepMaskReg,
      busyAfterNormalUpdates))
  lqReleasedBits := Mux(doFlush, 0.U(n.W),
    Mux(recoverApplyPending,
      lqReleasedAfterNormalUpdates & recoverKeepMaskReg,
      lqReleasedAfterNormalUpdates))
  
  // ---- 指针 / count 更新 ----
  val countAfterCycle = Mux(doFlush, 0.U,
    Mux(io.recover.valid, effectiveCount,
      Mux(recoverApplyPending, recoverCountReg,
        count + allocCount - retirePending)))
  allocationBaseCredits := Mux(countAfterCycle <= (n - 2 * w).U,
    (2 * w).U, (n.U - countAfterCycle)(creditBits - 1, 0))
  when(doFlush) {
    head := 0.U
    tail := 0.U
    count := 0.U
    retirePending := 0.U
  }.elsewhen(io.recover.valid) {
    count := effectiveCount
    retirePending := 0.U
  }.elsewhen(recoverApplyPending) {
    tail := recoverTailReg
    count := recoverCountReg
    retirePending := 0.U
  }.otherwise {
    head := nextHead
    tail := wrap(tail +& allocCount)
    count := count + allocCount - retirePending
    retirePending := commitCount
  }

  when(doFlush) {
    bankReadRow := VecInit(Seq.fill(numBanks)(0.U(rowBits.W)))
    headBankOH := 1.U
    candIdx := VecInit((0 until w).map(_.U(p.robIdxBits.W)))
  }.elsewhen(!io.recover.valid && !recoverApplyPending) {
    for (k <- 0 until w) {
      candIdx(k) := Mux1H(headStepOH,
        (0 to w).map(step => wrap(head +& (step + k).U)))
    }
    for (bank <- 0 until numBanks) {
      bankReadRow(bank) := Mux1H(headStepOH,
        (0 to w).map(step => bankRowFor(wrap(head +& step.U), bank)))
    }
    val rotated = (0 to w).map { step =>
      VecInit((0 until numBanks).map(bank =>
        headBankOH((bank - step % numBanks + numBanks) % numBanks))).asUInt
    }
    headBankOH := Mux1H(headStepOH, rotated)
  }

  // ---- 分支屏障 ----
  val unresolvedComb = VecInit((0 until n).map(i =>
    busyRead(i) && branchRead(i) && !doneRead(i))).asUInt.orR
  val unresolvedReg = RegInit(false.B)
  when(doFlush) {
    unresolvedReg := false.B
  }.otherwise {
    unresolvedReg := unresolvedComb
  }
  io.hasUnresolvedBranch := unresolvedReg
}
