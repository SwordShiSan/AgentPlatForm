#!/usr/bin/env python3
"""Generate protected, untracked cloud configuration without printing secrets."""
import base64,hashlib,json,os,pathlib,secrets
root=pathlib.Path(os.environ.get('PLATFORM_ROOT','/srv/agentplatform')).resolve()
secret=root/'secrets'; config=root/'config'
secret.mkdir(mode=0o700,parents=True,exist_ok=True); config.mkdir(mode=0o700,exist_ok=True)
def value(name):
 p=secret/name
 if not p.exists():
  fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o400)
  with os.fdopen(fd,'w') as f:f.write(secrets.token_hex(32))
 # Compose bind secrets preserve owner: readable file inside root-only directory.
 os.chmod(p,0o444)
 return p.read_text().strip()
for name in ['pg_admin','pg_api','pg_worker','pg_filer','pg_migration','redis','mq_publisher','mq_execution','mq_asset','mq_maintenance','mq_admin','s3_read','s3_write','s3_admin']:value(name)
def config_write(name,text):
 p=config/name;p.write_text(text);os.chmod(p,0o444)
identities=[{'name':'ap-read','credentials':[{'accessKey':'ap-read','secretKey':value('s3_read')}],'actions':['Read:ap-assets','List:ap-assets']},
 {'name':'ap-write','credentials':[{'accessKey':'ap-write','secretKey':value('s3_write')}],'actions':['Read:ap-assets','Write:ap-assets','List:ap-assets','Tagging:ap-assets']},
 {'name':'ap-admin','credentials':[{'accessKey':'ap-admin','secretKey':value('s3_admin')}],'actions':['Admin']}]
config_write('s3.json',json.dumps({'identities':identities}))
config_write('master.toml','[master.volume_growth]\ncopy_1=1\ncopy_2=1\ncopy_3=1\ncopy_other=1\nthreshold=0.9\n')
config_write('redis.conf','bind 0.0.0.0\nprotected-mode yes\nmaxmemory 24mb\nmaxmemory-policy allkeys-lru\nsave ""\nappendonly no\nrequirepass '+value('redis')+'\n')
config_write('filer.toml','[postgres]\nenabled=true\nhostname="postgres"\nport=5432\nusername="ap_filer"\npassword="'+value('pg_filer')+'"\ndatabase="agentplatform"\nschema="weed"\nsslmode="disable"\nconnection_max_idle=2\nconnection_max_open=0\ncreateTable=\'CREATE TABLE IF NOT EXISTS "%s" (dirhash BIGINT, name VARCHAR(65535) COLLATE "C", directory VARCHAR(65535), meta bytea, PRIMARY KEY(dirhash,name))\'\n')
def mq_user(name,admin=False):
 salt=secrets.token_bytes(4); password=value(name)
 return {'name':name,'password_hash':base64.b64encode(salt+hashlib.sha256(salt+password.encode()).digest()).decode(),'hashing_algorithm':'rabbit_password_hashing_sha256','tags':['administrator'] if admin else []}
users=[mq_user(n,n=='mq_admin') for n in ['mq_publisher','mq_execution','mq_asset','mq_maintenance','mq_admin']]
permissions=[{'user':'mq_publisher','vhost':'/agentplatform','configure':'^$','write':'^ap.commands.v1$','read':'^$'}]
for name,queue in [('mq_execution','ap.execution.v1'),('mq_asset','ap.asset.v1'),('mq_maintenance','ap.maintenance.v1')]:
 permissions.append({'user':name,'vhost':'/agentplatform','configure':'^$','write':'^$','read':'^'+queue.replace('.','\\.')+'$'})
permissions.append({'user':'mq_admin','vhost':'/agentplatform','configure':'.*','write':'.*','read':'.*'})
queues=[{'name':q,'vhost':'/agentplatform','durable':True,'auto_delete':False,'arguments':{'x-queue-type':'quorum','x-delivery-limit':10}} for q in ['ap.execution.v1','ap.asset.v1','ap.maintenance.v1']]
bindings=[{'source':'ap.commands.v1','vhost':'/agentplatform','destination':q['name'],'destination_type':'queue','routing_key':key,'arguments':{}} for q,key in zip(queues,['execution.ready','asset.process','maintenance.run'])]
definitions={'users':users,'vhosts':[{'name':'/agentplatform'}],'permissions':permissions,'queues':queues,'exchanges':[{'name':'ap.commands.v1','vhost':'/agentplatform','type':'direct','durable':True,'auto_delete':False,'internal':False,'arguments':{}}],'bindings':bindings}
config_write('rabbitmq-definitions.json',json.dumps(definitions))
config_write('rabbitmq.conf','listeners.tcp.default=5672\nmanagement.tcp.port=15672\ndefinitions.import_backend=local_filesystem\ndefinitions.local.path=/etc/rabbitmq/definitions.json\nvm_memory_high_watermark.absolute=192MB\ndisk_free_limit.absolute=1GB\n')
for name,uid in [('postgres',999),('redis',999),('rabbitmq',999),('object-master',10001),('object-volume',10001),('object-filer',10001)]:
 p=root/'data'/name;p.mkdir(parents=True,exist_ok=True);os.chown(p,uid,uid)
print('Independent secret files, least-privilege topology and data directories prepared; no credential values emitted.')
