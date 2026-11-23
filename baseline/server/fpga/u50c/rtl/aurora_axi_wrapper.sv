`include "axi4_if.svh"

module aurora_axi_wrapper (
    input  logic       clk,
    input  logic       reset,
    input  logic [7:0] ext_int,
    axi4_if.master     m_axi
);
    Aurora u_aurora (
        .clk      (clk),
        .rst      (reset),
        .ext_int  (ext_int),
        .ipi_int  (1'b0),

        .awid     (m_axi.awid),
        .awaddr   (m_axi.awaddr),
        .awlen    (m_axi.awlen),
        .awsize   (m_axi.awsize),
        .awburst  (m_axi.awburst),
        .awlock   (m_axi.awlock),
        .awcache  (m_axi.awcache),
        .awprot   (m_axi.awprot),
        .awqos    (m_axi.awqos),
        .awvalid  (m_axi.awvalid),
        .awready  (m_axi.awready),
        .wdata    (m_axi.wdata),
        .wstrb    (m_axi.wstrb),
        .wlast    (m_axi.wlast),
        .wvalid   (m_axi.wvalid),
        .wready   (m_axi.wready),
        .bid      (m_axi.bid),
        .bresp    (m_axi.bresp),
        .bvalid   (m_axi.bvalid),
        .bready   (m_axi.bready),
        .arid     (m_axi.arid),
        .araddr   (m_axi.araddr),
        .arlen    (m_axi.arlen),
        .arsize   (m_axi.arsize),
        .arburst  (m_axi.arburst),
        .arlock   (m_axi.arlock),
        .arcache  (m_axi.arcache),
        .arprot   (m_axi.arprot),
        .arqos    (m_axi.arqos),
        .arvalid  (m_axi.arvalid),
        .arready  (m_axi.arready),
        .rid      (m_axi.rid),
        .rdata    (m_axi.rdata),
        .rresp    (m_axi.rresp),
        .rlast    (m_axi.rlast),
        .rvalid   (m_axi.rvalid),
        .rready   (m_axi.rready)
    );
endmodule
