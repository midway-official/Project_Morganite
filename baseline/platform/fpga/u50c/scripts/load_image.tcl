set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]
set repo_root [file normalize [file join $script_dir ../../..]]
set default_image [file join $repo_root sim linux-boot-runs v0.3 image ram.dat]
set image [expr {[llength $argv] >= 1 ? [file normalize [lindex $argv 0]] : $default_image}]
set server_url [expr {[llength $argv] >= 2 ? [lindex $argv 1] : "127.0.0.1:13121"}]
set start_cpu [expr {[llength $argv] >= 3 ? [lindex $argv 2] : 1}]
set expected [expr {[llength $argv] >= 4 ? [lindex $argv 3] : "xcu50"}]
if {![file exists $image]} {error "Image does not exist: $image"}

proc bytes_to_words {bytes} {
    set padded $bytes
    while {[llength $padded] % 4} {lappend padded 0}
    set words {}
    for {set i 0} {$i < [llength $padded]} {incr i 4} {
        set b0 [lindex $padded $i]
        set b1 [lindex $padded [expr {$i + 1}]]
        set b2 [lindex $padded [expr {$i + 2}]]
        set b3 [lindex $padded [expr {$i + 3}]]
        lappend words [format %02X%02X%02X%02X $b3 $b2 $b1 $b0]
    }
    return $words
}

proc flush_image_chunk {hw_axi address bytes verify} {
    if {[llength $bytes] == 0} {return 0}
    set words [bytes_to_words $bytes]
    u50hw::write_words $hw_axi $address $words image_write
    if {$verify} {
        set got [u50hw::read_word $hw_axi $address]
        scan [lindex $words 0] %x expected
        if {$got != $expected} {
            error [format "Readback mismatch at 0x%08X: expected %08X got %08X" \
                $address $expected $got]
        }
    }
    return [expr {[llength $words] * 4}]
}

proc hbm_self_test {hw_axi} {
    # 128 32-bit beats become the HBM port's maximum 16 256-bit beats.  This
    # exercises the complete JTAG/fabric/width-converter/CDC/HBM path before
    # the CPU can leave reset.
    set address 0x1fdff000
    set patterns {}
    for {set i 0} {$i < 128} {incr i} {
        set value [expr {(0xa5a50000 ^ ($i * 0x01010101)) & 0xffffffff}]
        lappend patterns [format %08X $value]
    }
    u50hw::write_words $hw_axi $address $patterns hbm_test_write
    set readback [u50hw::read_words $hw_axi $address 128 hbm_test_read]
    set width [string length $readback]
    if {$width != 1024} {
        error "HBM self-test returned $width hex digits, expected 1024"
    }
    for {set index 0} {$index < 128} {incr index} {
        set first [expr {$width - 8 * ($index + 1)}]
        scan [string range $readback $first [expr {$first + 7}]] %x got
        scan [lindex $patterns $index] %x expected
        if {$got != $expected} {
            error [format "HBM self-test mismatch at 0x%08X: expected %08X got %08X" \
                [expr {$address + 4 * $index}] $expected $got]
        }
    }
    puts "HBM_SELF_TEST_OK=128_words"
}

if {[catch {
    set device [u50hw::open_target $server_url $expected]
    set hw_axi [u50hw::get_axi]
    set control 0x1fe10000
    # Hold the CPU and clear stale TX/RX/overflow state before each load.
    u50hw::write_word $hw_axi $control 0x0000000e
    set build_id [u50hw::read_word $hw_axi 0x1fe10010]
    if {$build_id != 0x54324801} {error [format "Unexpected SoC build/ABI ID: %08X" $build_id]}

    set ready 0
    for {set retry 0} {$retry < 200} {incr retry} {
        set status [u50hw::read_word $hw_axi $control]
        if {($status & 0x12) == 0x12} {set ready 1; break}
        after 100
    }
    if {!$ready} {error "HBM initialization or CPU quiesce did not complete"}
    puts "HBM_READY=1"
    hbm_self_test $hw_axi

    set fd [open $image r]
    set address -1
    set bytes {}
    set segment_first 1
    set total 0
    while {[gets $fd line] >= 0} {
        set line [string trim $line]
        if {$line eq ""} {continue}
        if {[string index $line 0] eq "@"} {
            if {$address >= 0 && [llength $bytes]} {
                set advanced [flush_image_chunk $hw_axi $address $bytes $segment_first]
                incr address $advanced
                incr total [llength $bytes]
            }
            scan [string range $line 1 end] %x address
            set bytes {}
            set segment_first 1
            puts [format "LOAD_SEGMENT=0x%08X" $address]
        } else {
            scan $line %x value
            lappend bytes [expr {$value & 0xff}]
            if {[llength $bytes] == 512} {
                # Read back the first word of every segment and each MiB-sized
                # transfer window without doubling the full JTAG load time.
                set verify [expr {$segment_first || ($total % 1048576) == 0}]
                set advanced [flush_image_chunk $hw_axi $address $bytes $verify]
                incr address $advanced
                incr total 512
                set bytes {}
                set segment_first 0
                if {($total % 1048576) == 0} {puts "LOADED_BYTES=$total"}
            }
        }
    }
    close $fd
    if {$address >= 0 && [llength $bytes]} {
        flush_image_chunk $hw_axi $address $bytes $segment_first
        incr total [llength $bytes]
    }
    puts "IMAGE_LOADED_BYTES=$total"
    if {$start_cpu} {
        u50hw::write_word $hw_axi $control 1
        puts "CPU_RUN=1"
    } else {
        puts "CPU_RUN=0"
    }
} message options]} {
    puts stderr "LOAD_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
u50hw::close_target
