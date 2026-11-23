set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]
set server [expr {[llength $argv] ? [lindex $argv 0] : "127.0.0.1:13121"}]
if {[catch {
    set device [u50hw::open_target $server xcu50]
    set axi [u50hw::get_axi]
    u50hw::write_word $axi 0x1fe10000 0x0e
    set halted 0
    for {set i 0} {$i < 200} {incr i} {
        set status [u50hw::read_word $axi 0x1fe10000]
        if {($status & 0x1f) == 0x16} {set halted 1; break}
        after 10
    }
    if {!$halted} {error [format "CPU/HBM/clock status not ready: %08X" $status]}
    foreach {address expected} {0x1fe10010 0x54324801 0x1fe10014 0x00e10064} {
        for {set i 0} {$i < 8} {incr i} {
            set value [u50hw::read_word $axi $address]
            if {$value != $expected} {error "SoC identifier/clock register mismatch"}
        }
    }
    foreach byte {0x5a 0xa5 0x31} {u50hw::write_word $axi 0x1fe10008 $byte}
    set status [u50hw::read_word $axi 0x1fe10000]
    if {(($status >> 10) & 511) != 3} {error "RX FIFO writes/count did not agree"}
    u50hw::write_word $axi 0x1fe10000 0x0e
    set status [u50hw::read_word $axi 0x1fe10000]
    if {$status != 0x16} {error [format "FIFO clear/status mismatch: %08X" $status]}
    puts "PERIPHERAL_RW_VERIFY_PASS=1"

    set vios [get_hw_vios -quiet]
    if {[llength $vios] != 2} {error "Expected exactly two VIO cores"}
    set status_seen 0
    set hbm_seen 0
    foreach vio $vios {
        refresh_hw_vio $vio
        set cell [get_property CELL_NAME $vio]
        set probe [get_hw_probes -of_objects $vio -filter {
            NAME =~ *probe_in0 || NAME =~ *hbm_axi_debug* || NAME =~ *status_ref*
        }]
        if {[llength $probe] != 1} {error "Cannot identify the VIO input: $cell"}
        set raw [get_property INPUT_VALUE $probe]
        if {[scan $raw %x value] != 1} {error "Cannot parse VIO input: $raw"}
        puts [format "VIO_STATUS=%s:%X" $cell $value]
        if {[string match *u_status_vio* $cell]} {
            if {$value != 0x3f} {error "VIO shows unlocked clocks, reset, or thermal trip"}
            set status_seen 1
        } elseif {[string match *u_hbm_vio* $cell]} {
            if {($value & 3) != 3} {error "HBM VIO reset/APB initialization is not ready"}
            set hbm_seen 1
        }
    }
    if {!$status_seen || !$hbm_seen} {error "Missing named status/HBM VIO"}
    puts "BOARD_VERIFY_PASS=Tick2h_U50C_100MHz"
} message options]} {
    puts stderr "BOARD_VERIFY_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
u50hw::close_target
