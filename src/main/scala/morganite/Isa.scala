package morganite

import chisel3._
import chisel3.util._

class Decoded extends Bundle {
  val rd, a, b, c = UInt(5.W)
  val funct = UInt(3.W)
  val op = UInt(7.W)
  val high = UInt(7.W)
  val imm = UInt(32.W)
  val fpOp = UInt(4.W)
  val mem, store, scratch, ctrl, fp, legal = Bool()
}

object Isa {
  def decode(i: UInt, p: GpuParams): Decoded = {
    val d = Wire(new Decoded)
    val op = i(6, 0)
    val f = i(14, 12)
    val hi = i(31, 25)
    val rd = i(11, 7)
    val a = i(19, 15)
    val b = i(24, 20)
    val r = op === 0x33.U
    val imm = op === 0x13.U
    val sys = op === 0x5b.U
    val lui = op === 0x37.U
    val fma = op === 0x43.U
    val mma = p.tensorK.U =/= 0.U && op === 0x47.U
    val graphics = op === 0x57.U
    val gfxFn = Cat(i(26,25),f)
    val gfxLegal = GraphicsOp.enabled(p).map(gfxFn === _.U).foldLeft(false.B)(_ || _)
    val fop = op === 0x53.U
    d.op := op
    d.funct := f
    d.high := hi
    d.mem := Seq(0x03, 0x23, 0x0b, 0x2b).map(n => op === n.U).reduce(_ || _)
    d.store := d.mem && op(5)
    d.scratch := d.mem && op(3)
    d.ctrl := op === 0x7b.U
    d.fp := fma || fop
    d.fpOp := Mux(fma, 0.U, hi(3, 0))
    d.rd := Mux(d.ctrl || d.store, 0.U, rd)
    d.a := Mux(r || imm || d.fp || mma || graphics || d.mem || (d.ctrl && (f === 4.U || f === 5.U)), a, 0.U)
    d.b := Mux(r || d.store || fma || mma || graphics || (fop && hi <= 6.U), b, 0.U)
    d.c := Mux(fma || mma || graphics, i(31, 27), 0.U)
    val rawImm = Mux(d.store, Cat(hi, rd), i(31, 20))
    d.imm := Mux(lui, Cat(i(31, 12), 0.U(12.W)), Cat(Fill(20, rawImm(11)), rawImm))
    val ctrlTarget = f >= 3.U && f <= 6.U
    val ctrlPred = f === 4.U || f === 5.U
    val gfxArity = Mux(gfxFn >= 9.U,b === 0.U && !i(31,27).orR,
      Mux(gfxFn <= 2.U || gfxFn === 7.U || gfxFn === 8.U,!i(31,27).orR,true.B))
    val formatOK = (graphics && gfxLegal && gfxArity) || lui || (sys && i(31, 15) === 0.U && f <= 5.U) ||
      (r && (hi === 0.U || (hi === 32.U && (f === 0.U || f === 5.U)) || (hi === 1.U && f === 0.U))) ||
      (imm && Mux(f === 1.U, hi === 0.U, Mux(f === 5.U, hi === 0.U || hi === 32.U, true.B))) ||
      (d.mem && (f <= 2.U || (!d.store && (f === 4.U || f === 5.U)))) ||
      ((fma || mma) && f === 0.U && i(26, 25) === 0.U) ||
      (fop && f === 0.U && hi >= 1.U && hi <= 12.U && (hi <= 6.U || b === 0.U)) ||
      (d.ctrl && rd === 0.U && (ctrlTarget || i(31, 20) === 0.U) && (ctrlPred || a === 0.U))
    d.legal := formatOK && Seq(d.rd, d.a, d.b, d.c).map(_ < p.registers.U).reduce(_ && _)
    d
  }

  def integer(d: Decoded, a: UInt, b: UInt): UInt = {
    val right = Mux(d.op === 0x13.U, d.imm, b)
    val shift = right(4, 0)
    val alu = MuxLookup(d.funct, 0.U)(Seq(
      0.U -> Mux(d.op === 0x33.U && d.high === 1.U, (a * b)(31, 0),
        Mux(d.op === 0x33.U && d.high === 32.U, a - right, a + right)),
      1.U -> (a << shift)(31, 0), 2.U -> (a.asSInt < right.asSInt), 3.U -> (a < right),
      4.U -> (a ^ right), 5.U -> Mux(d.high(5), (a.asSInt >> shift).asUInt, a >> shift),
      6.U -> (a | right), 7.U -> (a & right)))
    Mux(d.op === 0x37.U, d.imm, alu)
  }
}
