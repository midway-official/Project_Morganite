// Reset asserts asynchronously in each destination and releases after four
// local clock edges. XPM supplies the synchronizer placement and CDC constraints.
module u50c_reset_control (
    input  wire clk_ref,
    input  wire clk_cpu,
    input  wire clk_hbm,
    input  wire clk_locked,
    output wire resetn_ref,
    output wire resetn_cpu,
    output wire resetn_hbm
);
    logic [11:0] startup_count = 12'b0;
    logic startup_done = 1'b0;
    always_ff @(posedge clk_ref) begin
        if (!clk_locked) begin
            startup_count <= 12'b0;
            startup_done <= 1'b0;
        end else if (!startup_done) begin
            startup_count <= startup_count + 12'd1;
            if (&startup_count) startup_done <= 1'b1;
        end
    end
    assign resetn_ref = startup_done;
    xpm_cdc_async_rst #(.DEST_SYNC_FF(4), .INIT_SYNC_FF(0), .RST_ACTIVE_HIGH(0))
    u_cpu_reset_sync (.src_arst(startup_done), .dest_clk(clk_cpu), .dest_arst(resetn_cpu));
    xpm_cdc_async_rst #(.DEST_SYNC_FF(4), .INIT_SYNC_FF(0), .RST_ACTIVE_HIGH(0))
    u_hbm_reset_sync (.src_arst(startup_done), .dest_clk(clk_hbm), .dest_arst(resetn_hbm));
endmodule
