`include "axi4_if.svh"

module u50c_top (
    input  wire SYSCLK3_P,
    input  wire SYSCLK3_N,
    output wire HBM_CATTRIP
);
    wire clk100_ibuf;
    wire clk100;
    wire clk_cpu;
    wire clk225;
    wire clk_locked;
    wire hbm_ready;
    wire hbm_cattrip_raw;
    wire [6:0] hbm_temp;
    wire [15:0] hbm_axi_debug;
    wire uart_irq;
    wire cpu_run;
    wire cpu_reset;
    wire cpu_idle;
    wire cpu_enable;
    wire vio_force_cpu_reset;
    wire vio_clear_axi_debug;
    wire [7:0] ext_int;

    (* ASYNC_REG = "TRUE" *) logic [2:0] hbm_ready_pipe = 3'b0;
    (* ASYNC_REG = "TRUE" *) logic [1:0] force_cpu_reset_pipe = 2'b0;
    wire resetn100;
    wire resetn_cpu;
    wire resetn225;
    wire [7:0] status_raw;
    wire [7:0] status_ref;
    wire hbm_ready_cpu = hbm_ready_pipe[2];

    axi4_if #(.ADDR_WIDTH(32), .DATA_WIDTH(32), .ID_WIDTH(4)) cpu_axi();
    axi4_if #(.ADDR_WIDTH(32), .DATA_WIDTH(32), .ID_WIDTH(4)) jtag_axi();
    axi4_if #(.ADDR_WIDTH(32), .DATA_WIDTH(32), .ID_WIDTH(4)) mem_axi();
    axi4_if #(.ADDR_WIDTH(32), .DATA_WIDTH(32), .ID_WIDTH(4)) periph_axi();

    IBUFDS #(
        .DIFF_TERM("TRUE"),
        .IBUF_LOW_PWR("FALSE")
    ) u_hbm_refclk_ibuf (
        .I (SYSCLK3_P),
        .IB(SYSCLK3_N),
        .O (clk100_ibuf)
    );

    BUFG u_hbm_refclk_bufg (
        .I(clk100_ibuf),
        .O(clk100)
    );

    clk_wiz_0 u_clock_gen (
        .clk_out1(clk_cpu),
        .clk_out2(clk225),
        .locked  (clk_locked),
        .clk_in1 (clk100)
    );

    u50c_reset_control u_reset_control (
        .clk_ref(clk100), .clk_cpu(clk_cpu), .clk_hbm(clk225), .clk_locked(clk_locked),
        .resetn_ref(resetn100), .resetn_cpu(resetn_cpu), .resetn_hbm(resetn225)
    );
    always_ff @(posedge clk_cpu) begin
        if (!resetn_cpu) hbm_ready_pipe <= 3'b0;
        else           hbm_ready_pipe <= {hbm_ready_pipe[1:0], hbm_ready};
        force_cpu_reset_pipe <= {force_cpu_reset_pipe[0], vio_force_cpu_reset};
    end

    // NEMU's 16550 model raises external interrupt line 1.
    assign ext_int = {6'b0, uart_irq, 1'b0};
    assign cpu_enable = cpu_run && !force_cpu_reset_pipe[1];
    assign cpu_reset = !resetn_cpu || !hbm_ready_cpu || (!cpu_enable && cpu_idle);

    aurora_axi_wrapper u_cpu (
        .clk     (clk_cpu),
        .reset   (cpu_reset),
        .ext_int (ext_int),
        .m_axi   (cpu_axi)
    );

    jtag_axi_master u_jtag (
        .clk    (clk_cpu),
        .resetn (resetn_cpu),
        .m_axi  (jtag_axi)
    );

    axi_arbiter_2m2s u_fabric (
        .clk    (clk_cpu),
        .resetn (resetn_cpu),
        .cpu_enable(cpu_enable),
        .cpu_idle(cpu_idle),
        .cpu    (cpu_axi),
        .jtag   (jtag_axi),
        .mem    (mem_axi),
        .periph (periph_axi)
    );

    hbm_axi_bridge u_memory (
        .clk_cpu       (clk_cpu),
        .resetn_cpu    (resetn_cpu),
        .clk225      (clk225),
        .resetn225   (resetn225),
        .clk100      (clk100),
        .resetn100   (resetn100),
        .hbm_ready   (hbm_ready),
        .hbm_cattrip (hbm_cattrip_raw),
        .hbm_temp    (hbm_temp),
        .axi_debug_clear(vio_clear_axi_debug),
        .axi_debug   (hbm_axi_debug),
        .s_axi       (mem_axi)
    );

    // Never mask the HBM IP's thermal shutdown indication with init status.
    // The configured single left stack exports DRAM_0_STAT_CATTRIP.
    assign HBM_CATTRIP = hbm_cattrip_raw;

    // These are independent diagnostic levels, not a coherent data word.
    // Synchronize each bit before the reference-clocked VIO samples it.
    assign status_raw = {cpu_run, hbm_cattrip_raw, hbm_ready, resetn225,
                         resetn_cpu, resetn100, resetn100, clk_locked};
    for (genvar i = 0; i < 8; i++) begin : g_status_sync
        xpm_cdc_single #(.DEST_SYNC_FF(3), .SRC_INPUT_REG(0), .INIT_SYNC_FF(0))
        u_sync (.src_clk(1'b0), .src_in(status_raw[i]), .dest_clk(clk100), .dest_out(status_ref[i]));
    end
    vio_status_0 u_status_vio (
        .clk       (clk100),
        .probe_in0 (status_ref),
        .probe_out0(vio_force_cpu_reset)
    );

    vio_hbm_0 u_hbm_vio (
        .clk       (clk225),
        .probe_in0 (hbm_axi_debug),
        .probe_out0(vio_clear_axi_debug)
    );

    jtag_uart_16550 u_uart (
        .clk        (clk_cpu),
        .resetn     (resetn_cpu),
        .hbm_ready  (hbm_ready_cpu),
        .clk_locked (clk_locked),
        .cpu_halted (cpu_reset),
        .cpu_run    (cpu_run),
        .irq        (uart_irq),
        .s_axi      (periph_axi)
    );
endmodule
