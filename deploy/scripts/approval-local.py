from pathlib import Path
import json,os,re,secrets,subprocess,sys,time,uuid,socket,argparse
ROOT=Path.cwd();DATA=ROOT/'.cache/r43-local';PRIVATE=DATA/'fixture'
parser=argparse.ArgumentParser(description='Operate only the isolated R4.3 local fixture; never production.');parser.add_argument('action',choices=['prepare','migrate','grant','seed','metadata','start','status','pause','stop']);MODE=parser.parse_args().action;STAMP=str(time.time_ns());LOG=DATA/('app-'+MODE+'-'+STAMP);LOG.mkdir()
record=json.loads((DATA/'images.json').read_text());images=record['images']
ENV=dict(os.environ,R43_ROOT=PRIVATE.as_posix(),R43_API_IMAGE=images['api']['tag'],R43_GATEWAY_IMAGE=images['gateway']['tag'],R43_FIXTURE_IMAGE=images['provider-fixture']['tag'])
COMPOSE=['docker','compose','-p','agentplatform-r43-local','-f',str(ROOT/'deploy/compose.approval-local.yaml')]
for image in images.values():
    actual=json.loads(subprocess.check_output(['docker','image','inspect',image['tag']],text=True))[0]
    assert actual['Id']==image['configId'],'Local image identity changed'
def call(args,*,input=None):
    result=subprocess.run(args,cwd=ROOT,env=ENV,input=input,text=True,capture_output=True)
    if result.returncode:
        (LOG/'failure.log').write_text(result.stdout+result.stderr,encoding='utf-8');raise RuntimeError('Local fixture action failed; inspect protected '+str(LOG/'failure.log'))
    return result.stdout
def sql(text,database='r43_app'):
    return call(['docker','exec','-i','agentplatform-r43-local-postgres-1','psql','-v','ON_ERROR_STOP=1','-q','-At','-U','postgres','-d',database],input=text)
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
    print('R43_PRIVATE_APP_INPUTS_READY')
elif MODE=='migrate':
    if sql("SELECT count(*) FROM pg_database WHERE datname='r43_app'",'postgres').strip()=='0':sql('CREATE DATABASE r43_app','postgres')
    output=operator([],profile='cloud-migrate');(LOG/'migration.log').write_text(output,encoding='utf-8')
    assert sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success').strip()=='17'
    print('R43_APPLICATION_SCHEMA17_READY')
elif MODE=='grant':
    for role,file in [('ap_migration','postgres-password.txt'),('ap_api','api-password'),('ap_worker','worker-password')]:
        password=(PRIVATE/file).read_text().strip();assert re.fullmatch('[A-Za-z0-9_-]+',password)
        sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" LOGIN; END IF; END $$;ALTER ROLE "+role+" PASSWORD '"+password+"';")
    for name in ['r1-runtime-grants.sql','r2-runtime-grants.sql','r4-runtime-grants.sql','r4-permission-grants.sql','r4-approval-grants.sql']:
        sql((ROOT/'deploy/config'/name).read_text(encoding='utf-8'))
    print('R43_API_WORKER_LEAST_PRIVILEGE_GRANTS_READY')
elif MODE=='seed':
    path=PRIVATE/'provision-requests.json'
    ids=json.loads(path.read_text()) if path.exists() else {name:str(uuid.uuid4()) for name in ['r43_admin','r43_user']}
    path.write_text(json.dumps(ids,indent=2)+'\n')
    for name,action,file in [('r43_admin','bootstrap','alice-password'),('r43_user','create','bob-password')]:
        operator(['--identity-action='+action,'--username='+name,'--request-id='+ids[name],'--password-file=/tmp/operator-input'],(PRIVATE/file).read_text(),profile='identity-ops')
    key=PRIVATE/'fixture-credential-id'
    if not key.exists():
        output=operator(['--chat-action=import-credential','--scope=PLATFORM','--key-file=/tmp/operator-input'],'R43_DETERMINISTIC_PROVIDER_SENTINEL')
        found=re.search(r'Credential imported: ([0-9a-f-]{36})',output);assert found;key.write_text(found.group(1))
    for name in ids:
        row=sql("SELECT u.id::text||','||s.id::text FROM pf.app_user u JOIN pf.space s ON s.owner_user_id=u.id WHERE u.username_norm='"+name+"' AND s.type='PERSONAL'").strip().split(',');assert len(row)==2
        operator(['--chat-action=bind-model','--source-policy=PLATFORM','--credential-id='+key.read_text().strip(),'--model-id=0199e000-0000-7000-8000-000000000002','--user-id='+row[0],'--space-id='+row[1]])
    print('R43_TWO_FIXTURE_ACCOUNTS_AND_ENCRYPTED_SENTINEL_READY')
elif MODE=='metadata':
    # These rows exercise only permissions/control-plane behavior. They are not allocated XFS resources.
    marker=PRIVATE/'workspace-metadata.json'
    if marker.exists():
        metadata=json.loads(marker.read_text())
        assert sql("SELECT count(*) FROM pf.workspace WHERE id='"+str(uuid.UUID(metadata['workspaceId']))+"' AND name='R43 browser metadata'").strip()=='1'
    else:
        actor=sql("SELECT u.id::text||','||s.id::text FROM pf.app_user u JOIN pf.space s ON s.owner_user_id=u.id WHERE u.username_norm='r43_user' AND s.type='PERSONAL'").strip().split(',');assert len(actor)==2
        user,space=[str(uuid.UUID(value)) for value in actor]
        workspace,operation,command,directory=[str(uuid.uuid4()) for _ in range(4)]
        sql("BEGIN;INSERT INTO pf.workspace(id,space_id,actor_id,name,state,quota_bytes,quota_inodes,project_id,operation_id) VALUES('"+workspace+"','"+space+"','"+user+"','R43 browser metadata','READY',2147483648,100000,(SELECT coalesce(max(project_id),200000)+1 FROM pf.workspace),'"+operation+"');INSERT INTO pf.workspace_operation(id,space_id,workspace_id,actor_id,command_id,status) VALUES('"+operation+"','"+space+"','"+workspace+"','"+user+"','"+command+"','COMMITTED');INSERT INTO pf.file_entry(id,space_id,workspace_id,relative_path,parent_path,name,kind,collection_state) VALUES('"+directory+"','"+space+"','"+workspace+"','docs','','docs','DIRECTORY','CONFIRMED');COMMIT;")
        marker.write_text(json.dumps({'workspaceId':workspace,'directoryId':directory,'physicalAllocation':False},indent=2)+'\n',encoding='utf-8')
    print('R43_CONTROL_PLANE_METADATA_ONLY_READY')
elif MODE=='start':
    with socket.socket() as port:
        if port.connect_ex(('127.0.0.1',18103))==0:
            names=call(['docker','ps','--filter','label=com.docker.compose.project=agentplatform-r43-local','--format','{{.Names}}']);assert 'gateway' in names,'Port belongs to another application'
    config=json.loads(call(COMPOSE+['--profile','fixture','config','--format','json']))
    caps=sum(int(s.get('mem_limit',0)) for k,s in config['services'].items() if k!='operator')+320*1024**2+512*1024**2
    assert caps<=2887*1024**2,'R43 resource admission failed'
    output=call(COMPOSE+['--profile','fixture','up','-d','--no-build','--wait','--wait-timeout','150','api','worker','fixture','gateway']);(LOG/'start.log').write_text(output)
    print('R43_HTTP_GATEWAY_READY_127_0_0_1_18103')
elif MODE in ['pause','stop']:
    call(COMPOSE+['--profile','fixture','stop','gateway','api','worker','fixture'])
    if MODE=='stop':call(['docker','compose','-f',str(PRIVATE/'compose.yaml'),'-f',str(PRIVATE/'mq.compose.yaml'),'stop','postgres','rabbitmq'])
    print('R43_ALL_ACCEPTANCE_SERVICES_STOPPED_VOLUMES_PRESERVED' if MODE=='stop' else 'R43_APPLICATION_PAUSED_DEPENDENCIES_RETAINED')
elif MODE=='status':
    print(call(['docker','ps','-a','--filter','label=com.docker.compose.project=agentplatform-r43-local','--format','{{.Names}} {{.Status}}']))
else:raise SystemExit('Unsupported local fixture action')
(LOG/'result.json').write_text(json.dumps({'status':'PASS','mode':MODE,'applicationSource':record['source'],'supplierCalls':0},indent=2)+'\n')
