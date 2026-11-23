# Elaborate the actual board top and generated IP, without running synthesis.
set script_dir [file dirname [file normalize [info script]]]
set build_dir [file normalize [file join $script_dir ../build]]
if {[llength $argv]} {set build_dir [file normalize [lindex $argv 0]]}
set_param general.maxThreads 2
open_project [file join $build_dir vivado aurora_u50c.xpr]
# RTL elaboration needs IP HDL rather than OOC checkpoint placeholders.
foreach xci [get_files -quiet *.xci] {
    set_property generate_synth_checkpoint false $xci
}
update_compile_order -fileset sources_1
synth_design -rtl -name rtl_check -top u50c_top -part xcu50-fsvh2104-2-e
puts "RTL_ELABORATION_PASS=Tick2h_U50C_100MHz"
puts "TOP_PORTS=[get_ports]"
close_design
close_project
exit
