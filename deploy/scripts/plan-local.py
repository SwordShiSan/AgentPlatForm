from pathlib import Path
import json,os,re,secrets,subprocess,sys,time,uuid,socket,argparse
ROOT=Path.cwd();DATA=ROOT/'.cache/r41-local';PRIVATE=DATA/'fixture'
parser=argparse.ArgumentParser(description='Operate only the isolated R4.1 local fixture; never production.');parser.add_argument('action',choices=['prepare','migrate','grant','seed','start','status','stop']);MODE=parser.parse_args().action;STAMP=str(time.time_ns());LOG=DATA/('app-'+MODE+'-'+STAMP);LOG.mkdir()
record=json.loads((DATA/'images.json').read_text());images=record['images']
ENV=dict(os.environ,R41_ROOT=PRIVATE.as_posix(),R41_API_IMAGE=images['api']['tag'],R41_GATEWAY_IMAGE=images['gateway']['tag'],R41_FIXTURE_IMAGE=images['provider-fixture']['tag'])
COMPOSE=['docker','compose','-p','agentplatform-r41-local','-f',str(ROOT/'deploy/compose.plan-local.yaml')]
for image in images.values():
    actual=json.loads(subprocess.check_output(['docker','image','inspect',image['tag']],text=True))[0]
    assert actual['Id']==image['configId'],'Local image identity changed'
def call(args,*,input=None):
    result=subprocess.run(args,cwd=ROOT,env=ENV,input=input,text=True,capture_output=True)
    if result.returncode:
        (LOG/'failure.log').write_text(result.stdout+result.stderr,encoding='utf-8');raise RuntimeError('Local fixture action failed; inspect protected '+str(LOG/'failure.log'))
    return result.stdout
def sql(text,database='r41_app'):
    return call(['docker','exec','-i','agentplatform-r41-local-postgres-1','psql','-v','ON_ERROR_STOP=1','-q','-At','-U','postgres','-d',database],input=text)
def operator(args,private=None,profile='chat-ops'):
    command=COMPOSE+['--profile','operator','run','--rm','-T','--no-deps','-e','SPRING_PROFILES_ACTIVE='+profile]
    if private is None:return call(command+['operator']+args)
    shell='umask 077; cat > /tmp/operator-input; java -Xms16m -Xmx160m -jar /app/platform.jar "$@"; result=$?; rm -f /tmp/operator-input; exit "$result"'
    return call(command+['--entrypoint','sh','operator','-c',shell,'operator']+args,input=private)
if MODE=='prepare':
    for name in ['api-password','worker-password','alice-password','bob-password']:
        p=PRIVATE/name
        if not p.exists():p.write_text(secrets.token_urlsafe(32),encoding='utf-8')
    for name in ['master-key','cursor-key']:
        p=PRIVATE/name
        if not p.exists():p.write_bytes(secrets.token_bytes(32))
    call(COMPOSE+['--profile','fixture','config','--quiet'])
    print('R41_PRIVATE_APP_INPUTS_READY')
elif MODE=='migrate':
    if sql("SELECT count(*) FROM pg_database WHERE datname='r41_app'",'postgres').strip()=='0':sql('CREATE DATABASE r41_app','postgres')
    output=operator([],profile='cloud-migrate');(LOG/'migration.log').write_text(output,encoding='utf-8')
    assert sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success').strip()=='15'
    print('R41_APPLICATION_SCHEMA15_READY')
elif MODE=='grant':
    for role,file in [('ap_migration','postgres-password.txt'),('ap_api','api-password'),('ap_worker','worker-password')]:
        password=(PRIVATE/file).read_text().strip();assert re.fullmatch('[A-Za-z0-9_-]+',password)
        sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" LOGIN; END IF; END $$;ALTER ROLE "+role+" PASSWORD '"+password+"';")
    for name in ['r1-runtime-grants.sql','r2-runtime-grants.sql','r4-runtime-grants.sql']:
        sql((ROOT/'deploy/config'/name).read_text(encoding='utf-8'))
    print('R41_API_WORKER_LEAST_PRIVILEGE_GRANTS_READY')
elif MODE=='seed':
    path=PRIVATE/'provision-requests.json'
    ids=json.loads(path.read_text()) if path.exists() else {name:str(uuid.uuid4()) for name in ['r41_admin','r41_user']}
    path.write_text(json.dumps(ids,indent=2)+'\n')
    for name,action,file in [('r41_admin','bootstrap','alice-password'),('r41_user','create','bob-password')]:
        operator(['--identity-action='+action,'--username='+name,'--request-id='+ids[name],'--password-file=/tmp/operator-input'],(PRIVATE/file).read_text(),profile='identity-ops')
    key=PRIVATE/'fixture-credential-id'
    if not key.exists():
        output=operator(['--chat-action=import-credential','--scope=PLATFORM','--key-file=/tmp/operator-input'],'R41_DETERMINISTIC_PROVIDER_SENTINEL')
        found=re.search(r'Credential imported: ([0-9a-f-]{36})',output);assert found;key.write_text(found.group(1))
    for name in ids:
        row=sql("SELECT u.id::text||','||s.id::text FROM pf.app_user u JOIN pf.space s ON s.owner_user_id=u.id WHERE u.username_norm='"+name+"' AND s.type='PERSONAL'").strip().split(',');assert len(row)==2
        operator(['--chat-action=bind-model','--source-policy=PLATFORM','--credential-id='+key.read_text().strip(),'--model-id=0199e000-0000-7000-8000-000000000002','--user-id='+row[0],'--space-id='+row[1]])
    print('R41_TWO_FIXTURE_ACCOUNTS_AND_ENCRYPTED_SENTINEL_READY')
elif MODE=='start':
    with socket.socket() as port:
        if port.connect_ex(('127.0.0.1',18101))==0:
            names=call(['docker','ps','--filter','label=com.docker.compose.project=agentplatform-r41-local','--format','{{.Names}}']);assert 'gateway' in names,'Port belongs to another application'
    config=json.loads(call(COMPOSE+['--profile','fixture','config','--format','json']))
    caps=sum(int(s.get('mem_limit',0)) for k,s in config['services'].items() if k!='operator')+320*1024**2+512*1024**2
    assert caps<=2887*1024**2,'R41 resource admission failed'
    output=call(COMPOSE+['--profile','fixture','up','-d','--no-build','--wait','--wait-timeout','150','api','worker','fixture','gateway']);(LOG/'start.log').write_text(output)
    print('R41_HTTP_GATEWAY_READY_127_0_0_1_18101')
elif MODE=='stop':
    call(COMPOSE+['--profile','fixture','stop','gateway','api','worker','fixture'])
    call(['docker','compose','-f',str(PRIVATE/'compose.yaml'),'-f',str(PRIVATE/'mq.compose.yaml'),'stop','postgres','rabbitmq'])
    print('R41_ALL_ACCEPTANCE_SERVICES_STOPPED_VOLUMES_PRESERVED')
elif MODE=='status':
    print(call(['docker','ps','-a','--filter','label=com.docker.compose.project=agentplatform-r41-local','--format','{{.Names}} {{.Status}}']))
else:raise SystemExit('Unsupported local fixture action')
(LOG/'result.json').write_text(json.dumps({'status':'PASS','mode':MODE,'applicationSource':record['source'],'supplierCalls':0},indent=2)+'\n')
