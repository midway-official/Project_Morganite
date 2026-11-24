package aurora

import chisel3._
import chisel3.util._

/** IDU：与取指宽度对齐的并行译码单元。
  * 流水级（两组寄存器）：
  *   S1：弹性输入寄存器（为空且 S2 可接收时旁路）
  *   S2：ID/DEC 寄存器（DecodeGroup 锁存，Decoder 从 S1 或旁路输入译码）
  * S2 输出直接送入 ISU，仍以寄存边界隔离 Decoder 和 Rename。
  * flush 只清零两级 valid。流水载荷由 valid 严格保护，不复位、不在 flush
  * 时清零，避免宽数据寄存器连接全核 reset/flush 网络。
  * 不再额外复制整组 DecodeGroup 作为纯输出缓冲。
  */
class DecodeGroup(implicit p: AuroraConfig) extends Bundle {
  val uops = Vec(p.coreWidth, new DecodedUop)
}

class IDU(implicit p: AuroraConfig) extends Module {
  val io = IO(new Bundle {
    val in    = Flipped(Decoupled(new FetchGroup))
    val out   = Decoupled(new DecodeGroup)
    val flush = Input(Bool())
  })

  val w = p.coreWidth

  // ---- S1：IF/ID 寄存器（锁存 FetchGroup）----
  val s1Reg   = Reg(new FetchGroup)
  val s1Valid = RegInit(false.B)

  // ---- S2：ID/DEC 寄存器（锁存 DecodeGroup）----
  val s2Reg   = Reg(new DecodeGroup)
  val s2Valid = RegInit(false.B)

  // ---- 两级背压链 ----
  val s2CanShift = !s2Valid || io.out.ready
  val s1CanAccept = !s1Valid || s2CanShift
  io.in.ready := s1CanAccept
  val inputBypasses = !s1Valid && s2CanShift
  val decodeInput = Mux(s1Valid, s1Reg, io.in.bits)
  val decodeValid = s1Valid || io.in.valid

  // ---- S1 更新 ----
  when(s1CanAccept && io.in.valid) { s1Reg := io.in.bits }
  when(io.flush) {
    s1Valid := false.B
  }.elsewhen(s1CanAccept) {
    s1Valid := io.in.valid && !inputBypasses
  }

  // ---- 并行译码（组合逻辑：选择 S1 或空槽旁路，结果写入 S2）----
  val decoders = Seq.fill(w)(Module(new Decoder))
  val decGroup = Wire(new DecodeGroup)
  for (i <- 0 until w) {
    decoders(i).io.inst := decodeInput.slots(i).inst
    decoders(i).io.pc   := decodeInput.slots(i).pc
    val du = WireDefault(decoders(i).io.uop)
    du.valid      := decodeInput.slots(i).valid
    du.predTaken  := decodeInput.slots(i).predTaken
    du.predTarget := decodeInput.slots(i).predTarget
    du.predBhtIdx := decodeInput.slots(i).predBhtIdx
    du.predGhr    := decodeInput.slots(i).predGhr
    du.predTageTable := decodeInput.slots(i).predTageTable
    du.predTageGhr := decodeInput.slots(i).predTageGhr
    du.predCtr    := decodeInput.slots(i).predCtr
    du.exception  := Mux(decodeInput.slots(i).exception.valid,
      decodeInput.slots(i).exception, decoders(i).io.uop.exception)
    decGroup.uops(i) := du
  }

  // ---- S2 更新 ----
  when(s2CanShift && decodeValid) { s2Reg := decGroup }
  when(io.flush) {
    s2Valid := false.B
  }.elsewhen(s2CanShift) {
    s2Valid := decodeValid
  }

  // 本级直接连接 Rename。flush 当拍禁止旧 uop 被分发。
  io.out.valid := s2Valid && !io.flush
  io.out.bits  := s2Reg
  
}
