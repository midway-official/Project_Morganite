`timescale 1ns/1ps
`include "axi4_if.svh"

module dual_hbm_tb;
    logic clk=0, hclk=0, rst=0;
    wire ready;
    always #5 clk=~clk;
    always #2.222 hclk=~hclk;
    axi4_if host();
    axi4_if #(.DATA_WIDTH(256)) gpu0(), gpu1();
    hbm_axi_bridge dut(.clk_cpu(clk), .clk225(hclk), .clk100(clk),
        .resetn_cpu(rst), .resetn225(rst), .resetn100(rst),
        .hbm_ready(ready), .axi_debug_clear(1'b0), .s_axi(host), .gpu0, .gpu1);

    // Run exactly the same producer/consumer sequence on even and odd pages.
    `define PORT_TASKS(NAME, BUS, SIZE) \
    task automatic NAME``_init(); \
        {BUS.awvalid,BUS.wvalid,BUS.arvalid,BUS.rready,BUS.bready}=0; \
        {BUS.awlen,BUS.arlen,BUS.awlock,BUS.arlock,BUS.awqos,BUS.arqos,BUS.awprot,BUS.arprot}=0; \
        BUS.awcache=3; BUS.arcache=3; BUS.awsize=SIZE; BUS.arsize=SIZE; \
        BUS.awburst=1; BUS.arburst=1; BUS.wlast=1; BUS.arid=3; BUS.awid=4; \
    endtask \
    task automatic NAME``_write(input logic [31:0] addr, val, strb); \
        @(negedge clk); BUS.awaddr=addr; BUS.awvalid=1; BUS.wdata=val; BUS.wstrb=strb; BUS.wvalid=1; \
        fork \
            begin do @(posedge clk); while(!BUS.awready); @(negedge clk); BUS.awvalid=0; end \
            begin do @(posedge clk); while(!BUS.wready); @(negedge clk); BUS.wvalid=0; end \
        join \
        BUS.bready=1; do @(posedge clk); while(!BUS.bvalid); \
        if(BUS.bresp !== 0 || BUS.bid !== 4) $fatal(1,"write response at %h",addr); \
        @(negedge clk); BUS.bready=0; \
    endtask \
    task automatic NAME``_check(input logic [31:0] addr, val); \
        @(negedge clk); BUS.araddr=addr; BUS.arvalid=1; \
        do @(posedge clk); while(!BUS.arready); @(negedge clk); BUS.arvalid=0; BUS.rready=1; \
        do @(posedge clk); while(!BUS.rvalid); \
        if(BUS.rresp !== 0 || BUS.rid !== 3 || BUS.rlast !== 1 || BUS.rdata[31:0] !== val) \
            $fatal(1,"read %h expected %h got %h",addr,val,BUS.rdata[31:0]); \
        @(negedge clk); BUS.rready=0; \
    endtask
    `PORT_TASKS(cpu,host,2)
    `PORT_TASKS(even,gpu0,5)
    `PORT_TASKS(odd,gpu1,5)
    initial begin
        cpu_init(); even_init(); odd_init();
        #300; @(negedge clk); rst=1;
        wait(ready); repeat(40) @(posedge clk);
        cpu_write(32'h01c00000,32'h11223344,15);
        cpu_write(32'h01c01000,32'h55667788,15);
        fork
            even_check(32'h01c00000,32'h11223344);
            odd_check(32'h01c01000,32'h55667788);
        join
        fork
            even_write(32'h01c00000,32'h88888888,5);
            odd_write(32'h01c01000,32'hbbbbbbbb,10);
        join
        cpu_check(32'h01c00000,32'h11883388);
        cpu_check(32'h01c01000,32'hbb66bb88);
        $display("PASS dual HBM host/GPU bidirectional page and strobe visibility");
        $finish;
    end
    initial begin #2000000; $fatal(1,"HBM model timeout, ready=%b",ready); end
endmodule
