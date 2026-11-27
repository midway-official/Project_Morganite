"""Wire a native GPU endpoint to each enabled HBM stack in a staged board tree."""
from pathlib import Path
import re
import shutil

AXI = {
    "aw": "id addr len size burst lock cache prot qos valid ready",
    "w": "data strb last valid ready", "b": "id resp valid ready",
    "ar": "id addr len size burst lock cache prot qos valid ready",
    "r": "id data resp last valid ready",
}


def gpu_bridge():
    def instance(name, sides, clocks):
        ports = list(clocks)
        for prefix, bus in sides:
            for ch, fields in AXI.items():
                for field in fields.split():
                    pin = ch + field
                    wire = f"{bus}.{pin}"
                    if prefix == "m" and bus == "pc" and ch in ("ar", "aw"):
                        if field in ("lock", "cache", "qos", "prot"):
                            continue
                        if field == "len":
                            wire += "[3:0]"
                    ports.append(f".{prefix}_axi_{pin}({wire})")
                if ch in ("aw", "ar") and (prefix == "s" or bus == "cdc"):
                    wire = "4'b0" if prefix == "s" else ""
                    ports.append(f".{prefix}_axi_{ch}region({wire})")
        return name + "(\n        " + ",\n        ".join(ports) + "\n    );"
    clock = instance("gpu_axi_cdc u_cdc", [("s", "src"), ("m", "cdc")],
                     [".s_axi_aclk(clk_cpu)", ".s_axi_aresetn(resetn_cpu)",
                      ".m_axi_aclk(clk225)", ".m_axi_aresetn(resetn225)"])
    protocol = instance("gpu_axi_protocol u_protocol", [("s", "cdc"), ("m", "pc")],
                        [".aclk(clk225)", ".aresetn(resetn225)"])
    return f'''`include "axi4_if.svh"
module gpu_hbm_path(input logic clk_cpu, resetn_cpu, clk225, resetn225,
                    axi4_if.slave src, axi4_if.master pc);
    axi4_if #(.DATA_WIDTH(256)) cdc();
    {clock}
    {protocol}
    assign pc.arlen[7:4] = 0;
    assign pc.awlen[7:4] = 0;
endmodule
'''


def hbm_pins(stack, host=False):
    bus = "host1" if host else f"gpu_pc{stack}"
    prefix = f"AXI_{int(not host) + stack*16:02}_"
    ports = [("ACLK", "clk225"), ("ARESET_N", "resetn225"),
             ("WDATA_PARITY", "32'b0"), ("RDATA_PARITY", "")]
    for ch, fields in AXI.items():
        for field in fields.split():
            if field in ("lock", "cache", "qos", "prot"):
                continue
            value = f"{bus}.{ch}{field}"
            if field == "addr":
                value = "{" + f"1'b{stack}, " + value + "}"
            elif field == "len":
                value += "[3:0]"
            elif field == "id":
                if ch in ("ar", "aw"):
                    value = "6'b0" if host else "{2'b0, " + value + "}"
                else:
                    value = "" if host else f"gpu_{ch}id{stack}"
            ports.append(((ch+field).upper(), value))
    return "".join(f"        .{prefix}{name}({value}),\n" for name, value in ports)


def split_host(bridge, replace):
    # The original ID-less host path is serialized in both directions by its fabric.
    # Gate each address until the previous response and retain AW ownership through B.
    wires = """
    axi4_if #(.DATA_WIDTH(256)) host0(), host1();
    logic read_bank, write_bank, read_active, write_active;
    wire ar_bank = stack_for_host(pc_araddr);
    wire aw_bank = stack_for_host(pc_awaddr);
    always_ff @(posedge clk225) begin
        if (!resetn225) begin
            read_active <= 0; write_active <= 0; read_bank <= 0; write_bank <= 0;
        end else begin
            if (pc_arvalid && pc_arready) begin read_active <= 1; read_bank <= ar_bank; end
            if (pc_rvalid && pc_rready && pc_rlast) read_active <= 0;
            if (pc_awvalid && pc_awready) begin write_active <= 1; write_bank <= aw_bank; end
            if (pc_bvalid && pc_bready) write_active <= 0;
        end
    end
"""
    assigns = []
    for ch, fields in AXI.items():
        addr = ch in ("ar","aw")
        active = "read_active" if ch in ("ar","r") else "write_active"
        bank = ch+"_bank" if addr else ("read_bank" if ch == "r" else "write_bank")
        for field in fields.split():
            if field in ("lock","cache","qos","prot","id"):
                continue
            pin = ch+field
            # Change only the HBM00 pin; preserve the PC-to-host converter ports.
            legacy = "{1'b0, pc_"+pin+"[31:5], 5'b0}" if field=="addr" else "pc_"+pin
            mapped = "{1'b0, host0."+pin+"[31:5], 5'b0}" if field=="addr" else "host0."+pin
            if field=="len":
                mapped += "[3:0]"
            expr = re.compile(r"(\.AXI_00_"+pin.upper()+r"\s*\()"+re.escape(legacy)+r"\)")
            match = expr.search(bridge.read_text())
            if not match:
                raise ValueError("missing HBM port "+pin)
            replace(bridge,match[0],match[1]+mapped+")")
            forward = (ch in ("ar","aw","w")) != (field == "ready")
            if forward:
                for i in range(2):
                    gate = f" && {bank} == 1'b{i} && {'!' if addr else ''}{active}" if field in ("valid","ready") else ""
                    assigns.append(f"    assign host{i}.{pin} = pc_{pin}{gate};")
            else:
                mux = f"({bank} ? host1.{pin} : host0.{pin})"
                gate = f"{'!' if addr else ''}{active} && " if field in ("valid","ready") else ""
                assigns.append(f"    assign pc_{pin} = {gate}{mux};")
    replace(bridge,"    hbm_0 u_hbm (",wires+"\n".join(assigns)+"\n    hbm_0 u_hbm (\n"+hbm_pins(1,host=True).rstrip())


def integrate(rtl, project, ports, replace):
    board = Path(__file__).resolve().parents[1] / "board"
    shutil.copy2(board / "gpu_axi_ip.tcl", project.parent)
    (rtl / "gpu_hbm_path.sv").write_text(gpu_bridge())
    top, bridge = rtl / "u50c_top.sv", rtl / "hbm_axi_bridge.sv"
    replace(top, "    axi4_if host_mem();", "".join(
        f"    axi4_if #(.DATA_WIDTH(256)) gpu_mem{i}();\n" for i in range(ports)) + "    axi4_if host_mem();")
    replace(top, "        .s_axi       (mem_axi)", "".join(
        f"        .gpu{i}(gpu_mem{i}),\n" for i in range(ports)) + "        .s_axi       (mem_axi)")
    replace(bridge, "    axi4_if.slave      s_axi", "".join(f"    axi4_if.slave gpu{i},\n" for i in range(ports)) +
            "    axi4_if.slave      s_axi")
    blocks = []
    for i in range(ports):
        blocks.append(f"""    axi4_if #(.DATA_WIDTH(256)) gpu_pc{i}();
    logic [5:0] gpu_bid{i}, gpu_rid{i};
    assign gpu_pc{i}.bid = gpu_bid{i}[3:0];
    assign gpu_pc{i}.rid = gpu_rid{i}[3:0];
    gpu_hbm_path u_gpu_path{i}(.clk_cpu, .resetn_cpu, .clk225, .resetn225, .src(gpu{i}), .pc(gpu_pc{i}));
""")
    replace(bridge, "    hbm_0 u_hbm (", "".join(blocks) + "    hbm_0 u_hbm (\n" +
            "".join(hbm_pins(i) for i in range(ports)).rstrip())
    if ports == 2:
        # Keep CPU/JTAG on a host port local to the target stack.
        anchor = "    hbm_0 u_hbm ("
        replace(bridge, anchor, """    function automatic logic stack_for_host(input logic [31:0] addr);
        return addr >= 32'h01000000 && addr < 32'h1fe00000 && addr[12];
    endfunction
    logic init1, trip0, trip1;
    logic [6:0] temp0, temp1;
    assign hbm_cattrip = trip0 | trip1;
    assign hbm_temp = temp0 > temp1 ? temp0 : temp1;
""" + anchor + """
        .HBM_REF_CLK_1(clk100), .APB_1_PCLK(clk100), .APB_1_PRESET_N(resetn100),
        .APB_1_PWDATA(32'b0), .APB_1_PADDR(22'b0), .APB_1_PENABLE(1'b0),
        .APB_1_PSEL(1'b0), .APB_1_PWRITE(1'b0), .apb_complete_1(init1),
        .DRAM_1_STAT_CATTRIP(trip1), .DRAM_1_STAT_TEMP(temp1),""")
        split_host(bridge,replace)
        replace(bridge, "(hbm_cattrip)", "(trip0)")
        replace(bridge, "(hbm_temp)", "(temp0)")
        replace(bridge, "hbm_ready = apb_complete;", "hbm_ready = apb_complete && init1;")
        replace(project, "CONFIG.USER_HBM_DENSITY {4GB}", "CONFIG.USER_HBM_DENSITY {8GB} CONFIG.USER_SWITCH_ENABLE_00 {true} CONFIG.USER_SWITCH_ENABLE_01 {true} CONFIG.USER_AXI_CLK1_FREQ {225}")
    replace(project, 'lappend hbm_cfg [format "CONFIG.USER_SAXI_%02d" $i] {false}',
            'lappend hbm_cfg [format "CONFIG.USER_SAXI_%02d" $i] [expr {$i in {' +
            ("1 16 17" if ports == 2 else "1") + '}}]')
    replace(project, "create_ip -name clk_wiz", "source [file join [file dirname [info script]] gpu_axi_ip.tcl]\ncreate_ip -name clk_wiz")
    replace(project, "add_files -norecurse $generated_rtl", "add_files -norecurse [file join $rtl_dir gpu_hbm_path.sv]\nadd_files -norecurse $generated_rtl")
    replace(project, "4GB/SAXI00/", ("8GB/SAXI00+01+16+17/" if ports == 2 else "4GB/SAXI00+01/"))
