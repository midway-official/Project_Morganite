`timescale 1ns/1ps
`include "axi4_if.svh"
module gpu_hbm_ip_tb;
    logic clk=0, hclk=0, rst=0;
    always #5 clk = ~clk;
    always #2.222 hclk = ~hclk;
    axi4_if #(.DATA_WIDTH(256)) src(), pc();
    gpu_hbm_path dut(.clk_cpu(clk), .resetn_cpu(rst), .clk225(hclk), .resetn225(rst), .src, .pc);
    int nr=0, na=0, nw=0, rb=0, rr=0;
    logic [7:0] ids=0;
    logic [3:0] bids=0;
    always @(posedge hclk) if(rst) begin
        if(pc.arvalid && pc.arready) begin
            if(pc.arid !== nr[3:0] || pc.araddr !== nr*32 || pc.arsize !== 5 || pc.arlen !== 0) $fatal;
            nr++;
        end
        if(pc.awvalid && pc.awready) begin
            if(pc.awid !== na[3:0] || pc.awaddr !== 1024+na*32 || pc.awlen !== 0) $fatal;
            na++;
        end
        if(pc.wvalid && pc.wready) begin
            if(pc.wdata !== nw+123 || pc.wstrb !== (32'h0f << nw) || !pc.wlast) $fatal;
            nw++;
        end
    end
    always @(posedge clk) if(rst) begin
        if(src.rvalid && src.rready) begin
            if(src.rid !== (7-rr) || ids[src.rid] || src.rdata !== src.rid+456 || !src.rlast) $fatal;
            ids[src.rid]=1; rr++;
        end
        if(src.bvalid && src.bready) begin
            if(src.bid !== (3-rb) || bids[src.bid]) $fatal;
            bids[src.bid]=1; rb++;
        end
    end
    initial begin
        {src.awvalid,src.wvalid,src.arvalid,src.bready,src.rready}=0;
        {src.awlen,src.arlen,src.awlock,src.arlock,src.awprot,src.arprot,
         src.awcache,src.arcache,src.awqos,src.arqos}=0;
        src.awburst=1;src.arburst=1;src.awsize=5;src.arsize=5;src.wlast=1;
        pc.arready=1;pc.awready=1;pc.wready=0;pc.bvalid=0;pc.rvalid=0;
        pc.bresp=0;pc.rresp=0;pc.rlast=1;
        #200; @(negedge clk); rst=1;
        fork
            begin
                for(int i=0;i<8;i++) begin
                    @(negedge clk);src.arvalid=1;src.arid=i;src.araddr=i*32;
                    do @(posedge clk); while(!src.arready);
                end
                @(negedge clk);src.arvalid=0;
            end
            begin
                for(int i=0;i<4;i++) begin
                    @(negedge clk);src.awvalid=1;src.awid=i;src.awaddr=1024+i*32;
                    do @(posedge clk); while(!src.awready);
                end
                @(negedge clk);src.awvalid=0;
            end
            begin
                for(int i=0;i<4;i++) begin
                    @(negedge clk);src.wvalid=1;src.wdata=i+123;src.wstrb=32'h0f<<i;
                    do @(posedge clk); while(!src.wready);
                end
                @(negedge clk);src.wvalid=0;
            end
            begin repeat(90) @(negedge hclk); pc.wready=1; end
        join
        wait(nr==8 && na==4 && nw==4);
        fork
            begin
                for(int i=7;i>=0;i--) begin
                    @(negedge hclk);pc.rvalid=1;pc.rid=i;pc.rdata=i+456;
                    do @(posedge hclk); while(!pc.rready);
                end
                @(negedge hclk);pc.rvalid=0;
            end
            begin
                for(int i=3;i>=0;i--) begin
                    @(negedge hclk);pc.bvalid=1;pc.bid=i;
                    do @(posedge hclk); while(!pc.bready);
                end
                @(negedge hclk);pc.bvalid=0;
            end
            begin repeat(25) @(negedge clk);src.rready=1;src.bready=1;end
        join
        wait(rr==8 && rb==4);
        $display("PASS vendor CDC and AXI3 8R/4W with reversed IDs at 100/225 MHz");
        $finish;
    end
    initial begin #15000; $fatal(1,"CDC or AXI3 progress timeout"); end
endmodule
