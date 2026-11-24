# Extract worst-per-endpoint setup and hold paths from an implemented checkpoint.
# Run: vivado -mode batch -source timing_paths.tcl -tclargs design.dcp out
if {$argc != 2} {error "expected a checkpoint and output directory"}
lassign $argv checkpoint dest
file mkdir $dest
set_param general.maxThreads 2
open_checkpoint $checkpoint

proc owner {pin} {
    if {[string match "u_cpu/*" $pin]} {return cpu}
    if {[string match "u_gpu_soc/gpu/*" $pin]} {return gpu}
    if {[string match "u_gpu_soc/*" $pin]} {return gpu_board_interface}
    if {[string match "u_memory/*" $pin]} {return hbm_bridge}
    return [lindex [split $pin /] 0]
}

proc slr_of {pin} {
    set cell [get_cells -quiet -of_objects [get_pins -quiet $pin]]
    if {![llength $cell]} {return "-"}
    set loc [get_property LOC $cell]
    if {$loc eq ""} {return "-"}
    set slr [get_slrs -quiet -of_objects [get_sites -quiet $loc]]
    return [join $slr ,]
}

set meta [open [file join $dest checkpoint.txt] w]
puts $meta "checkpoint=$checkpoint"
puts $meta "sha256=[lindex [exec sha256sum $checkpoint] 0]"
puts $meta "vivado=[version -short]"
set out [open [file join $dest paths.tsv] w]
puts $out [join {analysis launch_clock capture_clock slack_ns data_ns logic_levels from_owner to_owner from_slr to_slr start_pin end_pin} "\t"]
foreach analysis {max min} {
    set cap 4096
    set paths [get_timing_paths -quiet -delay_type $analysis -slack_lesser_than 0 -max_paths $cap -nworst 1 -sort_by slack]
    puts $meta "$analysis.negative_paths_returned=[llength $paths]"
    puts $meta "$analysis.query_limit=$cap"
    if {![llength $paths]} {
        set paths [get_timing_paths -delay_type $analysis -max_paths 1 -nworst 1 -sort_by slack]
    }
    set examples {}
    set seen {}
    foreach path $paths {
        set src [get_property STARTPOINT_PIN $path]
        set dst [get_property ENDPOINT_PIN $path]
        set launch [get_property STARTPOINT_CLOCK $path]
        set capture [get_property ENDPOINT_CLOCK $path]
        set from [owner $src]
        set to [owner $dst]
        puts $out [join [list $analysis $launch $capture [get_property SLACK $path] [get_property DATAPATH_DELAY $path] \
            [get_property LOGIC_LEVELS $path] $from $to [slr_of $src] [slr_of $dst] $src $dst] "\t"]
        set key [list $launch $capture $from $to]
        if {![dict exists $seen $key]} {
            dict set seen $key 1
            lappend examples $path
        }
    }
    report_timing -of_objects $examples -file [file join $dest ${analysis}_examples.rpt]
}
close $out
close $meta
