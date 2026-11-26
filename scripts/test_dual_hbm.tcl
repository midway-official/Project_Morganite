# Pass the dual-stack staged project's .xpr as the only argument.
set root [file normalize [file join [file dirname [info script]] ..]]
open_project [lindex $argv 0]
# An optional 'ports' argument exercises the same packaged bridge and converter IPs
# against shared, byte-addressable AXI3 memory when the vendor DRAM model is unavailable.
set existing [get_files -quiet -of_objects [get_filesets sim_1] *hbm_ports_model.sv]
if {[llength $existing]} {remove_files -fileset sim_1 $existing}
set_property USED_IN_SIMULATION true [get_files *hbm_0.xci]
if {[lindex $argv 1] eq "ports"} {
    set model [file normalize hbm_ports_model.sv]
    exec env -u PYTHONHOME -u PYTHONPATH /usr/bin/python3 [file join $root sim hbm_ports.py] $model
    set_property USED_IN_SIMULATION false [get_files *hbm_0.xci]
    add_files -fileset sim_1 -norecurse $model
}
add_files -fileset sim_1 -norecurse [file join $root sim dual_hbm_tb.sv]
set_property top dual_hbm_tb [get_filesets sim_1]
set_property xsim.simulate.runtime 2100us [get_filesets sim_1]
set log [file join [get_property DIRECTORY [current_project]] aurora_u50c.sim sim_1 behav xsim simulate.log]
launch_simulation -simset sim_1
close_sim
set fd [open $log]; set result [read $fd]; close $fd
if {![string match "*PASS dual HBM host/GPU*" $result]} {error "dual HBM test failed"}
