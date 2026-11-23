#!/usr/bin/env python3
"""Submit one identified routed build and kernel to the existing U50C manager."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("build", type=Path)
    p.add_argument("software", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--manager", type=Path, default=Path.home()/"aurora-u50c-debug")
    args = p.parse_args()
    build, software, run = (x.resolve() for x in (args.build, args.software, args.output))
    impl = build/"implementation"
    complete = dict(line.split("=",1) for line in (impl/"BUILD_COMPLETE.txt").read_text().splitlines())
    if (complete["CANDIDATE"] != "Morganite_2SM" or float(complete["WNS"]) < 0 or float(complete["WHS"]) < 0
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
    first, limit = layout["dma_window"]
    ranges = [(start,start+size) for start,size in layout["segments"]] + layout["zero_ranges"]
    for start,end in ranges:
        if start < first or end > limit:
            raise ValueError("image segment or BSS is outside the documented Linux RAM")
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
