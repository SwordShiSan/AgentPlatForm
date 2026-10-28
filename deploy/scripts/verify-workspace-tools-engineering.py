from pathlib import Path
import json,os,subprocess,time,hashlib,importlib.util
root=Path(__file__).resolve().parents[2];out=root/'.cache/r44-local'/('engineering-'+str(time.time_ns()));out.mkdir(parents=True)
module_spec=importlib.util.spec_from_file_location('r44_manifest',root/'deploy/scripts/verify-workspace-tool-admission.py');module=importlib.util.module_from_spec(module_spec);module_spec.loader.exec_module(module)
before=module.source_identity();(out/'source.json').write_text(json.dumps(before,indent=2),encoding='utf-8')
java=root/'.tools/jdk-21.0.12.1/jdk-21.0.12.1+1';env=dict(os.environ,JAVA_HOME=str(java),PATH=str(java/'bin')+os.pathsep+os.environ['PATH'],OPENSPEC_TELEMETRY='0',R44_ACCEPTANCE='1')
url='jdbc:postgresql://127.0.0.1:15457/r44_test?connectTimeout=2&socketTimeout=5';password=str(root/'.cache/r44-local/fixture/postgres-password.txt')
for stage in ['R41','R42','R43','R44']:env[stage+'_TEST_JDBC_URL']=url;env[stage+'_TEST_PASSWORD_FILE']=password
command=[str(root/'backend/mvnw.cmd'),'-B','-ntp','-f',str(root/'backend/pom.xml'),'verify']
start=time.monotonic()
with (out/'maven.log').open('wb') as log: result=subprocess.run(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
unchanged=before==module.source_identity();record={'status':'PASS' if result.returncode==0 and unchanged else 'FAIL','exitCode':result.returncode,'inputUnchanged':unchanged,'source':before['commit'],'inputManifestSha256':before['inputManifestSha256'],'logSha256':hashlib.sha256((out/'maven.log').read_bytes()).hexdigest(),'seconds':round(time.monotonic()-start,2),'supplierCalls':0}
(out/'result.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8');print(out);print(json.dumps(record))
if result.returncode:print('\n'.join((out/'maven.log').read_bytes().decode('utf-8',errors='replace').splitlines()[-60:]))
raise SystemExit(0 if record['status']=='PASS' else 1)
