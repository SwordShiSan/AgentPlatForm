"""Collect only whitelisted production receipts, never backups, credentials or raw logs."""
from pathlib import Path
import hashlib, importlib.util, json, subprocess

ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'.cache/r54-local'
GATES=['backup-r4','activate','start','smoke','observation','backup-r5','restore','rollback-drill','r4-drill-login','r4-business-guard','redeploy','completion-business-guard','completion-redeploy','final-smoke','final-observation','writes-open','completion','gateway-smoke']
FIELDS=['status','migration','runtimeBaseline','recordedAt','source','main','supplierCalls','elapsedSeconds','samples','brokerRestart','minAvailableBytes','minRootFreeBytes','swapUsedFirstBytes','swapUsedLastBytes','samplesNotAbsolutePeaks','requiredContainers','realWritesOpened','productionTag','configurationSha256','helperSha256','sameR4Containers','matchedSnapshotSchema','sourceUuidUnchanged','databaseFingerprints','credentialDecryption','immutableObjects','workspaceHashes','quotaGuard','missingMountRejected','readiness','workerStarted','temporaryResourcesStopped','crossUserGate','assertions','containers','login','historyReadOnly','noNewBusinessWrites','changedTables','scope','httpHosts','internalEndpoints','assets','dnsChecked']

def main():
    spec=importlib.util.spec_from_file_location('native',ROOT/'deploy/scripts/files-native-fixture.py')
    native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native);native.configure('r54')
    folder=json.loads((DATA/'release-pointer.json').read_text())['folder']
    code='''from pathlib import Path
import hashlib,json
r=Path(FOLDER);reports={}
for name in GATES:
 p=r/(name+'.json');raw=p.read_bytes();value=json.loads(raw);assert value['status']=='PASS',name
 reports[name]={'receiptSha256':hashlib.sha256(raw).hexdigest(),'result':{k:value[k] for k in FIELDS if k in value}}
stage=json.loads((r/'stage.json').read_text())
assert stage['realWritesOpened'] and stage['targetMigration']==21
completion=json.loads((r/'job-completion.json').read_text())
print(json.dumps({'reports':reports,'images':stage['images'],'resourceBudgetMiB':stage['resourceBudgetMiB'],'completionControl':{'inputSha256':completion['inputSha256'],'explicitUserApproval':True,'boundedSeconds':2700}}))
'''.replace('FOLDER',repr(folder)).replace('GATES',repr(GATES)).replace('FIELDS',repr(FIELDS))
    compile(code,'r54-whitelisted-release-evidence','exec')
    data=json.loads(native.remote(code,timeout=60))
    assert data['reports']['smoke']['result']['supplierCalls']<=20
    index=ROOT/'evidence/r54/acceptance-index.json';local=json.loads(index.read_text())
    assert local['sourceContinuity']['productionBackendInputsUnchanged'] and local['sourceContinuity']['productionFrontendContractInputsUnchanged']
    data.update(status='R5_RELEASED',migration=21,runtimeBaseline=1,sourceAtCollection=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),applicationSource=local['applicationSource'],localAcceptanceIndexSha256=hashlib.sha256(index.read_bytes()).hexdigest(),supplierCalls=data['reports']['smoke']['result']['supplierCalls'],scope='Fresh cloud gates; matching unchanged local gates reused. Sampled resources are not absolute peaks. Private backups and raw logs remain on the protected host.')
    out=ROOT/'evidence/r54/production-release.json';out.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'status':data['status'],'gates':len(GATES),'supplierCalls':data['supplierCalls'],'sha256':hashlib.sha256(out.read_bytes()).hexdigest()}))

if __name__=='__main__':main()
