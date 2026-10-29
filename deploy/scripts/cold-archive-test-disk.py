"""Cold archive one explicitly named stopped acceptance XFS disk; never production data.

Both protected local copies are decompressed/hashed before the remote raw file is
removed. The remote compressed original and identity receipt remain in place.
"""
from pathlib import Path
import argparse,gzip,hashlib,importlib.util,json,os,shutil,subprocess,time
ROOT=Path(__file__).resolve().parents[2]

def digest(path,compressed=False):
 h=hashlib.sha256()
 with (gzip.open(path,'rb') if compressed else path.open('rb')) as stream:
  for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
 return h.hexdigest()

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',required=True);p.add_argument('--project',required=True);args=p.parse_args()
 import re
 assert re.fullmatch('/srv/agentplatform/acceptance/r44-(?:r5[12]-[0-9]{14}|full-[0-9]{8}t[0-9]{6}z)',args.root)
 assert re.fullmatch('agentplatform-(?:r5[12]-local|r44-[0-9]{8}t[0-9]{6}z)',args.project)
 spec=importlib.util.spec_from_file_location('native',ROOT/'deploy/scripts/files-native-fixture.py');native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native);native.configure('r53');native.DATA.mkdir(parents=True,exist_ok=True)
 common='''from pathlib import Path
import gzip,hashlib,json,os,shutil,subprocess,stat
root=Path(ROOT);project=PROJECT;disk=root/'quota/workspaces.xfs';archive=root/'quota/workspaces.xfs.cold.gz'
def digest(path,compressed=False):
 h=hashlib.sha256()
 with (gzip.open(path,'rb') if compressed else path.open('rb')) as f:
  for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
 return h.hexdigest()
def check():
 assert root.resolve()==root and not root.is_symlink() and disk.resolve().parent==root/'quota' and not disk.is_symlink()
 assert stat.S_ISREG(disk.stat().st_mode) and disk.stat().st_size==4294967296
 assert not subprocess.check_output(['losetup','-j',str(disk)],text=True).strip(),'Test disk still has a loop device'
 assert subprocess.run(['findmnt','--mountpoint',str(root/'workspaces')],capture_output=True).returncode!=0,'Test filesystem still mounted'
 ids=subprocess.check_output(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project],text=True).split()
 rows=json.loads(subprocess.check_output(['docker','inspect',*ids])) if ids else []
 assert all(not x['State']['Running'] for x in rows),'Test process is running'
 live=subprocess.check_output(['docker','ps','-q'],text=True).split()
 for row in json.loads(subprocess.check_output(['docker','inspect',*live])) if live else []:
  for mount in row.get('Mounts',[]):
   source=mount.get('Source','');assert not source.startswith(str(root)+'/'),'Live process references this test root'
check();os.umask(0o077)
'''.replace('ROOT',repr(args.root)).replace('PROJECT',repr(args.project))
 code=common+'''
original=digest(disk)
if not archive.exists():
 temporary=archive.with_suffix('.tmp')
 with disk.open('rb') as source,temporary.open('xb') as target:
  with gzip.GzipFile(filename='',fileobj=target,mode='wb',compresslevel=1,mtime=0) as z:shutil.copyfileobj(source,z,1024*1024)
 os.rename(temporary,archive)
assert digest(archive,True)==original
receipt={'root':str(root),'project':project,'disk':str(disk),'archive':str(archive),'rawSha256':original,'archiveSha256':digest(archive),'rawBytes':disk.stat().st_size,'archiveBytes':archive.stat().st_size}
(root/'quota/cold-archive.json').write_text(json.dumps(receipt,indent=2));print(json.dumps(receipt))
'''
 record=json.loads(native.remote(code,timeout=240));copies=[]
 for suffix in ['a','b']:
  folder=ROOT.parent/'.protected-deliveries'/('r53-test-disk-'+suffix);folder.mkdir(parents=True,exist_ok=True)
  actor=subprocess.check_output(['whoami'],text=True).strip();subprocess.run(['icacls',str(folder),'/inheritance:r','/grant:r',actor+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F'],check=True,capture_output=True)
  copy=folder/(Path(args.root).name+'.xfs.gz')
  if not copy.exists():native.local(['scp',*native.SSH,'root@124.222.55.140:'+record['archive'],copy],timeout=240)
  assert digest(copy)==record['archiveSha256'] and digest(copy,True)==record['rawSha256']
  copies.append(str(copy))
 removed=native.remote(common+'\nassert digest(disk)=='+repr(record['rawSha256'])+'\nassert digest(archive,True)=='+repr(record['rawSha256'])+'\nbefore=shutil.disk_usage("/").free\ndisk.unlink()\nprint(json.dumps({"removedRawBytes":4294967296,"freeBefore":before,"freeAfter":shutil.disk_usage("/").free}))\n',timeout=240)
 receipt={**record,'copies':copies,'result':json.loads(removed),'scope':'stopped isolated acceptance disk only; production/business objects unchanged','retentionDays':30}
 (native.DATA/('cold-archive-'+Path(args.root).name+'.json')).write_text(json.dumps(receipt,indent=2),encoding='utf-8');print(json.dumps(receipt))

if __name__=='__main__':main()
