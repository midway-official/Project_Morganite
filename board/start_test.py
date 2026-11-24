#!/usr/bin/env python3
"""Submit one identified routed build and kernel to the existing U50C manager."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
from memory_layout import image_ranges, validate_buffer


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("build", type=Path)
    p.add_argument("software", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--manager", type=Path, default=Path.home()/"aurora-u50c-debug")
    p.add_argument("--allow-sub-ns", action="store_true", help="allow setup and hold slack strictly above -1 ns for board testing")
    args = p.parse_args()
    build, software, run = (x.resolve() for x in (args.build, args.software, args.output))
    impl = build/"implementation"
    build_manifest = json.loads((build/"manifest.json").read_text())
    sms = build_manifest["gpu_config"]["sms"]
    software_manifest = json.loads((software/"software.json").read_text())
    gpu_config = build_manifest["gpu_config"]
    expected_features = ((1 if gpu_config.get("ic_bytes", 0) else 0) |
                         (2 if gpu_config.get("dc_bytes", 0) else 0) |
                         (gpu_config.get("tensor_k", 0) << 8))
    if (software_manifest.get("gpu_config", {"sms": 2})["sms"] != sms or
            software_manifest.get("gpu_features", 0) != expected_features):
        raise ValueError("software GPU configuration does not match the routed build")
    candidate = f"Morganite_{sms}SM"
    marker = impl/"BUILD_COMPLETE.txt"
    if args.allow_sub_ns and not marker.exists():
        marker = impl/"BOARD_TEST_BUILD.txt"
    complete = dict(line.split("=",1) for line in marker.read_text().splitlines())
    slacks = [float(complete[key]) for key in ("WNS", "WHS")]
    allowed = all(math.isfinite(s) and (s >= 0 or (args.allow_sub_ns and s > -1)) for s in slacks)
    if (complete["CANDIDATE"] != candidate or not allowed
            or complete["DRC_ERROR_CRITICAL_COUNT"] != "0"):
        raise ValueError("routed candidate did not pass the implementation checks")
    bit = impl/"vivado/aurora_u50c.runs/impl_1/u50c_top.bit"
    probes = impl/"u50c_top.ltx"
    if Path(complete["BITSTREAM"]).resolve() != bit:
        raise ValueError("BUILD_COMPLETE bitstream path mismatch")
    subprocess.run(["sha256sum","--quiet","-c",impl/"program.sha256"],cwd=build,check=True)
    subprocess.run(["sha256sum","--quiet","-c","files.sha256"],cwd=software,check=True)
    image = software/"ram.dat"
    layout = json.loads((software/"image.json").read_text())
    if sha(image) != layout["sha256"]:
        raise ValueError("kernel image SHA mismatch")
    image_ranges(layout)
    if "dma_api" not in layout:
        first, limit = layout["dma_window"]
        validate_buffer(layout, first, limit - first)
    control = [sys.executable,str(software/"runtime/remote_debug_session.py"),str(args.manager)]
    status = json.loads(subprocess.check_output(control+["status"]))
    previous = status.get("request",{})
    if status.get("worker_alive"):
        raise RuntimeError("existing board worker in progress")
    if status.get("bridge_alive") and (status.get("heartbeat",{}).get("CLIENTS") != "0" or
            status.get("heartbeat_age_seconds",999)>10 or
            not str(Path(previous.get("bit","")).resolve()).startswith(str(build.parent)+"/") or
            previous.get("bit_sha256") not in {sha(bit), "8bc4c4fffeb9c4d59b557a994e1a7784f48daff0d990d78240f185b37e3c246e"}):
        raise RuntimeError("board bridge ownership or heartbeat changed")
    request = dict(image_format="dat",load_address=None,server="127.0.0.1:3122",uart_port=23251,
                   vivado_root=str(Path.home()/"Xilinx/Vivado/2023.2"),program=True,hbm_test=True,
                   zero_ranges=layout["zero_ranges"])
    for key, path in (("bit",bit),("ltx",probes),("image",image)):
        request[key], request[key+"_sha256"] = str(path), sha(path)
    run.mkdir(parents=True,exist_ok=False)
    def record(name, data):
        (run/(name+".json")).write_text(json.dumps(data,indent=2)+"\n")
    record("request",request)
    record("before",status)
    record("build",json.loads((build/"manifest.json").read_text()))
    record("software",json.loads((software/"software.json").read_text()))
    record("image",layout)
    record("physical",complete)
    launch = json.loads(subprocess.check_output(control+["start"],input=json.dumps(request).encode()))
    if any(launch["request"][k] != request[k] for k in request):
        raise RuntimeError("actual submitted request differs from verified inputs")
    record("launch",launch)
    print(launch["run_dir"],flush=True)


if __name__ == "__main__":
    main()
