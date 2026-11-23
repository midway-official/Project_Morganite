#!/usr/bin/env python3
"""Run on the U50C VM in a fresh Morganite run directory."""
import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("run", type=Path)
parser.add_argument("--manager", type=Path, default=Path.home() / "aurora-u50c-debug")
args = parser.parse_args()
run = args.run.resolve()
manifest = json.loads((run / "inputs/manifest.json").read_text())
runtime = run / "runtime/remote_debug_session.py"
control = [sys.executable, str(runtime), str(args.manager)]
status = json.loads(subprocess.check_output(control + ["status"]))
if status.get("worker_alive") or (status.get("bridge_alive") and
        (status.get("heartbeat", {}).get("CLIENTS") != "0" or
         status.get("heartbeat_age_seconds", 999) > 10 or
         status.get("request", {}).get("bit_sha256") != manifest["sha256"]["u50c_top.bit"])):
    raise SystemExit("Board has an active client, another design, or an unfinished operation")

request = {"image_format": "dat", "load_address": None, "server": "127.0.0.1:3122",
           "uart_port": 23251, "vivado_root": str(Path.home() / "Xilinx/Vivado/2023.2"),
           "program": True, "hbm_test": True, "zero_ranges": manifest["zero_ranges"]}
for key, name in [("image", "ram.dat"), ("bit", "u50c_top.bit"), ("ltx", "u50c_top.ltx")]:
    path = run / "inputs" / name
    value = hashlib.sha256(path.read_bytes()).hexdigest()
    if value != manifest["sha256"][name]:
        raise ValueError("Uploaded artifact hash mismatch for " + name)
    request[key], request[key + "_sha256"] = str(path), value
(run / "observed-before.json").write_text(json.dumps(status, indent=2))
with (run / "request.json").open("x") as f:
    json.dump(request, f, indent=2)
reply = subprocess.check_output(control + ["start"], input=json.dumps(request).encode())
(run / "launch.json").write_bytes(reply)
print(reply.decode(), end="")
