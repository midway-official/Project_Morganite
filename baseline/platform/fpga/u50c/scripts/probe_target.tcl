set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]
set server_url [expr {[llength $argv] >= 1 ? [lindex $argv 0] : "127.0.0.1:13121"}]
if {[catch {set device [u50hw::open_target $server_url ""]} message options]} {
    puts stderr "PROBE_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
puts "PROBE_OK=[get_property NAME $device]"
u50hw::close_target
