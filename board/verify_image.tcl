# Compare the independent JTAG read after all image writes, before CPU release.
proc morganite_check_bytes {axi address bytes} {
    set count [expr {([string length $bytes]+3)/4}]
    append bytes [string repeat \x00 [expr {$count*4-[string length $bytes]}]]
    binary scan $bytes H* expected
    set data [u50hw::read_words $axi $address $count verify_image]
    binary scan [binary format H* $data] c* values
    binary scan [binary format c* [lreverse $values]] H* actual
    if {$actual ne $expected} {
        for {set i 0} {$i < $count*4} {incr i} {
            set k [expr {2*$i}]
            set want [string range $expected $k [expr {$k+1}]]
            set got [string range $actual $k [expr {$k+1}]]
            if {$got ne $want} {
                error [format "HBM_READBACK_MISMATCH address=%08X expected=%s actual=%s" [expr {$address+$i}] $want $got]
            }
        }
        error "HBM readback length mismatch"
    }
}

proc morganite_verify_image {axi image zeros} {
    set fd [open $image r]
    set bytes ""
    set total 0
    set address -1
    while {[gets $fd line] >= 0} {
        regsub {//.*$} $line "" line
        foreach token $line {
            if {[string index $token 0] eq "@"} {
                if {$bytes ne ""} {
                    morganite_check_bytes $axi $address $bytes
                    incr total [string length $bytes]
                    set bytes ""
                }
                scan [string range $token 1 end] %x address
            } else {
                scan $token %x value
                append bytes [binary format c $value]
                if {[string length $bytes] == 512} {
                    morganite_check_bytes $axi $address $bytes
                    incr address 512
                    incr total 512
                    set bytes ""
                }
            }
        }
    }
    close $fd
    if {$bytes ne ""} {
        morganite_check_bytes $axi $address $bytes
        incr total [string length $bytes]
    }
    set cleared 0
    foreach range $zeros {
        lassign $range first limit
        for {set addr $first} {$addr < $limit} {incr addr 512} {
            set n [expr {min(512, $limit-$addr)}]
            morganite_check_bytes $axi $addr [string repeat \x00 $n]
            incr cleared $n
        }
    }
    puts "HBM_IMAGE_COMPARE_PASS bytes=$total zero_bytes=$cleared"
}
