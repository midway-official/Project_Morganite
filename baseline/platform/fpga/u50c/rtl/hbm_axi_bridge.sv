`include "axi4_if.svh"

module hbm_axi_bridge (
    input  logic       clk_cpu,
    input  logic       resetn_cpu,
    input  logic       clk225,
    input  logic       resetn225,
    input  logic       clk100,
    input  logic       resetn100,
    output logic       hbm_ready,
    output logic       hbm_cattrip,
    output logic [6:0] hbm_temp,
    input  logic       axi_debug_clear,
    output logic [15:0] axi_debug,
    axi4_if.slave      s_axi
);
    logic [31:0] dw_awaddr, dw_araddr;
    logic [7:0]  dw_awlen, dw_arlen;
    logic [2:0]  dw_awsize, dw_arsize;
    logic [1:0]  dw_awburst, dw_arburst;
    logic        dw_awlock, dw_arlock;
    logic [3:0]  dw_awcache, dw_arcache, dw_awregion, dw_arregion;
    logic [2:0]  dw_awprot, dw_arprot;
    logic [3:0]  dw_awqos, dw_arqos;
    logic        dw_awvalid, dw_awready;
    logic [255:0] dw_wdata;
    logic [31:0]  dw_wstrb;
    logic         dw_wlast, dw_wvalid, dw_wready;
    logic [1:0]   dw_bresp;
    logic         dw_bvalid, dw_bready;
    logic         dw_arvalid, dw_arready;
    logic [255:0] dw_rdata;
    logic [1:0]   dw_rresp;
    logic         dw_rlast, dw_rvalid, dw_rready;

    logic [31:0] hb_awaddr, hb_araddr;
    logic [7:0]  hb_awlen, hb_arlen;
    logic [2:0]  hb_awsize, hb_arsize;
    logic [1:0]  hb_awburst, hb_arburst;
    logic        hb_awlock, hb_arlock;
    logic [3:0]  hb_awcache, hb_arcache, hb_awregion, hb_arregion;
    logic [2:0]  hb_awprot, hb_arprot;
    logic [3:0]  hb_awqos, hb_arqos;
    logic        hb_awvalid, hb_awready;
    logic [255:0] hb_wdata;
    logic [31:0]  hb_wstrb;
    logic         hb_wlast, hb_wvalid, hb_wready;
    logic [1:0]   hb_bresp;
    logic         hb_bvalid, hb_bready;
    logic         hb_arvalid, hb_arready;
    logic [255:0] hb_rdata;
    logic [1:0]   hb_rresp;
    logic         hb_rlast, hb_rvalid, hb_rready;
    logic [5:0]   hbm_bid, hbm_rid;
    logic [31:0]  apb_rdata;
    logic         apb_ready, apb_slverr, apb_complete;
    logic [31:0]  hbm_rparity;
    logic [13:0]  axi_seen;
    logic [31:0] pc_awaddr, pc_araddr;
    logic [3:0] pc_awlen, pc_arlen;
    logic [2:0] pc_awsize, pc_arsize;
    logic [1:0] pc_awburst, pc_arburst;
    logic pc_awvalid, pc_awready, pc_arvalid, pc_arready;
    logic [255:0] pc_wdata, pc_rdata;
    logic [31:0] pc_wstrb;
    logic pc_wlast, pc_wvalid, pc_wready;
    logic pc_bvalid, pc_bready, pc_rlast, pc_rvalid, pc_rready;
    logic [1:0] pc_bresp, pc_rresp;

    // Sticky hardware evidence for each stage of a transaction.  The VIO can
    // be read after a JTAG transaction times out, without needing an ILA
    // trigger to be armed at exactly the right cycle.
    always_ff @(posedge clk225) begin
        if (!resetn225 || axi_debug_clear) begin
            axi_seen <= 14'b0;
        end else begin
            axi_seen <= axi_seen | {
                hb_rvalid && hb_rready,
                hb_rvalid,
                hb_arvalid && hb_arready,
                hb_arready,
                hb_arvalid,
                hb_bvalid && hb_bready,
                hb_bready,
                hb_bvalid,
                hb_wvalid && hb_wready,
                hb_wready,
                hb_wvalid,
                hb_awvalid && hb_awready,
                hb_awready,
                hb_awvalid
            };
        end
    end

    assign axi_debug = {axi_seen, apb_complete, resetn225};

    axi_dwidth_converter_0 u_width_converter (
        .s_axi_aclk     (clk_cpu),
        .s_axi_aresetn  (resetn_cpu),
        .s_axi_awid     (s_axi.awid),
        .s_axi_awaddr   (s_axi.awaddr),
        .s_axi_awlen    (s_axi.awlen),
        .s_axi_awsize   (s_axi.awsize),
        .s_axi_awburst  (s_axi.awburst),
        .s_axi_awlock   (s_axi.awlock),
        // PACKING_LEVEL=Always handles single-byte/halfword accesses too.
        // This port only serves HBM RAM; MMIO is decoded before the upsizer.
        .s_axi_awcache  (s_axi.awcache),
        .s_axi_awprot   (s_axi.awprot),
        .s_axi_awregion (4'b0),
        .s_axi_awqos    (s_axi.awqos),
        .s_axi_awvalid  (s_axi.awvalid),
        .s_axi_awready  (s_axi.awready),
        .s_axi_wdata    (s_axi.wdata),
        .s_axi_wstrb    (s_axi.wstrb),
        .s_axi_wlast    (s_axi.wlast),
        .s_axi_wvalid   (s_axi.wvalid),
        .s_axi_wready   (s_axi.wready),
        .s_axi_bid      (s_axi.bid),
        .s_axi_bresp    (s_axi.bresp),
        .s_axi_bvalid   (s_axi.bvalid),
        .s_axi_bready   (s_axi.bready),
        .s_axi_arid     (s_axi.arid),
        .s_axi_araddr   (s_axi.araddr),
        .s_axi_arlen    (s_axi.arlen),
        .s_axi_arsize   (s_axi.arsize),
        .s_axi_arburst  (s_axi.arburst),
        .s_axi_arlock   (s_axi.arlock),
        .s_axi_arcache  (s_axi.arcache),
        .s_axi_arprot   (s_axi.arprot),
        .s_axi_arregion (4'b0),
        .s_axi_arqos    (s_axi.arqos),
        .s_axi_arvalid  (s_axi.arvalid),
        .s_axi_arready  (s_axi.arready),
        .s_axi_rid      (s_axi.rid),
        .s_axi_rdata    (s_axi.rdata),
        .s_axi_rresp    (s_axi.rresp),
        .s_axi_rlast    (s_axi.rlast),
        .s_axi_rvalid   (s_axi.rvalid),
        .s_axi_rready   (s_axi.rready),
        .m_axi_awaddr   (dw_awaddr),
        .m_axi_awlen    (dw_awlen),
        .m_axi_awsize   (dw_awsize),
        .m_axi_awburst  (dw_awburst),
        .m_axi_awlock   (dw_awlock),
        .m_axi_awcache  (dw_awcache),
        .m_axi_awprot   (dw_awprot),
        .m_axi_awregion (dw_awregion),
        .m_axi_awqos    (dw_awqos),
        .m_axi_awvalid  (dw_awvalid),
        .m_axi_awready  (dw_awready),
        .m_axi_wdata    (dw_wdata),
        .m_axi_wstrb    (dw_wstrb),
        .m_axi_wlast    (dw_wlast),
        .m_axi_wvalid   (dw_wvalid),
        .m_axi_wready   (dw_wready),
        .m_axi_bresp    (dw_bresp),
        .m_axi_bvalid   (dw_bvalid),
        .m_axi_bready   (dw_bready),
        .m_axi_araddr   (dw_araddr),
        .m_axi_arlen    (dw_arlen),
        .m_axi_arsize   (dw_arsize),
        .m_axi_arburst  (dw_arburst),
        .m_axi_arlock   (dw_arlock),
        .m_axi_arcache  (dw_arcache),
        .m_axi_arprot   (dw_arprot),
        .m_axi_arregion (dw_arregion),
        .m_axi_arqos    (dw_arqos),
        .m_axi_arvalid  (dw_arvalid),
        .m_axi_arready  (dw_arready),
        .m_axi_rdata    (dw_rdata),
        .m_axi_rresp    (dw_rresp),
        .m_axi_rlast    (dw_rlast),
        .m_axi_rvalid   (dw_rvalid),
        .m_axi_rready   (dw_rready)
    );

    // The width converter retains the 4-bit source ID internally, so its wide
    // side is ID-less.  The clock converter is configured the same way.
    axi_clock_converter_0 u_clock_converter (
        .s_axi_aclk     (clk_cpu),
        .s_axi_aresetn  (resetn_cpu),
        .s_axi_awaddr   (dw_awaddr), .s_axi_awlen(dw_awlen),
        .s_axi_awsize   (dw_awsize), .s_axi_awburst(dw_awburst),
        .s_axi_awlock   (dw_awlock), .s_axi_awcache(dw_awcache),
        .s_axi_awprot   (dw_awprot), .s_axi_awregion(dw_awregion),
        .s_axi_awqos    (dw_awqos), .s_axi_awvalid(dw_awvalid),
        .s_axi_awready  (dw_awready),
        .s_axi_wdata    (dw_wdata), .s_axi_wstrb(dw_wstrb),
        .s_axi_wlast    (dw_wlast), .s_axi_wvalid(dw_wvalid),
        .s_axi_wready   (dw_wready),
        .s_axi_bresp    (dw_bresp), .s_axi_bvalid(dw_bvalid),
        .s_axi_bready   (dw_bready),
        .s_axi_araddr   (dw_araddr), .s_axi_arlen(dw_arlen),
        .s_axi_arsize   (dw_arsize), .s_axi_arburst(dw_arburst),
        .s_axi_arlock   (dw_arlock), .s_axi_arcache(dw_arcache),
        .s_axi_arprot   (dw_arprot), .s_axi_arregion(dw_arregion),
        .s_axi_arqos    (dw_arqos), .s_axi_arvalid(dw_arvalid),
        .s_axi_arready  (dw_arready),
        .s_axi_rdata    (dw_rdata), .s_axi_rresp(dw_rresp),
        .s_axi_rlast    (dw_rlast), .s_axi_rvalid(dw_rvalid),
        .s_axi_rready   (dw_rready),
        .m_axi_aclk     (clk225),
        .m_axi_aresetn  (resetn225),
        .m_axi_awaddr   (hb_awaddr), .m_axi_awlen(hb_awlen),
        .m_axi_awsize   (hb_awsize), .m_axi_awburst(hb_awburst),
        .m_axi_awlock   (hb_awlock), .m_axi_awcache(hb_awcache),
        .m_axi_awprot   (hb_awprot), .m_axi_awregion(hb_awregion),
        .m_axi_awqos    (hb_awqos), .m_axi_awvalid(hb_awvalid),
        .m_axi_awready  (hb_awready),
        .m_axi_wdata    (hb_wdata), .m_axi_wstrb(hb_wstrb),
        .m_axi_wlast    (hb_wlast), .m_axi_wvalid(hb_wvalid),
        .m_axi_wready   (hb_wready),
        .m_axi_bresp    (hb_bresp), .m_axi_bvalid(hb_bvalid),
        .m_axi_bready   (hb_bready),
        .m_axi_araddr   (hb_araddr), .m_axi_arlen(hb_arlen),
        .m_axi_arsize   (hb_arsize), .m_axi_arburst(hb_arburst),
        .m_axi_arlock   (hb_arlock), .m_axi_arcache(hb_arcache),
        .m_axi_arprot   (hb_arprot), .m_axi_arregion(hb_arregion),
        .m_axi_arqos    (hb_arqos), .m_axi_arvalid(hb_arvalid),
        .m_axi_arready  (hb_arready),
        .m_axi_rdata    (hb_rdata), .m_axi_rresp(hb_rresp),
        .m_axi_rlast    (hb_rlast), .m_axi_rvalid(hb_rvalid),
        .m_axi_rready   (hb_rready)
    );

    axi_protocol_converter_0 u_axi3_converter (
        .aclk(clk225), .aresetn(resetn225),
        .s_axi_awaddr(hb_awaddr), .s_axi_awlen(hb_awlen),
        .s_axi_awsize(hb_awsize), .s_axi_awburst(hb_awburst),
        .s_axi_awlock(hb_awlock), .s_axi_awcache(hb_awcache),
        .s_axi_awprot(hb_awprot), .s_axi_awregion(hb_awregion),
        .s_axi_awqos(hb_awqos), .s_axi_awvalid(hb_awvalid), .s_axi_awready(hb_awready),
        .s_axi_wdata(hb_wdata), .s_axi_wstrb(hb_wstrb),
        .s_axi_wlast(hb_wlast), .s_axi_wvalid(hb_wvalid), .s_axi_wready(hb_wready),
        .s_axi_bresp(hb_bresp), .s_axi_bvalid(hb_bvalid), .s_axi_bready(hb_bready),
        .s_axi_araddr(hb_araddr), .s_axi_arlen(hb_arlen),
        .s_axi_arsize(hb_arsize), .s_axi_arburst(hb_arburst),
        .s_axi_arlock(hb_arlock), .s_axi_arcache(hb_arcache),
        .s_axi_arprot(hb_arprot), .s_axi_arregion(hb_arregion),
        .s_axi_arqos(hb_arqos), .s_axi_arvalid(hb_arvalid), .s_axi_arready(hb_arready),
        .s_axi_rdata(hb_rdata), .s_axi_rresp(hb_rresp),
        .s_axi_rlast(hb_rlast), .s_axi_rvalid(hb_rvalid), .s_axi_rready(hb_rready),
        .m_axi_awaddr(pc_awaddr), .m_axi_awlen(pc_awlen),
        .m_axi_awsize(pc_awsize), .m_axi_awburst(pc_awburst),
        .m_axi_awvalid(pc_awvalid), .m_axi_awready(pc_awready),
        .m_axi_wdata(pc_wdata), .m_axi_wstrb(pc_wstrb),
        .m_axi_wlast(pc_wlast), .m_axi_wvalid(pc_wvalid), .m_axi_wready(pc_wready),
        .m_axi_bresp(pc_bresp), .m_axi_bvalid(pc_bvalid), .m_axi_bready(pc_bready),
        .m_axi_araddr(pc_araddr), .m_axi_arlen(pc_arlen),
        .m_axi_arsize(pc_arsize), .m_axi_arburst(pc_arburst),
        .m_axi_arvalid(pc_arvalid), .m_axi_arready(pc_arready),
        .m_axi_rdata(pc_rdata), .m_axi_rresp(pc_rresp),
        .m_axi_rlast(pc_rlast), .m_axi_rvalid(pc_rvalid), .m_axi_rready(pc_rready)
    );

    // The upsizer keeps the original byte offset for selecting return lanes.
    // HBM itself requires 32-byte-aligned addresses and full-width AxSIZE=5.
    hbm_0 u_hbm (
        .HBM_REF_CLK_0        (clk100),
        .AXI_00_ACLK          (clk225),
        .AXI_00_ARESET_N      (resetn225),
        .AXI_00_ARADDR        ({1'b0, pc_araddr[31:5], 5'b0}),
        .AXI_00_ARBURST       (pc_arburst),
        .AXI_00_ARID          (6'b0),
        .AXI_00_ARLEN         (pc_arlen),
        .AXI_00_ARSIZE        (pc_arsize),
        .AXI_00_ARVALID       (pc_arvalid),
        .AXI_00_ARREADY       (pc_arready),
        .AXI_00_RDATA_PARITY  (hbm_rparity),
        .AXI_00_RDATA         (pc_rdata),
        .AXI_00_RID           (hbm_rid),
        .AXI_00_RLAST         (pc_rlast),
        .AXI_00_RRESP         (pc_rresp),
        .AXI_00_RVALID        (pc_rvalid),
        .AXI_00_RREADY        (pc_rready),
        .AXI_00_AWADDR        ({1'b0, pc_awaddr[31:5], 5'b0}),
        .AXI_00_AWBURST       (pc_awburst),
        .AXI_00_AWID          (6'b0),
        .AXI_00_AWLEN         (pc_awlen),
        .AXI_00_AWSIZE        (pc_awsize),
        .AXI_00_AWVALID       (pc_awvalid),
        .AXI_00_AWREADY       (pc_awready),
        .AXI_00_WDATA         (pc_wdata),
        .AXI_00_WLAST         (pc_wlast),
        .AXI_00_WSTRB         (pc_wstrb),
        .AXI_00_WDATA_PARITY  (32'b0),
        .AXI_00_WVALID        (pc_wvalid),
        .AXI_00_WREADY        (pc_wready),
        .AXI_00_BID           (hbm_bid),
        .AXI_00_BRESP         (pc_bresp),
        .AXI_00_BVALID        (pc_bvalid),
        .AXI_00_BREADY        (pc_bready),
        .APB_0_PWDATA         (32'b0),
        .APB_0_PADDR          (22'b0),
        .APB_0_PCLK           (clk100),
        .APB_0_PENABLE        (1'b0),
        .APB_0_PRESET_N       (resetn100),
        .APB_0_PSEL           (1'b0),
        .APB_0_PWRITE         (1'b0),
        .APB_0_PRDATA         (apb_rdata),
        .APB_0_PREADY         (apb_ready),
        .APB_0_PSLVERR        (apb_slverr),
        .apb_complete_0       (apb_complete),
        .DRAM_0_STAT_CATTRIP  (hbm_cattrip),
        .DRAM_0_STAT_TEMP     (hbm_temp)
    );

    assign hbm_ready = apb_complete;
endmodule
