"""Stage private R3 app inputs; retain production R2 containers/data unchanged.

Run on the host with an accepted resolved Compose template. This command never
starts containers, migrates a database or imports acceptance secrets/data.
"""
from pathlib import Path
import argparse,copy,hashlib,json,os,re,secrets,subprocess,time

ROOT=Path('/srv/agentplatform')
def output(args):return subprocess.check_output(args,text=True).strip()
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('folder',type=Path);p.add_argument('--template',type=Path,required=True)
 p.add_argument('--source',required=True);p.add_argument('--api-image',required=True);p.add_argument('--gateway-image',required=True)
 a=p.parse_args();os.umask(0o077)
 assert re.fullmatch(r'/srv/agentplatform/operations/r3-release-[a-f0-9]{7,40}-\d{8}',str(a.folder))
 assert re.fullmatch('[a-f0-9]{40}',a.source) and not a.folder.exists()
 for image in [a.api_image,a.gateway_image]:
  assert re.fullmatch('sha256:[a-f0-9]{64}',image)
  r=json.loads(output(['docker','image','inspect',image]))[0];assert r['Id']==image and r['Architecture']=='amd64'
 rows=[]
 for project in ['agentplatform-env','agentplatform-app']:
  for cid in output(['docker','ps','-q','--no-trunc','--filter','label=com.docker.compose.project='+project]).split():
   rows.extend(json.loads(output(['docker','inspect',cid])))
 assert len(rows)==10 and all(r['State']['Running'] and not r['State']['OOMKilled'] for r in rows)
 by={r['Config']['Labels']['com.docker.compose.service']:r for r in rows}
 assert set(by)=={'api','worker','gateway','postgres','rabbitmq','redis','master','volume','filer','s3'}
 mount=json.loads(output(['findmnt','-J','-o','TARGET,SOURCE,FSTYPE,OPTIONS,UUID','--target',str(ROOT/'workspaces')]))['filesystems'][0]
 assert mount['target']==str(ROOT/'workspaces') and mount['fstype']=='xfs' and 'prjquota' in mount['options']
 assert mount['uuid']=='fe8f72e3-4958-4048-a091-b1933d4195da'
 loop=mount['source'];assert re.fullmatch('/dev/loop[0-9]+',loop)
 a.folder.mkdir(mode=0o700);private=a.folder/'secrets';private.mkdir(mode=0o700)
 def write_secret(name,value):
  path=private/name;path.write_text(value);os.chown(path,10001,10001);path.chmod(0o400);return path
 def token(name):return write_secret(name,secrets.token_urlsafe(36))
 for name in ['runner-password','ops-password','mq-password','host-proof-key']:token(name)
 for side in ['control','runner']:
  key=private/(side+'-private.pem');pub=private/(side+'-public.pem')
  subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:3072','-out',str(key)],check=True,capture_output=True)
  subprocess.run(['openssl','pkey','-in',str(key),'-pubout','-out',str(pub)],check=True,capture_output=True)
  for path in [key,pub]:os.chown(path,10001,10001);path.chmod(0o400)
 s3=json.loads((ROOT/'config/s3.json').read_text())
 for side in ['api','worker','runner']:
  access=secrets.token_hex(16);secret=secrets.token_urlsafe(36)
  write_secret('file-'+side+'-access',access);write_secret('file-'+side+'-secret',secret)
  actions=['Read:ap-assets'] if side=='runner' else [x+':ap-assets' for x in ['Read','Write','List','Tagging']]
  s3['identities'].append({'name':'r3-'+side,'credentials':[{'accessKey':access,'secretKey':secret}],'actions':actions})
 (a.folder/'s3.candidate.json').write_text(json.dumps(s3,indent=2))
 template=json.loads(a.template.read_text());config={'name':'agentplatform-r3-app','services':{},'networks':{},'secrets':{}}
 for alias,name in [('control','agentplatform-backplane'),('local-access','agentplatform-ingress'),('model-egress','agentplatform-model-egress')]:
  n=json.loads(output(['docker','network','inspect',name]))[0];assert bool(n['Internal'])==(alias=='control')
  config['networks'][alias]={'name':name,'external':True}
 mapping={'api_password':ROOT/'secrets/pg_api','worker_password':ROOT/'secrets/pg_worker','postgres_password':ROOT/'secrets/pg_migration',
          'runner_password':private/'runner-password','mq_password':private/'mq-password'}
 for side in ['control','runner']:
  for kind in ['private','public']:mapping[side+'_'+kind+'_key']=private/(side+'-'+kind+'.pem')
 for side in ['api','worker','runner']:
  for kind in ['access','secret']:mapping['file_'+side+'_'+kind]=private/('file-'+side+'-'+kind)
 for name,path in mapping.items():assert path.is_file();config['secrets'][name]={'file':str(path)}
 runtime=json.loads(Path('/etc/docker/daemon.json').read_text())['runtimes']['runsc']
 runsc=Path('/usr/local/lib/agentplatform-gvisor/20261005.0/runsc');digest=sha(runsc)
 assert runtime['path']==str(runsc) and runtime['runtimeArgs']==['--platform=systrap']
 assert digest=='210b437a9cfae51e8f8c9074ed19b8b5e59477178e2e391a18117d8d6b924f7a'
 attestation=a.folder/'runtime-attestation.json';attestation.write_text(json.dumps({**runtime,'sha256':digest,'verifiedAt':int(time.time())}));attestation.chmod(0o444)
 ids={k:template['services']['runner']['environment'][v] for k,v in [('sandbox','PLATFORM_WORKSPACE_ENVIRONMENT_IMAGE_ID'),('quota','PLATFORM_WORKSPACE_RUNNER_HELPER_IMAGE'),('file','PLATFORM_WORKSPACE_FILES_HELPER_IMAGE')]}
 for image in ids.values():assert json.loads(output(['docker','image','inspect',image]))[0]['Id']==image
 policy='environment-v3|'+ids['sandbox']+'|'+ids['quota']+'|'+str(ROOT/'workspaces')+'|'+mount['uuid']+'|'+loop+'|'+digest+'|systrap|uid10001|ro|net-none|caps-none|devices-none|memory512m-swap512m|cpu1|pids128|nproc32|tmpfs64m|guest-no-file-bridge|trusted-file-helper:runc|uid10001|ro|net-none|caps-none|devices-none|memory64m-swap64m|cpu1|pids32|tmpfs8m|bridge-v3-jsonl|startup-canary-v1'
 file_policy='file-stream-policy-v1|'+ids['file']+'|'+ids['quota']+'|'+str(ROOT/'workspaces')+'|'+mount['uuid']+'|'+loop+'|uid10001|runc-trusted-file-only|ro|net-none|caps-none|no-devices|no-socket|memory64m-swap64m|cpu1|pids32|tmpfs8m|raw64k|header64k|nfc|nofollow|sha256|single-work-bind|scan-v1-100entries-28k|create-noreplace|require-loop-device|file-stream-v2-sha256-trailer32'
 for name in ['api','worker','runner','gateway','operator']:
  service=copy.deepcopy(template['services'][name]);service['image']=a.gateway_image if name=='gateway' else a.api_image
  service.pop('container_name',None);service.pop('depends_on',None);service.pop('labels',None)
  if name in ['api','worker','runner']:
   env=service['environment']
   if name in ['api','worker']:
    previous=dict(v.split('=',1) for v in by[name]['Config']['Env'])
    inherited={k:v for k,v in previous.items() if k.startswith(('PLATFORM_','SERVER_','MANAGEMENT_'))}
    env.update(inherited)
    env['SPRING_PROFILES_ACTIVE']='identity-local,chat-local,r1-http,workspace-api' if name=='api' else 'chat-worker,workspace-worker'
    service['volumes']=[{'type':'bind','source':m['Source'],'target':m['Destination'],'read_only':not m['RW']} for m in by[name]['Mounts']]
   env.pop('PLATFORM_WORKSPACE_FILES_MAINTENANCE_SCAN_ADMISSION_ENABLED',None)
   env['PLATFORM_STORAGE_JDBC_URL']='jdbc:postgresql://postgres:5432/agentplatform?connectTimeout=2&socketTimeout=5'
   env['PLATFORM_FILES_ASSETS_BUCKET']='ap-assets'
   env['PLATFORM_WORKSPACE_ENVIRONMENT_POLICY_HASH']=hashlib.sha256(policy.encode()).hexdigest()
   env['PLATFORM_WORKSPACE_FILES_POLICY_HASH']=hashlib.sha256(file_policy.encode()).hexdigest()
   if name in ['api','worker']:
    env['PLATFORM_WORKSPACE_MESSAGING_USER']=env['PLATFORM_CHAT_MQ_USER']
    env['PLATFORM_WORKSPACE_MESSAGING_PASSWORD_FILE']=env['PLATFORM_CHAT_MQ_PASSWORD_FILE']
    service['secrets']=[s for s in service['secrets'] if s['source']!='mq_password']
   if name=='worker':
    env['PLATFORM_WORKSPACE_CONTROL_KEY_ID']='r3-control-1';env['PLATFORM_WORKSPACE_CONTROL_RUNNER_KEY_ID']='r3-runner-1'
    service['networks']['model-egress']=None
   if name=='api':env['PLATFORM_IDENTITY_ORIGINS']='http://agentplatform.lailihao.cloud,http://124.222.55.140';env['PLATFORM_IDENTITY_SECURE_COOKIE']='false'
   if name=='runner':
    env.update(PLATFORM_WORKSPACE_RUNNER_WORKSPACE_ROOT=str(ROOT/'workspaces'),PLATFORM_WORKSPACE_RUNNER_MOUNT_UUID=mount['uuid'],PLATFORM_WORKSPACE_RUNNER_QUOTA_DEVICE=loop,PLATFORM_WORKSPACE_RUNNER_CONTROL_KEY_ID='r3-control-1',PLATFORM_WORKSPACE_RUNNER_KEY_ID='r3-runner-1')
    service['volumes']=[{'type':'bind','source':'/var/run/docker.sock','target':'/var/run/docker.sock','read_only':True},{'type':'bind','source':str(attestation),'target':'/run/fixture/runtime.json','read_only':True}]
    service['group_add']=[str(Path('/var/run/docker.sock').stat().st_gid)]
  if name=='gateway':service['ports']=[{'target':8080,'published':'80','host_ip':'0.0.0.0','protocol':'tcp'}];service.pop('volumes',None)
  if name=='operator':
   service['environment'].update(PLATFORM_STORAGE_USER='ap_migration',PLATFORM_STORAGE_JDBC_URL='jdbc:postgresql://postgres:5432/agentplatform?connectTimeout=2&socketTimeout=5')
  config['services'][name]=service
 # Middleware identities stay unchanged; only receipted caps change during activation.
 caps={'postgres':192,'rabbitmq':256,'redis':16,'master':112,'volume':96,'filer':96,'s3':224}
 app=sum(int(config['services'][k]['mem_limit']) for k in ['api','worker','runner','gateway'])
 assert app+sum(caps.values())*1024**2+(512+128+64)*1024**2<=2887*1024**2
 (a.folder/'compose.json').write_text(json.dumps(config,indent=2))
 (a.folder/'source-containers.private.json').write_text(json.dumps(rows,indent=2))
 record={'status':'STAGED_NOT_RUNNING','source':a.source,'apiImage':a.api_image,'gatewayImage':a.gateway_image,'templateSha256':sha(a.template),'composeSha256':sha(a.folder/'compose.json'),'middlewareCapsMiB':caps,'productionUuid':mount['uuid'],'loop':loop,'createdAt':time.time(),'modelRequests':0}
 (a.folder/'stage.json').write_text(json.dumps(record,indent=2))
 print(json.dumps(record))
if __name__=='__main__':main()
