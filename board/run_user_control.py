#!/usr/bin/env python3
"""Run the PLV3 copy control on the frozen CPU-only U50C build via its manager."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import socket
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("previous", type=Path, help="current Morganite run containing launch.json")
    parser.add_argument("image", type=Path, help="build_copy_bare.py --user-probes output")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    previous, image, out = [p.resolve() for p in (args.previous, args.image, args.output)]
    project = previous.parent
    manager = Path.home() / "aurora-u50c-debug"
    prior = json.loads((previous / "launch.json").read_text())
    runtime = project / "sw-inflate-replay"
    subprocess.run(["sha256sum", "--quiet", "-c", "files.sha256"], cwd=runtime, check=True)
    ctl = [sys.executable, str(runtime / "runtime/remote_debug_session.py"), str(manager)]
    before = json.loads(subprocess.check_output(ctl + ["status"]))
    if (before["run_dir"] != prior["run_dir"] or before["request"] != prior["request"] or
            before["phase"] != "running" or before["worker_alive"] or not before["bridge_alive"] or
            before["heartbeat"]["CLIENTS"] != "0" or before["heartbeat_age_seconds"] > 10):
        raise RuntimeError("board run or UART ownership changed")
    inputs = project / "inputs"
    frozen = json.loads((inputs / "manifest.json").read_text())
    layout = json.loads((image / "image.json").read_text())
    if (set(layout.get("user_elf_sha256", {})) != {"original", "nop", "dbar"} or
            frozen["sha256"]["u50c_top.bit"] != "8bc4c4fffeb9c4d59b557a994e1a7784f48daff0d990d78240f185b37e3c246e"):
        raise ValueError("unexpected CPU-only control configuration")
    request = dict(prior["request"], program=True, hbm_test=True, zero_ranges=layout["zero_ranges"])
    files = {"bit": inputs / "u50c_top.bit", "ltx": inputs / "u50c_top.ltx", "image": image / "ram.dat"}
    for key, path in files.items():
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        expected = layout["sha256"] if key == "image" else frozen["sha256"][path.name]
        if digest != expected:
            raise ValueError(f"{key} hash differs from its manifest")
        request[key], request[key + "_sha256"] = str(path), digest
    out.mkdir(exist_ok=False)

    def save(name, value):
        (out / (name + ".json")).write_text(json.dumps(value, indent=2) + "\n")

    save("before", before)
    save("build", frozen)
    save("image", layout)
    save("request", request)
    launch = json.loads(subprocess.check_output(ctl + ["start"], input=json.dumps(request).encode()))
    save("launch", launch)
    actual = Path(launch["run_dir"])
    if launch["request"] != request:
        raise RuntimeError("manager accepted a different request")
    print(actual, flush=True)
    deadline = time.monotonic() + 600
    while not (actual / "ready").exists():
        state = json.loads((manager / "state.json").read_text())
        if state["run_dir"] != str(actual) or state["phase"] == "failed" or time.monotonic() > deadline:
            raise RuntimeError("board preparation failed")
        time.sleep(2)
    with socket.create_connection(("127.0.0.1", request["uart_port"]), 10) as sock, \
            (out / "bare-uart.log").open("wb") as log:
        sock.settimeout(120)
        data = bytearray()
        while b"USER_END\n" not in data:
            chunk = sock.recv(4096)
            if not chunk or b"USER_UNEXPECTED_TRAP" in data or b"USER_SETUP_FAILED" in data:
                raise RuntimeError("probe did not finish")
            log.write(chunk)
            log.flush()
            data.extend(chunk.replace(b"\r", b""))
    output = data.decode("ascii")
    trials = []
    for match in re.finditer(r"(?m)^(original|nop|dbar) (COPY_[^\n]+)\nUSER_RC ([01])$", output):
        name, message, rc = match.groups()
        trial = dict(variant=name, rc=int(rc))
        if rc == "0" and message.strip() == "COPY_PASS bytes_hex 00900000":
            trial["bytes_checked"] = 0x900000
        else:
            fail = re.fullmatch(r"COPY_FAIL pass/offset/distance/index/expected/actual ((?:[0-9a-f]{8} ){6})", message)
            if rc != "1" or not fail:
                raise ValueError("unrecognized copy result")
            trial.update(zip(("pass", "offset", "distance", "index", "expected", "actual"),
                             (int(n, 16) for n in fail[1].split())))
            trial["address_mod64"] = (trial["offset"] + trial["index"]) % 64
        trials.append(trial)
    if [t["variant"] for t in trials] != ["original", "nop", "dbar", "nop", "dbar", "original"]:
        raise ValueError("incomplete PLV3 probe sequence")
    save("results", dict(run=str(actual), elf_sha256=layout["user_elf_sha256"], trials=trials))


if __name__ == "__main__":
    main()
