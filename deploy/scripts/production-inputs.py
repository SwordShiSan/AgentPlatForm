"""Generate an exact Git-blob hash manifest for deployment inputs (no credentials)."""
import hashlib,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[2]
paths=subprocess.check_output(['git','ls-tree','-r','--name-only','HEAD','deploy'],cwd=root,text=True).splitlines()
rows=[]
for path in paths:
    data=subprocess.check_output(['git','show','HEAD:'+path],cwd=root)
    rows.append(hashlib.sha256(data).hexdigest()+'  '+path.removeprefix('deploy/'))
out=root/'evidence/r0-release/production-inputs.sha256';out.parent.mkdir(parents=True,exist_ok=True)
out.write_text('\n'.join(rows)+'\n',encoding='utf-8')
print('Pinned deployment Git blobs:',len(rows))
