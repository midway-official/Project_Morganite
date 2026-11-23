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
    set ready 0
    for {set retry 0} {$retry < 200} {incr retry} {
        set status [u50hw::read_word $hw_axi $control]
        if {($status & 0x1f) == 0x16} {set ready 1; break}
        after 10
    }
    puts [format "STATUS=0x%08X" $status]
    if {!$ready} {error "HBM initialization/CPU stop did not complete"}
    if {[u50hw::read_word $hw_axi 0x1fe10010] != 0x54324801} {error "Unexpected SoC ABI"}
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
    # One 256 MiB pseudo-channel region per address[31:28], left stack only.
    # Write every region before reading any, so decoder aliases cannot pass.
    foreach invert {0 1} {
        for {set pc 0} {$pc < 16} {incr pc} {
            set address [expr {($pc << 28) | 0x00100000}]
            set words {}
            for {set i 0} {$i < 128} {incr i} {
                set value [expr {(0xa55a5aa5 ^ $address ^ ($i * 0x01020409) ^
                                 ($invert ? 0xffffffff : 0)) & 0xffffffff}]
                lappend words [format %08X $value]
            }
            u50hw::write_words $hw_axi $address $words pc_write
        }
        for {set pc 0} {$pc < 16} {incr pc} {
            set address [expr {($pc << 28) | 0x00100000}]
            set data [u50hw::read_words $hw_axi $address 128 pc_read]
            if {[string length $data] != 1024} {error "HBM pseudo-channel read was truncated"}
            for {set i 0} {$i < 128} {incr i} {
                set offset [expr {1024 - 8*($i+1)}]
                scan [string range $data $offset [expr {$offset+7}]] %x got
                set expected [expr {(0xa55a5aa5 ^ $address ^ ($i * 0x01020409) ^
                                    ($invert ? 0xffffffff : 0)) & 0xffffffff}]
                if {$got != $expected} {error [format "HBM PC%d mismatch at %08X: %08X/%08X" $pc [expr {$address+4*$i}] $got $expected]}
            }
            puts "HBM_PC_VERIFY_PASS=pc$pc,invert$invert"
        }
    }
    puts "HBM_DIAG_VERIFY_PASS=16_pseudo_channels,2_patterns,128_words"
} message options]} {
    puts stderr "HBM_DIAG_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
u50hw::close_target
