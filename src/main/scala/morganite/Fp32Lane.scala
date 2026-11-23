package morganite

import chisel3._
import chisel3.util._
import hardfloat._
import hardfloat.consts._

object FpOp {
  val fma = 0.U(4.W)
  val add = 1.U(4.W)
  val sub = 2.U(4.W)
  val mul = 3.U(4.W)
  val eq = 4.U(4.W)
  val lt = 5.U(4.W)
  val le = 6.U(4.W)
  val fromI = 7.U(4.W)
  val fromU = 8.U(4.W)
  val toI = 9.U(4.W)
  val toU = 10.U(4.W)
  val toIRtz = 11.U(4.W)
  val toURtz = 12.U(4.W)
}

class FpRequest(tagBits: Int) extends Bundle {
  val a, b, c = UInt(32.W)
  val op = UInt(4.W)
  val tag = UInt(tagBits.W)
  val active = Bool()
}

class FpResult(tagBits: Int) extends Bundle {
  val data = UInt(32.W)
  val flags = UInt(5.W)
  val tag = UInt(tagBits.W)
  val active = Bool()
}

/** One binary32 operation per enabled cycle; metadata advances with the arithmetic. */
class Fp32Lane(tagBits: Int = 12) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new FpRequest(tagBits)))
    val out = Decoupled(new FpResult(tagBits))
  })
  val latency = 6
  val valid = RegInit(VecInit(Seq.fill(latency)(false.B)))
  val advance = !valid.last || io.out.ready
  when(advance) {
    valid(0) := io.in.valid
    for (i <- 1 until latency) valid(i) := valid(i - 1)
  }
  io.in.ready := advance
  io.out.valid := valid.last

  val in = io.in.bits
  val scaledAdd = in.op === FpOp.add || in.op === FpOp.sub
  // MUL needs a zero with the sign of the product to preserve negative zero.
  val productZero = Cat(in.a(31) ^ in.b(31), 0.U(31.W))
  val a1 = RegEnable(recFNFromFN(8, 24, in.a), advance)
  val b1 = RegEnable(recFNFromFN(8, 24, in.b), advance)
  val multiplicand1 = RegEnable(recFNFromFN(8, 24, Mux(scaledAdd, "h3f800000".U, in.b)), advance)
  val c1 = RegEnable(recFNFromFN(8, 24, Mux(scaledAdd, in.b, Mux(in.op === FpOp.mul, productZero, in.c))), advance)
  val op1 = RegEnable(in.op, advance)
  val int1 = RegEnable(in.a, advance)

  val pre = Module(new MulAddRecFNToRaw_preMul(8, 24))
  pre.io.op := (op1 === FpOp.sub).asUInt
  pre.io.a := a1
  pre.io.b := multiplicand1
  pre.io.c := c1
  val mulA2 = RegEnable(pre.io.mulAddA, advance)
  val mulB2 = RegEnable(pre.io.mulAddB, advance)
  val product3 = RegEnable(mulA2 * mulB2, advance)
  val addend3 = ShiftRegister(pre.io.mulAddC, 2, advance)
  val sum4 = RegEnable(product3 +& addend3, advance)
  val post = Module(new MulAddRecFNToRaw_postMul(8, 24))
  post.io.fromPreMul := ShiftRegister(pre.io.toPostMul, 3, advance)
  post.io.mulAddResult := sum4
  post.io.roundingMode := round_near_even
  val round = Module(new RoundRawFNToRecFN(8, 24, 0))
  round.io.in := RegEnable(post.io.rawOut, advance)
  round.io.invalidExc := RegEnable(post.io.invalidExc, advance)
  round.io.infiniteExc := false.B
  round.io.roundingMode := round_near_even
  round.io.detectTininess := tininess_afterRounding

  val compare = Module(new CompareRecFN(8, 24))
  compare.io.a := a1
  compare.io.b := b1
  compare.io.signaling := op1 =/= FpOp.eq
  val cmp = Mux(op1 === FpOp.eq, compare.io.eq, compare.io.lt || (op1 === FpOp.le && compare.io.eq))
  val fromInt = Module(new INToRecFN(32, 8, 24))
  fromInt.io.in := int1
  fromInt.io.signedIn := op1 === FpOp.fromI
  fromInt.io.roundingMode := round_near_even
  fromInt.io.detectTininess := tininess_afterRounding
  val toInt = Module(new RecFNToIN(8, 24, 32))
  toInt.io.in := a1
  toInt.io.signedOut := op1 === FpOp.toI || op1 === FpOp.toIRtz
  toInt.io.roundingMode := Mux(op1 >= FpOp.toIRtz, round_minMag, round_near_even)
  val intFlags = toInt.io.intExceptionFlags
  val asFloat = op1 === FpOp.fromI || op1 === FpOp.fromU
  val asCmp = op1 >= FpOp.eq && op1 <= FpOp.le
  val otherData = Mux(asCmp, cmp, Mux(asFloat, fNFromRecFN(8, 24, fromInt.io.out), toInt.io.out))
  val otherFlags = Mux(asCmp, compare.io.exceptionFlags, Mux(asFloat, fromInt.io.exceptionFlags,
    Cat(intFlags(2, 1).orR, 0.U(3.W), intFlags(0))))
  val other5 = ShiftRegister(Cat(otherFlags, otherData), 4, advance)
  val arithmetic5 = ShiftRegister(op1 <= FpOp.mul, 4, advance)
  val data6 = RegEnable(Mux(arithmetic5, fNFromRecFN(8, 24, round.io.out), other5(31, 0)), advance)
  val flags6 = RegEnable(Mux(arithmetic5, round.io.exceptionFlags, other5(36, 32)), advance)
  val active6 = ShiftRegister(in.active, latency, advance)
  io.out.bits.data := Mux(active6, data6, 0.U)
  io.out.bits.flags := Mux(active6, flags6, 0.U)
  io.out.bits.active := active6
  io.out.bits.tag := ShiftRegister(in.tag, latency, advance)
}
