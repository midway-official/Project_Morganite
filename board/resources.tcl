# Run against the exact complete-design checkpoint used for timing or bitstream.
if {$argc != 4} {error "usage: resources.tcl design.dcp output-dir synth|route baseline|gpu"}
lassign $argv checkpoint dest stage variant
set dest [file normalize $dest]
file mkdir $dest
set checkpoint [file normalize $checkpoint]
if {[file extension $checkpoint] eq ".xpr"} {
    if {$stage ne "synth"} {error "project input is supported for linked synthesis only"}
    open_project -read_only $checkpoint
    open_run synth_1
    set checkpoint [file join $dest linked-synth.dcp]
    write_checkpoint -force $checkpoint
} else {
    open_checkpoint $checkpoint
}
set meta [open [file join $dest provenance.txt] w]
puts $meta "checkpoint=$checkpoint"
puts $meta "checkpoint_sha256=[lindex [exec sha256sum $checkpoint] 0]"
puts $meta "vivado=[version -short]"
puts $meta "part=[get_property PART [current_design]]"
puts $meta "stage=$stage"
puts $meta "variant=$variant"
report_utilization -file [file join $dest soc.rpt]
report_utilization -hierarchical -hierarchical_depth 5 -file [file join $dest hierarchy.rpt]
set cpu [get_cells -quiet u_cpu]
if {[llength $cpu] != 1} {
    set cpu [get_cells -quiet -hierarchical -filter {REF_NAME == Aurora || ORIG_REF_NAME == Aurora}]
}
if {[llength $cpu] != 1} {error "cannot identify unique Aurora CPU scope"}
set scopes [dict create cpu $cpu]
if {$variant eq "gpu"} {
    set gpu [get_cells -quiet u_gpu_soc/gpu]
    if {[llength $gpu] != 1} {
        set gpu [get_cells -quiet -hierarchical -filter {REF_NAME == Gpu || ORIG_REF_NAME == Gpu}]
    }
    if {[llength $gpu] != 1} {error "cannot identify unique GPU scope"}
    dict set scopes gpu $gpu
}
dict for {name cell} $scopes {
    puts $meta "$name=[get_property NAME $cell]"
    report_utilization -cells $cell -file [file join $dest $name.rpt]
}
if {$stage eq "route"} {
    report_route_status -file [file join $dest route.rpt]
    # Classify placed primitives on each die by implemented hierarchy, including
    # the interconnect and replicated physical logic outside the compute blocks.
    foreach slr [get_slrs] {
        set sites [get_sites -quiet -of_objects $slr]
        set primitives [get_cells -quiet -of_objects $sites -filter {IS_PRIMITIVE}]
        set region [get_property NAME $slr]
        set groups [dict create]
        foreach leaf $primitives {
            set path [get_property NAME $leaf]
            set owner rest
            dict for {name cell} $scopes {
                if {[string first "$cell/" $path] == 0} {set owner $name; break}
            }
            dict lappend groups $owner $leaf
        }
        foreach name [concat [dict keys $scopes] rest] {
            if {[dict exists $groups $name]} {
                set selected [dict get $groups $name]
                puts $meta "$region.$name.placed=[llength $selected]"
                report_utilization -cells $selected -file [file join $dest ${region}_$name.rpt]
            } else {
                puts $meta "$region.$name.placed=0"
            }
        }
    }
}
close $meta
exit
