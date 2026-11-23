#!/usr/bin/env python3
"""Capture 5ec35882 Linux UART on the U50C JTAG bridge and check shell I/O."""
from pathlib import Path
import json, re, select, socket, sys, time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 23251
OUT = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('linux-uart.txt')
BOOT_TIMEOUT_S = int(sys.argv[3]) if len(sys.argv) > 3 else 1200
MARKER = 'AURORA_U50C_5EC35882_PASS_20261123'
COMMANDS = [
    ('/bin/busybox echo ' + MARKER, re.escape(MARKER)),
    ('/bin/busybox uname -m', r'(^|\n)loongarch32r(\n|$)'),
    ('/bin/busybox id', r'(^|\n)uid=0 gid=0[^\n]*(\n|$)'),
    ('/bin/busybox pwd', r'(^|\n)/(\n|$)'),
]

OUT.parent.mkdir(parents=True, exist_ok=True)
raw = bytearray()
with socket.create_connection(('127.0.0.1', PORT), timeout=15) as sock, OUT.open('wb') as log:
    sock.setblocking(False)
    def collect(deadline):
        while time.monotonic() < deadline:
            readable, _, _ = select.select([sock], [], [], min(0.25, max(0.0, deadline-time.monotonic())))
            if not readable:
                continue
            try:
                chunk = sock.recv(4096)
            except BlockingIOError:
                continue
            if not chunk:
                raise RuntimeError('UART bridge closed the connection')
            raw.extend(chunk)
            log.write(chunk)
            log.flush()
            text = raw.decode('latin-1').replace('\r', '\n')
            if re.search(r'Kernel panic|Oops:|Unable to handle kernel', text):
                raise RuntimeError('Linux UART reported a fatal kernel exception')
            yield text
        raise TimeoutError('UART response timeout')

    boot_deadline = time.monotonic() + BOOT_TIMEOUT_S
    for text in collect(boot_deadline):
        if 'Linux version' in text and 'Minimal LoongArch32 initramfs' in text and re.search(r'(^|\n)/ # ?$', text):
            print('LINUX_SHELL_PROMPT=observed', flush=True)
            break
    else:
        raise TimeoutError('No Linux shell prompt')

    results=[]
    for command, expected in COMMANDS:
        offset = len(raw)
        sock.sendall(command.encode('ascii') + b'\r')
        response_deadline = time.monotonic() + 30
        matched = False
        for text in collect(response_deadline):
            current = text[len(raw[:offset]).decode('latin-1').replace('\r','\n'):] if False else raw[offset:].decode('latin-1').replace('\r','\n')
            if re.search(expected, current) and re.search(r'\n/ # ?$', current):
                matched = True
                break
        if not matched:
            raise TimeoutError('Command did not return expected result: ' + command)
        results.append({'command':command,'expected_regex':expected,'passed':True})
        print('LINUX_COMMAND_PASS=' + command, flush=True)

text = raw.decode('latin-1').replace('\r','')
if 'loongarch32r' not in text or 'uid=0 gid=0' not in text or not re.search(r'(?m)^/$',text):
    raise RuntimeError('One or more shell responses were absent from the saved transcript')
result={
    'passed':True,'candidate':'5ec35882a6eb4040631c1cf9d322a754bd435907','board':'physical U50C',
    'transcript':str(OUT.resolve()),'transcript_sha256':__import__('hashlib').sha256(OUT.read_bytes()).hexdigest(),
    'boot_evidence':{'linux_version_seen':'Linux version' in text,
                     'initramfs_seen':'Minimal LoongArch32 initramfs' in text,
                     'shell_prompt_seen':True},
    'commands':results,'finished_local':time.strftime('%Y-%m-%dT%H:%M:%S%z')}
result_path=OUT.with_suffix(OUT.suffix+'.result.json')
result_path.write_text(json.dumps(result,indent=2)+'\n')
print('LINUX_UART_VALIDATION_PASS=' + str(result_path.resolve()),flush=True)
