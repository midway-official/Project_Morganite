package morganite

import chisel3._
import chisel3.util._

class AxiAddress(p: GpuParams) extends Bundle {
  val addr = UInt(p.axiAddrBits.W)
  val id = UInt(p.axiIdBits.W)
  val len = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
  val lock = Bool()
  val cache = UInt(4.W)
  val prot = UInt(3.W)
  val qos = UInt(4.W)
}
class ReadData(p: GpuParams) extends Bundle {
  val id = UInt(p.axiIdBits.W)
  val data = UInt(p.axiDataBits.W)
  val resp = UInt(2.W)
  val last = Bool()
}
class WriteData(p: GpuParams) extends Bundle {
  val data = UInt(p.axiDataBits.W)
  val strb = UInt((p.axiDataBits / 8).W)
  val last = Bool()
}
class WriteResponse(p: GpuParams) extends Bundle {
  val id = UInt(p.axiIdBits.W)
  val resp = UInt(2.W)
}
class AxiMaster(p: GpuParams) extends Bundle {
  val ar = Decoupled(new AxiAddress(p))
  val r = Flipped(Decoupled(new ReadData(p)))
  val aw = Decoupled(new AxiAddress(p))
  val w = Decoupled(new WriteData(p))
  val b = Flipped(Decoupled(new WriteResponse(p)))
}

/** Coalesce the active threads of one warp into same-page, naturally aligned INCR bursts. */
class BurstPlan(p: GpuParams) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorRequest(p)))
    val base, limit = Input(UInt(32.W))
    val burst = Decoupled(new AxiAddress(p))
    val cursor = Input(UInt(log2Ceil(p.warpWidth).max(1).W))
    val lane = Output(UInt(log2Ceil(p.warpWidth).max(1).W))
    val address, data = Output(UInt(32.W))
    val size = Output(UInt(2.W))
    val unsigned = Output(Bool())
    val complete, abort = Input(Bool())
    val done = Decoupled(new Bundle {
      val fault = UInt(4.W)
      val address = UInt(32.W)
    })
  })
  val idle :: prepare :: offer :: transfer :: finished :: Nil = Enum(5)
  val state = RegInit(idle)
  val request = Reg(new VectorRequest(p))
  val remaining = Reg(UInt(p.warpWidth.W))
  val count = Reg(UInt(log2Ceil(p.warpWidth + 1).W))
  val first, previous = Reg(UInt(32.W))
  val laneList = Reg(Vec(p.warpWidth, UInt(log2Ceil(p.warpWidth).max(1).W)))
  val fault = Reg(UInt(4.W))
  val faultAddress = Reg(UInt(32.W))
  val candidate = if (p.warpWidth == 1) 0.U else PriorityEncoder(remaining)
  val address = request.addr(candidate)(31, 0)
  val step = 1.U(4.W) << request.size
  val following = previous +& step
  val canAppend = !count.orR || (address === following && address(31, 12) === first(31, 12))
  io.in.ready := state === idle
  when(io.in.fire) {
    val in = io.in.bits
    val invalid = VecInit(in.addr.map(MemoryAccess.invalid(_, in.size, io.base, io.limit))).asUInt & in.mask
    request := in
    remaining := in.mask
    count := 0.U
    fault := Mux(invalid.orR, 3.U, 0.U)
    faultAddress := Mux1H(PriorityEncoderOH(invalid), in.addr)
    state := Mux(invalid.orR || !in.mask.orR, finished, prepare)
  }
  when(state === prepare) {
    when(canAppend) {
      when(!count.orR) { first := address }
      previous := address
      laneList(count(log2Ceil(p.warpWidth).max(1) - 1, 0)) := candidate
      count := count + 1.U
      val rest = remaining & (remaining - 1.U)
      remaining := rest
      when(!rest.orR) { state := offer }
    }.otherwise { state := offer }
  }
  io.burst.valid := state === offer
  io.burst.bits := 0.U.asTypeOf(io.burst.bits)
  io.burst.bits.size := request.size
  io.burst.bits.addr := first
  io.burst.bits.len := count - 1.U
  io.burst.bits.burst := 1.U
  when(io.burst.fire) { state := transfer }
  when(state === transfer && io.complete) {
    count := 0.U
    state := Mux(io.abort || !remaining.orR, finished, prepare)
  }
  io.lane := laneList(io.cursor)
  io.address := request.addr(io.lane)
  io.data := request.data(io.lane)
  io.size := request.size
  io.unsigned := request.unsigned
  io.done.valid := state === finished
  io.done.bits.fault := fault
  io.done.bits.address := faultAddress
  when(io.done.fire) { state := idle }
}

class Dma(p: GpuParams) extends Module {
  val owners = p.numSms + 1
  val io = IO(new Bundle {
    val clients = Vec(owners, Flipped(new VectorPort(p)))
    val axi = new AxiMaster(p)
    val base, limit = Input(UInt(32.W))
    val readBursts, writeBursts, readBytes, writeBytes = Output(UInt(32.W))
    val idle = Output(Bool())
  })
  val busy = RegInit(VecInit(Seq.fill(owners)(false.B)))
  io.idle := !busy.asUInt.orR
  val plans = Seq.fill(2)(Module(new BurstPlan(p)))
  val arbiters = Seq.fill(2)(Module(new RRArbiter(new VectorRequest(p), owners)))
  val owner = Reg(Vec(2, UInt(log2Ceil(owners).W)))
  val fault = Reg(Vec(2, UInt(4.W)))
  val address = Reg(Vec(2, UInt(32.W)))
  val cursor = RegInit(VecInit(Seq.fill(2)(0.U(log2Ceil(p.warpWidth).max(1).W))))
  val count = Reg(Vec(2, UInt(8.W)))
  val data = Reg(Vec(p.warpWidth, UInt(32.W)))
  val rBursts, wBursts, rBytes, wBytes = RegInit(0.U(32.W))
  io.readBursts := rBursts
  io.writeBursts := wBursts
  io.readBytes := rBytes
  io.writeBytes := wBytes
  for (i <- 0 until 2) {
    val plan = plans(i).io
    val arb = arbiters(i).io
    for (j <- 0 until owners) {
      val req = io.clients(j).req
      arb.in(j).valid := req.valid && !busy(j) && req.bits.store === (i == 1).B
      arb.in(j).bits := req.bits
    }
    plan.in <> arb.out
    plan.base := io.base
    plan.limit := io.limit
    when(plan.in.fire) {
      owner(i) := arb.chosen
      fault(i) := 0.U
      address(i) := 0.U
    }
    plan.cursor := cursor(i)
    plan.done.ready := io.clients(owner(i)).rsp.ready
    when(plan.burst.fire) {
      count(i) := plan.burst.bits.len
      cursor(i) := 0.U
    }
  }
  for (j <- 0 until owners) {
    val client = io.clients(j)
    client.req.ready := !busy(j) && Mux(client.req.bits.store, arbiters(1).io.in(j).ready, arbiters(0).io.in(j).ready)
    when(client.req.fire) { busy(j) := true.B }
    val read = plans.head.io.done.valid && owner(0) === j.U
    val write = plans.last.io.done.valid && owner(1) === j.U
    client.rsp.valid := read || write
    val d = Mux(write, plans.last.io.done.bits, plans.head.io.done.bits)
    client.rsp.bits.fault := Mux(d.fault.orR, d.fault, fault(write))
    client.rsp.bits.address := Mux(d.fault.orR, d.address, address(write))
    client.rsp.bits.data := Mux(read, data, 0.U.asTypeOf(data))
    when(client.rsp.fire) { busy(j) := false.B }
  }
  val r = plans.head.io
  io.axi.ar <> r.burst
  val reading = RegInit(false.B)
  val draining = RegInit(false.B)
  when(io.axi.ar.fire) {
    reading := true.B
    draining := false.B
    rBursts := rBursts + 1.U
  }
  val rb = io.axi.r.bits
  io.axi.r.ready := reading
  val rBad = rb.resp(1) || rb.id =/= 0.U || rb.last =/= (cursor(0) === count(0))
  when(io.axi.r.fire) {
    when(!fault(0).orR && rBad) {
      fault(0) := Mux(rb.resp === 3.U, 6.U, 5.U)
      address(0) := r.address
    }
    when(!draining) {
      data(r.lane) := MemoryAccess.unpack(rb.data, r.address, r.size, r.unsigned)
      rBytes := rBytes + (1.U << r.size)
    }
    when(rb.last) { reading := false.B }
      .elsewhen(cursor(0) === count(0)) { draining := true.B }
      .otherwise { cursor(0) := cursor(0) + 1.U }
  }
  r.complete := io.axi.r.fire && rb.last
  r.abort := fault(0).orR || rBad

  val wr = plans.last.io
  // Capture an entire burst before offering AW and W. Neither VALID waits for READY.
  val have = RegInit(false.B)
  val awPending, wPending = RegInit(false.B)
  val aw = Reg(new AxiAddress(p))
  wr.burst.ready := !have
  when(wr.burst.fire) {
    have := true.B
    awPending := true.B
    wPending := true.B
    aw := wr.burst.bits
    aw.id := 1.U
  }
  io.axi.aw.valid := awPending
  io.axi.aw.bits := aw
  when(io.axi.aw.fire) { awPending := false.B; wBursts := wBursts + 1.U }
  io.axi.w.valid := wPending
  io.axi.w.bits.data := (wr.data << Cat(wr.address(1, 0), 0.U(3.W)))(31, 0)
  io.axi.w.bits.strb := MemoryAccess.strobes(wr.size, wr.address)
  io.axi.w.bits.last := cursor(1) === count(1)
  when(io.axi.w.fire) {
    wBytes := wBytes + PopCount(io.axi.w.bits.strb)
    when(io.axi.w.bits.last) { wPending := false.B }.otherwise { cursor(1) := cursor(1) + 1.U }
  }
  io.axi.b.ready := have && !awPending && !wPending
  val wb = io.axi.b.bits
  val wBad = wb.resp(1) || wb.id =/= 1.U
  when(io.axi.b.fire) {
    have := false.B
    when(wBad) { fault(1) := Mux(wb.resp === 3.U, 6.U, 5.U); address(1) := aw.addr }
  }
  wr.complete := io.axi.b.fire
  wr.abort := wBad
}
