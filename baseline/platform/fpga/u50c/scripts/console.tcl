set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]
set server_url [expr {[llength $argv] >= 1 ? [lindex $argv 0] : "127.0.0.1:13121"}]
set expected [expr {[llength $argv] >= 2 ? [lindex $argv 1] : "xcu50"}]
set control 0x1fe10000
set rx_data 0x1fe10008
set tx_stream 0x1fe11000

proc emit_stream_data {hex_data byte_count} {
    set length [string length $hex_data]
    set emitted 0
    for {set word_index 0} {$emitted < $byte_count} {incr word_index} {
        set first [expr {$length - 8 * ($word_index + 1)}]
        set word_hex [string range $hex_data $first [expr {$first + 7}]]
        scan $word_hex %x word
        for {set lane 0} {$lane < 4 && $emitted < $byte_count} {incr lane} {
            set byte [expr {($word >> (8 * $lane)) & 0xff}]
            puts -nonewline [format %c $byte]
            incr emitted
        }
    }
    flush stdout
}

if {[catch {
    set device [u50hw::open_target $server_url $expected]
    set hw_axi [u50hw::get_axi]
    fconfigure stdin -blocking 0 -buffering none
    fconfigure stdout -buffering none
    puts "JTAG console active; press Ctrl+] to exit."
    while {1} {
        set status [u50hw::read_word $hw_axi $control]
        set tx_count [expr {($status >> 19) & 0x1fff}]
        while {$tx_count > 0} {
            set take [expr {$tx_count > 512 ? 512 : $tx_count}]
            set words [expr {($take + 3) / 4}]
            set stream [u50hw::read_words $hw_axi $tx_stream $words console_read]
            emit_stream_data $stream $take
            incr tx_count -$take
        }

        set input [read stdin 1]
        if {$input ne ""} {
            scan $input %c code
            if {$code == 29} {break}
            if {$code == 10} {set code 13}
            u50hw::write_word $hw_axi $rx_data $code
        }
        after 20
    }
} message options]} {
    puts stderr "CONSOLE_FAILED=$message"
    u50hw::close_target
    return -options $options $message
}
u50hw::close_target
