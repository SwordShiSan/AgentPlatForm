"""One targeted broker interruption and safe old-binary startup compatibility probe."""
from pathlib import Path
import argparse,importlib.util,json,time,uuid
ROOT=Path(__file__).resolve().parents[2]
def load(name,file):
 s=importlib.util.spec_from_file_location(name,ROOT/'deploy/scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.configure('r52');return m
api=load('api52faults','files-native-api.py');native=load('native52faults','files-native-fixture.py');DATA=native.DATA
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['broker','compat']);args=p.parse_args();plan=json.loads((DATA/'native-plan.json').read_text());root=plan['root']
 prefix='from pathlib import Path\nimport importlib.util,json,subprocess,time\nr=Path('+repr(root)+')\ns=importlib.util.spec_from_file_location("window",r/"files-native-window.py");m=importlib.util.module_from_spec(s);s.loader.exec_module(m)\n'
 if args.action=='compat':
  code='''before=m.sql('SELECT count(*) FROM pf.app_user');assert m.sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success')=='19'
old=json.loads(m.call(['docker','inspect','agentplatform-r4-app-api-1']))[0]['Image'];overlay=r/'compat-image.json';overlay.write_text(json.dumps({'services':{'operator':{'image':old}}}))
m.call(m.BASE+['stop','-t','10','api'])
try:
 requests=json.loads((r/'accounts/requests.json').read_text());shell='umask 077; cat > /tmp/operator-input; java -Xms16m -Xmx112m -jar /app/platform.jar "$@"; exit "$?"'
 command=m.BASE+['-f',str(overlay),'--profile','operator','run','--rm','-T','--no-deps','-e','SPRING_PROFILES_ACTIVE=identity-ops','--entrypoint','sh','operator','-c',shell,'operator','--identity-action=create','--username=r52_user','--request-id='+requests['r52_user'],'--password-file=/tmp/operator-input']
 m.call(command,(r/'accounts/r52_user.password').read_bytes(),timeout=60);assert m.sql('SELECT count(*) FROM pf.app_user')==before
finally:m.call(m.BASE+['up','-d','--no-deps','--wait','--wait-timeout','120','api'],timeout=150)
print(json.dumps({'status':'PASS','oldImage':old,'schemaVersion':19,'runtimeBaseline':1,'scope':'old R4 identity binary startup and idempotent provisioning against V19; no old file executor over new tombstone semantics','supplierCalls':0}))
'''
  result=json.loads(native.remote(prefix+code,timeout=180));(DATA/'native-compat-result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));return
 client=api.Client();ids=json.loads((DATA/'native-file-identities.json').read_text());entry=client.request('GET','/api/v1/files/'+ids['browser']['id'])[1]
 key=str(uuid.uuid4());body={'targetParentId':None,'name':'after-broker-recovery.txt'};headers={'Idempotency-Key':key,'If-Match':'"'+str(entry['revision'])+'"'}
 frozen=DATA/'broker-request.json';assert not frozen.exists(),'Original fault request exists; query it instead';frozen.write_text(json.dumps({'key':key,'fileId':entry['id'],'body':body,'headers':headers}))
 native.remote(prefix+"m.call(m.BASE+['stop','-t','5','rabbitmq']);print('ISOLATED_BROKER_STOPPED')\n")
 try:
  op=client.request('POST','/api/v1/files/'+entry['id']+'/move',body,headers)[1]
  assert client.request('GET','/api/v1/file-operations/'+op['id'])[1]['state']=='PENDING'
  assert client.request('POST','/api/v1/files/'+entry['id']+'/move',body,headers)[1]['id']==op['id']
 finally:native.remote(prefix+"m.call(m.BASE+['up','-d','--wait','--wait-timeout','90','rabbitmq'],timeout=120);print('SAME_BROKER_READY')\n",timeout=150)
 result=api.wait_operation(client,op['id']);assert result['state']=='COMMITTED',result
 current=client.request('GET','/api/v1/files/'+entry['id'])[1];assert current['id']==entry['id'] and current['versionId']==entry['versionId'] and current['path']==body['name']
 report={'status':'PASS','root':root,'operationId':op['id'],'scope':'actual broker stopped: accepted outbox remains pending, repeated idempotent command same operation, same broker recovery commits one move without version change','supplierCalls':0}
 (DATA/'native-broker-result.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
if __name__=='__main__':main()
