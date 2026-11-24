package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

object ExceptionCode {
  val INT  = 0x00
  val PIL  = 0x01
  val PIS  = 0x02
  val PIF  = 0x03
  val PME  = 0x04
  val PPI  = 0x07
  val ADEF = 0x08
  val ALE  = 0x09
  val SYS  = 0x0b
  val BRK  = 0x0c
  val INE  = 0x0d
  val IPE  = 0x0e
  val TLBR = 0x3f
}

class ExceptionInfo(implicit p: AuroraConfig) extends Bundle {
  val valid = Bool()
  val ecode = UInt(6.W)
  val esubcode = UInt(9.W)
  val badv = UInt(p.xLen.W)
  val badvValid = Bool()
}

object ExceptionInfo {
  def none(implicit p: AuroraConfig): ExceptionInfo =
    0.U.asTypeOf(new ExceptionInfo)
}

object TranslationAccess {
  val Fetch = 0
  val Load = 1
  val Store = 2
  val Width = 2
}

class TranslationRequest(implicit p: AuroraConfig) extends Bundle {
  val va = UInt(p.xLen.W)
  val access = UInt(TranslationAccess.Width.W)
}

class TranslationResponse(implicit p: AuroraConfig) extends Bundle {
  val va = UInt(p.xLen.W)
  val pa = UInt(p.xLen.W)
  val mat = UInt(2.W)
  val exception = new ExceptionInfo
}

/** AGU high address alternatives prepared alongside the page-offset sum. */
class DataPageCandidates extends Bundle {
  val prefix = UInt(10.W)
  val prefixWithCarry = UInt(10.W)
  val prefixCarry = Bool()
  val suffix = UInt(10.W)
  val suffixWithCarry = UInt(10.W)
  val suffixCarry = Bool()
}

class TranslationPortIO(implicit p: AuroraConfig) extends Bundle {
  val req = Flipped(Decoupled(new TranslationRequest))
  val resp = Decoupled(new TranslationResponse)
}

object TlbAdminOp {
  val Search = 0
  val Read = 1
  val Write = 2
  val Fill = 3
  val Invalidate = 4
  val Width = 3
}

class TlbAdminCommand(implicit p: AuroraConfig) extends Bundle {
  val op = UInt(TlbAdminOp.Width.W)
  val index = UInt(p.tlbIndexBits.W)
  val vppn = UInt(19.W)
  val ps = UInt(6.W)
  val asid = UInt(10.W)
  val elo0 = UInt(32.W)
  val elo1 = UInt(32.W)
  val enable = Bool()
  val invOp = UInt(5.W)
  val invAsid = UInt(10.W)
  val invVa = UInt(p.xLen.W)
}

class TlbAdminResponse(implicit p: AuroraConfig) extends Bundle {
  val found = Bool()
  val index = UInt(p.tlbIndexBits.W)
  val entryValid = Bool()
  val vppn = UInt(19.W)
  val ps = UInt(6.W)
  val asid = UInt(10.W)
  val elo0 = UInt(32.W)
  val elo1 = UInt(32.W)
}

class TlbTag extends Bundle {
  val vppn = UInt(19.W)
  val ps = UInt(6.W)
  val g = Bool()
  val asid = UInt(10.W)
}

class TlbPagePayload extends Bundle {
  val ppn = UInt(20.W)
  val plv = UInt(2.W)
  val mat = UInt(2.W)
  val d = Bool()
  val v = Bool()
}

class TlbPayload extends Bundle {
  val even = new TlbPagePayload
  val odd = new TlbPagePayload
}

class TlbLookupContext(implicit p: AuroraConfig) extends Bundle {
  val req = new TranslationRequest
  val direct = Bool()
  val dmw = Bool()
  val directPa = UInt(p.xLen.W)
  val directMat = UInt(2.W)
  val found = Bool()
  val hitIndex = UInt(p.tlbIndexBits.W)
  val largePage = Bool()
  val plv = UInt(2.W)
}

/** LA32R 地址翻译单元。
  *
  * 统一处理直接地址、DMW 和 TLB 翻译，并产生对应的地址与页故障。
  * TLB 查找使用寄存输入，保持请求在处理期间稳定。
  */
class MMU(precomputedDataPages: Boolean = false, stageDataTlb: Boolean = false)
    (implicit p: AuroraConfig) extends Module {
  require(!(precomputedDataPages && stageDataTlb))
  val io = IO(new Bundle {
    val dataPages = if (precomputedDataPages) Some(Input(new DataPageCandidates)) else None
    val lookup = Vec(2, new TranslationPortIO)
    val crmd = Input(UInt(32.W))
    val dmw0 = Input(UInt(32.W))
    val dmw1 = Input(UInt(32.W))
    val asid = Input(UInt(10.W))
    val flushLookup = Input(Bool())

    val adminReq = Flipped(Decoupled(new TlbAdminCommand))
    val adminResp = Decoupled(new TlbAdminResponse)
  })

  private val entries = p.tlbEntries
  private val payloadWidth = (new TlbPayload).getWidth
  val payloadWriteValid = WireDefault(false.B)
  val payloadWriteIndex = WireDefault(0.U(p.tlbIndexBits.W))
  val payloadWriteData = WireDefault(0.U(payloadWidth.W))

  // Translation, invalidation and TLBRD only consume enabled entries.
  val tagValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val tags = Reg(Vec(entries, new TlbTag))
  // Keep one coherent small payload image for parallel hit-mask selection.
  // This removes hit encoding followed by a RAM-address decode from the
  // translation request path; administrative reads retain their LUTRAM copy.
  val lookupPayload = Reg(Vec(entries, UInt(payloadWidth.W)))
  val adminPayload = Mem(entries, UInt(payloadWidth.W))
  for (memory <- Seq(adminPayload)) {
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        AttributeAnnotation(memory.toTarget, "ram_style = \"distributed\"")
    })
  }

  def pageMatches(tag: TlbTag, va: UInt): Bool = {
    val prefix = tag.vppn(18, 9) === va(31, 22)
    val suffix = tag.vppn(8, 0) === va(21, 13)
    prefix && (tag.ps === 21.U || (tag.ps === 12.U && suffix))
  }

  def lookupHits(va: UInt, lookupAsid: UInt): Vec[Bool] =
    VecInit(tags.zip(tagValid).map { case (tag, valid) =>
      valid && (tag.g || tag.asid === lookupAsid) && pageMatches(tag, va)
    })

  def dmwEnabled(dmw: UInt): Bool =
    ((io.crmd(1, 0) === 0.U) && dmw(0)) ||
      ((io.crmd(1, 0) === 3.U) && dmw(3))

  val mappedMode = !io.crmd(3) && io.crmd(4)
  val directMode = io.crmd(3) && !io.crmd(4)
  val largePages = VecInit(tags.map(_.ps === 21.U))

  for (port <- 0 until 2) {
    val req = io.lookup(port).req
    val resp = io.lookup(port).resp
    val responseValid = RegInit(false.B)
    val context = Reg(new TlbLookupContext)

    val hit0 = mappedMode && dmwEnabled(io.dmw0) &&
      io.dmw0(31, 29) === req.bits.va(31, 29)
    val hit1 = mappedMode && dmwEnabled(io.dmw1) &&
      io.dmw1(31, 29) === req.bits.va(31, 29)
    val usesDmw = hit0 || hit1
    val usesTlb = mappedMode && !usesDmw
    val staged = stageDataTlb && port == 1
    val pending = if (staged) Some(RegInit(false.B)) else None
    val pendingTlb = pending.getOrElse(false.B)
    val responseCanAdvance = !responseValid || resp.ready
    val requestSlotFree = responseCanAdvance && !pendingTlb
    req.ready := requestSlotFree && !io.flushLookup
    val requestFire = req.fire
    val acceptTlb = requestFire && usesTlb
    // Accepted translations require zero or one matching TLB entry. Encode
    // the hit mask directly so the payload RAM address has no priority chain.
    val directHits = if (precomputedDataPages && port == 1) {
      val pages = io.dataPages.get
      // The two VPN slices compare independently while their carries settle.
      // Large pages use only the upper slice.
      val selected = VecInit(tags.zip(tagValid).map { case (tag, valid) =>
        val prefixMatch = Mux(pages.prefixCarry,
          tag.vppn(18, 9) === pages.prefixWithCarry, tag.vppn(18, 9) === pages.prefix)
        val suffixMatch = Mux(pages.suffixCarry,
          tag.vppn(8, 0) === pages.suffixWithCarry(9, 1),
          tag.vppn(8, 0) === pages.suffix(9, 1))
        valid && (tag.g || tag.asid === io.asid) && prefixMatch &&
          (tag.ps === 21.U || (tag.ps === 12.U && suffixMatch))
      }).asUInt
      when(req.fire) { assert(selected === lookupHits(req.bits.va, io.asid).asUInt) }
      selected
    } else lookupHits(req.bits.va, io.asid).asUInt
    val (hits, selectedPayload, largePage) = if (staged) {
      // Invalidation can change several valid bits at acceptance. A write
      // or fill can also replace one entry. Capture those pre-edge values.
      val acceptedAsid = RegNext(io.asid)
      val acceptedValids = RegNext(tagValid.asUInt)
      val overwritten = RegNext(acceptTlb && payloadWriteValid, false.B)
      // Only pendingTlb consumes this one-edge snapshot. The AGU acceptance
      // gates the owner bit, while the old entry is sampled independently.
      val oldIndex = RegNext(payloadWriteIndex)
      val oldTag = RegNext(tags(payloadWriteIndex))
      val oldPayload = RegNext(lookupPayload(payloadWriteIndex))
      val liveHits = VecInit((0 until entries).map { entry =>
        val tag = tags(entry)
        acceptedValids(entry) && !(overwritten && oldIndex === entry.U) &&
          (tag.g || tag.asid === acceptedAsid) && pageMatches(tag, context.req.va)
      }).asUInt
      val oldHit = overwritten && acceptedValids(oldIndex) &&
        (oldTag.g || oldTag.asid === acceptedAsid) && pageMatches(oldTag, context.req.va)
      val acceptedHits = liveHits | Mux(oldHit, UIntToOH(oldIndex, entries), 0.U)
      val acceptedData = Mux1H(liveHits, lookupPayload) | Mux(oldHit, oldPayload, 0.U)
      val acceptedLarge = (liveHits & largePages.asUInt).orR ||
        (oldHit && oldTag.ps === 21.U)
      val hitWitness = RegEnable(directHits, acceptTlb)
      val payloadWitness = RegEnable(Mux1H(directHits, lookupPayload), acceptTlb)
      val largeWitness = RegEnable((directHits & largePages.asUInt).orR, acceptTlb)
      when(pendingTlb) {
        assert(acceptedHits === hitWitness)
        assert(acceptedData === payloadWitness)
        assert(acceptedLarge === largeWitness)
      }
      (acceptedHits, acceptedData, acceptedLarge)
    } else {
      (directHits, Mux1H(directHits, lookupPayload), (directHits & largePages.asUInt).orR)
    }
    val found = hits.orR
    val hitIndex = OHToUInt(hits)
    // Let a released slot sample candidate data before request qualification.
    // The accepted-hit bit forwards that snapshot on its original edge, then
    // retains it as the most recent payload. A miss still exposes the old
    // payload bits, matching the existing full response interface.
    val candidatePayload = RegEnable(selectedPayload,
      if (staged) pendingTlb else responseCanAdvance)
    val acceptedPayload = RegNext((if (staged) pendingTlb else acceptTlb) && found, false.B)
    val previousPayload = RegEnable(candidatePayload, acceptedPayload)
    val payloadRead = Mux(acceptedPayload, candidatePayload, previousPayload)
      .asTypeOf(new TlbPayload)

    when(io.flushLookup) {
      responseValid := false.B
      pending.foreach(_ := false.B)
    }.elsewhen(pendingTlb) {
      responseValid := true.B
      pending.foreach(_ := false.B)
    }.elsewhen(requestSlotFree) {
      responseValid := requestFire && !(staged.B && usesTlb)
      pending.foreach(_ := acceptTlb)
    }

    when(requestSlotFree) {
      context.req := req.bits
      context.direct := directMode || !mappedMode
      context.dmw := usesDmw
      context.directPa := Mux(hit0,
        Cat(io.dmw0(27, 25), req.bits.va(28, 0)),
        Mux(hit1, Cat(io.dmw1(27, 25), req.bits.va(28, 0)),
          req.bits.va))
      val directMat = Mux(req.bits.access === TranslationAccess.Fetch.U,
        io.crmd(6, 5), io.crmd(8, 7))
      context.directMat := Mux(hit0, io.dmw0(5, 4),
        Mux(hit1, io.dmw1(5, 4), directMat))
      context.plv := io.crmd(1, 0)
    }
    when(if (staged) pendingTlb else responseCanAdvance) {
      context.found := found
      context.hitIndex := hitIndex
      context.largePage := largePage
    }
    when(if (staged) pendingTlb else requestFire) { assert(PopCount(hits) <= 1.U) }

    val oddPage = Mux(context.largePage,
      context.req.va(21), context.req.va(12))
    val page = Mux(oddPage, payloadRead.odd, payloadRead.even)
    val smallPa = Cat(page.ppn, context.req.va(11, 0))
    val largePa = Cat(page.ppn(19, 9), context.req.va(20, 0))
    val tlbPa = Mux(context.largePage, largePa, smallPa)
    val isFetch = context.req.access === TranslationAccess.Fetch.U
    val isStore = context.req.access === TranslationAccess.Store.U
    val invalidCode = Mux(isFetch, ExceptionCode.PIF.U,
      Mux(isStore, ExceptionCode.PIS.U, ExceptionCode.PIL.U))

    val exception = WireDefault(ExceptionInfo.none)
    when(isFetch && context.req.va(1, 0) =/= 0.U) {
      exception.valid := true.B
      exception.ecode := ExceptionCode.ADEF.U
    }.elsewhen(!context.direct && !context.dmw) {
      when(!context.found) {
        exception.valid := true.B
        exception.ecode := ExceptionCode.TLBR.U
      }.elsewhen(!page.v) {
        exception.valid := true.B
        exception.ecode := invalidCode
      }.elsewhen(context.plv > page.plv) {
        exception.valid := true.B
        exception.ecode := ExceptionCode.PPI.U
      }.elsewhen(isStore && !page.d) {
        exception.valid := true.B
        exception.ecode := ExceptionCode.PME.U
      }
    }
    exception.badv := context.req.va
    exception.badvValid := exception.valid

    resp.valid := responseValid
    resp.bits.va := context.req.va
    resp.bits.pa := Mux(context.direct || context.dmw,
      context.directPa, tlbPa)
    resp.bits.mat := Mux(context.direct || context.dmw,
      context.directMat, page.mat)
    resp.bits.exception := exception
  }

  def unpackPage(elo: UInt): TlbPagePayload = {
    val page = Wire(new TlbPagePayload)
    page.ppn := elo(27, 8)
    page.plv := elo(3, 2)
    page.mat := elo(5, 4)
    page.d := elo(1)
    page.v := elo(0)
    page
  }

  def packPage(page: TlbPagePayload, global: Bool): UInt = {
    val value = WireDefault(0.U(32.W))
    value := Cat(0.U(4.W), page.ppn, 0.U(1.W), global,
      page.mat, page.plv, page.d, page.v)
    value
  }

  val adminResponseValid = RegInit(false.B)
  val adminResponse = Reg(new TlbAdminResponse)
  val adminReadData = RegEnable(adminPayload(io.adminReq.bits.index),
    io.adminReq.fire && io.adminReq.bits.op === TlbAdminOp.Read.U)
    .asTypeOf(new TlbPayload)
  val adminReadGlobal = Reg(Bool())
  val adminReadOp = RegInit(false.B)
  val fillIndex = RegInit(0.U(p.tlbIndexBits.W))

  io.adminReq.ready := !adminResponseValid
  io.adminResp.valid := adminResponseValid
  io.adminResp.bits := adminResponse
  when(adminReadOp && adminResponse.entryValid) {
    io.adminResp.bits.elo0 := packPage(adminReadData.even, adminReadGlobal)
    io.adminResp.bits.elo1 := packPage(adminReadData.odd, adminReadGlobal)
  }

  when(io.adminResp.fire) {
    adminResponseValid := false.B
    adminReadOp := false.B
  }

  val searchVa = Cat(io.adminReq.bits.vppn, 0.U(13.W))
  val searchHits = lookupHits(searchVa, io.adminReq.bits.asid)
  val searchFound = searchHits.asUInt.orR
  val searchIndex = PriorityEncoder(searchHits)

  when(io.adminReq.fire) {
    val command = io.adminReq.bits
    val writeIndex = Mux(command.op === TlbAdminOp.Fill.U,
      fillIndex, command.index)
    val writeTag = Wire(new TlbTag)
    writeTag.vppn := command.vppn
    writeTag.ps := command.ps
    writeTag.g := command.elo0(6) && command.elo1(6)
    writeTag.asid := command.asid
    val writePayload = Wire(new TlbPayload)
    writePayload.even := unpackPage(command.elo0)
    writePayload.odd := unpackPage(command.elo1)

    adminResponseValid := true.B
    adminReadOp := command.op === TlbAdminOp.Read.U
    adminResponse := 0.U.asTypeOf(new TlbAdminResponse)
    adminResponse.index := Mux(command.op === TlbAdminOp.Search.U,
      searchIndex, writeIndex)
    adminResponse.found := searchFound

    switch(command.op) {
      is(TlbAdminOp.Search.U) {
        assert(PopCount(searchHits) <= 1.U)
      }
      is(TlbAdminOp.Read.U) {
        val tag = tags(command.index)
        adminResponse.index := command.index
        val valid = tagValid(command.index)
        adminResponse.entryValid := valid
        when(valid) {
          adminResponse.vppn := tag.vppn
          adminResponse.ps := tag.ps
          adminResponse.asid := tag.asid
          adminReadGlobal := tag.g
        }
      }
      is(TlbAdminOp.Write.U, TlbAdminOp.Fill.U) {
        tags(writeIndex) := writeTag
        tagValid(writeIndex) := command.enable
        payloadWriteValid := true.B
        payloadWriteIndex := writeIndex
        payloadWriteData := writePayload.asUInt
        when(command.op === TlbAdminOp.Fill.U) { fillIndex := fillIndex + 1.U }
      }
      is(TlbAdminOp.Invalidate.U) {
        for (entry <- 0 until entries) {
          val tag = tags(entry)
          val vaMatch = pageMatches(tag, command.invVa)
          val invalidate = MuxLookup(command.invOp, false.B)(Seq(
            0.U -> true.B,
            1.U -> true.B,
            2.U -> tag.g,
            3.U -> !tag.g,
            4.U -> (!tag.g && tag.asid === command.invAsid),
            5.U -> (!tag.g && tag.asid === command.invAsid && vaMatch),
            6.U -> ((tag.g || tag.asid === command.invAsid) && vaMatch)
          ))
          when(tagValid(entry) && invalidate) { tagValid(entry) := false.B }
        }
      }
    }
  }

  when(payloadWriteValid) {
    lookupPayload(payloadWriteIndex) := payloadWriteData
    adminPayload.write(payloadWriteIndex, payloadWriteData)
  }
}
