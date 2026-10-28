"""Export one committed project image with verified OCI/legacy config and archive identities.

Reuses the R2 export chain without its stage-specific fixture bootstrap. No pull, push,
container start or production access is performed. Image contents stay in ignored private output.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

ROOT=Path(__file__).resolve().parents[2]


def digest(path):
    value=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):value.update(chunk)
    return value.hexdigest()


def exported_identity(exported, actual, source):
    """Resolve a Desktop OCI ID or legacy config ID through the saved archive.

    This also supports a shared archive with several separately pinned images.
    Callers must provide each image's original committed source, never relabel it.
    """
    manifest=json.load(exported.extractfile('manifest.json'))
    def blob(identity):
        raw=exported.extractfile('blobs/sha256/'+identity.split(':')[1]).read()
        assert 'sha256:'+hashlib.sha256(raw).hexdigest()==identity
        return json.loads(raw)
    descriptor=actual.get('Descriptor');index_id=None;manifest_id=None
    if descriptor:
        index_id=descriptor['digest'];node=blob(index_id)
        if 'manifests' in node:
            candidates=[m for m in node['manifests'] if m.get('platform',{}).get('architecture')=='amd64' and m.get('platform',{}).get('os')=='linux']
            assert len(candidates)==1;manifest_id=candidates[0]['digest'];node=blob(manifest_id)
        else:manifest_id=index_id
        expected=node['config']['digest']
    else:expected=actual['Id']
    matches=[]
    for row in manifest:
        raw=exported.extractfile(row['Config']).read();config_id='sha256:'+hashlib.sha256(raw).hexdigest()
        if config_id!=expected:continue
        config=json.loads(raw)
        assert config['architecture']=='amd64' and config['os']=='linux'
        assert config['config'].get('Labels',{}).get('org.opencontainers.image.revision')==source
        assert config['rootfs']['diff_ids']==actual['RootFS']['Layers']
        matches.append(row)
    assert len(matches)==1,'Missing or ambiguous exported platform config'
    return {'localId':actual['Id'],'indexDigest':index_id,'manifestDigest':manifest_id,'configId':expected,'exportManifest':matches}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag',required=True)
    parser.add_argument('--component',required=True)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();output=args.output.resolve()
    if not re.fullmatch(r'agentplatform-[a-z0-9-]+:[a-z0-9.-]+',args.tag):parser.error('Use a local project candidate tag')
    if not re.fullmatch(r'[a-z][a-z0-9-]{0,63}',args.component):parser.error('Invalid component')
    if not output.is_relative_to(ROOT/'.cache') or output.exists():parser.error('Use a new private .cache output directory')
    if subprocess.check_output(['git','status','--porcelain'],cwd=ROOT):parser.error('Commit public source before exporting an immutable artifact')
    source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    output.mkdir(parents=True)
    if os.name=='nt':
        actor=subprocess.check_output(['whoami'],text=True).strip()
        subprocess.run(['icacls',str(output),'/inheritance:r','/grant:r',actor+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F','*S-1-5-32-544:(OI)(CI)F'],check=True,capture_output=True)
    else:output.chmod(0o700)
    actual=json.loads(subprocess.check_output(['docker','image','inspect',args.tag]))[0]
    assert actual['Architecture']=='amd64' and actual['Os']=='linux'
    assert actual['Config'].get('Labels',{}).get('org.opencontainers.image.revision')==source,'Image was built from another source'
    archive=output/(args.component+'.tar');compressed=output/(args.component+'.tar.gz')
    subprocess.run(['docker','image','save','--output',str(archive),args.tag],check=True)
    with tarfile.open(archive) as exported:
        manifest=json.load(exported.extractfile('manifest.json'));assert len(manifest)==1
        identity=exported_identity(exported,actual,source)
    with archive.open('rb') as origin,compressed.open('xb') as destination:
        with gzip.GzipFile(filename='',fileobj=destination,mode='wb',compresslevel=1,mtime=0) as zipped:shutil.copyfileobj(origin,zipped,1024*1024)
    record={'component':args.component,'tag':args.tag,'source':source,**identity,
            'platform':'linux/amd64','archive':str(compressed),'archiveBytes':compressed.stat().st_size,
            'archiveSha256':digest(compressed),'exportManifest':manifest,'registry':'LOCAL_ONLY_NOT_PUSHED'}
    (output/'image.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:record[k] for k in ['component','source','localId','configId','archiveBytes','archiveSha256']}))


if __name__=='__main__':main()
