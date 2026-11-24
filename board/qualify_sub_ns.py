"""Qualify a routed board-test BIT after the standard implementation flow reports negative slack."""

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


def check_reports(folder):
    timing = (folder / "timing_impl.rpt").read_text().split("| Design Timing Summary", 1)[1]
    row = next(line.split() for line in timing.splitlines() if re.fullmatch(r"(?:\s+-?[\d.]+){12}\s*", line))
    for offset in (0, 4, 8):
        slack, total, failed, endpoints = map(float, row[offset:offset + 4])
        if (endpoints < 1 or failed > endpoints or
                (offset < 8 and slack <= -1) or (offset == 8 and slack < 0) or
                (failed == 0 and (slack < 0 or total != 0)) or
                (failed > 0 and (slack >= 0 or total >= 0))):
            raise ValueError("Timing outside the board-test limits: " + " ".join(row))
    for clock, rate in (("clk_out1_clk_wiz_0", "100.000"), ("clk_out2_clk_wiz_0", "225.000")):
        if not re.search(r"(?m)^\s*" + clock + r"\s+\{[^}]+\}\s+[\d.]+\s+" + re.escape(rate) + r"\s*$", timing):
            raise ValueError("Clock frequency mismatch: " + clock)
    route = (folder / "route_impl.rpt").read_text()
    def nets(label):
        return int(re.search(re.escape(label) + r"\.+\s*:\s*(\d+)", route)[1])
    if nets("nets with routing errors") or not 0 < nets("routable nets") == nets("fully routed nets"):
        raise ValueError("Route incomplete")
    checks = (folder / "check_timing_impl.rpt").read_text()
    for item in ("no_clock", "unconstrained_internal_endpoints"):
        if "checking " + item + " (0)" not in checks:
            raise ValueError("Unconstrained endpoints: " + item)
    cdc = (folder / "cdc_impl.rpt").read_text().splitlines()
    crossings = [line.split()[-5:] for line in cdc if line.startswith(("Warning ", "Info ", "Critical ", "Error "))]
    if not crossings or any(int(v[0]) != int(v[1]) or list(map(int, v[2:])) != [0, 0, 0] for v in crossings):
        raise ValueError("CDC report needs review")
    drc = (folder / "drc_impl.rpt").read_text()
    if ("Design State : Fully Routed" not in drc or "Violations found:" not in drc or
            re.search(r"\|\s*(?:Error|Critical Warning)\s*\|", drc, re.I)):
        raise ValueError("DRC report incomplete or has errors or critical warnings")
    return dict(zip(("WNS", "TNS", "SETUP_FAILING", "SETUP_TOTAL", "WHS", "THS",
                     "HOLD_FAILING", "HOLD_TOTAL", "WPWS", "TPWS", "PW_FAILING", "PW_TOTAL"), row))


def qualify(build, vivado):
    impl = build / "implementation"
    run = impl / "vivado/aurora_u50c.runs/impl_1"
    timing = check_reports(impl)
    bit = run / "u50c_top.bit"
    if not (run / ".write_bitstream.end.rst").exists() or not bit.stat().st_size:
        raise ValueError("BIT generation did not complete")
    checkpoint = run / "u50c_top_postroute_physopt.dcp"
    if not checkpoint.exists():
        checkpoint = run / "u50c_top_routed.dcp"
    probes = impl / "u50c_top.ltx"
    subprocess.run([vivado, "-mode", "batch", "-notrace", "-log", impl / "test_probes.log",
                    "-nojournal", "-source", Path(__file__).with_name("export_test_probes.tcl"),
                    "-tclargs", checkpoint, probes], cwd=impl, check=True)
    if not probes.stat().st_size:
        raise ValueError("Missing probes file")
    config = json.loads((build / "manifest.json").read_text())["gpu_config"]
    mode = "SUB_NS_BOARD_TEST" if min(float(timing[k]) for k in ("WNS", "WHS")) < 0 else "TIMING_MET"
    manifest = dict(CANDIDATE=f"Morganite_{config['sms']}SM", TIMING_STATUS=mode, CPU_MHZ=100,
                    HBM_AXI_MHZ=225, DRC_ERROR_CRITICAL_COUNT=0, BITSTREAM=bit, **timing)
    marker = impl / "BOARD_TEST_BUILD.txt"
    marker.write_text("".join(f"{key}={value}\n" for key, value in manifest.items()))
    hashes = impl / "program.sha256.tmp"
    hashes.write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(build)}\n"
                              for p in (bit, probes)))
    hashes.replace(impl / "program.sha256")
    print(marker.read_text(), end="")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--vivado", default="vivado")
    args = parser.parse_args()
    qualify(args.build.resolve(), args.vivado)
