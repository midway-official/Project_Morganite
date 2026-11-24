package morganite

import chisel3._
import chisel3.util._

class LineRequest(p: GpuParams) extends Bundle {
  val address = UInt(32.W) // first valid word; instruction lines are relative to programBase
  val mask = UInt(p.lineWords.W)
}

class LineReply(p: GpuParams) extends Bundle {
  val data = Vec(p.lineWords, UInt(32.W))
  val fault = UInt(4.W)
  val address = UInt(32.W)
}

/** One blocking, direct-mapped cache. The whole vector and tag share a synchronous read. */
class ReadOnlyL1(p: GpuParams, bytes: Int) extends Module {
  val io = IO(new Bundle {
    val lookup = Flipped(Decoupled(new LineRequest(p)))
    val result = Decoupled(new LineReply(p))
    val memory = new VectorPort(p)
    val invalidate = Input(Bool())
    val hits, misses = Output(UInt(32.W))
    val idle = Output(Bool())
  })
  val sets = bytes / (4 * p.lineWords)
  val shift = log2Ceil(p.lineWords * 4)
  def index(addr: UInt): UInt = addr(shift + log2Ceil(sets) - 1, shift)
  val idle :: compare :: issue :: fill :: respond :: Nil = Enum(5)
  val state = RegInit(idle)
  val request = RegEnable(io.lookup.bits, io.lookup.fire)
  val valid = RegInit(0.U(sets.W))
  val set = index(request.address)
  val data = SyncReadMem(sets, Vec(p.lineWords, UInt(32.W)))
  val tags = SyncReadMem(sets, UInt((32 - shift - log2Ceil(sets)).W))
  def tag(addr: UInt): UInt = addr(31, shift + log2Ceil(sets))
  val rd = data.read(index(io.lookup.bits.address), io.lookup.fire)
  val rdTag = tags.read(index(io.lookup.bits.address), io.lookup.fire)
  val reply = Reg(new LineReply(p))
  val hits, misses = RegInit(0.U(32.W))
  io.hits := hits
  io.misses := misses
  io.idle := state === idle
  io.lookup.ready := state === idle && !io.invalidate
  io.result.valid := state === respond
  io.result.bits := reply
  io.memory.req.valid := state === issue
  io.memory.req.bits := 0.U.asTypeOf(io.memory.req.bits)
  io.memory.req.bits.size := 2.U
  io.memory.req.bits.mask := request.mask
  for (i <- 0 until p.warpWidth) { io.memory.req.bits.addr(i) := request.address +& (4*i).U }
  io.memory.rsp.ready := state === fill
  when(io.lookup.fire) { state := compare }
  when(state === compare) {
    when(valid(set) && rdTag === tag(request.address)) {
      reply.data := rd
      reply.fault := 0.U
      reply.address := 0.U
      state := respond
      hits := hits + 1.U
    }.otherwise { state := issue; misses := misses + 1.U }
  }
  when(io.memory.req.fire) { state := fill }
  when(io.memory.rsp.fire) {
    val r = io.memory.rsp.bits
    val words = VecInit(r.data.take(p.lineWords))
    reply.data := words
    reply.fault := r.fault
    reply.address := r.address
    when(!r.fault.orR) {
      data.write(set, words)
      tags.write(set, tag(request.address))
      valid := valid.bitSet(set, true.B)
    }
    state := respond
  }
  when(io.result.fire) { state := idle }
  when(io.invalidate) {
    assert(state === idle)
    valid := 0.U
    hits := 0.U
    misses := 0.U
  }
}

/** Keep mixed-line vectors coalescible in the DMA. Only a vector wholly within a
  * single immutable line is cached; permissions are checked even on bypasses. */
class DataL1(p: GpuParams) extends Module {
  val io = IO(new Bundle {
    val core = Flipped(new VectorPort(p))
    val mem = new VectorPort(p)
    val roBase, roEnd = Input(UInt(32.W))
    val invalidate = Input(Bool())
    val hits, misses = Output(UInt(32.W))
  })
  val idle :: waitLine :: waitMem :: Nil = Enum(3)
  val state = RegInit(idle)
  val req = io.core.req.bits
  val shift = log2Ceil(4 * p.lineWords)
  // Lane zero anchors the common broadcast and lane-aligned vector cases.
  // A masked-off lane zero or a gather takes the normal coalescing path.
  val first = req.addr(0)
  val line = (first >> shift) << shift
  val broadcast = (VecInit(req.addr.map(_ === first)).asUInt & req.mask) === req.mask
  val packed = (VecInit(req.addr.zipWithIndex.map { case (a, i) =>
    if (i >= p.lineWords) false.B else a === (line | (i*4).U)
  }).asUInt & req.mask) === req.mask && req.size === 2.U
  val cacheable = !req.store && req.size =/= 3.U && req.mask(0) &&
    ((broadcast && !(first & MemoryAccess.lastOffset(req.size)).orR) || packed) &&
    line >= io.roBase && (line +& (4*p.lineWords).U) <= io.roEnd
  val cache = Module(new ReadOnlyL1(p, p.dCacheBytes))
  io.hits := cache.io.hits
  io.misses := cache.io.misses
  cache.io.invalidate := io.invalidate
  io.core.req.ready := state === idle && Mux(cacheable, cache.io.lookup.ready, io.mem.req.ready)
  cache.io.lookup.valid := state === idle && io.core.req.valid && cacheable
  cache.io.lookup.bits.address := line
  cache.io.lookup.bits.mask := Fill(p.lineWords, 1.U)
  val cached = cache.io.lookup.fire
  val scalar = RegEnable(broadcast, cached)
  val offset = RegEnable(first(shift-1,0), cached)
  val size = RegEnable(req.size, cached)
  val unsigned = RegEnable(req.unsigned, cached)
  val r = cache.io.result.bits
  val word = if (p.lineWords == 1) r.data(0) else r.data(offset(shift-1,2))
  val scalarValue = MemoryAccess.unpack(word, offset, size, unsigned)
  val inCache = state === waitLine
  io.core.rsp.valid := Mux(inCache, cache.io.result.valid, state === waitMem && io.mem.rsp.valid)
  io.core.rsp.bits := io.mem.rsp.bits
  when(inCache) {
    io.core.rsp.bits.fault := r.fault
    io.core.rsp.bits.address := r.address
    for (i <- 0 until p.warpWidth) {
      io.core.rsp.bits.data(i) := Mux(scalar, scalarValue, r.data(i % p.lineWords))
    }
  }
  cache.io.result.ready := inCache && io.core.rsp.ready
  io.mem.req.valid := Mux(inCache, cache.io.memory.req.valid, state === idle && io.core.req.valid && !cacheable)
  io.mem.req.bits := Mux(inCache, cache.io.memory.req.bits, req)
  cache.io.memory.req.ready := inCache && io.mem.req.ready
  cache.io.memory.rsp.valid := inCache && io.mem.rsp.valid
  cache.io.memory.rsp.bits := io.mem.rsp.bits
  io.mem.rsp.ready := Mux(inCache, cache.io.memory.rsp.ready, state === waitMem && io.core.rsp.ready)
  when(io.core.req.fire) { state := Mux(cacheable, waitLine, waitMem) }
  when(io.core.rsp.fire) { state := idle }
}
