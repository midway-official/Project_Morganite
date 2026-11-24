package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** Four parallel 17x17 DSPs and carry-save reduction to one final sum.
  * Input registration balances issue selection against DSP delay. The optional
  * second edge registers the four partial products ahead of reduction.
  */
class RetimedDspMultiplier(registerInputs: Boolean = true,
                           registerPartials: Boolean = false,
                           keepInputFabric: Boolean = false) extends Module {
  require(!registerPartials || registerInputs)
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val unsigned = Input(Bool())
    val product = Output(UInt(66.W))
  })
  private val aLow = Cat(0.U(1.W), io.a(15, 0)).asSInt
  private val bLow = Cat(0.U(1.W), io.b(15, 0)).asSInt
  private val aHigh = Cat(!io.unsigned && io.a(31), io.a(31, 16)).asSInt
  private val bHigh = Cat(!io.unsigned && io.b(31), io.b(31, 16)).asSInt
  private def partial(a: SInt, b: SInt): SInt = {
    val product = Wire(SInt(34.W))
    annotate(new ChiselAnnotation {
      override def toFirrtl = AttributeAnnotation(product.toTarget, "use_dsp = \"yes\"")
    })
    if (registerInputs) {
      val registeredA = RegNext(a)
      val registeredB = RegNext(b)
      if (registerPartials || keepInputFabric) {
        // Give the issue-side registers a fabric placement independent of the
        // DSP columns. This preserves the existing boundary and latency.
        for (operand <- Seq(registeredA, registeredB)) {
          annotate(new ChiselAnnotation {
            override def toFirrtl = AttributeAnnotation(operand.toTarget,
              "keep = \"true\"")
          })
        }
      }
      product := registeredA * registeredB
      if (registerPartials) RegNext(product) else product
    } else {
      product := a * b
      RegNext(product)
    }
  }
  private val ll = partial(aLow, bLow)
  private val lh = partial(aLow, bHigh)
  private val hl = partial(aHigh, bLow)
  private val hh = partial(aHigh, bHigh)
  private def shifted(value: SInt, bits: Int): UInt =
    (value.pad(66).asUInt << bits)(65, 0)
  private def compress(a: UInt, b: UInt, c: UInt): (UInt, UInt) =
    (a ^ b ^ c, (((a & b) | (b & c) | (c & a)) << 1)(65, 0))
  private val (sum1, carry1) = compress(shifted(ll, 0), shifted(lh, 16), shifted(hl, 16))
  private val (sum2, carry2) = compress(sum1, carry1, shifted(hh, 32))
  io.product := sum2 + carry2
}
