package aurora

import chisel3._
import chisel3.util._

/** Optional deeper AGU input queue. Recovery marks slots, never compacts payloads.
  * Issue order need not be ROB order: each resident is checked independently.
  * Killed head slots retire locally after recovery, one per cycle. This keeps
  * recovery comparisons and payload multiplexing out of enqueue-ready logic.
  */
class RecoverableAguQueue(depth: Int)(implicit p: AuroraConfig) extends Module {
  require(depth >= 2 && isPow2(depth))
  private val ptrBits = log2Ceil(depth)
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new ExecIn))
    val deq = Decoupled(new ExecIn)
    val flush = Input(Bool())
    val recover = Input(Valid(UInt(p.robIdxBits.W)))
    val recoverHead = Input(UInt(p.robIdxBits.W))
    val occupancy = Output(UInt(log2Ceil(depth + 1).W))
  })
  val entries = Reg(Vec(depth, new ExecIn))
  val valid = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val head = RegInit(0.U(ptrBits.W))
  val tail = RegInit(0.U(ptrBits.W))
  // Includes killed holes until they reach head, so tail never overwrites live data.
  val span = RegInit(0.U(log2Ceil(depth + 1).W))
  val applyRecovery = RegNext(io.recover.valid && !io.flush, false.B)
  val kill = Reg(Vec(depth, Bool()))
  when(io.recover.valid) {
    for (slot <- 0 until depth) {
      kill(slot) := valid(slot) && RobOrder.isYounger(entries(slot).uop.robIdx,
        io.recover.bits, io.recoverHead)
    }
  }
  val blocked = io.flush || io.recover.valid || applyRecovery
  io.enq.ready := span < depth.U && !blocked
  io.deq.valid := span =/= 0.U && valid(head) && !blocked
  io.deq.bits := entries(head)
  io.occupancy := span
  val skipKilledHead = span =/= 0.U && !valid(head) && !blocked
  val pop = io.deq.fire || skipKilledHead
  when(io.flush) {
    valid := VecInit(Seq.fill(depth)(false.B))
    span := 0.U
    head := 0.U
    tail := 0.U
  }.elsewhen(applyRecovery) {
    for (slot <- 0 until depth) {
      when(kill(slot)) { valid(slot) := false.B }
    }
  }.elsewhen(!blocked) {
    when(pop) {
      valid(head) := false.B
      head := head + 1.U
    }
    when(io.enq.fire) {
      entries(tail) := io.enq.bits
      valid(tail) := true.B
      tail := tail + 1.U
    }
    when(io.enq.fire =/= pop) {
      span := Mux(io.enq.fire, span + 1.U, span - 1.U)
    }
  }
  assert(span <= depth.U)
  assert(PopCount(valid) <= span)
}
