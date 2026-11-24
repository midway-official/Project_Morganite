# vivado -mode batch -source scripts/synth_gpu.tcl -tclargs top rtl outdir
lassign $argv top rtl out
set rtl [file normalize $rtl]
file mkdir $out
cd $out
set_param general.maxThreads 4
set f [open clock.xdc w]
puts $f {create_clock -period 10.000 -name core [get_ports clock]}
close $f
read_verilog -sv $rtl
read_xdc clock.xdc
synth_design -mode out_of_context -part xcu50-fsvh2104-2-e -top $top
report_utilization -file utilization.rpt
report_utilization -hierarchical -file hierarchy.rpt
report_timing_summary -file estimated_timing.rpt
