`timescale 1ns/1ps
`include "axi4_if.svh"
module soc_contract_tb;
    logic clk = 0, hclk = 0, resetn = 0;
    always #5 clk = ~clk;
    always #2.222 hclk = ~hclk;
    axi4_if cpu(), host(), mem(), periph();
    logic ready, trip, run_cpu, irq;
    logic cpu_enable=1, cpu_idle;
    logic [6:0] temp;
    logic [15:0] debug_status;
    byte unsigned golden [0:65535];
    int checks = 0;
    axi_arbiter_2m2s fabric(.clk(clk), .resetn(resetn), .cpu_enable(cpu_enable), .cpu_idle(cpu_idle),
        .cpu(cpu), .jtag(host), .mem(mem), .periph(periph));
    hbm_axi_bridge memory_path(.clk_cpu(clk), .resetn_cpu(resetn), .clk225(hclk), .resetn225(resetn),
        .clk100(clk), .resetn100(resetn), .hbm_ready(ready), .hbm_cattrip(trip), .hbm_temp(temp),
        .axi_debug_clear(1'b0), .axi_debug(debug_status), .s_axi(mem));
    jtag_uart_16550 uart(.clk(clk), .resetn(resetn), .hbm_ready(ready), .clk_locked(1'b1), .cpu_halted(!run_cpu),
        .cpu_run(run_cpu), .irq(irq), .s_axi(periph));

    task automatic write_host(input int address, input int count, input int size, input int seed,
                              input bit update_golden = 1, input int force_strobe = -1, input int burst=1);
        int a, word_data, strobe;
        @(negedge clk);
        host.awid = 13; host.awaddr = address; host.awlen = count-1; host.awsize = size;
        host.awburst = burst; host.awcache = 0; host.awvalid = 1;
        do @(posedge clk); while (!host.awready);
        @(negedge clk); host.awvalid = 0;
        a = address;
        for (int beat=0; beat<count; beat++) begin
            word_data = seed ^ (beat * 32'h1020301);
            strobe = force_strobe >= 0 ? force_strobe : (((1 << (1 << size))-1) << (a & 3));
            host.wdata = word_data; host.wstrb = strobe; host.wlast = beat == count-1; host.wvalid = 1;
            do @(posedge clk); while (!host.wready);
            if (update_golden) for (int lane=0; lane<4; lane++)
                if ((strobe >> lane) & 1) golden[(a & ~3) + lane] = (word_data >> (lane*8)) & 255;
            @(negedge clk); host.wvalid = 0;
            if (beat % 3 == 0) repeat (2) @(negedge clk);
            a = burst==2 ? ((address & ~((count<<size)-1)) | ((a+(1<<size)) & ((count<<size)-1))) : a+(1<<size);
        end
        repeat (4) @(negedge clk);
        host.bready = 1;
        do @(posedge clk); while (!host.bvalid);
        assert(host.bid == 13 && host.bresp == 0) else $fatal(1, "Host B response/ID");
        checks++;
        @(negedge clk); host.bready = 0;
    endtask

    task automatic read_host(input int address, input int count, input int size, input int burst=1);
        int a, actual, expected, mask;
        @(negedge clk);
        host.arid = 9; host.araddr = address; host.arlen = count-1; host.arsize = size;
        host.arburst = burst; host.arcache = 0; host.arvalid = 1;
        do @(posedge clk); while (!host.arready);
        @(negedge clk); host.arvalid = 0;
        a = address;
        for (int beat=0; beat<count; beat++) begin
            host.rready = 0;
            wait(host.rvalid);
            actual = host.rdata;
            repeat (3) begin
                @(negedge clk);
                assert(host.rvalid && host.rdata === actual) else $fatal(1,"Unstable read under backpressure");
            end
            expected = 0;
            for (int lane=0; lane<4; lane++) expected |= int'(golden[(a & ~3)+lane]) << (8*lane);
            mask = size == 2 ? -1 : ((1 << (8*(1 << size)))-1) << (8*(a&3));
            assert((actual & mask) === (expected & mask))
                else $fatal(1,"Read mismatch at %x: got %x expected %x mask %x",a,actual,expected,mask);
            assert(host.rid == 9 && host.rresp == 0 && host.rlast == (beat==count-1))
                else $fatal(1,"Host read response/ID/last");
            checks++;
            host.rready = 1; @(posedge clk); @(negedge clk); host.rready = 0;
            a = burst==2 ? ((address & ~((count<<size)-1)) | ((a+(1<<size)) & ((count<<size)-1))) : a+(1<<size);
        end
    endtask

    task automatic mmio_read(input int address, output int value, input int hold_cycles = 3);
        @(negedge clk);
        host.arid=7; host.araddr=address; host.arlen=0; host.arsize=2; host.arburst=1; host.arvalid=1;
        do @(posedge clk); while(!host.arready);
        @(negedge clk); host.arvalid=0; host.rready=0;
        wait(host.rvalid); value=host.rdata;
        repeat(hold_cycles) begin
            @(negedge clk);
            assert(host.rdata === value && host.rvalid) else $fatal(1,"MMIO RDATA not stable");
        end
        host.rready=1; @(posedge clk); @(negedge clk); host.rready=0;
        checks++;
    endtask

    task automatic cpu_write(input int address, input int value, input bit quiesce=0);
        @(negedge clk); cpu.awaddr=address; cpu.awlen=0; cpu.awsize=2;
        cpu.awburst=1; cpu.awid=3; cpu.awcache=2; cpu.awvalid=1;
        do @(posedge clk); while(!cpu.awready);
        @(negedge clk); cpu.awvalid=0;
        if(quiesce) begin
            cpu_enable=0;
            repeat(5) begin
                @(negedge clk);
                assert(!cpu_idle) else $fatal(1,"CPU declared idle before its W/B completed");
            end
        end
        cpu.wdata=value;cpu.wstrb=15;cpu.wlast=1;cpu.wvalid=1;
        do @(posedge clk); while(!cpu.wready);
        @(negedge clk); cpu.wvalid=0;cpu.bready=1;
        do @(posedge clk); while(!cpu.bvalid);
        assert(cpu.bid==3 && cpu.bresp==0) else $fatal(1,"CPU response routing");
        @(negedge clk);cpu.bready=0;
    endtask

    int value, held;
    initial begin
        foreach(golden[i]) golden[i]=0;
        cpu.awvalid=0;cpu.wvalid=0;cpu.arvalid=0;cpu.bready=0;cpu.rready=0;
        host.awvalid=0;host.wvalid=0;host.arvalid=0;host.bready=0;host.rready=0;
        repeat(30) @(negedge clk);resetn=1;
        wait(ready);repeat(30) @(negedge clk);
        // Aligned and unaligned maximum JTAG bursts, and an AXI4 256-beat
        // burst that must be split into multiple legal HBM transactions.
        write_host('h1000,128,2,'h5ac31f09);read_host('h1000,128,2);
        write_host('h201c,128,2,'h719503ab);read_host('h201c,128,2);
        write_host('h3004,256,2,'h198673ae);read_host('h3004,256,2);
        for(int offset=0;offset<16;offset++) begin
            read_host('h1000+4*offset,16,2,2);
            write_host('h7000+4*offset,16,2,'h53a716b9^offset,1,-1,2);
            read_host('h7000+4*offset,16,2,2);
        end
        for(int lane=0;lane<4;lane++) begin
            write_host('h4000+lane,1,0,'h83fa1957 ^ lane);read_host('h4000+lane,1,0);
        end
        for(int lane=0;lane<4;lane+=2) begin
            write_host('h4020+lane,1,1,'h2468ace0 ^ lane);read_host('h4020+lane,1,1);
        end
        read_host('h4000,16,2);
        fork
            read_host('h1000,128,2);
            begin for(int n=0;n<16;n++) cpu_write('h5000+n*4,'hfab10000+n); end
        join
        for(int n=0;n<16;n++) for(int lane=0;lane<4;lane++)
            golden['h5000+n*4+lane]=(('hfab10000+n)>>(lane*8))&255;
        read_host('h5000,16,2);
        fork
            cpu_write('h6000,'h159ae73c,1);
            read_host('h1000,16,2);
        join
        assert(cpu_idle) else $fatal(1,"CPU did not quiesce after outstanding response");
        @(negedge clk);cpu.arvalid=1;cpu.araddr='h6000;
        repeat(6) begin
            @(negedge clk);assert(!cpu.arready) else $fatal(1,"New CPU request accepted while stopping");
        end
        cpu.arvalid=0;cpu_enable=1;
        mmio_read('h1fe10010,value);assert(value=='h54324801) else $fatal(1,"SoC ABI ID");
        mmio_read('h1fe10014,value);assert(value=='h00e10064) else $fatal(1,"Clock configuration ID");
        // Zero strobe must not release the CPU.
        write_host('h1fe10000,1,2,1,0,0);
        assert(!run_cpu) else $fatal(1,"Zero WSTRB changed run control");
        write_host('h1fe10000,1,2,1,0);assert(run_cpu) else $fatal(1,"Run control write");
        write_host('h1fe001e3,1,0,'h03000000,0);
        write_host('h1fe001e0,1,0,'h41,0);
        fork
            mmio_read('h1fe11000,held,20);
            begin wait(host.rvalid);cpu_write('h1fe001e0,'h42);end
        join
        assert(held=='h41) else $fatal(1,"TX stream capture changed");
        mmio_read('h1fe11000,value);assert(value=='h42) else $fatal(1,"Concurrent TX lost");
        write_host('h1fe001e1,1,0,'h100,0);
        write_host('h1fe10008,1,2,'h55,0);assert(irq) else $fatal(1,"RX interrupt missing");
        mmio_read('h1fe001e0,value);assert(value=='h55 && !irq) else $fatal(1,"RX/IRQ consumption");
        mmio_read('h1fe10000,value);assert(!value[3]) else $fatal(1,"FIFO overflow");
        // Full RX FIFO and wrap-around, then the complete 4KiB TX FIFO.
        for(int i=0;i<256;i++) write_host('h1fe10008,1,2,i,0);
        write_host('h1fe10008,1,2,'hff,0); // full FIFO must reject this byte
        for(int i=0;i<256;i++) begin
            mmio_read('h1fe001e0,value);
            assert((value&255)==(i&255)) else $fatal(1,"RX FIFO order/wrap");
        end
        assert(!irq) else $fatal(1,"RX IRQ did not clear after draining");
        for(int i=0;i<4096;i++) write_host('h1fe001e0,1,0,i,0);
        mmio_read('h1fe001e5,value);assert((value&'h6000)==0) else $fatal(1,"TX full status");
        write_host('h1fe001e0,1,0,'hff,0);
        mmio_read('h1fe10000,value);
        assert(value[3] && (value>>19)==4096) else $fatal(1,"TX overflow or count");
        for(int i=0;i<1024;i++) begin
            mmio_read('h1fe11000,value);
            for(int lane=0;lane<4;lane++)
                assert(((value>>(8*lane))&255)==((4*i+lane)&255)) else $fatal(1,"TX FIFO order/wrap");
        end
        write_host('h1fe10000,1,2,8,0);
        mmio_read('h1fe10000,value);assert(!value[3] && (value>>19)==0) else $fatal(1,"TX clear/count");
        $display("SOC_CONTRACT_PASS checks=%0d cpu_mhz=100 hbm_axi_mhz=225",checks);
        $finish;
    end
    initial begin #2000000;$fatal(1,"SoC contract test timeout");end
endmodule
