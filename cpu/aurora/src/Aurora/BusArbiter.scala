package aurora

import chisel3._
import chisel3.util._

class BufferedReadDescriptor extends Bundle {
  val ownerIfu = Bool()
  val addr = UInt(32.W)
  val size = UInt(3.W)
  val len = UInt(8.W)
  val burst = UInt(2.W)
}

class BufferedWriteDescriptor extends Bundle {
  val addr = UInt(32.W)
  val size = UInt(3.W)
  val len = UInt(8.W)
  val burst = UInt(2.W)
}

class BufferedWriteBeat extends Bundle {
  val data = UInt(32.W)
  val strb = UInt(4.W)
  val last = Bool()
}

/** Registered AXI W head with non-flow, non-pipe capacity. */
class BufferedWriteBeatQueue(depth: Int) extends Module {
  require(depth >= 1)
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new BufferedWriteBeat))
    val deq = Decoupled(new BufferedWriteBeat)
    val count = Output(UInt(log2Ceil(depth + 1).W))
  })
  val slots = Reg(Vec(depth, new BufferedWriteBeat))
  val count = RegInit(0.U(log2Ceil(depth + 1).W))
  io.count := count
  io.enq.ready := count =/= depth.U
  io.deq.valid := count =/= 0.U
  io.deq.bits := slots(0)
  val remove = io.deq.fire
  val append = io.enq.fire
  val remaining = count - remove.asUInt
  for (slot <- 0 until depth) {
    when(append && remaining === slot.U) {
      slots(slot) := io.enq.bits
    }.otherwise {
      if (slot + 1 < depth) {
        when(remove && remaining > slot.U) { slots(slot) := slots(slot + 1) }
      }
    }
  }
  count := remaining + append.asUInt
}

/** Read-only observation; no event feeds transaction admission or arbitration. */
class BusPerfEvents(implicit p: AuroraConfig) extends Bundle {
  val lsuReadOccupancy = UInt(log2Ceil(p.axiLsuReadMaxOutstanding + 1).W)
  val writeOccupancy = UInt(log2Ceil(p.axiWriteMaxOutstanding + 1).W)
  val readDescriptorOccupancy = UInt(log2Ceil(p.axiReadRequestQueueDepth + 1).W)
  val writeDataOccupancy = UInt(log2Ceil(p.axiWriteDataQueueDepth + 1).W)
  val lsuReadCreditBlocked = Bool()
  val readDescriptorBlocked = Bool()
  val writeCreditBlocked = Bool()
  val arBlocked = Bool()
  val awBlocked = Bool()
  val wBlocked = Bool()
  val rBlocked = Bool()
}

/** 在 IFU、LSU 和 DCache 请求之间仲裁共享 AXI 主端。
  *
  * 读写通道独立仲裁；已授权请求在整个 burst 完成前保持归属，
  * 并为返回通道保留请求方身份。
  */
class BusArbiter(implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val lsu = Flipped(new BusClientIO)
    val ifu = Flipped(new BusClientIO)
    val store = Flipped(new BusWriteClientIO)
    val axi = new Axi4MasterIO
    val perf = Output(new BusPerfEvents)
  })

  private val readDescriptorDepth = p.axiReadRequestQueueDepth
  private val lsuReadCreditDepth = p.axiLsuReadMaxOutstanding
  private val writeCreditDepth = p.axiWriteMaxOutstanding

  val readDescriptors = Module(new Queue(
    new BufferedReadDescriptor, readDescriptorDepth))
  val lsuReadCount = RegInit(0.U(log2Ceil(lsuReadCreditDepth + 1).W))
  val ifuOutstanding = RegInit(false.B)

  val lsuCreditAvailable = lsuReadCount < lsuReadCreditDepth.U
  val lsuReadEligible = io.lsu.requ_valid && lsuCreditAvailable
  val ifuReadEligible = io.ifu.requ_valid && !ifuOutstanding
  val selectLsuRead = lsuReadEligible
  val selectIfuRead = !selectLsuRead && ifuReadEligible

  readDescriptors.io.enq.valid := selectLsuRead || selectIfuRead
  readDescriptors.io.enq.bits.ownerIfu := selectIfuRead
  readDescriptors.io.enq.bits.addr := Mux(selectLsuRead,
    io.lsu.requ_addr, io.ifu.requ_addr)
  readDescriptors.io.enq.bits.size := Mux(selectLsuRead,
    io.lsu.requ_size, io.ifu.requ_size)
  readDescriptors.io.enq.bits.len := Mux(selectLsuRead,
    io.lsu.requ_len, io.ifu.requ_len)
  readDescriptors.io.enq.bits.burst := Mux(selectLsuRead,
    io.lsu.requ_burst, io.ifu.requ_burst)

  io.lsu.requ_ready := readDescriptors.io.enq.ready && selectLsuRead
  io.ifu.requ_ready := readDescriptors.io.enq.ready && selectIfuRead
  val captureLsuRead = io.lsu.requ_valid && io.lsu.requ_ready
  val captureIfuRead = io.ifu.requ_valid && io.ifu.requ_ready

  io.axi.arid := Mux(readDescriptors.io.deq.bits.ownerIfu,
    BusDeviceId.IFU.U, BusDeviceId.LSU.U)
  io.axi.araddr := readDescriptors.io.deq.bits.addr
  io.axi.arlen := readDescriptors.io.deq.bits.len
  io.axi.arsize := readDescriptors.io.deq.bits.size
  io.axi.arburst := readDescriptors.io.deq.bits.burst
  io.axi.arlock := false.B
  io.axi.arcache := "b0010".U
  io.axi.arprot := 0.U
  io.axi.arqos := 0.U
  io.axi.arvalid := readDescriptors.io.deq.valid
  readDescriptors.io.deq.ready := io.axi.arready

  val lsuResponseRaw = io.axi.rvalid &&
    io.axi.rid === BusDeviceId.LSU.U
  val ifuResponseRaw = io.axi.rvalid &&
    io.axi.rid === BusDeviceId.IFU.U
  io.lsu.resp_valid := lsuResponseRaw
  io.lsu.resp_rdata := io.axi.rdata
  io.lsu.resp_last := io.axi.rlast
  io.ifu.resp_valid := ifuResponseRaw
  io.ifu.resp_rdata := io.axi.rdata
  io.ifu.resp_last := io.axi.rlast
  io.axi.rready := Mux(lsuResponseRaw,
    io.lsu.resp_ready,
    Mux(ifuResponseRaw, io.ifu.resp_ready, false.B))

  val lsuResponseLast = lsuResponseRaw && io.axi.rready && io.axi.rlast
  val ifuResponseLast = ifuResponseRaw && io.axi.rready && io.axi.rlast
  when(captureLsuRead =/= lsuResponseLast) {
    lsuReadCount := Mux(captureLsuRead,
      lsuReadCount + 1.U, lsuReadCount - 1.U)
  }
  when(captureIfuRead) {
    ifuOutstanding := true.B
  }.elsewhen(ifuResponseLast) {
    ifuOutstanding := false.B
  }

  val writeDescriptors = Module(new Queue(
    new BufferedWriteDescriptor, writeCreditDepth))
  val writeInputContexts = Module(new Queue(UInt(8.W), writeCreditDepth))
  // No pipe/flow bypass: AXI ready must not reach the upstream control path.
  val writeDataQueue = Module(new BufferedWriteBeatQueue(p.axiWriteDataQueueDepth))
  val writeResponseContexts = Module(new Queue(
    new BufferedWriteDescriptor, writeCreditDepth))
  val writeAcceptedCount = RegInit(0.U(log2Ceil(writeCreditDepth + 1).W))
  val writeInputBeat = RegInit(0.U(8.W))
  val writeDataCredits = RegInit(0.U(log2Ceil(writeCreditDepth + 1).W))

  val writeCreditAvailable = writeAcceptedCount < writeCreditDepth.U
  val writeCommandCanCapture = writeCreditAvailable &&
    writeDescriptors.io.enq.ready && writeInputContexts.io.enq.ready
  io.store.req_ready := writeCommandCanCapture
  val captureWrite = io.store.req_valid && io.store.req_ready

  writeDescriptors.io.enq.valid := captureWrite
  writeDescriptors.io.enq.bits.addr := io.store.req_addr
  writeDescriptors.io.enq.bits.size := io.store.req_size
  writeDescriptors.io.enq.bits.len := io.store.req_len
  writeDescriptors.io.enq.bits.burst := io.store.req_burst
  writeInputContexts.io.enq.valid := captureWrite
  writeInputContexts.io.enq.bits := io.store.req_len

  val captureWriteDataReady = writeInputContexts.io.deq.valid &&
    writeDataQueue.io.enq.ready
  io.store.data_ready := captureWriteDataReady
  val captureWriteData = io.store.data_valid && io.store.data_ready
  val expectedInputLast = writeInputBeat === writeInputContexts.io.deq.bits
  writeDataQueue.io.enq.valid := io.store.data_valid &&
    writeInputContexts.io.deq.valid
  writeDataQueue.io.enq.bits.data := io.store.data_bits
  writeDataQueue.io.enq.bits.strb := io.store.data_strb
  writeDataQueue.io.enq.bits.last := io.store.data_last
  writeInputContexts.io.deq.ready := captureWriteData && io.store.data_last
  when(captureWriteData) {
    assert(io.store.data_strb.orR)
    assert(io.store.data_last === expectedInputLast)
    when(io.store.data_last) {
      writeInputBeat := 0.U
    }.otherwise {
      writeInputBeat := writeInputBeat + 1.U
    }
  }

  io.axi.awid := BusDeviceId.ROBStore.U
  io.axi.awaddr := writeDescriptors.io.deq.bits.addr
  io.axi.awlen := writeDescriptors.io.deq.bits.len
  io.axi.awsize := writeDescriptors.io.deq.bits.size
  io.axi.awburst := writeDescriptors.io.deq.bits.burst
  io.axi.awlock := false.B
  io.axi.awcache := "b0010".U
  io.axi.awprot := 0.U
  io.axi.awqos := 0.U
  io.axi.awvalid := writeDescriptors.io.deq.valid &&
    writeResponseContexts.io.enq.ready
  val writeAddressFire = io.axi.awvalid && io.axi.awready
  writeDescriptors.io.deq.ready := writeAddressFire
  writeResponseContexts.io.enq.valid := writeAddressFire
  writeResponseContexts.io.enq.bits := writeDescriptors.io.deq.bits

  io.axi.wdata := writeDataQueue.io.deq.bits.data
  io.axi.wstrb := writeDataQueue.io.deq.bits.strb
  io.axi.wlast := writeDataQueue.io.deq.bits.last
  io.axi.wvalid := writeDataQueue.io.deq.valid && writeDataCredits =/= 0.U
  val writeDataFire = io.axi.wvalid && io.axi.wready
  val writeDataLastFire = writeDataFire && io.axi.wlast
  writeDataQueue.io.deq.ready := writeDataFire
  when(writeAddressFire =/= writeDataLastFire) {
    writeDataCredits := Mux(writeAddressFire,
      writeDataCredits + 1.U, writeDataCredits - 1.U)
  }

  val writeResponseRaw = io.axi.bvalid &&
    io.axi.bid === BusDeviceId.ROBStore.U
  io.store.resp_valid := writeResponseRaw &&
    writeResponseContexts.io.deq.valid
  io.axi.bready := writeResponseRaw && writeResponseContexts.io.deq.valid &&
    io.store.resp_ready
  val writeResponseFire = io.axi.bvalid && io.axi.bready
  writeResponseContexts.io.deq.ready := writeResponseFire

  when(captureWrite =/= writeResponseFire) {
    writeAcceptedCount := Mux(captureWrite,
      writeAcceptedCount + 1.U, writeAcceptedCount - 1.U)
  }

  when(io.axi.rvalid && io.axi.rready) {
    assert(io.axi.rresp === Axi4Const.RespOkay)
    assert(lsuResponseRaw || ifuResponseRaw)
    when(lsuResponseRaw) { assert(lsuReadCount =/= 0.U) }
    when(ifuResponseRaw) { assert(ifuOutstanding) }
  }
  when(io.axi.bvalid) {
    assert(io.axi.bid === BusDeviceId.ROBStore.U)
    assert(writeResponseContexts.io.deq.valid)
  }
  when(writeResponseFire) {
    assert(io.axi.bresp === Axi4Const.RespOkay)
    assert(writeAcceptedCount =/= 0.U)
  }
  when(io.axi.wvalid) {
    assert(writeDataCredits =/= 0.U)
  }
  when(io.ifu.requ_valid) {
    assert(!io.ifu.requ_type)
    assert(io.ifu.requ_burst === Axi4Const.BurstIncr)
  }
  when(io.lsu.requ_valid) {
    assert(!io.lsu.requ_type)
    assert(io.lsu.requ_burst === Axi4Const.BurstIncr ||
      io.lsu.requ_burst === Axi4Const.BurstWrap)
  }
  assert(lsuReadCount <= lsuReadCreditDepth.U)
  assert(writeAcceptedCount <= writeCreditDepth.U)
  assert(writeDataCredits <= writeCreditDepth.U)
  io.perf.lsuReadOccupancy := lsuReadCount
  io.perf.writeOccupancy := writeAcceptedCount
  io.perf.readDescriptorOccupancy := readDescriptors.io.count
  io.perf.writeDataOccupancy := writeDataQueue.io.count
  io.perf.lsuReadCreditBlocked := io.lsu.requ_valid && !lsuCreditAvailable
  io.perf.readDescriptorBlocked := readDescriptors.io.enq.valid && !readDescriptors.io.enq.ready
  io.perf.writeCreditBlocked := io.store.req_valid && !writeCreditAvailable
  io.perf.arBlocked := io.axi.arvalid && !io.axi.arready
  io.perf.awBlocked := io.axi.awvalid && !io.axi.awready
  io.perf.wBlocked := io.axi.wvalid && !io.axi.wready
  io.perf.rBlocked := io.axi.rvalid && !io.axi.rready
}
