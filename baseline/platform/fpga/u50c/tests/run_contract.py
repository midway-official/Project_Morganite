#!/usr/bin/env python3
"""Exercise the actual generated AMD width, CDC and protocol converter models."""
from pathlib import Path
import subprocess
import os
import argparse
import json
import hashlib
from datetime import datetime, timezone

parser = argparse.ArgumentParser()
parser.add_argument('--boot', action='store_true', help='Run the frozen CPU reset-stub smoke test')
parser.add_argument('--initialize', choices=['none', 'zero', 'random'], default='none',
                    help='Use frozen Chisel simulation initialization macros for unreset state')
parser.add_argument('--program', choices=['linux-stub', 'soc-smoke'], default='linux-stub')
args = parser.parse_args()
top = 'soc_boot_tb' if args.boot else 'soc_contract_tb'
marker = 'CPU_BOOT_PASS' if args.boot else 'SOC_CONTRACT_PASS'

U50 = Path(__file__).resolve().parents[1]
ROOT = U50.parents[1]
BUILD = Path(os.environ.get('U50_BUILD_DIR', str(U50 / 'build'))).resolve()
IP = BUILD / 'vivado/aurora_u50c.srcs/sources_1/ip'
sources = {}
for name in ['axi_dwidth_converter_0', 'axi_clock_converter_0', 'axi_protocol_converter_0']:
    root = IP / name
    for p in sorted((root / 'hdl').glob('*.v')):
        if '_vlsyn_' not in p.name:
            sources.setdefault(p.name, p)
    sources.setdefault('fifo_generator_vlog_beh.v', root / 'simulation/fifo_generator_vlog_beh.v')
    sources[name] = root / 'sim' / (name + '.v')
vivado = Path(os.environ.get('VIVADO_ROOT', '/opt/xilinx/Vivado/2019.2'))
out = BUILD / (('boot_sim_' + args.program + '_' + args.initialize) if args.boot else 'contract_sim')
out.mkdir(exist_ok=True)
sv = [vivado / 'data/ip/xpm/xpm_cdc/hdl/xpm_cdc.sv']
sv += [U50 / 'rtl' / n for n in ['axi_arbiter_2m2s.sv', 'jtag_uart_16550.sv', 'hbm_axi_bridge.sv']]
sv += [U50 / 'tests' / n for n in ['hbm_port_model.sv', top + '.sv']]
if args.boot:
    sources['Aurora.v'] = BUILD / 'generated/Aurora.v'
    sv.append(U50 / 'rtl/aurora_axi_wrapper.sv')
    image = U50.parents[1] / 'sim/linux-boot-runs/v0.3/image/start.bin'
    if args.program == 'soc-smoke':
        tools = Path(os.environ.get('LA32R_TOOLCHAIN', '/home/midway/la32r/la32r-toolchains/install/bin'))
        prefix = str(tools / 'loongarch32r-linux-gnusf-')
        subprocess.run([prefix+'gcc', '-c', str(U50 / 'tests/soc_smoke.S'), '-o', str(out/'smoke.o')], check=True)
        subprocess.run([prefix+'ld', '-Ttext=0x1c000000', '-e', '_start', str(out/'smoke.o'), '-o', str(out/'smoke.elf')], check=True)
        image = out / 'smoke.bin'
        subprocess.run([prefix+'objcopy', '-O', 'binary', '-j', '.text', str(out/'smoke.elf'), str(image)], check=True)
    data = image.read_bytes()
    if args.program == 'linux-stub' and len(data) != 308:
        raise SystemExit('Unexpected boot stub length; update the test deliberately')
    data += bytes((-len(data)) % 4)
    (out / 'start.hex').write_text('\n'.join(f'{b:02x}' for b in data) + '\n')
    text = 'AURORA_SOC_SMOKE_OK\n' if args.program == 'soc-smoke' else 'abcdefghij\nuart work!\n'
    (out / 'boot_config.svh').write_text(
        f'localparam integer IMAGE_BYTES={len(data)};\n'
        f'localparam integer SOC_SMOKE={int(args.program == "soc-smoke")};\n'
        f'string expected={json.dumps(text)};\n')
commands = [
    [str(vivado / 'bin/xvlog'), '--relax'] + [str(p) for p in sources.values()],
    [str(vivado / 'bin/xvlog'), '--relax', '-sv', '-i', str(U50 / 'rtl'), '-i', str(out)] + [str(p) for p in sv],
    [str(vivado / 'bin/xelab'), '-debug', 'typical', '-L', 'unisims_ver', '-L', 'unimacro_ver',
     'work.' + top, '-s', top, '-mt', '2'],
]
if args.boot and args.initialize != 'none':
    commands[0][2:2] = ['-d', 'RANDOMIZE_REG_INIT', '-d', 'RANDOMIZE_MEM_INIT']
    if args.initialize == 'zero':
        commands[0][2:2] = ['-d', "RANDOM=32'h0"]
identity_files = sorted((U50 / 'rtl').glob('*')) + [
    U50 / 'tests/hbm_port_model.sv', U50 / 'tests' / (top + '.sv'), Path(__file__).resolve(),
]
if args.boot:
    identity_files += [BUILD / 'generated/Aurora.v', image]
    if args.program == 'soc-smoke':
        identity_files.append(U50 / 'tests/soc_smoke.S')
identity = {
    'passed': False, 'top': top,
    'cpu_mhz': 100, 'hbm_axi_mhz': 225,
    'initialize': args.initialize, 'program': args.program if args.boot else None,
    'started_utc': datetime.now(timezone.utc).isoformat(),
    'sources': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in identity_files},
    'ip_configs': {name: hashlib.sha256((IP/name/(name+'.xci')).read_bytes()).hexdigest()
                   for name in ['axi_dwidth_converter_0','axi_clock_converter_0','axi_protocol_converter_0']},
    'scope': 'Generated AMD conversion IP + strict behavioral HBM AXI3 port; not physical HBM training',
}
(out / 'validation.json').write_text(json.dumps(identity, indent=2) + '\n')
with (out / 'build.log').open('w') as log:
    for args in commands:
        subprocess.run(args, cwd=out, stdout=log, stderr=subprocess.STDOUT, check=True)
with (out / 'run.log').open('w') as log:
    subprocess.run([str(vivado / 'bin/xsim'), top, '-runall', '-onfinish', 'quit'],
                   cwd=out, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
result = (out / 'run.log').read_text()
print(result)
if marker not in result or 'Fatal:' in result or 'Error:' in result:
    raise SystemExit('Simulation did not reach the contract pass marker')
for name, expected_sha in identity['sources'].items():
    if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != expected_sha:
        raise SystemExit('Source changed while simulation was running: ' + name)
identity.update(passed=True, finished_utc=datetime.now(timezone.utc).isoformat(),
                run_log_sha256=hashlib.sha256((out/'run.log').read_bytes()).hexdigest())
(out / 'validation.json').write_text(json.dumps(identity, indent=2) + '\n')
