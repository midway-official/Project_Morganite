set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]

set server_url [expr {[llength $argv] >= 1 ? [lindex $argv 0] : "127.0.0.1:13121"}]
set expected [expr {[llength $argv] >= 2 ? [lindex $argv 1] : "xcu50"}]
set address [expr {[llength $argv] >= 3 ? [lindex $argv 2] : 0x1fe10000}]

if {[catch {
    set device [u50hw::open_target $server_url $expected]
    set hw_axi [u50hw::get_axi]
    set value [u50hw::read_word $hw_axi $address]
    puts [format "READ_WORD=0x%08X:0x%08X" $address $value]
} message options]} {
    puts stderr "READ_WORD_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
u50hw::close_target
