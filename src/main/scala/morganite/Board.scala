package morganite

import chisel3._
import chisel3.util._

/** Registered address ownership at the input to the existing HBM width/clock converter. */
class MemoryMux(p: GpuParams) extends Module {
  val io = IO(new Bundle {
    val in = Vec(2, Flipped(new AxiMaster(p)))
    val mem = new AxiMaster(p)
  })
  val mr = io.mem
  val read = RegInit(false.B)
  val ro = Reg(UInt(1.W))
  val arPending = RegInit(false.B)
  val ar = Module(new RRArbiter(new AxiAddress(p), 2))
  for (i <- 0 until 2) ar.io.in(i) <> io.in(i).ar
  ar.io.out.ready := !read
  mr.ar.bits := RegEnable(ar.io.out.bits, ar.io.out.fire)
  mr.ar.valid := arPending
  when(ar.io.out.fire) { read := true.B; ro := ar.io.chosen; arPending := true.B }
  when(mr.ar.fire) { arPending := false.B }
  mr.r.ready := read && !arPending && io.in(ro).r.ready
  for (i <- 0 until 2) {
    io.in(i).r.valid := read && !arPending && ro === i.U && mr.r.valid
    io.in(i).r.bits := mr.r.bits
  }
  when(mr.r.fire && mr.r.bits.last) { read := false.B }

  val write = RegInit(false.B)
  val wo = Reg(UInt(1.W))
  val awPending = RegInit(false.B)
  val wDone = RegInit(false.B)
  val aw = Module(new RRArbiter(new AxiAddress(p), 2))
  for (i <- 0 until 2) aw.io.in(i) <> io.in(i).aw
  aw.io.out.ready := !write
  mr.aw.bits := RegEnable(aw.io.out.bits, aw.io.out.fire)
  mr.aw.valid := awPending
  when(aw.io.out.fire) { write := true.B; wo := aw.io.chosen; awPending := true.B; wDone := false.B }
  when(mr.aw.fire) { awPending := false.B }
  mr.w.valid := write && !wDone && io.in(wo).w.valid
  mr.w.bits := io.in(wo).w.bits
  when(mr.w.fire && mr.w.bits.last) { wDone := true.B }
  mr.b.ready := write && !awPending && wDone && io.in(wo).b.ready
  for (i <- 0 until 2) {
    io.in(i).w.ready := write && !wDone && wo === i.U && mr.w.ready
    io.in(i).b.valid := write && !awPending && wDone && wo === i.U && mr.b.valid
    io.in(i).b.bits := mr.b.bits
  }
  when(mr.b.fire) { write := false.B }
}

/** Route the 4 KiB GPU page to AXI-Lite; all other peripheral accesses retain their AXI burst. */
class PeripheralDecode(p: GpuParams) extends Module {
  val io = IO(new Bundle {
    val host = Flipped(new AxiMaster(p))
    val legacy = new AxiMaster(p)
    val gpu = Flipped(new AxiLiteSlave)
  })
  val src = io.host
  val old = io.legacy
  val lite = io.gpu
  def gpuPage(a: AxiAddress) = a.addr(31, 12) === "h1fe20".U
  def supported(a: AxiAddress) = !a.len.orR && a.size === 2.U && !a.addr(1, 0).orR && !a.lock && a.burst =/= 3.U
  val rBusy, rAddress, rGpu, rError = RegInit(false.B)
  val ar = Reg(new AxiAddress(p))
  val left = Reg(UInt(8.W))
  src.ar.ready := !rBusy
  when(src.ar.fire) {
    ar := src.ar.bits; rBusy := true.B; rAddress := true.B
    rGpu := gpuPage(src.ar.bits); rError := gpuPage(src.ar.bits) && !supported(src.ar.bits)
    left := src.ar.bits.len
  }
  old.ar.valid := rAddress && !rGpu
  old.ar.bits := ar
  lite.ar.valid := rAddress && rGpu && !rError
  lite.ar.bits.addr := ar.addr
  lite.ar.bits.prot := ar.prot
  when(old.ar.fire || lite.ar.fire || (rAddress && rError)) { rAddress := false.B }
  val returnR = rBusy && !rAddress
  lite.r.ready := returnR && rGpu && !rError && src.r.ready
  old.r.ready := returnR && !rGpu && src.r.ready
  src.r.valid := returnR && Mux(rGpu, rError || lite.r.valid, old.r.valid)
  src.r.bits := old.r.bits
  when(rGpu) {
    src.r.bits.data := Mux(rError, 0.U, lite.r.bits.data)
    src.r.bits.resp := Mux(rError, 2.U, lite.r.bits.resp)
    src.r.bits.id := ar.id
    src.r.bits.last := !left.orR
  }
  when(src.r.fire) { left := left - 1.U; when(src.r.bits.last) { rBusy := false.B } }
  val wBusy, wAddress, wGpu, wError, wDone = RegInit(false.B)
  val aw = Reg(new AxiAddress(p))
  src.aw.ready := !wBusy
  when(src.aw.fire) {
    aw := src.aw.bits; wBusy := true.B; wAddress := true.B; wDone := false.B
    wGpu := gpuPage(src.aw.bits); wError := gpuPage(src.aw.bits) && !supported(src.aw.bits)
  }
  old.aw.valid := wAddress && !wGpu
  old.aw.bits := aw
  lite.aw.valid := wAddress && wGpu && !wError
  lite.aw.bits.addr := aw.addr
  lite.aw.bits.prot := aw.prot
  when(old.aw.fire || lite.aw.fire || (wAddress && wError)) { wAddress := false.B }
  val dataPhase = wBusy && !wDone
  old.w.valid := dataPhase && !wGpu && src.w.valid
  old.w.bits := src.w.bits
  lite.w.valid := dataPhase && wGpu && !wError && src.w.valid
  lite.w.bits.data := src.w.bits.data
  lite.w.bits.strb := src.w.bits.strb
  src.w.ready := dataPhase && Mux(wGpu, wError || lite.w.ready, old.w.ready)
  when(src.w.fire && src.w.bits.last) { wDone := true.B }
  val returnB = wBusy && !wAddress && wDone
  lite.b.ready := returnB && wGpu && !wError && src.b.ready
  old.b.ready := returnB && !wGpu && src.b.ready
  src.b.valid := returnB && Mux(wGpu, wError || lite.b.valid, old.b.valid)
  src.b.bits.id := Mux(wGpu, aw.id, old.b.bits.id)
  src.b.bits.resp := Mux(wGpu, Mux(wError, 2.U, lite.b.bits), old.b.bits.resp)
  when(src.b.fire) { wBusy := false.B }
}

class MorganiteBoard(p: GpuParams) extends Module {
  private val host = p.copy(hbmPorts = 1, axiDataBits = 32, readOutstanding = 1, writeOutstanding = 1)
  val io = IO(new Bundle {
    val cpuMemory, peripheral = Flipped(new AxiMaster(host))
    val hbm, legacy = new AxiMaster(host)
    val gpuHbm1 = if (p.hbmPorts == 2) Some(new AxiMaster(p)) else None
    val gpuHbm = if (p.axiDataBits == 256) Some(new AxiMaster(p)) else None
  })
  val gpu = Module(new Gpu(p))
  if (p.axiDataBits == 256) {
    io.gpuHbm1.foreach(_ <> gpu.io.dma1.get)
    io.hbm <> io.cpuMemory
    io.gpuHbm.get <> gpu.io.dma
  } else {
    val mux = Module(new MemoryMux(p))
    mux.io.in(0) <> io.cpuMemory
    mux.io.in(1) <> gpu.io.dma
    io.hbm <> mux.io.mem
  }
  val decode = Module(new PeripheralDecode(host))
  decode.io.host <> io.peripheral
  io.legacy <> decode.io.legacy
  gpu.io.host <> decode.io.gpu
}
