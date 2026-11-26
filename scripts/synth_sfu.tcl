# Usage: vivado -mode batch -source scripts/synth_sfu.tcl -tclargs GraphicsLane.sv out
if {$argc != 2} {error "expected SFU-only GraphicsLane.sv and output directory"}
lassign $argv rtl dest
file mkdir $dest
set dest [file normalize $dest]
create_project -in_memory -part xcu50-fsvh2104-2-e
set_param general.maxThreads 4
read_verilog -sv $rtl
set fd [open [file join $dest lane.xdc] w]
puts $fd {create_clock -name sm_clk -period 10.000 [get_ports clock]}
close $fd
read_xdc [file join $dest lane.xdc]
synth_design -top GraphicsLane -mode out_of_context -part xcu50-fsvh2104-2-e
opt_design
place_design
route_design
report_utilization -file [file join $dest utilization.rpt]
report_timing_summary -delay_type min_max -file [file join $dest timing.rpt]
report_route_status -file [file join $dest route.rpt]
