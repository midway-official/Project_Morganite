package aurora

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.AttributeAnnotation

/** A physical 1R1W replica with an unconditional synchronous read.
  * No lookup acceptance/hold signal enters this module or its read address.
  * Keep the boundary, rather than the array, to retain BRAM inference while
  * preventing a late external selection from merging the two physical reads.
  */
class DCacheDemandReadReplica(depth: Int, bytes: Int) extends Module {
  require(depth >= 2 && isPow2(depth) && bytes >= 1)
  private val addressBits = log2Ceil(depth)
  val io = IO(new Bundle {
    val readIndex = Input(UInt(addressBits.W))
    val writeEnable = Input(Bool())
    val writeIndex = Input(UInt(addressBits.W))
    val writeData = Input(UInt((bytes * 8).W))
    val writeMask = Input(UInt(bytes.W))
    val readData = Output(UInt((bytes * 8).W))
  })
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(
      DCacheDemandReadReplica.this.toTarget, "keep_hierarchy = \"yes\", dont_touch = \"yes\"")
  })
  annotate(new ChiselAnnotation {
    override def toFirrtl = firrtl.transforms.NoDedupAnnotation(
      DCacheDemandReadReplica.this.toTarget)
  })
  val data = SyncReadMem(depth, Vec(bytes, UInt(8.W)), SyncReadMem.ReadFirst)
  annotate(new ChiselAnnotation {
    override def toFirrtl = AttributeAnnotation(data.toTarget, "ram_style = \"block\"")
  })
  io.readData := data.read(io.readIndex, true.B).asUInt
  when(io.writeEnable) {
    data.write(io.writeIndex, io.writeData.asTypeOf(Vec(bytes, UInt(8.W))),
      io.writeMask.asBools)
  }
}

/** Same one-edge latency as the old held-address SyncReadMem lowering.
  * Both RAMs read every edge; acceptance chooses only their registered results.
  * Captured write forwarding makes same-edge byte writes visible explicitly.
  */
class DCacheDemandParallelRead(depth: Int, bytes: Int,
                               separateWriteAddresses: Boolean = false) extends Module {
  private val addressBits = log2Ceil(depth)
  val io = IO(new Bundle {
    val candidateIndex = Input(UInt(addressBits.W))
    val lookupFire = Input(Bool())
    val writeEnable = Input(Bool())
    val writeIndex = Input(UInt(addressBits.W))
    val writeData = Input(UInt((bytes * 8).W))
    val writeMask = Input(UInt(bytes.W))
    val writeAddresses = if (separateWriteAddresses)
      Some(Input(Vec(2, Valid(UInt(addressBits.W))))) else None
    val readData = Output(UInt((bytes * 8).W))
  })
  // No reset: the original implicit held address has none either. Cache valid
  // state qualifies observations before the first accepted/readable word.
  val heldIndex = RegEnable(io.candidateIndex, io.lookupFire)
  val selectCandidate = RegNext(io.lookupFire)
  val candidateCopy = Module(new DCacheDemandReadReplica(depth, bytes))
  val heldCopy = Module(new DCacheDemandReadReplica(depth, bytes))
  candidateCopy.io.readIndex := io.candidateIndex
  heldCopy.io.readIndex := heldIndex
  for (copy <- Seq(candidateCopy, heldCopy)) {
    copy.io.writeEnable := io.writeEnable
    copy.io.writeIndex := io.writeIndex
    copy.io.writeData := io.writeData
    copy.io.writeMask := io.writeMask
  }
  def writeCollision(index: UInt): Bool = {
    val original = io.writeEnable && io.writeIndex === index
    io.writeAddresses.map { sources =>
      // A late store hit qualifies an address comparison already in flight.
      val parallel = sources.map(source => source.valid && source.bits === index)
        .reduce(_ || _)
      assert(parallel === original)
      parallel
    }.getOrElse(original)
  }
  val candidateCollision = RegNext(writeCollision(io.candidateIndex))
  val heldCollision = RegNext(writeCollision(heldIndex))
  val capturedWriteData = RegNext(io.writeData)
  val capturedWriteMask = RegNext(io.writeMask)
  io.readData := Cat((0 until bytes).reverse.map { byte =>
    val candidateBypass = candidateCollision && capturedWriteMask(byte)
    val heldBypass = heldCollision && capturedWriteMask(byte)
    val readCandidate = selectCandidate && !candidateBypass
    val readHeld = !selectCandidate && !heldBypass
    val readWrite = Mux(selectCandidate, candidateBypass, heldBypass)
    Mux1H(Seq(
      readCandidate -> candidateCopy.io.readData(8 * byte + 7, 8 * byte),
      readHeld -> heldCopy.io.readData(8 * byte + 7, 8 * byte),
      readWrite -> capturedWriteData(8 * byte + 7, 8 * byte)))
  })
}
