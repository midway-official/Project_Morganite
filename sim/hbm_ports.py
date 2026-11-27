"""Expand the frozen byte-accurate AXI3 test model to a shared, 33-bit address space."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
source = (root / "baseline/platform/fpga/u50c/tests/hbm_port_model.sv").read_text()
header, body = source.split("\n);", 1)
pins = header[header.index("    input logic HBM_REF_CLK_0"):].splitlines()
common = body[body.index("    function automatic"):body.index("    int unsigned cycles")]
variables = body[body.index("    logic [32:0] write_addr"):body.index("    function automatic")]
apb = body[body.index("    always @(posedge APB_0_PCLK)"):body.index("    always @(posedge AXI_00_ACLK)")]
axi = body[body.index("    always @(posedge AXI_00_ACLK)"):body.index("endmodule")]
assigns = [s for s in body.splitlines() if s.startswith("    assign ")]
stack = lambda text: text.replace("APB_0", "APB_1").replace("apb_complete_0", "apb_complete_1").replace("DRAM_0", "DRAM_1").replace("REF_CLK_0", "REF_CLK_1")
ports = pins + [stack(s) for s in pins if "AXI_00" not in s]
for n in ("01", "16", "17"):
    ports.extend(s.replace("AXI_00", "AXI_"+n) for s in pins if "AXI_00" in s)
output = ["`timescale 1ns/1ps", "module hbm_0(", ",\n".join(s.rstrip(",") for s in ports), ");",
          "    logic [7:0] memory_bytes [longint unsigned];", common]
for n in ("00", "01", "16", "17"):
    local = "\n".join([variables, "    int unsigned cycles=0;", *[s for s in assigns if "AXI_00" in s], axi])
    output += ["    if (1) begin : port_"+n, local.replace("AXI_00","AXI_"+n),"    end"]
for n in range(2):
    local = "\n".join(["    int unsigned apb_cycles=0;", *[s for s in assigns if "AXI_00" not in s], apb])
    output += [f"    if (1) begin : stack_{n}", stack(local) if n else local, "    end"]
output.append("endmodule")
Path(sys.argv[1]).write_text("\n".join(output)+"\n")
