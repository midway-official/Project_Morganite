package aurora

import chisel3._
import chisel3.util._

/** Bounded circular LSQ indices. Inputs to wrap are in [0, 2*depth),
  * so non-power-of-two queues need one conditional subtraction, no divider.
  */
object LsqRing {
  /** First candidate in circular order starting at head. The queue retains
    * ownership of head; this combinational selection never advances it.
    */
  def firstFromOH(candidates: UInt, head: UInt, depth: Int): UInt = {
    require(depth >= 2 && candidates.getWidth == depth)
    val suffixMask = VecInit((0 until depth).map(slot => head <= slot.U)).asUInt
    val suffix = candidates & suffixMask
    val preferred = PriorityEncoderOH(suffix)
    val fallback = PriorityEncoderOH(candidates)
    preferred | (fallback & Fill(depth, !suffix.orR))
  }

  def wrap(value: UInt, depth: Int): UInt = {
    require(depth >= 2)
    val bits = log2Ceil(depth)
    if (isPow2(depth)) value(bits - 1, 0)
    else Mux(value >= depth.U, value - depth.U, value)(bits - 1, 0)
  }

  def retreat(pointer: UInt, count: UInt, depth: Int): UInt = {
    // Add a complete revolution before subtraction. Binary underflow modulo
    // 2^indexBits gives a wrong answer for a twelve-entry queue.
    wrap((pointer +& depth.U) - count, depth)
  }
}
