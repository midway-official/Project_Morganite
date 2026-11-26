package morganite

import chisel3._
import chisel3.util._

class SegmentRequest extends Bundle {
  val address = UInt(32.W)
  val size = UInt(3.W)
  val store = Bool()
  val data = UInt(256.W)
  val strb = UInt(32.W)
}

class SegmentReply extends Bundle {
  val data = UInt(256.W)
  val fault = UInt(4.W)
}

/** One per vector source. Only a packed segment crosses the arbitration tree. */
class SegmentSource(p: GpuParams) extends Module {
  val io = IO(new Bundle {
    val core = Flipped(new VectorPort(p))
    val base, limit, roBase, roEnd, codeBase, codeEnd = Input(UInt(32.W))
    val issue = Decoupled(new SegmentRequest)
    val response = Flipped(Decoupled(new SegmentReply))
    val cancel = Input(Bool())
    val idle = Output(Bool())
  })
  val idle :: matchLine :: issue :: waitData :: finish :: Nil = Enum(5)
  val state = RegInit(idle)
  val req = Reg(new VectorRequest(p))
  val reply = Reg(new VectorReply(p))
  val remaining = Reg(UInt(p.warpWidth.W))
  val cancelled = RegInit(false.B)
  when(state === idle) { cancelled := false.B }
  when(io.cancel) { cancelled := true.B }
  io.idle := state === idle
  io.core.req.ready := io.idle
  when(io.core.req.fire) {
    val in = io.core.req.bits
    val bad = VecInit(in.addr.map { addr =>
      val last = addr +& MemoryAccess.lastOffset(in.size)
      def overlaps(lo: UInt, hi: UInt) = lo < hi && addr < hi && last >= lo
      val protectedWrite = if (p.iCacheBytes > 0 || p.dCacheBytes > 0)
        in.store && (overlaps(io.codeBase, io.codeEnd) || overlaps(io.roBase, io.roEnd))
      else false.B
      MemoryAccess.invalid(addr, in.size, io.base, io.limit) || protectedWrite
    }).asUInt & in.mask
    req := in
    remaining := in.mask
    reply := 0.U.asTypeOf(reply)
    reply.fault := Mux(bad.orR, 3.U, 0.U)
    reply.address := Mux1H(PriorityEncoderOH(bad), in.addr)
    state := Mux(bad.orR || !in.mask.orR, finish, matchLine)
  }
  val first = Mux1H(PriorityEncoderOH(remaining), req.addr)(31, 0)
  val line = Cat(first(31,5), 0.U(5.W))
  val full = line >= io.base && (line +& 32.U) <= io.limit
  val candidates = VecInit(req.addr.zipWithIndex.map { case (a,i) =>
    remaining(i) && Mux(full, a(31,5) === first(31,5), a === first)
  })
  val grant = VecInit(req.addr.zipWithIndex.map { case (a,i) =>
    val duplicate = (0 until i).map(j => candidates(j) && req.addr(j)(4,0) === a(4,0)).foldLeft(false.B)(_ || _)
    candidates(i) && !(req.store && duplicate)
  }).asUInt
  val selected = RegEnable(grant, state === matchLine)
  val segmentAddress = RegEnable(Mux(full, line, first), state === matchLine)
  val segmentSize = RegEnable(Mux(full, 5.U, req.size), state === matchLine)
  when(state === matchLine) { state := issue }
  val stop = cancelled || io.cancel
  when((state === matchLine || state === issue) && stop) { state := finish; reply.fault := 7.U }
  io.issue.valid := state === issue && !stop
  io.issue.bits.address := segmentAddress
  io.issue.bits.size := segmentSize
  io.issue.bits.store := req.store
  val words = (0 until 8).map { word =>
    val lanes = req.addr.zipWithIndex.map { case (a,i) =>
      selected(i) && a(4,2) === word.U
    }
    val byteMasks = lanes.zipWithIndex.map { case (yes,i) =>
      Mux(yes, MemoryAccess.strobes(req.size, req.addr(i)), 0.U(4.W))
    }
    val payload = VecInit((0 until 4).map { b =>
      Mux1H(byteMasks.zipWithIndex.map { case (m,i) =>
        val offset = (b.U(2.W) - req.addr(i)(1,0))(1,0)
        m(b) -> (req.data(i) >> Cat(offset, 0.U(3.W)))(7,0)
      })
    })
    (byteMasks.reduce(_ | _), payload.asUInt)
  }
  io.issue.bits.strb := Cat(words.reverse.map(_._1))
  io.issue.bits.data := Cat(words.reverse.map(_._2))
  when(io.issue.fire) { state := waitData }
  io.response.ready := state === waitData
  when(io.response.fire) {
    val r = io.response.bits
    for (i <- 0 until p.warpWidth) {
      val a = req.addr(i)
      val word = r.data.asTypeOf(Vec(8, UInt(32.W)))(a(4,2))
      when(selected(i)) { reply.data(i) := MemoryAccess.unpack(word, a, req.size, req.unsigned) }
    }
    when(r.fault.orR) { reply.fault := r.fault; reply.address := first }
    val rest = remaining & ~selected
    remaining := rest
    when(stop && !r.fault.orR) { reply.fault := 7.U }
    state := Mux(stop || r.fault.orR || !rest.orR, finish, matchLine)
  }
  io.core.rsp.valid := state === finish
  io.core.rsp.bits := reply
  when(io.core.rsp.fire) { state := idle }
}

class WideDma(p: GpuParams) extends VectorDma(p) {
  require(p.axiDataBits == 256)
  val sources = Seq.tabulate(owners) { i =>
    val s = Module(new SegmentSource(p)).io
    s.cancel := io.cancel.get
    s.core <> io.clients(i)
    s.base := io.base
    s.limit := io.limit
    s.codeBase := io.codeBase
    s.codeEnd := io.codeEnd
    s.roBase := io.roBase
    s.roEnd := io.roEnd
    s
  }
  io.idle := sources.map(_.idle).reduce(_ && _)
  val ports = Seq(io.axi) ++ io.axi1.toSeq
  val ownerBits = log2Ceil(owners).max(1)
  class Packet extends SegmentRequest { val owner = UInt(ownerBits.W) }
  class Return extends SegmentReply { val owner = UInt(ownerBits.W) }

  // Loader has its own branch. All traffic belonging to an SM stays in its cluster.
  val loader = p.numSms
  val clusters = (0 until p.numSms).grouped(4).map { sms =>
    sms.flatMap(sm => (0 until owners).filter(i => i == sm || (i > loader && (i-loader-1)%p.numSms == sm)))
  }.toSeq :+ Seq(loader)
  val ready = Seq.fill(p.hbmPorts*2)(Wire(Vec(owners, Bool())))
  val returned = Seq.fill(p.hbmPorts*2)(Wire(Vec(owners, Valid(new SegmentReply))))
  val receivedBytes = Wire(Vec(p.hbmPorts, UInt(7.W)))

  for ((axi, bank) <- ports.zipWithIndex; (capacity, dir) <- Seq(p.readOutstanding, p.writeOutstanding).zipWithIndex) {
    val path = 2*bank + dir
    val write = dir == 1
    val root = Module(new RRArbiter(new Packet, clusters.size)).io
    val returns = Wire(Decoupled(new Return))
    val canReturn = Wire(Vec(clusters.size, Bool()))
    for ((members, k) <- clusters.zipWithIndex) {
      val local = Module(new RRArbiter(new Packet, members.size)).io
      for ((owner, j) <- members.zipWithIndex) {
        val source = sources(owner).issue
        val in = local.in(j)
        val matchBank = if (p.hbmPorts == 1) true.B else source.bits.address(12) === bank.U
        in.valid := matchBank && source.valid && source.bits.store === write.B
        in.bits.address := source.bits.address
        in.bits.size := source.bits.size
        in.bits.store := source.bits.store
        in.bits.data := source.bits.data
        in.bits.strb := source.bits.strb
        in.bits.owner := owner.U
        ready(path)(owner) := matchBank && in.ready && source.bits.store === write.B
      }
      root.in(k) <> Queue(local.out, 2, pipe=false, flow=false)
      val q = Module(new Queue(new Return, 2)).io
      val target = members.map(i => returns.bits.owner === i.U).reduce(_ || _)
      q.enq.bits := returns.bits
      q.enq.valid := returns.valid && target
      canReturn(k) := target && q.enq.ready
      q.deq.ready := true.B // source reserves its reply slot before injection
      for (i <- members) {
        val out = returned(path)(i)
        out.valid := q.deq.valid && q.deq.bits.owner === i.U
        out.bits.data := q.deq.bits.data
        out.bits.fault := q.deq.bits.fault
      }
    }
    returns.ready := canReturn.asUInt.orR
    val used = RegInit(VecInit(Seq.fill(capacity)(false.B)))
    val ownersById = Reg(Vec(capacity, UInt(ownerBits.W)))
    val sizeById = Reg(Vec(capacity, UInt(3.W)))
    val free = ~used.asUInt
    val next = if (capacity == 1) 0.U else PriorityEncoder(free)
    val address = if (write) axi.aw else axi.ar
    val addrValid = RegInit(false.B)
    val dataValid = RegInit(false.B)
    val packet = Reg(new Packet)
    val id = Reg(UInt(p.axiIdBits.W))
    // AW and W drain independently. Admission order is the W stream order.
    root.out.ready := free.orR && (!addrValid || address.ready) &&
      (if (write) !dataValid || axi.w.ready else true.B)
    address.valid := addrValid
    address.bits := 0.U.asTypeOf(address.bits)
    address.bits.addr := packet.address
    address.bits.size := packet.size
    address.bits.burst := 1.U
    address.bits.id := id
    when(address.fire) { addrValid := false.B }
    if (write) {
      axi.w.valid := dataValid
      axi.w.bits.strb := packet.strb
      axi.w.bits.data := packet.data
      axi.w.bits.last := true.B
      when(axi.w.fire) { dataValid := false.B }
    }
    when(root.out.fire) {
      used(next) := true.B
      ownersById(next) := root.out.bits.owner
      sizeById(next) := root.out.bits.size
      id := next
      packet := root.out.bits
      addrValid := true.B
      if (write) { dataValid := true.B }
    }
    val responseId = if (write) axi.b.bits.id else axi.r.bits.id
    val slot = if (capacity == 1) 0.U else responseId(log2Ceil(capacity)-1,0)
    val known = responseId < capacity.U && used(slot)
    val valid = if (write) axi.b.valid else axi.r.valid
    val resp = if (write) axi.b.bits.resp else axi.r.bits.resp
    val last = if (write) true.B else axi.r.bits.last
    val error = if (write) false.B else {
      val malformed = RegInit(VecInit(Seq.fill(capacity)(false.B)))
      when(axi.r.fire && known) { malformed(slot) := !last }
      receivedBytes(bank) := 1.U << sizeById(slot)
      malformed(slot)
    }
    returns.valid := valid && known && last
    returns.bits.owner := ownersById(slot)
    returns.bits.data := (if (write) 0.U else axi.r.bits.data)
    returns.bits.fault := Mux(resp === 3.U, 6.U, Mux(resp(1) || error, 5.U, 0.U))
    val accept = known && (!last || returns.ready)
    if (write) axi.b.ready := accept else axi.r.ready := accept
    when(valid) { assert(known, "AXI response without a reserved ID") }
    when(returns.fire) { used(slot) := false.B }
  }
  for ((s,i) <- sources.zipWithIndex) {
    s.issue.ready := ready.map(_(i)).reduce(_ || _)
    val replies = returned.map(_(i))
    s.response.valid := replies.map(_.valid).reduce(_ || _)
    s.response.bits := Mux1H(replies.map(r => r.valid -> r.bits))
    when(s.response.valid) { assert(s.response.ready && PopCount(replies.map(_.valid)) === 1.U) }
  }
  def counter(fire: Bool, increment: UInt): UInt = {
    val n = RegInit(0.U(32.W))
    when(fire) { n := n + increment }
    n
  }
  io.readBursts := ports.map(a => counter(a.ar.fire, 1.U)).reduce(_ + _)
  io.writeBursts := ports.map(a => counter(a.aw.fire, 1.U)).reduce(_ + _)
  io.readBytes := ports.zipWithIndex.map { case (a,i) => counter(a.r.fire, receivedBytes(i)) }.reduce(_ + _)
  io.writeBytes := ports.map(a => counter(a.w.fire, PopCount(a.w.bits.strb))).reduce(_ + _)
}
