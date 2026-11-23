`include "axi4_if.svh"
module board_decode;
    bit clk_cpu, resetn_cpu;
    always #5 clk_cpu = ~clk_cpu;
    axi4_if host_io(), host_mem(), mem_axi(), periph_axi();
    `include "test_gpu_instance.svh"
    logic cpu_run;
    jtag_uart_16550 uart(.clk(clk_cpu), .resetn(resetn_cpu), .hbm_ready(1'b1),
        .clk_locked(1'b1), .cpu_halted(1'b0), .cpu_run, .irq(), .s_axi(periph_axi));
    initial begin #20000; $fatal(1, "peripheral access timeout"); end
    int reads, writes;
    task automatic read_word(input int addr, value, input bit [2:0] size = 2,
                             input bit [1:0] resp = 0, input bit [7:0] len = 0);
        @(negedge clk_cpu);
        host_io.araddr = addr; host_io.arsize = size; host_io.arlen = len;
        host_io.arburst = 1; host_io.arid = 9; host_io.arvalid = 1;
        do @(posedge clk_cpu); while (!host_io.arready);
        @(negedge clk_cpu); host_io.arvalid = 0;
        for (int i=0; i<=int'(len); i++) begin
            do @(posedge clk_cpu); while (!host_io.rvalid);
            repeat (3) begin
                @(posedge clk_cpu);
                if (!host_io.rvalid || host_io.rresp != resp || host_io.rdata != value ||
                    host_io.rid != 9 || host_io.rlast != (i==int'(len)))
                    $fatal(1,"read at %h returned %h resp %d beat %d",addr,host_io.rdata,host_io.rresp,i);
            end
            @(negedge clk_cpu); host_io.rready=1;
            @(negedge clk_cpu); host_io.rready=0;
        end
        reads++;
    endtask
    task automatic write_word(input int addr, value, input bit [2:0] size=2,
                              input bit [3:0] strb=15, input bit [1:0] resp=0,
                              input bit [7:0] len=0);
        @(negedge clk_cpu);
        host_io.awaddr=addr; host_io.awlen=len; host_io.awsize=size; host_io.awburst=1;
        host_io.awid=7; host_io.wvalid=1; host_io.wdata=value; host_io.wstrb=strb; host_io.wlast=(len==0);
        repeat(3) @(negedge clk_cpu);
        host_io.awvalid=1;
        do @(posedge clk_cpu); while (!host_io.awready);
        @(negedge clk_cpu); host_io.awvalid=0;
        for (int i=0; i<=int'(len); i++) begin
            host_io.wlast=(i==int'(len));
            do @(posedge clk_cpu); while (!host_io.wready);
            @(negedge clk_cpu);
        end
        host_io.wvalid=0;
        do @(posedge clk_cpu); while (!host_io.bvalid);
        repeat(3) begin
            @(posedge clk_cpu);
            if (!host_io.bvalid || host_io.bresp!=resp || host_io.bid!=7)
                $fatal(1,"write at %h returned %d",addr,host_io.bresp);
        end
        @(negedge clk_cpu); host_io.bready=1;
        @(negedge clk_cpu); host_io.bready=0;
        writes++;
    endtask
    initial begin
        repeat(4) @(negedge clk_cpu); resetn_cpu=1;
        read_word('h1fe20000,'h4d4f5247);
        read_word('h1fe20004,'h00010000);
        write_word('h1fe2000c,'h1f801000); read_word('h1fe2000c,'h1f801000);
        read_word('h1fe20ffc,0,2,2);
        read_word('h1fe21000,0); read_word('h1fe1fffc,0);
        read_word('h1fe20000,0,0,2); read_word('h1fe20000,0,2,2,3);
        write_word('h1fe2000c,0,2,15,2,2); read_word('h1fe2000c,'h1f801000);
        read_word('h1fe10010,'h54324801); read_word('h1fe10014,'h00e10064);
        read_word('h1fe10000,6);
        write_word('h1fe10000,1); read_word('h1fe10000,7);
        write_word('h1fe001e7,'hbb000000,0,8); read_word('h1fe001e7,'hbb000000,0);
        write_word('h1fe001e0,'h4d,0,1); read_word('h1fe10004,'h8000004d);
        write_word('h1fe10008,'h53); read_word('h1fe001e0,'h53,0);
        write_word('h1fefd000,'hdeadbeef); read_word('h1fefd000,0,2,0,3);
        $display("{\"reads\":%0d,\"writes\":%0d,\"pass\":true}",reads,writes);
        $finish;
    end
endmodule
