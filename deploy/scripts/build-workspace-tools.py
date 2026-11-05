"""Build/export a clean R4.4 candidate locally after a PASS engineering receipt."""
from pathlib import Path
import argparse,gzip,hashlib,importlib.util,json,os,shutil,subprocess,tarfile,time
ROOT=Path(__file__).resolve().parents[2]

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--engineering-receipt',required=True,type=Path);p.add_argument('--resume',type=Path);p.add_argument('--reuse-unchanged-from',type=Path);p.add_argument('--stage',choices=['r44','r52','r53','r54'],default='r44');args=p.parse_args()
    data=ROOT/('.cache/'+args.stage+'-local')
    receipt=json.loads(args.engineering_receipt.read_text(encoding='utf-8'));assert receipt['status']=='PASS' and receipt['inputUnchanged']
    inputs=args.engineering_receipt.parent/'source.json'
    if not inputs.exists():inputs=args.engineering_receipt.parent/'source-before.json'
    verified=json.loads(inputs.read_text(encoding='utf-8'))
    for name,digest in verified['files'].items():
        if name.startswith('backend/'):assert sha(ROOT/name)==digest,'Backend differs from verified source: '+name
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT),'Commit public source before building'
    configuration_source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    source=configuration_source
    reuse=None;reuse_proofs={}
    if args.reuse_unchanged_from:
        assert args.stage=='r54' and not args.resume
        reuse=json.loads(args.reuse_unchanged_from.read_text());assert reuse['status']=='BUILT_EXPORTED_NOT_ACCEPTED'
        affected=subprocess.check_output(['git','diff','--name-only',reuse['source'],source],cwd=ROOT,text=True).splitlines()
        copied_helpers=['deploy/scripts/'+name+'.py' for name in ['file-stream-bridge','file-mutation-bridge','file-scan-bridge','file-capture-bridge','sandbox-file-bridge','file-helper-entrypoint']]
        fixed={'gateway':['deploy/gateway.Dockerfile','deploy/nginx.production.conf','frontend/package.json','frontend/pnpm-lock.yaml','frontend/vite.config.ts','contracts/openapi.yaml'],
               'file-helper':['deploy/file-helper.Dockerfile',*copied_helpers],
               'preview-renderer':['deploy/preview-renderer.Dockerfile','deploy/preview-renderer.lock.json','backend/platform-app/pom.xml','backend/pom.xml'],
               'provider-fixture':['deploy/provider-fixture.Dockerfile','deploy/probes/chat_provider_fixture.py']}
        for component,paths in fixed.items():
            relevant=[name for name in affected if name in paths or component=='gateway' and name.startswith('frontend/src/') or component=='preview-renderer' and (name.startswith('backend/platform-app/src/renderer/') or name.startswith('deploy/preview/'))]
            assert not relevant,'Reused component input changed: '+str(relevant)
            reuse_proofs[component]={'priorSource':reuse['images'][component]['source'],'inputDiff':[],'priorRecordSha256':sha(args.reuse_unchanged_from)}
        assert sha(ROOT/'backend/platform-app/target/platform-app-0.1.0-SNAPSHOT-renderer.jar')==json.loads(args.engineering_receipt.read_text())['checks']['packages']['renderer']['sha256']
    if args.resume:
        out=args.resume.resolve();assert out.is_relative_to(data) and out.is_dir()
        state=json.loads((out/'build-inputs.json').read_text(encoding='utf-8'));source=state['source']
        changed=subprocess.check_output(['git','diff','--name-only',source,configuration_source],cwd=ROOT,text=True).splitlines()
        assert set(changed)<=set(['.dockerignore','deploy/scripts/build-workspace-tools.py']),'Resume only a reviewed packaging-only fix'
    else:
        out=data/('candidate-'+source[:12]+'-'+str(time.time_ns()));out.mkdir(parents=True)
        (out/'build-inputs.json').write_text(json.dumps({'source':source,'engineeringReceiptSha256':sha(args.engineering_receipt)},indent=2)+'\n',encoding='utf-8')
    actor=subprocess.check_output(['whoami'],text=True).strip()
    subprocess.run(['icacls',str(out),'/inheritance:r','/grant:r',actor+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F'],check=True,capture_output=True)
    node=ROOT/'.tools/node-v22.23.3-win-x64/node.exe';env=dict(os.environ,VITE_CHAT_ENABLED='true',VITE_PLAN_ENABLED='true',VITE_PERMISSIONS_ENABLED='true',VITE_WORKSPACE_ENABLED='true',VITE_WORKSPACE_FILES_ENABLED='true',VITE_WORKSPACE_TOOLS_ENABLED='true')
    commands=json.loads((out/'commands.json').read_text(encoding='utf-8')) if args.resume else []
    sequence=len(commands)
    def run(name,cmd,cwd=ROOT):
        start=time.monotonic()
        log_name=name+'-'+str(len(commands))+'.log'
        with (out/log_name).open('xb') as log:r=subprocess.run([str(a) for a in cmd],cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT)
        commands.append({'name':name,'exitCode':r.returncode,'seconds':round(time.monotonic()-start,2),'logSha256':sha(out/log_name)})
        (out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n',encoding='utf-8')
        assert r.returncode==0,name+' failed; read protected log before retrying'
        print(name+' PASS',flush=True)
    jar=ROOT/'backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar';assert jar.is_file()
    if not args.resume and not reuse:run('frontend-build',[node,ROOT/'frontend/node_modules/vite/bin/vite.js','build'],ROOT/'frontend')
    images={}
    for component in (['api','gateway','file-helper','preview-renderer','provider-fixture'] if args.stage=='r54' else ['api','gateway','file-helper','preview-renderer'] if args.stage=='r53' else ['api','gateway','file-helper'] if args.stage=='r52' else ['api','gateway','file-helper','provider-fixture']):
        if reuse and component!='api':
            item=json.loads(json.dumps(reuse['images'][component]));actual=json.loads(subprocess.check_output(['docker','--context','desktop-linux','image','inspect',item['tag']]))[0]
            assert actual['Id']==item['localId'] and actual['Config']['Labels']['org.opencontainers.image.revision']==item['source']
            item['transportIncluded']=False;item['reuseProof']=reuse_proofs[component];images[component]=item;continue
        tag='agentplatform-'+component+':'+args.stage+'-'+source[:12]
        cmd=['docker','--context','desktop-linux','build','--platform','linux/amd64','--network='+('default' if component=='preview-renderer' else 'none'),'--pull=false','--build-arg','SOURCE_REVISION='+source,'-f','deploy/'+component+'.Dockerfile','-t',tag]
        if component=='gateway':cmd+=['--build-arg','NGINX_CONFIG=deploy/nginx.files-local.conf' if args.stage in ['r52','r53'] else 'NGINX_CONFIG=deploy/nginx.production.conf']
        completed=any(row['name']==component+'-image' and row['exitCode']==0 for row in commands)
        if not completed:run(component+'-image',cmd+['.'])
        image=json.loads(subprocess.check_output(['docker','image','inspect',tag]))[0]
        assert image['Architecture']=='amd64' and image['Os']=='linux' and image['Config']['Labels']['org.opencontainers.image.revision']==source
        images[component]={'component':component,'tag':tag,'source':source,'inspect':image}
    archive=out/'artifacts.tar';run('save',['docker','image','save','-o',archive,*[x['tag'] for x in images.values() if 'inspect' in x]])
    spec=importlib.util.spec_from_file_location('image_export',ROOT/'deploy/scripts/export-project-image.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    with tarfile.open(archive) as exported:
        for component,item in images.items():
            if 'inspect' in item:item.update(module.exported_identity(exported,item.pop('inspect'),source));item['transportIncluded']=True
            item['platform']='linux/amd64'
    compressed=out/'artifacts.tar.gz'
    with archive.open('rb') as origin,compressed.open('xb') as destination:
        with gzip.GzipFile(filename='',fileobj=destination,mode='wb',compresslevel=1,mtime=0) as z:shutil.copyfileobj(origin,z,1024*1024)
    record={'status':'BUILT_EXPORTED_NOT_ACCEPTED','source':source,'buildConfigurationSource':configuration_source,'images':images,'archive':str(compressed),'archiveBytes':compressed.stat().st_size,'archiveSha256':sha(compressed),'uncompressedSha256':sha(archive),'jarSha256':sha(jar),'engineeringReceiptSha256':sha(args.engineering_receipt),'commands':commands,'supplierCalls':0}
    (out/'result.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8');(data/'candidate-pointer.json').write_text(json.dumps({'folder':str(out),'source':source},indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:record[k] for k in ['status','source','archiveBytes','archiveSha256']}));print(out)

if __name__=='__main__':main()
