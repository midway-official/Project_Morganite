package aurora

import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** Translation response paired with the prediction snapshot from its request. */
class IFUTranslationEnvelope(implicit p: AuroraConfig) extends Bundle {
  val response = new TranslationResponse
  val l1NextPc = UInt(p.xLen.W)
}

/** PC/ITLB → 同步 ICache → 查询/原始指令 F1 → 精确预测 F2。
  * F0 快速预测维持请求启动间隔；F2 修正经寄存器反馈，不组合旁路到 PC。
  * ICache 保存缺失请求的所有权，IFU 不再倒退 PC 重发翻译。
  */
class IFU(registerTranslation: Boolean = false)(implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val out = Decoupled(new FetchGroup)
    val redirect = Input(new Bundle {
      val valid = Bool()
      val pc    = UInt(p.xLen.W)
    })
    val bus = new BusClientIO
    val translationReq = Decoupled(new TranslationRequest)
    val translationResp = Flipped(Decoupled(new TranslationResponse))
    // BPU 训练反馈（来自 BRU 执行结果）
    val bpuUpdate = Input(new BpuUpdate)
    val bpuRepair = Input(Valid(new BpuRepair))
    // 低频 CSR 地址翻译配置与提交侧 Cache 维护。
    val crmd = Input(UInt(32.W))
    val dmw0 = Input(UInt(32.W))
    val dmw1 = Input(UInt(32.W))
    val idle = Input(Bool())
    val invalidateAll = Input(Bool())
    val cacop = Input(Valid(new Bundle {
      val code = UInt(5.W)
      val addr = UInt(p.xLen.W)
    }))
    val perf = Output(new ICachePerfEvents)
  })

  private val w = p.fetchWidth
  val pc = RegInit(p.resetPc.U(p.xLen.W))
  val translationPc = RegInit(p.resetPc.U(p.xLen.W))
  dontTouch(translationPc)
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(translationPc.toTarget,
      "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 32")
  })
  val faultInFlight = RegInit(false.B)
  val predictionRedirectValid = RegInit(false.B)
  val predictionRedirectEvent = WireDefault(false.B)
  val predictionRedirectNow = predictionRedirectValid
  val killFetch = io.redirect.valid || predictionRedirectNow

  // Break the cache-hit -> translation-response ready -> MMU request-enable
  // path with a small registered-credit buffer. Flow-through keeps the empty
  // case at the original latency; pipe=false keeps enq.ready independent of
  // the ICache consumer's late ready signal.
  val translationRespQueue = Module(new Queue(
    new IFUTranslationEnvelope, entries = 2, pipe = false, flow = !registerTranslation,
    hasFlush = true))
  val translationNextPcQueue = Module(new Queue(
    UInt(p.xLen.W), entries = 4, pipe = false, flow = false,
    hasFlush = true))
  translationRespQueue.io.flush.get := killFetch
  // A backend redirect cancels outstanding MMU requests. A predictor redirect
  // does not: drain the old response metadata while allowing the corrected-PC
  // request to enter on the same cycle.
  translationNextPcQueue.io.flush.get := io.redirect.valid

  val bufferedTranslation = translationRespQueue.io.deq
  val incomingTranslationFault = io.translationResp.valid &&
    io.translationResp.bits.exception.valid
  translationRespQueue.io.enq.valid := io.translationResp.valid &&
    translationNextPcQueue.io.deq.valid && !killFetch && !io.idle
  translationRespQueue.io.enq.bits.response := io.translationResp.bits
  translationRespQueue.io.enq.bits.l1NextPc := translationNextPcQueue.io.deq.bits
  translationNextPcQueue.io.deq.ready := io.translationResp.valid &&
    !io.redirect.valid && (predictionRedirectNow ||
      (!io.idle && translationRespQueue.io.enq.ready))
  io.translationResp.ready := killFetch || (!io.idle &&
    translationNextPcQueue.io.deq.valid && translationRespQueue.io.enq.ready)
  when(io.translationResp.valid && !killFetch && !io.idle) {
    assert(translationNextPcQueue.io.deq.valid)
  }

  val fastBpu = Module(new FastBPU)
  val bpu = Module(new TageOnlyBPU(precompareNextPc = true))
  val cache = Module(new ICache)
  cache.io.bus <> io.bus
  cache.io.flush := killFetch
  cache.io.invalidateAll := io.invalidateAll
  cache.io.cacop := io.cacop
  fastBpu.io.update := io.bpuUpdate
  bpu.io.update := io.bpuUpdate
  bpu.io.repair := io.bpuRepair
  bpu.io.flush := io.redirect.valid

  val bpValid = RegInit(false.B)
  val bpBits = Reg(new ICacheResponse)
  val bpException = Reg(new ExceptionInfo)
  val outValid = RegInit(false.B)
  val outBits = Reg(new FetchGroup)
  val outReady = !outValid || io.out.ready
  val bpReady = !bpValid || outReady
  val bpFire = bpValid && outReady && !killFetch && !io.idle
  bpu.io.groupPc := bpBits.pc
  bpu.io.insts := bpBits.insts
  bpu.io.slotBeginValid := bpBits.valid
  bpu.io.fire := bpFire && !bpException.valid
  bpu.io.lookupPc := cache.io.predictorPc
  // An available F2 slot may sample an unowned cache response. Only the
  // accepted response sets bpValid, and a held owner keeps all query state.
  bpu.io.lookupEnable := cache.io.resp.ready
  bpu.io.lookupInsts := cache.io.resp.bits.insts
  bpu.io.lookupValid := cache.io.resp.bits.valid
  cache.io.resp.ready := bpReady && !killFetch && !io.idle

  val fault = bufferedTranslation.bits.response.exception.valid
  cache.io.req.valid := bufferedTranslation.valid && !fault && !killFetch && !io.idle
  cache.io.req.bits.pc := bufferedTranslation.bits.response.va
  cache.io.req.bits.pa := bufferedTranslation.bits.response.pa
  cache.io.req.bits.cacheable := MemoryAddressing.isCacheableLine(
    bufferedTranslation.bits.response.pa, bufferedTranslation.bits.response.mat,
    p.icacheLineBytes)
  cache.io.req.bits.l1NextPc := bufferedTranslation.bits.l1NextPc
  // 翻译异常不能越过尚未从 ICache 返回的较老窗口。
  val faultReady = cache.io.empty && bpReady && !io.idle
  bufferedTranslation.ready := killFetch || Mux(fault, faultReady,
    cache.io.req.ready && !io.idle)
  val faultFire = bufferedTranslation.fire && fault && !killFetch

  // F2 在发现修正的沿直接写 PC；下一拍取消旧窗口时，F0 查询已经是
  // 正确的寄存 PC，不再让 redirect 选择器串入整个 FastBPU 查询路径。
  val requestPc = pc
  fastBpu.io.pc := requestPc
  io.translationReq.bits.va := translationPc
  if (p.enableDifftest) { assert(translationPc === requestPc) }
  io.translationReq.bits.access := TranslationAccess.Fetch.U
  io.translationReq.valid := !io.redirect.valid && !io.idle &&
    (!faultInFlight || predictionRedirectNow) &&
    !(incomingTranslationFault && !predictionRedirectNow) &&
    translationNextPcQueue.io.enq.ready
  translationNextPcQueue.io.enq.valid := io.translationReq.fire
  translationNextPcQueue.io.enq.bits := fastBpu.io.nextPc
  val backendPc = io.redirect.valid
  val correctionPc = !backendPc && predictionRedirectEvent
  val sequentialPc = !backendPc && !predictionRedirectEvent && io.translationReq.fire
  when(backendPc || correctionPc || sequentialPc) {
    val nextPc = Mux1H(Seq(backendPc -> io.redirect.pc, correctionPc -> bpu.io.nextPc,
      sequentialPc -> fastBpu.io.nextPc))
    pc := nextPc
    translationPc := nextPc
  }

  when(cache.io.resp.ready) {
    bpBits := cache.io.resp.bits
    bpException := Mux(faultFire, bufferedTranslation.bits.response.exception,
      ExceptionInfo.none)
    when(faultFire) {
      bpBits.pc := bufferedTranslation.bits.response.va
      bpBits.l1NextPc := bufferedTranslation.bits.l1NextPc
    }
  }
  when(killFetch) {
    bpValid := false.B
    faultInFlight := false.B
  }.otherwise {
    when(bpReady) { bpValid := cache.io.resp.fire || faultFire }
    when((translationRespQueue.io.enq.fire && incomingTranslationFault) ||
        faultFire) { faultInFlight := true.B }
  }

  val predictedGroup = Wire(new FetchGroup)
  for (slot <- 0 until w) {
    val result = predictedGroup.slots(slot)
    result.pc := bpBits.pc + (slot * p.instructionBytes).U
    result.inst := Mux(bpException.valid, 0.U, bpBits.insts(slot))
    result.valid := Mux(bpException.valid, (slot == 0).B, bpu.io.slotValid(slot))
    result.predTaken := !bpException.valid && bpu.io.predTaken(slot)
    result.predTarget := Mux(bpException.valid, 0.U, bpu.io.predTarget(slot))
    result.predBhtIdx := Mux(bpException.valid, 0.U, bpu.io.predBhtIdx(slot))
    result.predGhr := Mux(bpException.valid, 0.U, bpu.io.predGhr(slot))
    result.predTageTable := Mux(bpException.valid, 0.U, bpu.io.predTageTable(slot))
    result.predTageGhr := Mux(bpException.valid, 0.U, bpu.io.predTageGhr(slot))
    result.predCtr := Mux(bpException.valid, 0.U, bpu.io.predCtr(slot))
    result.exception := Mux(bpException.valid && (slot == 0).B,
      bpException, ExceptionInfo.none)
  }
  bpu.io.compareNextPc.get := bpBits.l1NextPc
  val predictionMismatch = !bpException.valid && !bpu.io.nextPcMatches.get
  predictionRedirectEvent := bpFire && predictionMismatch
  predictionRedirectValid := predictionRedirectEvent && !io.redirect.valid

  when(outReady && bpFire) { outBits := predictedGroup }
  when(io.redirect.valid) {
    outValid := false.B
  }.elsewhen(outReady) {
    outValid := bpFire
  }
  io.out.valid := outValid && !io.redirect.valid && !io.idle
  io.out.bits := outBits

  io.perf := cache.io.perf
  io.perf.l1Lookup := io.translationReq.fire
  io.perf.l1Hit := io.translationReq.fire && fastBpu.io.hit
  io.perf.l1Taken := io.translationReq.fire && fastBpu.io.taken
  io.perf.predictionRedirect := predictionRedirectEvent
  io.perf.predictionRedirectPc := Mux(predictionRedirectEvent, bpBits.pc, 0.U)
}

object IFUMain extends App {
  implicit val p: AuroraConfig = AuroraConfig.default
  val outDir = if (args.nonEmpty) args(0) else "build"
  (new ChiselStage).emitVerilog(new IFU, Array("--target-dir", outDir))
}
