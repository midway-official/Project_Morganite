namespace eval u50hw {
    # The public hw_server exposes this cable as Xilinx/<serial>; some local
    # servers prepend FT4232H-.  Match the stable cable serial in both cases.
    variable target_pattern "*50241A20256MA*"
    variable default_probes [file normalize [file join [file dirname [info script]] ../build/u50c_top.ltx]]
}

proc u50hw::open_target {{server_url "127.0.0.1:13121"} {expected_family "xcu50"}} {
    open_hw_manager
    connect_hw_server -url $server_url -allow_non_jtag
    set targets [get_hw_targets -quiet $u50hw::target_pattern]
    if {[llength $targets] != 1} {
        error "Expected exactly one U50C target matching $u50hw::target_pattern, got: $targets"
    }
    set target [lindex $targets 0]
    open_hw_target $target

    set devices [get_hw_devices -quiet]
    if {[llength $devices] < 1} {
        error "No FPGA was found behind [get_property NAME $target]"
    }
    set device ""
    foreach candidate $devices {
        set name [string tolower [get_property NAME $candidate]]
        if {[string match "*xcu50*" $name] || [string match "*xcvu35p*" $name]} {
            set device $candidate
            break
        }
    }
    if {$device eq ""} {
        error "Neither XCU50 nor XCVU35P was found in scan chain: $devices"
    }

    set actual_name [string tolower [get_property NAME $device]]
    set actual_part ""
    foreach property {PART PART_NAME} {
        if {![catch {set value [get_property $property $device]}] && $value ne ""} {
            set actual_part [string tolower $value]
            break
        }
    }
    puts "HW_TARGET=[get_property NAME $target]"
    puts "HW_DEVICE=$actual_name"
    puts "HW_PART=$actual_part"

    set expected [string tolower $expected_family]
    if {$expected ne "" &&
        ![string match "*${expected}*" $actual_name] &&
        ![string match "*${expected}*" $actual_part]} {
        error "Hardware part mismatch: expected $expected, scanned device $actual_name part $actual_part"
    }
    current_hw_device $device
    return $device
}

proc u50hw::close_target {} {
    catch {close_hw_target}
    catch {disconnect_hw_server}
    catch {close_hw_manager}
}

proc u50hw::refresh_device {} {
    set device [current_hw_device]
    set probes $u50hw::default_probes
    if {[info exists ::env(U50_PROBES_FILE)]} {
        set probes [file normalize $::env(U50_PROBES_FILE)]
        if {![file exists $probes]} {error "U50_PROBES_FILE does not exist: $probes"}
    }
    if {[file exists $probes]} {
        set_property PROBES.FILE $probes $device
        set_property FULL_PROBES.FILE $probes $device
        refresh_hw_device -update_hw_probes true $device
    } else {
        refresh_hw_device -update_hw_probes false $device
    }
}

proc u50hw::get_axi {} {
    u50hw::refresh_device
    set axes [get_hw_axis -quiet]
    if {[llength $axes] != 1} {
        error "Expected one JTAG-to-AXI core, got: $axes"
    }
    return [lindex $axes 0]
}

proc u50hw::write_words {hw_axi address words {name write_words}} {
    if {[llength $words] < 1 || [llength $words] > 128} {
        error "write_words supports 1..128 words, got [llength $words]"
    }
    # Vivado represents a burst as one wide hexadecimal value.  The lowest
    # address is the least-significant (right-most) 32-bit word.
    set stream [join [lreverse $words] _]
    create_hw_axi_txn -force $name $hw_axi -type WRITE \
        -address [format %08X $address] -len [llength $words] \
        -burst INCR -data $stream
    run_hw_axi [get_hw_axi_txns $name]
    delete_hw_axi_txn [get_hw_axi_txns $name]
}

proc u50hw::read_words {hw_axi address count {name read_words}} {
    create_hw_axi_txn -force $name $hw_axi -type READ \
        -address [format %08X $address] -len $count -burst INCR
    run_hw_axi [get_hw_axi_txns $name]
    set data [get_property DATA [get_hw_axi_txns $name]]
    delete_hw_axi_txn [get_hw_axi_txns $name]
    regsub -all {[^0-9A-Fa-f]} $data "" data
    return [string toupper $data]
}

proc u50hw::read_word {hw_axi address} {
    set data [u50hw::read_words $hw_axi $address 1 read_one]
    if {[string length $data] < 8} {
        error "Invalid JTAG AXI read data '$data'"
    }
    scan [string range $data end-7 end] %x value
    return $value
}

proc u50hw::write_word {hw_axi address value} {
    u50hw::write_words $hw_axi $address [list [format %08X $value]] write_one
}
