package morganite

import chisel3._
import chisel3.util._

class RegisterRead(p: GpuParams) extends Bundle {
  val row = UInt(p.rowBits.W)
  val source = Vec(3, UInt(p.registerBits.W))
}

class RegisterWrite(p: GpuParams) extends Bundle {
  val row = UInt(p.rowBits.W)
  val dest = UInt(p.registerBits.W)
  val mask = UInt(p.lanes.W)
  val data = Vec(p.lanes, UInt(32.W))
}

/** Address row = warp * beats + physical slice. Three synchronous copies per lane. */
class LaneRegisters(p: GpuParams) extends Module {
  val io = IO(new Bundle {
    val rd = Flipped(Valid(new RegisterRead(p)))
    val wr = Flipped(Valid(new RegisterWrite(p)))
    val data = Output(Vec(3, Vec(p.lanes, UInt(32.W))))
  })
  val depth = p.warpSlots * p.beats * p.registers
  def address(row: UInt, reg: UInt): UInt =
    (if (p.warpSlots * p.beats == 1) reg else Cat(row, reg))(log2Ceil(depth) - 1, 0)
  val wrAddr = address(io.wr.bits.row, io.wr.bits.dest)
  for (lane <- 0 until p.lanes) {
    val wrEna = io.wr.valid && io.wr.bits.dest.orR && io.wr.bits.mask(lane)
    val forwarded = RegEnable(io.wr.bits.data(lane), wrEna)
    for (port <- 0 until 3) {
      val rdAddr = address(io.rd.bits.row, io.rd.bits.source(port))
      val fromRam = if (p.bramRf) {
        // Register the data output (UG901 simple dual-port template). The
        // explicit forwarding below defines simultaneous same-address access.
        val bank = Mem(depth, UInt(32.W))
        when(wrEna) { bank.write(wrAddr, io.wr.bits.data(lane)) }
        RegEnable(bank(rdAddr), io.rd.valid)
      } else {
        val bank = SyncReadMem(depth, UInt(32.W))
        when(wrEna) { bank.write(wrAddr, io.wr.bits.data(lane)) }
        bank.read(rdAddr, io.rd.valid)
      }
      val bypass = RegEnable(wrEna && wrAddr === rdAddr, io.rd.valid)
      val zero = RegEnable(!io.rd.bits.source(port).orR, io.rd.valid)
      io.data(port)(lane) := Mux(zero, 0.U, Mux(bypass, forwarded, fromRam))
    }
  }
}

/** Include the launching issue/writeback registers and the capture registers for OOC timing. */
class RegisterTiming(p: GpuParams) extends Module {
  val inner = Module(new LaneRegisters(p))
  val io = IO(chiselTypeOf(inner.io))
  inner.io.rd := RegNext(io.rd)
  inner.io.wr := RegNext(io.wr)
  io.data := RegNext(inner.io.data)
}
