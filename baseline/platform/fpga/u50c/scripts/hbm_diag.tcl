set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]

set server_url [expr {[llength $argv] >= 1 ? [lindex $argv 0] : "127.0.0.1:13121"}]
set expected [expr {[llength $argv] >= 2 ? [lindex $argv 1] : "xcu50"}]
set base [expr {[llength $argv] >= 3 ? [lindex $argv 2] : 0x1fdf0000}]
set control 0x1fe10000

if {[catch {
    set device [u50hw::open_target $server_url $expected]
    set hw_axi [u50hw::get_axi]
    u50hw::write_word $hw_axi $control 0x0000000e
    set status [u50hw::read_word $hw_axi $control]
    puts [format "STATUS=0x%08X" $status]
    if {!($status & 2)} {error "HBM initialization is not complete"}
    puts [format "HBM_DIAG_BASE=0x%08X" $base]

    foreach length {1 2 4 8 16 32 64 128} {
        set words {}
        for {set index 0} {$index < $length} {incr index} {
            lappend words [format %08X [expr {(0x51000000 ^ ($length << 16) ^ $index) & 0xffffffff}]]
        }
        puts "HBM_DIAG_BEGIN=$length"
        u50hw::write_words $hw_axi $base $words [format "diag_write_%d" $length]
        set first [u50hw::read_word $hw_axi $base]
        set last [u50hw::read_word $hw_axi [expr {$base + 4 * ($length - 1)}]]
        scan [lindex $words 0] %x expected_first
        scan [lindex $words end] %x expected_last
        if {$first != $expected_first || $last != $expected_last} {
            error [format "HBM diagnostic mismatch for length %d: first=%08X/%08X last=%08X/%08X" \
                $length $first $expected_first $last $expected_last]
        }
        puts "HBM_DIAG_OK=$length"
    }
} message options]} {
    puts stderr "HBM_DIAG_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
u50hw::close_target
