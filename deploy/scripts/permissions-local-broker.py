from pathlib import Path
import ast,base64,hashlib,json,secrets,subprocess,socket
ROOT=Path.cwd();DATA=ROOT/'.cache/r42-local/fixture'
for name in ['api','worker','operator']:
    target=DATA/('mq-'+name+'-password')
    if not target.exists():target.write_text(secrets.token_urlsafe(32),encoding='utf-8')
tree=ast.parse((ROOT/'deploy/scripts/chat-local.py').read_text(encoding='utf-8'))
exec(compile(ast.Module(body=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='definitions'],type_ignores=[]),'<existing-broker-definitions>','exec'))
definitions()
p=DATA/'rabbitmq-definitions.json';value=json.loads(p.read_text());value['vhosts']=[{'name':'/agentplatform'}];value['permissions']=[x for x in value['permissions'] if x['vhost']=='/agentplatform'];p.write_text(json.dumps(value),encoding='utf-8')
compose=DATA/'mq.compose.yaml'
compose.write_text('''services:
  rabbitmq:
    image: rabbitmq:4.3.6-management@sha256:8dd6e3570ddaa2ef82a6c3a8950e79c1f73893adfbdddbc374fc1f1ac8a0f5dd
    hostname: agentplatform-r42-mq
    restart: "no"
    mem_limit: 512m
    memswap_limit: 512m
    cpus: 1
    environment: {RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS: "+S 1:1 +A 4"}
    volumes:
      - "mq-data:/var/lib/rabbitmq"
      - "./rabbitmq.conf:/etc/rabbitmq/rabbitmq.conf:ro"
      - "./rabbitmq-definitions.json:/etc/rabbitmq/definitions.json:ro"
    ports: ["127.0.0.1:15712:5672", "127.0.0.1:15713:15672"]
    networks: [r42]
    healthcheck: {test: [CMD, rabbitmq-diagnostics, -q, ping], interval: 5s, timeout: 5s, retries: 24}
    logging: {driver: json-file, options: {max-size: 10m, max-file: "3"}}
volumes:
  mq-data:
''',encoding='utf-8',newline='\n')
with socket.socket() as port:
    if port.connect_ex(('127.0.0.1',15712))==0:
        rows=subprocess.check_output(['docker','ps','--filter','label=com.docker.compose.project=agentplatform-r42-local','--format','{{.Names}}'],text=True)
        assert 'rabbitmq' in rows,'AMQP port belongs to another project'
command=['docker','compose','-f',str(DATA/'compose.yaml'),'-f',str(compose)]
subprocess.run(command+['config','--quiet'],check=True)
subprocess.run(command+['create','rabbitmq'],check=True)
# Named-volume copy-up can retain the image cookie's root:root/0400 ownership.
# Repair only this exact isolated volume/file before RabbitMQ starts; never print the cookie.
volume='agentplatform-r42-local_mq-data'
metadata=json.loads(subprocess.check_output(['docker','volume','inspect',volume],text=True))[0]
assert metadata.get('Labels',{}).get('com.docker.compose.project')=='agentplatform-r42-local'
image='rabbitmq:4.3.6-management@sha256:8dd6e3570ddaa2ef82a6c3a8950e79c1f73893adfbdddbc374fc1f1ac8a0f5dd'
repair='if [ -f /var/lib/rabbitmq/.erlang.cookie ]; then chown rabbitmq:rabbitmq /var/lib/rabbitmq/.erlang.cookie; chmod 600 /var/lib/rabbitmq/.erlang.cookie; fi'
subprocess.run(['docker','run','--rm','--network','none','--read-only','--user','0:0',
                '--cap-drop','ALL','--cap-add','CHOWN','--cap-add','FOWNER','--security-opt','no-new-privileges:true',
                '--memory','64m','--memory-swap','64m','--pids-limit','16','--entrypoint','sh',
                '--mount','type=volume,source='+volume+',target=/var/lib/rabbitmq',image,'-eu','-c',repair],check=True)
subprocess.run(command+['up','-d','--no-build','--wait','rabbitmq'],check=True)
print('R42_ISOLATED_BROKER_READY')
