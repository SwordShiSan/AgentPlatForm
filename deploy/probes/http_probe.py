"""Live public/minimal readiness and private management-port assertions."""
import json,pathlib,subprocess,time,urllib.request,urllib.error
root=pathlib.Path('/srv/agentplatform');results=[]
def get(path):
 try:
  with urllib.request.urlopen('http://127.0.0.1:18080'+path,timeout=10) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
def sql(text):subprocess.run(['docker','exec','agentplatform-env-postgres-1','psql','-U','ap_admin','-d','agentplatform','-v','ON_ERROR_STOP=1','-c',text],check=True,capture_output=True)
def wait_ready():
 for _ in range(40):
  if get('/api/v1/readiness')==(200,{'status':'READY'}):return
  time.sleep(.5)
 raise AssertionError('Readiness did not recover')
try:
 assert get('/api/v1/health')==(200,{'status':'UP'});wait_ready()
 sql('UPDATE pf.baseline_version SET schema_version=2 WHERE id=1')
 assert get('/api/v1/readiness')==(503,{'status':'NOT_READY'})
 assert get('/api/v1/health')==(200,{'status':'UP'})
 results.append({'case':'schema-incompatible-fails-readiness','status':'PASS'})
finally:sql('UPDATE pf.baseline_version SET schema_version=1 WHERE id=1')
wait_ready()
try:
 subprocess.run(['docker','stop','-t','30','agentplatform-env-redis-1'],check=True,capture_output=True)
 assert get('/api/v1/readiness')==(503,{'status':'NOT_READY'})
 assert get('/api/v1/health')==(200,{'status':'UP'})
 results.append({'case':'dependency-failure-fails-readiness-only','status':'PASS'})
finally:subprocess.run(['docker','start','agentplatform-env-redis-1'],check=True,capture_output=True)
wait_ready()
metrics=json.loads(subprocess.check_output(['docker','exec','agentplatform-app-api-1','wget','-q','-O','-','http://127.0.0.1:8081/actuator/metrics']))
assert 'jvm.memory.used' in metrics['names'] and 'process.uptime' in metrics['names']
assert get('/api/v1/actuator/metrics')[0]==404
results.append({'case':'metrics-private-port-public-path-denied','status':'PASS'})
(root/'reports/http.json').write_text(json.dumps(results,indent=2))
print('HTTP readiness/schema/dependency/private metrics:PASS')
