#!/usr/bin/env python3
"""Capture the live LA32R Linux resources through an already booted JTAG UART."""
import argparse
import re
import socket
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
parser.add_argument("--port", type=int, default=23251)
args = parser.parse_args()
commands = [
    "cat /proc/cmdline", "cat /proc/iomem", "cat /proc/meminfo",
    "cat /proc/interrupts", "ls /sys/bus/platform/devices", "ls /sys/firmware",
    "cat /proc/cpuinfo",
]
with socket.create_connection(("127.0.0.1", args.port), timeout=5) as uart:
    uart.settimeout(1)
    with args.output.open("xb") as out:
        for command in [""] + commands:
            uart.sendall(command.encode("ascii") + b"\r")
            buf = bytearray()
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                try:
                    data = uart.recv(4096)
                except socket.timeout:
                    continue
                if not data:
                    raise EOFError("UART bridge closed")
                out.write(data)
                buf.extend(data)
                if re.search(rb"(?:^|\n)/ # ?$", buf.replace(b"\r", b"")):
                    break
            else:
                raise TimeoutError(command)
