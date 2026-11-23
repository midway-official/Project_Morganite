package morganite

import chisel3._
import chisel3.util._

class LaneBeat(p: GpuParams) extends Bundle {
  val index = UInt(p.beatBits.W)
  val mask = UInt(p.lanes.W)
  val last = Bool()
}

/** Enumerate nonempty physical-lane slices in increasing thread order. */
class WarpBeats(p: GpuParams) extends Module {
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(UInt(p.warpWidth.W)))
    val beat = Decoupled(new LaneBeat(p))
  })
  val remaining = RegInit(0.U(p.beats.W))
  val mask = Reg(UInt(p.warpWidth.W))
  val chunks = mask.asTypeOf(Vec(p.beats, UInt(p.lanes.W)))
  val index = if (p.beats == 1) 0.U else PriorityEncoder(remaining)
  val rest = remaining & (remaining - 1.U)
  io.beat.valid := remaining.orR
  io.beat.bits.index := index
  io.beat.bits.mask := (if (p.beats == 1) chunks(0) else chunks(index))
  io.beat.bits.last := !rest.orR
  io.issue.ready := !remaining.orR || (io.beat.ready && !rest.orR)
  when(io.beat.fire) { remaining := rest }
  when(io.issue.fire) {
    mask := io.issue.bits
    remaining := VecInit(io.issue.bits.asTypeOf(Vec(p.beats, UInt(p.lanes.W))).map(_.orR)).asUInt
  }
}
