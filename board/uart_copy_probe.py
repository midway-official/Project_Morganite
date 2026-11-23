#!/usr/bin/env python3
"""Run the LA32R inflate copy controls over a verified, idle board UART."""
import argparse
import base64
import gzip
import hashlib
import json
import re
import socket
import time
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("run", type=Path)
    p.add_argument("binaries", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--rounds", type=int, default=4)
    args = p.parse_args()
    if args.rounds < 1:
        p.error("rounds must be positive")
    launch = json.loads((args.run/"launch.json").read_text())
    actual = Path(launch["run_dir"])
    state = json.loads((actual.parent.parent/"state.json").read_text())
    if (state["run_dir"] != str(actual) or state["request"] != launch["request"] or
            "CLIENTS=0" not in (actual/"heartbeat").read_text().splitlines() or
            not json.loads((args.run/"uart-result.json").read_text())["pass"]):
        raise RuntimeError("board run or UART ownership changed")
    manifest = json.loads((args.binaries/"binaries.json").read_text())
    names = ("original", "nop", "dbar")
    payloads = {k: (args.binaries/k).read_bytes() for k in names}
    if {k: hashlib.sha256(b).hexdigest() for k, b in payloads.items()} != manifest:
        raise ValueError("probe ELF hash mismatch")
    args.output.mkdir(exist_ok=False)
    results = {"run": str(actual), "elf_sha256": manifest, "trials": []}
    with socket.create_connection(("127.0.0.1", launch["request"]["uart_port"]), 15) as sock, \
            (args.output/"uart.log").open("wb") as log:
        sock.settimeout(1)

        def prompt():
            data = bytearray()
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                try:
                    chunk = sock.recv(8192)
                except socket.timeout:
                    continue
                if not chunk:
                    raise EOFError("UART bridge disconnected")
                data.extend(chunk)
                log.write(chunk)
                log.flush()
                if data.rstrip(b" ").endswith(b"\n/ #"):
                    return bytes(data).replace(b"\r", b"").decode("ascii", "replace")
            raise TimeoutError("missing Linux prompt")

        def command(line, check=True):
            sock.sendall((line+"; echo MG_COPY_RC_$?\r").encode())
            output = prompt()
            match = re.search(r"(?m)^MG_COPY_RC_(\d+)$", output)
            if not match or (check and match[1] != "0"):
                raise ValueError(output[-250:])
            return int(match[1]), output

        sock.sendall(b"\r")
        prompt()
        for name in names:
            path = "/tmp/mg-probe-"+name
            encoded = base64.b64encode(gzip.compress(payloads[name])).decode()
            command("> "+path+".b64")
            for i in range(0, len(encoded), 96):
                command("echo "+encoded[i:i+96]+" >> "+path+".b64")
            command("/bin/busybox base64 -d "+path+".b64 | /bin/busybox gzip -dc > "+path)
            _, response = command("/bin/busybox sha256sum "+path)
            if "\n"+manifest[name]+"  "+path+"\n" not in response:
                raise ValueError("transferred ELF differs: "+name)
            command("/bin/busybox chmod 700 "+path)

        for iteration in range(args.rounds):
            for index in range(len(names)):
                name = names[(iteration + index) % len(names)]
                rc, response = command("/tmp/mg-probe-"+name, check=False)
                trial = {"round": iteration+1, "variant": name, "rc": rc}
                if rc == 1:
                    mismatch = re.search(r"(?m)^COPY_FAIL pass/offset/distance/index/expected/actual "
                                         r"((?:[0-9a-f]{8} ){6})$", response)
                    if not mismatch:
                        raise ValueError(response[-200:])
                    trial.update(zip(("pass", "offset", "distance", "index", "expected", "actual"),
                                     (int(n, 16) for n in mismatch[1].split())))
                elif rc or "\nCOPY_PASS bytes_hex 00900000 \n" not in response:
                    raise ValueError(response[-200:])
                results["trials"].append(trial)
                (args.output/"results.json").write_text(json.dumps(results, indent=2)+"\n")
                print(json.dumps(trial), flush=True)


if __name__ == "__main__":
    main()
