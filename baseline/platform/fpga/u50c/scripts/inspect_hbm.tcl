set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]
set server_url [expr {[llength $argv] >= 1 ? [lindex $argv 0] : "127.0.0.1:13121"}]

if {[catch {
    set device [u50hw::open_target $server_url xcu50]
    refresh_hw_device -update_hw_probes false $device
    set hbms [get_hw_hbms -quiet]
    puts "HBM_OBJECTS=$hbms"
    foreach hbm $hbms {
        puts "HBM_BEGIN=[get_property NAME $hbm]"
        if {[catch {refresh_hw_hbm $hbm} refresh_message]} {
            puts "HBM_REFRESH_FAILED=$refresh_message"
        } else {
            puts "HBM_REFRESH_OK=1"
        }
        foreach property [lsort [list_property $hbm]] {
            if {[regexp -nocase {(cal|init|status|error|temp|clk|stack|density|switch|power|ref|cfg_core)} $property]} {
                puts "$property=[get_property $property $hbm]"
            }
        }
        puts "HBM_END=[get_property NAME $hbm]"
    }
} message options]} {
    puts stderr "HBM_INSPECT_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
u50hw::close_target
