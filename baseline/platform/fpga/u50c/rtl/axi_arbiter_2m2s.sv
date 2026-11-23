`include "axi4_if.svh"

// Two AXI4 masters (Aurora and JTAG) share one memory port and one peripheral
// port.  Reads and writes are independently serialized.  This intentionally
// small fabric is sufficient for first-silicon bring-up and preserves bursts.
module axi_arbiter_2m2s (
    input logic      clk,
    input logic      resetn,
    input logic      cpu_enable,
    output logic     cpu_idle,
    axi4_if.slave    cpu,
    axi4_if.slave    jtag,
    axi4_if.master   mem,
    axi4_if.master   periph
);
    logic rd_active, rd_owner, rd_target;
    logic wr_active, wr_owner, wr_target, wr_data_done;
    logic rd_rr, wr_rr;
    logic rd_sel_owner, rd_sel_target;
    logic wr_sel_owner, wr_sel_target;
    wire rd_cpu_request = cpu_enable && cpu.arvalid;
    wire wr_cpu_request = cpu_enable && cpu.awvalid;
    // Stop accepting new CPU addresses before resetting the core. Existing
    // transfers retain their owner and finish normally, including W/B beats.
    assign cpu_idle = (!rd_active || rd_owner) && (!wr_active || wr_owner);

    function automatic logic is_peripheral(input logic [31:0] address);
        // Keep the complete LS1A-style 0x1fe0_0000..0x1fef_ffff MMIO area
        // out of HBM.  Unimplemented devices return zero/OKAY in the small
        // peripheral block, which also covers the NAND probe used by Linux.
        is_peripheral = (address[31:20] == 12'h1fe);
    endfunction

    always_comb begin
        if (rd_cpu_request && jtag.arvalid)
            rd_sel_owner = rd_rr;
        else
            rd_sel_owner = jtag.arvalid;
        rd_sel_target = is_peripheral(rd_sel_owner ? jtag.araddr : cpu.araddr);

        if (wr_cpu_request && jtag.awvalid)
            wr_sel_owner = wr_rr;
        else
            wr_sel_owner = jtag.awvalid;
        wr_sel_target = is_peripheral(wr_sel_owner ? jtag.awaddr : cpu.awaddr);
    end

    always_ff @(posedge clk) begin
        if (!resetn) begin
            rd_active    <= 1'b0;
            rd_owner     <= 1'b0;
            rd_target    <= 1'b0;
            rd_rr        <= 1'b0;
            wr_active    <= 1'b0;
            wr_owner     <= 1'b0;
            wr_target    <= 1'b0;
            wr_data_done <= 1'b0;
            wr_rr        <= 1'b0;
        end else begin
            if (!rd_active) begin
                if ((!rd_sel_target && mem.arvalid && mem.arready) ||
                    ( rd_sel_target && periph.arvalid && periph.arready)) begin
                    rd_active <= 1'b1;
                    rd_owner  <= rd_sel_owner;
                    rd_target <= rd_sel_target;
                    rd_rr     <= ~rd_sel_owner;
                end
            end else if ((!rd_target && mem.rvalid && mem.rready && mem.rlast) ||
                         ( rd_target && periph.rvalid && periph.rready && periph.rlast)) begin
                rd_active <= 1'b0;
            end

            if (!wr_active) begin
                if ((!wr_sel_target && mem.awvalid && mem.awready) ||
                    ( wr_sel_target && periph.awvalid && periph.awready)) begin
                    wr_active    <= 1'b1;
                    wr_owner     <= wr_sel_owner;
                    wr_target    <= wr_sel_target;
                    wr_data_done <= 1'b0;
                    wr_rr        <= ~wr_sel_owner;
                end
            end else if (!wr_data_done) begin
                if ((!wr_target && mem.wvalid && mem.wready && mem.wlast) ||
                    ( wr_target && periph.wvalid && periph.wready && periph.wlast))
                    wr_data_done <= 1'b1;
            end else if ((!wr_target && mem.bvalid && mem.bready) ||
                         ( wr_target && periph.bvalid && periph.bready)) begin
                wr_active    <= 1'b0;
                wr_data_done <= 1'b0;
            end
        end
    end

    always_comb begin
        cpu.awready = 1'b0;
        cpu.wready  = 1'b0;
        cpu.bid     = '0;
        cpu.bresp   = 2'b00;
        cpu.bvalid  = 1'b0;
        cpu.arready = 1'b0;
        cpu.rid     = '0;
        cpu.rdata   = '0;
        cpu.rresp   = 2'b00;
        cpu.rlast   = 1'b0;
        cpu.rvalid  = 1'b0;

        jtag.awready = 1'b0;
        jtag.wready  = 1'b0;
        jtag.bid     = '0;
        jtag.bresp   = 2'b00;
        jtag.bvalid  = 1'b0;
        jtag.arready = 1'b0;
        jtag.rid     = '0;
        jtag.rdata   = '0;
        jtag.rresp   = 2'b00;
        jtag.rlast   = 1'b0;
        jtag.rvalid  = 1'b0;

        mem.awid = '0; mem.awaddr = '0; mem.awlen = '0; mem.awsize = '0;
        mem.awburst = '0; mem.awlock = 1'b0; mem.awcache = '0;
        mem.awprot = '0; mem.awqos = '0; mem.awvalid = 1'b0;
        mem.wdata = '0; mem.wstrb = '0; mem.wlast = 1'b0; mem.wvalid = 1'b0;
        mem.bready = 1'b0;
        mem.arid = '0; mem.araddr = '0; mem.arlen = '0; mem.arsize = '0;
        mem.arburst = '0; mem.arlock = 1'b0; mem.arcache = '0;
        mem.arprot = '0; mem.arqos = '0; mem.arvalid = 1'b0;
        mem.rready = 1'b0;

        periph.awid = '0; periph.awaddr = '0; periph.awlen = '0;
        periph.awsize = '0; periph.awburst = '0; periph.awlock = 1'b0;
        periph.awcache = '0; periph.awprot = '0; periph.awqos = '0;
        periph.awvalid = 1'b0; periph.wdata = '0; periph.wstrb = '0;
        periph.wlast = 1'b0; periph.wvalid = 1'b0; periph.bready = 1'b0;
        periph.arid = '0; periph.araddr = '0; periph.arlen = '0;
        periph.arsize = '0; periph.arburst = '0; periph.arlock = 1'b0;
        periph.arcache = '0; periph.arprot = '0; periph.arqos = '0;
        periph.arvalid = 1'b0; periph.rready = 1'b0;

        if (!rd_active) begin
            if (rd_sel_owner ? jtag.arvalid : rd_cpu_request) begin
                if (rd_sel_target) begin
                    periph.arid    = rd_sel_owner ? jtag.arid    : cpu.arid;
                    periph.araddr  = rd_sel_owner ? jtag.araddr  : cpu.araddr;
                    periph.arlen   = rd_sel_owner ? jtag.arlen   : cpu.arlen;
                    periph.arsize  = rd_sel_owner ? jtag.arsize  : cpu.arsize;
                    periph.arburst = rd_sel_owner ? jtag.arburst : cpu.arburst;
                    periph.arlock  = rd_sel_owner ? jtag.arlock  : cpu.arlock;
                    periph.arcache = rd_sel_owner ? jtag.arcache : cpu.arcache;
                    periph.arprot  = rd_sel_owner ? jtag.arprot  : cpu.arprot;
                    periph.arqos   = rd_sel_owner ? jtag.arqos   : cpu.arqos;
                    periph.arvalid = 1'b1;
                    if (rd_sel_owner) jtag.arready = periph.arready;
                    else              cpu.arready  = periph.arready;
                end else begin
                    mem.arid    = rd_sel_owner ? jtag.arid    : cpu.arid;
                    mem.araddr  = rd_sel_owner ? jtag.araddr  : cpu.araddr;
                    mem.arlen   = rd_sel_owner ? jtag.arlen   : cpu.arlen;
                    mem.arsize  = rd_sel_owner ? jtag.arsize  : cpu.arsize;
                    mem.arburst = rd_sel_owner ? jtag.arburst : cpu.arburst;
                    mem.arlock  = rd_sel_owner ? jtag.arlock  : cpu.arlock;
                    mem.arcache = rd_sel_owner ? jtag.arcache : cpu.arcache;
                    mem.arprot  = rd_sel_owner ? jtag.arprot  : cpu.arprot;
                    mem.arqos   = rd_sel_owner ? jtag.arqos   : cpu.arqos;
                    mem.arvalid = 1'b1;
                    if (rd_sel_owner) jtag.arready = mem.arready;
                    else              cpu.arready  = mem.arready;
                end
            end
        end else if (rd_target) begin
            if (rd_owner) begin
                jtag.rid = periph.rid; jtag.rdata = periph.rdata;
                jtag.rresp = periph.rresp; jtag.rlast = periph.rlast;
                jtag.rvalid = periph.rvalid; periph.rready = jtag.rready;
            end else begin
                cpu.rid = periph.rid; cpu.rdata = periph.rdata;
                cpu.rresp = periph.rresp; cpu.rlast = periph.rlast;
                cpu.rvalid = periph.rvalid; periph.rready = cpu.rready;
            end
        end else begin
            if (rd_owner) begin
                jtag.rid = mem.rid; jtag.rdata = mem.rdata;
                jtag.rresp = mem.rresp; jtag.rlast = mem.rlast;
                jtag.rvalid = mem.rvalid; mem.rready = jtag.rready;
            end else begin
                cpu.rid = mem.rid; cpu.rdata = mem.rdata;
                cpu.rresp = mem.rresp; cpu.rlast = mem.rlast;
                cpu.rvalid = mem.rvalid; mem.rready = cpu.rready;
            end
        end

        if (!wr_active) begin
            if (wr_sel_owner ? jtag.awvalid : wr_cpu_request) begin
                if (wr_sel_target) begin
                    periph.awid    = wr_sel_owner ? jtag.awid    : cpu.awid;
                    periph.awaddr  = wr_sel_owner ? jtag.awaddr  : cpu.awaddr;
                    periph.awlen   = wr_sel_owner ? jtag.awlen   : cpu.awlen;
                    periph.awsize  = wr_sel_owner ? jtag.awsize  : cpu.awsize;
                    periph.awburst = wr_sel_owner ? jtag.awburst : cpu.awburst;
                    periph.awlock  = wr_sel_owner ? jtag.awlock  : cpu.awlock;
                    periph.awcache = wr_sel_owner ? jtag.awcache : cpu.awcache;
                    periph.awprot  = wr_sel_owner ? jtag.awprot  : cpu.awprot;
                    periph.awqos   = wr_sel_owner ? jtag.awqos   : cpu.awqos;
                    periph.awvalid = 1'b1;
                    if (wr_sel_owner) jtag.awready = periph.awready;
                    else              cpu.awready  = periph.awready;
                end else begin
                    mem.awid    = wr_sel_owner ? jtag.awid    : cpu.awid;
                    mem.awaddr  = wr_sel_owner ? jtag.awaddr  : cpu.awaddr;
                    mem.awlen   = wr_sel_owner ? jtag.awlen   : cpu.awlen;
                    mem.awsize  = wr_sel_owner ? jtag.awsize  : cpu.awsize;
                    mem.awburst = wr_sel_owner ? jtag.awburst : cpu.awburst;
                    mem.awlock  = wr_sel_owner ? jtag.awlock  : cpu.awlock;
                    mem.awcache = wr_sel_owner ? jtag.awcache : cpu.awcache;
                    mem.awprot  = wr_sel_owner ? jtag.awprot  : cpu.awprot;
                    mem.awqos   = wr_sel_owner ? jtag.awqos   : cpu.awqos;
                    mem.awvalid = 1'b1;
                    if (wr_sel_owner) jtag.awready = mem.awready;
                    else              cpu.awready  = mem.awready;
                end
            end
        end else if (!wr_data_done) begin
            if (wr_target) begin
                periph.wdata  = wr_owner ? jtag.wdata  : cpu.wdata;
                periph.wstrb  = wr_owner ? jtag.wstrb  : cpu.wstrb;
                periph.wlast  = wr_owner ? jtag.wlast  : cpu.wlast;
                periph.wvalid = wr_owner ? jtag.wvalid : cpu.wvalid;
                if (wr_owner) jtag.wready = periph.wready;
                else          cpu.wready  = periph.wready;
            end else begin
                mem.wdata  = wr_owner ? jtag.wdata  : cpu.wdata;
                mem.wstrb  = wr_owner ? jtag.wstrb  : cpu.wstrb;
                mem.wlast  = wr_owner ? jtag.wlast  : cpu.wlast;
                mem.wvalid = wr_owner ? jtag.wvalid : cpu.wvalid;
                if (wr_owner) jtag.wready = mem.wready;
                else          cpu.wready  = mem.wready;
            end
        end else if (wr_target) begin
            if (wr_owner) begin
                jtag.bid = periph.bid; jtag.bresp = periph.bresp;
                jtag.bvalid = periph.bvalid; periph.bready = jtag.bready;
            end else begin
                cpu.bid = periph.bid; cpu.bresp = periph.bresp;
                cpu.bvalid = periph.bvalid; periph.bready = cpu.bready;
            end
        end else begin
            if (wr_owner) begin
                jtag.bid = mem.bid; jtag.bresp = mem.bresp;
                jtag.bvalid = mem.bvalid; mem.bready = jtag.bready;
            end else begin
                cpu.bid = mem.bid; cpu.bresp = mem.bresp;
                cpu.bvalid = mem.bvalid; mem.bready = cpu.bready;
            end
        end
    end
endmodule
