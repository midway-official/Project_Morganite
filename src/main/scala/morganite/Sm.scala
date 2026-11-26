package morganite

import chisel3._
import chisel3.util._

class Group(p: GpuParams) extends Bundle {
  val id, argument, programBase = UInt(32.W)
  val threads = UInt(p.threadCountBits.W)
  val words = UInt((p.pcBits + 1).W)
}
class GroupResult extends Bundle {
  val fault = UInt(4.W)
  val address, group, cycles, fpOps, fmaOps, memStalls, tensorOps = UInt(32.W)
}
class InstructionWrite(p: GpuParams) extends Bundle {
  val address = UInt(p.pcBits.W)
  val data = UInt(32.W)
}
class WarpIssue(p: GpuParams) extends Bundle {
  val warp = UInt(p.warpBits.W)
  val pc = UInt((p.pcBits + 1).W)
  val mask = UInt(p.warpWidth.W)
  val decoded = new Decoded
}
class WriteTag(p: GpuParams) extends Bundle {
  val warp = UInt(p.warpBits.W)
  val row = UInt(p.rowBits.W)
  val dest = UInt(p.registerBits.W)
  val mask = UInt(p.lanes.W)
  val last, fp, fma, tensor = Bool()
}
class SplitFrame(p: GpuParams) extends Bundle {
  val parent, other = UInt(p.warpWidth.W)
  val target = UInt((p.pcBits + 1).W)
}

class Sm(p: GpuParams, smId: Int = 0) extends Module {
  val io = IO(new Bundle {
    val program = Flipped(Valid(new InstructionWrite(p)))
    val start = Flipped(Decoupled(new Group(p)))
    val result = Decoupled(new GroupResult)
    val global1 = if (p.globalSlots == 2) Some(new VectorPort(p)) else None
    val global = new VectorPort(p)
    val fetch = if (p.iCacheBytes > 0) Some(new VectorPort(p)) else None
    val invalidate = Input(Bool())
    val iHits, iMisses = Output(UInt(32.W))
    val cancel = Input(Bool())
  })
  val idle :: run :: finish :: Nil = Enum(3)
  val state = RegInit(idle)
  val group = Reg(new Group(p))
  val summary = Reg(new GroupResult)
  io.start.ready := state === idle
  io.result.valid := state === finish
  io.result.bits := summary
  when(io.result.fire) { state := idle }

  def warpVec[T <: Data](gen: T) = Reg(Vec(p.warpSlots, gen))
  val pc = warpVec(UInt((p.pcBits + 1).W))
  val instruction = warpVec(UInt(32.W))
  val fetched = warpVec(Bool())
  val live = warpVec(Bool())
  val resident = warpVec(Bool())
  val locked = warpVec(Bool())
  val barrier = warpVec(Bool())
  val mask = warpVec(UInt(p.warpWidth.W))
  val flags = warpVec(UInt(5.W))
  val pending = warpVec(UInt(p.registers.W))
  val inflight = warpVec(UInt(5.W))
  val depth = warpVec(UInt(log2Ceil(p.splitDepth + 1).W))
  val frames = warpVec(Vec(p.splitDepth, new SplitFrame(p)))

  when(io.start.fire) {
    state := run
    group := io.start.bits
    summary := 0.U.asTypeOf(new GroupResult)
    summary.group := io.start.bits.id
    when(!io.start.bits.threads.orR || io.start.bits.threads > p.maxThreads.U ||
        !io.start.bits.words.orR || io.start.bits.words > p.programWords.U) {
      summary.fault := 1.U
      summary.address := io.start.bits.programBase
    }
    for (w <- 0 until p.warpSlots) {
      val lanes = VecInit((0 until p.warpWidth).map(t => (w * p.warpWidth + t).U < io.start.bits.threads)).asUInt
      mask(w) := lanes
      live(w) := lanes.orR
      resident(w) := lanes.orR
      pc(w) := 0.U
      fetched(w) := false.B
      locked(w) := false.B
      barrier(w) := false.B
      flags(w) := 0.U
      pending(w) := 0.U
      inflight(w) := 0.U
      depth(w) := 0.U
    }
  }
  val healthy = state === run && !summary.fault.orR && !io.cancel
  def fail(code: UInt, address: UInt): Unit = {
    when(state === run && !summary.fault.orR) {
      summary.fault := code
      summary.address := address
    }
  }
  when(io.cancel) { fail(7.U, 0.U) }
  when(state === run) { summary.cycles := summary.cycles + 1.U }

  val memIdle :: gather :: send :: awaitReply :: writeRegs :: Nil = Enum(5)
  // Wide configurations reserve two global instructions and one local instruction.
  // The execution pipe still gathers exactly one physical RF slice per cycle.
  val slotCount = if (p.globalSlots == 2) 3 else 1
  val slotState = RegInit(VecInit(Seq.fill(slotCount)(memIdle)))
  val memIssues = Reg(Vec(slotCount, new WarpIssue(p)))
  val requests = Reg(Vec(slotCount, new VectorRequest(p)))
  val loads = Reg(Vec(slotCount, Vec(p.warpWidth, UInt(32.W))))
  val writeBeat = Reg(Vec(slotCount, UInt(p.beatBits.W)))
  val scratch = Module(new Scratchpad(p))
  val ports = Seq(io.global) ++ io.global1.toSeq :+ scratch.io
  for ((port, i) <- ports.zipWithIndex) {
    val slot = if (slotCount == 1) 0 else i
    val forPort = if (slotCount > 1) true.B else memIssues(slot).decoded.scratch === (i == 1).B
    port.req.valid := slotState(slot) === send && forPort
    port.req.bits := requests(slot)
    port.rsp.ready := slotState(slot) === awaitReply && forPort
    when(port.req.fire) { slotState(slot) := awaitReply }
    when(port.rsp.fire) {
      val reply = port.rsp.bits
      when(reply.fault.orR || requests(slot).store || !healthy) {
        slotState(slot) := memIdle
        locked(memIssues(slot).warp) := false.B
        when(reply.fault.orR) { fail(reply.fault, reply.address) }
      }.otherwise {
        loads(slot) := reply.data
        writeBeat(slot) := 0.U
        slotState(slot) := writeRegs
      }
    }
  }
  val globalFree = VecInit(slotState.take(p.globalSlots).map(_ === memIdle)).asUInt
  def available(local: Bool): Bool = Mux(local, slotState.last === memIdle, globalFree.orR)
  def allocate(local: Bool): UInt = if (slotCount == 1) 0.U
    else Mux(local, (slotCount-1).U, PriorityEncoder(globalFree))
  val wbSlots = VecInit(slotState.map(_ === writeRegs)).asUInt
  when(state === run && slotState.map(s => s === awaitReply || s === send).reduce(_ || _)) {
    summary.memStalls := summary.memStalls + 1.U
  }

  val arb = Module(new RRArbiter(new WarpIssue(p), p.warpSlots))
  val beats = Module(new WarpBeats(p))
  val meta = RegEnable(arb.io.out.bits, arb.io.out.fire)
  val arbDec = arb.io.out.bits.decoded
  val immediateJump = p.earlyBranch.B && arbDec.legal && arbDec.ctrl && arbDec.funct === 3.U
  beats.io.issue.valid := arb.io.out.valid && !immediateJump
  beats.io.issue.bits := arb.io.out.bits.mask
  arb.io.out.ready := beats.io.issue.ready
  beats.io.beat.ready := true.B
  val fetchIdle = WireDefault(true.B)
  io.iHits := 0.U
  io.iMisses := 0.U
  if (p.iCacheBytes > 0) {
    val cache = Module(new ReadOnlyL1(p, p.iCacheBytes))
    io.fetch.get <> cache.io.memory
    cache.io.invalidate := io.invalidate
    io.iHits := cache.io.hits
    io.iMisses := cache.io.misses
    fetchIdle := cache.io.idle
    // A line buffer supplies all ready warps, including different PCs in that line.
    val offsetBits = log2Ceil(p.lineWords)
    val hot = RegInit(false.B)
    val hotLine = Reg(UInt((p.pcBits+1).W))
    val hotData = Reg(Vec(p.lineWords, UInt(32.W)))
    val misses = Module(new RRArbiter(UInt((p.pcBits+1).W), p.warpSlots))
    val wanted = misses.io.out.bits
    cache.io.lookup.valid := misses.io.out.valid
    misses.io.out.ready := cache.io.lookup.ready
    cache.io.lookup.bits.address := group.programBase + (wanted << (offsetBits+2))
    cache.io.lookup.bits.mask := VecInit((0 until p.lineWords).map(i =>
      ((wanted << offsetBits) +& i.U) < group.words)).asUInt
    val requested = RegEnable(wanted, cache.io.lookup.fire)
    cache.io.result.ready := true.B
    when(cache.io.result.fire) {
      when(cache.io.result.bits.fault.orR) { fail(cache.io.result.bits.fault, cache.io.result.bits.address) }
        .otherwise { hot := true.B; hotLine := requested; hotData := cache.io.result.bits.data }
    }
    when(io.invalidate) { hot := false.B }
    for (w <- 0 until p.warpSlots) {
      val want = healthy && live(w) && !fetched(w) && !locked(w) && !barrier(w)
      val line = pc(w) >> offsetBits
      val hit = hot && hotLine === line
      misses.io.in(w).valid := want && pc(w) < group.words && !hit
      misses.io.in(w).bits := line
      when(want && (hit || pc(w) >= group.words)) {
        val data = if (p.lineWords == 1) hotData(0) else hotData(pc(w)(offsetBits-1,0))
        instruction(w) := Mux(pc(w) < group.words, data, 0.U)
        fetched(w) := true.B
      }
    }
  }
  for (w <- 0 until p.warpSlots) {
    // One read port per warp. All replicas are loaded together at the job boundary.
    if (p.iCacheBytes == 0) {
    val imem = Mem(p.programWords, UInt(32.W))
    when(io.program.valid && state === idle) { imem.write(io.program.bits.address, io.program.bits.data) }
    when(healthy && live(w) && !fetched(w) && !locked(w) && !barrier(w)) {
      instruction(w) := Mux(pc(w) < group.words, imem(pc(w)(p.pcBits - 1, 0)), 0.U)
      fetched(w) := true.B
    }
    }
    val d = Isa.decode(instruction(w), p)
    if (p.tensorK > 0) {
      val completeTiles = mask(w).asTypeOf(Vec(p.warpWidth / 4, UInt(4.W))).map(m => !m.orR || m.andR)
      when(d.op === 0x47.U && !completeTiles.reduce(_ && _)) { d.legal := false.B }
    }
    val dep = Seq(d.a, d.b, d.c, d.rd).map(idx => pending(w)(idx(p.registerBits - 1, 0))).reduce(_ || _)
    val jump = p.earlyBranch.B && d.legal && d.ctrl && d.funct === 3.U
    val serial = d.mem || (d.ctrl && !(p.earlyBranch.B && (d.funct === 3.U || d.funct === 4.U))) ||
      !d.legal || (d.op === 0x5b.U && d.funct === 5.U)
    val port = arb.io.in(w)
    port.valid := healthy && live(w) && fetched(w) && !locked(w) && !barrier(w) &&
      !dep && (!serial || !inflight(w).orR) && (!d.mem || available(d.scratch)) && !wbSlots.orR
    port.bits.warp := w.U
    port.bits.pc := pc(w)
    port.bits.mask := mask(w)
    port.bits.decoded := d
    when(port.fire) {
      fetched(w) := false.B
      pc(w) := pc(w) + 1.U
      when(d.mem || (d.ctrl && !jump) || !d.legal) { locked(w) := true.B }
      when(jump) {
        val target = pc(w).zext +& d.imm.asSInt
        when(target < 0.S || target >= group.words.zext) { fail(2.U, group.programBase + (pc(w) << 2)) }
          .otherwise { pc(w) := target.asUInt }
      }
      when(d.mem && d.legal) {
        val slot = allocate(d.scratch)
        val request = requests(slot)
        slotState(slot) := gather
        memIssues(slot) := port.bits
        request.mask := mask(w)
        request.size := d.funct(1, 0)
        request.unsigned := d.funct(2)
        request.store := d.store
      }
    }
  }

  val rf = Module(new LaneRegisters(p))
  val beat = beats.io.beat.bits
  val row = (meta.warp * p.beats.U + beat.index)(p.rowBits - 1, 0)
  rf.io.rd.valid := beats.io.beat.fire
  rf.io.rd.bits.row := row
  rf.io.rd.bits.source := VecInit(Seq(meta.decoded.a, meta.decoded.b, meta.decoded.c).map(_(p.registerBits - 1, 0)))
  val exValid = RegNext(beats.io.beat.fire, false.B)
  val ex = RegEnable(meta, beats.io.beat.fire)
  val slice = RegEnable(beat, beats.io.beat.fire)
  val exRow = RegEnable(row, beats.io.beat.fire)
  val d = ex.decoded
  val w = ex.warp
  val a = rf.io.data(0)
  val b = rf.io.data(1)
  val arithmetic = exValid && d.legal && !d.mem && !d.ctrl
  val tag = Wire(new WriteTag(p))
  tag.warp := w
  tag.row := exRow
  tag.dest := d.rd
  tag.mask := slice.mask
  tag.last := slice.last
  tag.fp := d.fp
  tag.fma := d.fp && d.fpOp === FpOp.fma
  tag.tensor := d.op === 0x47.U
  val latency = 6
  val retire = ShiftRegister(arithmetic, latency, false.B, true.B)
  val wb = ShiftRegister(tag, latency)
  val wbData = Wire(Vec(p.lanes, UInt(32.W)))
  val exceptions = Wire(Vec(p.lanes, UInt(5.W)))
  for (l <- 0 until p.lanes) {
    val tid = exRow * p.lanes.U + l.U
    val systemValue = MuxLookup(d.funct, 0.U)(Seq(0.U -> tid, 1.U -> group.id,
      2.U -> group.threads, 3.U -> group.argument, 4.U -> smId.U, 5.U -> flags(w)))
    val intValue = Mux(d.op === 0x5b.U, systemValue, Isa.integer(d, a(l), b(l)))
    val integerWB = if (p.tensorK == 0) ShiftRegister(intValue, latency) else {
      val rowA = a((l / 4) * 4 + (l % 4) / 2 * 2)
      val colB = b((l / 4) * 4 + l % 2)
      val products = (0 until p.tensorK).map { k =>
        RegNext(rowA(8*k+7, 8*k).asSInt * colB(8*k+7, 8*k).asSInt)
      }
      val sum = RegNext(products.reduce(_ +& _))
      val acc = ShiftRegister(rf.io.data(2)(l), 2)
      val dot = (acc.asSInt + sum).asUInt
      ShiftRegister(Mux(ShiftRegister(d.op === 0x47.U, 2), dot, ShiftRegister(intValue, 2)), latency-2)
    }
    val fp = Module(new Fp32Lane(1))
    fp.io.in.valid := arithmetic && d.fp
    fp.io.in.bits.a := a(l)
    fp.io.in.bits.b := b(l)
    fp.io.in.bits.c := rf.io.data(2)(l)
    fp.io.in.bits.op := d.fpOp
    fp.io.in.bits.tag := 0.U
    fp.io.in.bits.active := slice.mask(l)
    fp.io.out.ready := true.B
    val nonFP = if (p.graphicsMask == 0) integerWB else {
      val unit = Module(new GraphicsLane(p))
      unit.io.op := Cat(d.high(1,0),d.funct)
      unit.io.a := a(l); unit.io.b := b(l); unit.io.c := rf.io.data(2)(l)
      Mux(ShiftRegister(d.op === 0x57.U,latency),unit.io.result,integerWB)
    }
    wbData(l) := Mux(wb.fp, fp.io.out.bits.data, nonFP)
    exceptions(l) := fp.io.out.bits.flags
  }
  when(retire && wb.fp) {
    flags(wb.warp) := flags(wb.warp) | exceptions.reduce(_ | _)
    summary.fpOps := summary.fpOps + PopCount(wb.mask)
    when(wb.fma) { summary.fmaOps := summary.fmaOps + PopCount(wb.mask) }
  }
  when(retire && wb.tensor) {
    summary.tensorOps := summary.tensorOps + PopCount(wb.mask) * p.tensorK.U
  }
  // Stop issuing while a return awaits RF service. Existing fixed-latency writes drain.
  val wbSlot = if (slotCount == 1) 0.U else PriorityEncoder(wbSlots)
  val wbBeat = writeBeat(wbSlot)
  val memIssue = memIssues(wbSlot)
  val request = requests(wbSlot)
  val loaded = loads(wbSlot)
  val loadWB = wbSlots.orR && !retire
  val parts = loaded.asTypeOf(Vec(p.beats, Vec(p.lanes, UInt(32.W))))
  val masks = request.mask.asTypeOf(Vec(p.beats, UInt(p.lanes.W)))
  rf.io.wr.valid := retire || loadWB
  rf.io.wr.bits.row := Mux(retire, wb.row, memIssue.warp * p.beats.U + wbBeat)
  rf.io.wr.bits.dest := Mux(retire, wb.dest, memIssue.decoded.rd)
  rf.io.wr.bits.mask := Mux(retire, wb.mask, if (p.beats == 1) masks(0) else masks(wbBeat))
  rf.io.wr.bits.data := Mux(retire, wbData, if (p.beats == 1) parts(0) else parts(wbBeat))
  when(loadWB) {
    when(wbBeat === (p.beats - 1).U) {
      slotState(wbSlot) := memIdle
      locked(memIssue.warp) := false.B
    }.otherwise { writeBeat(wbSlot) := wbBeat + 1.U }
  }
  for (i <- 0 until p.warpSlots) {
    val port = arb.io.in(i)
    val dec = port.bits.decoded
    val accepted = port.fire && dec.legal && !dec.ctrl && !dec.mem
    val completed = retire && wb.last && wb.warp === i.U
    when(accepted || completed) {
      val set = Mux(accepted && dec.rd.orR, UIntToOH(dec.rd, p.registers), 0.U)
      val clear = Mux(completed && wb.dest.orR, UIntToOH(wb.dest, p.registers), 0.U)
      pending(i) := (pending(i) & ~clear) | set
      inflight(i) := inflight(i) + accepted - completed
    }
  }

  when(exValid && d.mem && d.legal) {
    val gathering = VecInit((0 until slotCount).map(i => slotState(i) === gather && memIssues(i).warp === w)).asUInt
    assert(PopCount(gathering) === 1.U)
    val slot = if (slotCount == 1) 0.U else OHToUInt(gathering)
    val request = requests(slot)
    for (k <- 0 until p.beats; l <- 0 until p.lanes) {
      when(slice.index === k.U) {
        request.addr(k * p.lanes + l) := (a(l).zext +& d.imm.asSInt).asUInt
        request.data(k * p.lanes + l) := b(l)
      }
    }
    when(slice.last) { slotState(slot) := send }
  }
  val votes = RegInit(0.U(p.warpWidth.W))
  val laneVotes = VecInit(a.map(_.orR)).asUInt & slice.mask
  val allVotes = votes | ((laneVotes << (slice.index * p.lanes.U))(p.warpWidth - 1, 0))
  val location = group.programBase + (ex.pc << 2)
  when(exValid && !d.legal && slice.last) { fail(2.U, location) }
  when(exValid && d.ctrl && d.legal && !(p.earlyBranch.B && d.funct === 3.U)) {
    votes := Mux(slice.last, 0.U, allVotes)
    when(slice.last) {
      locked(w) := false.B
      val relative = ex.pc.zext +& d.imm.asSInt
      val otherMask = ex.mask & ~allVotes
      val top = (depth(w) - 1.U)(log2Ceil(p.splitDepth) - 1, 0)
      val frame = frames(w)(top)
      def branch(): Unit = {
        when(relative < 0.S || relative >= group.words.zext) { fail(2.U, location) }
          .otherwise { pc(w) := relative.asUInt }
      }
      switch(d.funct) {
        is(0.U) {
          when(depth(w).orR) { fail(4.U, location) }.otherwise { live(w) := false.B }
        }
        is(1.U) {
          when(depth(w).orR) { fail(4.U, location) }.otherwise { barrier(w) := true.B }
        }
        is(3.U) { branch() }
        is(4.U) {
          when(!allVotes.orR) { branch() }.elsewhen(otherMask.orR) { fail(4.U, location) }
        }
        is(5.U) {
          when(depth(w) === p.splitDepth.U || relative < 0.S || relative >= group.words.zext) {
            fail(4.U, location)
          }.otherwise {
            val next = frames(w)(depth(w)(log2Ceil(p.splitDepth) - 1, 0))
            next.parent := ex.mask
            next.other := Mux(allVotes.orR, otherMask, 0.U)
            next.target := relative.asUInt
            depth(w) := depth(w) + 1.U
            mask(w) := Mux(allVotes.orR, allVotes, ex.mask)
            when(!allVotes.orR) { pc(w) := relative.asUInt }
          }
        }
        is(6.U) {
          when(!depth(w).orR) { fail(4.U, location) }
            .elsewhen(frame.other.orR) {
              mask(w) := frame.other
              pc(w) := frame.target
              frame.other := 0.U
            }.otherwise { branch() }
        }
        is(7.U) {
          when(!depth(w).orR || frame.other.orR) { fail(4.U, location) }
            .otherwise { depth(w) := depth(w) - 1.U; mask(w) := frame.parent }
        }
      }
    }
  }
  when(healthy && barrier.asUInt.orR) {
    when(live.asUInt =/= resident.asUInt) { fail(4.U, group.programBase + ((pc(0) - 1.U) << 2)) }
      .elsewhen(barrier.asUInt === resident.asUInt) {
        val samePC = (0 until p.warpSlots).map(i => !resident(i) || pc(i) === pc(0)).reduce(_ && _)
        when(!samePC) { fail(4.U, group.programBase + ((pc(0) - 1.U) << 2)) }
          .otherwise { barrier.foreach(_ := false.B) }
      }
  }
  when(state === run && (!live.asUInt.orR || summary.fault.orR) && !inflight.asUInt.orR &&
      fetchIdle && !beats.io.beat.valid && !exValid && slotState.map(_ === memIdle).reduce(_ && _)) {
    state := finish
  }
}
