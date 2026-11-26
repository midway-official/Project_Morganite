"""Measure verified, page-striped DMA copies with the programmable GPU kernel."""
import hashlib
import re
import struct

from uart_stress import upload_uart


def page_copy(command, jobs, info):
    src, dst = 0x10000, 0x90000
    groups, page, repeats = 64, 4096, 4
    length = page * groups
    if info["bytes"] < dst + length or info["caps"] & 255 != 16:
        raise ValueError("page copy requires 16 SMs and space for two 256 KiB buffers")
    # Distinct page and lane data also detect swapped HBM pages and word lanes.
    data = b"".join(struct.pack("<32I", *(0x9e372d31 ^ (p << 16) ^ i for i in range(32)))
                    * (page // 128) for p in range(groups))
    expected = hashlib.sha256(data).hexdigest()
    upload_uart(command, src, data)
    upload_uart(command, 8192, struct.pack("<2I", info["dma"] + src, info["dma"] + dst))
    results = {}
    for mask in (1, 65535):
        command("/bin/busybox dd if=/dev/zero of=/tmp/page-zero bs=4096 count=64 2>/dev/null"
                " && /bin/mgpu upload {} /tmp/page-zero".format(dst))
        row, = jobs("HBM page copy mask={}".format(mask), "kernel hbm_copy 64 32 {}".format(mask))
        counts = [sm[1] for sm in row["sm"]]
        if (row["fault"] or row["mask"] != mask or row["cycles"] <= 0 or sum(counts) != groups or
                any(bool(mask & (1 << i)) != bool(n) for i, n in enumerate(counts))):
            raise ValueError("page-copy group/SM counters differ")
        log = command("/bin/mgpu download {} {} /tmp/page-out && /bin/busybox sha256sum /tmp/page-out"
                      .format(dst, length))
        if not re.search(r"(?m)^" + expected + r"  /tmp/page-out$", log):
            raise ValueError("page-copy output differs")
        results[str(mask)] = dict(row, GBps_each_direction=length * repeats * 0.1 / row["cycles"])
    return dict(groups=groups, page_bytes=page, repeats=repeats, bytes_each_direction=length * repeats,
                clock_hz=100000000, output_sha256=expected, runs=results,
                speedup=results["1"]["cycles"] / results["65535"]["cycles"])
