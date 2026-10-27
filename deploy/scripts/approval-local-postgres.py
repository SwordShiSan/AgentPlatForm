from pathlib import Path
import json,secrets,socket,subprocess,os,ipaddress
root=Path.cwd(); fixture=root/'.cache/r43-local/fixture'; fixture.mkdir(parents=True,exist_ok=True)
password=fixture/'postgres-password.txt'
if not password.exists():password.write_text(secrets.token_urlsafe(36),encoding='utf-8')
result=subprocess.run(['icacls',str(fixture),'/inheritance:r','/grant:r',os.environ['USERNAME']+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],capture_output=True,text=True)
assert result.returncode==0,'Fixture ACL setup failed'
compose=fixture/'compose.yaml'
value=f'''name: agentplatform-r43-local
services:
  postgres:
    image: pgvector/pgvector:0.8.1-pg17-trixie@sha256:137f044b0efe3d57f39b972b9b53641b1f2045b99d879e298bbf514a25787dcf
    restart: "no"
    mem_limit: 320m
    memswap_limit: 320m
    cpus: 1
    environment:
      POSTGRES_DB: r43_test
      POSTGRES_PASSWORD_FILE: /run/secrets/postgres_password
    secrets: [postgres_password]
    ports: ["127.0.0.1:15456:5432"]
    volumes: ["postgres-data:/var/lib/postgresql/data"]
    networks: [r43]
    healthcheck: {{test: [CMD, pg_isready, -U, postgres, -d, r43_test], interval: 2s, timeout: 3s, retries: 30}}
    logging: {{driver: json-file, options: {{max-size: 10m, max-file: "3"}}}}
secrets:
  postgres_password:
    file: {json.dumps(str(password))}
volumes:
  postgres-data:
networks:
  r43:
    ipam:
      config: [{{subnet: "10.234.43.0/24"}}]
'''
compose.write_text(value,encoding='utf-8',newline='\n')
with socket.socket() as port:
    if port.connect_ex(('127.0.0.1',15456))==0:
        rows=subprocess.check_output(['docker','ps','--filter','label=com.docker.compose.project=agentplatform-r43-local','--format','{{.Names}}'],text=True)
        assert 'postgres' in rows,'Port15456 occupied by another project'
ids=subprocess.check_output(['docker','network','ls','-q'],text=True).split()
networks=json.loads(subprocess.check_output(['docker','network','inspect',*ids],text=True))
candidate=ipaddress.ip_network('10.234.43.0/24')
for network in networks:
    if network['Name']=='agentplatform-r43-local_r43':continue
    for item in network.get('IPAM',{}).get('Config',[]) or []:
        if item.get('Subnet'):assert not candidate.overlaps(ipaddress.ip_network(item['Subnet'])),'R43 subnet conflicts with existing network'
subprocess.run(['docker','compose','-f',str(compose),'config','--quiet'],check=True)
subprocess.run(['docker','compose','-f',str(compose),'up','-d','--no-build','--wait','postgres'],check=True)
print('R43_ISOLATED_POSTGRES_READY')
