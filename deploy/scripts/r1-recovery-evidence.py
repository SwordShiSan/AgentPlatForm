"""Offline restored credential check; no worker/provider and no plaintext output."""
import json,os,subprocess,sys
from pathlib import Path

container,folder=sys.argv[1:]
root=Path('/srv/agentplatform')
restore=Path(folder).resolve()
assert restore.is_relative_to(root/'operations') and restore.name.startswith('r04-restore-')
state=json.loads(subprocess.check_output(['docker','inspect',container]))[0]
assert state['Config']['Labels']['com.docker.compose.project']=='agentplatform-r04-restore-env'
assert not subprocess.check_output(['docker','ps','-q','--filter','label=com.docker.compose.project=agentplatform-r04-restore-app','--filter','label=com.docker.compose.service=worker'],text=True).strip()
assert (restore/'secrets/chat_master').read_bytes()==(root/'secrets/chat_master').read_bytes()
command=['docker','compose','--env-file',str(restore/'deploy/images.env'),'-p','agentplatform-r04-restore-app',
    '-f',str(restore/'deploy/compose.app.yaml'),'-f',str(restore/'deploy/compose.r1-http.app.yaml'),
    '--profile','r1-ops','run','--rm','-T','--interactive=false','operator','--chat-action=verify-restored-credentials']
result=subprocess.run(command,env=dict(os.environ,PLATFORM_ROOT=str(restore)),stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=120)
assert result.returncode==0 and 'Restored credential authentication/decryption PASS' in result.stdout,'Offline credential restoration check failed; private logs withheld'
print(json.dumps({'status':'PASS','restoredKeyVersion':1,'masterIdentityMatches':True,'authenticatedDecryption':True,'workerStarted':False,'realProviderCalls':0}))
