package morganite

import circt.stage.ChiselStage

object Generate extends App {
  require(args.length == 5, "module output-directory SM-count warp-width lanes")
  val p = GpuParams(numSms = args(2).toInt, warpWidth = args(3).toInt, lanes = args(4).toInt)
  val design = args(0) match {
    case "WarpBeats" => () => new WarpBeats(p)
    case other => throw new IllegalArgumentException(s"unknown top $other")
  }
  ChiselStage.emitSystemVerilogFile(design(), Array("--target-dir", args(1)),
    Array("-disable-all-randomization", "-strip-debug-info"))
}
