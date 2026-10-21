"""Fixed trusted quota operation. No network, user programs, Docker socket or arbitrary paths."""
import argparse,hashlib,json,os,pathlib,re,stat,subprocess,sys,uuid
ROOT=pathlib.Path('/quota')
def run(args):
 p=subprocess.run(args,text=True,capture_output=True,timeout=15)
 if p.returncode:raise RuntimeError('NATIVE_QUOTA_COMMAND_FAILED')
 return p.stdout.strip()
def identity(value):
 parsed=uuid.UUID(value)
 if str(parsed)!=value:raise ValueError('INVALID_IDENTITY')
 return value
def unsafe(path):
 current=path
 while current!=ROOT:
  if current.is_symlink():return True
  current=current.parent
 return ROOT.is_symlink()

def hard_limit(report,project,expected):
 rows=[line.split() for line in report.splitlines() if line.split() and line.split()[0] in (str(project),'#'+str(project))]
 if len(rows)!=1 or len(rows[0])<4 or not rows[0][3].isdigit() or int(rows[0][3])!=expected:raise RuntimeError('WORKSPACE_QUOTA_NOT_VERIFIED')
def main():
 os.umask(0o077)
 p=argparse.ArgumentParser();p.add_argument('--space',required=True,type=identity);p.add_argument('--workspace',required=True,type=identity)
 p.add_argument('--operation',required=True,type=identity);p.add_argument('--command',required=True,type=identity)
 p.add_argument('--project',required=True,type=int);p.add_argument('--mount-uuid',required=True,type=identity)
 args=p.parse_args();effect=False
 try:
  if args.project<200001:raise ValueError('INVALID_PROJECT_ID')
  mount=json.loads(run(['findmnt','-J','--target',str(ROOT)]))['filesystems'][0]
  if mount['target']!=str(ROOT) or mount['fstype']!='xfs' or not ({'prjquota','pquota'}&set(mount['options'].split(','))):raise ValueError('WORKSPACE_MOUNT_UNAVAILABLE')
  actual=run(['xfs_io','-r','-c','fsuuid',str(ROOT)])
  if re.findall(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}',actual.lower())!=[args.mount_uuid]:raise ValueError('WORKSPACE_MOUNT_UNAVAILABLE')
  quota_state=run(['xfs_quota','-x','-c','state -p',str(ROOT)])
  if not re.search(r'Accounting:\s+ON\b',quota_state) or not re.search(r'Enforcement:\s+ON\b',quota_state):raise ValueError('WORKSPACE_QUOTA_UNAVAILABLE')
  path=ROOT/args.space/args.workspace/'work'
  control=ROOT/'.workspace-control';marker=control/(args.workspace+'.json')
  if unsafe(path) or unsafe(control) or marker.is_symlink():raise ValueError('WORKSPACE_PATH_UNSAFE')
  if marker.exists() or (ROOT/args.space/args.workspace).exists():
   effect=True
   raise RuntimeError('WORKSPACE_ALLOCATION_REQUIRES_RECONCILIATION')
  for parent in (ROOT,ROOT/args.space,control):
   if parent.exists():
    metadata=parent.stat(follow_symlinks=False)
    if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid!=0 or metadata.st_mode&0o022:raise ValueError('WORKSPACE_PATH_UNSAFE')
    if parent!=ROOT and metadata.st_mode&0o077:raise ValueError('WORKSPACE_PATH_UNSAFE')
  facts={'space':args.space,'workspace':args.workspace,'operation':args.operation,'command':args.command,'project':args.project,'uuid':args.mount_uuid,'bytes':2147483648,'inodes':100000}
  capacity=os.statvfs(ROOT)
  if capacity.f_bavail*capacity.f_frsize<2147483648:raise ValueError('WORKSPACE_CAPACITY_UNAVAILABLE')
  effect=True
  control.mkdir(mode=0o700,exist_ok=True)
  fd=os.open(marker,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
  with os.fdopen(fd,'w') as stream:
   stream.write(json.dumps(facts,sort_keys=True)+'\n');stream.flush();os.fsync(stream.fileno())
  path.mkdir(mode=0o700,parents=True,exist_ok=False)
  run(['xfs_quota','-x','-c',f'project -s -p {path} {args.project}',str(ROOT)])
  run(['xfs_quota','-x','-c',f'limit -p bsoft=0 bhard=2147483648 isoft=0 ihard=100000 {args.project}',str(ROOT)])
  stat_output=run(['xfs_io','-r','-c','stat',str(path)])
  if not re.search(rf'fsxattr\.projid\s*=\s*{args.project}\b',stat_output):raise RuntimeError('WORKSPACE_PROJECT_MISMATCH')
  blocks=run(['xfs_quota','-x','-c',f'report -p -b -n -N -L {args.project} -U {args.project}',str(ROOT)])
  inodes=run(['xfs_quota','-x','-c',f'report -p -i -n -N -L {args.project} -U {args.project}',str(ROOT)])
  hard_limit(blocks,args.project,2097152);hard_limit(inodes,args.project,100000)
  report=blocks+'\n'+inodes
  # Native reports are retained in the private marker; actual hard refusal is a separate acceptance gate.
  # Enter the trusted directory before dropping privileges, so0700 ancestors stay protected.
  previous=os.open('.',os.O_RDONLY)
  try:
   os.chdir(path)
   # Finish root-owned quota setup and enter the directory before giving it away.
   # This avoids needing DAC_OVERRIDE/FOWNER merely to traverse its0700 path.
   os.chown('.',10001,10001)
   pid=os.fork()
   if pid==0:
    try:
     os.setgroups([]);os.setgid(10001);os.setuid(10001)
     token=args.command.encode();target=pathlib.Path('.r31-storage-canary')
     with target.open('xb') as stream:stream.write(token);stream.flush();os.fsync(stream.fileno())
     if target.read_bytes()!=token:os._exit(3)
     inherited=run(['xfs_io','-r','-c','stat',str(target)])
     if not re.search(rf'fsxattr\.projid\s*=\s*{args.project}\b',inherited):os._exit(5)
     target.unlink();os._exit(0)
    except BaseException:os._exit(4)
   _,status=os.waitpid(pid,0)
   if os.waitstatus_to_exitcode(status)!=0:raise RuntimeError('WORKSPACE_CANARY_FAILED')
  finally:os.fchdir(previous);os.close(previous)
  owner=path.stat(follow_symlinks=False)
  if owner.st_uid!=10001 or owner.st_gid!=10001 or stat.S_IMODE(owner.st_mode)!=0o700:raise RuntimeError('WORKSPACE_OWNER_MISMATCH')
  facts['quotaReport']=report;facts['projectStat']=stat_output;facts['ownerUid']=owner.st_uid;facts['ownerGid']=owner.st_gid;facts['mode']='0700';facts['verified']=True
  encoded=json.dumps(facts,sort_keys=True,separators=(',',':')).encode();digest=hashlib.sha256(encoded).hexdigest()
  fd=os.open(marker,os.O_WRONLY|os.O_TRUNC|os.O_NOFOLLOW)
  with os.fdopen(fd,'wb') as stream:stream.write(encoded);stream.flush();os.fsync(stream.fileno())
  print(json.dumps({'state':'COMMITTED','effectState':'ALLOCATED','allocationHash':digest,'errorCode':None}))
 except Exception as failure:
  code=str(failure) if isinstance(failure,(ValueError,RuntimeError)) and re.fullmatch('[A-Z_]{1,64}',str(failure)) else 'WORKSPACE_PROVISIONING_UNKNOWN'
  print(json.dumps({'state':'UNKNOWN' if effect else 'FAILED','effectState':'MAY_HAVE_STARTED' if effect else 'NOT_STARTED','allocationHash':None,'errorCode':code}))
  raise SystemExit(1)
if __name__=='__main__':main()
