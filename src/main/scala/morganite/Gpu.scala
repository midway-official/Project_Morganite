package morganite

import chisel3._
import chisel3.util._

class LiteAddress extends Bundle {
  val addr = UInt(32.W)
  val prot = UInt(3.W)
}
class AxiLiteSlave extends Bundle {
  val aw = Flipped(Decoupled(new LiteAddress))
  val w = Flipped(Decoupled(new Bundle { val data = UInt(32.W); val strb = UInt(4.W) }))
  val b = Decoupled(UInt(2.W))
  val ar = Flipped(Decoupled(new LiteAddress))
  val r = Decoupled(new Bundle { val data = UInt(32.W); val resp = UInt(2.W) })
}
object Abi {
  val version = 0x10000
  val descriptorMagic = 0x3144474d // "MGD1" in memory
  val dmaFloor = 0x01000000
  val dmaCeiling = 0x1fe00000
}

class Gpu(p: GpuParams) extends Module {
  require(p.numSms <= 32 && p.maxThreads <= 255, "ABI capability fields")
  val io = IO(new Bundle {
    val host = new AxiLiteSlave
    val dma = new AxiMaster(p)
  })
  val idle :: descReq :: descRsp :: validate :: codeReq :: codeRsp :: load :: dispatch :: drain :: Nil = Enum(9)
  val state = RegInit(idle)
  val busy = state =/= idle
  val base = RegInit(0.U(32.W))
  val limit = RegInit(0.U(32.W))
  val descriptor = RegInit(0.U(32.W))
  val enable = RegInit(((BigInt(1) << p.numSms) - 1).U(p.numSms.W))
  val sequence, completed, faultSeq, faultAddress = RegInit(0.U(32.W))
  val fault = RegInit(0.U(4.W))
  val finished = RegInit(false.B)
  val cancel = RegInit(false.B)
  val elapsed = RegInit(0.U(32.W))
  val desc = Reg(Vec(8, UInt(32.W)))
  val index = Reg(UInt((p.pcBits + 1).W))
  val lane = Reg(UInt(log2Ceil(p.warpWidth).max(1).W))
  val block = Reg(Vec(p.warpWidth, UInt(32.W)))
  val nextGroup = RegInit(0.U(32.W))
  val occupied = RegInit(VecInit(Seq.fill(p.numSms)(false.B)))
  val stats = RegInit(VecInit(Seq.fill(p.numSms)(VecInit(Seq.fill(5)(0.U(32.W))))))
  val errorCondition = cancel || fault.orR
  def firstFault(code: UInt, address: UInt): Unit = {
    when(!fault.orR) { fault := code; faultAddress := address }
  }
  val dma = Module(new Dma(p))
  val sms = (0 until p.numSms).map(i => Module(new Sm(p, i)))
  val dataCaches = Seq.fill(if (p.dCacheBytes > 0) p.numSms else 0)(Module(new DataL1(p)))
  val roBase, roEnd = RegInit(0.U(32.W))
  io.dma <> dma.io.axi
  dma.io.base := base
  dma.io.limit := limit
  dma.io.roBase := roBase
  dma.io.roEnd := roEnd
  dma.io.codeBase := desc(2)
  dma.io.codeEnd := desc(2) + (desc(3) << 2)
  val counters = Seq(dma.io.readBursts, dma.io.writeBursts, dma.io.readBytes, dma.io.writeBytes)
  val countersAtStart = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))
  when(busy) { elapsed := elapsed + 1.U }

  // AXI4-Lite channels can arrive independently, including W before AW.
  val host = io.host
  val aw = Module(new Queue(new LiteAddress, 1))
  val wd = Module(new Queue(chiselTypeOf(host.w.bits), 1))
  aw.io.enq <> host.aw
  wd.io.enq <> host.w
  val bValid = RegInit(false.B)
  val bResp = Reg(UInt(2.W))
  host.b.valid := bValid
  host.b.bits := bResp
  val canWrite = !bValid || host.b.ready
  aw.io.deq.ready := wd.io.deq.valid && canWrite
  wd.io.deq.ready := aw.io.deq.valid && canWrite
  val wa = aw.io.deq.bits.addr(11, 0)
  val value = wd.io.deq.bits.data
  val strobes = wd.io.deq.bits.strb
  val byteMask = FillInterleaved(8, strobes)
  def merge(old: UInt): UInt = (old & ~byteMask) | (value & byteMask)
  val submit = WireDefault(false.B)
  when(host.b.fire) { bValid := false.B }
  when(aw.io.deq.fire) {
    bValid := true.B
    bResp := 2.U
    when(wa === 0x0c.U && !busy) { descriptor := merge(descriptor); bResp := 0.U }
    when(wa === 0x30.U && !busy) { base := merge(base); bResp := 0.U }
    when(wa === 0x34.U && !busy) { limit := merge(limit); bResp := 0.U }
    when(wa === 0x38.U && !busy) { enable := merge(enable); bResp := 0.U }
    if (p.dCacheBytes > 0) {
      when(wa === 0x54.U && !busy) { roBase := merge(roBase); bResp := 0.U }
      when(wa === 0x58.U && !busy) { roEnd := merge(roEnd); bResp := 0.U }
    }
    when(wa === 0x2c.U && strobes === 15.U && value === 1.U) {
      when(busy) { cancel := true.B }
      bResp := 0.U
    }
    when(wa === 0x10.U && !busy && strobes === 15.U && value.orR && value =/= sequence) {
      submit := true.B
      bResp := 0.U
    }
  }
  val rdMap = Seq(
    0x00 -> "h4d4f5247".U, 0x04 -> Abi.version.U,
    0x08 -> Cat(p.warpSlots.U(8.W), p.warpWidth.U(8.W), p.lanes.U(8.W), p.numSms.U(8.W)),
    0x0c -> descriptor, 0x14 -> Cat(fault.orR && finished, finished, busy),
    0x18 -> completed, 0x1c -> fault, 0x20 -> faultAddress, 0x24 -> faultSeq,
    0x28 -> sequence, 0x30 -> base, 0x34 -> limit, 0x38 -> enable, 0x3c -> elapsed) ++
    counters.zipWithIndex.map { case (v, i) => (0x40 + 4 * i) -> (v - countersAtStart(i)) } ++
    (for (i <- 0 until p.numSms; j <- 0 until 5) yield (0x100 + i * 0x20 + j * 4) -> stats(i)(j)) ++
    Seq(0x50 -> ((if (p.iCacheBytes > 0) 1 else 0) | (if (p.dCacheBytes > 0) 2 else 0) |
      (p.tensorK << 8)).U, 0x54 -> roBase, 0x58 -> roEnd) ++
    sms.zipWithIndex.flatMap { case (sm,i) =>
      val d = dataCaches.lift(i)
      Seq(sm.io.iHits, sm.io.iMisses, d.map(_.io.hits).getOrElse(0.U),
        d.map(_.io.misses).getOrElse(0.U)).zipWithIndex.map { case (v,j) => (0x600 + i*16 + j*4) -> v }
    }
  val rValid = RegInit(false.B)
  val rData = Reg(UInt(32.W))
  val rResp = Reg(UInt(2.W))
  host.ar.ready := !rValid || host.r.ready
  host.r.valid := rValid
  host.r.bits.data := rData
  host.r.bits.resp := rResp
  when(host.r.fire) { rValid := false.B }
  when(host.ar.fire) {
    val offset = host.ar.bits.addr(11, 0)
    val matches = rdMap.map { case (k, v) => (offset === k.U) -> v }
    rValid := true.B
    rData := Mux1H(matches)
    rResp := Mux(matches.map(_._1).reduce(_ || _), 0.U, 2.U)
  }
  when(submit) {
    sequence := value
    fault := 0.U
    faultAddress := 0.U
    faultSeq := 0.U
    cancel := false.B
    finished := false.B
    nextGroup := 0.U
    elapsed := 0.U
    index := 0.U
    stats.foreach(_.foreach(_ := 0.U))
    countersAtStart := VecInit(counters)
    val validWindow = base >= Abi.dmaFloor.U && limit <= Abi.dmaCeiling.U && base < limit
    val descOK = !descriptor(1, 0).orR && descriptor >= base && (descriptor +& 32.U) <= limit
    val validRo = roBase === roEnd || (roBase >= base && roBase < roEnd && roEnd <= limit)
    when(!validWindow || !validRo || !descOK || !enable.orR) {
      fault := 1.U
      faultAddress := descriptor
      state := drain
    }.otherwise { state := descReq }
  }
  val loader = dma.io.clients(p.numSms)
  val isDesc = state === descReq || state === descRsp
  val count = Mux(isDesc, 8.U, desc(3))
  val ptr = Mux(isDesc, descriptor, desc(2))
  val countLeft = count - index
  val chunk = Mux(countLeft < p.warpWidth.U, countLeft, p.warpWidth.U)
  loader.req.valid := state === descReq || state === codeReq
  loader.req.bits := 0.U.asTypeOf(loader.req.bits)
  loader.req.bits.size := 2.U
  loader.req.bits.unsigned := true.B
  loader.req.bits.mask := VecInit((0 until p.warpWidth).map(i => i.U < chunk)).asUInt
  for (i <- 0 until p.warpWidth) { loader.req.bits.addr(i) := ptr +& ((index +& i.U) << 2) }
  loader.rsp.ready := state === descRsp || state === codeRsp
  when(loader.req.fire) { state := Mux(isDesc, descRsp, codeRsp) }
  when(loader.rsp.fire) {
    when(loader.rsp.bits.fault.orR || errorCondition) {
      when(loader.rsp.bits.fault.orR) { firstFault(loader.rsp.bits.fault, loader.rsp.bits.address) }
      state := drain
    }.elsewhen(isDesc) {
      for (i <- 0 until p.warpWidth) {
        when(i.U < chunk) { desc((index + i.U)(2, 0)) := loader.rsp.bits.data(i) }
      }
      index := index + chunk
      state := Mux(index + chunk >= 8.U, validate, descReq)
    }.otherwise {
      block := loader.rsp.bits.data
      lane := 0.U
      state := load
    }
  }
  when(state === validate) {
    val programEnd = desc(2) +& (desc(3) << 2)
    val legal = desc(0) === Abi.descriptorMagic.U && desc(1) === Abi.version.U &&
      desc(2) >= base && !desc(2)(1, 0).orR && programEnd <= limit &&
      desc(3).orR && desc(3) <= p.programWords.U && desc(4) >= base && desc(4) < limit &&
      desc(5).orR && desc(6).orR && desc(6) <= p.maxThreads.U && desc(7) === 0.U
    when(!legal || errorCondition) {
      when(!legal) { firstFault(1.U, descriptor) }
      state := drain
    }.otherwise { index := 0.U; state := (if (p.iCacheBytes > 0) dispatch else codeReq) }
  }
  when(state === load) {
    when(errorCondition) { state := drain }
      .elsewhen(lane +& 1.U === chunk) {
        index := index + chunk
        state := Mux(index + chunk === desc(3), dispatch, codeReq)
      }.otherwise { lane := lane + 1.U }
  }
  val free = VecInit(sms.zipWithIndex.map { case (sm, i) => sm.io.start.ready && enable(i) }).asUInt
  val chosen = PriorityEncoderOH(free)
  val terminal = VecInit(sms.map(sm => sm.io.result.fire && sm.io.result.bits.fault.orR)).asUInt
  val first = Mux1H(PriorityEncoderOH(terminal), sms.map(_.io.result.bits))
  when(terminal.orR) { firstFault(first.fault, first.address) }
  // Registered error state stops later admissions. An already accepted group drains normally.
  for ((sm, i) <- sms.zipWithIndex) {
    sm.io.invalidate := submit
    sm.io.fetch.foreach(_ <> dma.io.clients(p.numSms + 1 + i))
    if (p.dCacheBytes > 0) {
      val c = dataCaches(i).io
      c.core <> sm.io.global
      dma.io.clients(i) <> c.mem
      c.roBase := roBase
      c.roEnd := roEnd
      c.invalidate := submit
    } else { dma.io.clients(i) <> sm.io.global }
    sm.io.program.valid := state === load && !errorCondition
    sm.io.program.bits.address := index + lane
    sm.io.program.bits.data := block(lane)
    sm.io.cancel := errorCondition
    sm.io.start.valid := state === dispatch && !errorCondition && nextGroup < desc(5) && chosen(i)
    sm.io.start.bits.id := nextGroup
    sm.io.start.bits.argument := desc(4)
    sm.io.start.bits.programBase := desc(2)
    sm.io.start.bits.words := desc(3)
    sm.io.start.bits.threads := desc(6)
    sm.io.result.ready := true.B
    when(sm.io.start.fire) { occupied(i) := true.B; nextGroup := nextGroup + 1.U }
    when(sm.io.result.fire) {
      occupied(i) := false.B
      val g = sm.io.result.bits
      for ((v, j) <- Seq(g.cycles, !g.fault.orR, g.fpOps, g.fmaOps, g.memStalls).zipWithIndex) {
        stats(i)(j) := stats(i)(j) + v
      }
    }
  }
  when(state === dispatch && (errorCondition || nextGroup === desc(5))) { state := drain }
  when(state === drain && !occupied.asUInt.orR && dma.io.idle) {
    when(errorCondition) {
      when(cancel && !fault.orR) { fault := 7.U; faultAddress := 0.U }
      faultSeq := sequence
    }.otherwise { completed := sequence }
    finished := true.B
    state := idle
  }
}
