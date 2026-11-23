set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]

set server_url [expr {[llength $argv] >= 1 ? [lindex $argv 0] : "127.0.0.1:13121"}]
set expected [expr {[llength $argv] >= 2 ? [lindex $argv 1] : "xcu50"}]

if {[catch {
    set device [u50hw::open_target $server_url $expected]
    refresh_hw_device -update_hw_probes false $device
    set vios [get_hw_vios -quiet]
    if {[llength $vios] != 2} {error "Expected two status VIOs, got: $vios"}
    foreach vio $vios {
        refresh_hw_vio $vio
        puts "VIO=[get_property NAME $vio]"
        foreach probe [get_hw_probes -of_objects $vio] {
            puts "PROBE=[get_property NAME $probe]"
            puts "VALUE=[get_property INPUT_VALUE $probe]"
        }
    }
} message options]} {
    puts stderr "VIO_INSPECT_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
u50hw::close_target
