package morganite

import chisel3.util.{isPow2, log2Ceil}

case class GpuParams(
    numSms: Int = 2, warpWidth: Int = 8, lanes: Int = 4, warpSlots: Int = 4,
    registers: Int = 32, scratchBytes: Int = 8192, axiAddrBits: Int = 32,
    axiDataBits: Int = 32, axiIdBits: Int = 4, readOutstanding: Int = 1,
    writeOutstanding: Int = 1) {
  require(numSms > 0 && warpSlots > 0)
  require(isPow2(warpWidth) && warpWidth <= 32)
  require(isPow2(lanes) && lanes <= warpWidth)
  require(registers >= 2 && registers <= 32 && isPow2(registers))
  require(isPow2(scratchBytes) && scratchBytes >= lanes * 4)
  require(axiAddrBits == 32 && axiDataBits == 32, "ABI v1 uses a 32-bit DMA port")
  require(axiIdBits >= 1 && axiIdBits <= 16)
  require(readOutstanding == 1 && writeOutstanding == 1, "one transaction per direction in v1")
  val beats: Int = warpWidth / lanes
  val beatBits: Int = log2Ceil(beats).max(1)
  val maxThreads: Int = warpWidth * warpSlots
  val warpBits: Int = log2Ceil(warpSlots).max(1)
  val threadCountBits: Int = log2Ceil(maxThreads + 1)
  val registerBits: Int = log2Ceil(registers)
  val rowBits: Int = log2Ceil(warpSlots * beats).max(1)
  val programWords: Int = 1024
  val pcBits: Int = log2Ceil(programWords)
  val splitDepth: Int = 4
}
