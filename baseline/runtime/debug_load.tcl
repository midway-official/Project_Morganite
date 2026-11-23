# Config values are generated as hexadecimal UTF-8, never evaluated as commands.
set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]
source [lindex $argv 0]

proc load_bytes {axi address bytes {verify 1}} {
    binary scan $bytes c* values
    while {[llength $values] % 4} {lappend values 0}
    set words {}
    foreach {b0 b1 b2 b3} $values {
        lappend words [format %02X%02X%02X%02X [expr {$b3&255}] [expr {$b2&255}] [expr {$b1&255}] [expr {$b0&255}]]
    }
    u50hw::write_words $axi $address $words debug_image_write
    if {$verify} {
        set got [u50hw::read_word $axi $address]
        scan [lindex $words 0] %x expected
        if {$got != $expected} {error [format "Image readback mismatch at %08X" $address]}
    }
    return [expr {[llength $words]*4}]
}
if {[catch {
    set device [u50hw::open_target $server xcu50]
    set axi [u50hw::get_axi]
    u50hw::write_word $axi 0x1fe10000 14
    set ready 0
    for {set i 0} {$i<200} {incr i} {
        set status [u50hw::read_word $axi 0x1fe10000]
        if {($status & 31)==22} {set ready 1;break}
        after 10
    }
    if {!$ready} {error "HBM/clock readiness or CPU quiescence failed"}
    if {[u50hw::read_word $axi 0x1fe10010] != 0x54324801} {error "Incompatible SoC UART/control ABI"}
    foreach range $zero_ranges {
        lassign $range first limit
        puts [format "DEBUG_ZERO_RANGE=%08X:%08X" $first $limit]
        for {set address $first} {$address<$limit} {incr address 512} {
            set count [expr {min(128,($limit-$address)/4)}]
            u50hw::write_words $axi $address [lrepeat $count 00000000] debug_zero
            if {(($address-$first)%1048576)==0} {puts [format "DEBUG_ZERO_PROGRESS=%08X" $address]}
        }
        if {[u50hw::read_word $axi $first] != 0 || [u50hw::read_word $axi [expr {$limit-4}]] != 0} {
            error "Memory initialization readback failed"
        }
        puts [format "DEBUG_ZERO_VERIFY_PASS=%08X:%08X" $first $limit]
    }
    set fd [open $image rb]
    fconfigure $fd -translation binary
    set total 0
    if {$image_format eq "bin"} {
        set address $load_address
        while {![eof $fd]} {
            set bytes [read $fd 512]
            if {$bytes eq ""} {break}
            incr address [load_bytes $axi $address $bytes [expr {($total%1048576)==0}]]
            incr total [string length $bytes]
        }
    } else {
        set address -1
        set bytes ""
        set segment_first 1
        while {[gets $fd line]>=0} {
            regsub {//.*$} $line "" line
            foreach token $line {
                if {[string index $token 0] eq "@"} {
                    if {$bytes ne ""} {
                        incr address [load_bytes $axi $address $bytes]
                        incr total [string length $bytes];set bytes ""
                    }
                    if {[scan [string range $token 1 end] %x address]!=1} {error "Invalid image address"}
                    set segment_first 1
                } else {
                    if {$address<0 || ![regexp {^[0-9A-Fa-f]{1,2}$} $token]} {error "Expected @address and byte-hex tokens"}
                    scan $token %x value
                    append bytes [binary format c $value]
                    if {[string length $bytes]==512} {
                        incr address [load_bytes $axi $address $bytes [expr {$segment_first || ($total%1048576)==0}]]
                        incr total 512;set bytes ""
                        set segment_first 0
                        if {($total%1048576)==0} {puts "DEBUG_IMAGE_PROGRESS=$total"}
                    }
                }
            }
        }
        if {$bytes ne ""} {load_bytes $axi $address $bytes;incr total [string length $bytes]}
    }
    close $fd
    puts "DEBUG_IMAGE_LOADED_BYTES=$total"
    puts "DEBUG_LOAD_PASS_CPU_HELD=1"
} message options]} {
    puts stderr "DEBUG_LOAD_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
u50hw::close_target
