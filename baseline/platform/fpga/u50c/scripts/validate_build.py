#!/usr/bin/env python3
"""Check identity and final board implementation evidence before programming."""
from pathlib import Path
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
import argparse

ROOT = Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('build_dir',nargs='?',default=str(ROOT/'fpga/u50c/build'))
parser.add_argument('--local-only',action='store_true')
args=parser.parse_args()
BUILD = Path(args.build_dir).resolve()
HW_SHA = '0834d66dffcf2a403b56dde16f6e959dbf5f9924103c0b15c37447d12cdb7d52'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(ok, message):
    if not ok:
        raise SystemExit(message)


local = json.loads((BUILD/'LOCAL_VALIDATION.json').read_text())
require(local['passed'], 'Local SoC qualification has not passed')
for name, digest in local['sources'].items():
    require(sha(ROOT/name)==digest, 'Local validation is stale: '+name)
manifest = json.loads((BUILD/'input-manifest.json').read_text())
require(manifest['hardware_rtl_sha256']==HW_SHA and sha(BUILD/'generated/Aurora.v')==HW_SHA,
        'Build is not frozen Tick2h hardware RTL')
for name, digest in manifest['soc_sources'].items():
    require(sha(ROOT/name)==digest, 'SoC source changed during implementation: '+name)
if args.local_only:
    print('LOCAL_QUALIFICATION_CURRENT=Tick2h_U50C_100MHz')
    raise SystemExit(0)

ip_root = BUILD/'vivado/aurora_u50c.srcs/sources_1/ip'
ns = {'s':'http://www.spiritconsortium.org/XMLSchema/SPIRIT/1685-2009'}


def config(name):
    tree = ET.parse(ip_root/name/(name+'.xci'))
    return {e.get('{'+ns['s']+'}referenceId')[len('PARAM_VALUE.'):]: e.text
            for e in tree.findall('.//s:configurableElementValue',ns)
            if e.get('{'+ns['s']+'}referenceId','').startswith('PARAM_VALUE.')}


configs = {name: config(name) for name in ['hbm_0','clk_wiz_0','axi_dwidth_converter_0',
                                           'axi_clock_converter_0','axi_protocol_converter_0']}
hbm = configs['hbm_0']
require(hbm['USER_HBM_DENSITY']=='4GB' and hbm['USER_HBM_STACK']=='1'
        and hbm['USER_SINGLE_STACK_SELECTION']=='LEFT', 'Unexpected HBM stack/density')
require(hbm['USER_SWITCH_ENABLE_00'].lower()=='true', 'HBM global address switch is disabled')
for i in range(16):
    require((hbm['USER_MC_ENABLE_%02d'%i].lower()=='true')==(i<8), 'Unexpected enabled HBM memory controller')
require(configs['axi_dwidth_converter_0']['PACKING_LEVEL']=='2', 'HBM width converter is not Always Packing')
require(configs['axi_protocol_converter_0']['MI_PROTOCOL']=='AXI3', 'HBM protocol is not AXI3')
require(abs(float(configs['clk_wiz_0']['CLKOUT1_REQUESTED_OUT_FREQ'])-100)<0.001, 'CPU target is not 100MHz')

timing = (BUILD/'timing_impl.rpt').read_text()
lines = timing.splitlines()
header = next((i for i,line in enumerate(lines) if 'WNS(ns)' in line and 'WHS(ns)' in line),None)
require(header is not None, 'Missing final timing summary table')
values = None
for line in lines[header+1:header+10]:
    parts = line.split()
    if len(parts)==12:
        try:
            values = [float(x) for x in parts]
            break
        except ValueError:
            pass
require(values is not None, 'Cannot parse final setup/hold/pulse timing summary')
for i in [0,4,8]:
    require(values[i]>=0, 'Negative final setup/hold/pulse slack')
for i in [1,2,5,6,9,10]:
    require(values[i]==0, 'Final timing reports failing endpoints/total negative slack')
stamp = dict(line.split('=',1) for line in (BUILD/'BUILD_COMPLETE.txt').read_text().splitlines() if '=' in line)
require(stamp.get('CPU_MHZ')=='100' and stamp.get('DRC_ERROR_CRITICAL_COUNT')=='0', 'Missing board build/DRC gate')
route = (BUILD/'route_impl.rpt').read_text()
route_counts = {}
for label in ['routable nets', 'fully routed nets', 'nets with routing errors']:
    match = re.search(r'# of '+label+r'\D*:\s*(\d+)',route,re.I)
    require(match is not None, 'Missing route status: '+label)
    route_counts[label] = int(match.group(1))
require(route_counts['routable nets'] > 0 and
        route_counts['fully routed nets'] == route_counts['routable nets'] and
        route_counts['nets with routing errors'] == 0, 'Route status is not clean')
bit = BUILD/'vivado/aurora_u50c.runs/impl_1/u50c_top.bit'
require(bit.is_file() and bit.stat().st_size>1024, 'Implemented bitstream is missing')
result = {'passed':True, 'candidate':'Tick2h', 'part':manifest['part'],
          'cpu_mhz':100, 'hbm_axi_mhz':225, 'hardware_rtl_sha256':HW_SHA,
          'bitstream_sha256':sha(bit), 'WNS':values[0], 'WHS':values[4], 'WPWS':values[8],
          'inputs':manifest, 'local_qualification':local,
          'reports':{name:sha(BUILD/name) for name in ['timing_impl.rpt','drc_impl.rpt',
                    'route_impl.rpt','clocks_impl.rpt','cdc_impl.rpt','check_timing_impl.rpt']}}
(BUILD/'VALIDATED_BUILD.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:result[k] for k in ['passed','candidate','cpu_mhz','WNS','WHS','WPWS','bitstream_sha256']},indent=2))
