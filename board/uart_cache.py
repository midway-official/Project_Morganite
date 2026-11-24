"""Exercise the immutable DMA range on the board using the installed copy kernel."""
import base64
import gzip
import hashlib
import re
import struct


def verify_cache(command, jobs, info):
    sms = info["caps"] & 255
    warp = (info["caps"] >> 16) & 255
    mask = (1 << sms) - 1
    base = info["dma"]
    # mgpu kernel places the descriptor at 0 and its argument at 8192.
    arg, src_a, src_b, dst = 8192, 65536, 98304, 131072
    size = sms * warp * 4
    if not (info["features"] & 2) or base % 32 or dst + size > info["bytes"]:
        raise ValueError("cache test needs an aligned DMA pool and data L1")

    def upload(offset, data):
        payload = base64.b64encode(gzip.compress(data)).decode("ascii")
        command("echo " + payload + " | /bin/busybox base64 -d | /bin/busybox gzip -dc > /tmp/cache.bin"
                " && /bin/mgpu upload {} /tmp/cache.bin".format(offset))

    def pattern(seed):
        # Four distinct lanes and SM-dependent words; compressible on the UART.
        return b"".join(struct.pack("<I", seed ^ (i % 4) ^ ((i // warp) % 4 << 8))
                        for i in range(sms * warp))

    values = [pattern(0x12345678), pattern(0x98765432)]
    upload(src_a, values[0])
    upload(src_b, values[1])
    evidence = []
    for stage, src, data in (("bypass", src_a, values[0]), ("cold", src_a, values[0]),
                             ("updated", src_b, values[1])):
        upload(arg, struct.pack("<2I", base + src, base + dst))
        upload(dst, bytes(size))
        cmd = ("kernel copy {} {} {}".format(sms, warp, mask) if stage == "bypass"
               else "kernel_ro copy {} {} {} {} 32".format(sms, warp, mask, arg))
        rows = jobs("data L1 " + stage, cmd)
        if len(rows) != 1 or rows[0]["fault"] or [s[1] for s in rows[0]["sm"]] != [1]*sms:
            raise ValueError("copy did not complete one group per SM")
        digest = hashlib.sha256(data).hexdigest()
        out = command("/bin/mgpu download {} {} /tmp/cache-out && /bin/busybox sha256sum /tmp/cache-out"
                      .format(dst, size))
        if not re.search(r"(?m)^" + digest + r"  /tmp/cache-out$", out):
            raise ValueError("incorrect data L1 copy: " + stage)
        # Read the two counters after job completion. mgpu upload/download keep the last job's counters.
        txt = command("addr=$((0x1fe20608)); i=0; while [ $i -lt " + str(sms) +
                      " ]; do /bin/busybox devmem $addr 32 && /bin/busybox devmem $((addr+4)) 32"
                      " || break; addr=$((addr+16)); i=$((i+1)); done; test $i -eq " + str(sms), 240)
        counts = [int(word, 16) for word in re.findall(r"(?m)^0[xX]([0-9a-fA-F]+)$", txt)]
        expected = 0 if stage == "bypass" else 1
        if counts != [expected] * (2*sms):
            raise ValueError("unexpected data L1 hit/miss counts for {}: {}".format(stage, counts))
        evidence.append(dict(stage=stage, sequence=rows[0]["seq"], output_sha256=digest,
                             hits=counts[::2], misses=counts[1::2]))
    return evidence
