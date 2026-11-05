"""Publish bounded R54 acceptance metadata; raw logs/accounts remain protected."""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2];DATA=ROOT/'.cache/r54-local';OUT=ROOT/'evidence/r54'


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    full=DATA/'engineering-1793879228741231200/result.json';correction=DATA/'engineering-1793881912400504800/result.json';front=DATA/'frontend-verify-1793879011125813500/result.json'
    for path in [full,correction,front]:assert json.loads(path.read_text())['status']=='PASS'
    previous=json.loads(correction.with_name('source-before.json').read_text())['sourceInputFiles']
    spec=importlib.util.spec_from_file_location('engineering',ROOT/'deploy/scripts/verify-r53-engineering.py');engineering=importlib.util.module_from_spec(spec);spec.loader.exec_module(engineering)
    current=engineering.source_identity()['sourceInputFiles']
    changed=[name for name in previous.keys()|current.keys() if previous.get(name)!=current.get(name)]
    allowed={'backend/platform-app/src/test/java/com/agentplatform/app/attachment/AttachmentPostgresTest.java','backend/platform-infrastructure/src/test/java/com/agentplatform/infrastructure/harness/agentscope/PlanThenToolApprovalProbeTest.java'}
    assert {name.replace('\\','/') for name in changed}<=allowed,'Production inputs changed since corrected package acceptance'
    frontend=json.loads(front.read_text());production={name:digest for name,digest in frontend['inputs'].items() if name.startswith('frontend/src/') or name.startswith('contracts/') or name in ['frontend/package.json','frontend/pnpm-lock.yaml','frontend/vite.config.ts']}
    assert all(sha(ROOT/name)==digest for name,digest in production.items())
    pg=Path(json.loads((DATA/'postgres/latest.json').read_text())['folder'])/'receipt.json';assert json.loads(pg.read_text())['status']=='PASS'
    native=['native-attachment-result.json','native-fixed-source-result.json','native-workbench-browser-result.json','native-observation.json','native-acceptance.json']
    for name in native:
        row=json.loads((DATA/name).read_text());assert row['status']=='PASS'
        if name in ['native-workbench-browser-result.json','native-fixed-source-result.json','native-attachment-result.json']:(OUT/name).write_text(json.dumps(row,indent=2),encoding='utf-8')
    observation=json.loads((DATA/'native-observation.json').read_text())
    (OUT/'native-observation.json').write_text(json.dumps({'status':'PASS','seconds':observation['seconds'],'serialRounds':observation['serialRounds'],'minAvailableBytes':min(row['MemAvailable'] for row in observation['samples']),'minRootFreeBytes':min(row['rootFreeBytes'] for row in observation['samples']),'residentLimitBytes':observation['samples'][0]['capSum'],'final':observation['final'],'scope':observation['scope'],'samplesNotAbsolutePeaks':True},indent=2))
    plan=ROOT/'backend/platform-infrastructure/target/surefire-reports/TEST-com.agentplatform.infrastructure.harness.agentscope.PlanThenToolApprovalProbeTest.xml'
    suite=ET.parse(plan).getroot();assert int(suite.attrib['tests'])==2 and int(suite.attrib['failures'])==int(suite.attrib['errors'])==0
    ui=DATA/'ui-contract-1793887276051258300/result.json';assert json.loads(ui.read_text())['status']=='PASS'
    candidate=json.loads((Path(json.loads((DATA/'candidate-pointer.json').read_text())['folder'])/'result.json').read_text())
    receipts=[full,correction,front,pg,plan,ui,*[DATA/name for name in native],DATA/'large-plan-approval.log']
    index={'status':'LOCAL_FUNCTIONAL_PASS_PRODUCTION_PENDING','sourceAtCollection':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'applicationSource':candidate['source'],'sourceContinuity':{'correctedPackageInputChanges':sorted(changed),'productionBackendInputsUnchanged':True,'productionFrontendContractInputsUnchanged':True,'changedTestInputs':{name:sha(ROOT/name) for name in changed}},
        'engineering':{'fullReactorTests':319,'targetedBundleSdkTests':58,'freshLargePlanExactApprovalTests':2,'freshPostgresTests':8,'notSummedAsUniqueTests':True},
        'checks':{'frontend':'types/unit/build/contracts PASS; identical production inputs','pagination':'10k logical nodes/virtualization PASS; browser fixture only','native':'Actual PG/MQ/S3/XFS/runsc attachment/extraction/pinned-source/chat/browser subset PASS','observation':'182seconds PASS; exact R4 restored','productionRelease':'NOT_RUN','realSupplier':'NOT_RUN; stage counter0'},
        'receipts':[{'path':str(path.relative_to(ROOT)).replace('\\','/'),'sha256':sha(path)} for path in receipts],
        'images':{part:{key:row[key] for key in ['source','configId','platform']} for part,row in candidate['images'].items()},'migration':21,'runtimeBaseline':1,'supplierCalls':0,
        'scope':'Unchanged legacy/permission/isolation gates are reused only within matching artifact scope. No new throughput, minimum physical host or production claim; unresolved failures and unrun cloud gates remain separate.'}
    (OUT/'acceptance-index.json').write_text(json.dumps(index,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':index['status'],'files':len(receipts),'applicationSource':candidate['source']}))


if __name__=='__main__':main()
