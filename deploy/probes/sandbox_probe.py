"""Trusted R0 host acceptance fixtures; not a user command API or production runner."""
import fcntl,json,os,pathlib,re,subprocess,sys,time,uuid
root=pathlib.Path(os.environ.get('PLATFORM_ROOT','/srv/agentplatform')).resolve(); reports=[]
assert str(root)=='/srv/agentplatform' or re.fullmatch(r'/srv/agentplatform/operations/r04-restore-\d{8}T\d{6}Z',str(root)),'Unapproved probe root'
image=None
for line in (root/'deploy/images.env').read_text().splitlines():
 if line.startswith('SANDBOX_IMAGE='):image=line.split('=',1)[1]
uid=str(uuid.uuid4()); workspace=root/'workspaces'/uid
guard_only=len(sys.argv)>1 and sys.argv[1]=='verify-mount'
project=100001
name='agentplatform-r03-sandbox-probe'
def command(args,check=True,timeout=150):return subprocess.run(args,check=check,text=True,capture_output=True,timeout=timeout)
def quota_verified():
 f=command(['findmnt','-no','FSTYPE,OPTIONS,UUID','--target',str(root/'workspaces')]).stdout.split()
 assert f[0]=='xfs' and 'prjquota' in f[1].split(',') and f[2]==(root/'quota/filesystem.uuid').read_text().strip()
 return True
def available():
 return int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')))*1024
def admission(active=0,memory=None):
 if not quota_verified():return 'DISABLED'
 if active or (available() if memory is None else memory)<1152*1024*1024:return 'QUEUED'
 return 'ALLOW'
def args():
 assert image and re.fullmatch(r'sha256:[0-9a-f]{64}',image),'Sandbox requires verified immutable image identity'
 assert admission()=='ALLOW','Insufficient host memory for sandbox and512MiB free floor'
 return ['docker','run','--name',name,'--runtime=runsc','--user','10001:10001','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','512m','--memory-swap','512m','--cpus','1','--pids-limit','128','--ulimit','nproc=128:128','--tmpfs','/tmp:rw,noexec,nosuid,size=64m','--mount',f'type=bind,src={workspace},dst=/workspace','--label','project=agentplatform','--label','role=sandbox-probe','--label',f'workspaceId={uid}','--label','generation=1',image]
def remove():
 r=command(['docker','inspect',name],False)
 if r.returncode==0:
  d=json.loads(r.stdout)[0];assert d['Config']['Labels'].get('role')=='sandbox-probe'
  command(['docker','rm','-f',name])
def run(program,timeout=150):
 try:
  result=command(args()+program,False,timeout)
  state=json.loads(command(['docker','inspect',name]).stdout)[0]['State']
  reports.append({'case':'runtime-exit','program':program[0],'exitCode':state['ExitCode'],'oomKilled':state['OOMKilled']})
  return result
 finally:remove()
def case(key,fn):
 try:fn();reports.append({'case':key,'status':'PASS'});print(key+':PASS',flush=True)
 except Exception as e:reports.append({'case':key,'status':'FAIL','exception':type(e).__name__});print(key+':FAIL:'+type(e).__name__,flush=True);raise
def compat():
 r=run(['python','-c',"from pathlib import Path; p=Path('/workspace/a.txt'); p.write_text('中文😀666'); assert p.read_text()=='中文😀666'; print('666')"])
 assert r.returncode==0 and '666' in r.stdout
 r=run(['node','-e',"const fs=require('fs');if(fs.readFileSync('/workspace/a.txt','utf8')!=='中文😀666')process.exit(1);fs.writeFileSync('/workspace/b.txt','node-ok')"])
 assert r.returncode==0 and (workspace/'b.txt').read_text()=='node-ok'
def isolation():
 r=run(['python','-c',"import os,socket;from pathlib import Path;assert os.getuid()==10001;assert not Path('/var/run/docker.sock').exists();assert not Path('/run/secrets').exists();assert not Path('/home/ai-agent_big-market').exists();assert len(list(Path('/proc').glob('[0-9]*')))<30;\ntry:socket.create_connection(('169.254.169.254',80),1);raise AssertionError('metadata reachable')\nexcept OSError:pass\ntry:Path('/etc/escape').write_text('x');raise AssertionError('root writable')\nexcept OSError:pass"])
 assert r.returncode==0,r.stderr
def quota_case():
 command(['xfs_quota','-x','-c',f'limit -p bhard=8m ihard=16 {project}',str(root/'workspaces')])
 # Linux XFS project quota deliberately reports ENOSPC (user quota uses EDQUOT).
 native="import os,errno;os.setgroups([]);os.setgid(10001);os.setuid(10001);failed=False\ntry:\n with open("+repr(str(workspace/'large'))+",'wb') as f:\n  for i in range(32):f.write(b'Z'*(1024*1024));f.flush()\nexcept OSError as e:assert e.errno==errno.ENOSPC;failed=True;print('native-errno='+str(e.errno))\nassert failed"
 native_result=command(['python3','-c',native],False);assert native_result.returncode==0,native_result.stderr
 assert 'native-errno=28' in native_result.stdout
 assert (workspace/'large').stat().st_size<=8*1024*1024
 reports.append({'case':'project-quota-report','report':command(['xfs_quota','-x','-c','report -p -b -i -h',str(root/'workspaces')]).stdout,'status':'PASS'})
 (workspace/'large').unlink()
 assert __import__('shutil').disk_usage(root).free>5*1024**3
 r=run(['python','-c',"import errno;from pathlib import Path;failed=False\ntry:\n with open('/workspace/large','wb') as f:\n  for i in range(32):f.write(b'Z'*(1024*1024));f.flush()\nexcept OSError as e:assert e.errno in (errno.EDQUOT,errno.ENOSPC);failed=True;print('gvisor-errno='+str(e.errno))\nassert failed"])
 assert r.returncode==0,r.stderr
 assert (workspace/'large').stat().st_size<=8*1024*1024
 reports.append({'case':'quota-errno-boundary','native':28,'guest':r.stdout.strip(),'status':'PASS'})
 (workspace/'large').unlink(missing_ok=True)
 r=run(['python','-c',"import errno;from pathlib import Path;failed=False\ntry:\n for i in range(64):Path('/workspace/inode-'+str(i)).touch()\nexcept OSError as e:assert e.errno in (errno.EDQUOT,errno.ENOSPC);failed=True\nassert failed"])
 assert r.returncode==0,r.stderr
 assert len(list(workspace.glob('inode-*')))<16
 for p in workspace.glob('inode-*'):p.unlink()
 command(['xfs_quota','-x','-c',f'limit -p bhard=2g ihard=100000 {project}',str(root/'workspaces')])
def pid_case():
 # A tiny native shell avoids Python fork memory exhaustion masking the PID gate.
 r=run(['sh','-c','i=0; while [ "$i" -lt 256 ]; do sleep 30 & i=$((i+1)); printf "%s\\n" "$i" > /workspace/pid-count; done; wait'],90)
 count=int((workspace/'pid-count').read_text().strip())
 state=reports[-1]
 reports.append({'case':'pid-observation','spawned':count,'stderr':r.stderr,'oomKilled':state['oomKilled']})
 assert r.returncode!=0 and 1<count<128 and not state['oomKilled'],r.stdout+r.stderr
def memory_case():
 r=run(['python','-c',"data=[]\nwhile True:data.append(bytearray(32*1024*1024))"],90)
 assert r.returncode!=0 and available()>512*1024*1024
def cpu_case():
 command(args()+['python','-c','while True: pass'],False,timeout=10)
def cpu_timeout():
 a=args();a.insert(2,'-d')
 r=command(a+['python','-c','while True: pass']);assert r.returncode==0
 try:
  before=json.loads(command(['docker','stats','--no-stream','--format','{{json .}}',name]).stdout)
  d=json.loads(command(['docker','inspect',name]).stdout)[0]
  assert d['HostConfig']['NanoCpus']==1000000000
  time.sleep(3)
  command(['docker','kill',name]);assert command(['docker','wait',name]).stdout.strip()!='0'
  reports.append({'case':'CPU-observation','sample':before.get('CPUPerc'),'status':'PASS'})
 finally:remove()
def idle():
 a=args();a.insert(2,'-d');command(a+['sleep','infinity'])
 try:
  last=time.monotonic();time.sleep(60)
  assert time.monotonic()-last>=60
  remove();assert (workspace/'a.txt').read_text()=='中文😀666'
 finally:remove()
def remount():
 remove();command(['umount',str(root/'workspaces')]);command(['mount','-o','loop,prjquota,noatime',str(root/'quota/workspaces.xfs'),str(root/'workspaces')]);quota_verified()
 assert (workspace/'a.txt').read_text()=='中文😀666'
 compat();quota_case()
def single_slot():
 lockpath=root/'operations/sandbox-slot.lock'
 with lockpath.open('a') as first,lockpath.open('a') as second:
  fcntl.flock(first,fcntl.LOCK_EX|fcntl.LOCK_NB)
  try:fcntl.flock(second,fcntl.LOCK_EX|fcntl.LOCK_NB);raise AssertionError('Second slot admitted')
  except BlockingIOError:pass
 assert admission(active=1)=='QUEUED' and admission(memory=1152*1024*1024-1)=='QUEUED'
if guard_only:
 quota_verified();print('XFS identity/quota guard:ALLOW');sys.exit(0)
try:
 quota_verified()
 workspace.mkdir();os.chown(workspace,10001,10001)
 command(['xfs_quota','-x','-c',f'project -s -p {workspace} {project}',str(root/'workspaces')])
 command(['xfs_quota','-x','-c',f'limit -p bhard=2g ihard=100000 {project}',str(root/'workspaces')])
 case('Python-Node-file-rebuild',compat);case('socket-network-root-proc-isolation',isolation)
 case('byte-inode-project-quota',quota_case);case('PID-hard-limit',pid_case);case('memory-exhaustion-host-survives',memory_case)
 case('CPU-limit-kill-timeout',cpu_timeout);case('single-slot-low-memory',single_slot);case('idle60s-keeps-files',idle);case('XFS-remount-persistence-quota',remount)
finally:
 remove()
 (root/'reports/sandbox.json').write_text(json.dumps(reports,indent=2))
 # Only this trusted UUID fixture directory, no production workspace cleanup.
 if workspace.exists() and workspace.parent==root/'workspaces' and workspace.name==uid:
  for p in workspace.iterdir():
   if p.is_file():p.unlink()
  workspace.rmdir()
  command(['xfs_quota','-x','-c',f'limit -p bhard=0 ihard=0 {project}',str(root/'workspaces')],False)
