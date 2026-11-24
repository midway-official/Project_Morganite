package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** IFU 与 IDU 之间的原始指令队列。
  * 队列以 FetchGroup 为粒度，每个表项包含 coreWidth 路原始指令及预测上下文。
  * 存储体使用扁平 UInt Mem，并在读口显式放置一拍数据寄存器，形成
  * Vivado 2019.2 能稳定识别的同步读/同步写 BRAM 模板。
  * flush 只清空指针、计数和输出 valid，绝不复位存储数组；对数组
  * 逐项复位会阻止 Vivado 将其映射到 BRAM。
  */
class InstructionBuffer(implicit p: AuroraConfig) extends Module {
  private val depth = p.instructionBufferDepth
  private val ptrBits = p.instructionBufferPtrBits
  private val countBits = p.instructionBufferCountBits
  // 连续窗口共享 PC；截断后至多一个有效 taken 目标，取指异常仅在首槽。
  // 逻辑容量仍为256组；压缩重复上下文，避免在 BRAM 中保存四份地址/异常。
  class StoredWindow extends Bundle {
    val pc = UInt(p.xLen.W)
    val insts = Vec(p.fetchWidth, UInt(32.W))
    val valid = Vec(p.fetchWidth, Bool())
    val taken = Vec(p.fetchWidth, Bool())
    val target = UInt(p.xLen.W)
    val bhtIdx = Vec(p.fetchWidth, UInt(p.bhtIdxBits.W))
    val history = Vec(p.fetchWidth, UInt(p.ghrBits.W))
    val tageTable = Vec(p.fetchWidth, UInt(p.tageTableBits.W))
    val tageGhr = Vec(p.fetchWidth, UInt(p.ghrBits.W))
    val ctr = Vec(p.fetchWidth, UInt(p.tageCounterBits.W))
    val exception = new ExceptionInfo
  }
  private val entryWidth = (new StoredWindow).getWidth

  require(depth >= 2, "InstructionBuffer depth 必须至少为 2")
  require(isPow2(depth), "InstructionBuffer depth 必须是 2 的幂")

  val io = IO(new Bundle {
    val in    = Flipped(Decoupled(new FetchGroup))
    val out   = Decoupled(new FetchGroup)
    val flush = Input(Bool())
  })

  // 扁平成单个宽整数数组，避免 Bundle 被拆成大量小存储体。
  val storage = Mem(depth, UInt(entryWidth.W))
  annotate(new ChiselAnnotation {
    override def toFirrtl =
      AttributeAnnotation(storage.toTarget, "ram_style = \"block\"")
  })

  val enqPtr   = withReset(reset.asBool) { RegInit(0.U(ptrBits.W)) }
  val deqPtr   = withReset(reset.asBool) { RegInit(0.U(ptrBits.W)) }
  // 已写入 BRAM 但尚未发起同步读的表项数，不包含读响应级和输出级。
  val memCount = withReset(reset.asBool) { RegInit(0.U(countBits.W)) }
  val readPending = withReset(reset.asBool) { RegInit(false.B) }
  val outBits  = Reg(UInt(entryWidth.W))
  // 保留 BRAM 后的弹性寄存边界，防止综合将其跨层重定时到 Decoder/Rename，
  // 重新形成 RAMB CLKARDCLK -> rename/dispatch 的长组合路径。
  annotate(new ChiselAnnotation {
    override def toFirrtl =
      AttributeAnnotation(outBits.toTarget, "DONT_TOUCH = \"yes\"")
  })
  val outValid = withReset(reset.asBool) { RegInit(false.B) }
  val skidBits = Reg(UInt(entryWidth.W))
  val skidValid = withReset(reset.asBool) { RegInit(false.B) }

  val prefetchedCount = outValid.asUInt +& skidValid.asUInt +& readPending.asUInt
  val readEnable = prefetchedCount < 3.U && memCount =/= 0.U && !io.flush
  // 让数组读数据只驱动一个寄存器。SyncReadMem 生成的“寄存地址后组合读”
  // 在 readData 同时流向 out/skid 时会使 Vivado 2019.2 回退到 LUTRAM。
  val readData   = RegEnable(storage(deqPtr), readEnable)

  io.out.valid := outValid
  val outputWindow = outBits.asTypeOf(new StoredWindow)
  for (slot <- 0 until p.fetchWidth) {
    val result = io.out.bits.slots(slot)
    result.pc := outputWindow.pc + (slot * p.instructionBytes).U
    result.inst := outputWindow.insts(slot)
    result.valid := outputWindow.valid(slot)
    result.predTaken := outputWindow.taken(slot)
    result.predTarget := Mux(outputWindow.taken(slot), outputWindow.target, result.pc + 4.U)
    result.predBhtIdx := outputWindow.bhtIdx(slot)
    result.predGhr := outputWindow.history(slot)
    result.predTageTable := outputWindow.tageTable(slot)
    result.predTageGhr := outputWindow.tageGhr(slot)
    result.predCtr := outputWindow.ctr(slot)
    result.exception := (if (slot == 0) outputWindow.exception else ExceptionInfo.none)
  }

  val occupancy = memCount +& readPending.asUInt +& outValid.asUInt +& skidValid.asUInt
  // ready 只依赖本地占用量，不将 IDU 的同拍消费反向传到 IFU。
  // flush 优先清空所有有效状态；同拍接受的旧窗口不会保留。
  io.in.ready := occupancy < depth.U
  val enqueue = io.in.fire
  // An older RAM/skid window always owns the next output slot. The consumer
  // can free the current slot at this edge; IFU ready still uses occupancy only.
  val dequeue = io.out.fire
  val noQueuedSuccessor = !skidValid && !readPending && memCount === 0.U
  val canWriteOutput = noQueuedSuccessor && (!outValid || io.out.ready)
  val directEnqueue = enqueue && canWriteOutput
  val ramEnqueue = enqueue && !canWriteOutput
  val pendingMove = readPending && (!skidValid || dequeue)

  val inputWindow = Wire(new StoredWindow)
  val validTaken = VecInit(io.in.bits.slots.map(slot => slot.valid && slot.predTaken))
  inputWindow.pc := io.in.bits.slots(0).pc
  inputWindow.target := Mux1H(validTaken, io.in.bits.slots.map(_.predTarget))
  inputWindow.exception := io.in.bits.slots(0).exception
  for (slot <- 0 until p.fetchWidth) {
    val in = io.in.bits.slots(slot)
    inputWindow.insts(slot) := in.inst
    inputWindow.valid(slot) := in.valid
    inputWindow.taken(slot) := validTaken(slot)
    inputWindow.bhtIdx(slot) := in.predBhtIdx
    inputWindow.history(slot) := in.predGhr
    inputWindow.tageTable(slot) := in.predTageTable
    inputWindow.tageGhr(slot) := in.predTageGhr
    inputWindow.ctr(slot) := in.predCtr
    inputWindow.tageTable(slot) := in.predTageTable
    inputWindow.ctr(slot) := in.predCtr
    when(enqueue && in.valid) {
      assert(in.pc === inputWindow.pc + (slot * p.instructionBytes).U)
      if (slot > 0) { assert(!in.exception.valid) }
    }
  }
  when(enqueue) {
    assert(PopCount(validTaken) <= 1.U)
  }
  when(ramEnqueue) {
    storage.write(enqPtr, inputWindow.asUInt)
  }

  when(io.flush) {
    enqPtr   := 0.U
    deqPtr   := 0.U
    memCount := 0.U
    readPending := false.B
    outValid := false.B
    skidValid := false.B
  }.otherwise {
    when(ramEnqueue) {
      enqPtr := enqPtr + 1.U
    }
    when(readEnable) {
      deqPtr := deqPtr + 1.U
    }

    switch(Cat(ramEnqueue, readEnable)) {
      is("b10".U) { memCount := memCount + 1.U }
      is("b01".U) { memCount := memCount - 1.U }
    }

    readPending := (readPending && !pendingMove) || readEnable

    switch(Cat(dequeue, pendingMove)) {
      is("b01".U) {
        when(!outValid) {
          outBits := readData
          outValid := true.B
        }.otherwise {
          skidBits := readData
          skidValid := true.B
        }
      }
      is("b10".U) {
        when(skidValid) {
          outBits := skidBits
          skidValid := false.B
        }.otherwise {
          outValid := false.B
        }
      }
      is("b11".U) {
        when(skidValid) {
          outBits := skidBits
          skidBits := readData
        }.otherwise {
          outBits := readData
          skidValid := false.B
        }
        outValid := true.B
      }
    }
    when(directEnqueue) {
      outBits := inputWindow.asUInt
      outValid := true.B
    }
  }
}
