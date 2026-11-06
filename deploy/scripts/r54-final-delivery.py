"""Package final clean-main documentation and redacted evidence, verify three copies."""
from pathlib import Path
import argparse, datetime, hashlib, importlib.util, json, shutil, subprocess, zipfile

ROOT=Path(__file__).resolve().parents[2]
DOCS=ROOT.parent/'项目文档/06-AgentScope重构专项'
BASE=ROOT.parent/'.protected-deliveries'

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        while chunk:=stream.read(1048576):h.update(chunk)
    return h.hexdigest()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--stamp');args=parser.parse_args()
    def git(*arguments):return subprocess.check_output(['git',*arguments],cwd=ROOT,text=True).strip()
    assert git('branch','--show-current')=='main' and not git('status','--porcelain')
    source=git('rev-parse','HEAD');assert source==git('rev-parse','origin/main')
    release=json.loads((ROOT/'evidence/r54/production-release.json').read_text());assert release['status']=='R5_RELEASED'
    now=datetime.datetime.now(datetime.timezone.utc);stamp=args.stamp or now.strftime('%Y%m%dT%H%M%SZ')
    assert len(stamp)==16 and stamp.endswith('Z') and stamp[:8].isdigit()
    a=BASE/'r54-final-a'/stamp;b=BASE/'r54-final-b'/stamp
    assert not a.exists() and not b.exists();a.mkdir(parents=True);b.mkdir(parents=True)
    remote='/srv/agentplatform/operations/deliveries/r54-final-'+stamp
    handoff=DOCS/'08-重构进度管控/07-R5最终交付记录.md'
    handoff.write_text('# R5 正文与脱敏证据交付\n\n最终干净main：'+source+'。生产tag：'+release['reports']['writes-open']['result']['productionTag']+'。\n\n正文documentation.zip与脱敏证据redacted-evidence.zip分开封装。两份保护本地目录：\n\n- '+str(a)+'\n- '+str(b)+'\n\n云端保护副本：'+remote+'。保留至'+(now+datetime.timedelta(days=30)).isoformat()+'。\n\n各文件SHA256和最终副本校验以本批manifest.json、verification.json及新仓.cache/r54-local/final-delivery-pointer.json为准；只有校验PASS才认定交付完成。正文不在Git仓库内，Git推送不代表正文交付。秘密、原始SQL和敏感运维日志不进入脱敏证据包。\n',encoding='utf-8',newline='\n')
    allowed={'.md','.png','.svg','.jpg','.jpeg','.webp','.drawio','.pdf','.json','.txt'}
    docs=[p for p in DOCS.rglob('*') if p.is_file() and not p.is_symlink() and p.suffix.lower() in allowed and not any(x in {'.git','.cache','node_modules'} for x in p.relative_to(DOCS).parts)]
    assert docs
    with zipfile.ZipFile(a/'documentation.zip','x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as target:
        for path in sorted(docs):target.write(path,'06-AgentScope重构专项/'+path.relative_to(DOCS).as_posix())
    evidence=[p for p in (ROOT/'evidence/r54').rglob('*') if p.is_file() and not p.is_symlink() and p.suffix in {'.json','.md'} and '.private' not in p.name]
    assert evidence and all(git('ls-files','--',str(p.relative_to(ROOT))) for p in evidence)
    with zipfile.ZipFile(a/'redacted-evidence.zip','x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as target:
        for path in sorted(evidence):target.write(path,path.relative_to(ROOT).as_posix())
        target.write(ROOT/'deploy/R5-RELEASE.md','deploy/R5-RELEASE.md')
    files={p.name:sha(p) for p in a.iterdir() if p.is_file()}
    manifest={'status':'PASS','finalMain':source,'gitTree':git('rev-parse','HEAD^{tree}'),'productionMain':release['reports']['writes-open']['result']['main'],'productionTag':release['reports']['writes-open']['result']['productionTag'],'applicationSource':release['applicationSource'],'createdAt':now.isoformat(),'retainUntil':(now+datetime.timedelta(days=30)).isoformat(),'files':files,'documentationFiles':len(docs),'redactedEvidenceFiles':len(evidence),'gitBlobSha256':{str(p.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(subprocess.check_output(['git','show','HEAD:'+p.relative_to(ROOT).as_posix()],cwd=ROOT)).hexdigest() for p in evidence},'localCopies':[str(a),str(b)],'cloudCopy':remote,'scope':'No credentials, SQL backups, private journals or raw logs in the evidence archive; canonical body is a separate protected artifact, not a Git-push claim.'}
    (a/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    for path in a.iterdir():shutil.copy2(path,b/path.name);assert sha(path)==sha(b/path.name)
    spec=importlib.util.spec_from_file_location('native',ROOT/'deploy/scripts/files-native-fixture.py');native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native);native.configure('r54')
    native.remote('from pathlib import Path\np=Path('+repr(remote)+');assert not p.exists();p.mkdir(parents=True,mode=0o700)\nprint("PROTECTED_DELIVERY_DIRECTORY_CREATED")\n')
    hashes={p.name:sha(p) for p in a.iterdir()}
    for path in a.iterdir():native.local(['scp',*native.SSH,path,'root@124.222.55.140:'+remote+'/'+path.name])
    code='''from pathlib import Path
import hashlib,json
r=Path(REMOTE);expected=HASHES
for name,digest in expected.items():
 p=r/name;p.chmod(0o600);assert hashlib.sha256(p.read_bytes()).hexdigest()==digest
print(json.dumps({'status':'PASS','copies':3,'cloudHashesVerified':True}))
'''.replace('REMOTE',repr(remote)).replace('HASHES',repr(hashes))
    proof=json.loads(native.remote(code));assert proof['status']=='PASS'
    manifest.update(cloudHashesVerified=True,twoLocalHashesVerified=True,manifestSha256=hashes['manifest.json'])
    verification={'status':'PASS','finalMain':source,'files':files,'manifestSha256':hashes['manifest.json'],'twoLocalHashesVerified':True,'cloudHashesVerified':True,'cloudCopy':remote,'retainUntil':manifest['retainUntil']}
    for directory in [a,b]:(directory/'verification.json').write_text(json.dumps(verification,indent=2)+'\n',encoding='utf-8',newline='\n')
    native.local(['scp',*native.SSH,a/'verification.json','root@124.222.55.140:'+remote+'/verification.json'])
    native.remote('from pathlib import Path\nimport hashlib\np=Path('+repr(remote+'/verification.json')+');p.chmod(0o600);assert hashlib.sha256(p.read_bytes()).hexdigest()=='+repr(sha(a/'verification.json'))+'\nprint("DELIVERY_VERIFICATION_RECEIPT_MATCH")\n')
    pointer=ROOT/'.cache/r54-local/final-delivery-pointer.json';pointer.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'status':'PASS','finalMain':source,'localCopies':[str(a),str(b)],'cloudCopy':remote,'files':files,'retainUntil':manifest['retainUntil']},ensure_ascii=False))

if __name__=='__main__':main()
