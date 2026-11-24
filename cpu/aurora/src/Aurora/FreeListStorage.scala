package aurora

import chisel3._
import chisel3.util._

/** Storage for the ISU's circular free list.
  *
  * Reads and writes are contiguous windows. Banking by the low pointer bits
  * gives each bank one reader and one writer, instead of a full multiport
  * crossbar over all physical slots. Pointer/credit/recovery state stays in
  * ISU; this module introduces no pipeline stage or allocation policy.
  */
class FreeListStorage(implicit p: AuroraConfig) extends Module {
  private val banks = p.coreWidth min p.numPhysRegs
  private val bankBits = log2Ceil(banks)
  private val rows = p.numPhysRegs / banks
  private val rowBits = log2Ceil(rows max 2)
  require(isPow2(banks) && p.numPhysRegs % banks == 0)

  val io = IO(new Bundle {
    val readHead = Input(UInt(p.physRegBits.W))
    val readWindow = Output(Vec(p.coreWidth, UInt(p.physRegBits.W)))
    val writeTail = Input(UInt(p.physRegBits.W))
    val writeCount = Input(UInt(p.issueCountBits.W))
    val writeData = Input(Vec(p.coreWidth, UInt(p.physRegBits.W)))
  })

  val storage = Seq.tabulate(banks) { bank =>
    RegInit(VecInit((0 until rows).map { row =>
      val index = row * banks + bank
      val initial = if (index < p.freeListCapacity) index + p.numArchRegs else 0
      initial.U(p.physRegBits.W)
    }))
  }

  def rowForBank(pointer: UInt, bank: Int): UInt = {
    if (rows == 1) 0.U(rowBits.W)
    else {
      val base = pointer(p.physRegBits - 1, bankBits)
      val wrapsBank = pointer(bankBits - 1, 0) > bank.U
      (base + wrapsBank.asUInt)(rowBits - 1, 0)
    }
  }

  val readValues = VecInit((0 until banks).map { bank =>
    storage(bank)(rowForBank(io.readHead, bank))
  })
  for (offset <- 0 until p.coreWidth) {
    val bank = (io.readHead(bankBits - 1, 0) + offset.U)(bankBits - 1, 0)
    io.readWindow(offset) := readValues(bank)
  }

  for (bank <- 0 until banks) {
    val rank = (bank.U(bankBits.W) - io.writeTail(bankBits - 1, 0))(bankBits - 1, 0)
    when(io.writeCount > rank) {
      storage(bank)(rowForBank(io.writeTail, bank)) := io.writeData(rank)
    }
  }
  // A legal commit packet cannot return more distinct registers than banks.
  assert(io.writeCount <= banks.U)
}
