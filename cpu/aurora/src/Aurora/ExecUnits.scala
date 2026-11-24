package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation
import UopConst._

/** 执行单元读操作数后的输入包。 */
class ExecIn(implicit p: AuroraConfig) extends Bundle {
  val uop  = new RenamedUop
  val src1 = UInt(p.xLen.W)
  val src2 = UInt(p.xLen.W)
}

/** 可配置的硬件 DSP 乘法器，为 ALU 提供可选的快速路径。 */
class UnifiedDspMultiplier(width: Int = 32) extends RawModule {
  require(width > 0, "DSP multiplier requires a positive operand width")
  val io = IO(new Bundle {
    val a = Input(SInt((width + 1).W))
    val b = Input(SInt((width + 1).W))
    val product = Output(UInt((2 * (width + 1)).W))
  })

  val product = Wire(SInt((2 * (width + 1)).W))
  annotate(new ChiselAnnotation {
    override def toFirrtl =
      AttributeAnnotation(product.toTarget, "use_dsp = \"yes\"")
  })
  product := io.a * io.b
  io.product := product.asUInt
}

/** ALU：整数算术 / 逻辑 / 移位。结果在单元内寄存后送往 CDB。 */
class ALU(preparedMultiply: Boolean = false, preparedControl: Boolean = false,
          localCdbData: Boolean = false, registerMultiplyPartials: Boolean = false,
          keepMultiplyInputFabric: Boolean = false)
    (implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new ExecIn))
    val multiplyOperands = if (preparedMultiply) Some(Input(new ExecIn)) else None
    val multiplyUnsigned = if (preparedMultiply) Some(Input(Bool())) else None
    val operationOH = if (preparedControl) Some(Input(UInt(ALU_OP_NUM.W))) else None
    val operationIsMultiply = if (preparedControl) Some(Input(Bool())) else None
    val simpleB = if (preparedControl) Some(Input(UInt(p.xLen.W))) else None
    val out = Valid(new CdbEntry)
    val earlyResult = Output(Valid(new FastAluResult))
    val earlySimpleResult = Output(Valid(new FastAluResult))
    val earlyMultiplyResult = Output(Valid(new FastAluResult))
    val delayedMultiplyWake = if (registerMultiplyPartials)
      Some(Output(Valid(UInt(p.physRegBits.W)))) else None
    val earlyAddressResult = Output(Valid(new FastAluResult))
    val iqCdbPrd = Output(Vec(p.numAlu + 1, UInt(p.physRegBits.W)))
    val cdbBroadcastValid = Output(Bool())
    val cdbData = if (localCdbData) Some(Output(Vec(p.cdbDataCopies, UInt(p.xLen.W)))) else None
    val fullFlushAhead = Input(Bool())
    val flush = Input(Bool())
    val recover = Input(Valid(UInt(p.robIdxBits.W)))
    val robHeadIdx = Input(UInt(p.robIdxBits.W))
  })

  // 将 Core 中原有的 issBits 边界移入 ALU，隔离操作数旁路与执行逻辑；
  // 握手和流水拍数保持不变。
  val inputUop = Reg(new RenamedUop)
  val simpleSrc1 = Reg(UInt(p.xLen.W))
  val simpleB = Reg(UInt(p.xLen.W))
  val simpleImm = Reg(UInt(p.xLen.W))
  val simpleOpOH = Reg(UInt(ALU_OP_NUM.W))
  val addressOpOH = Reg(UInt(ALU_OP_NUM.W))
  dontTouch(addressOpOH)
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(addressOpOH.toTarget,
      "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 32")
  })
  dontTouch(simpleSrc1)
  dontTouch(simpleB)
  dontTouch(simpleImm)
  dontTouch(simpleOpOH)
  for (operandReg <- Seq(
      simpleSrc1, simpleB, simpleImm, simpleOpOH)) {
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        AttributeAnnotation(operandReg.toTarget,
          "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 32")
    })
  }
  val inputValid = RegInit(false.B)
  io.in.ready := true.B
  inputUop := io.in.bits.uop
  simpleSrc1 := io.in.bits.src1
  simpleB := io.simpleB.getOrElse(Mux(io.in.bits.uop.useImm,
    io.in.bits.uop.imm, io.in.bits.src2))
  simpleImm := io.in.bits.uop.imm
  simpleOpOH := io.operationOH.getOrElse(UIntToOH(io.in.bits.uop.aluOp, ALU_OP_NUM))
  addressOpOH := io.operationOH.getOrElse(UIntToOH(io.in.bits.uop.aluOp, ALU_OP_NUM))
  val inputMultiply = io.operationIsMultiply.getOrElse(
    io.in.bits.uop.aluOp === ALU_MUL.U || io.in.bits.uop.aluOp === ALU_MULH.U ||
      io.in.bits.uop.aluOp === ALU_MULHU.U)
  // Publish simple/address availability at the operand boundary. MemIQ uses
  // it in its ready and age selection, independently of the address ALU.
  val simpleResultValid = RegNext(io.in.valid && !io.flush && !inputMultiply,
    false.B)
  val multiplyInput = io.multiplyOperands.getOrElse(io.in.bits)
  val multiplyUnsigned = io.multiplyUnsigned.getOrElse(multiplyInput.uop.aluOp === ALU_MULHU.U)
  require(p.xLen == 32)
  // Move the existing multiply operand boundary after four independent DSP
  // partial products. The following execute stage only reduces and adds;
  // total result/wakeup latency and the one-result-per-cycle contract stay put.
  val multiplier = Module(new RetimedDspMultiplier(
    registerPartials = registerMultiplyPartials, keepInputFabric = keepMultiplyInputFabric))
  multiplier.io.a := multiplyInput.src1
  multiplier.io.b := multiplyInput.src2
  multiplier.io.unsigned := multiplyUnsigned
  when(io.flush) {
    inputValid := false.B
  }.otherwise {
    inputValid := io.in.valid
  }

  val u = inputUop
  def simpleAluResult(
      a: UInt,
      b: UInt,
      imm: UInt,
      opOH: UInt): UInt = {
    val shiftLeft = opOH(ALU_SLL)
    val shiftArithmetic = opOH(ALU_SRA)
    val isShift = shiftLeft || opOH(ALU_SRL) || shiftArithmetic
    val shiftInput = Mux(shiftLeft, Reverse(a), a)
    val shiftFill = shiftArithmetic && a(p.xLen - 1)
    val shiftWide = Cat(Fill(p.xLen, shiftFill), shiftInput)
    val shiftRight = (shiftWide >> b(p.shiftAmountBits - 1, 0))(
      p.xLen - 1, 0)
    val shiftResult = Mux(shiftLeft, Reverse(shiftRight), shiftRight)
    Mux1H(Seq(
      opOH(ALU_ADD) -> (a + b),
      opOH(ALU_SUB) -> (a - b),
      opOH(ALU_AND) -> (a & b),
      opOH(ALU_OR) -> (a | b),
      opOH(ALU_XOR) -> (a ^ b),
      opOH(ALU_NOR) -> ~(a | b),
      isShift -> shiftResult,
      opOH(ALU_LU12I) -> imm,
      opOH(ALU_COPY) -> imm,
      opOH(ALU_SLT) -> Cat(0.U((p.xLen - 1).W), a.asSInt < b.asSInt),
      opOH(ALU_SLTU) -> Cat(0.U((p.xLen - 1).W), a < b)
    ))
  }
  val simpleResult = simpleAluResult(
    simpleSrc1, simpleB, simpleImm, simpleOpOH)
  val multiplyHigh = simpleOpOH(ALU_MULH) || simpleOpOH(ALU_MULHU)
  val multiplyOp = simpleOpOH(ALU_MUL) || multiplyHigh
  val product = multiplier.io.product
  val multiplyResult = Mux(simpleOpOH(ALU_MUL),
    product(p.xLen - 1, 0),
    product(2 * p.xLen - 1, p.xLen))
  val executeResult = Mux1H(Seq(
    simpleOpOH(ALU_MUL) -> product(p.xLen - 1, 0),
    multiplyHigh -> product(2 * p.xLen - 1, p.xLen),
    !multiplyOp -> simpleResult))

  // 普通 ALU 与乘法共用结果寄存器；该级每拍都可接收一条指令。
  val resultValid = RegInit(false.B)
  val resultPrd = Reg(UInt(p.physRegBits.W))
  val iqResultPrd = Reg(Vec(p.numAlu + 1, UInt(p.physRegBits.W)))
  val resultRobIdx = Reg(UInt(p.robIdxBits.W))
  val resultData = Reg(UInt(p.xLen.W))
  for (target <- 0 until p.numAlu + 1) {
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(
        iqResultPrd(target).toTarget,
        "EQUIVALENT_REGISTER_REMOVAL = \"NO\", max_fanout = 8")
    })
    io.iqCdbPrd(target) := iqResultPrd(target)
  }

  // 结果 payload 由 resultValid 屏蔽；flush 当拍允许覆写不可见数据，
  // 避免 ROB flush 进入结果寄存器的 clock enable。
  val (execute, writePrd, writeRobIdx, writeData) = if (registerMultiplyPartials) {
    class Completion extends Bundle {
      val prd = UInt(p.physRegBits.W)
      val robIdx = UInt(p.robIdxBits.W)
      val data = UInt(p.xLen.W)
    }
    def survives(idx: UInt): Bool = !io.flush && !(io.recover.valid &&
      RobOrder.isYounger(idx, io.recover.bits, io.robHeadIdx))
    val enterMultiply = inputValid && multiplyOp && survives(u.robIdx)
    val mulValid = RegNext(enterMultiply, false.B)
    val mulHigh = RegNext(multiplyHigh)
    val mulPrd = RegNext(u.prd)
    val mulRobIdx = RegNext(u.robIdx)
    val mul = Wire(new Completion)
    mul.prd := mulPrd
    mul.robIdx := mulRobIdx
    mul.data := Mux(mulHigh, product(2 * p.xLen - 1, p.xLen), product(p.xLen - 1, 0))
    val simple = Wire(new Completion)
    simple.prd := u.prd
    simple.robIdx := u.robIdx
    simple.data := simpleResult
    val simpleValid = inputValid && !multiplyOp && survives(u.robIdx)
    val multiplyValid = mulValid && survives(mulRobIdx)
    val deferred = Reg(new Completion)
    val deferredValid = RegInit(false.B)
    val hasDeferred = deferredValid && survives(deferred.robIdx)
    // One issue per cycle and a one-cycle latency difference can generate
    // at most one excess completion. Preserve age when the two lanes meet.
    assert(!(hasDeferred && multiplyValid && simpleValid))
    val selectMul = multiplyValid && !hasDeferred
    val selectSimple = simpleValid && !hasDeferred && !multiplyValid
    val selected = Mux1H(Seq(hasDeferred -> deferred, selectMul -> mul,
      selectSimple -> simple))
    deferredValid := (hasDeferred && (multiplyValid || simpleValid)) ||
      (multiplyValid && simpleValid)
    when(hasDeferred && multiplyValid) {
      deferred := mul
    }.elsewhen(simpleValid && (hasDeferred || multiplyValid)) {
      deferred := simple
    }
    io.delayedMultiplyWake.get.valid := enterMultiply && u.writesReg && u.prd.orR
    io.delayedMultiplyWake.get.bits := u.prd
    io.earlyMultiplyResult.valid := mulValid && p.allowAluMultiplyForward.B
    io.earlyMultiplyResult.bits.prd := mul.prd
    io.earlyMultiplyResult.bits.data := mul.data
    (hasDeferred || multiplyValid || simpleValid, selected.prd, selected.robIdx, selected.data)
  } else {
    io.earlyMultiplyResult.valid := inputValid && multiplyOp && p.allowAluMultiplyForward.B
    io.earlyMultiplyResult.bits.prd := u.prd
    io.earlyMultiplyResult.bits.data := multiplyResult
    (inputValid, u.prd, u.robIdx, executeResult)
  }
  io.cdbData.foreach { copies =>
    for (target <- 0 until p.cdbDataCopies) {
      copies(target) := CdbEntry.localDataCopy(writeData, execute)
    }
  }
  io.earlyResult.valid := execute
  io.earlyResult.bits.prd := writePrd
  io.earlyResult.bits.data := writeData
  io.earlySimpleResult.valid := simpleResultValid
  io.earlySimpleResult.bits.prd := u.prd
  io.earlySimpleResult.bits.data := simpleResult
  io.earlyAddressResult.valid := simpleResultValid
  io.earlyAddressResult.bits.prd := u.prd
  io.earlyAddressResult.bits.data := simpleAluResult(
    simpleSrc1, simpleB, simpleImm, addressOpOH)
  when(execute) {
    resultPrd := writePrd
    for (target <- 0 until p.numAlu + 1) {
      iqResultPrd(target) := writePrd
    }
    resultRobIdx := writeRobIdx
    resultData := writeData
  }
  when(io.flush) {
    resultValid := false.B
  }.otherwise {
    resultValid := execute
  }

  // Core.flush is RegNext(fullFlushAhead). Qualify at the existing result
  // boundary so broadcast valid no longer crosses the global flush net.
  // Keep resultValid unchanged for the execution/fast-wakeup contract.
  val cdbBroadcastValid = RegNext(
    execute && !io.flush && !io.fullFlushAhead, false.B)
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(
      cdbBroadcastValid.toTarget, "max_fanout = 32")
  })
  io.cdbBroadcastValid := cdbBroadcastValid

  io.out.valid := resultValid
  io.out.bits := CdbEntry.default
  io.out.bits.valid  := io.out.valid
  io.out.bits.prd    := resultPrd
  io.out.bits.data   := resultData
  io.out.bits.robIdx := resultRobIdx
}

/** DIV/MOD：归一化 radix-4 恢复除法单元。
  * Setup、radix-4 iteration 和 signed finalize 各自跨越寄存器边界。迭代
  * 周期从三个已寄存的除数倍数并行形成差值，再只经过选择器写回余数；
  * 不把两个 32-bit 加减器串进同一组合路径。归一化会跳过无意义的高零
  * 位，最坏执行 ceil(xLen/2) 轮，常见小整数只需一到数轮。
  */
class DivUnit(implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ExecIn))
    val out = Valid(new CdbEntry)
    val flush = Input(Bool())
    val recover = Input(Valid(UInt(p.robIdxBits.W)))
    val robHeadIdx = Input(UInt(p.robIdxBits.W))
  })

  private val multipleWidth = p.xLen + 2
  private val pairCountBits = log2Ceil((p.xLen + 1) / 2 + 1)

  val setupValid = RegInit(false.B)
  val iterateValid = RegInit(false.B)
  val finalizeValid = RegInit(false.B)
  val resultValid = RegInit(false.B)
  val dividendMagnitude = Reg(UInt(p.xLen.W))
  val divisorMagnitude = Reg(UInt(p.xLen.W))
  val remainderMagnitude = Reg(UInt(p.xLen.W))
  val quotientMagnitude = Reg(UInt(p.xLen.W))
  val divisorOnce = Reg(UInt(multipleWidth.W))
  val divisorTwice = Reg(UInt(multipleWidth.W))
  val divisorThree = Reg(UInt(multipleWidth.W))
  val pairsRemaining = Reg(UInt(pairCountBits.W))
  val quotientNegative = Reg(Bool())
  val remainderNegative = Reg(Bool())
  val returnRemainder = Reg(Bool())
  val operationPrd = Reg(UInt(p.physRegBits.W))
  val operationRobIdx = Reg(UInt(p.robIdxBits.W))
  val resultData = Reg(UInt(p.xLen.W))

  val inputUnsigned = io.in.bits.uop.divOp === DIV_WU.U ||
    io.in.bits.uop.divOp === MOD_WU.U
  val inputRemainder = io.in.bits.uop.divOp === MOD_W.U ||
    io.in.bits.uop.divOp === MOD_WU.U
  val inputDividendNegative = !inputUnsigned &&
    io.in.bits.src1(p.xLen - 1)
  val inputDivisorNegative = !inputUnsigned &&
    io.in.bits.src2(p.xLen - 1)
  val inputDividendMagnitude = Wire(UInt(p.xLen.W))
  val inputDivisorMagnitude = Wire(UInt(p.xLen.W))
  inputDividendMagnitude := Mux(inputDividendNegative,
    (~io.in.bits.src1).asUInt + 1.U, io.in.bits.src1)
  inputDivisorMagnitude := Mux(inputDivisorNegative,
    (~io.in.bits.src2).asUInt + 1.U, io.in.bits.src2)

  val dividendLeadingZeros = PriorityEncoder(Reverse(dividendMagnitude))
  val divisorLeadingZeros = PriorityEncoder(Reverse(divisorMagnitude))
  val dividendMsb = (p.xLen - 1).U - dividendLeadingZeros
  val divisorMsb = (p.xLen - 1).U - divisorLeadingZeros
  val quotientTopBit = dividendMsb - divisorMsb
  val initialBaseShift = Cat(
    quotientTopBit(p.shiftAmountBits - 1, 1), 0.U(1.W))
  val divisorWide = Cat(0.U(2.W), divisorMagnitude)
  val shiftedDivisorWide = divisorWide << initialBaseShift
  val initialDivisorOnce = shiftedDivisorWide(multipleWidth - 1, 0)
  val initialDivisorTwice = (initialDivisorOnce << 1)(multipleWidth - 1, 0)
  val initialDivisorThree = initialDivisorOnce + initialDivisorTwice
  val initialPairCount = (quotientTopBit >> 1) +& 1.U

  val remainderWide = Cat(0.U(2.W), remainderMagnitude)
  val subtractOnce = remainderWide - divisorOnce
  val subtractTwice = remainderWide - divisorTwice
  val subtractThree = remainderWide - divisorThree
  val canSubtractOnce = remainderWide >= divisorOnce
  val canSubtractTwice = remainderWide >= divisorTwice
  val canSubtractThree = remainderWide >= divisorThree
  val quotientDigit = Wire(UInt(2.W))
  val nextRemainderWide = Wire(UInt(multipleWidth.W))
  when(canSubtractThree) {
    quotientDigit := 3.U
    nextRemainderWide := subtractThree
  }.elsewhen(canSubtractTwice) {
    quotientDigit := 2.U
    nextRemainderWide := subtractTwice
  }.elsewhen(canSubtractOnce) {
    quotientDigit := 1.U
    nextRemainderWide := subtractOnce
  }.otherwise {
    quotientDigit := 0.U
    nextRemainderWide := remainderWide
  }
  val nextQuotientMagnitude = Cat(
    quotientMagnitude(p.xLen - 3, 0), quotientDigit)
  val nextRemainderMagnitude = nextRemainderWide(p.xLen - 1, 0)

  val signedQuotient = Mux(quotientNegative,
    (~quotientMagnitude).asUInt + 1.U, quotientMagnitude)
  val signedRemainder = Mux(remainderNegative,
    (~remainderMagnitude).asUInt + 1.U, remainderMagnitude)

  val operationLive = setupValid || iterateValid || finalizeValid || resultValid
  val operationKilled = io.recover.valid && operationLive &&
    RobOrder.isYounger(operationRobIdx, io.recover.bits, io.robHeadIdx)
  io.in.ready := !operationLive && !io.flush && !io.recover.valid

  // The result is observed only while resultValid. Prepare payloads without
  // the late IQ fire/zero-divisor decision in a 32-bit clock-enable cone. Hold
  // an outstanding result through recovery, as required by the original unit.
  when(!resultValid) {
    resultData := Mux(finalizeValid,
      Mux(returnRemainder, signedRemainder, signedQuotient),
      Mux(inputRemainder, io.in.bits.src1, Fill(p.xLen, 1.U(1.W)).asUInt))
  }

  when(io.flush) {
    setupValid := false.B
    iterateValid := false.B
    finalizeValid := false.B
    resultValid := false.B
  }.elsewhen(io.recover.valid) {
    when(operationKilled) {
      setupValid := false.B
      iterateValid := false.B
      finalizeValid := false.B
      resultValid := false.B
    }
  }.otherwise {
    when(resultValid) {
      resultValid := false.B
    }

    when(io.in.fire) {
      operationPrd := io.in.bits.uop.prd
      operationRobIdx := io.in.bits.uop.robIdx
      returnRemainder := inputRemainder
      quotientNegative := inputDividendNegative ^ inputDivisorNegative
      remainderNegative := inputDividendNegative
      dividendMagnitude := inputDividendMagnitude
      divisorMagnitude := inputDivisorMagnitude

      when(io.in.bits.src2 === 0.U) {
        resultValid := true.B
      }.otherwise {
        setupValid := true.B
      }
    }.elsewhen(setupValid) {
      setupValid := false.B
      // The iteration state is first consumed after setup. Initialize it from
      // the registered operands and keep the IQ bypass off the remainder flops.
      quotientMagnitude := 0.U
      remainderMagnitude := dividendMagnitude
      when(dividendMagnitude < divisorMagnitude) {
        finalizeValid := true.B
      }.otherwise {
        divisorOnce := initialDivisorOnce
        divisorTwice := initialDivisorTwice
        divisorThree := initialDivisorThree
        pairsRemaining := initialPairCount
        iterateValid := true.B
      }
    }.elsewhen(iterateValid) {
      quotientMagnitude := nextQuotientMagnitude
      remainderMagnitude := nextRemainderMagnitude
      when(pairsRemaining === 1.U) {
        iterateValid := false.B
        finalizeValid := true.B
      }.otherwise {
        divisorOnce := divisorOnce >> 2
        divisorTwice := divisorTwice >> 2
        divisorThree := divisorThree >> 2
        pairsRemaining := pairsRemaining - 1.U
      }
    }.elsewhen(finalizeValid) {
      finalizeValid := false.B
      resultValid := true.B
    }
  }

  assert(PopCount(Seq(setupValid, iterateValid, finalizeValid, resultValid)) <= 1.U)
  when(iterateValid) { assert(pairsRemaining =/= 0.U) }

  io.out.valid := resultValid && !io.flush && !io.recover.valid
  io.out.bits := CdbEntry.default
  io.out.bits.valid := io.out.valid
  io.out.bits.prd := operationPrd
  io.out.bits.data := resultData
  io.out.bits.robIdx := operationRobIdx
}
