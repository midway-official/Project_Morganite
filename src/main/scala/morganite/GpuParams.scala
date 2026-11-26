package morganite

import chisel3.util.{isPow2, log2Ceil}

case class GpuParams(
    numSms: Int = 2, warpWidth: Int = 8, lanes: Int = 4, warpSlots: Int = 4,
    registers: Int = 32, scratchBytes: Int = 8192, axiAddrBits: Int = 32,
    axiDataBits: Int = 32, axiIdBits: Int = 4, readOutstanding: Int = 1,
    writeOutstanding: Int = 1, iCacheBytes: Int = 0, dCacheBytes: Int = 0,
    lineWords: Int = 4, tensorK: Int = 0, bramRf: Boolean = false,
    earlyBranch: Boolean = false, hbmPorts: Int = 1) {
  require(hbmPorts == 1 || (hbmPorts == 2 && axiDataBits == 256))
  require(numSms > 0 && warpSlots > 0)
  require(isPow2(warpWidth) && warpWidth <= 32)
  require(isPow2(lanes) && lanes <= warpWidth)
  require(registers >= 2 && registers <= 32 && isPow2(registers))
  require(isPow2(scratchBytes) && scratchBytes >= lanes * 4)
  require(axiAddrBits == 32 && Set(32, 256)(axiDataBits))
  require(axiIdBits >= 1 && axiIdBits <= 16)
  require(Seq(readOutstanding, writeOutstanding).forall(n => n > 0 && n <= (1 << axiIdBits)))
  require(axiDataBits == 256 || (readOutstanding == 1 && writeOutstanding == 1))
  require(isPow2(lineWords) && lineWords <= 32)
  for (bytes <- Seq(iCacheBytes, dCacheBytes) if bytes != 0) {
    require(isPow2(bytes) && bytes >= 8 * lineWords && bytes <= 65536)
    require(lineWords <= warpWidth, "a refill fits one DMA vector")
  }
  require(Set(0, 1, 2, 4)(tensorK) && (tensorK == 0 || lanes % 4 == 0))
  val globalSlots = if (axiDataBits == 256) 2 else 1
  val extraGlobalBase = numSms + 1 + (if (iCacheBytes > 0) numSms else 0)
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
