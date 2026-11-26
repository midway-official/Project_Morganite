package morganite

import chisel3._
import chisel3.util._

object GraphicsOp {
  val delta = 0; val constant = 1; val bias = 2
  val bounds = 3; val bin = 4
  val edge = 5; val cover = 6
  val depth = 7; val blend = 8
  val rcp = 9; val rsqrt = 10
  def enabled(p: GpuParams): Seq[Int] = Seq(
    p.triangleSetup -> Seq(delta,constant,bias), p.binning -> Seq(bounds,bin),
    p.raster -> Seq(edge,cover), p.rop -> Seq(depth,blend), p.sfu -> Seq(rcp,rsqrt)
  ).collect { case (true, ops) => ops }.flatten
}

/** All paths retire at the existing six-cycle arithmetic writeback port. */
class GraphicsLane(p: GpuParams) extends Module {
  val io = IO(new Bundle {
    val op = Input(UInt(5.W))
    val a,b,c = Input(UInt(32.W))
    val result = Output(UInt(32.W))
  })
  import GraphicsOp._
  val a = io.a; val b = io.b; val c = io.c
  def x(v: UInt): SInt = v(15,0).asSInt
  def y(v: UInt): SInt = v(31,16).asSInt
  val options = collection.mutable.ArrayBuffer.empty[(UInt,UInt)]
  def result(op: Int, value: UInt, latency: Int): Unit = {
    options += op.U -> ShiftRegister(value,6-latency)
  }
  if (p.triangleSetup) {
    val aa = RegNext(y(a)-&y(b))
    val bb = RegNext(x(b)-&x(a))
    val xy = RegNext(x(a)*y(b)); val yx = RegNext(y(a)*x(b))
    result(delta,Cat(bb(15,0),aa(15,0)),1)
    result(constant,(xy-yx).asUInt,1)
    result(bias,Mux(aa > 0.S || (aa === 0.S && bb > 0.S),0.U,"hffffffff".U),1)
  }
  if (p.binning) {
    // Unsigned 12.4 viewport positions; each bin covers 8 by 8 pixels.
    def tileX(v: UInt) = v(14,7)
    def tileY(v: UInt) = v(30,23)
    def bound(axis: UInt => UInt, low: Boolean): UInt =
      Seq(a,b,c).map(axis).reduce((i,j) => Mux(if (low) i < j else i > j,i,j))
    result(bounds,Cat(bound(tileY,false),bound(tileY,true),bound(tileX,false),bound(tileX,true)),0)
    val hit = tileX(b) >= a(7,0) && tileX(b) <= a(15,8) &&
      tileY(b) >= a(23,16) && tileY(b) <= a(31,24)
    result(bin,Mux(hit,c,0.U),0)
  }
  if (p.raster) {
    val ax = RegNext(x(a)*x(b)); val by = RegNext(y(a)*y(b))
    result(edge,(RegNext(ax+by)+ShiftRegister(c.asSInt,2)).asUInt,2)
    result(cover,!(a(31)||b(31)||c(31)),0)
  }
  if (p.rop) {
    def nan(v: UInt) = v(30,23).andR && v(22,0).orR
    val ordered = !nan(a) && !nan(b) && (a(30,0).orR || b(30,0).orR)
    result(depth,ordered && Mux(a(31) =/= b(31),a(31),Mux(a(31),a > b,a < b)),0)
    val alpha = a(31,24)
    val rgba = (0 until 4).map { i =>
      val src = if (i==3) 255.U(8.W) else a(8*i+7,8*i)
      val dst = b(8*i+7,8*i)
      val left = RegNext(src*alpha); val right = RegNext(dst*(255.U-alpha))
      val n = RegNext(left +& right + 128.U)
      ((n + (n >> 8)) >> 8)(7,0)
    }
    result(blend,Cat(rgba.reverse),2)
  }
  if (p.sfu) {
    // The ROM holds the 15 most significant fraction bits of a midpoint estimate.
    // Subnormal inputs/outputs flush to signed zero. Exact powers of two bypass the ROM.
    val exponent = a(30,23)
    val frac = a(22,0)
    val rsq = io.op === rsqrt.U
    val odd = !exponent(0) // unbiased exponent parity
    val index = Cat(Mux(rsq, Mux(odd,2.U(2.W),1.U(2.W)),0.U(2.W)),frac(22,16))
    val rom = VecInit((0 until 384).map { i =>
      val m = 1.0 + ((i%128)+0.5)/128.0
      val normalized = i/128 match {
        case 0 => 2.0/m
        case 1 => 2.0/math.sqrt(m)
        case _ => 2.0/math.sqrt(2.0*m)
      }
      math.round((normalized-1.0)*32768).U(15.W)
    })
    val exact = !frac.orR && (!rsq || !odd)
    val exp = Mux(rsq,(380.U(10.W)-exponent) >> 1,253.U(10.W)-exponent) + exact
    val e = RegNext(exp)
    val mantissa = RegNext(Mux(exact,0.U,Cat(rom(index),0.U(8.W))))
    val sign = RegNext(a(31))
    val special = RegNext(exponent.andR || !exponent.orR || (rsq && a(31)))
    val inf = Cat(a(31),"h7f800000".U(31.W))
    val zero = Cat(a(31),0.U(31.W))
    val exceptional = RegNext(Mux(!exponent.orR,inf,
      Mux(frac.orR && exponent.andR || (rsq && a(31)),"h7fc00000".U,zero)))
    // e wraps above 255 only on reciprocal underflow (largest inputs).
    val normal = Cat(sign,Mux(!e.orR || e > 254.U,0.U(31.W),Cat(e(7,0),mantissa)))
    val value = Mux(special,exceptional,normal)
    result(rcp,value,1); result(rsqrt,value,1)
  }
  io.result := MuxLookup(ShiftRegister(io.op,6),0.U)(options.toSeq)
}
