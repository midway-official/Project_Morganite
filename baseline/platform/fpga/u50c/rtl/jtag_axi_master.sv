`include "axi4_if.svh"

module jtag_axi_master (
    input logic   clk,
    input logic   resetn,
    axi4_if.master m_axi
);
    jtag_axi_0 u_jtag_axi (
        .aclk          (clk),
        .aresetn       (resetn),
        .m_axi_awid    (m_axi.awid),
        .m_axi_awaddr  (m_axi.awaddr),
        .m_axi_awlen   (m_axi.awlen),
        .m_axi_awsize  (m_axi.awsize),
        .m_axi_awburst (m_axi.awburst),
        .m_axi_awlock  (m_axi.awlock),
        .m_axi_awcache (m_axi.awcache),
        .m_axi_awprot  (m_axi.awprot),
        .m_axi_awqos   (m_axi.awqos),
        .m_axi_awvalid (m_axi.awvalid),
        .m_axi_awready (m_axi.awready),
        .m_axi_wdata   (m_axi.wdata),
        .m_axi_wstrb   (m_axi.wstrb),
        .m_axi_wlast   (m_axi.wlast),
        .m_axi_wvalid  (m_axi.wvalid),
        .m_axi_wready  (m_axi.wready),
        .m_axi_bid     (m_axi.bid),
        .m_axi_bresp   (m_axi.bresp),
        .m_axi_bvalid  (m_axi.bvalid),
        .m_axi_bready  (m_axi.bready),
        .m_axi_arid    (m_axi.arid),
        .m_axi_araddr  (m_axi.araddr),
        .m_axi_arlen   (m_axi.arlen),
        .m_axi_arsize  (m_axi.arsize),
        .m_axi_arburst (m_axi.arburst),
        .m_axi_arlock  (m_axi.arlock),
        .m_axi_arcache (m_axi.arcache),
        .m_axi_arprot  (m_axi.arprot),
        .m_axi_arqos   (m_axi.arqos),
        .m_axi_arvalid (m_axi.arvalid),
        .m_axi_arready (m_axi.arready),
        .m_axi_rid     (m_axi.rid),
        .m_axi_rdata   (m_axi.rdata),
        .m_axi_rresp   (m_axi.rresp),
        .m_axi_rlast   (m_axi.rlast),
        .m_axi_rvalid  (m_axi.rvalid),
        .m_axi_rready  (m_axi.rready)
    );
endmodule
