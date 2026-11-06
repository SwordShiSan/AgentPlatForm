"""One entry point for the isolated R6.1 local acceptance stack.

Never operates on production. The fixture keeps its own credentials, volumes and network.
"""
from pathlib import Path
import argparse
import base64
import hashlib
import ipaddress
import json
import os
import re
import secrets
import socket
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache' / 'r61-local'
PRIVATE = DATA / 'fixture'
COMPOSE = ['docker', 'compose', '-p', 'agentplatform-r61-local', '-f', str(ROOT / 'deploy/compose.r61-local.yaml')]
RABBIT = 'rabbitmq:4.3.6-management@sha256:8dd6e3570ddaa2ef82a6c3a8950e79c1f73893adfbdddbc374fc1f1ac8a0f5dd'

def call(args, *, env=None, input_text=None, timeout=300, sensitive=False, cwd=ROOT):
    result = subprocess.run([str(item) for item in args], cwd=cwd, env=env, input=input_text,
                            text=True, capture_output=True, timeout=timeout, errors='replace')
    if result.returncode:
        DATA.mkdir(parents=True, exist_ok=True)
        log = DATA / ('failure-' + str(time.time_ns()) + '.log')
        log.write_text('Sensitive operation failed; output withheld.\n' if sensitive else result.stdout + result.stderr, encoding='utf-8')
        raise RuntimeError('Local acceptance operation failed; inspect protected ' + str(log))
    return result.stdout

def source_manifest():
    paths = call(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z', '--', 'backend', 'frontend', 'contracts', 'deploy']).split('\0')
    return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in sorted(set(paths) - {''}) if (ROOT / name).is_file()}

def port_available(port):
    with socket.socket() as probe:
        return probe.connect_ex(('127.0.0.1', port)) != 0

def prepare():
    PRIVATE.mkdir(parents=True, exist_ok=True)
    if os.name == 'nt':
        user = call(['whoami']).strip()
        call(['icacls', str(PRIVATE), '/inheritance:r', '/grant:r', user + ':(OI)(CI)F', 'SYSTEM:(OI)(CI)F'])
    for name in ['postgres-password', 'api-password', 'worker-password', 'admin-password', 'user-password',
                 'mq-api-password', 'mq-worker-password', 'mq-operator-password']:
        path = PRIVATE / name
        if not path.exists(): path.write_text(secrets.token_urlsafe(32), encoding='utf-8')
    for name in ['master-key', 'cursor-key']:
        path = PRIVATE / name
        if not path.exists(): path.write_bytes(secrets.token_bytes(32))
    manifest = PRIVATE / 'manifest.json'
    if not manifest.exists():
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        manifest.write_text(json.dumps({'project': 'agentplatform-r61-local', 'gatewayPort': port}, indent=2) + '\n', encoding='utf-8')
    saved=json.loads(manifest.read_text(encoding='utf-8'))
    if 'database' not in saved:
        saved['database']='r61_app'
        manifest.write_text(json.dumps(saved,indent=2)+'\n',encoding='utf-8')
    if 'subnet' not in saved:
        ids=call(['docker','network','ls','-q']).split()
        networks=json.loads(call(['docker','network','inspect',*ids])) if ids else []
        occupied=[]
        for network in networks:
            for item in network.get('IPAM',{}).get('Config',[]) or []:
                if item.get('Subnet'): occupied.append(ipaddress.ip_network(item['Subnet']))
        for number in range(1,255):
            candidate=ipaddress.ip_network('10.236.'+str(number)+'.0/24')
            if not any(candidate.version==existing.version and candidate.overlaps(existing) for existing in occupied):
                saved['subnet']=str(candidate);break
        if 'subnet' not in saved: raise RuntimeError('No independent fixture subnet available')
        manifest.write_text(json.dumps(saved,indent=2)+'\n',encoding='utf-8')
    users = []
    for name in ['api', 'worker', 'operator']:
        value = (PRIVATE / ('mq-' + name + '-password')).read_text().strip()
        salt = secrets.token_bytes(4)
        users.append({'name': 'ap_' + name, 'password_hash': base64.b64encode(salt + hashlib.sha256(salt + value.encode()).digest()).decode(),
                      'hashing_algorithm': 'rabbit_password_hashing_sha256', 'tags': ['administrator'] if name == 'operator' else []})
    def permission(name, configure, write, read):
        return {'user': 'ap_' + name, 'vhost': '/agentplatform', 'configure': configure, 'write': write, 'read': read}
    definitions = {'users': users, 'vhosts': [{'name': '/agentplatform'}],
      'permissions': [permission('api', '^$', '^ap.commands.v1$', '^$'), permission('worker', '^$', '^$', '^ap.execution.v1$'), permission('operator', '.*', '.*', '.*')],
      'exchanges': [{'name': name, 'vhost': '/agentplatform', 'type': 'direct', 'durable': True, 'auto_delete': False, 'internal': False, 'arguments': {}} for name in ['ap.commands.v1', 'ap.dead.v1']],
      'queues': [{'name': name, 'vhost': '/agentplatform', 'durable': True, 'auto_delete': False, 'arguments': args} for name, args in [('ap.execution.v1', {'x-queue-type':'quorum','x-dead-letter-exchange':'ap.dead.v1','x-dead-letter-routing-key':'execution.dead'}), ('ap.execution.dead.v1', {'x-queue-type':'quorum'})]],
      'bindings': [{'source': src, 'vhost': '/agentplatform', 'destination': dst, 'destination_type': 'queue', 'routing_key': key, 'arguments': {}} for src,dst,key in [('ap.commands.v1','ap.execution.v1','execution.ready'),('ap.dead.v1','ap.execution.dead.v1','execution.dead')]]}
    if not (PRIVATE / 'rabbitmq-definitions.json').exists():
        (PRIVATE / 'rabbitmq-definitions.json').write_text(json.dumps(definitions), encoding='utf-8')
    if not (PRIVATE / 'rabbitmq.conf').exists():
        (PRIVATE / 'rabbitmq.conf').write_text('definitions.import_backend = local_filesystem\ndefinitions.local.path = /etc/rabbitmq/definitions.json\nvm_memory_high_watermark.absolute = 256MiB\ndisk_free_limit.absolute = 256MiB\nlog.console.level = warning\nconsumer_timeout = 180000\n', encoding='utf-8')
    print('R61 isolated inputs ready')

def build():
    prepare(); before = source_manifest(); sha = call(['git', 'rev-parse', 'HEAD']).strip()
    java = ROOT / '.tools/jdk-21.0.12.1/jdk-21.0.12.1+1'
    node = ROOT / '.tools/node-v22.23.3-win-x64/node.exe'
    env = dict(os.environ, JAVA_HOME=str(java), PATH=str(java/'bin')+os.pathsep+str(node.parent)+os.pathsep+os.environ['PATH'],
               VITE_CHAT_ENABLED='true', VITE_PLAN_ENABLED='true', VITE_PERMISSIONS_ENABLED='true',
               VITE_WORKSPACE_ENABLED='true', VITE_PERSONAL_CONFIG_ENABLED='true')
    temporary = DATA / 'tmp'; temporary.mkdir(parents=True, exist_ok=True); env.update(TEMP=str(temporary),TMP=str(temporary))
    call([ROOT/'backend/mvnw.cmd','-B','-ntp','-f',ROOT/'backend/pom.xml','-DskipTests','package'],env=env,timeout=600)
    call([node,ROOT/'frontend/node_modules/vite/bin/vite.js','build'],env=env,timeout=300,cwd=ROOT/'frontend')
    assert before == source_manifest(), 'Source changed while building'
    identity = hashlib.sha256(json.dumps(before,sort_keys=True).encode()).hexdigest()
    images = {}
    for component in ['api','gateway','provider-fixture']:
        tag = 'agentplatform-' + component + ':r61-' + identity[:12]
        args = ['docker','build','--platform','linux/amd64','--pull=false','--build-arg','SOURCE_REVISION='+sha,'-f','deploy/'+component+'.Dockerfile','-t',tag]
        if component == 'gateway': args += ['--build-arg','NGINX_CONFIG=deploy/nginx.chat-local.conf']
        call(args+['.'],env=env,timeout=600)
        row=json.loads(call(['docker','image','inspect',tag]))[0]
        assert row['Architecture']=='amd64' and row['Os']=='linux'
        images[component]={'tag':tag,'configId':row['Id']}
    (DATA/'images.json').write_text(json.dumps({'source':sha,'inputHash':identity,'images':images},indent=2)+'\n',encoding='utf-8')
    (DATA/'build-inputs.json').write_text(json.dumps(before,indent=2)+'\n',encoding='utf-8')
    print('R61 local images built:', identity)

def environment(validate_source=True):
    images=json.loads((DATA/'images.json').read_text(encoding='utf-8'))
    if validate_source:
        for image in images['images'].values():
            assert json.loads(call(['docker','image','inspect',image['tag']]))[0]['Id']==image['configId'], 'Image identity changed'
        assert hashlib.sha256(json.dumps(source_manifest(),sort_keys=True).encode()).hexdigest()==images['inputHash'], 'Source changed since build'
    manifest=json.loads((PRIVATE/'manifest.json').read_text(encoding='utf-8'))
    return dict(os.environ,R61_ROOT=PRIVATE.as_posix(),R61_PORT=str(manifest['gatewayPort']),R61_SUBNET=manifest['subnet'],R61_DATABASE=manifest['database'],
        R61_API_IMAGE=images['images']['api']['tag'],R61_GATEWAY_IMAGE=images['images']['gateway']['tag'],R61_FIXTURE_IMAGE=images['images']['provider-fixture']['tag'])

def start():
    prepare(); env=environment(); port=int(env['R61_PORT'])
    existing=call(['docker','ps','--filter','label=com.docker.compose.project=agentplatform-r61-local','--format','{{.Names}}'])
    if not port_available(port) and 'gateway' not in existing: raise RuntimeError('Fixture gateway port is occupied')
    memory=int(call(['docker','info','--format','{{.MemTotal}}']).strip())
    if memory < (2080+768)*1024*1024: raise RuntimeError('Docker memory does not satisfy fixture budget')
    call(COMPOSE+['config','--quiet'],env=env)
    call(COMPOSE+['up','-d','--no-build','--wait','postgres'],env=env,timeout=180)
    call(COMPOSE+['create','rabbitmq'],env=env)
    volume='agentplatform-r61-local_mq-data'
    metadata=json.loads(call(['docker','volume','inspect',volume]))[0]
    assert metadata.get('Labels',{}).get('com.docker.compose.project')=='agentplatform-r61-local'
    repair='if [ -f /var/lib/rabbitmq/.erlang.cookie ]; then chown rabbitmq:rabbitmq /var/lib/rabbitmq/.erlang.cookie; chmod 600 /var/lib/rabbitmq/.erlang.cookie; fi'
    call(['docker','run','--rm','--network','none','--read-only','--user','0:0','--cap-drop','ALL','--cap-add','CHOWN','--cap-add','FOWNER',
          '--security-opt','no-new-privileges:true','--memory','64m','--memory-swap','64m','--pids-limit','16','--entrypoint','sh',
          '--mount','type=volume,source='+volume+',target=/var/lib/rabbitmq',RABBIT,'-eu','-c',repair],timeout=120)
    call(COMPOSE+['up','-d','--no-build','--wait','rabbitmq'],env=env,timeout=180)
    database=env['R61_DATABASE'];assert re.fullmatch(r'r61_[a-z0-9_]+',database)
    def sql(text,sensitive=False,db=None): return call(['docker','exec','-i','agentplatform-r61-local-postgres-1','psql','-v','ON_ERROR_STOP=1','-q','-At','-U','postgres','-d',db or database],input_text=text,sensitive=sensitive)
    if sql("SELECT count(*) FROM pg_database WHERE datname='"+database+"'",db='postgres').strip()=='0':sql('CREATE DATABASE '+database,db='postgres')
    def operator(args,private=None,profile='chat-ops'):
        command=COMPOSE+['--profile','operator','run','--rm','-T','--no-deps','-e','SPRING_PROFILES_ACTIVE='+profile]
        if private is None:return call(command+['operator']+args,env=env)
        shell='umask 077; cat > /tmp/operator-input; java -Xms16m -Xmx160m -jar /app/platform.jar "$@"; result=$?; rm -f /tmp/operator-input; exit "$result"'
        return call(command+['--entrypoint','sh','operator','-c',shell,'operator']+args,env=env,input_text=private,sensitive=True)
    operator([],profile='cloud-migrate')
    assert sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success').strip()=='22'
    for role,name in [('ap_migration','postgres-password'),('ap_api','api-password'),('ap_worker','worker-password')]:
        password=(PRIVATE/name).read_text().strip();assert re.fullmatch('[A-Za-z0-9_-]+',password)
        sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" LOGIN; END IF; END $$; ALTER ROLE "+role+" PASSWORD '"+password+"';",sensitive=True)
    sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ap_runner') THEN CREATE ROLE ap_runner NOLOGIN; END IF; END $$;")
    for name in ['r1-runtime-grants.sql','r2-runtime-grants.sql','r3-workspace-grants.sql','r4-runtime-grants.sql','r4-permission-grants.sql','r4-approval-grants.sql','r6-configuration-grants.sql']:
        sql((ROOT/'deploy/config'/name).read_text(encoding='utf-8'))
    # The R6 control-plane fixture reads the pre-existing workspace capacity and
    # worker catalog; it does not enable or start the runner or workspace tools.
    sql('GRANT SELECT ON pf.workspace_capacity,pf.workspace_operation,pf.task_tool_catalog TO ap_api,ap_worker; GRANT UPDATE(revision) ON pf.workspace_capacity TO ap_api,ap_worker;')
    path=PRIVATE/('provision-'+database+'.json')
    ids=json.loads(path.read_text()) if path.exists() else {name:str(uuid.uuid4()) for name in ['r61_admin','r61_user']}
    path.write_text(json.dumps(ids,indent=2)+'\n',encoding='utf-8')
    for name,action,password in [('r61_admin','bootstrap','admin-password'),('r61_user','create','user-password')]:
        operator(['--identity-action='+action,'--username='+name,'--request-id='+ids[name],'--password-file=/tmp/operator-input'],(PRIVATE/password).read_text(),profile='identity-ops')
    key=PRIVATE/('fixture-credential-'+database)
    if not key.exists():
        output=operator(['--chat-action=import-credential','--scope=PLATFORM','--key-file=/tmp/operator-input'],'R61_DETERMINISTIC_PROVIDER_SENTINEL')
        found=re.search(r'Credential imported: ([0-9a-f-]{36})',output);assert found;key.write_text(found.group(1))
    for name in ids:
        row=sql("SELECT u.id::text||','||s.id::text FROM pf.app_user u JOIN pf.space s ON s.owner_user_id=u.id WHERE u.username_norm='"+name+"' AND s.type='PERSONAL'").strip().split(',');assert len(row)==2
        operator(['--chat-action=bind-model','--source-policy=PLATFORM','--credential-id='+key.read_text().strip(),'--model-id=0199e000-0000-7000-8000-000000000002','--user-id='+row[0],'--space-id='+row[1]])
    marker=PRIVATE/('workspace-'+database+'.json')
    if not marker.exists():
        row=sql("SELECT u.id::text||','||s.id::text FROM pf.app_user u JOIN pf.space s ON s.owner_user_id=u.id WHERE u.username_norm='r61_admin' AND s.type='PERSONAL'").strip().split(',')
        user,space=[str(uuid.UUID(value)) for value in row];workspace,operation,command=[str(uuid.uuid4()) for _ in range(3)]
        sql("BEGIN;INSERT INTO pf.workspace(id,space_id,actor_id,name,state,quota_bytes,quota_inodes,project_id,operation_id) VALUES('"+workspace+"','"+space+"','"+user+"','R61 metadata','READY',2147483648,100000,200001,'"+operation+"');INSERT INTO pf.workspace_operation(id,space_id,workspace_id,actor_id,command_id,status) VALUES('"+operation+"','"+space+"','"+workspace+"','"+user+"','"+command+"','COMMITTED');COMMIT;")
        marker.write_text(json.dumps({'workspaceId':workspace,'physicalAllocation':False},indent=2)+'\n',encoding='utf-8')
    (PRIVATE/'workspace-metadata.json').write_text(marker.read_text(encoding='utf-8'),encoding='utf-8')
    call(COMPOSE+['up','-d','--no-build','--wait','--wait-timeout','150','api','worker','fixture','gateway'],env=env,timeout=240)
    print('R61 local gateway ready on 127.0.0.1:'+str(port))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['prepare','build','start','status','stop']);parser.add_argument('--fresh-db',action='store_true');args=parser.parse_args();action=args.action
    if args.fresh_db and action!='prepare':raise ValueError('--fresh-db is only valid with prepare')
    if action=='prepare':
        prepare()
        if args.fresh_db:
            if call(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-r61-local']).strip():
                raise RuntimeError('Stop the isolated fixture before selecting a fresh database')
            manifest=PRIVATE/'manifest.json';value=json.loads(manifest.read_text(encoding='utf-8'));value['database']='r61_'+secrets.token_hex(6);manifest.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8');print('R61 fresh test database selected; prior database retained')
    elif action=='build': build()
    elif action=='start': start()
    elif action=='status': print(call(['docker','ps','-a','--filter','label=com.docker.compose.project=agentplatform-r61-local','--format','{{.Names}} {{.Status}}']))
    else:
        env=environment(validate_source=False);call(COMPOSE+['stop','gateway','api','worker','fixture','rabbitmq','postgres'],env=env,timeout=180);print('R61 fixture stopped; volumes retained')

if __name__=='__main__': main()
