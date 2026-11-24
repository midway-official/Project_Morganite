package aurora

import chisel3._

object BusDeviceId {
  val Width = 4
  val IFU = 0
  val LSU = 1
  val ROBStore = 2
}

object Axi4Const {
  val RespOkay = 0.U(2.W)
  val BurstIncr = 1.U(2.W)
  val BurstWrap = 2.U(2.W)
}

/** One registered AXI R beat; buffer depth is independent of transaction credit. */
class AxiReadResponseBeat extends Bundle {
  val id = UInt(BusDeviceId.Width.W)
  val data = UInt(32.W)
  val resp = UInt(2.W)
  val last = Bool()
}

/** 内部请求方使用的总线接口。设备 ID 由共享仲裁器注入。 */
class BusClientIO extends Bundle {
  val requ_valid = Output(Bool())
  val requ_addr  = Output(UInt(32.W))
  val requ_size  = Output(UInt(3.W))
  val requ_len   = Output(UInt(8.W))
  val requ_burst = Output(UInt(2.W))
  val requ_type  = Output(Bool())      // 0=读, 1=写
  val requ_wdata = Output(UInt(32.W))
  val requ_wstrb = Output(UInt(4.W))
  val requ_ready = Input(Bool())

  val resp_valid = Input(Bool())
  val resp_rdata = Input(UInt(32.W))
  val resp_last  = Input(Bool())
  val resp_ready = Output(Bool())
}

/** 内部写请求方接口，地址和数据分别使用握手传输。 */
class BusWriteClientIO extends Bundle {
  val req_valid = Output(Bool())
  val req_addr = Output(UInt(32.W))
  val req_size = Output(UInt(3.W))
  val req_len = Output(UInt(8.W))
  val req_burst = Output(UInt(2.W))
  val req_ready = Input(Bool())

  val data_valid = Output(Bool())
  val data_bits = Output(UInt(32.W))
  val data_strb = Output(UInt(4.W))
  val data_last = Output(Bool())
  val data_ready = Input(Bool())

  val resp_valid = Input(Bool())
  val resp_ready = Output(Bool())
}

/** 32 位 AXI4 Full 主端接口。 */
class Axi4MasterIO extends Bundle {
  val awid = Output(UInt(BusDeviceId.Width.W))
  val awaddr = Output(UInt(32.W))
  val awlen = Output(UInt(8.W))
  val awsize = Output(UInt(3.W))
  val awburst = Output(UInt(2.W))
  val awlock = Output(Bool())
  val awcache = Output(UInt(4.W))
  val awprot = Output(UInt(3.W))
  val awqos = Output(UInt(4.W))
  val awvalid = Output(Bool())
  val awready = Input(Bool())

  val wdata = Output(UInt(32.W))
  val wstrb = Output(UInt(4.W))
  val wlast = Output(Bool())
  val wvalid = Output(Bool())
  val wready = Input(Bool())

  val bid = Input(UInt(BusDeviceId.Width.W))
  val bresp = Input(UInt(2.W))
  val bvalid = Input(Bool())
  val bready = Output(Bool())

  val arid = Output(UInt(BusDeviceId.Width.W))
  val araddr = Output(UInt(32.W))
  val arlen = Output(UInt(8.W))
  val arsize = Output(UInt(3.W))
  val arburst = Output(UInt(2.W))
  val arlock = Output(Bool())
  val arcache = Output(UInt(4.W))
  val arprot = Output(UInt(3.W))
  val arqos = Output(UInt(4.W))
  val arvalid = Output(Bool())
  val arready = Input(Bool())

  val rid = Input(UInt(BusDeviceId.Width.W))
  val rdata = Input(UInt(32.W))
  val rresp = Input(UInt(2.W))
  val rlast = Input(Bool())
  val rvalid = Input(Bool())
  val rready = Output(Bool())
}
