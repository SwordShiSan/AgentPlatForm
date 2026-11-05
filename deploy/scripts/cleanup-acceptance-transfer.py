"""Remove only a verified old acceptance image transport copy, retaining images/data/local archive."""
from pathlib import Path
import argparse, hashlib, importlib.util, json
ROOT=Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--stage',choices=['r53'],required=True);args=parser.parse_args()
    plan=json.loads((ROOT/('.cache/'+args.stage+'-local/native-plan.json')).read_text())
    artifact=plan['gateway'];local=Path(artifact['archive']);expected=artifact['archiveSha256']
    with local.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==expected
    spec=importlib.util.spec_from_file_location('fixture',ROOT/'deploy/scripts/files-native-fixture.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);m.configure('r54')
    root=plan['root'];assert root.startswith('/srv/agentplatform/acceptance/r44-r53-')
    code='''from pathlib import Path
import hashlib,json,shutil,subprocess
root=Path(ROOT);project=PROJECT;expected=DIGEST
assert root.resolve()==root and json.loads((root/'reports/window-result.json').read_text())['r4Restored']
assert not subprocess.check_output(['docker','ps','-q','--filter','label=com.docker.compose.project='+project],text=True).strip()
paths=[]
for name in ['candidate-update.tar.gz','gateway.tar']:
 path=root/name
 if path.is_file() and not path.is_symlink():
  with path.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
  if digest==expected:paths.append(path)
assert len(paths)==1,'No unique matching transport copy; no deletion performed'
path=paths[0];size=path.stat().st_size
record={'scope':'old stopped acceptance transport only','archiveSha256':expected,'removedBytes':size,'localImmutableCopyVerified':True,'imagesAndDataUnchanged':True}
(root/'transport-cleanup.json').write_text(json.dumps(record,indent=2))
path.unlink();record['freeBytes']=shutil.disk_usage('/').free
print(json.dumps(record))
'''.replace('ROOT',repr(root)).replace('PROJECT',repr(plan['project'])).replace('DIGEST',repr(expected))
    result=json.loads(m.remote(code,timeout=120));(m.DATA/'prior-transfer-cleanup.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))

if __name__=='__main__':main()
