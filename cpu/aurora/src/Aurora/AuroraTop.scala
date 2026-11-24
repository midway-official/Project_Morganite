package aurora

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** 带 32 位 AXI4 Full 主端内存接口的处理器顶层。 */
class Aurora(implicit p: AuroraConfig) extends RawModule {
  val clk = IO(Input(Clock()))
  val rst = IO(Input(Bool()))
  val ext_int = IO(Input(UInt(8.W)))
  val ipi_int = IO(Input(Bool()))

  val awid = IO(Output(UInt(BusDeviceId.Width.W)))
  val awaddr = IO(Output(UInt(32.W)))
  val awlen = IO(Output(UInt(8.W)))
  val awsize = IO(Output(UInt(3.W)))
  val awburst = IO(Output(UInt(2.W)))
  val awlock = IO(Output(Bool()))
  val awcache = IO(Output(UInt(4.W)))
  val awprot = IO(Output(UInt(3.W)))
  val awqos = IO(Output(UInt(4.W)))
  val awvalid = IO(Output(Bool()))
  val awready = IO(Input(Bool()))

  val wdata = IO(Output(UInt(32.W)))
  val wstrb = IO(Output(UInt(4.W)))
  val wlast = IO(Output(Bool()))
  val wvalid = IO(Output(Bool()))
  val wready = IO(Input(Bool()))

  val bid = IO(Input(UInt(BusDeviceId.Width.W)))
  val bresp = IO(Input(UInt(2.W)))
  val bvalid = IO(Input(Bool()))
  val bready = IO(Output(Bool()))

  val arid = IO(Output(UInt(BusDeviceId.Width.W)))
  val araddr = IO(Output(UInt(32.W)))
  val arlen = IO(Output(UInt(8.W)))
  val arsize = IO(Output(UInt(3.W)))
  val arburst = IO(Output(UInt(2.W)))
  val arlock = IO(Output(Bool()))
  val arcache = IO(Output(UInt(4.W)))
  val arprot = IO(Output(UInt(3.W)))
  val arqos = IO(Output(UInt(4.W)))
  val arvalid = IO(Output(Bool()))
  val arready = IO(Input(Bool()))

  val rid = IO(Input(UInt(BusDeviceId.Width.W)))
  val rdata = IO(Input(UInt(32.W)))
  val rresp = IO(Input(UInt(2.W)))
  val rlast = IO(Input(Bool()))
  val rvalid = IO(Input(Bool()))
  val rready = IO(Output(Bool()))

  // 性能事件口与 Difftest 独立裁剪，普通性能测量仍可使用 NEMU 校验。
  val br_total = if (p.enablePerfEvents) IO(Output(UInt(32.W)))
    else WireDefault(0.U(32.W))
  val br_mispredict = if (p.enablePerfEvents) IO(Output(UInt(32.W)))
    else WireDefault(0.U(32.W))
  val inst_commit = if (p.enablePerfEvents) IO(Output(UInt(32.W)))
    else WireDefault(0.U(32.W))
  val perf = if (p.enablePerfEvents) IO(Output(new PerfEvents))
    else WireDefault(0.U.asTypeOf(new PerfEvents))
  val difftest_commit = if (p.enableDifftest)
    IO(Output(Vec(p.coreWidth, new DifftestCommit)))
  else WireDefault(0.U.asTypeOf(Vec(p.coreWidth, new DifftestCommit)))
  val difftest_store = if (p.enableDifftest)
    IO(Output(Vec(p.coreWidth, new DifftestMemoryEvent)))
  else WireDefault(0.U.asTypeOf(Vec(p.coreWidth, new DifftestMemoryEvent)))
  val difftest_load = if (p.enableDifftest)
    IO(Output(Vec(p.coreWidth, new DifftestMemoryEvent)))
  else WireDefault(0.U.asTypeOf(Vec(p.coreWidth, new DifftestMemoryEvent)))
  val difftest_trap = if (p.enableDifftest) IO(Output(new DifftestTrap))
    else WireDefault(0.U.asTypeOf(new DifftestTrap))
  val difftest_csr = if (p.enableDifftest) IO(Output(new DifftestCsrState))
    else WireDefault(0.U.asTypeOf(new DifftestCsrState))
  val difftest_timer = if (p.enableDifftest) IO(Output(UInt(64.W)))
    else WireDefault(0.U(64.W))
  val difftest_tlbfill_index = if (p.enableDifftest)
    IO(Output(UInt(p.tlbIndexBits.W)))
  else WireDefault(0.U(p.tlbIndexBits.W))
  val ordered_store_debug = if (p.enableDifftest) IO(Output(new OrderedStoreDebug))
    else WireDefault(0.U.asTypeOf(new OrderedStoreDebug))

  // 异步断言、同步释放。同步器只驱动分发级，核心高扇出复位由分发级承担。
  val rstSync0 = withClockAndReset(clk, rst.asAsyncReset) {
    val r = RegInit(true.B); r := false.B; r
  }
  val rstSync1 = withClockAndReset(clk, rst.asAsyncReset) {
    val r = RegInit(true.B); r := rstSync0; r
  }
  for (stage <- Seq(rstSync0, rstSync1)) {
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(stage.toTarget, "ASYNC_REG = \"TRUE\"")
    })
  }
  val coreReset = withClockAndReset(clk, rst.asAsyncReset) {
    val r = RegInit(true.B); r := rstSync1; r
  }
  annotate(new ChiselAnnotation {
    override def toFirrtl =
      AttributeAnnotation(coreReset.toTarget, "max_fanout = \"64\"")
  })
  withClockAndReset(clk, coreReset) {
    val core = Module(new Core)
    val extIntSync0 = RegNext(ext_int, 0.U)
    val extIntSync1 = RegNext(extIntSync0, 0.U)
    val ipiIntSync0 = RegNext(ipi_int, false.B)
    val ipiIntSync1 = RegNext(ipiIntSync0, false.B)
    core.io.hardwareInterrupts := extIntSync1
    core.io.ipiInterrupt := ipiIntSync1
    awid := core.io.axi.awid
    awaddr := core.io.axi.awaddr
    awlen := core.io.axi.awlen
    awsize := core.io.axi.awsize
    awburst := core.io.axi.awburst
    awlock := core.io.axi.awlock
    awcache := core.io.axi.awcache
    awprot := core.io.axi.awprot
    awqos := core.io.axi.awqos
    awvalid := core.io.axi.awvalid
    core.io.axi.awready := awready

    wdata := core.io.axi.wdata
    wstrb := core.io.axi.wstrb
    wlast := core.io.axi.wlast
    wvalid := core.io.axi.wvalid
    core.io.axi.wready := wready

    core.io.axi.bid := bid
    core.io.axi.bresp := bresp
    core.io.axi.bvalid := bvalid
    bready := core.io.axi.bready

    arid := core.io.axi.arid
    araddr := core.io.axi.araddr
    arlen := core.io.axi.arlen
    arsize := core.io.axi.arsize
    arburst := core.io.axi.arburst
    arlock := core.io.axi.arlock
    arcache := core.io.axi.arcache
    arprot := core.io.axi.arprot
    arqos := core.io.axi.arqos
    arvalid := core.io.axi.arvalid
    core.io.axi.arready := arready

    if (p.axiReadResponseQueueDepth == 1) {
      val rBufferValid = RegInit(false.B)
      val rBufferId = Reg(UInt(BusDeviceId.Width.W))
      val rBufferData = Reg(UInt(32.W))
      val rBufferResp = Reg(UInt(2.W))
      val rBufferLast = Reg(Bool())
      val rBufferReady = !rBufferValid || core.io.axi.rready
      rready := rBufferReady
      when(rBufferReady) {
        rBufferValid := rvalid
        when(rvalid) {
          rBufferId := rid
          rBufferData := rdata
          rBufferResp := rresp
          rBufferLast := rlast
        }
      }
      core.io.axi.rid := rBufferId
      core.io.axi.rdata := rBufferData
      core.io.axi.rresp := rBufferResp
      core.io.axi.rlast := rBufferLast
      core.io.axi.rvalid := rBufferValid
    } else {
      // Registered occupancy controls ready; no downstream-ready bypass.
      val responses = Module(new chisel3.util.Queue(
        new AxiReadResponseBeat, p.axiReadResponseQueueDepth, pipe = false, flow = false))
      responses.io.enq.valid := rvalid
      responses.io.enq.bits.id := rid
      responses.io.enq.bits.data := rdata
      responses.io.enq.bits.resp := rresp
      responses.io.enq.bits.last := rlast
      rready := responses.io.enq.ready
      core.io.axi.rid := responses.io.deq.bits.id
      core.io.axi.rdata := responses.io.deq.bits.data
      core.io.axi.rresp := responses.io.deq.bits.resp
      core.io.axi.rlast := responses.io.deq.bits.last
      core.io.axi.rvalid := responses.io.deq.valid
      responses.io.deq.ready := core.io.axi.rready
    }

    if (p.enablePerfEvents) {
      br_total      := core.io.brTotal
      br_mispredict := core.io.brMispredict
      inst_commit   := core.io.instCommit
      perf := core.io.perf
      // Core 复位释放后才允许统计，仿真器使用该信号作为精确测量门控。
      perf.active := !coreReset
    } else {
      br_total := 0.U
      br_mispredict := 0.U
      inst_commit := 0.U
      perf := 0.U.asTypeOf(perf)
    }
    if (p.enableDifftest) {
      difftest_commit := core.io.difftestCommit
      difftest_store := core.io.difftestStore
      difftest_load := core.io.difftestLoad
      difftest_trap := core.io.difftestTrap
      difftest_csr := core.io.difftestCsr
      difftest_timer := core.io.difftestTimer
      difftest_tlbfill_index := core.io.difftestTlbfillIndex
      ordered_store_debug := core.io.orderedStoreDebug
    } else {
      difftest_commit := 0.U.asTypeOf(difftest_commit)
      difftest_store := 0.U.asTypeOf(difftest_store)
      difftest_load := 0.U.asTypeOf(difftest_load)
      difftest_trap := 0.U.asTypeOf(difftest_trap)
      difftest_csr := 0.U.asTypeOf(difftest_csr)
      difftest_timer := 0.U
      difftest_tlbfill_index := 0.U
      ordered_store_debug := 0.U.asTypeOf(ordered_store_debug)
    }
  }
}

object AuroraMain extends App {
  private val supportedProperties = scala.collection.mutable.Set.empty[String]
  private def propertyInt(name: String, default: Int): Int = {
    supportedProperties += name
    sys.props.get(name).map(_.toInt).getOrElse(default)
  }
  private def propertyBool(name: String, default: Boolean): Boolean = {
    supportedProperties += name
    sys.props.get(name).map(_.toBoolean).getOrElse(default)
  }
  private val defaults = AuroraConfig.default
  implicit val p: AuroraConfig = AuroraConfig(
    numDiv = propertyInt("aurora.numDiv", defaults.numDiv),
    numPhysRegs = propertyInt("aurora.numPhysRegs", defaults.numPhysRegs),
    numRobEntries = propertyInt("aurora.numRobEntries", defaults.numRobEntries),
    numCheckpoints = propertyInt("aurora.numCheckpoints", defaults.numCheckpoints),
    divIqDepth = propertyInt("aurora.divIqDepth", defaults.divIqDepth),
    lsuIqDepth = propertyInt("aurora.lsuIqDepth", defaults.lsuIqDepth),
    lqDepth = propertyInt("aurora.lqDepth", defaults.lqDepth),
    sqDepth = propertyInt("aurora.sqDepth", defaults.sqDepth),
    lsuAguQueueDepth = propertyInt("aurora.lsuAguQueueDepth", defaults.lsuAguQueueDepth),
    committedStoreBufferDepth = propertyInt("aurora.committedStoreBufferDepth", defaults.committedStoreBufferDepth),
    storeCommitWindowGroups = propertyInt("aurora.storeCommitWindowGroups", defaults.storeCommitWindowGroups),
    dcacheSets = propertyInt("aurora.dcacheSets", defaults.dcacheSets),
    dcacheWays = propertyInt("aurora.dcacheWays", defaults.dcacheWays),
    dcacheLineBytes = propertyInt("aurora.dcacheLineBytes", defaults.dcacheLineBytes),
    dcacheMshrEntries = propertyInt("aurora.dcacheMshrEntries", defaults.dcacheMshrEntries),
    dcacheFillContextDepth = propertyInt("aurora.dcacheFillContextDepth", defaults.dcacheFillContextDepth),
    dcacheStoreUpdateQueueDepth = propertyInt("aurora.dcacheStoreUpdateQueueDepth", defaults.dcacheStoreUpdateQueueDepth),
    dcacheWritebackQueueDepth = propertyInt("aurora.dcacheWritebackQueueDepth", defaults.dcacheWritebackQueueDepth),
    axiLsuReadMaxOutstanding = propertyInt("aurora.axiLsuReadMaxOutstanding", defaults.axiLsuReadMaxOutstanding),
    axiWriteMaxOutstanding = propertyInt("aurora.axiWriteMaxOutstanding", defaults.axiWriteMaxOutstanding),
    axiReadRequestQueueDepth = propertyInt("aurora.axiReadRequestQueueDepth", defaults.axiReadRequestQueueDepth),
    axiWriteDataQueueDepth = propertyInt("aurora.axiWriteDataQueueDepth", defaults.axiWriteDataQueueDepth),
    axiReadResponseQueueDepth = propertyInt("aurora.axiReadResponseQueueDepth", defaults.axiReadResponseQueueDepth),
    tlbEntries = propertyInt("aurora.tlbEntries", defaults.tlbEntries),
    allowAluMultiplyForward = propertyBool("aurora.allowAluMultiplyForward", defaults.allowAluMultiplyForward),
    enablePerfEvents = propertyBool("aurora.enablePerfEvents", defaults.enablePerfEvents),
    enableDifftest = propertyBool("aurora.enableDifftest", defaults.enableDifftest))
  private val unknownProperties = sys.props.keySet.filter(_.startsWith("aurora.")).toSet -- supportedProperties
  require(unknownProperties.isEmpty,
    s"Unknown Aurora configuration properties: ${unknownProperties.toSeq.sorted.mkString(", ")}")
  val outDir = if (args.nonEmpty) args(0) else "build"
  val stage = new ChiselStage
  stage.emitVerilog(
    new Aurora,
    Array("--target-dir", outDir)
  )
  // Bind software reference/observation to the parameters actually elaborated.
  val scalarConfig = p.productElementNames.zip(p.productIterator).flatMap {
    case (name, value: Int) => Some(s"\"$name\":$value")
    case (name, value: Boolean) => Some(s"\"$name\":$value")
    case _ => None
  }.toSeq ++ Seq(s"\"dcacheBytes\":${p.dcacheBytes}",
    s"\"iCacheCfgWord\":${p.iCacheCfgWord}",
    s"\"dCacheCfgWord\":${p.dCacheCfgWord}")
  java.nio.file.Files.write(java.nio.file.Paths.get(outDir, "aurora-config.json"),
    (scalarConfig.mkString("{\n  ", ",\n  ", "\n}\n")).getBytes(java.nio.charset.StandardCharsets.UTF_8))
}
