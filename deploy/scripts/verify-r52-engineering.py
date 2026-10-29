"""Whole-reactor R52 engineering gates; external integration receipts remain separate."""
from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,time
ROOT=Path(__file__).resolve().parents[2]
def main():
 spec=importlib.util.spec_from_file_location('identity',ROOT/'deploy/scripts/verify-workspace-tool-admission.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 out=ROOT/'.cache/r52-local'/('engineering-'+str(time.time_ns()));out.mkdir(parents=True)
 before=m.source_identity();(out/'source.json').write_text(json.dumps(before,indent=2),encoding='utf-8')
 java=ROOT/'.tools/jdk-21.0.12.1/jdk-21.0.12.1+1';env=dict(os.environ,JAVA_HOME=str(java),PATH=str(java/'bin')+os.pathsep+os.environ['PATH'],OPENSPEC_TELEMETRY='0')
 for name in list(env):
  if name.endswith('_ACCEPTANCE') or '_TEST_JDBC_URL' in name or '_TEST_PASSWORD_FILE' in name:env.pop(name)
 started=time.monotonic()
 with (out/'maven.log').open('wb') as log:p=subprocess.run([str(ROOT/'backend/mvnw.cmd'),'-B','-ntp','-o','-f',str(ROOT/'backend/pom.xml'),'verify'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
 after=m.source_identity();unchanged={k:v for k,v in before['files'].items() if k.startswith('backend/')}=={k:v for k,v in after['files'].items() if k.startswith('backend/')};result={'status':'PASS' if p.returncode==0 and unchanged else 'FAIL','exitCode':p.returncode,'inputUnchanged':unchanged,'source':before['commit'],'seconds':round(time.monotonic()-started,2),'supplierCalls':0,'logSha256':hashlib.sha256((out/'maven.log').read_bytes()).hexdigest(),'scope':'whole reactor including architecture/Enforcer/unit tests; backend input identity is checked separately from deployment scripts; environment-dependent skipped tests remain NOT_RUN and use separate actual integration receipts'}
 (out/'result.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(out);print(json.dumps(result))
 if p.returncode:print((out/'maven.log').read_text(encoding='utf-8',errors='replace')[-7000:])
 return 0 if result['status']=='PASS' else 1
if __name__=='__main__':raise SystemExit(main())
