"""Wire a native GPU endpoint to each enabled HBM stack in a staged board tree."""
from pathlib import Path
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


def hbm_pins(stack):
    bus = f"gpu_pc{stack}"
    prefix = f"AXI_{1 + stack*16:02}_"
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
                    value = "{2'b0, " + value + "}"
                else:
                    value = f"gpu_{ch}id{stack}"
            ports.append(((ch+field).upper(), value))
    return "".join(f"        .{prefix}{name}({value}),\n" for name, value in ports)


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
        # CPU/JTAG use global addressing on SAXI00. No AXI burst can cross a 4 KiB page.
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
        for ch in ("ar", "aw"):
            replace(bridge, "{1'b0, pc_"+ch+"addr[31:5]", "{stack_for_host(pc_"+ch+"addr), pc_"+ch+"addr[31:5]")
        replace(bridge, "(hbm_cattrip)", "(trip0)")
        replace(bridge, "(hbm_temp)", "(temp0)")
        replace(bridge, "hbm_ready = apb_complete;", "hbm_ready = apb_complete && init1;")
        replace(project, "CONFIG.USER_HBM_DENSITY {4GB}", "CONFIG.USER_HBM_DENSITY {8GB} CONFIG.USER_SWITCH_ENABLE_00 {true} CONFIG.USER_SWITCH_ENABLE_01 {true} CONFIG.USER_AXI_CLK1_FREQ {225}")
    replace(project, 'lappend hbm_cfg [format "CONFIG.USER_SAXI_%02d" $i] {false}',
            'lappend hbm_cfg [format "CONFIG.USER_SAXI_%02d" $i] [expr {$i in {' +
            ("1 17" if ports == 2 else "1") + '}}]')
    replace(project, "create_ip -name clk_wiz", "source [file join [file dirname [info script]] gpu_axi_ip.tcl]\ncreate_ip -name clk_wiz")
    replace(project, "add_files -norecurse $generated_rtl", "add_files -norecurse [file join $rtl_dir gpu_hbm_path.sv]\nadd_files -norecurse $generated_rtl")
    replace(project, "4GB/SAXI00/", ("8GB/SAXI00+01+17/" if ports == 2 else "4GB/SAXI00+01/"))
