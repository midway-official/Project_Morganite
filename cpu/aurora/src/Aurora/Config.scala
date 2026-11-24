package aurora

import chisel3._
import chisel3.util._

/** 左闭右开的物理地址区间 `[base, limit)`。 */
case class AddressRegion(base: BigInt, limit: BigInt) {
  require(base >= 0 && base < limit, "地址区间必须非空且 base < limit")
  require(limit <= (BigInt(1) << AuroraConfig.PhysicalAddressBits),
    s"地址区间必须位于 ${AuroraConfig.PhysicalAddressBits} 位物理地址空间")
}

/** 全局参数化配置。构造参数按其主要使用模块分组，跨模块参数在类体中派生。 */
case class AuroraConfig(
    // 1. 流水宽度与执行单元数量。
    coreWidth: Int = 4,
    numAlu: Int = 2,
    numDiv: Int = 1,

    // 2. 乱序后端容量：物理寄存器、ROB、分支恢复点。
    numPhysRegs: Int = 64,
    numRobEntries: Int = 64,
    numCheckpoints: Int = 16,

    // 3. 前端：复位入口、指令缓冲与 ICache 几何结构。
    resetPc: BigInt = 0x1c000000L,
    instructionBufferDepth: Int = 256,
    icacheSets: Int = 1024,
    icacheWays: Int = 1,
    icacheLineBytes: Int = 16,

    // 4. 分支预测：L1、L2、TAGE 表容量，再列训练/选择策略。
    l1BtbEntries: Int = 64,
    l1BtbWays: Int = 2,
    l1BhtEntries: Int = 32,
    l2BtbEntries: Int = 32,
    l2BhtEntries: Int = 128,
    l2GhrBits: Int = 32,
    l2RasDepth: Int = 8,
    l2LoopPredEntries: Int = 8,
    l2LoopPredCountBits: Int = 6,
    tageTables: Int = 3,
    tageEntries: Int = 512,
    tageTagBits: Int = 8,
    tageBimodalEntries: Int = 1024,
    tageHistoryLens: Seq[Int] = Seq(4, 12, 32),
    tageFoldHistory: Boolean = true,
    tageTrainCurrentCounter: Boolean = true,
    tageTrainBaseAlways: Boolean = true,
    loopRespectStrongTage: Boolean = true,
    loopRespectStrongNotTaken: Boolean = false,

    // 5. 发射队列：Depth 为项数，EnqWidth 为每拍入队宽度。
    aluIqDepth: Int = 8,
    bruIqDepth: Int = 8,
    divIqDepth: Int = 4,
    lsuIqDepth: Int = 8, // MemIQ
    aluIqEnqWidth: Int = 2,
    bruIqEnqWidth: Int = 1,
    divIqEnqWidth: Int = 1,
    iqOverrides: Map[IqTarget, GenIqParams] = Map.empty,

    // 6. LSU：投机 LQ/SQ、地址计算缓冲、已提交 store 缓冲。
    lqDepth: Int = 16,
    sqDepth: Int = 8,
    lsuAguQueueDepth: Int = 2,
    committedStoreBufferDepth: Int = 4, // CSB
    storeCommitWindowGroups: Int = 2, // ROB store 提交预观察窗口，以 commitWidth 为一组

    // 7. DCache：几何结构、未完成 miss、fill/store/writeback 容量。
    dcacheSets: Int = 256, // 容量 = Sets × Ways × LineBytes = 64 KiB
    dcacheWays: Int = 4,
    dcacheLineBytes: Int = 64,
    dcacheMshrEntries: Int = 4,
    dcacheFillContextDepth: Int = 2,
    dcacheStoreUpdateQueueDepth: Int = 8,
    dcacheWritebackQueueDepth: Int = 2,

    // 8. AXI：MaxOutstanding 为未完成事务上限；QueueDepth 为内部缓冲项数。
    axiLsuReadMaxOutstanding: Int = 2,
    axiWriteMaxOutstanding: Int = 2,
    axiReadRequestQueueDepth: Int = 4, // 尚未完成 AR 握手的请求描述
    axiWriteDataQueueDepth: Int = 4, // 单位为 beat，不是 cache line 或事务
    axiReadResponseQueueDepth: Int = 1, // 单位为返回 beat；1 保留原寄存路径

    // 9. 地址翻译与设备物理地址区间。
    tlbEntries: Int = 16,
    peripheralRegions: Seq[AddressRegion] = Seq(
      AddressRegion(BigInt("1f000000", 16), BigInt("1f100000", 16)),
      AddressRegion(BigInt("1faf0000", 16), BigInt("1fb00000", 16)),
      AddressRegion(BigInt("1fd00000", 16), BigInt("1fd10000", 16)),
      AddressRegion(BigInt("1fe00000", 16), BigInt("1fe10000", 16))
    ),

    // 10. 执行旁路策略。
    allowAluMultiplyForward: Boolean = false,
    allowBruSimpleForward: Boolean = true,

    // 11. 独立观测开关：性能分析、NEMU 架构差分。
    enablePerfEvents: Boolean = true,
    enableDifftest: Boolean = true
) {
  val xLen: Int = AuroraConfig.XLen
  val numArchRegs: Int = AuroraConfig.NumArchRegs
  val bitsPerByte: Int = AuroraConfig.BitsPerByte
  val instructionBytes: Int = xLen / bitsPerByte
  val wordBytes: Int = instructionBytes
  val shiftAmountBits: Int = log2Ceil(xLen)

  val fetchWidth: Int = coreWidth
  val issueWidth: Int = coreWidth
  val commitWidth: Int = coreWidth
  val numSourceOperands: Int = 2
  val numBru: Int = 1
  val numLsu: Int = 1
  val numCdb: Int = numAlu + numBru + numLsu + numDiv
  val cdbDataCopies: Int = numAlu + numDiv + 3 // ALU IQs, DIV IQs, MemIQ, branch IQ, SQ
  val bruCdbLane: Int = numAlu
  val lsuCdbLane: Int = numAlu + 1
  val divCdbBase: Int = numAlu + 2
  val divCdbLanes: Seq[Int] = (0 until numDiv).map(divCdbBase + _)
  val numPrfReadPorts: Int = numSourceOperands * coreWidth

  require(coreWidth >= 2 && isPow2(coreWidth),
    "coreWidth 必须是不小于 2 的 2 的幂")
  require(numAlu >= 1, "numAlu 必须至少为 1")
  require(numDiv >= 1, "numDiv 必须至少为 1")
  require(numPhysRegs > numArchRegs && isPow2(numPhysRegs),
    "numPhysRegs 必须是大于架构寄存器数量的 2 的幂")

  val issueCountBits: Int = log2Ceil(issueWidth + 1)
  val aluIdxBits: Int = log2Ceil(numAlu max 2)
  val divIdxBits: Int = log2Ceil(numDiv max 2)
  val totalAluCreditBits: Int = log2Ceil(numAlu * issueWidth + 1)
  val archRegBits: Int = log2Ceil(numArchRegs)
  val physRegBits: Int = log2Ceil(numPhysRegs)
  val freeListCapacity: Int = numPhysRegs - numArchRegs
  val freeListCountBits: Int = log2Ceil(freeListCapacity + 1)

  val robBanks: Int = coreWidth
  require(numRobEntries >= 2 && isPow2(numRobEntries),
    "numRobEntries 必须是不小于 2 的 2 的幂")
  require(numRobEntries % robBanks == 0,
    "numRobEntries 必须能被 robBanks(=coreWidth) 整除")
  require(numCheckpoints >= 1 && numCheckpoints <= numRobEntries,
    "numCheckpoints 必须在 1 到 numRobEntries 之间")
  val robBankDepth: Int = numRobEntries / robBanks
  require(robBankDepth >= 2, "每个 ROB bank 深度必须至少为 2")

  val robIdxBits: Int = log2Ceil(numRobEntries)
  val robCountBits: Int = log2Ceil(numRobEntries + 1)
  val robBankBits: Int = log2Ceil(robBanks)
  val robRowBits: Int = log2Ceil(robBankDepth)
  val checkpointIdxBits: Int = log2Ceil(numCheckpoints max 2)

  require(aluIqDepth >= aluIqEnqWidth && isPow2(aluIqDepth),
    "ALU IQ depth must cover its enqueue width and be a power of two")
  require(bruIqDepth >= bruIqEnqWidth, "BRU IQ depth must cover its enqueue width")
  require(lsuIqDepth >= issueWidth, "MemIQ depth must cover dispatch width")
  require(divIqDepth >= divIqEnqWidth, "DIV IQ depth must cover its enqueue width")
  for (width <- Seq(aluIqEnqWidth, bruIqEnqWidth, divIqEnqWidth)) {
    require(width >= 1 && width <= coreWidth, "invalid non-memory IQ enqueue width")
  }
  iqOverrides.keys.foreach {
    case IqTarget.Alu(i) => require(i >= 0 && i < numAlu, "unknown ALU IQ override")
    case IqTarget.Branch => ()
    case IqTarget.Divide(i) => require(i >= 0 && i < numDiv, "unknown DIV IQ override")
  }
  val aluIqParams: Seq[GenIqParams] = (0 until numAlu).map(a =>
    iqOverrides.getOrElse(IqTarget.Alu(a), GenIqParams.alu(this)))
  val bruIqParams: GenIqParams =
    iqOverrides.getOrElse(IqTarget.Branch, GenIqParams.branch(this))
  val divIqParams: Seq[GenIqParams] = (0 until numDiv).map(d =>
    iqOverrides.getOrElse(IqTarget.Divide(d), GenIqParams.divide(this)))
  for ((q, depth) <- aluIqParams.map(_ -> aluIqDepth) ++
      Seq(bruIqParams -> bruIqDepth) ++ divIqParams.map(_ -> divIqDepth)) {
    q.validate(this)
    require(q.depth == depth, "Core IQ override must preserve the configured queue depth")
  }
  val aluIqCountBits: Int = log2Ceil(aluIqDepth + 1)
  val bruIqCountBits: Int = log2Ceil(bruIqDepth + 1)
  val lsuIqCountBits: Int = log2Ceil(lsuIqDepth + 1)
  val divIqCountBits: Int = log2Ceil(divIqDepth + 1)

  // LSU 容量约束与索引位宽。
  require(lqDepth >= issueWidth && (isPow2(lqDepth) || lqDepth == 12),
    "lqDepth 必须不小于 coreWidth，且为 2 的幂或 12")
  require(sqDepth >= issueWidth && (isPow2(sqDepth) || sqDepth == 12),
    "sqDepth 必须不小于 coreWidth，且为 2 的幂或 12")
  require(committedStoreBufferDepth >= commitWidth &&
      isPow2(committedStoreBufferDepth),
    "committedStoreBufferDepth 必须是不小于 coreWidth 的 2 的幂")
  require(lsuAguQueueDepth >= 2 && isPow2(lsuAguQueueDepth),
    "lsuAguQueueDepth must be a power of two >= 2")
  require(storeCommitWindowGroups >= 2 && isPow2(storeCommitWindowGroups) &&
      storeCommitWindowGroups * commitWidth <= numRobEntries,
    "storeCommitWindowGroups must be a power of two >= 2 and fit in the ROB")
  val lsqIdxBits: Int = log2Ceil(lqDepth max sqDepth)
  val lqCountBits: Int = log2Ceil(lqDepth + 1)
  val sqCountBits: Int = log2Ceil(sqDepth + 1)
  val storeCommitWindowDepth: Int = storeCommitWindowGroups * commitWidth
  val storeCommitWindowIdxBits: Int = log2Ceil(storeCommitWindowDepth)
  val committedStoreBufferPtrBits: Int =
    log2Ceil(committedStoreBufferDepth)
  val committedStoreBufferCountBits: Int =
    log2Ceil(committedStoreBufferDepth + 1)

  // DCache 几何结构、内部上下文及其位宽。
  require(dcacheSets >= 2 && isPow2(dcacheSets),
    "dcacheSets 必须是不小于 2 的 2 的幂")
  require(dcacheWays >= 2 && isPow2(dcacheWays),
    "dcacheWays 必须是不小于 2 的 2 的幂")
  require(dcacheLineBytes >= wordBytes && isPow2(dcacheLineBytes),
    "dcacheLineBytes 必须是不小于一个字的 2 的幂")
  require(dcacheLineBytes <= 16 * wordBytes,
    "dcacheLineBytes 不能超过 AXI WRAP burst 的 16 个 beat")
  require(dcacheMshrEntries >= 1 && dcacheMshrEntries <= lqDepth &&
      isPow2(dcacheMshrEntries),
    "dcacheMshrEntries 必须是 1..lqDepth 范围内的 2 的幂")

  require(dcacheStoreUpdateQueueDepth >= 2 && isPow2(dcacheStoreUpdateQueueDepth),
    "dcacheStoreUpdateQueueDepth 必须是不小于 2 的 2 的幂")
  for ((name, depth) <- Seq("dcacheFillContextDepth" -> dcacheFillContextDepth,
                           "dcacheWritebackQueueDepth" -> dcacheWritebackQueueDepth)) {
    require(depth >= 2 && isPow2(depth), s"$name must be a power of two >= 2")
  }
  val dcacheStoreUpdateQueueCountBits: Int = log2Ceil(dcacheStoreUpdateQueueDepth + 1)
  val dcacheFillContextPtrBits: Int = log2Ceil(dcacheFillContextDepth)
  val dcacheFillContextCountBits: Int = log2Ceil(dcacheFillContextDepth + 1)
  val dcacheWritebackQueuePtrBits: Int = log2Ceil(dcacheWritebackQueueDepth)
  val dcacheWritebackQueueCountBits: Int = log2Ceil(dcacheWritebackQueueDepth + 1)
  val dcacheLineWords: Int = dcacheLineBytes / wordBytes
  val dcacheBytes: Int = dcacheSets * dcacheWays * dcacheLineBytes
  val dcacheWords: Int = dcacheBytes / wordBytes
  val dcacheWordOffsetBits: Int = log2Ceil(wordBytes)
  val dcacheOffsetBits: Int = log2Ceil(dcacheLineBytes)
  val dcacheIndexBits: Int = log2Ceil(dcacheSets)
  val dcacheWayBits: Int = log2Ceil(dcacheWays)
  val dcacheLineWordBits: Int = log2Ceil(dcacheLineWords max 2)
  val dcacheDataIndexBits: Int = log2Ceil(dcacheSets * dcacheLineWords)
  val dcacheTagBits: Int = xLen - dcacheOffsetBits - dcacheIndexBits
  val dcacheMshrIdxBits: Int = log2Ceil(dcacheMshrEntries max 2)
  val dcacheMshrCountBits: Int = log2Ceil(dcacheMshrEntries + 1)
  require(dcacheOffsetBits + dcacheIndexBits < xLen,
    "D-Cache 索引必须保留至少一位 tag")

  val dCacheCfgWord: BigInt = (BigInt(dcacheOffsetBits) << 24) |
    (BigInt(dcacheIndexBits) << 16) | BigInt(dcacheWays - 1)

  // AXI 事务额度与弹性缓冲；通用 Queue 允许非二次幂深度。
  for ((name, depth) <- Seq("axiLsuReadMaxOutstanding" -> axiLsuReadMaxOutstanding,
      "axiWriteMaxOutstanding" -> axiWriteMaxOutstanding,
      "axiReadRequestQueueDepth" -> axiReadRequestQueueDepth,
      "axiWriteDataQueueDepth" -> axiWriteDataQueueDepth,
      "axiReadResponseQueueDepth" -> axiReadResponseQueueDepth)) {
    require(depth >= 1, s"$name must be positive")
  }

  // TLB 容量与索引。
  require(tlbEntries >= 4 && isPow2(tlbEntries),
    "TLB 项数必须是不小于 4 的 2 的幂")
  val tlbIndexBits: Int = log2Ceil(tlbEntries)

  // 指令缓冲与 ICache。
  require(instructionBufferDepth >= 2 && isPow2(instructionBufferDepth),
    "instructionBufferDepth 必须是不小于 2 的 2 的幂")
  require(icacheSets >= 2 && isPow2(icacheSets),
    "icacheSets 必须是不小于 2 的 2 的幂")
  require(icacheWays == 1, "当前 I-Cache 实现仅支持直接映射")
  require(icacheLineBytes >= instructionBytes && isPow2(icacheLineBytes),
    "icacheLineBytes 必须是不小于一条指令的 2 的幂")
  require(icacheLineBytes <= 256 * instructionBytes,
    "icacheLineBytes 不能超过 AXI INCR burst 的 256 个 beat")

  require(fetchWidth <= icacheLineBytes / instructionBytes,
    "coreWidth 不能超过一条 I-Cache line 可容纳的指令数")
  val fetchBytes: Int = fetchWidth * instructionBytes
  require(icacheLineBytes % fetchBytes == 0,
    "icacheLineBytes 必须是 fetchBytes 的整数倍，保持配置与对齐 bank 的整除关系")
  require(resetPc >= 0 && resetPc < (BigInt(1) << xLen),
    "resetPc 必须位于物理地址空间")
  require(resetPc % instructionBytes == 0, "resetPc 必须按指令宽度对齐")

  val instructionBufferPtrBits: Int = log2Ceil(instructionBufferDepth)
  val instructionBufferCountBits: Int = log2Ceil(instructionBufferDepth + 1)
  val slotBits: Int = log2Ceil(fetchWidth)
  val groupOffsetBits: Int = log2Ceil(fetchBytes)
  val icacheLineWords: Int = icacheLineBytes / instructionBytes
  val icacheOffsetBits: Int = log2Ceil(icacheLineBytes)
  val icacheIndexBits: Int = log2Ceil(icacheSets)
  require(icacheOffsetBits + icacheIndexBits < xLen,
    "I-Cache 索引必须保留至少一位 tag")
  val icacheTagBits: Int = xLen - icacheOffsetBits - icacheIndexBits
  val iCacheCfgWord: BigInt = (BigInt(icacheOffsetBits) << 24) |
    (BigInt(icacheIndexBits) << 16) | BigInt(icacheWays - 1)

  // 分支预测器容量约束与派生索引。
  require(l1BtbEntries >= 2 && isPow2(l1BtbEntries),
    "l1BtbEntries 必须是不小于 2 的 2 的幂")
  require(l1BtbWays >= 2 && isPow2(l1BtbWays),
    "l1BtbWays 必须是不小于 2 的 2 的幂，一级预测器不允许关闭")
  require(l1BtbEntries >= 2 * l1BtbWays &&
      l1BtbEntries % l1BtbWays == 0,
    "l1BtbEntries 必须至少包含两组并能被 l1BtbWays 整除")
  require(l1BhtEntries >= 2 && isPow2(l1BhtEntries),
    "l1BhtEntries 必须是不小于 2 的 2 的幂")
  val l1BtbSets: Int = l1BtbEntries / l1BtbWays
  val l1BtbIdxBits: Int = log2Ceil(l1BtbSets)
  val l1BtbWayBits: Int = log2Ceil(l1BtbWays)
  val l1BhtIdxBits: Int = log2Ceil(l1BhtEntries)
  val l1BtbTagBits: Int = xLen - groupOffsetBits - l1BtbIdxBits
  require(l1BtbTagBits >= 1, "L1 BTB 索引必须保留至少一位 tag")
  require(l1BhtIdxBits + 2 <= xLen,
    "L1 BHT 索引不能超过 PC 字地址位宽")

  require(tageTables >= 1 && tageTables <= 7, "tageTables 必须在 1..7")
  require(isPow2(tageEntries) && tageEntries >= 2 * fetchWidth,
    "tageEntries 必须是 2 的幂且至少 2*fetchWidth")
  require(tageTagBits >= 4 && tageTagBits + log2Ceil(tageEntries) <= xLen,
    "tage tag/index 位宽非法")
  require(tageHistoryLens.length >= tageTables &&
    tageHistoryLens.take(tageTables).sliding(2).forall(w => w(0) < w(1)),
    "tageHistoryLens 必须严格递增且不少于表数")
  require(tageHistoryLens.take(tageTables).forall(h => h >= 1),
    "TAGE 历史长度必须至少为 1")
  require(isPow2(tageBimodalEntries) && tageBimodalEntries >= 2 * fetchWidth,
    "tageBimodalEntries 必须是 2 的幂")
  val tageTableBits: Int = log2Ceil(tageTables + 1)
  val tageCounterBits: Int = 2
  val btbEntries: Int = l2BtbEntries
  val bhtEntries: Int = l2BhtEntries
  val ghrBits: Int = l2GhrBits
  val rasDepth: Int = l2RasDepth
  val loopPredEntries: Int = l2LoopPredEntries
  val loopPredCountBits: Int = l2LoopPredCountBits
  require(btbEntries >= 2 && isPow2(btbEntries),
    "l2BtbEntries 必须是不小于 2 的 2 的幂")
  require(bhtEntries >= 2 && isPow2(bhtEntries),
    "l2BhtEntries 必须是不小于 2 的 2 的幂")
  require(ghrBits >= 1 && ghrBits <= xLen,
    "l2GhrBits 必须位于 1..xLen")
  require(rasDepth >= 1, "l2RasDepth 必须至少为 1")
  require(loopPredEntries >= 2 && isPow2(loopPredEntries),
    "l2LoopPredEntries 必须是不小于 2 的 2 的幂")
  require(loopPredCountBits >= 2, "l2LoopPredCountBits 必须至少为 2")
  val btbIdxBits: Int = log2Ceil(btbEntries)
  val bhtIdxBits: Int = log2Ceil(bhtEntries)
  val loopPredIdxBits: Int = log2Ceil(loopPredEntries)
  require(btbIdxBits + 2 < xLen, "BTB 索引必须保留至少一位 tag")
  require(bhtIdxBits + 2 <= xLen, "BHT 索引不能超过 PC 字地址位宽")
  require(loopPredIdxBits + 2 < xLen,
    "loop predictor 索引必须保留至少一位 tag")
  require(2 * loopPredIdxBits + 2 <= xLen,
    "loop predictor 折叠索引不能超过 PC 字地址位宽")
  val btbTagBits: Int = xLen - btbIdxBits - 2
  val loopPredTagBits: Int = xLen - loopPredIdxBits - 2
  val rasPtrBits: Int = log2Ceil(rasDepth + 1)

  val strbBits: Int = wordBytes
  val boffBits: Int = log2Ceil(strbBits)
}

/** 物理地址属性判定的唯一实现位置。 */
object MemoryAddressing {
  val MatCoherentCached = 1

  def isPeripheral(addr: UInt)(implicit p: AuroraConfig): Bool = {
    if (p.peripheralRegions.isEmpty) {
      false.B
    } else {
      VecInit(p.peripheralRegions.map { region =>
        addr >= region.base.U(p.xLen.W) && addr < region.limit.U(p.xLen.W)
      }).asUInt.orR
    }
  }

  /** 访存块是否与任一外设地址区间重叠。 */
  def overlapsPeripheral(base: UInt, bytes: Int)(implicit p: AuroraConfig): Bool = {
    require(bytes > 0 && isPow2(bytes), "访存块大小必须是正的 2 次幂")
    if (p.peripheralRegions.isEmpty) {
      false.B
    } else {
      val wideBase = Cat(0.U(1.W), base)
      val wideLimit = wideBase + bytes.U((p.xLen + 1).W)
      VecInit(p.peripheralRegions.map { region =>
        wideBase < region.limit.U((p.xLen + 1).W) &&
          wideLimit > region.base.U((p.xLen + 1).W)
      }).asUInt.orR
    }
  }

  /** 单个地址是否可缓存。 */
  def isCacheable(addr: UInt, mat: UInt)(implicit p: AuroraConfig): Bool =
    mat === MatCoherentCached.U && !isPeripheral(addr)

  /** 整条 Cache line 是否可缓存；只要与外设区间重叠就按不可缓存处理。 */
  def overlapsPeripheralLine(addr: UInt, lineBytes: Int)(implicit p: AuroraConfig): Bool = {
    require(lineBytes > 0 && isPow2(lineBytes), "Cache line 大小必须是正的 2 次幂")
    val offsetBits = log2Ceil(lineBytes)
    val lineIndex = addr(p.xLen - 1, offsetBits)
    // [lineBase, lineEnd) overlaps a region exactly when its index is
    // between the region's rounded-down base and rounded-up limit. No
    // runtime address addition is needed, including at the top of memory.
    p.peripheralRegions.map { region =>
      lineIndex >= (region.base >> offsetBits).U &&
        lineIndex < ((region.limit + lineBytes - 1) >> offsetBits).U
    }.foldLeft(false.B)(_ || _)
  }
  def isCacheableLine(addr: UInt, mat: UInt, lineBytes: Int)(implicit p: AuroraConfig): Bool =
    mat === MatCoherentCached.U && !overlapsPeripheralLine(addr, lineBytes)
}

object AuroraConfig {
  final val XLen = 32
  final val PhysicalAddressBits = XLen
  final val NumArchRegs = 32
  final val BitsPerByte = 8

  def default: AuroraConfig = AuroraConfig()
}
