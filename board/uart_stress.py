"""Run replaceable compute programs over the existing Linux CLI and UART."""
import base64
import gzip
import hashlib
import json
import math
import re
import struct
import time

from PIL import Image


def verify_stress(command, jobs, info, suite, out):
    out.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((suite / "suite.json").read_text())
    for name, digest in manifest["sha256"].items():
        if hashlib.sha256((suite / name).read_bytes()).hexdigest() != digest:
            raise ValueError("stress package mismatch: " + name)
    (out / "suite.json").write_text(json.dumps(manifest, indent=2))
    base, sms = info["dma"], info["caps"] & 255
    if sms != 16 or info["bytes"] < 0x60000 or base + info["bytes"] > 2**32:
        raise ValueError("unexpected device or DMA window")
    code, arg, buf_a, buf_b = 4096, 8192, 65536, 131072

    def upload(offset, data):
        encoded = base64.b64encode(gzip.compress(data)).decode("ascii")
        for i in range(0, len(encoded), 600):
            command("echo {} {} /tmp/mgst.b64".format(encoded[i:i+600], ">>" if i else ">"))
        command("/bin/busybox base64 -d /tmp/mgst.b64 > /tmp/mgst.gz && "
                "/bin/busybox gzip -dc /tmp/mgst.gz > /tmp/mgst.in && "
                "/bin/mgpu upload {} /tmp/mgst.in".format(offset))

    def download(offset, size):
        log = command("/bin/mgpu download {} {} /tmp/mgst.out && ".format(offset, size) +
                      "/bin/busybox sha256sum /tmp/mgst.out && "
                      "/bin/busybox gzip -c /tmp/mgst.out | /bin/busybox base64", 1800)
        sha = re.search(r"(?m)^([0-9a-f]{64})  /tmp/mgst.out$", log)
        encoded = "".join(s for s in log.splitlines() if re.fullmatch(r"[a-zA-Z0-9+/]+=*", s))
        raw = gzip.decompress(base64.b64decode(encoded, validate=True))
        if len(raw) != size or not sha or hashlib.sha256(raw).hexdigest() != sha[1]:
            raise ValueError("incomplete GPU readback")
        return raw

    report = {}
    for name in ("raytrace", "mandelbrot", "julia", "nbody"):
        cfg = manifest["cases"][name]
        binary = (suite / cfg["program"]).read_bytes()
        upload(code, binary)
        if download(code, len(binary)) != binary:
            raise ValueError("uploaded GPU program mismatch")
        ref = (suite / (name + ".ref")).read_bytes()
        params = bytes.fromhex(cfg["params_hex"])
        upload(0, struct.pack("<8I", 0x3144474d, 0x10000, base+code, len(binary)//4,
                              base+arg, cfg["groups"], cfg["threads"], 0))
        case = {"runs": []}
        report[name] = case
        def save():
            tmp = out / "results.tmp"
            tmp.write_text(json.dumps(report, indent=2) + "\n")
            tmp.replace(out / "results.json")
        def submit(mask, index):
            rows = jobs("{} {} mask={}".format(name, index, mask), "submit 0 {}".format(mask), 600)
            if len(rows) != 1:
                raise ValueError("missing GPU job")
            row = rows[0]
            counts = row["sm"]
            if (row["mask"] != mask or row["fault"] or len(counts) != sms or row["cycles"] <= 0 or
                    sum(s[1] for s in counts) != cfg["groups"] or
                    any(bool(mask & (1<<i)) != bool(s[1]) for i, s in enumerate(counts))):
                raise ValueError("unexpected SM counters: " + str(row))
            case["runs"].append(dict(index=index, **row))
            save()
            return row["cycles"]

        if name == "nbody":
            baseline = None
            cycles = []
            for mask in (1, 65535):
                src, dst = buf_a, buf_b
                upload(src, (suite / "nbody.init").read_bytes())
                upload(dst, bytes([0xa5])*len(ref))
                total = 0
                for step in range(cfg["steps"]):
                    upload(arg, struct.pack("<2I", base+src, base+dst) + params)
                    total += submit(mask, step)
                    src, dst = dst, src
                raw = download(src, len(ref))
                (out / ("nbody-{}.f32".format(mask))).write_bytes(raw)
                errors = [abs(a[0]-b[0]) for a,b in zip(struct.iter_unpack("<f",raw),
                                                         struct.iter_unpack("<f",ref))]
                if not all(math.isfinite(e) and e <= cfg["max_abs_error"] for e in errors):
                    raise ValueError("N-body FP error exceeds tolerance")
                if baseline is not None and raw != baseline:
                    raise ValueError("N-body differs between SM configurations")
                baseline = raw
                cycles.append(total)
                case["max_abs_error"] = max(errors)
            case["speedup_1_to_16"] = cycles[0] / cycles[1]
        else:
            w, h, tile = cfg["width"], cfg["height"], cfg["tile_rows"]
            size = w*tile*4
            upload(arg, struct.pack("<I", base+buf_b) + params)
            benchmark_y = h//2
            row_arg = arg + (12 if name == "raytrace" else 44)
            upload(row_arg, struct.pack("<I", benchmark_y))
            upload(buf_b, bytes([0xa5])*size)
            single_cycles = submit(1, benchmark_y)
            baseline = download(buf_b, size)
            frame = bytearray()
            start = time.monotonic()
            worst = 0
            for y in range(0,h,tile):
                upload(row_arg, struct.pack("<I", y))
                upload(buf_b, bytes([0xa5])*size)
                ticks = submit(65535, y)
                raw = download(buf_b, size)
                frame.extend(raw)
                expected = ref[y*w*4:y*w*4+size]
                err = max(abs(a-b) for a,b in zip(raw, expected))
                if err > cfg["max_channel_error"]:
                    (out / "{}-row{}-failed.rgba".format(name,y)).write_bytes(raw)
                    raise ValueError("{} row {} max channel error {}".format(name,y,err))
                worst = max(worst, err)
                if y == benchmark_y:
                    if raw != baseline:
                        raise ValueError("image tile differs between SM configurations")
                    case["tile_speedup_1_to_16"] = single_cycles / ticks
            case.update(max_channel_error=worst, elapsed_with_uart_s=time.monotonic()-start,
                        frame_cycles=sum(r["cycles"] for r in case["runs"] if r["mask"]==65535))
            (out / (name + ".rgba")).write_bytes(frame)
            Image.frombytes("RGBA", (w,h), bytes(frame)).save(str(out / (name + ".png")))
        case["pass"] = True
        save()
    return report
