set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]
set u50_dir [file normalize [file join $script_dir ..]]
set default_bit [file join $u50_dir build vivado aurora_u50c.runs impl_1 u50c_top.bit]
set bit_file [expr {[llength $argv] >= 1 ? [file normalize [lindex $argv 0]] : $default_bit}]
set server_url [expr {[llength $argv] >= 2 ? [lindex $argv 1] : "127.0.0.1:13121"}]
set expected [expr {[llength $argv] >= 3 ? [lindex $argv 2] : "xcu50"}]
set probes_file [expr {[llength $argv] >= 4 ? [file normalize [lindex $argv 3]] : [file join $u50_dir build u50c_top.ltx]}]
if {![file exists $bit_file]} {error "Bitstream does not exist: $bit_file"}
if {![file exists $probes_file]} {error "Debug probes do not exist: $probes_file"}

if {[catch {
    set device [u50hw::open_target $server_url $expected]
    set_property PROGRAM.FILE $bit_file $device
    set_property PROBES.FILE $probes_file $device
    set_property FULL_PROBES.FILE $probes_file $device
    program_hw_devices $device
    refresh_hw_device $device
    set axes [get_hw_axis -quiet]
    if {[llength $axes] != 1} {error "Programmed design has no unique JTAG-to-AXI core: $axes"}
    puts "PROGRAM_OK=$bit_file"
    puts "HW_AXI=[lindex $axes 0]"
} message options]} {
    puts stderr "PROGRAM_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
u50hw::close_target
