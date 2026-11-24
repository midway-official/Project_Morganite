package morganite

import circt.stage.ChiselStage

object Generate extends App {
  require(args.length >= 5, "module output-directory SM-count warp-width lanes")
  val options = args.drop(5).map { arg =>
    arg.split("=", -1) match {
      case Array(k, v) if Set("ic", "dc", "line", "tensor", "bramRf", "earlyBranch")(k) => k -> v.toInt
      case _ => throw new IllegalArgumentException(s"unknown configuration: $arg")
    }
  }.toMap
  def flag(name: String): Boolean = { val v = options.getOrElse(name, 0); require(v == 0 || v == 1); v == 1 }
  val p = GpuParams(numSms = args(2).toInt, warpWidth = args(3).toInt, lanes = args(4).toInt,
    iCacheBytes = options.getOrElse("ic", 0), dCacheBytes = options.getOrElse("dc", 0),
    lineWords = options.getOrElse("line", 4), tensorK = options.getOrElse("tensor", 0),
    bramRf = flag("bramRf"), earlyBranch = flag("earlyBranch"))
  val design = args(0) match {
    case "WarpBeats" => () => new WarpBeats(p)
    case "Fp32Lane" => () => new Fp32Lane()
    case "LaneRegisters" => () => new LaneRegisters(p)
    case "RegisterTiming" => () => new RegisterTiming(p)
    case "Sm" => () => new Sm(p)
    case "Gpu" => () => new Gpu(p)
    case "MorganiteBoard" => () => new MorganiteBoard(p)
    case "PeripheralDecode" => () => new PeripheralDecode(p)
    case other => throw new IllegalArgumentException(s"unknown top $other")
  }
  ChiselStage.emitSystemVerilogFile(design(), Array("--target-dir", args(1)),
    Array("-disable-all-randomization", "-strip-debug-info"))
}
