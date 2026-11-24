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
from memory_layout import linux_mapping


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
        software = json.loads((run/"software.json").read_text())
        expected = {"/bin/mgpu": software["cli"]}
        expected.update({"/gpu/" + k: v for k, v in software["gpu_programs_and_scenes"].items()})
        hashes = command("/bin/busybox sha256sum /bin/mgpu /gpu/*", 240)
        actual_hashes = {path: digest for digest, path in
                         re.findall(r"(?m)^([0-9a-f]{64})  (/[^\r\n]+)$", hashes)}
        mismatch = sorted(k for k in expected.keys() | actual_hashes.keys()
                          if expected.get(k) != actual_hashes.get(k))
        record(run/"payload-sha256.json", dict(sha256=actual_hashes, mismatches=mismatch))
        if mismatch:
            raise ValueError("installed GPU payload hash mismatch: " + ", ".join(mismatch))
        resources = command("cat /proc/iomem; cat /proc/interrupts; ls /sys/bus/platform/devices")
        (run/"linux-resources.txt").write_text(resources)
        info = json.loads(next(line for line in command("/bin/mgpu info").splitlines() if line.startswith("{")))
        layout = json.loads((run/"image.json").read_text())
        sms = software.get("gpu_config", {"sms": 2})["sms"]
        expected_caps = 0x04080400 | sms
        expected_features = software.get("gpu_features", 0)
        if info["id"] != 0x4d4f5247 or info["abi"] != 0x10000 or info["caps"] != expected_caps:
            raise ValueError("unexpected GPU identity: " + str(info))
        if info.get("features", 0) != expected_features:
            raise ValueError("unexpected GPU features: " + str(info))
        record(run/"dma-mapping.json", linux_mapping(layout, info["dma"], info["bytes"], resources, boot))
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
            groups = max(8, sms * 4)
            if (len(rows)!=102 or len({r["seq"] for r in rows})!=102 or
                    any(r["fault"] for r in rows) or
                    any(len(r["sm"]) != sms for r in rows) or
                    any(sum(s[1] for s in r["sm"]) != groups or min(s[1] for s in r["sm"]) < 1
                        for r in rows[:100]) or
                    any(r["sm"][i][1] != 8 or sum(s[1] for s in r["sm"]) != 8
                        for i, r in enumerate(rows[-2:]))):
                raise ValueError("incomplete workgroup or sequence records")
            (actual/"observe").touch()
            bench = jobs("FMA throughput and DMA bandwidth","bench",600)
            concurrent = json.loads((actual/"concurrent.json").read_text())
            if not any(r.get("seq")==concurrent["sequence"] and r["cpu_busy_samples"] for r in bench):
                raise ValueError("JTAG/CPU overlap outside the measured job")
            record(run/"concurrent.json",concurrent)
            perf = bench[-1]
            tensor = next((r for r in bench if "tensor_macs" in r), {})
            if (expected_features >> 8) and not tensor.get("tensor_macs"):
                raise ValueError("missing INT8 MAC measurement")
            record(run/"performance.json",dict(perf | tensor,
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
