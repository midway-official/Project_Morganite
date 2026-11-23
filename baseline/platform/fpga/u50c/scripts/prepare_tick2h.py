#!/usr/bin/env python3
"""Bind the board build to the accepted Tick2h hardware RTL and board inputs."""
from pathlib import Path
import gzip
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[3]
FROZEN = ROOT / 'reports/2026-10-26-tick2h'
BUILD = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / 'fpga/u50c/build'
SOURCE_SHA = '35d28bca89b2c65ddee21fd378ba46e0f28b932f6f13b25e7d861f7444e99885'
HW_SHA = '0834d66dffcf2a403b56dde16f6e959dbf5f9924103c0b15c37447d12cdb7d52'
INPUTS = {
    'au50_boardfiles_v1_3_20211104.zip': '8f449d6b719bd97360e6db9c966f04f603cb2a09cff82f330d236c01d2accc5b',
    'alveo-u50-xdc_20210505.zip': '84bb3d625974f8453a6bd19dcd515b7a5510798022a56c7a1e6401520ff72828',
    'sim/linux-boot-runs/v0.3/image/ram.dat': '5240b97dc6b80198e0420f5272fad0d22ae630b568c9df0515605d5972e1e083',
    'sim/linux-boot-runs/v0.3/image/start.bin': 'bd567f2af91365c3a3253f67a497cd313d971fce748030bb0eadaa27651c8f80',
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


frozen = json.loads((FROZEN / 'frozen.json').read_text())
if frozen['source']['production_sha256'] != SOURCE_SHA or frozen['RTL']['hw'] != HW_SHA:
    raise SystemExit('Frozen Tick2h identity mismatch')
rtl = gzip.decompress((FROZEN / 'tick2h-hw-Aurora.v.gz').read_bytes())
if sha(rtl) != HW_SHA:
    raise SystemExit('Frozen hardware RTL hash mismatch')
for name, expected in INPUTS.items():
    if sha((ROOT / name).read_bytes()) != expected:
        raise SystemExit(f'Input hash mismatch: {name}')
generated = BUILD / 'generated'
generated.mkdir(parents=True, exist_ok=True)
(generated / 'Aurora.v').write_bytes(rtl)
soc_inputs = sorted((ROOT / 'fpga/u50c/rtl').glob('*')) + [
    ROOT / 'fpga/u50c/constraints/u50c_minimal.xdc',
    ROOT / 'fpga/u50c/scripts/create_project.tcl',
]
manifest = {
    'candidate': 'Tick2h', 'production_source_sha256': SOURCE_SHA,
    'hardware_rtl_sha256': HW_SHA, 'part': 'xcu50-fsvh2104-2-e',
    'cpu_mhz': 100, 'hbm_axi_mhz': 225, 'reference_mhz': 100,
    'inputs': INPUTS,
    'soc_sources': {str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in soc_inputs},
}
(BUILD / 'input-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({'candidate': 'Tick2h', 'hardware_rtl_sha256': HW_SHA,
                  'cpu_mhz': 100, 'build_dir': str(BUILD)}, indent=2))
