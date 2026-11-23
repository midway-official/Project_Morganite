#!/usr/bin/env python3
"""Local SoC qualification before any FPGA programming; does not boot Linux."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import os
import shutil
import subprocess
import sys
import argparse

U50 = Path(__file__).resolve().parents[1]
ROOT = U50.parents[1]
BUILD = Path(os.environ.get('U50_BUILD_DIR', str(U50 / 'build'))).resolve()
OUT = BUILD / 'local_validation'
OUT.mkdir(parents=True, exist_ok=True)
vivado = Path(os.environ.get('VIVADO_ROOT', '/opt/xilinx/Vivado/2019.2'))
parser = argparse.ArgumentParser()
parser.add_argument('--resume', action='store_true',
                    help='Reuse completed simulations only after verifying their input and log hashes')
options = parser.parse_args()
tracked = sorted((U50 / 'rtl').glob('*')) + [
    U50 / 'constraints/u50c_minimal.xdc', U50 / 'scripts/create_project.tcl',
    BUILD / 'generated/Aurora.v',
]
sources = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in tracked}
result = {'passed': False, 'candidate': 'Tick2h', 'cpu_mhz': 100, 'hbm_axi_mhz': 225,
          'started_utc': datetime.now(timezone.utc).isoformat(), 'sources': sources, 'suites': []}
summary = BUILD / 'LOCAL_VALIDATION.json'
summary.write_text(json.dumps(result, indent=2) + '\n')
suites = [
    ('interface', [], 'contract_sim'),
    ('linux-reset-stub-zero', ['--boot', '--initialize', 'zero'], 'boot_sim_linux-stub_zero'),
    ('cached-irq-smoke-zero', ['--boot', '--initialize', 'zero', '--program', 'soc-smoke'], 'boot_sim_soc-smoke_zero'),
    ('cached-irq-smoke-random', ['--boot', '--initialize', 'random', '--program', 'soc-smoke'], 'boot_sim_soc-smoke_random'),
]
for name, args, dirname in suites:
    print('LOCAL_VALIDATION_START=' + name, flush=True)
    record_path = BUILD / dirname / 'validation.json'
    reuse = False
    if options.resume and record_path.is_file():
        old = json.loads(record_path.read_text())
        reuse = old.get('passed', False)
        for source, digest in old.get('sources', {}).items():
            p = ROOT/source
            reuse = reuse and p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest() == digest
        for ip, digest in old.get('ip_configs', {}).items():
            p = BUILD/'vivado/aurora_u50c.srcs/sources_1/ip'/ip/(ip+'.xci')
            reuse = reuse and p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest() == digest
        p = BUILD/dirname/'run.log'
        reuse = reuse and p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest() == old.get('run_log_sha256')
    if reuse:
        print('LOCAL_VALIDATION_REUSE_VERIFIED=' + name, flush=True)
    else:
        with (OUT / (name + '.log')).open('w') as log:
            subprocess.run([sys.executable, str(U50/'tests/run_contract.py')] + args,
                           stdout=log, stderr=subprocess.STDOUT, check=True)
    record = json.loads((BUILD / dirname / 'validation.json').read_text())
    if not record['passed']:
        raise SystemExit('Suite did not pass: ' + name)
    shutil.copyfile(BUILD / dirname / 'validation.json', OUT / (name + '.json'))
    result['suites'].append({'name': name, 'passed': True, 'record': str(OUT/(name+'.json'))})
    summary.write_text(json.dumps(result, indent=2) + '\n')
    print('LOCAL_VALIDATION_PASS=' + name, flush=True)
print('LOCAL_VALIDATION_START=board-top-rtl', flush=True)
log_path = OUT / 'board-top-rtl.log'
with log_path.open('w') as log:
    subprocess.run([str(vivado/'bin/vivado'), '-mode', 'batch', '-notrace',
                    '-log', str(OUT/'rtl_check.log'), '-journal', str(OUT/'rtl_check.jou'),
                    '-source', str(U50/'scripts/check_rtl.tcl'), '-tclargs', str(BUILD)],
                   cwd=OUT, stdout=log, stderr=subprocess.STDOUT, check=True)
if 'RTL_ELABORATION_PASS=Tick2h_U50C_100MHz' not in log_path.read_text():
    raise SystemExit('Board top RTL elaboration did not pass')
for name, expected in sources.items():
    if hashlib.sha256((ROOT/name).read_bytes()).hexdigest() != expected:
        raise SystemExit('SoC changed during local validation: ' + name)
result['suites'].append({'name': 'board-top-rtl', 'passed': True})
result.update(passed=True, finished_utc=datetime.now(timezone.utc).isoformat(),
              scope='Interface and CPU short programs plus full board RTL elaboration; physical HBM and Linux-on-FPGA pending')
summary.write_text(json.dumps(result, indent=2) + '\n')
print('LOCAL_SOC_QUALIFICATION_PASS=' + str(summary), flush=True)
