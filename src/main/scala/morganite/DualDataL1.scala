package morganite

import chisel3._
import chisel3.util._

/** Direct-mapped immutable lines, two outstanding misses, and one SRAM fill port.
  * Each global LSU slot reserves its own result and downstream transaction context.
  */
class DualDataL1(p: GpuParams) extends Module {
  val io = IO(new Bundle {
    val core = Vec(2, Flipped(new VectorPort(p)))
    val mem = Vec(2, new VectorPort(p))
    val roBase, roEnd = Input(UInt(32.W))
    val invalidate, cancel = Input(Bool())
    val hits, misses = Output(UInt(32.W))
  })
  val sets = p.dCacheBytes / (4*p.lineWords)
  val shift = log2Ceil(4*p.lineWords)
  def index(a: UInt): UInt = a(shift+log2Ceil(sets)-1,shift)
  def tag(a: UInt): UInt = a(31,shift+log2Ceil(sets))
  val tags = SyncReadMem(sets, UInt((32-shift-log2Ceil(sets)).W))
  val ram = SyncReadMem(sets, Vec(p.lineWords, UInt(32.W)))
  val valid = RegInit(0.U(sets.W))
  val idle :: lookup :: compare :: refill :: waitFill :: merged :: bypass :: waitMem :: done :: Nil = Enum(9)
  val state = RegInit(VecInit(Seq.fill(2)(idle)))
  val req = Reg(Vec(2, new VectorRequest(p)))
  val line = Reg(Vec(2, UInt(32.W)))
  val result = Reg(Vec(2, new VectorReply(p)))
  val stale = RegInit(VecInit(false.B,false.B))
  val stop = io.invalidate || io.cancel
  val hits, misses = RegInit(0.U(32.W))
  io.hits := hits
  io.misses := misses
  val compareBusy = state.contains(compare)
  val look = Module(new RRArbiter(UInt(32.W),2)).io
  val fills = Module(new RRArbiter(new VectorReply(p),2)).io
  val old = stale.asUInt.orR
  for (i <- 0 until 2) {
    val other = 1-i
    val core = io.core(i)
    val mem = io.mem(i)
    core.req.ready := state(i) === idle && !io.invalidate && !old
    when(core.req.fire) {
      val in = core.req.bits
      val first = Mux1H(PriorityEncoderOH(in.mask), in.addr)
      val base = (first >> shift) << shift
      val legal = in.addr.zipWithIndex.map { case (a,j) =>
        !in.mask(j) || (a(32,shift) === first(32,shift) && !(a & MemoryAccess.lastOffset(in.size)).orR)
      }.reduce(_ && _)
      val immutable = !in.store && in.mask.orR && in.size =/= 3.U && legal &&
        base >= io.roBase && base +& (4*p.lineWords).U <= io.roEnd
      req(i) := in
      line(i) := base
      state(i) := Mux(io.cancel, done, Mux(immutable, lookup, bypass))
      when(io.cancel) { result(i).fault := 7.U; result(i).address := 0.U }
    }
    val otherMissing = state(other) === refill || state(other) === waitFill
    val conflict = otherMissing && index(line(i)) === index(line(other)) && line(i) =/= line(other)
    look.in(i).valid := state(i) === lookup && !conflict && !stop && !stale(i)
    look.in(i).bits := line(i)
    when(look.in(i).fire) { state(i) := compare }
    mem.req.valid := state(i) === refill || state(i) === bypass
    mem.req.bits := req(i)
    when(state(i) === refill) {
      mem.req.bits.mask := ((BigInt(1)<<p.lineWords)-1).U
      mem.req.bits.store := false.B
      mem.req.bits.unsigned := true.B
      mem.req.bits.size := 2.U
      for (j <- 0 until p.warpWidth) { mem.req.bits.addr(j) := line(i) +& (j*4).U }
    }
    when(mem.req.fire) { state(i) := Mux(state(i) === refill, waitFill, waitMem) }
    fills.in(i).valid := mem.rsp.valid && state(i) === waitFill
    fills.in(i).bits := mem.rsp.bits
    mem.rsp.ready := state(i) === waitMem || (state(i) === waitFill && fills.in(i).ready)
    when(mem.rsp.fire && state(i) === waitMem) {
      result(i) := mem.rsp.bits
      when((stop || stale(i)) && !mem.rsp.bits.fault.orR) { result(i).fault := 7.U }
      state(i) := done
    }
    when(stop && state(i) =/= idle && state(i) =/= done) {
      stale(i) := true.B
      when(state(i) === lookup) { state(i) := done; result(i).fault := 7.U; result(i).address := line(i) }
    }
    core.rsp.valid := state(i) === done
    core.rsp.bits := result(i)
    when(core.rsp.fire) { state(i) := idle; stale(i) := false.B }
  }
  // Compare and fill use distinct cycles. A colliding tag never observes read-during-write data.
  look.out.ready := !compareBusy && !fills.out.valid
  fills.out.ready := !compareBusy
  val data = ram.read(index(look.out.bits),look.out.fire)
  val rdTag = tags.read(index(look.out.bits),look.out.fire)
  val comparing = RegEnable(look.chosen,look.out.fire)
  val other = ~comparing
  def complete(i: UInt, words: Vec[UInt], fault: UInt, addr: UInt): Unit = {
    result(i).fault := fault
    result(i).address := addr
    for (j <- 0 until p.warpWidth) {
      val a = req(i).addr(j)
      val word = if (p.lineWords == 1) words(0) else words(a(shift-1,2))
      result(i).data(j) := MemoryAccess.unpack(word,a,req(i).size,req(i).unsigned)
    }
    state(i) := done
  }
  when(compareBusy) {
    when(stop || stale(comparing)) {
      complete(comparing,data,7.U,line(comparing))
    }.elsewhen(valid(index(line(comparing))) && rdTag === tag(line(comparing))) {
      complete(comparing,data,0.U,0.U)
      hits := hits + 1.U
    }.elsewhen((state(other) === refill || state(other) === waitFill) && line(other) === line(comparing)) {
      state(comparing) := merged
    }.otherwise { state(comparing) := refill; misses := misses + 1.U }
  }
  when(fills.out.fire) {
    val i = fills.chosen
    val response = fills.out.bits
    val words = VecInit(response.data.take(p.lineWords))
    val fault = Mux(response.fault.orR,response.fault,Mux(stop || stale(i),7.U,0.U))
    when(!fault.orR) {
      ram.write(index(line(i)),words)
      tags.write(index(line(i)),tag(line(i)))
      valid := valid.bitSet(index(line(i)),true.B)
    }
    complete(i,words,fault,response.address)
    when(state(~i) === merged) { complete(~i,words,fault,response.address) }
  }
  when(io.invalidate) { valid := 0.U; hits := 0.U; misses := 0.U }
}
