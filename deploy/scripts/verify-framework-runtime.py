"""Verify final R2.C runtime identities, privileges, resource limits and sentinel secrecy."""
import os,json,subprocess
from pathlib import Path
assert os.environ.get("AGENTPLATFORM_ACCEPTANCE_STAGE") == "r2c", "Explicit isolated R2.C fixture required"
from execution_fixture import ROOT,DATA,ENV,COMPOSE,PROJECT
from execution_images import pin_exported
pin_exported(ENV,DATA,required=True)
def command(args):
    return subprocess.run(args,cwd=ROOT,env=ENV,check=True,text=True,capture_output=True).stdout.strip()
def sql(query):
    return command(COMPOSE+['exec','-T','postgres','psql','-XAt','-v','ON_ERROR_STOP=1','-U','postgres','-d','chat','-c',query])
ids=command(COMPOSE+['ps','-q']).splitlines()
containers=json.loads(command(['docker','inspect',*ids]))
images=json.loads((DATA/'artifacts/images.json').read_text())
api=next(i for i in images if i['component']=='api')
assert sql('SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL')=='0'
assert sql('SELECT physical_calls FROM pf.acceptance_model_meter WHERE id=1')=='0'
assert sql("SELECT has_column_privilege('ap_chat_api','pf.credential','ciphertext','SELECT')")=='f'
assert sql("SELECT has_table_privilege('ap_chat_worker','pf.stopped_owner_proof','SELECT')")=='f'
assert sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success')=='6'
threads={}
for item in containers:
    service=item['Config']['Labels']['com.docker.compose.service']
    assert item['Config']['Labels']['com.docker.compose.project']==PROJECT
    assert item['HostConfig']['Memory']==item['HostConfig']['MemorySwap']
    assert not item['State']['OOMKilled']
    if service in ['api','worker','worker-2']:
        assert item['Config']['Image'] in [api['localId'],api['configId']]
        assert item['Config']['User']=='10001:10001' and item['HostConfig']['ReadonlyRootfs']
        assert not any('docker.sock' in str(m) for m in item['Mounts'])
        threads[service]=int(command(['docker','exec',item['Id'],'sh','-c','ls /proc/1/task | wc -l']))
rawlogs=command(COMPOSE+['logs','--no-color','api','worker','worker-2'])
dump=command(COMPOSE+['exec','-T','postgres','pg_dump','--data-only','-U','postgres','-d','chat'])
for secret in ['R12_FIXTURE_CREDENTIAL_SENTINEL','R13_USER_KEY_SENTINEL_NOT_A_REAL_KEY']:
    assert secret not in rawlogs and secret not in dump,'Sentinel leaked in logs/database plaintext'
query="SELECT json_build_object('calls', (SELECT count(*) FROM pf.model_call), 'unknownCalls',(SELECT count(*) FROM pf.model_call WHERE status='UNKNOWN'),'reconcilingReservations',(SELECT count(*) FROM pf.budget_reservation WHERE state='RECONCILING'),'attempts',(SELECT count(*) FROM pf.attempt),'turns',(SELECT count(*) FROM pf.model_turn))"
result={'status':'PASS','source':api['source'],'realProviderCalls':0,'schema':6,'credentialsEncrypted':True,'apiCiphertextReadDenied':True,'workerStopProofReadDenied':True,'sentinelLeaks':0,'threadSamples':threads,'ledger':json.loads(sql(query)),'containerLimitsMiB':sum(c['HostConfig']['Memory'] for c in containers)/1048576,'containerIdentities':[{'service':c['Config']['Labels']['com.docker.compose.service'],'id':c['Id'],'image':c['Image'],'configuredImage':c['Config']['Image']} for c in containers]}
assert result['containerLimitsMiB']<=2887
(DATA/'acceptance/runtime-audit.json').write_text(json.dumps(result,indent=2))
print(json.dumps({k:v for k,v in result.items() if k!='containerIdentities'}))
