package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** 物理寄存器堆（PRF）。
  * 只存放数据。就绪(busy)状态由 ISU 内的 ready 表管理。p0 恒为 0（映射架构 r0）。
  * 写口来自 CDB（多路），普通读口为分发提供源值。
  */
class PRF(numRead: Int, numWrite: Int, numDebugRead: Int)(implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val raddr = Input(Vec(numRead, UInt(p.physRegBits.W)))
    val rdata = Output(Vec(numRead, UInt(p.xLen.W)))
    val waddr = Input(Vec(numWrite, UInt(p.physRegBits.W)))
    val wdata = Input(Vec(numWrite, UInt(p.xLen.W)))
    val wen   = Input(Vec(numWrite, Bool()))
    val debugRaddr = Input(Vec(numDebugRead, UInt(p.physRegBits.W)))
    val debugRdata = Output(Vec(numDebugRead, UInt(p.xLen.W)))
  })

  require(numWrite >= 1)

  // Each CDB lane owns a single-write data bank, which can infer distributed
  // RAM even with several asynchronous readers. Only the narrow owner table
  // needs multiple write ports. The number of logical physical registers and
  // all visible read/write cycles are unchanged.
  val dataBanks = Seq.fill(numWrite)(Mem(p.numPhysRegs, UInt(p.xLen.W)))
  for (bank <- dataBanks) {
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(
        bank.toTarget, "ram_style = \"distributed\"")
    })
  }
  val latestWriter = Reg(Vec(p.numPhysRegs, UInt(numWrite.W)))
  val initialized = withReset(reset.asBool) {
    RegInit(VecInit(Seq.fill(p.numPhysRegs)(false.B)))
  }

  // CDB 广播先在 PRF 边界落一拍，再写入分散的数据阵列。这样全核范围的
  // CDB data/address/enable 网络不会直接驱动每个物理寄存器的数据输入。
  // valid 单独复位；无效拍的 payload 可以自由翻转，不影响可见状态。
  val writeAddress = Reg(Vec(numWrite, UInt(p.physRegBits.W)))
  val writeData = Reg(Vec(numWrite, UInt(p.xLen.W)))
  val writeValid = RegInit(VecInit(Seq.fill(numWrite)(false.B)))
  for (i <- 0 until numWrite) {
    val ramAddress = RegNext(io.waddr(i))
    dontTouch(ramAddress)
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(ramAddress.toTarget,
        "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 24")
    })
    writeAddress(i) := io.waddr(i)
    writeData(i) := io.wdata(i)
    writeValid(i) := io.wen(i) && io.waddr(i) =/= 0.U
    when(writeValid(i)) {
      dataBanks(i).write(ramAddress, writeData(i))
      if (p.enableDifftest) { assert(ramAddress === writeAddress(i)) }
    }
  }

  for (pr <- 1 until p.numPhysRegs) {
    val writeHits = VecInit((0 until numWrite).map(i =>
      writeValid(i) && writeAddress(i) === pr.U))
    val writeHit = writeHits.asUInt.orR
    when(writeHit) {
      latestWriter(pr) := writeHits.asUInt
      initialized(pr) := true.B
    }
    assert(PopCount(writeHits) <= 1.U)
  }

  def readStored(address: UInt): UInt = {
    val readable = address =/= 0.U && initialized(address)
    val data = Mux1H(latestWriter(address), dataBanks.map(_.read(address)))
    // A one-input Mux1H is the input itself, so keep the initialized/p0
    // guard outside the mux for the single-write-port configuration too.
    Mux(readable, data, 0.U)
  }

  for (i <- 0 until numRead) {
    val address = io.raddr(i)
    io.rdata(i) := readStored(address)
  }
  // Debug reads include staged writes; normal ports retain the two-edge contract.
  def readIncludingPending(address: UInt): UInt = {
    val pendingHits = VecInit((0 until numWrite).map(w =>
      writeValid(w) && writeAddress(w) === address))
    assert(PopCount(pendingHits) <= 1.U)
    Mux(pendingHits.asUInt.orR,
      Mux1H(pendingHits, writeData), readStored(address))
  }
  for (i <- 0 until numDebugRead) {
    io.debugRdata(i) := readIncludingPending(io.debugRaddr(i))
  }
}
