set script_dir [file dirname [file normalize [info script]]]
set u50_dir [file normalize [file join $script_dir ..]]
set repo_root [file normalize [file join $u50_dir ../..]]
set build_dir [file normalize [file join $u50_dir build]]
if {[llength $argv] >= 4} {set build_dir [file normalize [lindex $argv 3]]}
set project_dir [file join $build_dir vivado]
set generated_rtl [file join $build_dir generated Aurora.v]
set board_repo [file join $build_dir board_repo]

set part [expr {[llength $argv] >= 1 ? [lindex $argv 0] : "xcu50-fsvh2104-2-e"}]
set jobs [expr {[llength $argv] >= 2 ? [lindex $argv 1] : 4}]
set build_bitstream [expr {[llength $argv] >= 3 ? [lindex $argv 2] : 1}]
set_param general.maxThreads $jobs

if {![file exists $generated_rtl]} {
    error "Missing generated Aurora RTL: $generated_rtl"
}
if {![file isdirectory $board_repo]} {
    error "Missing extracted AU50 board repository: $board_repo"
}

set_param board.repoPaths [list $board_repo]
set board_part xilinx.com:au50:part0:1.3
set board_matches [get_board_parts -quiet $board_part]
if {[llength $board_matches] != 1} {
    error "AU50 board part $board_part was not found in $board_repo"
}
set board_device [get_property PART_NAME [lindex $board_matches 0]]
puts "BOARD_PART=$board_part"
puts "BOARD_DEVICE=$board_device"
puts "BUILD_PART=$part"
if {![string equal -nocase $part $board_device]} {
    error "Board file part $board_device does not match requested build part $part"
}

file delete -force $project_dir
create_project -force aurora_u50c $project_dir -part $part
set_property strategy Flow_PerfOptimized_high [get_runs synth_1]
set_property strategy Performance_ExplorePostRoutePhysOpt [get_runs impl_1]
puts "SYNTH_STRATEGY=[get_property STRATEGY [get_runs synth_1]]"
puts "IMPL_STRATEGY=[get_property STRATEGY [get_runs impl_1]]"
set_property board_part $board_part [current_project]
set_property target_language Verilog [current_project]
set_property simulator_language Mixed [current_project]

set rtl_dir [file join $u50_dir rtl]
set rtl_files [list \
    [file join $rtl_dir axi4_if.svh] \
    [file join $rtl_dir aurora_axi_wrapper.sv] \
    [file join $rtl_dir jtag_axi_master.sv] \
    [file join $rtl_dir axi_arbiter_2m2s.sv] \
    [file join $rtl_dir jtag_uart_16550.sv] \
    [file join $rtl_dir hbm_axi_bridge.sv] \
    [file join $rtl_dir u50c_top.sv]]
add_files -norecurse $generated_rtl
add_files -norecurse $rtl_files
set_property file_type SystemVerilog [get_files -quiet *.sv]
set_property include_dirs [list $rtl_dir] [get_filesets sources_1]
add_files -fileset constrs_1 -norecurse [file join $u50_dir constraints u50c_minimal.xdc]
set_property top u50c_top [get_filesets sources_1]

create_ip -name jtag_axi -vendor xilinx.com -library ip -module_name jtag_axi_0
set_property -dict [list \
    CONFIG.M_AXI_ADDR_WIDTH {32} \
    CONFIG.M_AXI_DATA_WIDTH {32} \
    CONFIG.M_AXI_ID_WIDTH {4} \
    CONFIG.PROTOCOL {0}] [get_ips jtag_axi_0]

create_ip -name axi_dwidth_converter -vendor xilinx.com -library ip \
    -module_name axi_dwidth_converter_0
set_property -dict [list \
    CONFIG.ADDR_WIDTH {32} \
    CONFIG.SI_DATA_WIDTH {32} \
    CONFIG.MI_DATA_WIDTH {256} \
    CONFIG.SI_ID_WIDTH {4} \
    CONFIG.PACKING_LEVEL {2} \
    CONFIG.PROTOCOL {AXI4} \
    CONFIG.ACLK_ASYNC {0}] [get_ips axi_dwidth_converter_0]

# HBM uses AXI3. Split long/unaligned packed AXI4 bursts correctly instead of
# truncating the eight-bit LEN at the HBM boundary.
create_ip -name axi_protocol_converter -vendor xilinx.com -library ip \
    -module_name axi_protocol_converter_0
set_property -dict [list CONFIG.ADDR_WIDTH {32} CONFIG.DATA_WIDTH {256} \
    CONFIG.ID_WIDTH {0} CONFIG.SI_PROTOCOL {AXI4} CONFIG.MI_PROTOCOL {AXI3} \
    CONFIG.TRANSLATION_MODE {2}] [get_ips axi_protocol_converter_0]

create_ip -name axi_clock_converter -vendor xilinx.com -library ip \
    -module_name axi_clock_converter_0
set_property -dict [list \
    CONFIG.ADDR_WIDTH {32} \
    CONFIG.DATA_WIDTH {256} \
    CONFIG.ID_WIDTH {0} \
    CONFIG.PROTOCOL {AXI4} \
    CONFIG.ACLK_ASYNC {1} \
    CONFIG.SYNCHRONIZATION_STAGES {3}] [get_ips axi_clock_converter_0]

create_ip -name clk_wiz -vendor xilinx.com -library ip -module_name clk_wiz_0
set_property -dict [list \
    CONFIG.PRIM_IN_FREQ {100.000} \
    CONFIG.PRIM_SOURCE {No_buffer} \
    CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {100.000} \
    CONFIG.CLKOUT2_USED {true} \
    CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {225.000} \
    CONFIG.USE_RESET {false}] [get_ips clk_wiz_0]

create_ip -name hbm -vendor xilinx.com -library ip -module_name hbm_0
set hbm_cfg [list \
    CONFIG.USER_HBM_DENSITY {4GB} \
    CONFIG.USER_SAXI_00 {true} \
    CONFIG.USER_AXI_ADDR_SIZE {32} \
    CONFIG.USER_AXI_CLK_FREQ {225} \
    CONFIG.USER_AXI_INPUT_CLK_FREQ {100} \
    CONFIG.USER_DIS_REF_CLK_BUFG {true} \
    CONFIG.USER_APB_EN {true}]
for {set i 1} {$i < 32} {incr i} {
    lappend hbm_cfg [format "CONFIG.USER_SAXI_%02d" $i] {false}
}
set_property -dict $hbm_cfg [get_ips hbm_0]

set clock_source [get_property CONFIG.PRIM_SOURCE [get_ips clk_wiz_0]]
set hbm_ref_buffer [get_property CONFIG.USER_DIS_REF_CLK_BUFG [get_ips hbm_0]]
if {![string equal -nocase $clock_source "No_buffer"]} {
    error "Clock Wizard unexpectedly adds an input buffer: $clock_source"
}
if {![string equal -nocase $hbm_ref_buffer "true"]} {
    error "HBM reference-clock input buffer must be disabled behind the top-level IBUFDS/BUFG"
}
puts "CLOCK_CONFIG=100MHz_input/100MHz_cpu/225MHz_hbm,source=$clock_source"
puts "HBM_CONFIG=4GB/SAXI00/refclk_buffer_disabled=$hbm_ref_buffer"

create_ip -name vio -vendor xilinx.com -library ip -module_name vio_status_0
set_property -dict [list \
    CONFIG.C_NUM_PROBE_IN {1} \
    CONFIG.C_NUM_PROBE_OUT {1} \
    CONFIG.C_PROBE_IN0_WIDTH {8} \
    CONFIG.C_PROBE_OUT0_WIDTH {1} \
    CONFIG.C_PROBE_OUT0_INIT_VAL {0x0}] [get_ips vio_status_0]

create_ip -name vio -vendor xilinx.com -library ip -module_name vio_hbm_0
set_property -dict [list \
    CONFIG.C_NUM_PROBE_IN {1} \
    CONFIG.C_NUM_PROBE_OUT {1} \
    CONFIG.C_PROBE_IN0_WIDTH {16} \
    CONFIG.C_PROBE_OUT0_WIDTH {1} \
    CONFIG.C_PROBE_OUT0_INIT_VAL {0x0}] [get_ips vio_hbm_0]

generate_target all [get_ips]
update_compile_order -fileset sources_1
update_compile_order -fileset sim_1

if {!$build_bitstream} {
    puts "PROJECT_CREATED_ONLY=1"
    close_project
    exit
}

launch_runs synth_1 -jobs $jobs
wait_on_run synth_1
if {[get_property PROGRESS [get_runs synth_1]] ne "100%"} {
    error "synth_1 did not complete"
}
open_run synth_1
foreach {port expected_pin expected_iostandard} {
    SYSCLK3_P BB18 LVDS
    SYSCLK3_N BC18 LVDS
    HBM_CATTRIP J18 LVCMOS18
} {
    set actual_pin [get_property PACKAGE_PIN [get_ports $port]]
    set actual_iostandard [get_property IOSTANDARD [get_ports $port]]
    if {![string equal -nocase $actual_pin $expected_pin] ||
        ![string equal -nocase $actual_iostandard $expected_iostandard]} {
        error "$port constraint mismatch: expected $expected_pin/$expected_iostandard, got $actual_pin/$actual_iostandard"
    }
    puts "PIN_CHECK=$port:$actual_pin:$actual_iostandard"
}
report_utilization -file [file join $build_dir utilization_synth.rpt]
report_timing_summary -file [file join $build_dir timing_synth.rpt]
report_drc -file [file join $build_dir drc_synth.rpt]
report_clock_interaction -file [file join $build_dir clock_interaction_synth.rpt]
close_design

launch_runs impl_1 -to_step write_bitstream -jobs $jobs
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] ne "100%"} {
    error "impl_1 did not complete"
}
open_run impl_1
report_utilization -file [file join $build_dir utilization_impl.rpt]
report_timing_summary -file [file join $build_dir timing_impl.rpt]
report_drc -file [file join $build_dir drc_impl.rpt]
report_route_status -file [file join $build_dir route_impl.rpt]
report_clock_interaction -file [file join $build_dir clock_interaction_impl.rpt]
report_cdc -file [file join $build_dir cdc_impl.rpt]
report_clocks -file [file join $build_dir clocks_impl.rpt]
check_timing -verbose -file [file join $build_dir check_timing_impl.rpt]
set cpu_clock [get_clocks -of_objects [get_nets clk_cpu]]
if {[llength $cpu_clock] != 1 || abs([get_property PERIOD $cpu_clock] - 10.0) > 0.001} {
    error "CPU clock is not constrained to 100MHz: $cpu_clock"
}
set drc_errors [get_drc_violations -quiet -filter {SEVERITY == "Error" || SEVERITY == "Critical Warning"}]
if {[llength $drc_errors]} {error "Implemented DRC errors/critical warnings: $drc_errors"}
set setup_failure [get_timing_paths -quiet -max_paths 1 -slack_lesser_than 0]
set hold_failure [get_timing_paths -quiet -delay_type min -max_paths 1 -slack_lesser_than 0]
if {[llength $setup_failure] || [llength $hold_failure]} {
    error "Implemented design has negative setup or hold slack"
}
write_debug_probes -force [file join $build_dir u50c_top.ltx]
set bit_file [file join $project_dir aurora_u50c.runs impl_1 u50c_top.bit]
if {![file exists $bit_file]} {error "Missing implemented bitstream: $bit_file"}
set fd [open [file join $build_dir BUILD_COMPLETE.txt] w]
puts $fd "CANDIDATE=Tick2h"
puts $fd "CPU_MHZ=100"
puts $fd "HBM_AXI_MHZ=225"
puts $fd "CPU_CLOCK=$cpu_clock"
puts $fd "WNS=[get_property SLACK [get_timing_paths -max_paths 1]]"
puts $fd "WHS=[get_property SLACK [get_timing_paths -delay_type min -max_paths 1]]"
puts $fd "DRC_ERROR_CRITICAL_COUNT=[llength $drc_errors]"
puts $fd "BITSTREAM=$bit_file"
close $fd
puts "BITSTREAM=$bit_file"
puts "BUILD_PART=$part"
exit
