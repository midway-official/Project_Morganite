package morganite

import chisel3._
import chisel3.util._

class VectorRequest(p: GpuParams) extends Bundle {
  val addr = Vec(p.warpWidth, UInt(33.W))
  val data = Vec(p.warpWidth, UInt(32.W))
  val mask = UInt(p.warpWidth.W)
  val store = Bool()
  val size = UInt(2.W)
  val unsigned = Bool()
}

class VectorReply(p: GpuParams) extends Bundle {
  val data = Vec(p.warpWidth, UInt(32.W))
  val fault = UInt(4.W)
  val address = UInt(32.W)
}

class VectorPort(p: GpuParams) extends Bundle {
  val req = Decoupled(new VectorRequest(p))
  val rsp = Flipped(Decoupled(new VectorReply(p)))
}

object MemoryAccess {
  def lastOffset(size: UInt): UInt = MuxLookup(size, 3.U)(Seq(0.U -> 0.U, 1.U -> 1.U))
  def invalid(address: UInt, size: UInt, base: UInt, limit: UInt): Bool = {
    val end = address +& lastOffset(size)
    size === 3.U || (address & lastOffset(size)).orR || address < base || end >= limit
  }
  def unpack(word: UInt, address: UInt, size: UInt, unsigned: Bool): UInt = {
    val shifted = word >> Cat(address(1, 0), 0.U(3.W))
    MuxLookup(size, word)(Seq(
      0.U -> Cat(Fill(24, shifted(7) && !unsigned), shifted(7, 0)),
      1.U -> Cat(Fill(16, shifted(15) && !unsigned), shifted(15, 0))))
  }
  def strobes(size: UInt, address: UInt): UInt =
    (MuxLookup(size, 15.U(4.W))(Seq(0.U -> 1.U, 1.U -> 3.U)) << address(1, 0))(3, 0)
}

class Scratchpad(p: GpuParams) extends Module {
  val io = IO(Flipped(new VectorPort(p)))
  val idle :: access :: drain :: respond :: Nil = Enum(4)
  val state = RegInit(idle)
  val request = Reg(new VectorRequest(p))
  val remaining = Reg(UInt(p.warpWidth.W))
  val reply = Reg(new VectorReply(p))
  io.req.ready := state === idle
  io.rsp.valid := state === respond
  io.rsp.bits := reply
  when(io.req.fire) {
    request := io.req.bits
    remaining := io.req.bits.mask
    val invalid = VecInit(io.req.bits.addr.map(a => MemoryAccess.invalid(a, io.req.bits.size, 0.U, p.scratchBytes.U))).asUInt & io.req.bits.mask
    reply.fault := Mux(invalid.orR, 3.U, 0.U)
    reply.address := Mux1H(PriorityEncoderOH(invalid), io.req.bits.addr)
    state := Mux(invalid.orR || !io.req.bits.mask.orR, respond, access)
  }
  val grants = (0 until p.lanes).map { bank =>
    val banks = p.lanes
    val depth = p.scratchBytes / (4 * banks)
    val candidates = VecInit(request.addr.map { addr =>
      if (banks == 1) true.B else addr(log2Ceil(banks) + 1, 2) === bank.U
    }).asUInt & remaining
    val grant = PriorityEncoderOH(candidates)
    val address = Mux1H(grant, request.addr)
    val value = Mux1H(grant, request.data)
    val index = if (depth == 1) 0.U else address(log2Ceil(p.scratchBytes) - 1, log2Ceil(banks) + 2)
    val mem = SyncReadMem(depth, Vec(4, UInt(8.W)))
    val selected = state === access && candidates.orR
    val data = mem.read(index, selected && !request.store).asUInt
    when(selected && request.store) {
      val shifted = (value << Cat(address(1, 0), 0.U(3.W)))(31, 0)
      mem.write(index, shifted.asTypeOf(Vec(4, UInt(8.W))), MemoryAccess.strobes(request.size, address).asBools)
    }
    val readMask = RegNext(Mux(selected && !request.store, grant, 0.U), 0.U)
    val offset = RegEnable(address(1, 0), selected)
    for (i <- 0 until p.warpWidth) {
      when(readMask(i)) { reply.data(i) := MemoryAccess.unpack(data, offset, request.size, request.unsigned) }
    }
    grant
  }
  when(state === access) {
    val rest = remaining & ~grants.reduce(_ | _)
    remaining := rest
    when(!rest.orR) { state := drain }
  }
  when(state === drain) { state := respond }
  when(io.rsp.fire) { state := idle }
}
