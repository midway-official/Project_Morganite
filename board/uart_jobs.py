#!/usr/bin/env python3
"""Exercise a fresh physical Linux boot and retain device outputs on the FPGA VM."""
import argparse
import base64
import gzip
import hashlib
import json
import re
import socket
import time
from pathlib import Path


def record(path, value):
    path.write_text(json.dumps(value, indent=2)+"\n")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("run", type=Path)
    p.add_argument("--full", action="store_true")
    args = p.parse_args()
    run = args.run.resolve()
    launch = json.loads((run/"launch.json").read_text())
    actual = Path(launch["run_dir"])
    limit = time.monotonic()+2400
    while not (actual/"ready").is_file():
        state = json.loads((actual.parent.parent/"state.json").read_text())
        if state["run_dir"] != str(actual) or state["phase"]=="failed" or time.monotonic()>limit:
            raise RuntimeError("board preparation failed: "+str(state))
        time.sleep(2)
    request = json.loads((actual/"request.json").read_text())
    if request != launch["request"]:
        raise ValueError("manager request and launch differ")
    stats = []
    with socket.create_connection(("127.0.0.1", request["uart_port"]),15) as sock, (run/"linux-uart.log").open("wb") as log:
        sock.settimeout(1)
        def prompt(timeout):
            data = bytearray()
            until = time.monotonic()+timeout
            while time.monotonic()<until:
                try:
                    chunk = sock.recv(65536)
                except socket.timeout:
                    continue
                if not chunk:
                    raise IOError("UART bridge closed")
                log.write(chunk); log.flush()
                data.extend(chunk)
                if re.search(b"(?:Kernel panic|Oops:|Unable to handle kernel)", data[-512:]):
                    raise RuntimeError("kernel fault on UART")
                if data.rstrip(b" ").endswith(b"\n/ #"):
                    return bytes(data).replace(b"\r",b"").decode("ascii","replace")
            raise TimeoutError("UART prompt timeout")
        def command(cmd, timeout=120):
            sock.sendall((cmd+"; echo MG_EXIT_$?\r").encode())
            text = prompt(timeout)
            if not re.search(r"(?m)^MG_EXIT_0$",text):
                raise RuntimeError("Linux command failed: "+cmd+"\n"+text[-600:])
            return text
        boot = prompt(1200)
        if "Minimal LoongArch32 initramfs" not in boot or "Linux version" not in boot:
            raise RuntimeError("missing Linux boot banner")
        if not re.search(r"(?m)^loongarch32r$",command("uname -m")):
            raise RuntimeError("unexpected architecture")
        resources = command("cat /proc/iomem; cat /proc/interrupts; ls /sys/bus/platform/devices")
        (run/"linux-resources.txt").write_text(resources)
        info = json.loads(next(line for line in command("/bin/mgpu info").splitlines() if line.startswith("{")))
        lo, hi = json.loads((run/"image.json").read_text())["dma_window"]
        if (info["id"] != 0x4d4f5247 or info["abi"] != 0x10000 or info["caps"] != 0x04080402
                or info["dma"] != lo or lo+info["bytes"] != hi):
            raise ValueError("unexpected device or DMA mapping: "+str(info))
        ram = re.findall(r"(?m)^([0-9a-f]+)-([0-9a-f]+) : System RAM$",resources)
        if not ram or any(max(int(a,16),lo) <= min(int(b,16),hi-1) for a,b in ram):
            raise ValueError("coherent DMA pool overlaps advertised System RAM")
        def jobs(name, cmd, timeout=240):
            output = command("/bin/mgpu "+cmd,timeout)
            rows = [json.loads(line) for line in output.splitlines() if line.startswith("{")]
            if not rows:
                raise ValueError("missing device counters for "+name)
            stats.extend(rows)
            record(run/"jobs.json",stats)
            print("PASS "+name,flush=True)
            return rows
        jobs("loopback","loopback")
        if args.full:
            rows = jobs("100 integer and FP32 jobs with cross-SM visibility","selftest 100",1200)
            if (len(rows)!=102 or len({r["seq"] for r in rows})!=102 or
                    any(r["fault"] or sum(s[1] for s in r["sm"])!=8 for r in rows)):
                raise ValueError("incomplete workgroup or sequence records")
            (actual/"observe").touch()
            bench = jobs("FMA throughput and DMA bandwidth","bench",600)
            concurrent = json.loads((actual/"concurrent.json").read_text())
            if not any(r.get("seq")==concurrent["sequence"] and r["cpu_busy_samples"] for r in bench):
                raise ValueError("JTAG/CPU overlap outside the measured job")
            record(run/"concurrent.json",concurrent)
            perf = bench[-1]
            record(run/"performance.json",dict(perf,
                gflops=2*perf["perf_fmas"]*0.1/perf["cycles_all"],
                speedup=perf["cycles_1sm"]/perf["cycles_all"],
                copy_aggregate_MBps=perf["copy_payload_bytes"]*100/perf["copy_cycles"]))
            for name in ("triangle","vertex_shift","fragment_swap","shared","overlap","degenerate","cube_a","cube_b"):
                jobs(name,"render /gpu/"+name+".scene /tmp/frame",600)
                directory=run/"frames"/name
                directory.mkdir(parents=True)
                for filename in ("color.rgba","depth.f32","winner.u32"):
                    src="/tmp/frame/"+filename
                    digest=command("/bin/busybox sha256sum "+src)
                    expected=re.search(r"(?m)^([0-9a-f]{64})  /tmp/frame/",digest).group(1)
                    output=command("/bin/busybox gzip -c "+src+" | /bin/busybox base64",1800)
                    enc="".join(line for line in output.splitlines() if re.fullmatch(r"[A-Za-z0-9+/]+=*",line))
                    raw=gzip.decompress(base64.b64decode(enc,validate=True))
                    if len(raw)!=320*240*4 or hashlib.sha256(raw).hexdigest()!=expected:
                        raise ValueError("Linux readback/transfer mismatch "+filename)
                    (directory/filename).write_bytes(raw)
        record(run/"uart-result.json",{"pass":True,"boot":True,"device":info,"full":args.full,
                                       "hardware_run":str(actual),"image_sha256":request["image_sha256"]})
    print("UART_BOARD_JOBS_PASS",flush=True)


if __name__ == "__main__":
    main()
