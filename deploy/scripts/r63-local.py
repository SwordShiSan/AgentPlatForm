"""One entry point for the isolated R6.3 local acceptance stack.

Never operates on production. The fixture keeps its own credentials, volumes and network.
"""
from pathlib import Path
import argparse
import base64
import hashlib
import ipaddress
import importlib.util
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.cache' / 'r63-local'
PRIVATE = DATA / 'fixture'
COMPOSE = ['docker', 'compose', '-p', 'agentplatform-r63-local', '-f', str(ROOT / 'deploy/compose.r63-local.yaml')]
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
    call(['python',ROOT/'deploy/scripts/fetch-r63-model.py',ROOT/'.cache/r63-model'])
    PRIVATE.mkdir(parents=True, exist_ok=True)
    if os.name == 'nt':
        user = call(['whoami']).strip()
        call(['icacls', str(PRIVATE), '/inheritance:r', '/grant:r', user + ':(OI)(CI)F', 'SYSTEM:(OI)(CI)F'])
    for name in ['postgres-password', 'api-password', 'worker-password', 'admin-password', 'user-password',
                 'mq-api-password', 'mq-worker-password', 'mq-operator-password', 's3-access', 's3-secret']:
        path = PRIVATE / name
        if not path.exists(): path.write_text(secrets.token_urlsafe(32), encoding='utf-8')
    for name in ['master-key', 'cursor-key', 'mcp-master-key']:
        path = PRIVATE / name
        if not path.exists(): path.write_bytes(secrets.token_bytes(32))
    manifest = PRIVATE / 'manifest.json'
    if not manifest.exists():
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        manifest.write_text(json.dumps({'project': 'agentplatform-r63-local', 'gatewayPort': port}, indent=2) + '\n', encoding='utf-8')
    saved=json.loads(manifest.read_text(encoding='utf-8'))
    if 'database' not in saved:
        saved['database']='r63_app'
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
    if 'mcpSubnet' not in saved:
        ids=call(['docker','network','ls','-q']).split()
        networks=json.loads(call(['docker','network','inspect',*ids])) if ids else []
        occupied=[ipaddress.ip_network(saved['subnet'])]
        for network in networks:
            for item in network.get('IPAM',{}).get('Config',[]) or []:
                if item.get('Subnet'): occupied.append(ipaddress.ip_network(item['Subnet']))
        for number in range(1,255):
            candidate=ipaddress.ip_network('10.237.'+str(number)+'.0/24')
            if not any(candidate.version==existing.version and candidate.overlaps(existing) for existing in occupied):
                saved['mcpSubnet']=str(candidate);break
        if 'mcpSubnet' not in saved: raise RuntimeError('No independent MCP control subnet available')
        manifest.write_text(json.dumps(saved,indent=2)+'\n',encoding='utf-8')
    for name,base in [('egressSubnet','10.238.'),('ingressSubnet','10.239.'),('mcpFixtureSubnet','11.246.')]:
        if name in saved: continue
        ids=call(['docker','network','ls','-q']).split()
        networks=json.loads(call(['docker','network','inspect',*ids])) if ids else []
        occupied=[ipaddress.ip_network(saved[key]) for key in ['subnet','mcpSubnet','egressSubnet','ingressSubnet','mcpFixtureSubnet'] if key in saved]
        for network in networks:
            for item in network.get('IPAM',{}).get('Config',[]) or []:
                if item.get('Subnet'): occupied.append(ipaddress.ip_network(item['Subnet']))
        for number in range(1,255):
            candidate=ipaddress.ip_network(base+str(number)+'.0/24')
            if not any(candidate.version==existing.version and candidate.overlaps(existing) for existing in occupied):
                saved[name]=str(candidate);break
        if name not in saved: raise RuntimeError('No independent fixture subnet available for '+name)
        manifest.write_text(json.dumps(saved,indent=2)+'\n',encoding='utf-8')
    spec=importlib.util.spec_from_file_location('r63_proxy_config',ROOT/'deploy/scripts/r63-mcp-proxy-config.py')
    proxy=importlib.util.module_from_spec(spec);spec.loader.exec_module(proxy)
    worker_address=str(ipaddress.ip_network(saved['mcpSubnet']).network_address+10)
    proxy_path=PRIVATE/'squid.conf';desired=proxy.render(worker_address)
    if proxy_path.exists() and proxy_path.read_text(encoding='utf-8')!=desired: raise RuntimeError('Saved proxy policy identity changed')
    if not proxy_path.exists(): proxy_path.write_text(desired,encoding='utf-8',newline='\n')
    openssl=Path('C:/Program Files/Git/mingw64/bin/openssl.exe')
    for prefix in ['control-3072','runner-3072']:
        private,public=PRIVATE/(prefix+'-private.pem'),PRIVATE/(prefix+'-public.pem')
        if private.exists()!=public.exists(): raise RuntimeError('Incomplete fixture signing key pair')
        if not private.exists():
            call([openssl,'genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:3072','-out',private],sensitive=True)
            call([openssl,'pkey','-in',private,'-pubout','-out',public],sensitive=True)
    fixture_key,fixture_cert,trust=PRIVATE/'fixture.key',PRIVATE/'fixture.crt',PRIVATE/'fixture-trust.jks'
    if any(path.exists() for path in [fixture_key,fixture_cert,trust]) and not all(path.exists() for path in [fixture_key,fixture_cert,trust]):
        raise RuntimeError('Incomplete MCP TLS fixture identity')
    if not fixture_key.exists():
        call([openssl,'req','-x509','-newkey','rsa:2048','-sha256','-days','30','-nodes','-keyout',fixture_key,'-out',fixture_cert,
              '-subj','/CN=fixture.invalid','-addext','subjectAltName=DNS:fixture.invalid'],sensitive=True)
        java=ROOT/'.tools/jdk-21.0.12.1/jdk-21.0.12.1+1'
        shutil.copyfile(java/'lib/security/cacerts',trust)
        env=dict(os.environ,R63_TRUST_PASSWORD='changeit')
        call([java/'bin/keytool.exe','-importcert','-noprompt','-alias','r63-fixture','-file',fixture_cert,
              '-keystore',trust,'-storepass:env','R63_TRUST_PASSWORD'],env=env,sensitive=True)
    users = []
    for name in ['api', 'worker', 'operator']:
        value = (PRIVATE / ('mq-' + name + '-password')).read_text().strip()
        salt = secrets.token_bytes(4)
        users.append({'name': 'ap_' + name, 'password_hash': base64.b64encode(salt + hashlib.sha256(salt + value.encode()).digest()).decode(),
                      'hashing_algorithm': 'rabbit_password_hashing_sha256', 'tags': ['administrator'] if name == 'operator' else []})
    def permission(name, configure, write, read):
        return {'user': 'ap_' + name, 'vhost': '/agentplatform', 'configure': configure, 'write': write, 'read': read}
    definitions = {'users': users, 'vhosts': [{'name': '/agentplatform'}],
      'permissions': [permission(name, '.*', '.*', '.*') for name in ['api','worker','operator']],
      'exchanges': [{'name': name, 'vhost': '/agentplatform', 'type': 'direct', 'durable': True, 'auto_delete': False, 'internal': False, 'arguments': {}} for name in ['ap.commands.v1', 'ap.dead.v1']],
      'queues': [{'name': name, 'vhost': '/agentplatform', 'durable': True, 'auto_delete': False, 'arguments': args} for name, args in [('ap.execution.v1', {'x-queue-type':'quorum','x-dead-letter-exchange':'ap.dead.v1','x-dead-letter-routing-key':'execution.dead'}), ('ap.execution.dead.v1', {'x-queue-type':'quorum'})]],
      'bindings': [{'source': src, 'vhost': '/agentplatform', 'destination': dst, 'destination_type': 'queue', 'routing_key': key, 'arguments': {}} for src,dst,key in [('ap.commands.v1','ap.execution.v1','execution.ready'),('ap.dead.v1','ap.execution.dead.v1','execution.dead')]]}
    if not (PRIVATE / 'rabbitmq-definitions.json').exists():
        (PRIVATE / 'rabbitmq-definitions.json').write_text(json.dumps(definitions), encoding='utf-8')
    if not (PRIVATE / 'rabbitmq.conf').exists():
        (PRIVATE / 'rabbitmq.conf').write_text('definitions.import_backend = local_filesystem\ndefinitions.local.path = /etc/rabbitmq/definitions.json\nvm_memory_high_watermark.absolute = 192MiB\ndisk_free_limit.absolute = 192MiB\nlog.console.level = warning\nconsumer_timeout = 180000\n', encoding='utf-8')
    print('R63 isolated inputs ready')

def build():
    prepare(); call(['python',ROOT/'deploy/scripts/fetch-r63-model.py',ROOT/'.cache/r63-model'])
    before = source_manifest(); sha = call(['git', 'rev-parse', 'HEAD']).strip()
    java = ROOT / '.tools/jdk-21.0.12.1/jdk-21.0.12.1+1'
    node = ROOT / '.tools/node-v22.23.3-win-x64/node.exe'
    env = dict(os.environ, JAVA_HOME=str(java), PATH=str(java/'bin')+os.pathsep+str(node.parent)+os.pathsep+os.environ['PATH'],
               VITE_CHAT_ENABLED='true', VITE_PLAN_ENABLED='true', VITE_PERMISSIONS_ENABLED='true',
               VITE_WORKSPACE_ENABLED='true', VITE_PERSONAL_CONFIG_ENABLED='true', VITE_CAPABILITIES_ENABLED='true',
               VITE_KNOWLEDGE_ENABLED='true')
    temporary = DATA / 'tmp'; temporary.mkdir(parents=True, exist_ok=True); env.update(TEMP=str(temporary),TMP=str(temporary))
    call([ROOT/'backend/mvnw.cmd','-B','-ntp','-f',ROOT/'backend/pom.xml','-DskipTests','package'],env=env,timeout=600)
    call([node,ROOT/'frontend/node_modules/vite/bin/vite.js','build'],env=env,timeout=300,cwd=ROOT/'frontend')
    assert before == source_manifest(), 'Source changed while building'
    model_identity = {name:hashlib.sha256((ROOT/'.cache/r63-model'/name).read_bytes()).hexdigest()
                      for name in ['onnx/model_quantized.onnx','tokenizer.json']}
    identity = hashlib.sha256(json.dumps({'source':before,'model':model_identity},sort_keys=True).encode()).hexdigest()
    images = {}
    for component in ['api','gateway','provider-fixture']:
        tag = 'agentplatform-' + component + ':r63-' + identity[:12]
        args = ['docker','build','--platform','linux/amd64','--pull=false','--build-arg','SOURCE_REVISION='+sha,'-f','deploy/'+component+'.Dockerfile','-t',tag]
        if component == 'gateway': args += ['--build-arg','NGINX_CONFIG=deploy/nginx.chat-local.conf']
        call(args+['.'],env=env,timeout=600)
        row=json.loads(call(['docker','image','inspect',tag]))[0]
        assert row['Architecture']=='amd64' and row['Os']=='linux'
        images[component]={'tag':tag,'configId':row['Id']}
    for component,tag in [('file-helper','agentplatform-file-helper:r54-e4c1cda2e5c2')]:
        row=json.loads(call(['docker','image','inspect',tag]))[0]
        assert row['Architecture']=='amd64' and row['Os']=='linux'
        images[component]={'tag':tag,'configId':row['Id']}
    probe_tag='agentplatform-storage-probe:r63-'+identity[:12]
    probe_exists=subprocess.run(['docker','image','inspect',probe_tag],cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
    if not probe_exists:
        call(['docker','build','--platform','linux/amd64','--pull=false','--build-arg','SOURCE_REVISION='+sha,
              '-f','deploy/storage-probe.Dockerfile','-t',probe_tag,'.'],env=env,timeout=600)
    row=json.loads(call(['docker','image','inspect',probe_tag]))[0]
    assert row['Architecture']=='amd64' and row['Os']=='linux'
    images['storage-probe']={'tag':probe_tag,'configId':row['Id']}
    (DATA/'images.json').write_text(json.dumps({'source':sha,'inputHash':identity,'model':model_identity,'images':images},indent=2)+'\n',encoding='utf-8')
    (DATA/'build-inputs.json').write_text(json.dumps(before,indent=2)+'\n',encoding='utf-8')
    print('R63 local images built:', identity)

def environment(validate_source=True):
    images=json.loads((DATA/'images.json').read_text(encoding='utf-8'))
    model_identity = {name:hashlib.sha256((ROOT/'.cache/r63-model'/name).read_bytes()).hexdigest()
                      for name in ['onnx/model_quantized.onnx','tokenizer.json']}
    if images.get('model') != model_identity: raise RuntimeError('Pinned local model identity changed since build')
    if validate_source:
        for image in images['images'].values():
            assert json.loads(call(['docker','image','inspect',image['tag']]))[0]['Id']==image['configId'], 'Image identity changed'
        before=json.loads((DATA/'build-inputs.json').read_text(encoding='utf-8'));current=source_manifest()
        changed={name for name in set(before)|set(current) if before.get(name)!=current.get(name)}
        fixture_only={'deploy/scripts/r63-local.py','deploy/compose.r63-local.yaml','deploy/probes/r63-s3-init.py',
                      'deploy/compose.r63-local-cloud-runner.yaml','deploy/probes/r63-cloud-tunnel-check.py',
                      'deploy/probes/r63-cloud-runner-preflight.sh',
                      'deploy/probes/r63-create-runner-role.py',
                      'deploy/scripts/r63-cloud-runner-enable-local.py',
                      'deploy/probes/r63-cloud-r5-watchdog.sh',
                      'deploy/probes/r63-cloud-runner-start.sh',
                      'deploy/probes/r63-cloud-runner-maintenance.sh',
                      'deploy/probes/r63-cloud-runner-stage-verify.sh',
                      'deploy/scripts/r63-cloud-runner-stage.py',
                      'deploy/scripts/r63-signed-extraction-knowledge.py',
                      'deploy/scripts/r63-cloud-tunnel.py',
                      'deploy/r63-tunnel-client.Dockerfile',
                      'deploy/probes/r63-s3-readback-extraction.py',
                      'deploy/scripts/r63-mcp-proxy-config.py','deploy/probes/r63-mcp-tls-fixture.py',
                      'deploy/scripts/r63-browser.py','deploy/scripts/test-r63-local-stop.py','deploy/scripts/r63-knowledge-acceptance.py',
                      'deploy/probes/r63-put-extracted.py',
                      'deploy/scripts/r63-retrieval-scale.py',
                      'deploy/scripts/r63-resource-sample.py',
                      'deploy/scripts/r63-memory-matrix.py',
                      'deploy/scripts/r63-workspace-memory-context.py',
                      'deploy/scripts/r63-context-projection.py',
                      'deploy/scripts/r63-index-retention.py',
                      'backend/platform-app/src/test/java/com/agentplatform/app/knowledge/KnowledgeV23UpgradePostgresTest.java',
                      'backend/platform-infrastructure/src/test/java/com/agentplatform/infrastructure/capability/McpDiscoveryProcessTest.java',
                      'backend/platform-infrastructure/src/test/java/com/agentplatform/infrastructure/knowledge/E5OnnxEncoderIntegrationTest.java',
                      'frontend/tests/e2e/capabilities-r63.spec.ts'}
        if changed-fixture_only: raise RuntimeError('Image inputs changed since build: '+str(sorted(changed-fixture_only)))
        (DATA/'runtime-config.json').write_text(json.dumps({'buildSource':images['source'],'buildInputHash':images['inputHash'],
            'fixtureInputs':{name:current.get(name) for name in sorted(fixture_only)},'fixtureOnlyChanges':sorted(changed)},indent=2)+'\n',encoding='utf-8')
    manifest=json.loads((PRIVATE/'manifest.json').read_text(encoding='utf-8'))
    model_root=(ROOT/'.cache/r63-model').resolve().as_posix()
    return dict(os.environ,R63_ROOT=PRIVATE.as_posix(),R63_MODEL_ROOT=model_root,R63_PORT=str(manifest['gatewayPort']),R63_SUBNET=manifest['subnet'],R63_DATABASE=manifest['database'],
        R63_MCP_SUBNET=manifest['mcpSubnet'],R63_WORKER_IP=str(ipaddress.ip_network(manifest['mcpSubnet']).network_address+10),
        R63_PROXY_IP=str(ipaddress.ip_network(manifest['mcpSubnet']).network_address+20),
        R63_EGRESS_SUBNET=manifest['egressSubnet'],R63_INGRESS_SUBNET=manifest['ingressSubnet'],
        R63_MCP_FIXTURE_SUBNET=manifest['mcpFixtureSubnet'],R63_MCP_FIXTURE_IP=str(ipaddress.ip_network(manifest['mcpFixtureSubnet']).network_address+10),
        R63_MCP_FIXTURE_ID='sha256:35828d15944404ccfbf0a791190521cc78f257704d93940d53f367b721fbbbe0',
        R63_HELPER_ID=images['images']['file-helper']['configId'],R63_STORAGE_PROBE_ID=images['images']['storage-probe']['configId'],
        R63_API_IMAGE=images['images']['api']['tag'],R63_GATEWAY_IMAGE=images['images']['gateway']['tag'],R63_FIXTURE_IMAGE=images['images']['provider-fixture']['tag'])

def start():
    prepare(); env=environment(); port=int(env['R63_PORT'])
    existing=call(['docker','ps','--filter','label=com.docker.compose.project=agentplatform-r63-local','--format','{{.Names}}'])
    if not port_available(port) and 'gateway' not in existing: raise RuntimeError('Fixture gateway port is occupied')
    call(COMPOSE+['config','--quiet'],env=env)
    config=json.loads(call(COMPOSE+['config','--format','json'],env=env))
    def memory_bytes(value):
        if isinstance(value,(int,float)): return int(value)
        match=re.fullmatch(r'\s*(\d+(?:\.\d+)?)\s*([kKmMgGtT]?)(?:[iI]?[bB])?\s*',str(value))
        if not match: raise RuntimeError('Unrecognized container memory limit')
        scale={'':1,'k':1024,'m':1024**2,'g':1024**3,'t':1024**4}[match.group(2).lower()]
        return int(float(match.group(1))*scale)
    core_limits=sum(memory_bytes(service.get('mem_limit')) for name,service in config['services'].items()
                    if name!='operator' and service.get('mem_limit') is not None)
    # This is the local control-plane fixture profile. It deliberately does
    # not start a sandbox, renderer, or privileged file helper; those run only
    # in the approved cloud/Linux environment. Keep their reserved capacity
    # out of the local running-container sum.
    local_budget=core_limits
    if local_budget>2887*1024*1024: raise RuntimeError('R63 local container budget exceeds 2887MiB')
    memory=int(call(['docker','info','--format','{{.MemTotal}}']).strip())
    if memory < local_budget+768*1024*1024: raise RuntimeError('Docker memory does not leave the required R63 local host reserve')
    print('R63 local service caps (no local sandbox/helper):',round(local_budget/1024/1024),'MiB')
    call(COMPOSE+['up','-d','--no-build','--wait','postgres'],env=env,timeout=180)
    call(COMPOSE+['create','rabbitmq'],env=env)
    volume='agentplatform-r63-local_mq-data'
    metadata=json.loads(call(['docker','volume','inspect',volume]))[0]
    assert metadata.get('Labels',{}).get('com.docker.compose.project')=='agentplatform-r63-local'
    repair='if [ -f /var/lib/rabbitmq/.erlang.cookie ]; then chown rabbitmq:rabbitmq /var/lib/rabbitmq/.erlang.cookie; chmod 600 /var/lib/rabbitmq/.erlang.cookie; fi'
    call(['docker','run','--rm','--network','none','--read-only','--user','0:0','--cap-drop','ALL','--cap-add','CHOWN','--cap-add','FOWNER',
          '--security-opt','no-new-privileges:true','--memory','64m','--memory-swap','64m','--pids-limit','16','--entrypoint','sh',
          '--mount','type=volume,source='+volume+',target=/var/lib/rabbitmq',RABBIT,'-eu','-c',repair],timeout=120)
    call(COMPOSE+['up','-d','--no-build','--wait','rabbitmq'],env=env,timeout=180)
    database=env['R63_DATABASE'];assert re.fullmatch(r'r63_[a-z0-9_]+',database)
    def sql(text,sensitive=False,db=None): return call(['docker','exec','-i','agentplatform-r63-local-postgres-1','psql','-v','ON_ERROR_STOP=1','-q','-At','-U','postgres','-d',db or database],input_text=text,sensitive=sensitive)
    if sql("SELECT count(*) FROM pg_database WHERE datname='"+database+"'",db='postgres').strip()=='0':sql('CREATE DATABASE '+database,db='postgres')
    def operator(args,private=None,profile='chat-ops'):
        command=COMPOSE+['--profile','operator','run','--rm','-T','--no-deps','-e','SPRING_PROFILES_ACTIVE='+profile]
        if private is None:return call(command+['operator']+args,env=env)
        shell='umask 077; cat > /tmp/operator-input; java -Xms16m -Xmx160m -jar /app/platform.jar "$@"; result=$?; rm -f /tmp/operator-input; exit "$result"'
        return call(command+['--entrypoint','sh','operator','-c',shell,'operator']+args,env=env,input_text=private,sensitive=True)
    if sql("SELECT to_regclass('pf.flyway_schema_history') IS NOT NULL").strip()!='t':
        operator([],profile='cloud-migrate')
    assert sql('SELECT max(version::int) FROM pf.flyway_schema_history WHERE success').strip()=='24'
    for role,name in [('ap_migration','postgres-password'),('ap_api','api-password'),('ap_worker','worker-password')]:
        password=(PRIVATE/name).read_text().strip();assert re.fullmatch('[A-Za-z0-9_-]+',password)
        sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+role+"') THEN CREATE ROLE "+role+" LOGIN; END IF; END $$; ALTER ROLE "+role+" PASSWORD '"+password+"';",sensitive=True)
    sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ap_runner') THEN CREATE ROLE ap_runner NOLOGIN; END IF; IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ap_workspace_ops') THEN CREATE ROLE ap_workspace_ops NOLOGIN; END IF; END $$;")
    for name in ['r1-runtime-grants.sql','r2-runtime-grants.sql','r3-workspace-grants.sql','r3-environment-grants.sql',
                 'r3-file-grants.sql','r4-runtime-grants.sql','r4-permission-grants.sql','r4-approval-grants.sql',
                 'r4-workspace-tools-grants.sql',
                 'r5-file-grants.sql','r5-preview-grants.sql','r5-workbench-grants.sql','r6-configuration-grants.sql',
                 'r62-capability-bindings-grants.sql','r62-skill-import-grants.sql','r62-skill-version-grants.sql',
                 'r62-skill-worker-grants.sql','r62-connector-credential-grants.sql','r62-mcp-config-grants.sql',
                 'r62-mcp-worker-grants.sql','r62-capability-materialization-grants.sql','r63-knowledge-memory-grants.sql']:
        sql((ROOT/'deploy/config'/name).read_text(encoding='utf-8'))
    # The R6 control-plane fixture reads the pre-existing workspace capacity and
    # worker catalog; it does not enable or start the runner or workspace tools.
    sql('GRANT SELECT ON pf.workspace_capacity,pf.workspace_operation,pf.task_tool_catalog TO ap_api,ap_worker; GRANT UPDATE(revision) ON pf.workspace_capacity TO ap_api,ap_worker;')
    path=PRIVATE/('provision-'+database+'.json')
    ids=json.loads(path.read_text()) if path.exists() else {name:str(uuid.uuid4()) for name in ['r63_admin','r63_user']}
    path.write_text(json.dumps(ids,indent=2)+'\n',encoding='utf-8')
    for name,action,password in [('r63_admin','bootstrap','admin-password'),('r63_user','create','user-password')]:
        request=str(uuid.UUID(ids[name]));fingerprint=hashlib.sha256((name+':'+action).encode()).hexdigest()
        expected_admin='true' if action=='bootstrap' else 'false'
        ready=sql("SELECT EXISTS(SELECT 1 FROM pf.identity_operation o JOIN pf.app_user u ON u.id=o.user_id JOIN pf.space s ON s.owner_user_id=u.id AND s.type='PERSONAL' JOIN pf.membership m ON m.space_id=s.id AND m.user_id=u.id AND m.role='OWNER' AND m.status='ACTIVE' JOIN pf.space_storage st ON st.space_id=s.id AND st.status='ACTIVE' WHERE o.request_id='"+request+"' AND o.input_hash='"+fingerprint+"' AND o.bootstrap="+expected_admin+" AND u.username_norm='"+name+"' AND u.status='ACTIVE' AND u.platform_admin="+expected_admin+")").strip()=='t'
        if not ready: operator(['--identity-action='+action,'--username='+name,'--request-id='+ids[name],'--password-file=/tmp/operator-input'],(PRIVATE/password).read_text(),profile='identity-ops')
    key=PRIVATE/('fixture-credential-'+database)
    if not key.exists():
        output=operator(['--chat-action=import-credential','--scope=PLATFORM','--key-file=/tmp/operator-input'],'R63_DETERMINISTIC_PROVIDER_SENTINEL')
        found=re.search(r'Credential imported: ([0-9a-f-]{36})',output);assert found;key.write_text(found.group(1))
    for name in ids:
        row=sql("SELECT u.id::text||','||s.id::text FROM pf.app_user u JOIN pf.space s ON s.owner_user_id=u.id WHERE u.username_norm='"+name+"' AND s.type='PERSONAL'").strip().split(',');assert len(row)==2
        user_id,space_id=[str(uuid.UUID(value)) for value in row];credential_id=str(uuid.UUID(key.read_text().strip()))
        ready=sql("SELECT EXISTS(SELECT 1 FROM pf.model_binding b JOIN pf.credential k ON k.id=b.credential_id WHERE b.space_id='"+space_id+"' AND b.user_id='"+user_id+"' AND b.model_id='0199e000-0000-7000-8000-000000000002' AND b.credential_id='"+credential_id+"' AND b.source_policy='PLATFORM' AND k.status='ACTIVE')").strip()=='t'
        if not ready: operator(['--chat-action=bind-model','--source-policy=PLATFORM','--credential-id='+credential_id,'--model-id=0199e000-0000-7000-8000-000000000002','--user-id='+user_id,'--space-id='+space_id])
    marker=PRIVATE/('workspace-'+database+'.json')
    if not marker.exists():
        row=sql("SELECT u.id::text||','||s.id::text FROM pf.app_user u JOIN pf.space s ON s.owner_user_id=u.id WHERE u.username_norm='r63_admin' AND s.type='PERSONAL'").strip().split(',')
        user,space=[str(uuid.UUID(value)) for value in row];workspace,operation,command=[str(uuid.uuid4()) for _ in range(3)]
        sql("BEGIN;INSERT INTO pf.workspace(id,space_id,actor_id,name,state,quota_bytes,quota_inodes,project_id,operation_id) VALUES('"+workspace+"','"+space+"','"+user+"','R63 metadata','READY',2147483648,100000,200001,'"+operation+"');INSERT INTO pf.workspace_operation(id,space_id,workspace_id,actor_id,command_id,status) VALUES('"+operation+"','"+space+"','"+workspace+"','"+user+"','"+command+"','COMMITTED');COMMIT;")
        marker.write_text(json.dumps({'workspaceId':workspace,'physicalAllocation':False},indent=2)+'\n',encoding='utf-8')
    (PRIVATE/'workspace-metadata.json').write_text(marker.read_text(encoding='utf-8'),encoding='utf-8')
    call(COMPOSE+['up','-d','--no-build','--wait','--wait-timeout','150','seaweedfs','mcp-proxy','mcp-fixture'],env=env,timeout=240)
    call(['docker','run','--rm','--network','agentplatform-r63-local_r63','--memory','128m','--memory-swap','128m',
          '--cap-drop','ALL','--security-opt','no-new-privileges:true','--read-only',
          '--mount','type=bind,src='+str(PRIVATE/'s3-access')+',dst=/fixture/s3-access,readonly',
          '--mount','type=bind,src='+str(PRIVATE/'s3-secret')+',dst=/fixture/s3-secret,readonly',
          '--mount','type=bind,src='+str(ROOT/'deploy/probes/r63-s3-init.py')+',dst=/r63-s3-init.py,readonly',
          '--entrypoint','python3',env['R63_STORAGE_PROBE_ID'],'/r63-s3-init.py'],timeout=120)
    existing_worker=call(['docker','ps','-aq','--filter','name=^/agentplatform-r63-local-worker-1$']).strip()
    if existing_worker:
        row=json.loads(call(['docker','inspect',existing_worker]))[0]
        mounts={item['Destination']:Path(item['Source']).resolve() for item in row['Mounts'] if item.get('Type')=='bind'}
        required={'/run/secrets/control_private':(PRIVATE/'control-3072-private.pem').resolve(),
                  '/run/secrets/runner_public':(PRIVATE/'runner-3072-public.pem').resolve()}
        if any(mounts.get(destination)!=path for destination,path in required.items()):
            if row['State']['Running']: raise RuntimeError('Stop the fixture before changing worker secret mounts')
            call(COMPOSE+['up','-d','--no-deps','--force-recreate','worker'],env=env,timeout=120)
    # First use of DJL tokenizers extracts its native library inside the capped
    # Java container; on a cold Windows Docker Desktop this can exceed 300s
    # without an OOM or application failure. Keep one bounded cold-start window.
    call(COMPOSE+['up','-d','--no-build','--wait','--wait-timeout','420','api','worker','fixture','gateway'],env=env,timeout=480)
    expected={'postgres','rabbitmq','seaweedfs','mcp-proxy','mcp-fixture','api','worker','fixture','gateway'}
    present=set(call(COMPOSE+['ps','--services','--filter','status=running'],env=env).split())
    if present != expected: raise RuntimeError('Fixture required services are not all running: '+str(sorted(expected-present)))
    deadline=time.monotonic()+30
    while True:
        rabbit=json.loads(call(['docker','inspect','agentplatform-r63-local-rabbitmq-1']))[0]
        if not rabbit['State']['Running']:
            raise RuntimeError('Required RabbitMQ stopped during startup: exit='+str(rabbit['State']['ExitCode'])+' oom='+str(rabbit['State']['OOMKilled']))
        try: consumers=call(['docker','exec','agentplatform-r63-local-rabbitmq-1','rabbitmqctl','list_queues','-p','/agentplatform','name','consumers'],timeout=20)
        except RuntimeError as failure: raise RuntimeError('RabbitMQ is running but queue readiness query failed; inspect protected first-failure log') from failure
        lines={row.split()[0]:row.split()[1] for row in consumers.splitlines() if len(row.split())==2 and row.split()[1].isdigit()}
        if all(int(lines.get(name,'0'))>=1 for name in ['ap.knowledge-index.v1']): break
        if time.monotonic()>=deadline: raise RuntimeError('Fixture consumers did not become ready')
        time.sleep(1)
    present=set(call(COMPOSE+['ps','--services','--filter','status=running'],env=env).split())
    if present != expected: raise RuntimeError('A required fixture service exited during readiness checks')
    print('R63 local gateway ready on 127.0.0.1:'+str(port))

def stop():
    project='agentplatform-r63-local'
    ids=call(['docker','ps','-q','--no-trunc','--filter','label=com.docker.compose.project='+project]).split()
    if not ids:
        print('R63 fixture already stopped; volumes retained');return
    rows=json.loads(call(['docker','inspect',*ids]));allowed={'gateway','api','worker','fixture','mcp-fixture','mcp-proxy','seaweedfs','rabbitmq','postgres','operator'}
    groups=[[],[]]
    for row in rows:
        labels=row['Config'].get('Labels') or {};service=labels.get('com.docker.compose.service');name=row['Name'].lstrip('/')
        if labels.get('com.docker.compose.project')!=project or service not in allowed or not name.startswith(project+'-'):
            raise RuntimeError('Unexpected container in fixture project; refusing broad stop')
        groups[1 if service in {'mcp-proxy','seaweedfs','rabbitmq','postgres'} else 0].append(row['Id'])
    for group in groups:
        if group: call(['docker','stop','-t','15',*group],timeout=180)
    if call(['docker','ps','-q','--filter','label=com.docker.compose.project='+project]).strip():
        raise RuntimeError('A fixture container remains running')
    print('R63 fixture stopped; volumes retained')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['prepare','build','start','status','stop']);parser.add_argument('--fresh-db',action='store_true');args=parser.parse_args();action=args.action
    if args.fresh_db and action!='prepare':raise ValueError('--fresh-db is only valid with prepare')
    if action=='prepare':
        prepare()
        if args.fresh_db:
            if call(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-r63-local']).strip():
                raise RuntimeError('Stop the isolated fixture before selecting a fresh database')
            manifest=PRIVATE/'manifest.json';value=json.loads(manifest.read_text(encoding='utf-8'));value['database']='r63_'+secrets.token_hex(6);manifest.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8');print('R63 fresh test database selected; prior database retained')
    elif action=='build': build()
    elif action=='start': start()
    elif action=='status': print(call(['docker','ps','-a','--filter','label=com.docker.compose.project=agentplatform-r63-local','--format','{{.Names}} {{.Status}}']))
    else: stop()

if __name__=='__main__': main()
