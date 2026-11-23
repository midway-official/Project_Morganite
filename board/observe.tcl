# Called from the UART bridge only during the Linux benchmark. The JTAG word is
# above Linux RAM and outside the GPU aperture and HBM pseudo-channel test words.
proc morganite_observe {axi dir} {
    set result [file join $dir concurrent.json]
    if {![file exists [file join $dir observe]] || [file exists $result]} {return}
    set seq [u50hw::read_word $axi 0x1fe20028]
    if {([u50hw::read_word $axi 0x1fe20014] & 1) == 0} {return}
    set pattern [expr {$seq ^ 0x54c2f314}]
    u50hw::write_word $axi 0x20101000 $pattern
    set observed [u50hw::read_word $axi 0x20101000]
    if {$observed != $pattern} {error "concurrent JTAG data mismatch"}
    if {([u50hw::read_word $axi 0x1fe20014] & 1) && [u50hw::read_word $axi 0x1fe20028] == $seq} {
        set fd [open $result w]
        puts $fd [format {{"sequence":%d,"address":%d,"word":%d}} $seq 0x20101000 $observed]
        close $fd
    }
}
