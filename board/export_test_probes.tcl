if {$argc != 2} {error "expected implemented checkpoint and output LTX"}
lassign $argv checkpoint probes
open_checkpoint $checkpoint
write_debug_probes -force $probes
