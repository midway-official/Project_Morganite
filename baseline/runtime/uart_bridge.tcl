# Raw UART bytes use a loopback TCP socket; Vivado messages stay in its log.
set script_dir [file dirname [file normalize [info script]]]
source [file join $script_dir hw_common.tcl]
source [lindex $argv 0]
set clients {}
set pending_rx ""
set backlog ""
set first_client 1

proc drop_client {sock} {
    global clients
    catch {close $sock}
    set index [lsearch -exact $clients $sock]
    if {$index>=0} {set clients [lreplace $clients $index $index]}
}
proc receive_client {sock} {
    global pending_rx
    if {[catch {set bytes [read $sock 4096]}]} {drop_client $sock;return}
    if {[eof $sock]} {drop_client $sock;return}
    if {[string length $pending_rx]+[string length $bytes]>65536} {drop_client $sock;return}
    append pending_rx $bytes
}
proc accept_client {sock address port} {
    global clients backlog first_client
    if {[llength $clients]} {close $sock;return}
    fconfigure $sock -translation binary -encoding binary -buffering none -blocking 0
    lappend clients $sock
    if {$first_client} {
        if {[catch {puts -nonewline $sock $backlog;flush $sock}]} {drop_client $sock;return}
        set first_client 0
    }
    fileevent $sock readable [list receive_client $sock]
}
proc emit_uart {bytes} {
    global clients backlog uart_fd
    puts -nonewline $uart_fd $bytes
    append backlog $bytes
    if {[string length $backlog]>65536} {set backlog [string range $backlog end-65535 end]}
    foreach sock $clients {
        if {[catch {puts -nonewline $sock $bytes;flush $sock}]} {drop_client $sock;continue}
        set queued 0
        catch {set queued [chan pending output $sock]}
        if {$queued>65536} {drop_client $sock}
    }
}
proc unpack_uart {data count} {
    set bytes ""
    set length [string length $data]
    for {set i 0} {$i<$count} {incr i} {
        set offset [expr {$length-8*($i/4+1)}]
        scan [string range $data $offset [expr {$offset+7}]] %x word
        append bytes [binary format c [expr {($word>>(8*($i%4)))&255}]]
    }
    return $bytes
}
set uart_fd [open $uart_log ab]
fconfigure $uart_fd -translation binary -encoding binary -buffering none
if {[catch {
    set device [u50hw::open_target $server xcu50]
    set axi [u50hw::get_axi]
    if {[u50hw::read_word $axi 0x1fe10010]!=0x54324801} {error "Incompatible SoC UART ABI"}
    set listener [socket -server accept_client -myaddr 127.0.0.1 $uart_port]
    set ready [open $ready_file w]
    puts $ready "PID=[pid]\nPORT=$uart_port"
    close $ready
    puts "UART_BRIDGE_READY=$uart_port"
    set cpu_start_pending $start_cpu
    set last_heartbeat 0
    while {![file exists $stop_file]} {
        update
        if {$cpu_start_pending && [llength $clients]} {
            u50hw::write_word $axi 0x1fe10000 1
            set cpu_start_pending 0
            puts "UART_CPU_RELEASED_AFTER_CLIENT_CONNECT=1"
        }
        set status [u50hw::read_word $axi 0x1fe10000]
        set count [expr {($status>>19)&8191}]
        if {$count>=4} {
            set take [expr {min(512,$count&~3)}]
            emit_uart [unpack_uart [u50hw::read_words $axi 0x1fe11000 [expr {$take/4}] bridge_tx] $take]
        } elseif {$count>0} {
            set one [u50hw::read_word $axi 0x1fe10004]
            if {$one & 0x80000000} {emit_uart [binary format c [expr {$one&255}]]}
        }
        set free [expr {256-(($status>>10)&511)}]
        set take [expr {min(16,$free,[string length $pending_rx])}]
        for {set i 0} {$i<$take} {incr i} {
            binary scan [string index $pending_rx $i] c code
            u50hw::write_word $axi 0x1fe10008 [expr {$code&255}]
        }
        set pending_rx [string range $pending_rx $take end]
        if {[clock milliseconds]-$last_heartbeat>=1000} {
            set fd [open "$heartbeat_file.tmp" w]
            puts $fd "PID=[pid]\nSTATUS=$status\nTIME=[clock seconds]\nCLIENTS=[llength $clients]"
            close $fd
            file rename -force "$heartbeat_file.tmp" $heartbeat_file
            set last_heartbeat [clock milliseconds]
        }
        after 10
    }
    foreach sock $clients {drop_client $sock}
    close $listener
    puts "UART_BRIDGE_STOPPED=1"
} message options]} {
    puts stderr "UART_BRIDGE_FAILED=$message"
    close $uart_fd
    u50hw::close_target
    return -options $options $message
}
close $uart_fd
u50hw::close_target
