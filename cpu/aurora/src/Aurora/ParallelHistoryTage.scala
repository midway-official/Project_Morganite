package aurora

import chisel3._
import chisel3.util._

/** Select precomputed history-dependent addresses into a coherent table image.
  * Candidate hash/tag computation precedes the late one-hot choice. Each
  * physical bank performs one selected query and retains its training read.
  * The optional split-default mode isolates late acceptance in a registered
  * result choice, trading one extra read image for a shorter address path.
  * Logical capacity, prediction edge and committed training are unchanged.
  */
class ParallelHistoryTage(choices: Int, selectDefaultAfterQuery: Boolean = false,
                          withFallback: Boolean = false, parallelQueries: Boolean = false)(implicit p: AuroraConfig) extends Module {
  require(choices >= 2)
  require(!selectDefaultAfterQuery || choices >= 3)
  val io = IO(new Bundle {
    val lookupPc = Input(UInt(p.xLen.W))
    val historyChoices = Input(Vec(choices, UInt(p.ghrBits.W)))
    val historySelect = Input(UInt(choices.W))
    // The final choice is the default history. Advanced choices can be
    // resolved before ready/fire, independently of whether this group fires.
    val earlyHistorySelect = if (selectDefaultAfterQuery)
      Some(Input(UInt((choices - 1).W))) else None
    val lookupEnable = Input(Bool())
    val lookupFallbackTaken = if (withFallback) Some(Input(Vec(p.fetchWidth, Bool()))) else None
    val predWithFallback = if (withFallback) Some(Output(Vec(p.fetchWidth, Bool()))) else None
    val update = Input(new BpuUpdate)
    val flush = Input(Bool())
    val predTaken = Output(Vec(p.fetchWidth, Bool()))
    val predValid = Output(Vec(p.fetchWidth, Bool()))
    val predTable = Output(Vec(p.fetchWidth, UInt(p.tageTableBits.W)))
    val predCtr = Output(Vec(p.fetchWidth, UInt(p.tageCounterBits.W)))
    val predStrongTagged = Output(Vec(p.fetchWidth, Bool()))
    val predStrongTaken = Output(Vec(p.fetchWidth, Bool()))
  })
  private val queryChoices = if (selectDefaultAfterQuery) choices - 1 else choices
  val table = Module(new HistorySelectedTage(queryChoices, withFallback, parallelQueries))
  def connectCommon(predictor: HistorySelectedTage): Unit = {
    predictor.io.lookupPc := io.lookupPc
    predictor.io.lookupEnable := io.lookupEnable
    predictor.io.update := io.update
    predictor.io.flush := io.flush
    predictor.io.lookupFallbackTaken.foreach(_ := io.lookupFallbackTaken.get)
  }
  connectCommon(table)
  table.io.lookupHistory := 0.U
  table.io.historyChoices.get := VecInit(io.historyChoices.take(queryChoices))
  table.io.historySelect.get := (if (selectDefaultAfterQuery)
    io.earlyHistorySelect.get else io.historySelect)

  if (selectDefaultAfterQuery) {
    // Keep two identically trained read images. The late ready/fire choice
    // crosses only one register; it never drives a LUTRAM address or tag.
    // Both predictions are captured on the original lookup acceptance edge.
    val defaultTable = Module(new HistorySelectedTage(1, withFallback))
    connectCommon(defaultTable)
    defaultTable.io.lookupHistory := io.historyChoices(choices - 1)
    val useAdvanced = RegEnable(!io.historySelect(choices - 1), io.lookupEnable)
    io.predTaken := Mux(useAdvanced, table.io.predTaken, defaultTable.io.predTaken)
    io.predValid := Mux(useAdvanced, table.io.predValid, defaultTable.io.predValid)
    io.predTable := Mux(useAdvanced, table.io.predTable, defaultTable.io.predTable)
    io.predCtr := Mux(useAdvanced, table.io.predCtr, defaultTable.io.predCtr)
    io.predStrongTagged := Mux(useAdvanced, table.io.predStrongTagged, defaultTable.io.predStrongTagged)
    io.predStrongTaken := Mux(useAdvanced, table.io.predStrongTaken, defaultTable.io.predStrongTaken)
    io.predWithFallback.foreach(_ := Mux(useAdvanced,
      table.io.predWithFallback.get, defaultTable.io.predWithFallback.get))
    when(io.lookupEnable) {
      assert(PopCount(io.earlyHistorySelect.get) === 1.U)
      when(!io.historySelect(choices - 1)) {
        assert(io.earlyHistorySelect.get === io.historySelect(choices - 2, 0))
      }
    }
  } else {
    io.predTaken := table.io.predTaken
    io.predValid := table.io.predValid
    io.predTable := table.io.predTable
    io.predCtr := table.io.predCtr
    io.predStrongTagged := table.io.predStrongTagged
    io.predStrongTaken := table.io.predStrongTaken
    io.predWithFallback.foreach(_ := table.io.predWithFallback.get)
  }
  when(io.lookupEnable) { assert(PopCount(io.historySelect) === 1.U) }
}
