package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** 一个窗口只使用一次翻译，故不能跨越最小4 KiB页面。 */
object FetchWindow {
  def slotValid(pc: UInt, slot: Int)(implicit p: AuroraConfig): Bool =
    (pc(11, 0) +& (slot * p.instructionBytes).U) < 4096.U
  def nextPc(pc: UInt)(implicit p: AuroraConfig): UInt = {
    // fetchBytes 是2的幂。正常窗口保留组内偏移，只递增组号；
    // 页末窗口则清除偏移。避免 F0 上的逐槽加法/PopCount 再串一个全宽加法。
    if (p.groupOffsetBits < 12) {
      val groupInPage = pc(11, p.groupOffsetBits)
      val page = pc(p.xLen - 1, 12)
      val lastGroupInPage = groupInPage.andR
      val nextPage = Mux(lastGroupInPage, page + 1.U, page)
      Cat(nextPage, groupInPage + 1.U,
        Mux(lastGroupInPage, 0.U(p.groupOffsetBits.W),
        pc(p.groupOffsetBits - 1, 0)))
    } else {
      Cat(pc(p.xLen - 1, 12) + 1.U, 0.U(12.W))
    }
  }
}

class ICacheRequest(implicit p: AuroraConfig) extends Bundle {
  val pc = UInt(p.xLen.W)
  val pa = UInt(p.xLen.W)
  val cacheable = Bool()
  val l1NextPc = UInt(p.xLen.W)
}

class ICacheResponse(implicit p: AuroraConfig) extends Bundle {
  val pc = UInt(p.xLen.W)
  val l1NextPc = UInt(p.xLen.W)
  val insts = Vec(p.fetchWidth, UInt(32.W))
  val valid = Vec(p.fetchWidth, Bool())
}

/** 两个 line bank 的同步 tag/data ICache。容量、映射和总线信用保持不变。
  * 每拍可查询一个从实际 PC 起始的窗口，至多涉及两个相邻 line。
  * 缺失保留查询所有权，完成后重读；不撤销/重发已接受的其他翻译。
  */
class ICache(implicit p: AuroraConfig) extends Module {
  private val rows = p.icacheSets / 2
  private val rowBits = log2Ceil(rows max 2)
  private val words = p.icacheLineWords
  private val countBits = log2Ceil(words + 1)
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ICacheRequest))
    val resp = Decoupled(new ICacheResponse)
    val predictorPc = Output(UInt(p.xLen.W))
    val flush = Input(Bool())
    val invalidateAll = Input(Bool())
    val cacop = Input(Valid(new Bundle {
      val code = UInt(5.W)
      val addr = UInt(p.xLen.W)
    }))
    val bus = new BusClientIO
    val empty = Output(Bool())
    val perf = Output(new ICachePerfEvents)
  })

  def index(addr: UInt): UInt =
    addr(p.icacheOffsetBits + p.icacheIndexBits - 1, p.icacheOffsetBits)
  def tag(addr: UInt): UInt = addr(p.xLen - 1, p.icacheOffsetBits + p.icacheIndexBits)
  def row(idx: UInt): UInt = if (rows == 1) 0.U(rowBits.W) else idx(p.icacheIndexBits - 1, 1)
  def lineBase(addr: UInt): UInt = Cat(addr(p.xLen - 1, p.icacheOffsetBits), 0.U(p.icacheOffsetBits.W))

  // RAM 不复位，valid 与 tag 一起同步读。初始化/全失效仅扫描 tag RAM。
  val tags = Seq.fill(2)(Mem(rows, UInt((p.icacheTagBits + 1).W)))
  val data = Seq.fill(2)(Mem(rows, UInt((words * 32).W)))
  for (ram <- tags ++ data) {
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(ram.toTarget, "ram_style = \"block\"")
    })
  }
  val clearing = RegInit(true.B)
  val clearRow = RegInit(0.U(rowBits.W))
  val maintenance = io.invalidateAll || (io.cacop.valid && io.cacop.bits.code(2, 0) === 0.U)

  val idle :: request :: response :: drain :: Nil = Enum(4)
  val state = RegInit(idle)
  val owner = Reg(new ICacheRequest)
  val ownerPredictorPc = RegEnable(io.req.bits.pc, io.req.fire)
  dontTouch(ownerPredictorPc)
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(ownerPredictorPc.toTarget,
      "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 32")
  })
  io.predictorPc := ownerPredictorPc
  val ownerValid = RegInit(false.B)
  val lookupValid = RegInit(false.B)
  val uncachedReady = RegInit(false.B)
  val accessSeen = RegInit(false.B)
  val buffer = Reg(Vec(words, UInt(32.W)))
  val fillBase = Reg(UInt(p.xLen.W))
  val fillCacheable = Reg(Bool())
  val fillCount = Reg(UInt(countBits.W))
  val fillCancelled = RegInit(false.B)
  val reqAddr = Reg(UInt(p.xLen.W))
  val reqLen = Reg(UInt(8.W))

  val firstBase = lineBase(owner.pa)
  val secondBase = firstBase + p.icacheLineBytes.U
  // 每槽物理属性在接收请求时与 owner 一起寄存。必须逐槽屏蔽：
  // 窗口末尾进入外设区间时，前面的合法指令仍需返回并推动 PC。
  val reqSlotValid = VecInit((0 until p.fetchWidth).map { slot =>
    val address = io.req.bits.pa + (slot * p.instructionBytes).U
    FetchWindow.slotValid(io.req.bits.pc, slot) &&
      (!io.req.bits.cacheable || !MemoryAddressing.overlapsPeripheralLine(
        address, p.icacheLineBytes))
  })
  val slotValid = RegEnable(reqSlotValid, io.req.fire)
  val needsSecond = VecInit((0 until p.fetchWidth).map { slot =>
    slotValid(slot) && lineBase(owner.pa + (slot * p.instructionBytes).U) =/= firstBase
  }).asUInt.orR

  val readEnable = Wire(Bool())
  val readAddress = Wire(UInt(p.xLen.W))
  val readFirstIndex = index(readAddress)
  val readSecondIndex = readFirstIndex + 1.U
  val readTags = Wire(Vec(2, UInt((p.icacheTagBits + 1).W)))
  val readLines = Wire(Vec(2, UInt((words * 32).W)))
  for (bank <- 0 until 2) {
    val readIndex = Mux(readFirstIndex(0) === bank.U, readFirstIndex, readSecondIndex)
    readTags(bank) := RegEnable(tags(bank)(row(readIndex)), readEnable)
    readLines(bank) := RegEnable(data(bank)(row(readIndex)), readEnable)
  }
  val firstTag = readTags(index(firstBase)(0))
  val secondTag = readTags(index(secondBase)(0))
  val firstHit = firstTag(p.icacheTagBits) && firstTag(p.icacheTagBits - 1, 0) === tag(firstBase)
  val secondHit = secondTag(p.icacheTagBits) && secondTag(p.icacheTagBits - 1, 0) === tag(secondBase)
  val hit = Mux(owner.cacheable, lookupValid && firstHit && (!needsSecond || secondHit), uncachedReady)
  val canRun = state === idle && !clearing && !maintenance && !io.flush
  io.resp.valid := ownerValid && hit && canRun
  io.resp.bits.pc := owner.pc
  if (p.enableDifftest) {
    when(io.resp.valid) { assert(ownerPredictorPc === owner.pc) }
  }
  io.resp.bits.l1NextPc := owner.l1NextPc
  io.resp.bits.valid := slotValid
  for (slot <- 0 until p.fetchWidth) {
    val address = owner.pa + (slot * p.instructionBytes).U
    val line = readLines(index(address)(0)).asTypeOf(Vec(words, UInt(32.W)))
    val word = address(p.icacheOffsetBits - 1, 2)
    io.resp.bits.insts(slot) := Mux(owner.cacheable, line(word), buffer(slot))
  }
  io.req.ready := canRun && (!ownerValid || io.resp.fire)
  io.empty := !ownerValid && state === idle

  val retryRead = ownerValid && owner.cacheable && !lookupValid && canRun
  // An uncached request ignores the RAM response. Reading that address too
  // removes physical-address attribute calculation from BRAM read enable.
  readEnable := io.req.fire || retryRead
  // 重读旧owner与接收新请求互斥。地址不依赖hit/ready，握手只控制读使能。
  readAddress := Mux(retryRead, owner.pa, io.req.bits.pa)
  val missStart = ownerValid && !hit && canRun &&
    Mux(owner.cacheable, lookupValid, true.B)

  io.bus.requ_valid := state === request && !io.flush && !maintenance
  io.bus.requ_addr := reqAddr
  io.bus.requ_size := 2.U
  io.bus.requ_len := reqLen
  io.bus.requ_burst := Axi4Const.BurstIncr
  io.bus.requ_type := false.B
  io.bus.requ_wdata := 0.U
  io.bus.requ_wstrb := "b1111".U
  io.bus.resp_ready := state === response || state === drain
  val requestFire = io.bus.requ_valid && io.bus.requ_ready
  val responseFire = io.bus.resp_valid && io.bus.resp_ready
  val cachedLast = fillCount === (words - 1).U
  val uncachedLast = fillCount + 1.U === PopCount(slotValid)
  val fillLast = Mux(fillCacheable, cachedLast, uncachedLast)
  val install = state === response && responseFire && fillCacheable && cachedLast &&
    !fillCancelled && !io.flush && !maintenance && !clearing
  val refillLine = VecInit((0 until words).map(i =>
    Mux(fillCount === i.U, io.bus.resp_rdata, buffer(i))))

  for (bank <- 0 until 2) {
    // 先仲裁地址/数据，再调用一次 write。多个条件 write 会生成多个
    // FIRRTL 写端口，2019.2 将 tag RAM 展开为数万个 LUT/FF。
    val invalidateIndex = io.cacop.valid && io.cacop.bits.code(2, 0) === 0.U &&
      index(io.cacop.bits.addr)(0) === bank.U
    val installBank = install && index(fillBase)(0) === bank.U
    val tagWriteEnable = clearing || invalidateIndex || installBank
    val tagWriteRow = Mux(clearing, clearRow,
      Mux(invalidateIndex, row(index(io.cacop.bits.addr)), row(index(fillBase))))
    val tagWriteData = Mux(clearing || invalidateIndex,
      0.U((p.icacheTagBits + 1).W), Cat(true.B, tag(fillBase)))
    when(tagWriteEnable) {
      tags(bank).write(tagWriteRow, tagWriteData)
    }
    when(install && index(fillBase)(0) === bank.U) {
      data(bank).write(row(index(fillBase)), refillLine.asUInt)
    }
  }
  when(clearing) {
    when(clearRow === (rows - 1).U) {
      clearing := false.B
    }.otherwise { clearRow := clearRow + 1.U }
  }
  when(io.invalidateAll) {
    clearing := true.B
    clearRow := 0.U
  }

  when(io.req.fire) {
    owner := io.req.bits
    ownerValid := true.B
    lookupValid := io.req.bits.cacheable
    uncachedReady := false.B
    accessSeen := false.B
  }.elsewhen(io.resp.fire) {
    ownerValid := false.B
    lookupValid := false.B
    uncachedReady := false.B
    accessSeen := false.B
  }.elsewhen(retryRead) {
    lookupValid := true.B
  }

  when(missStart) {
    val missingBase = Mux(firstHit, secondBase, firstBase)
    fillBase := Mux(owner.cacheable, missingBase, owner.pa)
    reqAddr := Mux(owner.cacheable, missingBase, owner.pa)
    reqLen := Mux(owner.cacheable, (words - 1).U, 0.U)
    fillCacheable := owner.cacheable
    fillCount := 0.U
    fillCancelled := false.B
    lookupValid := false.B
    accessSeen := true.B
    state := request
  }
  when(requestFire) { state := response }
  when(state === response && responseFire) {
    buffer(fillCount) := io.bus.resp_rdata
    assert(io.bus.resp_last === Mux(fillCacheable, cachedLast, true.B))
    when(fillLast) {
      state := idle
      when(!fillCacheable && !fillCancelled && !io.flush && !maintenance) {
        uncachedReady := true.B
      }
    }.otherwise {
      fillCount := fillCount + 1.U
      when(!fillCacheable) {
        reqAddr := fillBase + ((fillCount + 1.U) << 2)
        state := request
      }
    }
  }
  when(state === drain && responseFire && io.bus.resp_last) { state := idle }

  // 维护保留尚未完成的 owner，失效结束后重新查询。redirect 则撤销 owner。
  when(maintenance || io.flush) {
    lookupValid := false.B
    uncachedReady := false.B
    fillCancelled := true.B
    when(state === request) { state := idle }
    when(state === response) {
      state := Mux(responseFire && io.bus.resp_last, idle, drain)
    }
  }
  when(io.flush) {
    ownerValid := false.B
    accessSeen := false.B
  }

  io.perf := 0.U.asTypeOf(new ICachePerfEvents)
  io.perf.access := !accessSeen && (missStart || io.resp.fire)
  io.perf.miss := !accessSeen && missStart
  io.perf.refillBeat := state === response && responseFire
}

/** 单独生成以检查存储器推导，不依赖全核实现。 */
object ICacheMain extends App {
  implicit val p: AuroraConfig = AuroraConfig.default
  val outDir = if (args.nonEmpty) args(0) else "build/icache"
  (new chisel3.stage.ChiselStage).emitVerilog(new ICache, Array("--target-dir", outDir))
}
