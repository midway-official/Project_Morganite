#!/usr/bin/env python3
"""Compare U50C initramfs diagnostics with the exact Linux image loaded by JTAG."""
import argparse
import hashlib
import json
import re
import struct
import zlib
from pathlib import Path


def find_archive(segments):
    archives = []
    for base, data in segments.items():
        for match in re.finditer(b"\x1f\x8b\x08", data):
            source = data[match.start():]
            decoder = zlib.decompressobj(31)
            try:
                cpio = decoder.decompress(source)
            except zlib.error:
                continue
            if decoder.eof and cpio.startswith(b"070701"):
                size = len(source) - len(decoder.unused_data)
                archives.append((base + match.start(), source[:size], cpio))
    if len(archives) != 1:
        raise ValueError(f"expected one built-in gzip/newc archive, found {len(archives)}")
    return archives[0]


def crc(data):
    return f"{zlib.crc32(data):08x}"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("software", type=Path)
    p.add_argument("run", type=Path)
    p.add_argument("--require-trace", action="store_true",
                   help="fail if the instrumented kernel did not emit its CRCs")
    p.add_argument("--require-replay", action="store_true",
                   help="also require all four isolated inflate results")
    args = p.parse_args()
    layout = json.loads((args.software / "image.json").read_text())
    request = json.loads((args.run / "request.json").read_text())
    image = (args.software / "ram.dat").read_bytes()
    digest = hashlib.sha256(image).hexdigest()
    if digest != request["image_sha256"] or digest != layout["sha256"]:
        raise ValueError("board request, software manifest and ram.dat must match")
    segments = {}
    for section in image.split(b"@")[1:]:
        address, encoded = section.split(b"\n", 1)
        base = int(address, 16)
        if base in segments:
            raise ValueError("duplicate RAM image segment")
        segments[base] = bytes.fromhex(encoded.decode("ascii"))
    if {a: len(d) for a, d in segments.items()} != dict(layout["segments"]):
        raise ValueError("RAM segment lengths disagree with the manifest")
    start, compressed, cpio = find_archive(segments)
    report = dict(image_sha256=digest, gzip_physical=hex(start),
                  gzip_bytes=len(compressed), gzip_crc32=crc(compressed),
                  gzip_sha256=hashlib.sha256(compressed).hexdigest(), cpio_bytes=len(cpio),
                  cpio_crc32=crc(cpio), cpio_sha256=hashlib.sha256(cpio).hexdigest())

    uart_path = args.run / "manager/uart.log"
    if not uart_path.exists():
        uart_path = args.run / "linux-uart.log"
    uart = uart_path.read_bytes()
    panic = re.search(rb"Kernel panic - not syncing: ([^\r\n]+)", uart)
    if panic:
        raw = panic[1]
        if len(raw) == 4:
            pointer, = struct.unpack("<I", raw)
            phys = pointer & 0x1fffffff
            for base, segment in segments.items():
                if base <= phys < base + len(segment):
                    raw = segment[phys - base:].split(b"\x00", 1)[0]
                    report["panic_string_pointer"] = hex(pointer)
                    break
        report["panic"] = raw.decode("ascii", "backslashreplace")
    unexpected = re.search(rb"initramfs unexpected byte offset=(\d+) value=([0-9a-f]{2}) state=(\d+)", uart)
    if unexpected:
        offset = int(unexpected[1])
        report["unexpected_byte"] = dict(offset=offset, parser_state=int(unexpected[3]),
                                          observed=int(unexpected[2], 16),
                                          expected=cpio[offset] if offset < len(cpio) else None)
    trace = re.search(rb"initramfs trace input_bytes=(\d+) cached_crc=(\w+) uncached_crc=(\w+) "
                      rb"output_bytes=(\d+) output_crc=(\w+)", uart)
    if trace:
        length, cached, uncached, output_bytes, output_crc = trace.groups()
        n = int(output_bytes)
        report["cpu_trace"] = dict(input_bytes=int(length), cached_crc=cached.decode(),
                                   uncached_crc=uncached.decode(), output_bytes=n,
                                   output_crc=output_crc.decode(), expected_prefix_crc=crc(cpio[:n]))
        report["input_matches"] = (int(length) == len(compressed) and
                                   cached == uncached == crc(compressed).encode())
        report["output_prefix_matches"] = n <= len(cpio) and output_crc == crc(cpio[:n]).encode()
        report["output_complete"] = n == len(cpio)
    replay = []
    for item in re.finditer(rb"inflate replay pass=(\d+) irq_masked=(\d) fill=([0-9a-f]{2}) "
                            rb"rc=(-?\d+) bytes=(\d+) crc=([0-9a-f]{8})", uart):
        index, masked, fill, rc, size, checksum = item.groups()
        replay.append(dict(index=int(index), irq_masked=bool(int(masked)), fill=int(fill, 16),
                           rc=int(rc), bytes=int(size), crc32=checksum.decode(),
                           matches=int(rc) == 1 and int(size) == len(cpio) and
                           checksum.decode() == report["cpio_crc32"]))
    if replay:
        report["isolated_replay"] = replay
        report["replay_matches"] = ([(r["index"], r["irq_masked"], r["fill"]) for r in replay] ==
                                    [(0, False, 0), (1, True, 0), (2, False, 165), (3, True, 165)]
                                    and all(r["matches"] for r in replay))
    print(json.dumps(report, indent=2))
    if args.require_replay and not replay:
        raise SystemExit("missing isolated inflate replay on UART")
    if (args.require_trace or args.require_replay) and not trace:
        raise SystemExit("missing initramfs trace on UART")
    if replay and not report["replay_matches"]:
        raise SystemExit(1)
    if trace and not all(report[k] for k in ("input_matches", "output_prefix_matches", "output_complete")):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
