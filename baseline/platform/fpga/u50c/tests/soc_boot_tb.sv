`timescale 1ns/1ps
`include "axi4_if.svh"
// Actual frozen CPU and board datapath; only the physical HBM controller is
// replaced. The small Linux reset stub is sufficient to test instruction
// fetch, narrow stores, polling loads, caches and the JTAG console together.
module soc_boot_tb;
    logic clk=0,hclk=0,resetn=0;
    always #5 clk=~clk;
    always #2.222 hclk=~hclk;
    axi4_if cpu(),host(),mem(),periph();
    logic ready,trip,run_cpu,irq;
    logic cpu_idle;
    wire cpu_reset = !resetn || !ready || (!run_cpu && cpu_idle);
    logic [6:0] temp;
    logic [15:0] debug_status;
    `include "boot_config.svh"
    logic [7:0] image [0:IMAGE_BYTES-1];
    string actual="";
    axi_arbiter_2m2s fabric(.clk(clk),.resetn(resetn),.cpu_enable(run_cpu),.cpu_idle(cpu_idle),
        .cpu(cpu),.jtag(host),.mem(mem),.periph(periph));
    aurora_axi_wrapper processor(.clk(clk),.reset(cpu_reset),
        .ext_int({6'b0,irq,1'b0}),.m_axi(cpu));
    hbm_axi_bridge memory_path(.clk_cpu(clk),.resetn_cpu(resetn),.clk225(hclk),.resetn225(resetn),
        .clk100(clk),.resetn100(resetn),.hbm_ready(ready),.hbm_cattrip(trip),.hbm_temp(temp),
        .axi_debug_clear(1'b0),.axi_debug(debug_status),.s_axi(mem));
    jtag_uart_16550 uart(.clk(clk),.resetn(resetn),.hbm_ready(ready),.clk_locked(1'b1),.cpu_halted(cpu_reset),
        .cpu_run(run_cpu),.irq(irq),.s_axi(periph));
    task automatic write_word(input int address,input int data_word,input int size=2,input int strobe=15);
        @(negedge clk);host.awaddr=address;host.awid=13;host.awlen=0;
        host.awsize=size;host.awburst=1;host.awcache=0;host.awvalid=1;
        do @(posedge clk);while(!host.awready);
        @(negedge clk);host.awvalid=0;host.wdata=data_word;
        host.wstrb=strobe;host.wlast=1;host.wvalid=1;
        do @(posedge clk);while(!host.wready);
        @(negedge clk);host.wvalid=0;host.bready=1;
        do @(posedge clk);while(!host.bvalid);
        assert(host.bid==13 && host.bresp==0) else $fatal(1,"Boot loader write failure");
        @(negedge clk);host.bready=0;
    endtask
    task automatic read_word(input int address,output int data_word,input int size=2);
        @(negedge clk);host.araddr=address;host.arid=9;host.arlen=0;
        host.arsize=size;host.arburst=1;host.arcache=0;host.arvalid=1;
        do @(posedge clk);while(!host.arready);
        @(negedge clk);host.arvalid=0;host.rready=1;
        do @(posedge clk);while(!host.rvalid);
        data_word=host.rdata;
        assert(host.rid==9 && host.rresp==0 && host.rlast) else $fatal(1,"Boot loader read failure");
        @(negedge clk);host.rready=0;
    endtask
    int word_data,status,bytes_pending;
    bit injected;
    int requests=0,responses=0,writes=0,commits=0,cycles=0;
    always @(posedge clk) begin
        cycles++;
        if(processor.u_aurora.core.rob_io_commit_0_valid && commits<64) begin
            $display("CPU_COMMIT pc=%x",processor.u_aurora.core.rob_io_commit_0_pc);
            commits++;
        end
        if(processor.u_aurora.core.lsu_io_cdbBroadcastValid && responses<64)
            $display("CPU_LSU data=%x",processor.u_aurora.core.lsu_io_out_bits_data);
        if(cycles % 5000 == 0)
            $display("CPU_PROGRESS cycles=%0d head=%x br_out=%b br_miss=%b lsu=%b",
                cycles,processor.u_aurora.core.rob_io_headIdx,
                processor.u_aurora.core.bru_io_out_valid,
                processor.u_aurora.core.rawEarlyMispredict,
                processor.u_aurora.core.lsu_io_cdbBroadcastValid);
        if(cpu.arvalid && cpu.arready && requests<32) begin
            $display("CPU_AR addr=%x len=%d size=%d id=%x",cpu.araddr,cpu.arlen,cpu.arsize,cpu.arid);
            requests++;
        end
        if(cpu.rvalid && cpu.rready && responses<64) begin
            $display("CPU_R data=%x id=%x last=%b",cpu.rdata,cpu.rid,cpu.rlast);
            responses++;
        end
        if(cpu.awvalid && cpu.awready && writes<32) begin
            $display("CPU_AW addr=%x len=%d size=%d",cpu.awaddr,cpu.awlen,cpu.awsize);
            writes++;
        end
    end
    initial begin
        host.awid=0;host.awaddr=0;host.awlen=0;host.awsize=2;host.awburst=1;
        host.awlock=0;host.awcache=0;host.awprot=0;host.awqos=0;
        host.arid=0;host.araddr=0;host.arlen=0;host.arsize=2;host.arburst=1;
        host.arlock=0;host.arcache=0;host.arprot=0;host.arqos=0;
        host.wdata=0;host.wstrb=0;host.wlast=0;
        host.awvalid=0;host.wvalid=0;host.arvalid=0;host.bready=0;host.rready=0;
        $readmemh("start.hex",image);
        repeat(30) @(negedge clk);resetn=1;wait(ready);repeat(30) @(negedge clk);
        $display("BOOT_LOADER_BEGIN");
        for(int i=0;i<IMAGE_BYTES;i+=4) begin
            word_data={image[i+3],image[i+2],image[i+1],image[i]};
            write_word('h1c000000+i,word_data);
            read_word('h1c000000+i,status);
            assert(status===word_data) else $fatal(1,"Boot stub HBM readback mismatch");
        end
        $display("BOOT_IMAGE_READBACK_PASS bytes=%0d",IMAGE_BYTES);
        for(int attempt=0;attempt<2;attempt++) begin
            actual="";injected=0;
            write_word('h1fe10000,14);
            write_word('h1fe001e7,0,0,8);
            read_word('h1fe10000,status);
            assert(status[4]) else $fatal(1,"CPU did not stop before reload");
            write_word('h1fe10000,1);
            $display("CPU_RELEASED attempt=%0d reset=%b run=%b",attempt,cpu_reset,run_cpu);
            while(actual.len()<expected.len()) begin
                if(SOC_SMOKE && !injected) begin
                    read_word('h1fe001e7,status,0);
                    if((status>>24)==8'h5a) begin
                        write_word('h1fe10008,'h5a);injected=1;
                        $display("CPU_IRQ_BYTE_INJECTED");
                    end
                end
                read_word('h1fe10000,status);
                bytes_pending=(status>>19)&'h1fff;
                assert(!status[3]) else $fatal(1,"Boot UART overflow");
                if(bytes_pending>0) begin
                    read_word('h1fe10004,word_data);
                    actual={actual,byte'(word_data)};
                end
            end
            write_word('h1fe10000,0);
            do read_word('h1fe10000,status);while(!status[4]);
            assert(actual==expected) else $fatal(1,"CPU boot output mismatch: %s",actual);
            if(SOC_SMOKE) begin
                assert(injected) else $fatal(1,"Interrupt test did not inject RX");
                read_word('h10200,status);assert(status=='hbbccaa44) else $fatal(1,"HBM narrow-store backing data");
                read_word('h10300,status);assert(status==18) else $fatal(1,"HBM LL/SC backing data");
                read_word('h10400,status);assert(status=='h600dbeef) else $fatal(1,"HBM interrupt marker");
            end
            $display("CPU_BOOT_ITERATION_PASS attempt=%0d output=%s",attempt,actual);
        end
        $display("CPU_BOOT_PASS frozen=Tick2h cpu_mhz=100 restarts=2 output=%s",actual);
        $finish;
    end
    initial begin #1000000;$fatal(1,"Frozen CPU boot smoke timeout output=%s",actual);end
endmodule
