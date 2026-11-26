package morganite

import circt.stage.ChiselStage

object Generate extends App {
  require(args.length >= 5, "module output-directory SM-count warp-width lanes")
  val options = args.drop(5).map { arg =>
    arg.split("=", -1) match {
      case Array(k, v) if Set("ic", "dc", "line", "tensor", "bramRf", "earlyBranch", "dmaBits", "reads", "writes", "ports",
        "setup", "bins", "raster", "rop", "sfu")(k) => k -> v.toInt
      case _ => throw new IllegalArgumentException(s"unknown configuration: $arg")
    }
  }.toMap
  def flag(name: String): Boolean = { val v = options.getOrElse(name, 0); require(v == 0 || v == 1); v == 1 }
  val (rows, cols) = args(2).split("x",-1).toList match {
    case r :: c :: Nil => (r.toInt,c.toInt)
    case n :: Nil => val count = n.toInt; if (count >= 4 && count % 4 == 0) (count/4,4) else (1,count)
    case _ => throw new IllegalArgumentException("expected SM grid RowsxCols or a total SM count")
  }
  val p = GpuParams(smRows = rows, smCols = cols, warpWidth = args(3).toInt, lanes = args(4).toInt,
    triangleSetup = flag("setup"), binning = flag("bins"), raster = flag("raster"), rop = flag("rop"), sfu = flag("sfu"),
    hbmPorts = options.getOrElse("ports", 1), axiDataBits = options.getOrElse("dmaBits", 32),
    readOutstanding = options.getOrElse("reads", 1), writeOutstanding = options.getOrElse("writes", 1),
    iCacheBytes = options.getOrElse("ic", 0), dCacheBytes = options.getOrElse("dc", 0),
    lineWords = options.getOrElse("line", 4), tensorK = options.getOrElse("tensor", 0),
    bramRf = flag("bramRf"), earlyBranch = flag("earlyBranch"))
  val design = args(0) match {
    case "GraphicsLane" => () => new GraphicsLane(p)
    case "DualDataL1" => () => new DualDataL1(p)
    case "Scratchpad" => () => new Scratchpad(p)
    case "WideDma" => () => new WideDma(p)
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
