`include "axi4_if.svh"

// 16550-compatible byte registers for Linux at 0x1fe0_01e0, plus a JTAG-only
// control/streaming window at 0x1fe1_0000.  The latter makes the console usable
// even when no physical UART cable is attached to the accelerator card.
module jtag_uart_16550 (
    input  logic    clk,
    input  logic    resetn,
    input  logic    hbm_ready,
    input  logic    clk_locked,
    input  logic    cpu_halted,
    output logic    cpu_run,
    output logic    irq,
    axi4_if.slave   s_axi
);
    localparam integer TX_DEPTH = 4096;
    localparam integer RX_DEPTH = 256;

    (* ram_style = "distributed" *) logic [7:0] tx_mem [0:TX_DEPTH-1];
    (* ram_style = "distributed" *) logic [7:0] rx_mem [0:RX_DEPTH-1];
    logic [11:0] tx_wptr, tx_rptr;
    logic [7:0]  rx_wptr, rx_rptr;
    logic [12:0] tx_count;
    logic [8:0]  rx_count;

    logic [7:0] dll, dlm, ier, fcr, lcr, mcr, scr;
    logic fifo_overflow;

    logic        w_active, b_valid;
    logic [31:0] w_addr;
    logic [3:0]  w_id, b_id;
    logic [7:0]  w_len, w_beat;
    logic [2:0]  w_size;
    logic [1:0]  w_burst;

    logic        r_active;
    logic [31:0] r_addr;
    logic [3:0]  r_id;
    logic [7:0]  r_len, r_beat;
    logic [2:0]  r_size;
    logic [1:0]  r_burst;
    logic        r_valid;
    logic [31:0] r_data_q;

    logic [7:0] w_byte;
    logic       w_fire, r_fire, read_capture;
    logic       tx_push, rx_push, rx_pop;
    logic [7:0] tx_push_data, rx_push_data;
    logic [2:0] tx_pop_count;
    logic       tx_clear, rx_clear;
    logic [7:0] uart_read_byte;
    logic [31:0] read_data;

    function automatic [31:0] byte_on_lane(
        input logic [7:0] value,
        input logic [1:0] lane
    );
        logic [31:0] result;
        begin
            result = 32'b0;
            result[lane*8 +: 8] = value;
            byte_on_lane = result;
        end
    endfunction

    function automatic [31:0] next_address(
        input logic [31:0] address,
        input logic [2:0] size,
        input logic [1:0] burst
    );
        next_address = (burst == 2'b01) ? address + (32'd1 << size) : address;
    endfunction

    assign s_axi.awready = resetn && !w_active && !b_valid;
    assign s_axi.wready  = resetn && w_active && !b_valid;
    assign s_axi.bid     = b_id;
    assign s_axi.bresp   = 2'b00;
    assign s_axi.bvalid  = b_valid;

    assign s_axi.arready = resetn && !r_active;
    assign s_axi.rid     = r_id;
    assign s_axi.rdata   = r_data_q;
    assign s_axi.rresp   = 2'b00;
    assign s_axi.rlast   = r_active && (r_beat == r_len);
    assign s_axi.rvalid  = r_valid;

    assign w_fire = s_axi.wvalid && s_axi.wready;
    assign r_fire = s_axi.rvalid && s_axi.rready;
    assign read_capture = r_active && !r_valid;
    assign w_byte = s_axi.wdata[w_addr[1:0]*8 +: 8];

    always_comb begin
        tx_push      = 1'b0;
        tx_push_data = w_byte;
        rx_push      = 1'b0;
        rx_push_data = s_axi.wdata[7:0];
        rx_pop       = 1'b0;
        tx_pop_count = 3'd0;
        tx_clear     = 1'b0;
        rx_clear     = 1'b0;

        if (w_fire && s_axi.wstrb[w_addr[1:0]]) begin
            if ((w_addr & 32'hfffffff8) == 32'h1fe001e0) begin
                if (w_addr[2:0] == 3'd0 && !lcr[7])
                    tx_push = (tx_count < TX_DEPTH);
                if (w_addr[2:0] == 3'd2) begin
                    rx_clear = w_byte[1];
                    tx_clear = w_byte[2];
                end
            end else if (w_addr[31:16] == 16'h1fe1) begin
                if (w_addr[15:0] == 16'h0000) begin
                    tx_clear = s_axi.wdata[1];
                    rx_clear = s_axi.wdata[2];
                end
                if (w_addr[15:0] == 16'h0008 && rx_count < RX_DEPTH)
                    rx_push = 1'b1;
            end
        end

        // Consume FIFO entries when the accepted read is captured. This
        // keeps both data and side effects stable during arbitrary RREADY
        // backpressure, including concurrent FIFO clears or new RX/TX data.
        if (read_capture) begin
            if ((r_addr & 32'hfffffff8) == 32'h1fe001e0 &&
                r_addr[2:0] == 3'd0 && !lcr[7])
                rx_pop = (rx_count != 0);
            if (r_addr[31:16] == 16'h1fe1 && r_addr[15:0] == 16'h0004)
                tx_pop_count = (tx_count != 0) ? 3'd1 : 3'd0;
            if (r_addr[31:16] == 16'h1fe1 && r_addr[15:12] == 4'h1) begin
                if      (tx_count >= 4) tx_pop_count = 3'd4;
                else if (tx_count == 3) tx_pop_count = 3'd3;
                else if (tx_count == 2) tx_pop_count = 3'd2;
                else if (tx_count == 1) tx_pop_count = 3'd1;
            end
        end
    end

    always_comb begin
        case (r_addr[2:0])
            3'd0: uart_read_byte = lcr[7] ? dll :
                                      ((rx_count != 0) ? rx_mem[rx_rptr] : 8'hff);
            3'd1: uart_read_byte = lcr[7] ? dlm : ier;
            3'd2: begin
                if (ier[0] && rx_count != 0) uart_read_byte = {fcr[0], fcr[0], 4'b0, 2'b00} | 8'h04;
                else if (ier[1] && tx_count < TX_DEPTH) uart_read_byte = {fcr[0], fcr[0], 4'b0, 2'b00} | 8'h02;
                else uart_read_byte = {fcr[0], fcr[0], 5'b0, 1'b1};
            end
            3'd3: uart_read_byte = lcr;
            3'd4: uart_read_byte = mcr;
            3'd5: uart_read_byte = {1'b0, (tx_count == 0), (tx_count < TX_DEPTH),
                                     3'b000, fifo_overflow, (rx_count != 0)};
            3'd6: uart_read_byte = 8'hb0;
            3'd7: uart_read_byte = scr;
            default: uart_read_byte = 8'h00;
        endcase

        read_data = 32'b0;
        if ((r_addr & 32'hfffffff8) == 32'h1fe001e0) begin
            read_data = byte_on_lane(uart_read_byte, r_addr[1:0]);
        end else if (r_addr[31:16] == 16'h1fe1) begin
            case (r_addr[15:0])
                16'h0000: read_data = {tx_count, rx_count, 5'b0, cpu_halted, fifo_overflow,
                                        clk_locked, hbm_ready, cpu_run};
                16'h0004: read_data = {(tx_count != 0), 23'b0,
                                        (tx_count != 0) ? tx_mem[tx_rptr] : 8'h00};
                16'h0008: read_data = 32'b0;
                16'h000c: read_data = {10'b0, rx_count, tx_count};
                16'h0010: read_data = 32'h54324801; // Tick2h SoC, ABI revision 1
                16'h0014: read_data = {16'd225, 16'd100}; // HBM AXI / CPU MHz
                default: begin
                    if (r_addr[15:12] == 4'h1) begin
                        read_data[7:0]   = (tx_count >= 1) ? tx_mem[tx_rptr] : 8'h00;
                        read_data[15:8]  = (tx_count >= 2) ? tx_mem[tx_rptr + 12'd1] : 8'h00;
                        read_data[23:16] = (tx_count >= 3) ? tx_mem[tx_rptr + 12'd2] : 8'h00;
                        read_data[31:24] = (tx_count >= 4) ? tx_mem[tx_rptr + 12'd3] : 8'h00;
                    end
                end
            endcase
        end
    end

    assign irq = (ier[0] && rx_count != 0) || (ier[1] && tx_count < TX_DEPTH);

    always_ff @(posedge clk) begin
        if (!resetn) begin
            w_active <= 1'b0;
            b_valid  <= 1'b0;
            b_id     <= 4'b0;
            w_addr   <= 32'b0;
            w_id     <= 4'b0;
            w_len    <= 8'b0;
            w_beat   <= 8'b0;
            w_size   <= 3'b0;
            w_burst  <= 2'b0;
            r_active <= 1'b0;
            r_addr   <= 32'b0;
            r_id     <= 4'b0;
            r_len    <= 8'b0;
            r_beat   <= 8'b0;
            r_size   <= 3'b0;
            r_burst  <= 2'b0;
            r_valid  <= 1'b0;
            r_data_q <= 32'b0;
        end else begin
            if (s_axi.awvalid && s_axi.awready) begin
                w_active <= 1'b1;
                w_addr   <= s_axi.awaddr;
                w_id     <= s_axi.awid;
                w_len    <= s_axi.awlen;
                w_beat   <= 8'b0;
                w_size   <= s_axi.awsize;
                w_burst  <= s_axi.awburst;
            end
            if (w_fire) begin
                if (s_axi.wlast || w_beat == w_len) begin
                    w_active <= 1'b0;
                    b_valid  <= 1'b1;
                    b_id     <= w_id;
                end else begin
                    w_beat <= w_beat + 8'd1;
                    w_addr <= next_address(w_addr, w_size, w_burst);
                end
            end
            if (s_axi.bvalid && s_axi.bready)
                b_valid <= 1'b0;

            if (s_axi.arvalid && s_axi.arready) begin
                r_active <= 1'b1;
                r_addr   <= s_axi.araddr;
                r_id     <= s_axi.arid;
                r_len    <= s_axi.arlen;
                r_beat   <= 8'b0;
                r_size   <= s_axi.arsize;
                r_burst  <= s_axi.arburst;
            end
            if (read_capture) begin
                r_data_q <= read_data;
                r_valid <= 1'b1;
            end
            if (r_fire) begin
                r_valid <= 1'b0;
                if (r_beat == r_len) begin
                    r_active <= 1'b0;
                end else begin
                    r_beat <= r_beat + 8'd1;
                    r_addr <= next_address(r_addr, r_size, r_burst);
                end
            end
        end
    end

    always_ff @(posedge clk) begin
        if (!resetn) begin
            cpu_run       <= 1'b0;
            dll           <= 8'b0;
            dlm           <= 8'b0;
            ier           <= 8'b0;
            fcr           <= 8'b0;
            lcr           <= 8'b0;
            mcr           <= 8'b0;
            scr           <= 8'b0;
            fifo_overflow <= 1'b0;
        end else if (w_fire && s_axi.wstrb[w_addr[1:0]]) begin
            if ((w_addr & 32'hfffffff8) == 32'h1fe001e0) begin
                case (w_addr[2:0])
                    3'd0: if (lcr[7]) dll <= w_byte;
                          else if (tx_count == TX_DEPTH) fifo_overflow <= 1'b1;
                    3'd1: if (lcr[7]) dlm <= w_byte;
                          else ier <= w_byte & 8'h0f;
                    3'd2: fcr <= w_byte;
                    3'd3: lcr <= w_byte;
                    3'd4: mcr <= w_byte;
                    3'd7: scr <= w_byte;
                    default: ;
                endcase
            end else if (w_addr[31:16] == 16'h1fe1 && w_addr[15:0] == 16'h0000) begin
                cpu_run <= s_axi.wdata[0];
                if (s_axi.wdata[3]) fifo_overflow <= 1'b0;
            end
        end
    end

    always_ff @(posedge clk) begin
        if (!resetn) begin
            tx_wptr  <= 12'b0;
            tx_rptr  <= 12'b0;
            tx_count <= 13'b0;
            rx_wptr  <= 8'b0;
            rx_rptr  <= 8'b0;
            rx_count <= 9'b0;
        end else begin
            if (tx_clear) begin
                tx_wptr  <= 12'b0;
                tx_rptr  <= 12'b0;
                tx_count <= 13'b0;
            end else begin
                if (tx_push) begin
                    tx_mem[tx_wptr] <= tx_push_data;
                    tx_wptr <= tx_wptr + 12'd1;
                end
                if (tx_pop_count != 0)
                    tx_rptr <= tx_rptr + tx_pop_count;
                tx_count <= tx_count + (tx_push ? 13'd1 : 13'd0) - tx_pop_count;
            end

            if (rx_clear) begin
                rx_wptr  <= 8'b0;
                rx_rptr  <= 8'b0;
                rx_count <= 9'b0;
            end else begin
                if (rx_push) begin
                    rx_mem[rx_wptr] <= rx_push_data;
                    rx_wptr <= rx_wptr + 8'd1;
                end
                if (rx_pop)
                    rx_rptr <= rx_rptr + 8'd1;
                rx_count <= rx_count + (rx_push ? 9'd1 : 9'd0) - (rx_pop ? 9'd1 : 9'd0);
            end
        end
    end
endmodule
