// Simulation only: strict AXI3 HBM port, not an HBM PHY/controller model.
`timescale 1ns/1ps
module hbm_0(
    input logic HBM_REF_CLK_0,
    input logic AXI_00_ACLK,
    input logic AXI_00_ARESET_N,
    input logic [32 : 0] AXI_00_ARADDR,
    input logic [1 : 0] AXI_00_ARBURST,
    input logic [5 : 0] AXI_00_ARID,
    input logic [3 : 0] AXI_00_ARLEN,
    input logic [2 : 0] AXI_00_ARSIZE,
    input logic AXI_00_ARVALID,
    input logic [32 : 0] AXI_00_AWADDR,
    input logic [1 : 0] AXI_00_AWBURST,
    input logic [5 : 0] AXI_00_AWID,
    input logic [3 : 0] AXI_00_AWLEN,
    input logic [2 : 0] AXI_00_AWSIZE,
    input logic AXI_00_AWVALID,
    input logic AXI_00_RREADY,
    input logic AXI_00_BREADY,
    input logic [255 : 0] AXI_00_WDATA,
    input logic AXI_00_WLAST,
    input logic [31 : 0] AXI_00_WSTRB,
    input logic [31 : 0] AXI_00_WDATA_PARITY,
    input logic AXI_00_WVALID,
    input logic [31 : 0] APB_0_PWDATA,
    input logic [21 : 0] APB_0_PADDR,
    input logic APB_0_PCLK,
    input logic APB_0_PENABLE,
    input logic APB_0_PRESET_N,
    input logic APB_0_PSEL,
    input logic APB_0_PWRITE,
    output logic AXI_00_ARREADY,
    output logic AXI_00_AWREADY,
    output logic [31 : 0] AXI_00_RDATA_PARITY,
    output logic [255 : 0] AXI_00_RDATA,
    output logic [5 : 0] AXI_00_RID,
    output logic AXI_00_RLAST,
    output logic [1 : 0] AXI_00_RRESP,
    output logic AXI_00_RVALID,
    output logic AXI_00_WREADY,
    output logic [5 : 0] AXI_00_BID,
    output logic [1 : 0] AXI_00_BRESP,
    output logic AXI_00_BVALID,
    output logic [31 : 0] APB_0_PRDATA,
    output logic APB_0_PREADY,
    output logic APB_0_PSLVERR,
    output logic apb_complete_0,
    output logic DRAM_0_STAT_CATTRIP,
    output logic [6 : 0] DRAM_0_STAT_TEMP
);
    logic [7:0] memory_bytes [longint unsigned];
    logic [32:0] write_addr, read_addr;
    logic [3:0] write_len, write_beat, read_len, read_beat;
    logic [5:0] write_id, read_id;
    logic write_active, read_active;
    logic [1:0] write_burst, read_burst;
    function automatic [32:0] next_addr(input logic [32:0] address,
                                       input logic [3:0] len,input logic [1:0] burst);
        logic [32:0] mask;
        mask = (33'(len)+1)*32-1;
        return burst==2 ? ((address & ~mask) | ((address+32) & mask)) : address+32;
    endfunction
    int unsigned cycles = 0, apb_cycles = 0;
    assign APB_0_PRDATA = 0;
    assign APB_0_PREADY = 1;
    assign APB_0_PSLVERR = 0;
    assign DRAM_0_STAT_CATTRIP = 0;
    assign DRAM_0_STAT_TEMP = 35;
    assign AXI_00_RDATA_PARITY = 0;
    assign AXI_00_BRESP = 0;
    assign AXI_00_RRESP = 0;
    assign AXI_00_AWREADY = AXI_00_ARESET_N && !write_active && !AXI_00_BVALID && cycles % 5 != 0;
    assign AXI_00_WREADY = AXI_00_ARESET_N && write_active && cycles % 3 != 0;
    assign AXI_00_ARREADY = AXI_00_ARESET_N && !read_active && cycles % 7 != 0;
    always @(posedge APB_0_PCLK) begin
        if (!APB_0_PRESET_N) begin apb_cycles <= 0; apb_complete_0 <= 0; end
        else begin
            apb_cycles <= apb_cycles + 1;
            if (apb_cycles > 20) apb_complete_0 <= 1;
        end
    end
    always @(posedge AXI_00_ACLK) begin
        if (!AXI_00_ARESET_N) begin
            cycles <= 0; write_active <= 0; read_active <= 0;
            AXI_00_BVALID <= 0; AXI_00_RVALID <= 0;
            AXI_00_RDATA <= 0; AXI_00_RLAST <= 0;
            AXI_00_RID <= 0; AXI_00_BID <= 0;
        end else begin
            cycles <= cycles + 1;
            if (AXI_00_AWVALID && AXI_00_AWREADY) begin
                assert(AXI_00_AWSIZE == 5 && AXI_00_AWADDR[4:0] == 0 && AXI_00_AWBURST inside {1,2})
                    else $fatal(1, "Illegal HBM write addr=%x size=%d len=%d burst=%d", AXI_00_AWADDR, AXI_00_AWSIZE, AXI_00_AWLEN, AXI_00_AWBURST);
                write_addr <= AXI_00_AWADDR; write_len <= AXI_00_AWLEN;
                write_id <= AXI_00_AWID; write_beat <= 0; write_active <= 1;
                write_burst <= AXI_00_AWBURST;
            end
            if (AXI_00_WVALID && AXI_00_WREADY) begin
                assert(AXI_00_WLAST == (write_beat == write_len)) else $fatal(1, "HBM WLAST mismatch");
                for (int lane = 0; lane < 32; lane++)
                    if (AXI_00_WSTRB[lane]) memory_bytes[longint'(write_addr) + lane] = AXI_00_WDATA[lane*8 +: 8];
                write_addr <= next_addr(write_addr,write_len,write_burst);
                write_beat <= write_beat + 1;
                if (AXI_00_WLAST) begin
                    write_active <= 0; AXI_00_BVALID <= 1; AXI_00_BID <= write_id;
                end
            end
            if (AXI_00_BVALID && AXI_00_BREADY) AXI_00_BVALID <= 0;
            if (AXI_00_ARVALID && AXI_00_ARREADY) begin
                assert(AXI_00_ARSIZE == 5 && AXI_00_ARADDR[4:0] == 0 && AXI_00_ARBURST inside {1,2})
                    else $fatal(1, "Illegal HBM read addr=%x size=%d len=%d burst=%d", AXI_00_ARADDR, AXI_00_ARSIZE, AXI_00_ARLEN, AXI_00_ARBURST);
                read_addr <= AXI_00_ARADDR; read_len <= AXI_00_ARLEN;
                read_id <= AXI_00_ARID; read_beat <= 0; read_active <= 1;
                read_burst <= AXI_00_ARBURST;
            end
            if (read_active && !AXI_00_RVALID && cycles % 4 != 0) begin
                for (int lane = 0; lane < 32; lane++)
                    AXI_00_RDATA[lane*8 +: 8] <= memory_bytes.exists(longint'(read_addr) + lane)
                        ? memory_bytes[longint'(read_addr) + lane] : 0;
                AXI_00_RVALID <= 1; AXI_00_RLAST <= read_beat == read_len; AXI_00_RID <= read_id;
            end
            if (AXI_00_RVALID && AXI_00_RREADY) begin
                AXI_00_RVALID <= 0;
                read_addr <= next_addr(read_addr,read_len,read_burst); read_beat <= read_beat + 1;
                if (AXI_00_RLAST) read_active <= 0;
            end
        end
    end
endmodule
