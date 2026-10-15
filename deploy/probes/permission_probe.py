"""Inspect actual new-project privileges; never serialize container Env or secret values."""
import json,pathlib,subprocess
root=pathlib.Path('/srv/agentplatform');rows=[]
ids=subprocess.check_output(['docker','ps','-q']).decode().split()
for container in ids:
 data=json.loads(subprocess.check_output(['docker','inspect',container]))[0]
 labels=data['Config'].get('Labels') or {};project=labels.get('com.docker.compose.project')
 if project not in ['agentplatform-app','agentplatform-env']:continue
 cfg=data['HostConfig'];name=data['Name'].lstrip('/');user=data['Config']['User']
 assert user and user.split(':')[0] not in ['0','root'],name
 assert not cfg['Privileged'] and 'ALL' in cfg['CapDrop'],name
 assert any(x.startswith('no-new-privileges') for x in cfg['SecurityOpt']),name
 assert cfg['Memory']>0 and cfg['PidsLimit']>0,name
 assert cfg['LogConfig']['Config'].get('max-size')=='10m' and cfg['LogConfig']['Config'].get('max-file')=='3',name
 mounts=data['Mounts']
 if labels.get('com.docker.compose.service')=='s3':
  assert '-iam=false' in ' '.join(data['Config'].get('Cmd') or []),'S3 advanced IAM/STS must stay disabled'
  assert any(m['Destination']=='/run/secrets/s3_kek' and not m['RW'] for m in mounts),'KEK wrapping secret must be readonly'
 assert all(m['Destination'] not in ['/var/run/docker.sock','/','/home'] for m in mounts),name
 if project=='agentplatform-app':assert cfg['ReadonlyRootfs'],name
 if 'runner' in name or 'egress' in name:
  assert not any(m['Destination'].startswith('/run/secrets/pg_') for m in mounts),name
 if name!='agentplatform-app-gateway-1':assert not cfg.get('PortBindings'),name
 rows.append({'name':name,'user':user,'memoryCapBytes':cfg['Memory'],'pidsLimit':cfg['PidsLimit'],'readOnlyRoot':cfg['ReadonlyRootfs'],'status':'PASS'})
assert len(rows)==12
(root/'reports/permissions.json').write_text(json.dumps(rows,indent=2))
print('Actual12-service privilege/resource/network scan:PASS')
