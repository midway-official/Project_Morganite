# Single-beat INCR from the GPU. Pass IDs through both IP blocks.
create_ip -name axi_clock_converter -vendor xilinx.com -library ip -module_name gpu_axi_cdc
set_property -dict [list CONFIG.ADDR_WIDTH {32} CONFIG.DATA_WIDTH {256} CONFIG.ID_WIDTH {4} \
    CONFIG.PROTOCOL {AXI4} CONFIG.ACLK_ASYNC {1} CONFIG.SYNCHRONIZATION_STAGES {3}] [get_ips gpu_axi_cdc]
create_ip -name axi_protocol_converter -vendor xilinx.com -library ip -module_name gpu_axi_protocol
set_property -dict [list CONFIG.ADDR_WIDTH {32} CONFIG.DATA_WIDTH {256} CONFIG.ID_WIDTH {4} \
    CONFIG.SI_PROTOCOL {AXI4} CONFIG.MI_PROTOCOL {AXI3} CONFIG.TRANSLATION_MODE {0}] [get_ips gpu_axi_protocol]
