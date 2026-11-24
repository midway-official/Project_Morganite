package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** 一个窗口的连续 PC 在各 bank 各读一次；训练另有一个读改写端口。
  * index = PC 的字索引 xor history。不能按 slot 实例化整张计数表。
  */
class BankedDirectionTable(entries: Int, counterBits: Int, initial: Int,
                           reportPhysicalBanks: Boolean = false)(
    implicit p: AuroraConfig) extends Module {
  require(isPow2(entries) && entries >= 2 * p.fetchWidth)
  private val indexBits = log2Ceil(entries)
  private val banks = p.fetchWidth
  private val bankBits = p.slotBits
  private val rowBits = indexBits - bankBits
  val io = IO(new Bundle {
    val pc = Input(UInt(p.xLen.W))
    val history = Input(UInt(indexBits.W))
    val counters = Output(Vec(banks, UInt(counterBits.W)))
    val valid = Output(Vec(banks, Bool()))
    val takenByBank = if (reportPhysicalBanks) Some(Output(UInt(banks.W))) else None
    val update = Input(Bool())
    val updateIndex = Input(UInt(indexBits.W))
    val taken = Input(Bool())
  })

  private val tables = Seq.fill(banks)(Mem(entries / banks, UInt(counterBits.W)))
  private val valids = RegInit(VecInit(Seq.fill(entries)(false.B)))
  tables.foreach { table =>
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(table.toTarget,
        "ram_style = \"distributed\"")
    })
  }
  val updateBank = io.updateIndex(bankBits - 1, 0)
  val updateRow = io.updateIndex(indexBits - 1, bankBits)
  val updateReads = VecInit(tables.map(_(updateRow)))
  val oldCounter = Mux(valids(io.updateIndex), updateReads(updateBank), initial.U)
  val maxCounter = ((1 << counterBits) - 1).U(counterBits.W)
  val newCounter = Mux(io.taken,
    Mux(oldCounter === maxCounter, maxCounter, oldCounter + 1.U),
    Mux(oldCounter === 0.U, 0.U, oldCounter - 1.U))
  when(io.update) {
    for (bank <- 0 until banks) {
      when(updateBank === bank.U) { tables(bank).write(updateRow, newCounter) }
    }
    valids(io.updateIndex) := true.B
  }

  val startBank = io.pc(bankBits + 1, 2)
  val baseRow = io.pc(indexBits + 1, bankBits + 2)
  val bankReads = Wire(Vec(banks, UInt(counterBits.W)))
  val bankTaken = if (reportPhysicalBanks) Some(Wire(Vec(banks, Bool()))) else None
  for (bank <- 0 until banks) {
    // XOR 的低位只改变 bank 排列，高位仍使用同一份历史。
    val wordBank = bank.U(bankBits.W) ^ io.history(bankBits - 1, 0)
    val wordRow = (baseRow + (wordBank < startBank).asUInt)(rowBits - 1, 0)
    val row = wordRow ^ io.history(indexBits - 1, bankBits)
    bankReads(bank) := tables(bank)(row)
    bankTaken.foreach { taken =>
      val index = Cat(row, bank.U(bankBits.W))
      val bypass = io.update && io.updateIndex === index
      taken(bank) := Mux(bypass, newCounter(counterBits - 1),
        valids(index) && bankReads(bank)(counterBits - 1))
    }
  }
  io.takenByBank.foreach(_ := bankTaken.get.asUInt)
  for (slot <- 0 until banks) {
    val word = (io.pc(indexBits + 1, 2) + slot.U)(indexBits - 1, 0)
    val index = word ^ io.history
    val bypass = io.update && io.updateIndex === index
    io.counters(slot) := Mux(bypass, newCounter, bankReads(index(bankBits - 1, 0)))
    io.valid(slot) := bypass || valids(index)
  }
}

/** 每窗口最多有一个有效 JIRL：第一个 JIRL 总预测 taken 并截断后续槽。
  * 在 ICache 输出端只做6位 opcode预译码，选择一个查询，不复制四份目标 RAM。
  * 32 项表只用 1.8 kbit，改用 distributed RAM，使 F2 逻辑从普通触发器出发，
  * 不再承担 RAMB36 的 clock-to-out；读口仍寄存，训练写口和旁路语义不变。
  */
class IndirectTargetTable(implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val pc = Input(UInt(p.xLen.W))
    val slot = Input(UInt(p.slotBits.W))
    val enable = Input(Bool())
    val update = Input(new BpuUpdate)
    val hit = Output(Vec(p.fetchWidth, Bool()))
    val target = Output(Vec(p.fetchWidth, UInt(p.xLen.W)))
  })
  val table = Mem(p.btbEntries, UInt((p.btbTagBits + p.xLen).W))
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(table.toTarget, "ram_style = \"distributed\"")
  })
  val valids = RegInit(VecInit(Seq.fill(p.btbEntries)(false.B)))
  val updateEnable = io.update.valid && (io.update.isIndirect || io.update.isRet)
  val updateIndex = io.update.pc(p.btbIdxBits + 1, 2)
  val updateTag = io.update.pc(p.xLen - 1, p.btbIdxBits + 2)
  val updateEntry = Cat(updateTag, io.update.target)
  when(updateEnable) {
    table.write(updateIndex, updateEntry)
    valids(updateIndex) := true.B
  }
  // The instruction opcode arrives from the ICache data RAM. Read every
  // constant-PC slot in parallel; the opcode only selects registered output.
  val queryPcs = VecInit((0 until p.fetchWidth).map(slot =>
    io.pc + (slot * p.instructionBytes).U))
  val queryIndices = VecInit(queryPcs.map(_(p.btbIdxBits + 1, 2)))
  val queryTags = VecInit(queryPcs.map(_(p.xLen - 1, p.btbIdxBits + 2)))
  val readSlot = RegEnable(UIntToOH(io.slot, p.fetchWidth),
    1.U(p.fetchWidth.W), io.enable)
  val preparedHits = Wire(Vec(p.fetchWidth, Bool()))
  val preparedTargets = Wire(Vec(p.fetchWidth, UInt(p.xLen.W)))
  for (slot <- 0 until p.fetchWidth) {
    val queryIndex = queryIndices(slot)
    val queryTag = queryTags(slot)
    val resident = table(queryIndex)
    val bypass = updateEnable && queryIndex === updateIndex
    val hit = Mux(bypass, updateTag === queryTag,
      valids(queryIndex) && resident(p.btbTagBits + p.xLen - 1, p.xLen) === queryTag)
    preparedHits(slot) := RegEnable(hit, false.B, io.enable)
    preparedTargets(slot) := RegEnable(
      Mux(bypass, io.update.target, resident(p.xLen - 1, 0)), io.enable)
    io.hit(slot) := readSlot(slot) && preparedHits(slot)
  }
  val selectedTarget = Mux1H(readSlot, preparedTargets)
  io.target := VecInit(Seq.fill(p.fetchWidth)(selectedTarget))
}
