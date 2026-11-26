# Run from a disposable build directory. Accepts local or server Vivado.
set root [file normalize [file join [file dirname [info script]] ..]]
create_project bridge ip-project -force -part xcu50-fsvh2104-2-e
source [file join $root board gpu_axi_ip.tcl]
set bridge [file normalize gpu_hbm_path.sv]
exec env -u PYTHONHOME -u PYTHONPATH /usr/bin/python3 -c "import sys; sys.path.insert(0,r'${root}/scripts'); from wide_board import gpu_bridge; print(gpu_bridge())" > $bridge
add_files -fileset sim_1 [list $bridge [file join $root sim gpu_hbm_ip_tb.sv]]
set_property include_dirs [list [file join $root baseline server fpga u50c rtl]] [get_filesets sim_1]
set_property top gpu_hbm_ip_tb [get_filesets sim_1]
set_property xsim.simulate.runtime 20us [get_filesets sim_1]
launch_simulation -simset sim_1
close_sim
set path [lindex [glob ip-project/*.sim/sim_1/behav/xsim/simulate.log] 0]
set fd [open $path]; set log [read $fd]; close $fd
if {![string match "*PASS vendor CDC*" $log]} { error "vendor bridge did not pass" }
