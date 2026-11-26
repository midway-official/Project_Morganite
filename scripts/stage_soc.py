#!/usr/bin/env python3
"""Package the frozen Aurora board project and the elaborated Morganite attachment."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

from wide_board import integrate
from prepare_baseline import BASE, ROOT, prepare


def replace_once(path, before, after):
    content = path.read_text()
    if content.count(before) != 1:
        raise ValueError(f"unexpected frozen source in {path}: {before}")
    path.write_text(content.replace(before, after))


def instantiate(wide_ports=0):
    ports = [".clock(clk_cpu)", ".reset(!resetn_cpu)"]
    fields = {"ar": "addr id len size burst lock cache prot qos", "aw": "addr id len size burst lock cache prot qos",
              "w": "data strb last", "b": "id resp", "r": "id data resp last"}
    bundles = [("cpuMemory", "host_mem"), ("peripheral", "host_io"), ("hbm", "mem_axi"), ("legacy", "periph_axi")]
    bundles.extend((("gpuHbm" if i == 0 else "gpuHbm1"), f"gpu_mem{i}") for i in range(wide_ports))
    for bundle, bus in bundles:
        for channel, payload in fields.items():
            for handshake in ["ready", "valid"]:
                ports.append(f".io_{bundle}_{channel}_{handshake}({bus}.{channel}{handshake})")
            for name in payload.split():
                ports.append(f".io_{bundle}_{channel}_bits_{name}({bus}.{channel}{name})")
    return "    MorganiteBoard u_gpu_soc (\n        " + ",\n        ".join(ports) + "\n    );\n\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    topology = parser.add_mutually_exclusive_group()
    topology.add_argument("--sms", type=int)
    topology.add_argument("--grid", metavar="MxN", help="SM and memory-tree dimensions, e.g. 4x4 or 2x8")
    parser.add_argument("--units", nargs="+", choices=("setup","bins","raster","rop","sfu"), default=[])
    parser.add_argument("--accelerators", action="store_true",
                        help="enable configured I/D L1, INT8 K=4, and optimized issue/RF")
    parser.add_argument("--wide-ports", type=int, choices=(1, 2), default=0,
                        help="dedicated 256-bit endpoints, one per HBM stack")
    args = parser.parse_args()
    if args.grid:
        match = re.fullmatch(r"([1-9][0-9]*)x([1-9][0-9]*)", args.grid)
        if not match:
            parser.error("--grid must be RowsxCols")
        rows, cols = map(int, match.groups())
        args.sms = rows*cols
    else:
        args.sms = 2 if args.sms is None else args.sms
        rows, cols = (args.sms//4,4) if args.sms >= 4 and args.sms%4 == 0 else (1,args.sms)
    if not 1 <= args.sms <= 32:
        parser.error("the dispatch ABI supports 1..32 SMs")
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    frozen = ROOT / "build/baseline"
    manifest = prepare(frozen, ROOT / ".worktrees/aurora")
    # Stage the board files named in the verified baseline manifest.
    for line in (BASE / "files.sha256").read_text().splitlines():
        _, name = line.split("  ", 1)
        if name.startswith("server/fpga/"):
            dest = out / Path(name).relative_to("server")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(BASE / name, dest)
    rtl = out / "fpga/u50c/rtl"
    project = out / "fpga/u50c/scripts/create_project.tcl"
    inputs = out / "inputs"
    (inputs / "generated").mkdir(parents=True)
    shutil.copy2(frozen / "generated/Aurora.v", inputs / "generated")
    with zipfile.ZipFile(BASE / "vendor/au50_boardfiles_v1_3_20211104.zip") as board_files:
        board_files.extractall(inputs / "board_repo")
    ic_bytes, dc_bytes = (4096, 8192) if args.sms == 32 else (2048, 2048)
    extra = f" ic={ic_bytes} dc={dc_bytes} line=8 tensor=4 bramRf=1 earlyBranch=1" if args.accelerators else ""
    units = sorted(set(args.units))
    extra += "".join(f" {unit}=1" for unit in units)
    if args.wide_ports:
        extra += f" dmaBits=256 reads=8 writes=4 ports={args.wide_ports}"
    rtl_dir = f"build/board-rtl-{rows}x{cols}" + (f"-wide{args.wide_ports}" if args.wide_ports else "") + "".join(f"-{name}" for name in units)
    subprocess.run(["bash", "-c", f'source scripts/toolchain.sh && sbt "runMain morganite.Generate MorganiteBoard {rtl_dir} {rows}x{cols} 8 4{extra}"'],
                   cwd=ROOT, check=True)
    gpu = ROOT / rtl_dir / "MorganiteBoard.sv"
    cpu = inputs / "generated/Aurora.v"
    modules = lambda p: set(re.findall(r"^module\s+(\w+)", p.read_text(), re.M))
    if modules(gpu) & modules(cpu):
        raise ValueError("duplicate CPU/GPU module names")
    shutil.copy2(gpu, rtl)
    top = rtl / "u50c_top.sv"
    replace_once(top, "    axi_arbiter_2m2s u_fabric", "    axi4_if host_mem();\n    axi4_if host_io();\n\n    axi_arbiter_2m2s u_fabric")
    replace_once(top, ".mem    (mem_axi),\n        .periph (periph_axi)", ".mem    (host_mem),\n        .periph (host_io)")
    replace_once(top, "    hbm_axi_bridge u_memory", instantiate(args.wide_ports) + "    hbm_axi_bridge u_memory")
    replace_once(project, "add_files -norecurse $generated_rtl", "add_files -norecurse [file join $rtl_dir MorganiteBoard.sv]\nadd_files -norecurse $generated_rtl")
    replace_once(project, "report_timing_summary -file [file join $build_dir timing_impl.rpt]",
                 "report_timing_summary -delay_type min_max -report_unconstrained -file [file join $build_dir timing_impl.rpt]\n"
                 "report_exceptions -file [file join $build_dir exceptions_impl.rpt]\n"
                 "report_methodology -file [file join $build_dir methodology_impl.rpt]\n"
                 "report_utilization -hierarchical -file [file join $build_dir hierarchy_impl.rpt]")
    replace_once(project, "CANDIDATE=Tick2h", f"CANDIDATE=Morganite_{args.sms}SM")
    if args.accelerators:
        replace_once(project, "report_utilization -file [file join $build_dir utilization_synth.rpt]",
                     "report_utilization -hierarchical -file [file join $build_dir hierarchy_synth.rpt]\n"
                     "report_utilization -file [file join $build_dir utilization_synth.rpt]\n"
                     "if {[info exists ::env(MORGANITE_SYNTH_ONLY)] && $::env(MORGANITE_SYNTH_ONLY) eq {1}} {close_project; exit}")
    if args.wide_ports:
        integrate(rtl, project, args.wide_ports, replace_once)
    shutil.copy2(ROOT / "board/run_soc.sh", out)
    manifest.update(gpu_commit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    gpu_config={"sms": args.sms, "sm_rows": rows, "sm_cols": cols, "graphics_units": units,
                                "lanes": 4, "warp_width": 8, "warp_slots": 4, "registers": 32,
                                "scratch_bytes": 8192, "dma_bits": 32, "gpu_mhz": 100},
                    gpu_rtl=hashlib.sha256(gpu.read_bytes()).hexdigest())
    if args.accelerators:
        manifest["gpu_config"].update(ic_bytes=ic_bytes, dc_bytes=dc_bytes, line_words=8, tensor_k=4,
                                      bram_rf=True, early_branch=True)
    if args.wide_ports:
        manifest["gpu_config"].update(dma_bits=256, hbm_ports=args.wide_ports, read_ids_per_port=8,
                                      write_ids_per_port=4, stripe_bytes=4096 if args.wide_ports == 2 else 0)
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2)+"\n")
    inputs_list = sorted(p for p in out.rglob("*") if p.is_file())
    (out / "files.sha256").write_text("".join(
        f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(out)}\n" for p in inputs_list))
