"""Retained SSH tunnel for a fixed file-acceptance stage; no server mutation."""
from pathlib import Path
import argparse, ctypes, json, os, signal, socket, subprocess, time
ROOT=Path(__file__).resolve().parents[2]

def identity(pid):
    if os.name!='nt':
        try:return Path('/proc/'+str(pid)+'/stat').read_text().split()[21]
        except FileNotFoundError:return None
    from ctypes import wintypes
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.GetProcessTimes.argtypes=[wintypes.HANDLE,*([ctypes.POINTER(wintypes.FILETIME)]*4)]
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=kernel.OpenProcess(0x1000,False,pid)
    if not handle:return None
    try:
        values=[wintypes.FILETIME() for _ in range(4)]
        if not kernel.GetProcessTimes(handle,*[ctypes.byref(value) for value in values]):return None
        return str((values[0].dwHighDateTime<<32)+values[0].dwLowDateTime)
    finally:kernel.CloseHandle(handle)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['start','status','stop']);p.add_argument('--stage',choices=['r51','r52','r53','r54'],required=True);a=p.parse_args()
    data=ROOT/('.cache/'+a.stage+'-local');data.mkdir(parents=True,exist_ok=True);path=data/'tunnel.json'
    port=18100+int(a.stage[1:]);prior=json.loads(path.read_text()) if path.exists() else None
    active=prior and prior['stage']==a.stage and identity(prior['pid'])==prior['created']
    if a.action=='status' or a.action=='start' and active:print(json.dumps({'status':'ACTIVE' if active else 'STOPPED','port':port}));return
    if a.action=='stop':
        if active:
            if os.name=='nt':
                result=subprocess.run(['taskkill','/PID',str(prior['pid']),'/F'],capture_output=True);assert result.returncode==0
            else:os.kill(prior['pid'],signal.SIGTERM)
        print(json.dumps({'status':'STOPPED','port':port}));return
    with socket.socket() as probe:probe.bind(('127.0.0.1',port))
    if a.stage=='r54':
        with socket.socket() as probe:probe.bind(('127.0.0.1',80))
    command=['ssh','-F','NUL' if os.name=='nt' else '/dev/null','-i','D:/java/Dev/腾讯云服务器/lailihao_shanghai.pem',
             '-o','BatchMode=yes','-o','ExitOnForwardFailure=yes','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=3',
             '-N','-L','127.0.0.1:'+str(port)+':127.0.0.1:'+str(port),
             *(['-L','127.0.0.1:80:127.0.0.1:'+str(port)] if a.stage=='r54' else []),'root@124.222.55.140']
    with (data/'tunnel.private.log').open('ab') as log:
        process=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=log,stderr=log,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0,start_new_session=os.name!='nt')
    row={'pid':process.pid,'created':identity(process.pid),'stage':a.stage,'port':port};assert row['created']
    path.write_text(json.dumps(row),encoding='utf-8')
    for _ in range(30):
        if process.poll() is not None:raise RuntimeError('SSH tunnel exited; inspect protected log before retrying')
        with socket.socket() as probe:
            probe.settimeout(.2)
            if probe.connect_ex(('127.0.0.1',port))==0:print(json.dumps({'status':'ACTIVE','port':port}));return
        time.sleep(.2)
    raise RuntimeError('Original tunnel not ready; query its existing identity')

if __name__=='__main__':main()
