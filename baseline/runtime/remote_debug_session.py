#!/usr/bin/env python3
"""Remote worker for one managed U50C debug session. Python 3.6 compatible."""
from pathlib import Path
import hashlib, json, os, re, signal, subprocess, sys, time
from datetime import datetime, timezone

ROOT=Path(sys.argv[1]).resolve()
COMMAND=sys.argv[2]
RUNTIME=Path(__file__).resolve().parent
STATE=ROOT/'state.json'
ROOT.mkdir(parents=True,exist_ok=True)


def read_state():
    return json.loads(STATE.read_text()) if STATE.exists() else {'phase':'absent'}


def save(state):
    state['updated_utc']=datetime.now(timezone.utc).isoformat()
    tmp=STATE.with_suffix('.tmp');tmp.write_text(json.dumps(state,indent=2)+'\n')
    os.replace(str(tmp),str(STATE))


def alive(pid):
    if not pid:return False
    try:
        return (Path('/proc')/str(pid)/'stat').read_text().split()[2]!='Z'
    except OSError:return False


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def memory_range(address,length):
    end=address+length
    if address<0 or end>0x100000000 or (address<0x1ff00000 and end>0x1fe00000):
        raise ValueError('Image/init range exceeds HBM or overlaps the peripheral window')
    if address%4:raise ValueError('Image segment addresses must be word aligned')


def validate_request(request):
    for name in ['bit','ltx','image']:
        if not Path(request[name]).is_file():raise ValueError('Missing '+name+': '+request[name])
    if request['image_format']=='bin':
        if request.get('load_address') is None:raise ValueError('Binary images require --load-address')
        if not Path(request['image']).stat().st_size:raise ValueError('Binary image is empty')
        memory_range(request['load_address'],(Path(request['image']).stat().st_size+3)//4*4)
    else:
        address=None;count=0;total=0
        for line in Path(request['image']).open():
            for token in line.split('//',1)[0].split():
                if token.startswith('@'):
                    if address is not None:memory_range(address,(count+3)//4*4)
                    address=int(token[1:],16);count=0
                else:
                    if address is None or not re.fullmatch('[0-9a-fA-F]{1,2}',token):
                        raise ValueError('Sparse images use @address and byte-hex tokens')
                    count+=1;total+=1
        if address is None or not total:raise ValueError('Image has no bytes')
        memory_range(address,(count+3)//4*4)
    request['bit_sha256']=sha(request['bit'])
    request['image_sha256']=sha(request['image'])
    request['ltx_sha256']=sha(request['ltx'])
    if request.get('zero_ranges') is None:
        request['zero_ranges']=([[0x00900000,0x01000000]] if request['image_sha256']==
            '5240b97dc6b80198e0420f5272fad0d22ae630b568c9df0515605d5972e1e083' else [])
    for first,limit in request['zero_ranges']:
        if first>=limit or first%4 or limit%4:raise ValueError('Invalid initialization range')
        memory_range(first,limit-first)
    return request


def stop_bridge(force=False):
    state=read_state();pid=state.get('bridge_pid')
    if alive(state.get('worker_pid')) and state.get('phase')!='running':
        raise RuntimeError('A prepare task is still running; inspect status before replacing it')
    if pid and alive(pid):
        cmd=(Path('/proc')/str(pid)/'cmdline').read_bytes()
        if b'uart_bridge.tcl' not in cmd:raise RuntimeError('Saved PID no longer belongs to this UART bridge')
        Path(state['run_dir'],'stop').touch()
        for unused in range(150):
            if not alive(pid):break
            time.sleep(.1)
        if alive(pid):
            if not force:raise RuntimeError('Bridge did not finish its AXI transaction; stop --force is available')
            os.killpg(pid,signal.SIGTERM)
            time.sleep(.5)
    state['phase']='stopped';save(state)
    return state


def tcl_string(value):
    data=str(value).encode('utf-8').hex()
    return '[encoding convertfrom utf-8 [binary format H* {%s}]]'%data


def write_config(path,request,run):
    values={'server':request['server'],'image':request['image'],
            'image_format':request['image_format'],'uart_log':str(run/'uart.log'),
            'ready_file':str(run/'ready'),'stop_file':str(run/'stop'),
            'heartbeat_file':str(run/'heartbeat')}
    lines=['set %s %s'%(key,tcl_string(value)) for key,value in values.items()]
    lines += ['set load_address %d'%(request.get('load_address') or 0),
              'set uart_port %d'%request['uart_port'],
              'set start_cpu 1',
              'set zero_ranges {%s}'%' '.join('{%d %d}'%(first,limit) for first,limit in request['zero_ranges'])]
    path.write_text('\n'.join(lines)+'\n')


def worker(request_file):
    request=json.loads(request_file.read_text());run=request_file.parent
    for unused in range(1000):
        if (run/'dispatch_ready').exists():break
        time.sleep(.01)
    else:raise RuntimeError('Launcher did not publish its process ownership record')
    state=read_state();state.update(worker_pid=os.getpid(),run_dir=str(run),request=request)
    env=os.environ.copy();env['U50_PROBES_FILE']=request['ltx']
    vivado=str(Path(request['vivado_root'])/'bin/vivado')
    config=run/'config.tcl';write_config(config,request,run)
    steps=[]
    if request.get('program',True):
        steps.append(('program','program.tcl',[request['bit'],request['server'],'xcu50',request['ltx']],'PROGRAM_OK='))
    steps.append(('board','verify_board.tcl',[request['server']],'BOARD_VERIFY_PASS='))
    if request.get('hbm_test',True):
        steps.append(('hbm','hbm_diag.tcl',[request['server'],'xcu50'],'HBM_DIAG_VERIFY_PASS='))
    steps.append(('load','debug_load.tcl',[str(config)],'DEBUG_LOAD_PASS_CPU_HELD=1'))
    try:
        for name,script,args,marker in steps:
            state.update(phase=name,log=str(run/(name+'.log')));save(state)
            with (run/(name+'.log')).open('w') as log:
                subprocess.run([vivado,'-mode','batch','-notrace','-source',str(RUNTIME/script),'-tclargs']+args,
                               cwd=str(run),env=env,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=1800)
            if marker not in (run/(name+'.log')).read_text():raise RuntimeError('Missing pass marker: '+name)
        state.update(phase='bridge-starting',log=str(run/'bridge.log'));save(state)
        with (run/'bridge.log').open('w') as log:
            bridge=subprocess.Popen([vivado,'-mode','batch','-notrace','-source',str(RUNTIME/'uart_bridge.tcl'),
                                     '-tclargs',str(config)],cwd=str(run),env=env,stdin=subprocess.DEVNULL,
                                    stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        state['bridge_pid']=bridge.pid;save(state)
        for unused in range(300):
            if (run/'ready').exists() and bridge.poll() is None:break
            if bridge.poll() is not None:raise RuntimeError('UART bridge exited; see bridge.log')
            time.sleep(.1)
        else:raise RuntimeError('UART bridge did not become ready')
        state.update(phase='running',worker_pid=None,uart_port=request['uart_port']);save(state)
    except BaseException as error:
        state.update(phase='failed',error=repr(error),worker_pid=None);save(state)
        raise


if COMMAND=='worker':
    worker(Path(sys.argv[3]))
elif COMMAND=='status':
    state=read_state()
    state['bridge_alive']=alive(state.get('bridge_pid'))
    state['worker_alive']=alive(state.get('worker_pid'))
    heartbeat=Path(state.get('run_dir',str(ROOT)))/'heartbeat'
    if heartbeat.exists():
        state['heartbeat']=dict(line.split('=',1) for line in heartbeat.read_text().splitlines() if '=' in line)
        state['heartbeat_age_seconds']=max(0,time.time()-heartbeat.stat().st_mtime)
        value=int(state['heartbeat'].get('STATUS','0'))
        state['observed_soc_status']={'cpu_run':bool(value&1),'hbm_ready':bool(value&2),
                                      'clock_locked':bool(value&4),'cpu_held_reset':bool(value&16)}
    progress=[]
    if state.get('run_dir'):
        for name in ['program','board','hbm','load','bridge']:
            path=Path(state['run_dir'])/(name+'.log')
            if path.exists():
                for index,line in enumerate(path.read_text(errors='replace').splitlines()):
                    if re.match(r'^(HW_TARGET|HW_DEVICE|PROGRAM_OK|PERIPHERAL_RW_VERIFY_PASS|VIO_STATUS|BOARD_VERIFY_PASS|HBM_|DEBUG_|UART_)',line):
                        progress.append({'id':name+':'+str(index),'text':line})
    state['progress']=progress[-256:]
    print(json.dumps(state))
elif COMMAND=='stop':
    request=json.load(sys.stdin);print(json.dumps(stop_bridge(request.get('force',False))))
elif COMMAND in ['start','reset']:
    incoming=json.load(sys.stdin)
    if COMMAND=='reset':
        request=read_state().get('request')
        if not request:raise SystemExit('No saved image to reset')
        request=dict(request);request['program']=incoming.get('reprogram',False)
    else:request=incoming
    request=validate_request(request)
    stop_bridge()
    run=ROOT/'runs'/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    run.mkdir(parents=True,exist_ok=False)
    request_file=run/'request.json';request_file.write_text(json.dumps(request,indent=2))
    with (run/'worker.log').open('w') as log:
        process=subprocess.Popen([sys.executable,str(Path(__file__).resolve()),str(ROOT),'worker',str(request_file)],
                                 stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    state={'phase':'preparing','worker_pid':process.pid,'run_dir':str(run),'request':request,
           'runtime':str(RUNTIME),'log':str(run/'worker.log')};save(state)
    (run/'dispatch_ready').touch()
    print(json.dumps(state))
else:raise SystemExit('Unknown remote command')
