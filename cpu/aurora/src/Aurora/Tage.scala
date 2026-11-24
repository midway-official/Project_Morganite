package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** Combinational hash of an already available history snapshot.
  * Index bank bits stay unchanged, so a consecutive window still has one read
  * per bank. Only the row bits fold older history; the tag uses a different
  * chunk rotation. No state or extra query edge is introduced here.
  */
object TageHistoryHash {
  private def fold(history: UInt, length: Int, width: Int,
                   offset: Int, rotation: Int): UInt = {
    require(width >= 1 && length >= 1 && offset >= 0)
    VecInit((0 until width).map { output =>
      (offset until length).filter { bit =>
        val relative = bit - offset
        (relative + (relative / width) * rotation) % width == output
      }.map(history(_)).foldLeft(false.B)(_ ^ _)
    }).asUInt
  }

  def indexHistory(history: UInt, length: Int)(implicit p: AuroraConfig): UInt = {
    val mask = ((BigInt(1) << length) - 1).U(p.ghrBits.W)
    val masked = history & mask
    if (p.tageFoldHistory) {
      val bankBits = log2Ceil(p.fetchWidth)
      val rowBits = log2Ceil(p.tageEntries) - bankBits
      Cat(fold(masked, length, rowBits, bankBits, 1),
        masked.pad(bankBits)(bankBits - 1, 0))
    } else masked
  }

  def index(pc: UInt, history: UInt, length: Int)(implicit p: AuroraConfig): UInt =
    ((pc >> 2) ^ indexHistory(history, length))(log2Ceil(p.tageEntries) - 1, 0)

  def tag(pc: UInt, history: UInt, length: Int)(implicit p: AuroraConfig): UInt = {
    val mask = ((BigInt(1) << length) - 1).U(p.ghrBits.W)
    val masked = history & mask
    val word = pc >> 2
    val raw = if (p.tageFoldHistory) {
      (word ^ (word >> 7) ^ fold(masked, length, p.tageTagBits, 0, 3))(
        p.tageTagBits - 1, 0)
    } else {
      val mixed = word ^ masked ^ (masked << 3)
      (mixed ^ (mixed >> 7))(p.tageTagBits - 1, 0)
    }
    Mux(raw === 0.U, 1.U, raw)
  }
}

/** 参数化 TAGE 方向预测器（无 SC，作为 F2 唯一方向预测器）。
  *
  * 每个 tagged 表按索引低位分为 w 个 bank，连续窗口每个 bank 只读一次；
  * 训练在提交级按已提交分支的 PC/预测时 GHR 重建索引，写入对应 bank。
  * tagged 表在 F1 内选择提供者；T0 回退与 slot 映射在寄存后对齐，背压时保持。
  */
class TageDirectionPredictor(implicit p: AuroraConfig) extends HistorySelectedTage(1)

/** One logical table image; choose precomputed bank addresses and tags before
  * its asynchronous read. The query/response edge and training policy remain
  * those of the scalar predictor, including same-address training collisions.
  */
class HistorySelectedTage(choices: Int, withFallback: Boolean = false,
                          parallelQueries: Boolean = false)(implicit p: AuroraConfig) extends Module {
  require(choices >= 1)
  private val w = p.fetchWidth
  private val tables = p.tageTables
  private val entries = p.tageEntries
  private val tagBits = p.tageTagBits
  private val ctrBits = p.tageCounterBits
  private val lens = p.tageHistoryLens.take(tables).map(h => math.min(h, p.ghrBits))
  private val idxBits = log2Ceil(entries)
  private val entryBits = tagBits + ctrBits
  private val t0Entries = p.tageBimodalEntries
  private val t0IdxBits = log2Ceil(t0Entries)

  val io = IO(new Bundle {
    val lookupPc = Input(UInt(p.xLen.W))
    val lookupHistory = Input(UInt(p.ghrBits.W))
    val historyChoices = if (choices > 1) Some(Input(Vec(choices, UInt(p.ghrBits.W)))) else None
    val historySelect = if (choices > 1) Some(Input(UInt(choices.W))) else None
    val lookupEnable = Input(Bool())
    val lookupFallbackTaken = if (withFallback) Some(Input(Vec(w, Bool()))) else None
    val predWithFallback = if (withFallback) Some(Output(Vec(w, Bool()))) else None
    val predTaken = Output(Vec(w, Bool()))
    val predValid = Output(Vec(w, Bool()))
    val predTable = Output(Vec(w, UInt(p.tageTableBits.W)))
    val predCtr = Output(Vec(w, UInt(ctrBits.W)))
    val predStrongTagged = Output(Vec(w, Bool()))
    val predStrongTaken = Output(Vec(w, Bool()))
    val update = Input(new BpuUpdate)
    val flush = Input(Bool())
  })

  // 连续 w 个 word 的索引低位覆盖全部 bank；历史 XOR 只改变 bank 排列。
  // 每个 bank 保留原逻辑表的 1/w 容量，所有 slot 共用一份完整表。
  private val banks = w
  private val bankBits = log2Ceil(w)
  private val tagged = Seq.fill(tables)(Seq.fill(banks)(
    Mem(entries / banks, UInt(entryBits.W))))
  private val t0 = Seq.fill(banks)(Mem(t0Entries / banks, UInt(ctrBits.W)))
  private val t0Valid = Seq.fill(banks)(Mem(t0Entries / banks, Bool()))
  // LUTRAM（distributed）异步读：tag 比较与最长匹配在 F1 内完成并寄存，
  // 使 F2 只看到已寄存的方向/提供者，切断 RAMB 输出到 pc 的组合路径。
  for (m <- tagged.flatten ++ t0 ++ t0Valid) {
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(m.toTarget, "ram_style = \"distributed\"")
    })
  }

  private def indexOf(pc: UInt, hist: UInt, len: Int): UInt = {
    TageHistoryHash.index(pc, hist, len)
  }
  private def tagOf(pc: UInt, hist: UInt, len: Int): UInt = {
    TageHistoryHash.tag(pc, hist, len)
  }

  private val startBank = io.lookupPc(bankBits + 1, 2)
  private def historyBank(history: UInt): UInt =
    history.pad(bankBits)(bankBits - 1, 0)

  private val histories = if (choices == 1) Seq(io.lookupHistory)
    else io.historyChoices.get.toSeq
  private def selectHistory[T <: Data](values: Seq[T]): T =
    if (values.size == 1) values.head else Mux1H(io.historySelect.get, VecInit(values))
  private def tableHistory(table: Int): Seq[UInt] =
    histories.map(history => TageHistoryHash.indexHistory(history, lens(table)))

  // PC 的行进位先并行算好；晚到的历史低位只选择两条已完成的行地址，
  // 不再经过“history XOR -> 比较 -> 行加法 -> XOR”的串行链。
  private def readBankRows[T <: Data](memories: Seq[Mem[T]], candidates: Seq[UInt],
                                     depth: Int): Vec[T] = {
    val rowBits = log2Ceil(depth) - bankBits
    val startRow = io.lookupPc(log2Ceil(depth) + 1, bankBits + 2)
    val nextRow = (startRow + 1.U)(rowBits - 1, 0)
    VecInit(memories.zipWithIndex.map { case (memory, bank) =>
      val rows = candidates.map { history =>
        val logicalBank = bank.U(bankBits.W) ^ historyBank(history)
        val pcRow = Mux(logicalBank < startBank, nextRow, startRow)
        (pcRow ^ (history >> bankBits))(rowBits - 1, 0)
      }
      memory.read(selectHistory(rows))
    })
  }

  // 每个物理 bank 的查询 PC 直接重建，tag 比较留在存储器旁；
  // 不先把完整表项按 slot 旋转，再串行做 tag/最长历史选择。
  private def bankPcs(history: UInt): Vec[UInt] = {
    val group = io.lookupPc(p.xLen - 1, bankBits + 2)
    val nextGroup = (group + 1.U)(p.xLen - bankBits - 3, 0)
    VecInit((0 until banks).map { bank =>
      val logicalBank = bank.U(bankBits.W) ^ historyBank(history)
      Cat(Mux(logicalBank < startBank, nextGroup, group), logicalBank, 0.U(2.W))
    })
  }

  private val reads = Seq.tabulate(tables)(t =>
    readBankRows(tagged(t), tableHistory(t), entries))
  private val bankHits = Seq.tabulate(tables) { t =>
    val candidateTags = histories.zip(tableHistory(t)).map { case (history, indexHistory) =>
      val pcs = bankPcs(indexHistory)
      VecInit((0 until banks).map(bank => tagOf(pcs(bank), history, lens(t))))
    }
    VecInit((0 until banks).map { bank =>
      // Expected tags settle alongside the selected RAM address. Keep their
      // mux before the comparator, avoiding an extra mux after late RAM data.
      val expected = selectHistory(candidateTags.map(_(bank)))
      expected === reads(t)(bank)(entryBits - 1, ctrBits)
    })
  }
  private val bankCounters = Seq.tabulate(tables)(t =>
    VecInit((0 until banks).map(bank => reads(t)(bank)(ctrBits - 1, 0))))

  // T0 保持其按 PC 分 bank 的组织；查询结果与 bank 映射在同一接受沿
  // 一起寄存，背压期间训练可继续而不会改变 F2 的已接受预测。
  private val t0BankRead = readBankRows(t0, Seq(0.U(p.ghrBits.W)), t0Entries)
  private val t0ValidBankRead = readBankRows(t0Valid, Seq(0.U(p.ghrBits.W)), t0Entries)
  private val t0Read = RegEnable(t0BankRead, io.lookupEnable)
  private val t0ValidRead = RegEnable(t0ValidBankRead, io.lookupEnable)
  private val slotPcBanks = RegEnable(VecInit((0 until w).map(slot =>
    (startBank + slot.U)(bankBits - 1, 0))), io.lookupEnable)

  private def slotBanks(candidates: Seq[UInt], oneHot: Boolean = false): Vec[UInt] =
    RegEnable(VecInit((0 until w).map(slot =>
      selectHistory(candidates.map { history =>
        val bank = ((startBank + slot.U) ^ historyBank(history))(bankBits - 1, 0)
        if (oneHot) UIntToOH(bank, banks) else bank
      }))),
      io.lookupEnable)

  private def longest(hits: Seq[Bool], counters: Seq[UInt]): (UInt, UInt, Bool) = {
    val selected = hits.indices.map { t =>
      hits(t) && !hits.drop(t + 1).foldLeft(false.B)(_ || _)
    }
    val provider = Mux1H(selected.zipWithIndex.map { case (enable, t) =>
      enable -> (t + 1).U(p.tageTableBits.W)
    })
    val counter = Mux1H(selected.zip(counters))
    (provider, counter, hits.foldLeft(false.B)(_ || _))
  }

  // 默认各表使用相同的历史低位，物理 bank 之间不需要交换完整表项。
  // 短历史参数若导致不同表采用不同 bank 排列，则在寄存后分别对齐。
  private val sameBankMapping = lens.map(h => math.min(h, bankBits)).distinct.size == 1
  if (withFallback) {
    val fallback = RegEnable(io.lookupFallbackTaken.get, io.lookupEnable)
    io.predWithFallback.get := VecInit((0 until w).map(slot =>
      Mux(io.predValid(slot), io.predTaken(slot), fallback(slot))))
  }
  if (parallelQueries && choices > 1) {
    // Share the logical memories and their single training writer. Independent
    // asynchronous read ports resolve every precomputed history before the
    // query edge; the late history decision only enters the choice register.
    class QueryPrediction extends Bundle {
      val taken = Vec(w, Bool())
      val valid = Vec(w, Bool())
      val provider = Vec(w, UInt(p.tageTableBits.W))
      val counter = Vec(w, UInt(ctrBits.W))
      val strong = Vec(w, Bool())
      val strongTaken = Vec(w, Bool())
      val fallback = Vec(w, Bool())
    }
    val predictions = histories.map { history =>
      val indexHistories = lens.map(len => TageHistoryHash.indexHistory(history, len))
      val queryReads = Seq.tabulate(tables)(t =>
        readBankRows(tagged(t), Seq(indexHistories(t)), entries))
      val queryHits = Seq.tabulate(tables) { t =>
        val pcs = bankPcs(indexHistories(t))
        VecInit((0 until banks).map(bank =>
          tagOf(pcs(bank), history, lens(t)) ===
            queryReads(t)(bank)(entryBits - 1, ctrBits)))
      }
      val prediction = Wire(new QueryPrediction)
      for (slot <- 0 until w) {
        val pcBank = (startBank + slot.U)(bankBits - 1, 0)
        val slotHits = Seq.tabulate(tables) { t =>
          val bank = (pcBank ^ historyBank(indexHistories(t)))(bankBits - 1, 0)
          Mux1H(UIntToOH(bank, banks), queryHits(t))
        }
        val slotCounters = Seq.tabulate(tables) { t =>
          val bank = (pcBank ^ historyBank(indexHistories(t)))(bankBits - 1, 0)
          Mux1H(UIntToOH(bank, banks), queryReads(t).map(_(ctrBits - 1, 0)))
        }
        val (provider, taggedCounter, hit) = longest(slotHits, slotCounters)
        val baseCounter = Mux1H(UIntToOH(pcBank, banks), t0BankRead)
        val baseValid = Mux1H(UIntToOH(pcBank, banks), t0ValidBankRead)
        val counter = Mux(hit, taggedCounter, baseCounter)
        prediction.provider(slot) := provider
        prediction.counter(slot) := counter
        prediction.valid(slot) := hit || baseValid
        prediction.taken(slot) := (if (sameBankMapping) counter(ctrBits - 1)
                                  else counter >= 2.U)
        prediction.strong(slot) := hit &&
          (taggedCounter === 0.U || taggedCounter === ((1 << ctrBits) - 1).U)
        prediction.strongTaken(slot) := hit &&
          taggedCounter === ((1 << ctrBits) - 1).U
        prediction.fallback(slot) := (if (withFallback)
          Mux(hit || baseValid, prediction.taken(slot), io.lookupFallbackTaken.get(slot))
          else prediction.taken(slot))
      }
      RegEnable(prediction, io.lookupEnable)
    }
    val registeredChoice = RegEnable(io.historySelect.get, io.lookupEnable)
    val selected = Mux1H(registeredChoice, predictions)
    io.predTaken := selected.taken
    io.predValid := selected.valid
    io.predTable := selected.provider
    io.predCtr := selected.counter
    io.predStrongTagged := selected.strong
    io.predStrongTaken := selected.strongTaken
    io.predWithFallback.foreach(_ := selected.fallback)
  } else {
  if (sameBankMapping) {
    // Decode bank selection before the lookup edge, leaving one parallel
    // selector between the registered prediction and the next lookup.
    val mappingOH = slotBanks(tableHistory(0), oneHot = true)
    val providers = Wire(Vec(banks, UInt(p.tageTableBits.W)))
    val counters = Wire(Vec(banks, UInt(ctrBits.W)))
    val hits = Wire(Vec(banks, Bool()))
    for (bank <- 0 until banks) {
      val (provider, counter, hit) = longest(
        bankHits.map(_(bank)), bankCounters.map(_(bank)))
      providers(bank) := provider
      counters(bank) := counter
      hits(bank) := hit
    }
    val registeredProviders = RegEnable(providers, io.lookupEnable)
    val registeredCounters = RegEnable(counters, io.lookupEnable)
    val registeredHits = RegEnable(hits, io.lookupEnable)
    // Feed the history/redirect loop from a direction already qualified at the
    // original query edge. Provider/counter metadata retain the old path.
    // The longest-hit mask is shared by metadata and the individual prediction
    // predicates. Qualify each table's direction/strength before selection,
    // avoiding a wide counter mux followed by another counter decode in the
    // speculative-history feedback path. Acceptance and training edges stay
    // unchanged; the fallback remains the same base-table read.
    val selectedTables = Seq.tabulate(banks) { bank =>
      (0 until tables).map(t => bankHits(t)(bank) &&
        !bankHits.drop(t + 1).map(_(bank)).foldLeft(false.B)(_ || _))
    }
    def selectedPredicate(bank: Int)(predicate: UInt => Bool): Bool =
      selectedTables(bank).zipWithIndex.map { case (selected, t) =>
        selected && predicate(bankCounters(t)(bank))
      }.foldLeft(false.B)(_ || _)
    val taggedDirections = VecInit((0 until banks).map(bank =>
      selectedPredicate(bank)(_(ctrBits - 1))))
    val strong = VecInit((0 until banks).map(bank =>
      selectedPredicate(bank)(counter => counter === 0.U ||
        counter === ((1 << ctrBits) - 1).U)))
    val strongTaken = VecInit((0 until banks).map(bank =>
      selectedPredicate(bank)(_ === ((1 << ctrBits) - 1).U)))
    // Register the actual slot predicates, not bank predicates followed by a
    // post-register crossbar in the speculative history/redirect feedback loop.
    def slotValues(values: Vec[Bool]): Vec[Bool] =
      VecInit((0 until w).map(slot => selectHistory(tableHistory(0).map { history =>
        val bank = ((startBank + slot.U) ^ historyBank(history))(bankBits - 1, 0)
        Mux1H(UIntToOH(bank, banks), values)
      })))
    val slotHitsNow = slotValues(hits)
    val slotTaggedTakenNow = slotValues(taggedDirections)
    val slotBaseTakenNow = VecInit((0 until w).map(slot =>
      t0BankRead((startBank + slot.U)(bankBits - 1, 0))(ctrBits - 1)))
    val slotBaseValidNow = VecInit((0 until w).map(slot =>
      t0ValidBankRead((startBank + slot.U)(bankBits - 1, 0))))
    // T0 is PC-indexed: history permutation followed by slot permutation
    // cancels. Read its logical slot directly instead of two dependent muxes.
    val slotDirections = RegEnable(VecInit((0 until w).map(slot =>
      slotTaggedTakenNow(slot) || (!slotHitsNow(slot) && slotBaseTakenNow(slot)))),
      io.lookupEnable)
    val slotValids = RegEnable(VecInit((0 until w).map(slot =>
      slotHitsNow(slot) || slotBaseValidNow(slot))), io.lookupEnable)
    val slotStrong = RegEnable(slotValues(strong), io.lookupEnable)
    val slotStrongTaken = RegEnable(slotValues(strongTaken), io.lookupEnable)
    if (withFallback) {
      io.predWithFallback.get := RegEnable(VecInit((0 until w).map(slot =>
        slotTaggedTakenNow(slot) || (!slotHitsNow(slot) &&
          Mux(slotBaseValidNow(slot), slotBaseTakenNow(slot),
            io.lookupFallbackTaken.get(slot))))), io.lookupEnable)
    }
    for (slot <- 0 until w) {
      val bankOH = mappingOH(slot)
      val hit = Mux1H(bankOH, registeredHits)
      val counter = Mux(hit, Mux1H(bankOH, registeredCounters), t0Read(slotPcBanks(slot)))
      io.predTable(slot) := Mux1H(bankOH, registeredProviders)
      io.predCtr(slot) := counter
      io.predTaken(slot) := slotDirections(slot)
      io.predValid(slot) := slotValids(slot)
      io.predStrongTagged(slot) := slotStrong(slot)
      io.predStrongTaken(slot) := slotStrongTaken(slot)
    }
  } else {
    val mappings = Seq.tabulate(tables)(t => slotBanks(tableHistory(t)))
    val registeredHits = bankHits.map(hits => RegEnable(hits, io.lookupEnable))
    val registeredCounters = bankCounters.map(ctrs => RegEnable(ctrs, io.lookupEnable))
    for (slot <- 0 until w) {
      val (provider, taggedCounter, hit) = longest(
        Seq.tabulate(tables)(t => registeredHits(t)(mappings(t)(slot))),
        Seq.tabulate(tables)(t => registeredCounters(t)(mappings(t)(slot))))
      val counter = Mux(hit, taggedCounter, t0Read(slotPcBanks(slot)))
      io.predTable(slot) := provider
      io.predCtr(slot) := counter
      io.predTaken(slot) := counter >= 2.U
      io.predValid(slot) := hit || t0ValidRead(slotPcBanks(slot))
      io.predStrongTagged(slot) := hit &&
        (taggedCounter === 0.U || taggedCounter === ((1 << ctrBits) - 1).U)
      io.predStrongTaken(slot) := hit && taggedCounter === ((1 << ctrBits) - 1).U
    }
  }

  }

  // 提交级训练队列。分支提交至多一条/拍，深度 4 足以覆盖短期背压。
  class TrainEntry extends Bundle {
    val baseIndex = UInt(t0IdxBits.W)
    val tableIndex = Vec(tables, UInt(idxBits.W))
    val tableTag = Vec(tables, UInt(tagBits.W))
    val taken = Bool()
    val predTaken = Bool()
    val predTable = UInt(p.tageTableBits.W)
    val predCtr = UInt(ctrBits.W)
  }
  private val q = Reg(Vec(4, new TrainEntry))
  private val qValid = RegInit(VecInit(Seq.fill(4)(false.B)))
  private val head = RegInit(0.U(2.W))
  private val tail = RegInit(0.U(2.W))
  private val count = RegInit(0.U(3.W))

  val enq = io.update.valid && io.update.isConditional && count < 4.U
  val incomingTraining = Wire(new TrainEntry)
  incomingTraining.baseIndex := (io.update.pc >> 2)(t0IdxBits - 1, 0)
  for (t <- 0 until tables) {
    incomingTraining.tableIndex(t) := indexOf(io.update.pc, io.update.predTageGhr, lens(t))
    incomingTraining.tableTag(t) := tagOf(io.update.pc, io.update.predTageGhr, lens(t))
  }
  incomingTraining.taken := io.update.taken
  incomingTraining.predTaken := io.update.predTaken
  incomingTraining.predTable := io.update.predTageTable
  incomingTraining.predCtr := io.update.predCtr
  when(enq) {
    q(tail) := incomingTraining
    qValid(tail) := true.B
    tail := tail + 1.U
  }
  val deq = count =/= 0.U
  val nextHead = head + 1.U
  val e = Reg(new TrainEntry)
  // Mirror the descriptor on the ownership edge, preserving the table update.
  when(deq && count > 1.U) {
    e := q(nextHead)
  }.elsewhen(enq) {
    e := incomingTraining
  }
  if (p.enableDifftest) {
    when(deq) {
      assert(qValid(head))
      assert(e.asUInt === q(head).asUInt)
    }
  }
  when(deq) {
    qValid(head) := false.B
    head := nextHead
  }
  // 前端 flush 不丢弃已提交训练；队列只由 enq/deq 维护。
  when(enq =/= deq) {
    count := Mux(enq, count + 1.U, count - 1.U)
  }

  private val t0WriteIdx = e.baseIndex
  private val t0TrainRows = VecInit(t0.map(_.read(t0WriteIdx(t0IdxBits - 1, bankBits))))
  private val t0Cur = t0TrainRows(t0WriteIdx(bankBits - 1, 0))
  private val t0Next = Mux(e.taken,
    Mux(t0Cur === ((1 << ctrBits) - 1).U, t0Cur, t0Cur + 1.U),
    Mux(t0Cur === 0.U, t0Cur, t0Cur - 1.U))
  private def updatedCounter(counter: UInt): UInt = Mux(e.taken,
    Mux(counter === ((1 << ctrBits) - 1).U, counter, counter + 1.U),
    Mux(counter === 0.U, counter, counter - 1.U))
  // 新分配表项直接初始化为本次实际方向（强正确），避免冷表项在
  // 首次使用时以弱反方向预测而污染长历史表。
  private val allocCtr = Mux(e.taken, 3.U, 0.U)
  // Keep the base predictor current even while a tagged provider supplies the
  // query. The original committed dequeue and the existing single write port
  // remain the only base-table update boundary.
  when(deq && (p.tageTrainBaseAlways.B || e.predTable === 0.U)) {
    for (bank <- 0 until banks) {
      when(t0WriteIdx(bankBits - 1, 0) === bank.U) {
        t0(bank).write(t0WriteIdx(t0IdxBits - 1, bankBits), t0Next)
        t0Valid(bank).write(t0WriteIdx(t0IdxBits - 1, bankBits), true.B)
      }
    }
  }
  private val allocTable = Mux(e.predTable < tables.U, e.predTable + 1.U, e.predTable)
  for (t <- 0 until tables) {
    val provider = e.predTable === (t + 1).U
    val allocate = e.predTable < tables.U &&
      e.predTaken =/= e.taken && allocTable === (t + 1).U
    val writeIdx = e.tableIndex(t)
    // Allocate only into a genuinely longer table. At the longest provider,
    // retain its ordinary saturating update through the same single write port.
    val writeTag = e.tableTag(t)
    for (bank <- 0 until banks) {
      // A committed update may carry an old prediction counter. Accumulate on
      // the current entry only while its tag still identifies the same context.
      // Keep the existing snapshot fallback if another tag replaced that row.
      // This adds a training read port; query and dequeue edges stay unchanged.
      val currentCounter = if (p.tageTrainCurrentCounter) {
        val current = tagged(t)(bank).read(writeIdx(idxBits - 1, bankBits))
        Mux(current(entryBits - 1, ctrBits) === writeTag,
          current(ctrBits - 1, 0), e.predCtr)
      } else e.predCtr
      val writeData = Cat(writeTag,
        Mux(allocate, allocCtr, updatedCounter(currentCounter)))
      when(deq && (provider || allocate) && writeIdx(bankBits - 1, 0) === bank.U) {
        tagged(t)(bank).write(writeIdx(idxBits - 1, bankBits), writeData)
      }
    }
  }
}
