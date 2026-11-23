if {$argc != 3} {error "expected top source.sv fresh-output-directory"}
lassign $argv top rtl output
set rtl [file normalize $rtl]
file mkdir $output
cd $output
set_param general.maxThreads 4
set clockfile [open clock.xdc w]
puts $clockfile {create_clock -name core -period 10.000 [get_ports clock]}
close $clockfile
read_verilog -sv $rtl
read_xdc clock.xdc
synth_design -mode out_of_context -top $top -part xcu50-fsvh2104-2-e
report_utilization -file utilization_synth.rpt
report_timing_summary -file timing_synth.rpt
opt_design
place_design
phys_opt_design
route_design
report_route_status -file route.rpt
report_timing_summary -delay_type min_max -report_unconstrained -file timing.rpt
report_utilization -file utilization.rpt
report_drc -file drc.rpt
report_clock_utilization -file clocks.rpt
check_timing -verbose -file check_timing.rpt
set setup [get_property SLACK [get_timing_paths -delay_type max -max_paths 1]]
set hold [get_property SLACK [get_timing_paths -delay_type min -max_paths 1]]
if {![string is double -strict $setup] || ![string is double -strict $hold]} {
    error "no measured register-to-register setup/hold paths for $top"
}
puts "OOC_TOP=$top SETUP=$setup HOLD=$hold VIVADO=[version -short]"
if {$setup < 0 || $hold < 0} {exit 2}
